# Phase 7 — Handover Context Convergence

**Branch:** `fix/automation-handover-quality-tabs`
**Date:** 2026-05-11
**Status:** Plan draft. Awaiting user approval → execution.
**Supersedes:** finishes the unfinished `HandoverPanel` migration from `workflow-context-design.md` §5.1 step 5.

## 1. Summary

Phase 5 introduced `WorkflowContextService` as the single source of truth for ticket / focused-PR / focused-build / focused-quality identity. Four of the five tool-window tabs (PR, Build, Quality, Automation) consume it. The fifth — Handover — still uses the pre-Phase-5 `EventBus`-accumulation model and is the only tab whose Checks dots stay blank when the user opens Handover on a fresh IDE without first touching Build / Quality / Automation.

Verification of the current code (2026-05-11) surfaced **three confirmed bugs** behind the symptom:

- **B1 (Critical, silent):** `:sonar` never emits `WorkflowEvent.QualityGateResult`. The event is defined and subscribed to (`HealthCheckService.SonarGateCheck`, `HandoverStateService`) but no module ever constructs and emits it. Handover's quality dot has never worked; HealthCheck framework's Sonar check stays at default.
- **B2 (High):** `BuildMonitorStartupActivity` starts polling at project open but resolves the plan key from `RepoContextResolver.getPrimary().bambooPlanKey` and the branch from `resolvePrimaryGitRepo()?.currentBranchName ?: "develop"`. For multi-repo / non-primary-plan / focus-on-different-repo users, the bootstrap polls the wrong build entirely. `BuildFailureBridgeStartupActivity` notifications target the wrong PRs.
- **B3 (High):** `BuildMonitorService.switchBranch(...)` is only called from `BuildDashboardPanel.kt:265, 275`. Focused-PR changes do not retarget the poller unless the Build tab is visible. After Build tab dispose (`BuildDashboardPanel.kt:721`), polling stops entirely.

Phase 7 fixes these three bugs as the precondition for migrating Handover. The Handover migration itself becomes a thin reactive subscription once the upstream is correct.

## 2. Goal

After Phase 7:

- Opening Handover on a fresh IDE with a hydrated `focusPr` shows real build / quality / suite status within ≤ one poll cycle (≤30s) for *all* repo / plan configurations, not just the single-repo happy path.
- `BuildMonitorService` ambient polling lifecycle is driven by `WorkflowContextService.focusBuild`, not by Build tab visibility. Multi-repo users get correct build-failure notifications without opening Build tab.
- `:sonar` emits `WorkflowEvent.QualityGateResult` when its quality-gate query lands a new result. Handover quality dot lights up.
- `HandoverStateService` becomes a thin reactive subscriber to upstream service `StateFlow`s + a small set of local mutators for Handover-actioned outcomes. The local `EventBus`-accumulation paths for `BuildFinished` / `QualityGateResult` / `AutomationFinished` / `AutomationTriggered` / `PullRequestCreated` are removed.

## 3. Scope

### In scope

- T-B1: Sonar quality-gate emission wiring.
- T-B2/B3: `BuildMonitorService` lifecycle migration to `focusBuild`-driven.
- T-Auto: Decision and minimal change for Automation suite hydration in Handover (Option A — consume existing `QueueService.stateFlow`, no new EP).
- T-Handover: Migrate `HandoverStateService` to subscribe to upstream `StateFlow`s; PR-scope event handlers; delete now-dead accumulator paths.
- T-Docs: Update `bamboo/CLAUDE.md` (remove stale `BuildStatusBarWidget` / `BuildStatusNodeDecorator` references), `handover/CLAUDE.md`, `core/CLAUDE.md`, `sonar/CLAUDE.md`, `automation/CLAUDE.md`, `docs/architecture/index.html`.

### Out of scope (deferred)

