# `delegation` — extended notes

## Why this is a meta-tool

Eight verbs share one schema entry: `send`, `close`, `answer`, `fetch_transcript`,
`status`, `wait`, `list_targets`, and `list_handles`. They are bundled because every
action operates on the same anchor — a delegation *handle* (and the *channel* / retained
session it identifies) — and shares the same substrate: `DelegationOutboundService` over a
local Unix-domain-socket (UDS) IPC transport to another running IntelliJ instance. Splitting
them into eight sibling tools would scatter the channel-lifecycle and settings-gating
logic and add schema tokens *every iteration*, even though most sessions never delegate
at all.

This mirrors the `runtime_exec` / `jira` consolidation pattern: the action-enum
keeps the cost flat and presents "coordinate with another IDE" as one capability
surface — the natural mental model.

The tool is also **conditionally registered**: it only appears when
`PluginSettings.enableOutboundCrossIdeDelegation` is on, and it is re-registered
dynamically when that setting is toggled (no IDE restart). The settings gate is
re-checked once at the top of `execute()` so a setting flipped off mid-session
fails closed with `DelegationOutboundDisabled`.

## What "cross-IDE" actually means here

This is **local-only, same-machine, same-user** delegation. "Another IDE" means a
second IntelliJ process the same user is running on the same box, with a
*different repository* open. The two processes talk over a UDS socket whose path
is derived from the target project path (`DelegationPaths.socketFor(path)`); there
is no HTTP, no TCP, no network egress.

Explicit v1 non-goals (do not suggest these as fixes): cross-machine,
cross-user, multi-hop (B delegating onward to C), per-delegation tool
restrictions, encryption, and non-plugin IDEs.

Continuation **after** the remote session completes is **in** scope (added in the
2026-05-30 campaign): reusing a handle with `send` resumes the persisted remote
session within a ~30 min retention window. It is *not* a non-goal — earlier docs that
implied "follow-ups only work on a still-live channel" are superseded.

Despite being localhost IPC, the blast-radius classification is `NETWORK`, not
`AGENT_CONTROL`: the call crosses the process boundary and causes *another user's
IDE* to pop a consent dialog and run an agent task. That is real outbound effect —
`AGENT_CONTROL` is reserved for tools with "zero blast radius outside the agent
loop" (`use_skill`, `task_update`), which delegation plainly is not.

## The async-result model is the part the LLM gets wrong

`send` does **not** return the remote agent's work. It returns immediately with a
one-line header:

```json
{"handle":"<id>","status":"running","repo":"<targetRepoName>"}
```

The actual result is delivered *later*, out of band, as a system **nudge**
injected into the delegator's loop when the remote session terminates:

```
[DELEGATION RESULT — <repo> (<shortId>)]
Status: COMPLETED | CANCELED | REJECTED | FAILED
Summary: ...
Files changed (N): ...
Branch: ... / Commit: ...
Duration: <n>s
```

The plumbing: at `send` time the tool captures the *delegator* session ID and
registers an `onResult` callback that calls
`AgentService.enqueueNudgeForSession(delegatorSessionId, nudge)`. The session ID
is captured into a local because the active session may change by the time the
remote finishes.

The consequence for the LLM: **do not poll.** After a `send`, the loop should
yield and continue other work; the nudge wakes it when the result is ready.
`fetch_transcript` exists for *on-demand inspection* of an in-flight or finished
session — not as a completion-polling mechanism. An agent that reads the
`status:"running"` header as if it were the result, or that spins on
`fetch_transcript`/`status`/`list_targets` waiting for completion, is misusing the tool.

The async result is also robust to an **idle delegator**: the normal post-`send`
state is idle (the LLM finishes its turn after delegating), and a result that lands
then is persisted and **auto-wakes** the session with a `[DELEGATION RESULT —
AUTO-RESUMED]` message — the same auto-wake mechanism background-process completion
uses. (Previously an idle-arrival result was silently dropped.)

### When you DO need the result inline: `wait`

If the next step genuinely depends on the delegation's outcome and there's nothing
useful to do meanwhile, **attach** with `wait` instead of polling:

