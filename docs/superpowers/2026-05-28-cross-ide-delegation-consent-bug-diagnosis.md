# Cross-IDE Delegation — "Allow once" consent failure: root-cause diagnosis

Date: 2026-05-28 · Branch: `feature/cross-ide-delegation` · Platform reported: **Windows** (dev is macOS — see Bug C for why it only fails on Windows)

## Reported symptoms
1. IDE B open with inbound delegation unchecked → IDE A's `delegation(action="list_targets")` reported it as `"closed"`.
2. The "Allow once" consent popup appeared in IDE B, but clicking it did nothing; IDE A failed with `DelegationTargetNotReachable`.
3. (`glob_files` blocked an out-of-project Windows path — expected behavior, not a bug.)

## Root causes (five, A–E)

### A — `list_targets` mislabels running-but-inbound-off as `"closed"`
`DelegationTool.kt` `defaultRecentsProvider` computes status by probing **only the delegation socket** (`DelegationPaths.socketFor`):
```
!exists -> missing ; ping(socketFor) != null -> running ; else -> closed
```
An open IDE with inbound OFF has its **delegation socket unbound** (gated on the setting) but its **doorbell socket always bound**. The probe never rings the doorbell, so "running but inbound-off" collapses into `"closed"` — indistinguishable from a dead IDE. (This is also the agent's feedback request: distinguish *reachable* from merely *known*.)

**Fix:** also probe `doorbellSocketFor`; add a new status `"available"` = doorbell bound, delegation socket not (a send will ring the doorbell for consent).

### B — "Allow once" does nothing (PRIMARY; all platforms)
`DelegationInboundConsentDialog` is `IdeModalityType.MODELESS` (correct — it must not hijack the user's work). But `DelegationDoorbellService.showDialogAndApply` reads the result like a modal dialog:
```
val choice = withContext(EDT) { val dlg = ...; dlg.show(); dlg.choice }   // show() is NON-BLOCKING for modeless
applyConsent(knock, choice, ...)   // choice == default CANCEL, before the user clicks
```
For a modeless dialog `show()` returns immediately, so `choice` is the default `CANCEL`. `applyConsent(CANCEL)` runs → `markDeclined`. The user's later click is never read.

`DialogModalityContractTest` Test 3 enshrined this as "correct" — it must be rewritten.

**Fix:** keep MODELESS; have the dialog report the user's actual choice via a callback fired from each `DialogWrapperAction` and `doCancelAction`; `showDialogAndApply` awaits that (suspends the coroutine, not the EDT) before `applyConsent`.

### C — Windows-only: why the error was `TargetNotReachable` not `Declined`
`ProjectIdentifier.compute()` hashes the **raw, un-normalized** path string. IDE A derives the target agent dir from `picked.path.toString()` (`Path.toString()` → **backslashes** on Windows); IDE B writes the declined marker via `ProjectIdentifier.agentDir(project.basePath)` (IntelliJ `project.basePath` → **forward slashes**). Different strings → different SHA-256 → different `pending-delegation/` dirs → IDE A never sees IDE B's `markDeclined` → 90 s poll timeout → `TargetNotReachable`.

The doorbell *socket* matched because `DelegationPaths.socketFor` normalizes (`toAbsolutePath().normalize()`); only the file-based pending/declined layer diverges. On macOS every path is forward-slash, so it's invisible in dev.

**Fix (user-chosen: at the delegation boundary, NOT in ProjectIdentifier — avoids orphaning existing on-disk data):** normalize both call sites to system-independent (forward-slash) form via a shared `DelegationPaths.projectKey()` before `ProjectIdentifier.agentDir`.

### D — Latent: preauth recorded after bind
`applyConsent` ALLOW_ONCE does `startTransient()` then `recordPreauth(nonce)`. Once B is fixed and the bind actually happens, IDE A's poll can detect the bound socket and fire its `Connect` before `recordPreauth` runs → `consumePreauth` returns false → IDE B falls to the redundant Accept dialog.

**Fix:** `recordPreauth(nonce)` BEFORE `startTransient()`.

### E — Delegated session not surfaced in IDE B (expectation gap)
User expected consent to "open the agent tab and start the delegated work." `startDelegatedSession` → `executeTask` runs the loop but does not activate IDE B's agent tool window or switch the webview to the delegated session. Verify after B; add minimal surfacing if headless.

## Why no test caught B/C/D
- B: `DelegationDoorbellServiceTest` drives `applyConsent(…, ALLOW_ONCE)` directly via a seam — downstream of the broken synchronous `.choice` read. The contract test (Test 3) pinned the wrong invariant.
- C: macOS-only dev; forward-slash paths mask the separator divergence.
- D: race window only opens once B binds the socket.

## Fix order (TDD)
C (pure helper) → A (pure status fn) → D (applyConsent order, MockK verifyOrder) → B (await + rewrite contract test) → E (verify/surface).