- Adding result slices (build-status, quality-result, suite-results) to `WorkflowContext` itself. The Phase 5 design deliberately separated identity (cheap, in-aggregate) from status (expensive, polled per-module). Phase 7 respects this — status flows live in their owning service's `StateFlow`, consumers subscribe directly.
- Cross-session suite-result hydration via a new `AutomationSuitesLookup` EP. Today's `QueueService` is session-scoped; Handover stays session-scoped to match. Revisit only if a user reports needing yesterday's suite runs visible after IDE restart.
- Persistence of `focusPr` / `focusBuild` across IDE restart. Phase 5 §10 deferred this; Phase 7 does not relitigate.
- Migrating any of the already-Phase-5-migrated tabs (PR, Build, Quality, Automation). They have working consumption paths; don't churn.
- Adding `BuildStatusBarWidget` / `BuildStatusNodeDecorator`. These are stale references in `bamboo/CLAUDE.md` from old phase plans that never landed. Phase 7 just removes the references.
- Refactoring `BuildMonitorStartupActivity` to "do nothing" vs deleting it. Decision deferred to T-B2/B3 implementation: simplest correct shape wins.

## 4. Confirmed bugs and the bug-fix payload

| ID | Severity | Symptom | Root cause | Closing task |
|---|---|---|---|---|
| B1 | Critical (silent) | Handover quality dot never lights up; `SonarGateCheck` health-check stays at cached default | No emit site for `WorkflowEvent.QualityGateResult` in any module | T-B1 |
| B2 | High | Multi-repo / non-primary-plan / focus-elsewhere users get wrong/missing build-failure notifications when Build tab not open | `BuildMonitorStartupActivity` polls primary plan only; never observes `focusBuild` | T-B2/B3 |
| B3 | High | Focused-PR change does not retarget the build poller unless Build tab visible; data drifts silently | `BuildMonitorService.switchBranch` is invoked only from `BuildDashboardPanel`, not from a focus-flow subscriber | T-B2/B3 |

## 5. Architecture changes

### 5.1 `:sonar` — emit `QualityGateResult` (T-B1)

**Where:** the existing `SonarServiceImpl` quality-gate fetch path. Currently `QualityDashboardPanel` calls into Sonar to render the gate result; that call site is the natural emit point. After fetching a result, emit:

```kotlin
eventBus.emit(
    WorkflowEvent.QualityGateResult(
        projectKey = scope.sonarProjectKey,
        passed = (gateStatus == "OK"),
    )
)
```

**Subtlety:** Sonar has a CE-task delay between push and gate-decision. The emit fires only after a *terminal* gate result is observed (OK / ERROR), not while the CE task is `IN_PROGRESS`. This matches `BuildMonitorService`'s existing "only emit on terminal state" pattern (`BuildMonitorService.kt:166-180`).

**No new polling.** Sonar quality is fetched on demand by `QualityDashboardPanel` (already migrated to `WorkflowContextService.state.map { it.focusQualityScope }`). The emit is a side effect of that existing fetch path. If `QualityDashboardPanel` isn't visible, no fetch happens, no emit happens — but Phase 7's Handover migration handles that case via the focus-driven catch-up at T-Handover.

### 5.2 `:bamboo` — focus-driven `BuildMonitorService` lifecycle (T-B2/B3)

**Today:**
- `BuildMonitorStartupActivity` calls `startPolling(primaryPlanKey, primaryBranch)` at project open.
- `BuildDashboardPanel.startMonitoring()` and `loadBuildsForContext()` call `startPolling`/`switchBranch` based on dropdown selection.
- `BuildDashboardPanel.dispose()` calls `stopPolling()`.

**After:**
- `BuildMonitorService` itself subscribes to `WorkflowContextService.state.map { it.focusBuild }.distinctUntilChanged()` on its own platform-injected `cs` scope. On non-null `focusBuild`, calls internal `startPolling(focusBuild.chainKey ?: focusBuild.planKey, focusBuild.branch, interval)`. On null, calls `stopPolling()`.
- `BuildMonitorStartupActivity` is deleted. The focus-flow subscription begins on service construction; `WorkflowContextProjectActivity` already guarantees `WorkflowContextService` is constructed before any panel.
- `BuildDashboardPanel` drops `startPolling` / `switchBranch` / `stopPolling` calls. Keeps `setVisible(isVisible)` for cadence backoff. The "select a different branch from dropdown" override (lines 265, 275) becomes a `WorkflowContextService.focusPr(...)` call instead — single write path.