`delegation(action="wait", handle=…, timeout_seconds=…)` suspends the current turn
until the remote session delivers its `Result` or raises a `Question`, and returns it
inline — the same `[DELEGATION RESULT …]` block the nudge would carry, or the question
text + `question_id` to answer. `timeout_seconds` is an **integer, allowed range
5–1800 (default 300); values outside that range are clamped and the clamp is reported
in the result**. Mechanically this is a one-shot deferred (`pendingResultWaiters`)
completed by the reader loop; the async nudge is suppressed for that handle while a
wait is pending, with a `finally` safety-net so a channel close can't leave `wait`
hanging. `DelegationTool.timeoutMs = Long.MAX_VALUE` so the loop's 120 s per-tool
timeout never truncates a legitimate wait — the action is self-bounded by its own
`timeout_seconds`.

**A wait timeout is not a failure.** It returns a "still running" note; the async
auto-delivery still fires when the remote finishes. On a `Question` outcome, call
`answer` then `wait` again.

### When you only need liveness: `status`

`delegation(action="status", handle=…)` is a cheap check that reads the single
handle-state source of truth (the same lookup `send`-continuation and `answer` use):

- `active` — the delegation is still running (with its last-seen state). No TTL field.
- `closed` — it finished, reported with the **terminal** `last_state`
  (`COMPLETED` / `FAILED` / `CANCELED` / `REJECTED`) and
  **`retention_expires_in_seconds`** — the exact number of seconds remaining before the
  handle is pruned (30-minute window, floored at 0). This lets the agent decide
  urgently whether to fetch the transcript or start a continuation before the window
  closes. The field is omitted for old persisted entries loaded from disk before this
  field was added.
- `DelegationHandleNotFound` — the handle is unknown or its 30-minute retention window
  elapsed. No TTL field.

Use it for a one-off "is it done yet?" decision (wait vs fetch_transcript vs move on),
never as a tight poll loop.

## `send`: two branches on `handle`

`send` is the only action with branching behavior, keyed on whether `handle` is
present:

| Branch | Trigger | Behavior |
|---|---|---|
| Fresh send | no `handle` | `DelegationOutboundService.send` opens the target **picker**, runs the inbound-consent handshake on the chosen target, creates a new channel, and registers the result nudge. |
| Continuation | `handle` present | `sendContinuation` skips the picker *and* the Accept dialog and writes a fresh `UserTurn` onto the existing session — **even if that session already COMPLETED** (this requires the remote IDE to still be OPEN — only the session completed, not the IDE; a closed IDE returns `ide_b_not_running`). Within the ~30 min retention window it sources `bSessionId` + `targetPath` from the retained handle (the live maps are wiped on close), dials the target repo with `ChannelResume`, and the target repo **resurrects the persisted session** (its TASK RESUMPTION / `initiateTaskLoop` machinery), re-registers the channel, and resumes from where it left off. |

### `suggested_repo`: pre-selection hint, not a hard target

`suggested_repo` is a **picker pre-selection hint only** — it tells the picker which
target IDE to highlight by default, but it does NOT force or hard-select the target.
If the suggested IDE is unavailable or busy, or if the user simply chooses a different
target in the picker, **the user's picker selection wins**. The tool does not
silently force the suggested repo, and it does not fail hard if the suggestion cannot
be honoured. The success result narrates what actually happened: if the suggestion was
honoured it notes "(target {repo} as suggested)"; if the user picked a different target
it notes "(you suggested {suggested}; user selected {repo} via the picker)"; if no
suggestion was given it notes "(target {repo} selected via picker)".

The continuation branch is the correct way to send a *follow-up* to a delegation,
whether it is still running or has already finished. Opening a fresh `send` for a
follow-up spins up a new picker and a new remote session, losing the conversational
context the remote agent already has.

**This is the campaign's big new capability.** Continuation used to require a
still-live channel; reattach-after-completion (the delegating repo re-dials from the
retained handle, the target repo resurrects the persisted session) means a `COMPLETED`
delegation is no longer a dead end within the retention window — provided the remote
IDE is still OPEN (only the agent session completed, not the IDE process; a
fully-closed IDE returns `ide_b_not_running`). If the remote session is genuinely
gone or the IDE is closed, the continuation fails with a *specific* `DelegationExpired`
reason (see the taxonomy below) rather than a vague "expired".

