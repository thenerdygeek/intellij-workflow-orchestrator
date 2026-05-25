# Network Connectivity Resilience — Design

**Date:** 2026-05-26
**Status:** Design — pending implementation plan
**Branch:** bugfix

## Problem

When a corporate laptop sleeps/locks and is then unlocked, the VPN tunnel takes time
(often tens of seconds to a couple of minutes) to re-establish. During that window every
piece of network I/O in the plugin fails:

- **Agent:** if the last view before lock was the approval gate and the user approves on
  unlock, the loop issues an LLM call into a dead tunnel. It retries on a *count-bounded*
  budget (`MAX_TIMEOUT_RETRIES = 4`, full-jitter backoff capped at 30s — `AgentLoop.kt:548-585`,
  `computeBackoffMs:604-613`) and then **fails the turn**, even though the request would have
  succeeded had it simply waited for the tunnel. It treats "transport is down" identically to
  "the server keeps erroring."
- **Pollers (Jira sprint, PR list, Bamboo builds, automation queue, insights):** `SmartPoller`
  reacts to errors by backing off *harder* (2× on exception vs 1.5× on no-change —
  `SmartPoller.kt:46-52`) and silently swallows failures. With no connectivity awareness, every
  poller independently fires requests into the dead tunnel and re-fails. After unlock, a stale
  backoff (potentially at its 5-minute cap) can delay fresh data right when the user wants it.

**Root cause:** there is no OS-level lifecycle awareness (no sleep/wake, lock/unlock, or
network-state listener) and no shared connectivity authority. The only existing signals are
`IdeFocusManager` focus and Swing tab visibility.

## Goals

1. Stop the agent from burning its retry budget (and failing) while genuinely offline.
2. Stop the pollers from hammering a dead tunnel and from sitting on stale backoff after wake.
3. Do it through one shared `:core` authority, not per-module patches — consistent with the
   plugin's "core interface → feature impl" architecture.

## Non-goals (YAGNI)

- **Per-host reachability map.** v1 uses a single coalesced global online/offline signal. The
  split-network case (corporate VPN down but cloud LLM reachable, or vice-versa) can produce a
  slightly wrong signal. Documented as a known limitation with a clear future extension path;
  not built now.
- **Auto-resume of the in-flight agent turn.** Per product decision, the agent **fails fast**
  on confirmed-offline and surfaces a manual "Retry now" affordance. No silent auto-resume of
  the failed turn.
- **OS sleep/wake native hooks.** Rejected in favor of a portable monotonic-clock-gap watchdog
  (the approach used by Chrome/VSCode/Slack).

## Behavioral decisions (confirmed with user)

| Decision | Choice |
|---|---|
| Agent behavior when confirmed offline | **Fail fast + manual "Retry now"** (resumes the same session) |
| Scope | **Shared `:core` service; both agent and pollers** |
| Detection mechanism | **Reactive failures + one active probe + clock-gap wake watchdog** (Approach A) |

## Architecture

### Component 1 — `NetworkStateService` (the authority, `:core`)

Single source of truth for "are we online." Application-level because the VPN tunnel is
per-machine, not per-project.

```kotlin
@Service(Service.Level.APP)
class NetworkStateService(private val cs: CoroutineScope) {   // platform-injected scope
    val state: StateFlow<NetworkState>                  // ONLINE | OFFLINE | RECONNECTING

    fun reportFailure(host: String, type: ErrorType)    // reactive input from any HTTP client
    fun reportSuccess(host: String)                     // a real request succeeded -> ONLINE

    suspend fun checkNow(host: String): NetworkState    // bounded fast probe (used by agent)
    suspend fun awaitOnline(timeoutMs: Long): Boolean   // pollers suspend here while OFFLINE
}

enum class NetworkState { ONLINE, OFFLINE, RECONNECTING }
```

**Important convention:** the service receives `cs: CoroutineScope` via constructor injection
(2024.1+ platform pattern). It must **not** allocate its own `CoroutineScope(SupervisorJob()+…)`
— the platform owns lifecycle and disposes it on IDE shutdown (`:core` CLAUDE.md).