**Edge case — Build tab as a viewer of non-focused builds.** Today the Build tab dropdown lets the user pick any branch, even if it isn't the focused PR's branch. After Phase 7, picking a non-focused branch must either:
- (a) update the focus chain (preferred — keeps single source of truth), or
- (b) be a local-only override that the dashboard handles by direct `BambooApiClient.getLatestResult` call without disturbing the service's polling.

T-B2/B3 picks (a). The dashboard's dropdown is repurposed as a "swap focused PR" affordance. Concrete flow: pick branch B from dropdown → resolves to PR for branch B → `WorkflowContextService.focusPr(prB)` → focus cascade fires → `BuildMonitorService` retargets. Same code path as PR-tab selection.

### 5.3 `:automation` — Handover consumes existing `QueueService.stateFlow` (T-Auto)

`QueueService` already exposes `StateFlow<List<RunEntry>>` (per `automation/CLAUDE.md` "Monitor lifecycle (PR 8)" — terminal entries persist until `dismiss`). `QueueRecoveryStartupActivity` rehydrates active queue entries across IDE restart. No service-side change needed.

T-Handover's subscription:

```kotlin
queueService.stateFlow.combine(workflowService.state) { entries, ctx ->
    val chainKey = ctx.focusBuild?.chainKey ?: return@combine emptyList()
    entries.filter { it.matchesChain(chainKey) }
}.collect { filtered -> updateSuiteResults(filtered) }
```

**Limitation acknowledged:** suite results visible in Handover are session-scoped (those triggered or recovered this session). Cross-session historical hydration is the deferred Option B.

### 5.4 `:handover` — reactive subscriber + local mutators (T-Handover)

`HandoverStateService` is restructured around three concerns:

```
┌── Identity inputs (read from WorkflowContextService) ────┐
│   activeTicket / focusPr / focusBuild / focusQualityScope │
└──────┬───────────────────────────────────────────────────┘
       │
       ↓
┌── Status inputs (subscribed from upstream StateFlows + filtered EventBus) ──┐
│   BuildMonitorService.stateFlow                                              │
│   QueueService.stateFlow.filter { it.matchesChain(focusBuild.chainKey) }    │
│   EventBus.events.filterIsInstance<QualityGateResult>()                      │
│      .filter { it.projectKey == focusQualityScope.sonarProjectKey }          │
│   EventBus.events.filterIsInstance<PullRequestCreated>()                     │
│      .filter { it.ticketId == activeTicket.key }                             │
└──────┬───────────────────────────────────────────────────────────────────────┘
       │
       ↓
┌── Local mutators (Handover-actioned outcomes) ──┐
│   markCopyrightFixed()                          │
│   markWorkLogged()                              │
│   markJiraCommentPosted()                       │
│   markJiraTransitioned(statusName)              │
└──────┬──────────────────────────────────────────┘
       │
       ↓
   _stateFlow: StateFlow<HandoverState>
       │
       ↓
   HandoverPanel / ChecksTab / ShareTab / ActionsTab
```

**Reset semantics:** `HandoverState` resets on `activeTicket` change (already implemented). Phase 7 extends reset to `focusPr` change too — switching focused PR clears all status slices and starts fresh subscriptions for the new chain.

**PR-scoping is the core invariant:** every event handler checks the event's payload against the *current* focus snapshot before applying. A `BuildFinished` event for a planKey/branch that doesn't match `focusBuild` is ignored. This eliminates today's bug where Handover accepts any `BuildFinished` event regardless of branch.

## 6. Tasks (landing order)

Each task: foreground subagent, TDD-per-task, no separate code reviewer (per `feedback_skip_subagent_reviews.md`). Cap: ≤2 concurrent foreground subagents (per Phase 6 closeout finding).

