---
name: project_dialog_modality_showandget_trap
description: DialogWrapper.showAndGet() throws for MODELESS dialogs; headless tests miss it — pin with source-contract test
metadata: 
  node_type: memory
  type: project
  originSessionId: 47f830d0-0f97-4027-b578-df13520757c7
---

⚠ TRAP 2026-05-27: `DialogWrapper.showAndGet()` throws `IllegalStateException("The showAndGet() method is for modal dialogs only")` when the dialog is constructed with `IdeModalityType.MODELESS` (its first line is `if (!isModal()) throw …`). It checks BEFORE showing, so the dialog never appears.

**Bug fixed (feature/cross-ide-delegation):** `DelegationPicker` was switched to MODELESS (commit `15b835d69`, 2026-05-24) "so auto-launch works", but `DelegationOutboundService.pickTarget()` still shows it via `showAndGet()` → crashed EVERY delegation attempt. The MODELESS rationale (keep picker open while a launcher spawns a new IDE window) became obsolete the NEXT DAY when Plan 6 Task 8 (`3a9823d04`) moved launcher-spawning into `send()` AFTER the picker closes. Fix = revert picker to modal `DialogWrapper(project, true)`; nothing needs MODELESS anymore. The only legit MODELESS dialog is `DelegationInboundConsentDialog`, which correctly uses `show()`.

**Note:** `DialogWrapper(project, false)` is still MODAL — the boolean is `canBeParent`, NOT modality. Only an explicit `IdeModalityType.MODELESS` makes a dialog non-modal.

**Why:** This is a RUNTIME contract (needs live Application + EDT + window manager) — MockK/headless JUnit can't reach it. All 24 delegation tests passed because `DelegationOutboundService` exposes `pickTargetOverride` and every test injects a fake `PickerEntry`; the real `pickTarget()` had ZERO coverage. Same blind-spot category as [[project_service_constructor_jvmoverloads_trap]] ("Tests + verifyPlugin miss it").

**How to apply:** For UI/DI runtime-wiring contracts that headless tests can't execute, add a cheap source-contract test (like `RunInvocationLeakTest`/`SpillingWiringTest`). Added `DialogModalityContractTest` (agent/…/delegation/ui/) — scans all `: DialogWrapper(…MODELESS)` classes + `showAndGet()` sites and fails if any MODELESS dialog is shown via showAndGet. Committed `91fbe6b38` on `feature/cross-ide-delegation` (full :agent:test green). NOT pushed; no release cut yet.
