---
name: project_passwordsafe_credential_attributes_username_trap
description: "TRAP/FIXED — Jira (any service) token change in Settings didn't persist; root cause was a PasswordSafe CredentialAttributes username mismatch, fixed by aligning it with the Credentials username"
metadata: 
  node_type: memory
  type: project
  originSessionId: 0bbf00fe-04d1-47fa-8172-0eba279e24c4
---

⚠ TRAP/FIXED 2026-06-15: A changed service token in Settings → Connections silently failed to
persist (test connection ✓, Apply, reopen → old token, 401). **No notification** (ruled out the
SSRF/URL-validation abort path) and PasswordSafe in **KeePass-file mode**.

**Root cause:** `CredentialStore.storeToken` stored `Credentials(service.name, token)` (KeePass entry
username = e.g. `"JIRA"`) but `credentialAttributes()` built `CredentialAttributes(generateServiceName("WorkflowOrchestrator", service.name))`
with a **null username**. With a null username in the lookup attributes, PasswordSafe's KeePass
backend could not reliably target the existing entry on `set()`, so a changed token landed
ambiguously and a later `get()` returned the stale value. The token *read* path worked (old token
was sent to Jira → 401 because expired), so it looked like only the *write* failed.

**Fix (proven empirically — 0.87.1 worked where 0.87.0 didn't):** add `service.name` as the username
arg: `CredentialAttributes(generateServiceName(...), service.name)`. Now set() and get() address the
same unique entry. **No migration needed** — existing entries already carry username `service.name`,
so the aligned lookup finds them. Shipped on `perf/waves3-6` as **0.87.2** (clean port; dropped the
0.87.1 shotgun extras — 500ms deferred cache-clear `Thread.sleep`, duplicate logging).
**Merged to main 2026-06-19** via PR #57 squash (`b873f8c82`) — rode in on the perf Waves 3-6 PR.

**Safety net added same commit:** `storeToken` now returns `Boolean` — it reads the value straight
back from PasswordSafe (bypassing the in-memory cache) and returns `false` on mismatch (logged at
**warn**, NOT error — `log.error` in a unit test trips `TestLoggerFactory$TestLoggerAssertionError`
and fails the test). `ConnectionsConfigurable.apply()` surfaces `false` via an ERROR notification
("<Service> token not saved"). This converts a future silent write failure into an instant signal —
the very thing whose absence made this a multi-hour hunt.

**Debugging dead ends (don't repeat):** the async token-load race (0.87.0's `isInitializing` guard
around the async field-set is actually *sound* — Swing fires the doc listener synchronously inside
the guard; the race fix changed nothing, which is what isolated the cause to the storage layer); and
the all-or-nothing URL validation abort in `apply()` (would have shown a notification → ruled out).

Files: `core/.../auth/CredentialStore.kt` (`credentialAttributes`, `storeToken`),
`core/.../settings/ConnectionsConfigurable.kt` (`apply`, `notifyTokenSaveFailure`),
`core/.../auth/CredentialStoreTest.kt` (verify tests). Tests/detekt/verifyPlugin all green.
Related: [[project_intellij_configurable_dialog_panel_pattern]].
