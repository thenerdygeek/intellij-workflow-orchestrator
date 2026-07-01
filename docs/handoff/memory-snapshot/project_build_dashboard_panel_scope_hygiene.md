---
name: BuildDashboardPanel scope cleanup (TODO)
description: BuildDashboardPanel uses ad-hoc CoroutineScope(IO+SupervisorJob); should consolidate per Phase 4 conventions
type: project
originSessionId: 37ba2642-e416-4e4e-b609-2145e6caa389
---
`bamboo/ui/BuildDashboardPanel.kt:81` declares `private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())`. Per `core/CLAUDE.md` "Service & threading conventions", non-`@Service` classes should consolidate fire-and-forget launches onto a single field scope (the canonical example is `AgentController.controllerScope` from Phase 4 C2).

The 2026-04-27 sweep added two more `scope.launch` blocks to this panel (the WorkflowContextService state collector + the interactionModeFlow collector), making the violation more visible. Reviewer M2 flagged it.

**Why deferred:** pre-existing pattern; out of scope for the resolver sweep, and the new collectors do behave correctly under panel disposal because `scope.cancel()` runs in `dispose()`.

**How to apply:**
1. Migrate `BuildDashboardPanel`'s ad-hoc scope to follow the `controllerScope` pattern (declared once, all launches go through it, cancel-on-dispose).
2. Audit ancestor-listener path at line ~653 — currently only `dispose()` cancels; detaching/reattaching the panel without disposal could leak collectors. Add `scope.cancel()` on `ancestorRemoved` for symmetry, OR confirm via lifecycle docs that ancestor-removed always precedes dispose for tool-window panels.
3. Test: `BuildDashboardPanel` lifecycle — instantiate, attach, detach, re-attach, dispose. Assert no collector leak (use `kotlinx.coroutines.test` to verify scope cancellation).
