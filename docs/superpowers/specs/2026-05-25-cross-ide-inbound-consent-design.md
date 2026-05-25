# Plan 6 — On-Demand Inbound Consent ("Doorbell")

**Status:** Design approved · ready for implementation plan
**Branch:** `feature/cross-ide-delegation` @ `3c906ae96`
**Worktree:** `.worktrees/cross-ide/`
**Builds on:** the cross-IDE delegation v1 (Plans 0–4) + the Plan 5.x meta-tool / picker / diagnostics work.

## 1. Problem

When IDE A delegates to IDE B and IDE B has the inbound setting OFF, the delegation cannot succeed by any path:

- IDE B's delegation socket is never bound, so it pings as `closed` in the picker — indistinguishable from "not running".
- The "Delegate" (OK) button is guarded to `RUNNING` only, so it silently no-ops on `closed` entries.
- "Launch & Delegate" spawns the launcher, which (if IDE B is already running) just focuses the window; the inbound socket still doesn't bind; `AutoLaunchPoller` times out after 90 s.

Plan 5.4 improved the *messaging* for this dead-end but did not change the outcome. This plan makes the delegation *succeed* via an explicit, user-consented opt-in.

## 2. Goal

Let IDE A delegate to an inbound-off IDE B by raising a consent prompt **in IDE B** that offers: **Allow once**, **Allow always**, or **Cancel**. Cover both:

- **Fresh-launch:** IDE B is closed → IDE A launches it → IDE B prompts after startup/indexing.
- **Already-running:** IDE B is open with inbound off → IDE B prompts live.

## 3. Non-goals

- No change to the delegation wire protocol for actual work (Connect / Result / Question / Answer / continuity all unchanged).
- No remote / cross-machine / cross-user support (still local-only, same-user).
- No bypass of consent — IDE B's human always approves; nothing auto-accepts work that wasn't explicitly allowed.
- The doorbell never starts a session, accepts work, or exposes any capability beyond raising a consent dialog.

## 4. Core principle: separate the doorbell from the door

| | Delegation socket (the door) | Doorbell socket (the bell) |
|---|---|---|
| Path | `DelegationPaths.socketFor(project)` → `<hash>.sock` (existing) | `DelegationPaths.doorbellSocketFor(project)` → `<hash>.doorbell.sock` (new) |
| Bound when | inbound setting ON (or transient grant) | **always**, for every open project, on plugin load |
| Accepts | Connect, Result, Question, Answer, FetchTranscript, ChannelResume, UserTurn, Heartbeat | **only** `Knock` |
| Can start a session? | yes | **no** — raises a consent dialog and nothing else |

The doorbell is a strict, single-purpose listener. Its existence does not weaken the inbound opt-in: no work flows until the user clicks Allow and the *delegation* socket binds.

## 5. Protocol additions (`core/delegation/DelegationProtocol.kt`)

```kotlin
/** Rung on the doorbell socket to ask an inbound-disabled IDE to raise a consent prompt. */
data class Knock(
    val delegatorIde: String,
    val delegatorRepo: String,
    val delegatorSessionId: String,
    val requestPreview: String,   // truncated (~280 chars) for the dialog; full request travels via Connect later
    val nonce: String,            // dedupe key shared with the pending-file + the eventual Connect
) : DelegationMessage()

/** Doorbell's immediate reply so IDE A knows the bell was heard (NOT that work was accepted). */
data class KnockAck(
    val nonce: String,
    val outcome: KnockOutcome,    // RINGING (dialog shown) | DUPLICATE (already showing for this nonce)
) : DelegationMessage()

enum class KnockOutcome { RINGING, DUPLICATE }
```

`Connect` gains one optional field:

```kotlin
data class Connect(
    // … existing fields …
    val preauthNonce: String? = null,   // when set + matches a consented nonce, IDE B skips the Accept dialog
)
```

Default `null` keeps every existing caller / serialized message compatible.

## 6. Pending-request file