| # | Task | Files touched | Tests | Depends on |
|---|------|---------------|-------|-----------|
| **T-B1** | `:sonar` emits `QualityGateResult` from quality-gate fetch path | `sonar/service/SonarServiceImpl.kt` (or wherever the quality-gate query terminates); `sonar/CLAUDE.md` | New: emission test (mock SonarApi → assert `eventBus.emit(QualityGateResult(...))`); only on terminal gate state | None — independent |
| **T-B2/B3-a** | `BuildMonitorService` subscribes to `WorkflowContextService.focusBuild` flow; drives own `startPolling`/`stopPolling`/`switchBranch` | `bamboo/service/BuildMonitorService.kt` | New: focus-change → startPolling fires; null focus → stopPolling fires; rapid focus changes → only latest target wins | None — independent |
| **T-B2/B3-b** | Delete `BuildMonitorStartupActivity`; `BuildDashboardPanel` drops poll-lifecycle calls; dropdown reroutes to `focusPr(...)` | `bamboo/listeners/BuildMonitorStartupActivity.kt` (delete); `bamboo/ui/BuildDashboardPanel.kt` (lines 265, 275, 721, 738, 744); `bamboo/CLAUDE.md` (remove stale widget refs) | Existing `BuildDashboardPanelTest`; new: dropdown selection → `focusPr` mutator called | T-B2/B3-a |
| **T-AutoSeed** *(added 2026-05-11 from queue item #2)* | `WorkflowContextProjectActivity.execute` calls `service.setActiveTicket(persistedAnchor)` on boot when `PluginSettings.activeTicketId` is non-blank, triggering the focusPr auto-seed cascade specified in Phase 5 design §4.5 step 3. Without this, `focusBuild` stays null on fresh IDE and T-B2/B3-a's ambient polling never starts. | `core/workflow/WorkflowContextProjectActivity.kt`; possibly `WorkflowContextService.kt` (if a dedicated `autoSeedFromAnchor()` helper is preferred over re-calling `setActiveTicket` — which already does idempotent persistence). New tests in `core/src/test/kotlin/...`. | New: persisted anchor + open PR matching ticket → after activity runs, `focusPr` is non-null. No persisted anchor → activity no-ops. Persisted anchor without matching PR → `focusPr` stays null but `activeTicket` is set. | None — independent of T-B2/B3-b; can land in parallel |
| **T-Handover-a** | Add status-flow subscriptions to `HandoverStateService` (Build, Sonar event, Queue) | `handover/service/HandoverStateService.kt`; `handover/CLAUDE.md` | New: focus-change resyncs slices; events filtered by focus; build-status flow drives `buildStatus` field; quality event drives `qualityGatePassed` | T-B1, T-B2/B3, **T-AutoSeed** (without it, `focusBuild` is null on boot and the new subscriptions never fire) |
| **T-Handover-b** *(SUPERSEDED 2026-05-11 by T-Handover-a Option-C decision)* | ~~Delete dead accumulator paths in `HandoverStateService.handleEvent`~~ — T-Handover-a chose Option C (EventBus + PR-scoping) over Option A (cross-module flow EPs), so the EventBus accumulator branches **are** the production data source rather than dead code. The actual goal of the deletion (PR-scoping) was delivered by T-Handover-a's `isInScope()` guard. **Deferred to Phase 8** alongside the cross-module flow-EP work that would make deletion safe (would also resolve queue item #3 — the automation-events `parentPlanKey` gap). | n/a — superseded | T-Handover-a |
| **T-Handover-c** *(DONE 2026-05-11)* | Verify `HandoverPlaceholderResolver` and `ShareTab` placeholders read from `WorkflowContext` (not stale `HandoverState` mirror); fix any drift. **Result:** resolver audit passed — zero identity drift. Two `ShareTab` fallbacks reading `HandoverState.ticketId` (stale mirror) fixed to use `WorkflowContextService.state`. Five focus-change regression tests added to `HandoverPlaceholderResolverTest`. Two `ShareTabTest` tests updated to supply `activeTicket` via `workflowContextFlow` instead of relying on the removed `HandoverState.ticketId` fallback. | `handover/ui/tabs/ShareTab.kt`; `handover/CLAUDE.md`; `handover/src/test/.../HandoverPlaceholderResolverTest.kt`; `handover/src/test/.../ShareTabTest.kt` | 5 new focus-change tests; 2 pre-existing tests updated; all 230+ handover tests green | T-Handover-a |
| **T-Docs** | Update `bamboo/CLAUDE.md` (drop stale widget refs, document focus-driven lifecycle), `handover/CLAUDE.md`, `core/CLAUDE.md`, `sonar/CLAUDE.md`, `automation/CLAUDE.md`, `docs/architecture/index.html` | the listed CLAUDE.md files + index.html | n/a | Bundle with T-B2/B3-b and T-Handover-b commits per same-commit-as-code rule |

## 7. Testing strategy

### 7.1 Unit tests (per task)

- **T-B1:** `SonarServiceQualityEmitTest` — mock SonarApi returns OK; assert `EventBus.emit(QualityGateResult(projectKey, passed=true))` called exactly once. `IN_PROGRESS` does not emit. ERROR emits with `passed=false`. Multiple identical results across calls do not double-emit.
- **T-B2/B3-a:** `BuildMonitorFocusLifecycleTest` — set `focusBuild` → `startPolling(chainKey, branch)` invoked. Clear `focusBuild` → `stopPolling()` invoked. Rapid focus changes A→B→C → final pollOnce target is C.
- **T-B2/B3-b:** `BuildDashboardPanelDropdownTest` — selecting a non-focused branch from dropdown calls `WorkflowContextService.focusPr(...)`, not `BuildMonitorService.switchBranch(...)` directly.
- **T-Handover-a:** `HandoverStateServiceFocusSyncTest` — emit a `BuildFinished` matching `focusBuild` → `buildStatus` updates. Emit one with mismatching planKey → ignored. `focusBuild` change → resync clears stale data, starts new subscription. Same shape for quality event and queue entries.

### 7.2 Characterization tests (lock the bug fixes shut)

- **`HandoverQualityDotEndToEndTest`:** simulate Sonar quality-gate result → assert `HandoverState.qualityGatePassed` flips → assert `failedFromState` includes/excludes the failure entry correctly. Replaces a today-passing-but-meaningless test with one that actually exercises the emit path.
- **`MultiRepoBuildNotificationTest`:** simulate multi-repo project with focused PR on non-primary repo. Trigger build for the focused repo's plan. Assert `BuildFailureBridgeStartupActivity` notification fires. (Today's behavior: notification doesn't fire because primary plan isn't being polled.)
- **`HandoverFreshIdeBootTest`:** seed `PluginSettings.activeTicketId` + matching open PR. Boot service. Assert `HandoverState.buildStatus` populated within first poll cycle, without any tab being constructed.