## Reachability is ambiguous by construction

`DelegationTargetNotReachable` deliberately enumerates causes rather than
asserting one, because at the socket layer **"inbound delegation disabled" and
"IDE not running" are indistinguishable**. The single most common real cause is
the former: the target IDE is running fine but has not enabled "Accept incoming
delegations from other IDEs" in Settings → Tools → Workflow Orchestrator →
Cross-IDE Delegation. The error message lists causes in likelihood order and
tells the LLM to ask the user to verify the inbound setting *first* — because
that is the cause a user cannot diagnose from this side and the one a naive
"the other IDE must be closed" reading gets wrong.

This is also why `list_targets` reports a coarse `status` per target
(`running` / `available` / `closed` / `discovered` / `missing`) derived from a
**dual** socket probe: the work socket (`running`) then the always-bound doorbell
socket (`available` — IDE open but inbound delegation OFF, so a send rings its
doorbell for consent). It tells you a socket answered, not that the target will
accept silently. The same `TargetStatusResolver` probe now backs the **picker** too,
so the picker no longer shows a running-but-inbound-off IDE as "closed".

## Consent and the human-in-the-loop on `answer`

`answer(handle, question_id, answer)` forwards a reply to a clarifying-question
nudge from the remote session. Note the reply parameter is **`answer`**, not
`request`. It honors `PluginSettings.autoApproveDelegationAnswers`:

- **off (default-safe):** opens `DelegationAnswerConfirmDialog` showing the
  remote question, the proposed answer, and the target repo. The human can edit
  the answer or decline entirely (decline → error, nothing is sent).
- **on:** the answer is forwarded directly without interrupting the loop.

`answer` routes its existence check through the **same** retained-aware
`handleState` lookup `status`, `wait`, `fetch_transcript`, and `send`-continuation
use, so all five now agree about whether a handle exists, and it distinguishes
**three** failure shapes so recovery differs:

- `DelegationHandleClosed` — the handle is **closed-but-retained**: the remote
  session has already COMPLETED (you can see its terminal state). You can't answer a
  finished session. **Recovery: continue it with `send` (same handle resumes it), or
  read it with `fetch_transcript` — do not retry `answer`.**
- `DelegationHandleNotFound` — the handle is genuinely unknown or pruned (never
  existed, or its retention window elapsed). **Recovery: start a new `send`.**
- `DelegationWriteFailed` — the channel exists but the IPC write was rejected
  (channel shutting down). **Recovery: retry, or fall back to a new `send`.**

This three-way split is part of Fix A: before it, `status`, `send`-continuation, and
`answer` could give three contradictory answers about the same handle (one read
`retainedHandles`, another the wiped `handleToSessionId`, a third `activeChannels`).
The 2026-06-01 follow-up extended the agreement to `fetch_transcript` and
`send`-continuation's *type* mapping: an unknown handle now surfaces
`DelegationHandleNotFound` (not `DelegationExpired: handle_not_found`) on all five.

## `fetch_transcript`: path + bounded preview

`fetch_transcript` exports/locates `transcript-export.json` **on the target
IDE's filesystem** and returns its path, byte size, a token estimate, and the
first 2 KiB under `head`. If the file exceeds 2 KiB it appends a truncation
marker pointing the LLM at `read_file` on the path for the full content.

The path-plus-preview shape is deliberate: a long remote session can produce a
large transcript, and returning it inline would blow the context budget on every
inspection. The preview answers "what is this session doing?" cheaply; the full
read is opt-in. It reads the target repo's `api_conversation_history.json` **directly off the
shared filesystem** (Unix sockets ⇒ same host) using the channel's own
`localSessionId`, not an IPC round-trip — fixing the old "no conversation history on
disk" (wrong session id sent) and "handle_not_found ~83 s post-completion" (handle
torn down on result arrival) bugs. It **still works after completion**: `close()`
snapshots a `RetainedHandle` kept for `TRANSCRIPT_RETENTION_MILLIS` (~30 min), so the
transcript stays reachable in that window. Past it (or for a handle that never
existed) `fetch_transcript` routes its existence check through the same
`handleState` single-source-of-truth the other actions use and returns
**`DelegationHandleNotFound`** — consistent with `status` / `wait` / `answer` /
`send`-continuation. `DelegationExpired` is reserved here for the distinct case of a
**known** handle whose transcript is genuinely unreachable (not on disk yet, or an IO
read failed).