Path: `<IDE-B agent dir>/pending-delegation/<nonce>.json` (under IDE B's per-project agent storage, reusing the existing project-dir resolution).

```json
{
  "delegatorIde": "ide-12345",
  "delegatorRepo": "backend-service",
  "delegatorSessionId": "…",
  "requestPreview": "…",
  "nonce": "uuid",
  "createdAt": 1716600000000
}
```

- TTL: 5 minutes. IDE B ignores + deletes files older than TTL on read.
- Written by IDE A **before** launching IDE B (covers the fresh-launch race where IDE B isn't up to receive a knock).
- Read by IDE B's startup activity after `DumbService` smart mode is reached.
- Deduped against the live knock by `nonce` — whichever path fires first wins; the other is suppressed.

## 7. Consent dialog (`agent/delegation/ui/DelegationInboundConsentDialog`)

Merges the opt-in choice with the per-delegation accept gate (no double prompt):

```
Project "backend-service" wants to delegate a task to this project's
Workflow agent:

    "<requestPreview>"

Inbound delegation is disabled for this project.

[ Allow once ]   [ Allow always ]   [ Cancel ]
```

- **Allow once** → `DelegationInboundService.startTransient()` binds the delegation socket WITHOUT persisting the setting; records `preauthNonce`; the socket unbinds when the delegated session terminates (and is not rebound on next launch).
- **Allow always** → set `PluginSettings.enableInboundCrossIdeDelegation = true` (fires `CrossIdeDelegationSettingsListener.inboundSettingChanged(true)` → existing `start()`); records `preauthNonce`.
- **Cancel** → write a `pending-delegation/<nonce>.declined` marker so IDE A's poller bails early (see §8.4); delete the pending request file.

Wording is provisional; copy can be refined during implementation.

## 8. Flows

### 8.1 IDE A — `DelegationOutboundService.send()` (delegation socket unreachable)

```
1. ping(socketFor(B))  →  reachable?
     yes → existing flow (Connect → Accept dialog → work). DONE.
     no  → continue:
2. nonce = UUID
3. write pending-delegation/<nonce>.json into B's agent dir
4. ring B's doorbell:  DelegationClient.knock(doorbellSocketFor(B), Knock(..., nonce))
     KnockAck(RINGING|DUPLICATE) → B is running; dialog is up.  (already-running case)
     no answer / connect refused → B not running:
         spawn launcher (existing ProcessSpawner path). (fresh-launch case)
5. AutoLaunchPoller waits for socketFor(B) to bind (only happens after Allow),
   OR returns early when it sees pending-delegation/<nonce>.declined (see §8.4).
6. socket binds → Connect(preauthNonce = nonce) → B matches nonce, skips Accept → work proceeds.
```

### 8.4 Cancel signalling (IDE B → IDE A)

IDE A is already polling for IDE B's delegation socket via `AutoLaunchPoller`. To let Cancel short-circuit that wait without a new live channel, IDE B writes `pending-delegation/<nonce>.declined` (empty marker) into its own agent dir — the same directory IDE A wrote the request into, so IDE A knows the path. The poll loop checks for the declined marker between socket pings; on seeing it, it stops with a `Declined` outcome and the tool surfaces `DelegationRejected(reason="inbound_consent_declined")`. IDE B deletes both the request file and the marker after a short grace period (or on next startup scan).

### 8.2 IDE B — already running, inbound off

```
Doorbell receives Knock →
  dedupe by nonce (ignore if a dialog for this nonce is already showing) →
  raise DelegationInboundConsentDialog on EDT →
    Allow once   → startTransient() + record nonce
    Allow always → persist setting (listener binds) + record nonce
    Cancel       → signal IDE A declined
```

### 8.3 IDE B — fresh launch

```
DelegationInboundDoorbellStartupActivity (runs for every project on open) →
  always bind the doorbell socket →
  after DumbService smart mode: scan pending-delegation/ for non-expired files →
    for each (deduped by nonce against any live knock already handled):
      raise the same consent dialog → same three outcomes
```

## 9. Transient bind lifecycle (Allow once)

- `DelegationInboundService.startTransient()` binds the delegation socket exactly like `start()` but sets an internal `transient = true` flag and does NOT touch settings.
- When the delegated session that consumed the pre-auth terminates (COMPLETED / CANCELED / FAILED), if `transient` and no other active delegated sessions remain, call `stop()` to unbind. State returns to "inbound off, only the doorbell listening."
- On IDE restart, a transient grant does not persist — the delegation socket stays unbound until another consent or a permanent setting.

## 10. Security & abuse considerations

- **Doorbell can only raise a dialog.** No session, no file access, no work. Worst case a local process spams consent popups.
- **Popup spam mitigation:** dedupe by `nonce`; suppress a new knock while a consent dialog for the same `(delegatorSessionId)` is already showing; ignore knocks beyond a small rate (e.g. 1 dialog per delegator per 10 s).
- **Local-only:** UDS under `~/.workflow-orchestrator/ipc/` (0700). Same trust boundary as the existing delegation socket.
- **TTL on pending files** prevents a stale request from prompting days later.
- **Pre-auth nonce is single-use:** consumed by the first matching Connect; cleared after.

## 11. Files

### New
- `agent/.../delegation/DelegationDoorbellService.kt` — binds the doorbell socket, handles `Knock`, raises the consent dialog (dedupe/rate-limit here).
- `agent/.../delegation/DelegationDoorbellStartupActivity.kt` — `ProjectActivity` that always starts the doorbell + scans pending files after smart mode.
- `agent/.../delegation/ui/DelegationInboundConsentDialog.kt` — the 3-button dialog.
- `agent/.../delegation/PendingDelegationStore.kt` — read/write/expire `pending-delegation/<nonce>.json`.

### Modified
- `core/.../delegation/DelegationPaths.kt` — add `doorbellSocketFor(project)`.
- `core/.../delegation/DelegationProtocol.kt` — add `Knock`, `KnockAck`, `KnockOutcome`; add `Connect.preauthNonce`.
- `core/.../delegation/DelegationClient.kt` — add `knock(doorbellPath, Knock): KnockAck?`.
- `core/.../delegation/DelegationServer.kt` — generalize so it can also back the doorbell (or a thin doorbell variant) handling only `Knock`.
- `agent/.../delegation/DelegationInboundService.kt` — add `startTransient()` + `transient` flag + teardown hook; `handleConnect` honors `preauthNonce` to skip the Accept dialog; expose a `recordPreauth(nonce)`.
- `agent/.../delegation/DelegationOutboundService.kt` — the §8.1 flow: write pending file, ring doorbell, then launch-or-wait; send `Connect(preauthNonce)`.
- `agent/.../delegation/ui/DelegationPicker.kt` — when the user picks an inbound-off / closed target, route through the knock-and-consent flow instead of the current dead-end timeout (the Launch & Delegate button becomes "Request & Delegate" for closed targets, conceptually).
- `agent/.../AgentService.kt` — wire doorbell teardown into session-end for the transient case.

### Tests
- `PendingDelegationStoreTest` — write / read / TTL-expiry / nonce dedupe.
- `DelegationDoorbellServiceTest` — Knock raises dialog (mocked), dedupe, rate-limit, doorbell never starts a session.
- `DelegationConsentFlowTest` — Allow once → transient bind + teardown; Allow always → persists setting; Cancel → declined signal.
- `DelegationPreauthConnectTest` — Connect with matching nonce skips the Accept dialog; non-matching falls back to the normal Accept dialog.
- Extend `DelegationE2ETest` with an inbound-off → consent → work happy path.

## 12. Edge cases

- **Both knock and pending-file fire** (IDE B launched AND reachable mid-startup) → nonce dedupe shows one dialog.
- **User clicks Cancel** → IDE A's poller must bail early (declined signal), not wait the full 90 s.
- **Allow once, session ends, second delegation arrives** → doorbell rings again; user consents again (correct — "once" means once).
- **IDE B closed during the consent wait** → IDE A's poller times out as today; Plan 5.4 messaging applies.
- **Multiple IDE A's knock the same IDE B** → separate nonces, separate dialogs (or queue); keep simple — show sequentially.
- **Doorbell socket stale file** on crash → reuse the existing socket-bind cleanup the delegation socket already does.

## 13. Rollout

- The doorbell binding is new always-on behavior. Gate behind nothing for now (it's harmless), but note it in the settings page help text: "Other IDEs can ask to delegate to this one; you'll be prompted to allow or decline."
- No version bump in this plan; ships in the next `cross-ide.N` smoke build after implementation.