### 7.3 Existing tests that must still pass

- `HandoverPanelTest`, `HandoverStateServiceTest`, `HandoverPlaceholderResolverTest`, `ChecksTabTest` — must pass after migration. Any failures are signals of behavior changes that need explicit decisions.

## 8. Risks

| # | Risk | Mitigation |
|---|------|------------|
| R1 | T-B2/B3-a's focus-driven polling restarts on every `focusBuild` change → Bamboo API quota concern | `distinctUntilChanged` on the subscription; `SmartPoller` 1.5x backoff continues to apply. Fewer restarts than today's "tab-open triggers fresh poll" pattern. |
| R2 | T-B2/B3-b breaks "view a different branch's build" UX in Build tab | Reroute to `focusPr` mutator preserves the affordance via single write path. If user feedback shows the cascade is too heavy for a quick view, add a local-override path as a follow-up. |
| R3 | T-B1 emit happens on every `QualityDashboardPanel` fetch even when result hasn't changed → noisy event stream | Track last-emitted result in `SonarServiceImpl`; emit only on transition. Same shape as `BuildMonitorService.previousStatus` (line 93). |
| R4 | T-Handover-a races between event-driven status update and focus-change reset → stale data briefly visible | `flatMapLatest` at the subscription site cancels in-flight resync on focus change. Plus: reset-on-focus-change writes a clean state before any new event can arrive. |
| R5 | Deleting `BuildMonitorStartupActivity` removes the only path that polls when no focus chain is set | Verified: with no focus chain, there is no build to poll. Today's startup polls "primary plan, primary branch" which is wrong for multi-repo users anyway. The right answer for "no focus" is "no polling". |
| R6 | T-Handover changes break in-flight active sessions if Phase 7 ships mid-day | Polling lifecycle tied to focus, not to session. Worst case: a dropped `BuildFinished` between deploy and next `focusBuild` change. Same severity as a routine restart. |
| R7 | Sonar emit path discovers Sonar's CE-task semantics aren't trivially "terminal vs in-progress" | Defer to existing `SonarServiceImpl` quality-gate logic. If terminal-state detection turns out to be subtle, T-B1 grows by one commit (model the CE-task transitions explicitly). Won't block T-B2/B3 or T-Handover. |
| R8 | `bamboo/CLAUDE.md` references that don't exist in code may have other downstream readers (e.g., agent prompts) | Grep for `BuildStatusBarWidget` / `BuildStatusNodeDecorator` across the project before removing. If any production consumer references them, escalate. (Today's verification found references only in old phase-plan markdown — safe.) |