## `list_targets`: read-only, never throws

`list_targets` enumerates **candidate target IDEs and their inbound readiness** — the
same list the picker shows — without opening any UI. It does **not** list your active
delegation handles (use `list_handles` for that).

Status meanings:

- **`running`** — IDE is open AND inbound delegation is ON (the "Accept incoming
  delegations" setting is enabled). A `send` to this target connects directly, no
  consent prompt needed. The per-target `status_label` refines this into **`running
  (busy)`** vs **`running (idle)`** using an **advisory** busy hint (machine field
  `busy`: `true` / `false` / `null`) that rides back on the liveness probe — `busy:
  true` means the target's agent tab is already running another task, so a `send`
  may be declined as `ide_b_busy` (or queued); `busy: false` means the tab is idle
  and ready. This is a **point-in-time (TOCTOU) snapshot** — the target could pick up
  work between this probe and your `send` — and `busy: null` means UNKNOWN (an older
  peer that predates the hint), which renders as plain `running`. Prefer an idle
  target when one is offered.
- **`available`** — IDE is open but inbound delegation is OFF. A `send` rings its
  doorbell and the **user on that IDE is asked to consent** before the remote agent
  starts.
- **`closed`** — path is in recents but the IDE is not running this project.
- **`missing`** — path no longer exists on disk.
- **`discovered`** — socket-glob found a reachable socket whose project path is not
  in recents (running, but not a recent project on this machine).

It is the union of:

1. **Recents** — `RecentProjectsManagerBase.getRecentPaths()`, each path
   UDS-socket-probed to classify `running` / `available` / `closed` / `missing`.
2. **Discovery** — `SocketGlobDiscovery` finds reachable sockets whose project
   path is *not* already in recents (`discovered`).

Both providers are wrapped in try/catch and degrade to an empty list on failure —
`list_targets` never errors. Its purpose is situational awareness before a blind
`send`: pick a `repoName` from the result to pass as `suggested_repo`.

> Note: the production recents provider does not currently expose per-path
> timestamps, so `lastOpened` is `null` for recents entries even though the field
> is present in the schema. Treat a present-but-null `lastOpened` as "unknown",
> not "never opened".

## `list_handles`: read-only, recover + correlate your own delegations

`list_handles` takes **no** handle and enumerates every delegation handle THIS
delegating repo holds for the **active** session — both still-active channels and
closed-but-retained snapshots — via
`DelegationOutboundService.handlesForSession(currentSessionId)`. Active handles
are scoped via `handleToSessionId` (the same per-session grouping `send` /
`cancelAllForSession` / `persistHandlesForSession` use); retained handles are
scoped via the `delegatorSessionId` captured into `RetainedHandle` at `close()`.

Each row's `last_state` comes from the same `handleState` single-source-of-truth
that `status` / `answer` / `send`-continuation use — never a parallel computation.
Result shape:

```json
{"handles":[{"handle":"<id>","repo":"<repoName>","project_path":"<path>","b_session_id":"<remote-sid|null>","last_state":"RUNNING","status":"active"}]}
```

`status` mirrors the `status` action's vocabulary (`active` / `closed`). An empty
session returns a clear "no active or retained delegations in this session"
message, not an error. Use it to recover a handle you lost track of, to correlate
which delegation is which, or — after a busy decline (below) — to match the
in-flight delegator session id named in the `ide_b_busy` reason against your own
`b_session_id`s.

## Self-describing busy decline (`ide_b_busy:`)

When the target repo declines an incoming delegation because its agent tab is
busy, the reason is **self-describing**: it leads with `ide_b_busy:` and NAMES the
in-flight task the target is busy with, e.g.

```
ide_b_busy: agent tab is busy running session <sid> ('<title>'), delegated by <repo> session <delegatorSessionId>; the user did not accept the takeover within Ns
```

When the in-flight task is itself a delegated session, the reason echoes ITS
delegator session id — so the delegating repo can recognize the blocker as **its own
earlier task** (match it against `list_handles`' `b_session_id`, or the delegator
session id it sent with that task). When the in-flight task is a local
(non-delegated) session, the delegator clause is omitted and only the local
session/title is named. When the in-flight descriptor is unavailable, the reason
falls back to a generic `ide_b_busy:` string. The trailing "the user did not accept
the takeover within Ns" (N = the real `ACCEPT_WINDOW_MS`) is the actual mechanism
(busy tab → top-bar Start prompt → not clicked in time), not a "you ignored it for
Ns" framing. `ide_b_busy` is a clean peer reason token, never conflated with
`session_closed:` / `declined_timeout:`.

## Channel limit and hygiene

There is a cap of `DelegationOutboundService.MAX_CHANNELS` concurrent open
channels; exceeding it returns `DelegationLimitReached`. `close` is idempotent
(`{"closed":false,...}` when already closed is still a success), so it is safe to
call defensively after a result has been consumed to free a slot. The teardown
path also releases the channel automatically when a remote session ends, but
explicit `close` is the way to reclaim a slot for a session you're done with
before the timeout prunes it.

## Handle lifecycle / retention (30 min after close)

Closing or completing a delegation does **not** immediately invalidate its handle.
`close()` snapshots a `RetainedHandle` (`bSessionId`, `targetPath`, `repoName`,
terminal `lastState`, `capturedAt` close-timestamp) into `retainedHandles`, kept for
exactly `TRANSCRIPT_RETENTION_MILLIS` = **30 minutes** and swept by
`pruneRetainedHandles()`.

Within that retention window, all the handle-reading actions keep working and
**agree** on the handle's existence (a closed-but-retained handle):

- `status` → `closed` with the terminal `last_state` **and
  `retention_expires_in_seconds`** — how many seconds remain before the handle is
  pruned. Use this to decide how urgently to call `fetch_transcript` or start a
  continuation. The field is omitted for old persisted entries loaded from disk
  before this field was added.
- `fetch_transcript` → the persisted transcript (read off disk).
- `send`-continuation → resurrects and resumes the persisted remote session (requires the remote IDE to still be OPEN; a closed IDE returns `ide_b_not_running`).
- `answer` → `DelegationHandleClosed` (you can't answer a finished session; resume
  it with `send` or read it with `fetch_transcript`).
- `wait` → reports `already_completed`.

After the window elapses (or for a handle that never existed) the handle is uniformly
**`DelegationHandleNotFound`** across **all five** handle-reading actions —
`status` / `wait` / `answer` / `fetch_transcript` / `send`-continuation — with the
same message. They share one single-source-of-truth existence check
(`DelegationOutboundService.handleState`), so they can never disagree about whether a
handle is known. (The 2026-06-01 consistency fix folded `fetch_transcript` and
`send`-continuation into this agreement: both previously reported the unknown case as
`DelegationExpired: handle_not_found` while the others said `DelegationHandleNotFound`.)

`DelegationExpired` is now reserved for a genuinely **distinct, handle-gone** condition on a
**known** handle: a `send`-continuation whose resurrection/reattach fails because the remote
session is genuinely unreachable (`ide_b_not_running` / `session_closed` / `session_not_found` /
`resume_failed`), or a `fetch_transcript` whose persisted transcript is unreachable
(not on disk yet / IO error). It is **not** used for an unknown/pruned handle, and it is **not**
used for a busy target: a continuation that fails because the target is `ide_b_busy` is a
TRANSIENT, RETRYABLE **`DelegationRejected`** (the handle is still valid; the target is just
occupied) — uniform with the fresh-send busy path. `DelegationExpired` means the handle is gone.

## Cold launch of a closed target

A `closed` target (in recents, IDE not running) can still receive a delegation: the
picker offers **Launch & delegate**. The delegating repo writes a `pending-delegation/<nonce>.json`
into the target repo's agent dir, launches the target IDE, and after the target IDE
finishes **indexing** its post-startup activity replays the pending request and raises
the consent dialog. Fix D decoupled the pending file's lifetime from the delegator's
in-memory wait and widened the replay TTL so a large-repo index that runs longer than
the old 90 s / 5 min budgets no longer deletes the request before the target can read
it — the launch request now stays
valid ~30 min. Practical consequence for the LLM: on a cold launch, the consent
dialog may take a while to appear (until indexing completes); that delay is expected,
not a failure.

## Error taxonomy

The first token of every error is the category, on purpose: branch on the prefix,
don't retry blindly.

| Category | Action(s) | Cause | Recovery |
|---|---|---|---|
| `DelegationOutboundDisabled` | all | outbound delegation off in Settings | Ask the user to enable it; nothing to retry. |
| `DelegationUserCanceledPicker` | send | user dismissed the picker | Don't auto-retry; confirm intent with the user. |
| `DelegationTargetNotReachable` | send | socket unreachable — usually inbound disabled on target | Ask user to enable "Accept incoming delegations" on the target IDE. |
| `DelegationLimitReached` | send | `MAX_CHANNELS` already open | `close` an existing channel, then resend. |
| `DelegationDeclined` / `DelegationRejected` | send, send (continuation) | **RETRYABLE.** target user declined consent, or the target tab is busy (`ide_b_busy` / `declined_timeout: the agent tab is busy; the user did not accept the takeover within 55s`). On a continuation, `ide_b_busy` lands here too (NOT `DelegationExpired`) — the handle is still valid; the target is just occupied. | Surface to user; for a busy tab, retry shortly with the **same handle** (still valid). |
| `DelegationExpired` | send (continuation), fetch_transcript | **HANDLE GONE.** a **known** handle whose continuation/transcript is genuinely unreachable. On continuation the reason is specific: `session_closed` / `session_not_found` (remote session gone or pruned), `ide_b_not_running` (target IDE down), `resume_failed` (session locked/missing on disk). On fetch_transcript: the transcript is not on disk yet or an IO read failed. **Not** used for an unknown/pruned handle, and **not** for a busy target (that's `DelegationRejected`, retryable). | `ide_b_not_running` → reopen/send fresh; `session_not_found`/`resume_failed`/`session_closed` → fresh `send`; transcript-unreachable → retry shortly. |
| `DelegationHandleClosed` | answer | closed-but-retained: the session already COMPLETED | Continue it with `send` (same handle resumes it), or `fetch_transcript`; don't retry `answer`. |
| `DelegationHandleNotFound` | **status, wait, answer, fetch_transcript, send (continuation)** | handle unknown or its ~30 min retention elapsed. Consistent type **and** message across all five handle-reading actions (shared `handleState` SSOT). | Start a fresh `send`. |
| `DelegationWriteFailed` | send (continuation), answer | IPC write rejected | Retry, or fall back to a fresh `send`. |

## Counterfactual: dropping `delegation`

There is no `run_command` substitute the way there is for `runtime_exec`. An
agent in the delegating repo simply has no path to make work happen in a repo it
does not have open: it cannot open another project, cannot reach the target repo's
services, and `run_command` only sees the current working tree. Without this tool
the only fallback is to tell the human to switch windows and drive the other agent
by hand — which discards the three things the tool adds: the asynchronous hand-off
(the delegating repo keeps working), the inbound-consent gate (the target repo's
user opts in), and the transcript trail for auditing what the remote agent did.

## Drop-decision summary

Net verdict: **STRONG keep** for the tool overall.

- `send` is the entire feature — the only way to hand work across the IDE
  boundary, async and consent-gated by design; its continuation branch now resumes
  the same remote session even after completion (within retention).
- `answer` keeps a delegated session unblocked while preserving a human review
  step (unless the user opts into auto-approve).
- `fetch_transcript` is the only window into the remote session, with bounded
  token cost; works post-completion within retention.
- `status` is a cheap, retained-aware liveness check (active / closed+terminal /
  not-found) that lets the LLM branch without a transcript round-trip.
- `wait` lets the LLM synchronously attach when the next step depends on the result,
  preserving the no-failure-on-timeout + async-auto-delivery contract.
- `close` is cheap channel hygiene against the concurrent-channel cap.
- `list_targets` is side-effect-free situational awareness that avoids a blind
  send into a picker.
- `list_handles` enumerates the handles you already hold this session — recover a lost
  handle or correlate an `ide_b_busy` blocker against your own `b_session_id`.

All eight anchor on the same handle / retained-session substrate and IPC transport,
so they belong in one tool. The feature is off by default, so the schema cost is only
paid by users who opt in.