Three detection inputs:

1. **Reactive** — any HTTP client that classifies an `ApiResult.Error` of type `NETWORK_ERROR`
   or `TIMEOUT` calls `reportFailure(host, type)`. Cheap, instant "something's wrong." A
   successful request calls `reportSuccess(host)` → ONLINE. (`ErrorType` already exists in
   `core/model/ApiResult.kt`.)

2. **Active probe** — once suspected offline, a single backoff loop (owned by `cs`) issues a
   lightweight reachability probe (short-timeout ~3s HEAD/GET) until it succeeds, then flips to
   ONLINE. This is the *only* thing that can discover reconnection while everything else is
   paused (solves the "silent network" problem — a paused system generates no signal). The
   probe uses a **dedicated OkHttpClient that bypasses `RetryInterceptor`** so the probe itself
   does not retry.
   - **Probe target:** the host of whatever request most recently failed (so the agent's
     `checkNow` probes the LLM host; a poller's path probes the Atlassian host). For the common
     case (VPN down → everything unreachable) any host is a valid proxy.

3. **Wake watchdog** — a coroutine (owned by `cs`) ticks every ~10s and compares elapsed
   wall-clock against expected. A jump materially larger than the tick interval means the machine
   slept → force `RECONNECTING`, fire an immediate probe, and reset all backoffs. Complemented by
   IntelliJ `ApplicationActivationListener` (window regained focus → opportunistic probe).
   - This catches the *time-gap* event itself, which is the actual trigger of the bug, and
     resets stale backoff timers so the user gets fresh data promptly on unlock.

### Component 2 — Agent integration (fail-fast)

At the retry seam (`AgentLoop.kt:1097-1099`), where `apiResult.type in TIMEOUT_ERRORS` is
checked, insert a branch **before** the retry budget is consumed:

```kotlin
val isTimeoutError = apiResult.type in TIMEOUT_ERRORS
if (apiResult.type in RETRYABLE_ERRORS && apiRetryCount < maxRetries) {
    if (isTimeoutError && networkState.checkNow(host) != NetworkState.ONLINE) {
        return makeFailed(error = "...", reason = FailureReason.OFFLINE, iterations = ...)
    }
    // ... existing L1-recycle / L2-escalation / delay+continue, UNCHANGED ...
}
```

- `checkNow(host)` is a bounded ~3s probe. **OFFLINE/RECONNECTING** → `makeFailed(reason =
  FailureReason.OFFLINE)` immediately — no budget burned, no ~2-minute churn.
- **ONLINE** → it was a transient blip; fall through to the existing retry machinery (genuine
  server-error handling is untouched).
- Add a new `FailureReason.OFFLINE` variant (`LoopResult.kt:5-12`) so the UI can render a
  distinct "you appear offline" card rather than a generic API error.
- Every successful API call calls `reportSuccess(host)`; every network failure calls
  `reportFailure(host, type)` — feeding the detector.

**Resume path (already exists):** failing a turn does not lose the session. State is persisted
atomically per chunk/tool-result, `promoteSteeringQueueOnFailure()` preserves pending steering
input (`AgentLoop.kt:2361-2374`), and `AgentService.resumeSession(sessionId)` reloads the
persisted conversation. The webview "Retry now" button calls `resumeSession(sessionId)` — a
one-call wire-up, no new plumbing.

### Component 3 — Poller integration (pause + staggered resume)

`SmartPoller` receives the network authority. At the top of each loop iteration
(`SmartPoller.kt:~42`, inside the existing `scope.launch { while (isActive) { … } }`):

```kotlin
while (isActive) {
    networkState.awaitOnline(timeoutMs)     // <-- suspends here while OFFLINE; no request fired
    try { val changed = action() ... }
    ...
}
```

- While OFFLINE the poller suspends on the StateFlow — no requests, no backoff churn, no log
  spam. Suspending is safe: the loop already runs inside a coroutine (`scope.launch`).
- When a poll's own request fails with a network error, it reports to the service; the next
  iteration then suspends.
- **Thundering-herd guard:** on OFFLINE→ONLINE, each poller waits a per-poller random jitter
  (0–baseInterval) before its first poll, so Jira/PR/Bamboo/automation/insights don't stampede
  the just-restored tunnel simultaneously.
- **Wake payoff:** the watchdog's immediate re-probe + backoff reset means a poller resumes
  with fresh data promptly on unlock instead of waiting out an accrued backoff cap.

## Data flow

```
                 reportFailure(host,type)         reportSuccess(host)
 HTTP clients  ───────────────────────────►  NetworkStateService  ◄───────────────  HTTP clients
 (agent + pollers)                              │   ▲        ▲
                                                │   │        │ wake watchdog (clock-gap)
                                  active probe ──┘   │        │ + ApplicationActivationListener
                                  (one backoff loop) │        │
                                                     │        │
                          state: StateFlow<NetworkState>
                                  │                     │
                 checkNow(host) ──┘                     └── awaitOnline(timeout)
                 (agent fail-fast)                          (pollers pause/resume)
```

## Error handling

- Probe never retries (bypasses `RetryInterceptor`) and is short-timeout; a failed probe simply
  keeps the state OFFLINE and schedules the next probe on backoff.
- `checkNow` is bounded — if the probe can't complete within its timeout it returns
  OFFLINE/RECONNECTING (fail-fast), never hangs the agent loop.
- `awaitOnline(timeout)` returns `false` on timeout so a poller can decide to attempt anyway
  rather than suspend forever (defensive against a stuck detector).
- Genuine server errors (5xx, rate-limit) are unaffected: `checkNow` returns ONLINE, so the
  existing retry/escalation paths run exactly as today.

## Testing strategy

Unit tests (no live network):

- `NetworkStateService`: reactive failure → OFFLINE; successful probe → ONLINE; probe failure →
  stays OFFLINE; `checkNow` bounded-timeout returns OFFLINE; `awaitOnline` suspends while OFFLINE
  and resumes on ONLINE; clock-gap injection (inject a fake clock) forces RECONNECTING + immediate
  probe + backoff reset.
- Agent: at the retry seam, OFFLINE → `makeFailed(reason = OFFLINE)` without incrementing
  `apiRetryCount`; ONLINE → existing retry path runs (counters advance). Inject a fake
  `NetworkStateService`.
- `SmartPoller`: OFFLINE gate suspends the loop (no `action()` calls); ONLINE resumes; per-poller
  jitter applied on transition. Use a `TestCoroutineScheduler`/virtual time.

## Known limitations / future extensions

- **Single coalesced global signal.** Split-network scenarios can mis-signal. Future: a per-host
  reachability map keyed by host, with `awaitOnline(host)`/`checkNow(host)` consulting per-host
  state. Defer until a concrete split-network complaint appears.
- **Probe target heuristic.** Using "most-recently-failed host" is pragmatic but could probe a
  host that is independently down while the rest of the network is fine. Acceptable for v1.

## Touch points (files)

| File | Change |
|---|---|
| `core/.../NetworkStateService.kt` (new) | The authority: state, probe loop, wake watchdog, APIs |
| `core/.../events` or settings | Register `@Service(Service.Level.APP)`; `ApplicationActivationListener` registration in plugin.xml |
| `core/http/*Interceptor`/client paths | Call `reportFailure`/`reportSuccess` on classified results |
| `core/polling/SmartPoller.kt` | Inject authority; `awaitOnline` gate; jittered resume |
| `agent/loop/AgentLoop.kt` | `checkNow` branch at retry seam; `reportSuccess`/`reportFailure` |
| `agent/.../LoopResult.kt` | New `FailureReason.OFFLINE` |
| Webview chat | "You appear offline" failure card + "Retry now" → `resumeSession` |
| `plugin.xml` | App-service + listener registration |