## 9. Exit criteria

- (a) Confirmed-bug-fix demonstrations:
    - **B1:** Trigger Sonar gate result → Handover quality dot reflects it within next render. Replace today's blank dot with green/red.
    - **B2:** On a multi-repo project, focus a PR for a non-primary repo, fail its build, observe `BuildFailureBridgeStartupActivity` notification fires correctly without opening Build tab.
    - **B3:** From PR tab, switch focus from PR-A to PR-B (different planKey/branch). Without opening Build tab, verify `BuildMonitorService.stateFlow` retargets within next poll cycle.
- (b) Fresh-IDE boot: `PluginSettings.activeTicketId` set, matching PR exists. Open Handover directly. Within ≤30s, all four Checks dots reflect real upstream state.
- (c) `HandoverStateService` has no `EventBus` accumulator paths for `BuildFinished` / `QualityGateResult` / `AutomationFinished` / `AutomationTriggered`. The four `mark*()` mutators remain.
- (d) `BuildMonitorStartupActivity` is deleted; `BuildDashboardPanel` does not call `startPolling` / `switchBranch` / `stopPolling` directly.
- (e) `verifyPlugin buildPlugin` green on IU-251 / IU-252 / IU-253.
- (f) Module CLAUDE.md files updated in same-commit cycle as code (per `feedback_update_docs_immediately.md`); `docs/architecture/index.html` updated.
- (g) `bamboo/CLAUDE.md` no longer references `BuildStatusBarWidget` or `BuildStatusNodeDecorator`.

## 10. Out of scope / deferred

- `AutomationSuitesLookup` EP for cross-session historical hydration (Option B). Today's session-scoped behavior matches Phase 5 §10 deferrals.
- Status slices added to `WorkflowContext` itself. Phase 5's identity / status separation respected.
- `latestBuildForBranchFlow` standalone flow (Phase 5 §10 deferred). Build tab's "view different branch" use case is served by the dropdown rerouting through `focusPr(...)`.
- Mid-flight `IN_PROGRESS` Sonar gate states surfaced as a "pending" Handover dot. Today's dot model is binary (passed / failed); Phase 7 inherits that constraint.
- Persistence of `HandoverState`'s status slices across IDE restart. Status is recomputed on boot from upstream `StateFlow`s.
- `BuildStatusBarWidget` / `BuildStatusNodeDecorator` implementations. They were Phase 1c / 3c plans that never landed; documenting their absence is the only Phase 7 action.
- Migrating `:agent`'s `EnvironmentDetailsBuilder` to consume the new status flows. Today it reads `WorkflowContext.state.value` (identity only); enriching with status is a separate enhancement.

## 11. Discovered-during-implementation queue

Bugs or issues found while executing Phase 7 tasks that fall **outside** the current task's scope are appended here rather than fixed inline. After the discovering task lands and is verified, the queue is worked in priority order. A true blocker that prevents the current task from completing is the only exception — escalate explicitly rather than silently widening scope.

Each entry: task that uncovered it, `file:line`, severity, one-line description, current state.

| # | Found in | Location | Severity | Description | Status |
|---|----------|----------|----------|-------------|--------|
| 1 | T-B1 | `sonar/service/SonarDataService.kt:35` | Low | `SonarDataService` allocates its own `CoroutineScope(SupervisorJob() + Dispatchers.IO)` instead of accepting a platform-injected `cs: CoroutineScope` as specified in `core/CLAUDE.md` "Service & threading conventions". No immediate runtime impact (lifecycle managed via `dispose()`), but diverges from the canonical pattern that all other project services use. | Open |
| 2 | User report 2026-05-11 during T-B2/B3-a | `core/workflow/WorkflowContextProjectActivity.kt:16-21` | **Critical — blocks Phase 7 exit criterion (b)** | Phase 5 design `workflow-context-design.md` §4.5 step 3 specified `autoSeedFromAnchor()` to fire `setActiveTicket(persistedAnchor)` on boot, triggering the `focusPr` cascade so `focusBuild` / `focusQualityScope` are populated before any panel is constructed. **Never implemented** — `WorkflowContextProjectActivity.execute()` only calls `recomputeFromEditor()` + installs `WorkflowEventMirror`. After T-B2/B3-a, on fresh IDE the `focusBuild`-driven polling never starts because `focusBuild` stays null until the user manually opens PR tab and `PrDashboardPanel.kt:275` fires `focusPr(ref)`. User-confirmed reproducer: fresh IDE → Automation tab "no CI build" → Build tab "no PR selected" → opening PR tab unblocks everything. | **Resolved (T-AutoSeed)** |
| 3 | T-Handover-a | `handover/service/HandoverStateService.kt` — `isInScope()` | Low | `AutomationTriggered` / `AutomationFinished` events are accepted unfiltered because automation suite plans have no structural link to `focusBuild.chainKey` or `focusBuild.planKey` in their event payloads. Today the only correlatable field is `dockerTagsJson`, whose format is user-defined and not guaranteed to contain the plan key. Consequence: if two PRs trigger automation suites concurrently (rare but possible), both suites accumulate in Handover's `suiteResults` regardless of which PR is focused. Proposed resolution: extend `WorkflowEvent.AutomationTriggered` / `WorkflowEvent.AutomationFinished` to carry a `parentPlanKey: String?` field that `:automation` populates from the triggering build's plan key; `isInScope()` can then match against `focusBuild.planKey`. Alternatively, add a `focusBuild.chainKey` → suite-plan mapping EP in `:core` that `:automation` registers. Neither change touches `:handover` — the guard in `isInScope()` is already structured to accommodate the filter once the field is available. | Open |

**Workflow:**
1. During implementation, if a subagent or grep surfaces an unrelated bug, append a row above with status `Open`.
2. Continue the current task. Do not fix inline.
3. When the current task hits its exit criteria, the next session works through this queue (priority order: severity, then "found in" task order). Each item becomes its own commit / TDD cycle.
4. Mark `Resolved (commit <sha>)` when fixed; `Deferred (reason)` when intentionally pushed to a later phase.

## 12. References

- Phase 5 design: `docs/architecture/workflow-context-design.md`
- Phase 5 plan: `docs/architecture/phase5-workflow-context-plan.md`
- Phase 6 closeout (security; unrelated content but explains "Phase 6 is taken"): `docs/architecture/phase6-closeout.md`
- Today's surfaces:
    - `core/workflow/WorkflowContextService.kt`
    - `core/workflow/WorkflowEventMirror.kt`
    - `core/workflow/WorkflowContextProjectActivity.kt`
    - `core/events/WorkflowEvent.kt`
    - `bamboo/service/BuildMonitorService.kt`
    - `bamboo/listeners/BuildMonitorStartupActivity.kt` (to delete in T-B2/B3-b)
    - `bamboo/listeners/BuildFailureBridgeStartupActivity.kt`
    - `bamboo/ui/BuildDashboardPanel.kt` (lines 265, 275, 721, 738, 744)
    - `automation/service/QueueService.kt`
    - `automation/service/QueueRecoveryStartupActivity.kt`
    - `handover/service/HandoverStateService.kt`
    - `handover/service/HandoverPlaceholderResolver.kt`
    - `handover/model/HandoverModels.kt`
    - `handover/ui/HandoverPanel.kt`
- Stale doc references to fix in T-Docs: `bamboo/CLAUDE.md` ("`BuildStatusBarWidget`", "`BuildStatusNodeDecorator`").
- Conventions:
    - Service-injected scope: `core/CLAUDE.md` § "Service & threading conventions (Phase 4)"
    - Disposable cascade: `phase4-prong-c-plan.md`
    - `editor-fallback-allowed` marker: `core/CLAUDE.md` § "Repo resolution"
