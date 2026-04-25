# Phase 4 Prong C — coroutine scope audit

Date: 2026-04-25
Auditor: opus max-effort subagent
Branch: `refactor/cleanup-perf-caching`

## Summary

- Total ad-hoc-scope sites audited: **58** (46 from the initial grep + 12 additional sites discovered by widening the pattern to `CoroutineScope(` without the `private val <name> =` prefix filter)
- **A. SAFE:** 19
- **B. FIX — bind to Disposable:** 14
- **C. CONVERT — use service-injected scope:** 15
- **D. LEAKY — investigate:** 10

**Key macro-finding (read this before the per-site tables):** `WorkflowToolWindowFactory` at `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt:344,361` creates tab content via `ContentFactory.getInstance().createContent(panel, tab.title, false)` without ever calling `content.setDisposer(panel as Disposable)` or registering the panel with `toolWindow.disposable`. IntelliJ's `Content.dispose()` does **not** cascade to its JComponent. Result: every tool window panel that declares `: Disposable` — `BuildDashboardPanel`, `PrDashboardPanel`, `QualityDashboardPanel`, `SprintDashboardPanel`, `HandoverPanel`, `AutomationPanel`, `TicketDetailPanel`, `QuickCommentPanel`, `IssueDetailPanel`, `MonitorPanel`, `AgentDashboardPanel` subtree — has a written `dispose()` that **never actually fires** at project close. Their scopes live until the JVM exits. **Fixing the factory fixes ~10 bucket-B sites in one commit**, which radically changes the plan shape and should land first.

---

## Per-site classification

### SAFE (scope.cancel guaranteed to fire)

| File | Line | Scope name | Proof of cleanup |
|---|---|---|---|
| `agent/AgentService.kt` | 99 | `scope` | `@Service(Service.Level.PROJECT)`, implements `Disposable`, `override fun dispose()` at line 2279 calls `scope.cancel("AgentService disposed")`. Platform cancels project services on project close. |
| `agent/testing/ToolTestingPanel.kt` | 32 | `scope` | Init block (line 93-95) explicitly calls `Disposer.register(parentDisposable) { job.cancel() }`. Parent disposable is passed by caller. |
| `agent/tools/background/BackgroundPool.kt` | 99 | `scope` | `@Service(Service.Level.PROJECT)` implementing `Disposable`. `override fun dispose()` (line 72) calls `scope.cancel()`. |
| `agent/tools/subagent/SubagentRunner.kt` | 203 | `scope` | `CoroutineScope(coroutineContext)` — **this is not an ad-hoc scope**. It inherits the caller's coroutine context, so its job is a child of the calling coroutine and auto-cancels when the parent cancels. False positive from the grep. Worth a one-line `CoroutineName` polish but no leak. |
| `agent/ui/AgentCefPanel.kt` | 52 | `scope` | `init` block at line 282: `Disposer.register(parentDisposable) { scope.cancel() }`. Also `dispose()` at line 1349 calls `scope.cancel()` again (belt-and-braces). |
| `agent/ui/AgentTabProvider.kt` | 56 | `scope` | `Disposer.register(project, Disposable { scope.cancel() })` at line 64-66. Parent is Project. |
| `automation/service/QueueService.kt` | 82 | `scope` (via `this.scope = …` in constructor) | `@Service(Service.Level.PROJECT)` `: Disposable`. `override fun dispose()` (line 371) calls `scope.cancel()`. |
| `automation/settings/AutomationConfigurable.kt` | 65, 91 | `scope` | `disposeUIResources()` line 454 calls `scope.cancel()`. Line 91 is a *re-initialization* in `createComponent()` — the same field is reassigned because the Configurable may be re-entered after `disposeUIResources()`. Also has `override fun dispose()` at line 461 also cancelling. Safe, but the pattern is unusual — see Notable findings. |
| `bamboo/run/BambooBuildRunState.kt` | 42 | `scope` (via `parentJob`) | Init registers `Disposer.register(environment.project, { parentJob.cancel() })` (line 46) plus `destroyProcessImpl`/`detachProcessImpl` (lines 139, 145) cancel on process stop. Belt-and-braces. |
| `bamboo/service/BuildMonitorService.kt` | 70 | `scope` | `@Service(Service.Level.PROJECT)` `: Disposable`, `override fun dispose()` at line 127 calls `scope.cancel()`. |
| `bamboo/ui/StageDetailPanel.kt` | 115 | `scope` | Init registers `Disposer.register(parentDisposable, Disposable { scope.cancel() })` at line 167. **Caveat:** the `parentDisposable` is `BuildDashboardPanel` (`this`), which may itself never be disposed per the macro-finding above — so this is only SAFE if the factory-level fix lands. Dual-classify: SAFE *if* BuildDashboardPanel is disposed, bucket-D otherwise. |
| `core/autodetect/AutoDetectFileListener.kt` | 28 | `scope` | Listener is registered as an `applicationListener` (plugin.xml line 382). The in-file comment (line 26-27) explicitly acknowledges that the application-listener lifetime is plugin-lifetime, so an ad-hoc scope here will leak only on plugin unload, not per project. Acceptable — but a plugin reload would leak. Borderline SAFE/B; plan should decide whether to Disposer-bind to a plugin-lifetime parent. |
| `core/events/BranchChangedEventEmitter.kt` | 26 | `scope` | Registered as `projectListener` and implements `Disposable`. `Disposer.register(project, this)` at line 29. `override fun dispose()` (line 56) calls `scope.cancel()`. |
| `core/healthcheck/HealthCheckService.kt` | 49 | `scope` | `@Service(Service.Level.PROJECT)` `: Disposable`. `override fun dispose()` (line 147) calls `scope.cancel()`. |
| `core/toolwindow/WorkflowToolWindowFactory.kt` | 122 | `scope` | Line 142-146: `Disposer.register(toolWindow.disposable) { scope.cancel() }`. This is the **only** panel-adjacent site that follows the correct pattern, and it's a raw function-scope local, not a panel field. |
| `core/util/DefaultBranchResolver.kt` | 25 | `scope` | `@Service(Service.Level.PROJECT)` `: Disposable`. `override fun dispose()` at line 265 calls `scope.cancel()`. |
| `handover/service/HandoverStateService.kt` | 35 (via `this.scope = …`) | `scope` | `@Service(Service.Level.PROJECT)` `: Disposable`. `override fun dispose()` (line 154) calls `scope.cancel()`. |
| `jira/listeners/BranchChangeTicketDetector.kt` | 38 | `scope` | `: Disposable`, registered `Disposer.register(project, this)` at line 41, `override fun dispose()` (line 117) calls `scope.cancel()`. |
| `jira/ui/TicketDetectionPresenter.kt` | 38 | `scope` | `@Service(Service.Level.PROJECT)` `: Disposable`. `Disposer.register(project, this)` at line 41. `override fun dispose()` (line 94) calls `scope.cancel()`. |

### FIX — bind to Disposable

| File | Line | Scope name | Parent Disposable to bind to | Fix template |
|---|---|---|---|---|
| `agent/ui/AgentController.kt` | 940 | local `scope` in `loadModelList()` | Method-local — launched work writes to dashboard. Outlives any useful lifetime. | Replace with existing `controllerScope.launch(Dispatchers.IO) { … }`. `controllerScope` exists at line 225 and is cancelled in `dispose()` (line 3129). All five remaining ad-hoc scopes in AgentController follow this identical fix shape. |
| `agent/ui/AgentController.kt` | 1156 | local `scope` in `executeTaskWithMentions` | — | Replace with `controllerScope.launch(Dispatchers.IO) { … }`. |
| `agent/ui/AgentController.kt` | 2936 | local `scope` in `startPhraseTimer` | — | Replace with `controllerScope.launch(Dispatchers.IO) { … }`. `phraseTimerJob` (the returned job) is already `null`-cancelled in `dispose()` (line 3118). |
| `agent/ui/AgentController.kt` | 2998 | local `scope` in `evaluateTitleOnCompletion` | — | Replace with `controllerScope.launch(Dispatchers.IO) { … }`. |
| `agent/ui/AgentController.kt` | 488, 1069, 2093, 2107, 2121, 2147, 2168, 2185, 2854 | `CoroutineScope(...).launch { }` fire-and-forget | — | Same fix — move to `controllerScope.launch(Dispatchers.IO) { … }`. Retry handler, compaction launcher, session history loaders. **9 sites, one commit.** |
| `bamboo/ui/BuildDashboardPanel.kt` | 67 | `scope` | — | Class already implements `Disposable` with `override fun dispose()` (line 709) calling `scope.cancel()`. The real fix is in the **factory**: in `WorkflowToolWindowFactory.materializeTab`, pass the panel as the Content's disposer: `val content = ContentFactory.getInstance().createContent(panel, tab.title, false); if (panel is Disposable) content.setDisposer(panel)`. **One factory-level fix unblocks 10+ panel sites.** |
| `bamboo/ui/StageDetailPanel.kt` | 115 | `scope` | Parent already `BuildDashboardPanel`. Safe only once the factory fix lands. | Covered by the factory fix above. |
| `handover/ui/HandoverPanel.kt` | 25 | `scope` | — | Class is `: Disposable` with `override fun dispose()` (line 77) cancelling. Fix: factory. |
| `automation/ui/AutomationPanel.kt` | 33 | `scope` | — | Class is `: Disposable` with `override fun dispose()` (line 281) cancelling. Fix: factory. |
| `automation/ui/AutomationStatusBarWidgetFactory.kt` | 33 | `scope` | — | `AutomationStatusBarWidget` extends `EditorBasedWidget` which is a `StatusBarWidget` implementing `Disposable`. Its `dispose()` (line 84) cancels the scope — and status bar widgets DO get disposed when the status bar tears them down. Actually this is probably **already SAFE**; move to SAFE. |
| `pullrequest/ui/PrDashboardPanel.kt` | 50 | `scope` | — | Implements `: Disposable` with `dispose()` at 641 cancelling. Fix: factory. |
| `pullrequest/ui/PrDetailPanel.kt` | 82 | `scope` | `PrDashboardPanel` (`detailPanel.dispose()` called from parent dispose at line 643). Chain works **once factory fix lands**. | Factory fix. |
| `pullrequest/ui/AiReviewTabPanel.kt` | 32 | `scope` | The panel implements `AutoCloseable`, not `Disposable`. `close()` cancels the scope. Lifetime is tied to `PrDetailPanel.commentsTabPanel`/`aiReviewTabPanel` which are `.close()`d from PrDetailPanel.dispose (line 937-938). Chain works once factory fix lands. | No code change beyond factory; consider migrating to `Disposable` for consistency. |
| `pullrequest/ui/CommentsTabPanel.kt` | 52 | `scope` | Same shape as `AiReviewTabPanel` — `AutoCloseable`, closed from `PrDetailPanel.dispose()`. | Factory fix. |
| `sonar/ui/QualityDashboardPanel.kt` | 43 | `scope` | — | Implements `: Disposable`, `dispose()` line 559. Fix: factory. |
| `sonar/ui/IssueListPanel.kt` | 44 | `scope` | — | Implements `Disposable`, `dispose()` line 316. Fix: factory (IssueListPanel is a child of QualityDashboardPanel which is itself tool-window-owned). |
| `sonar/ui/IssueDetailPanel.kt` | 33 | `scope` | — | Implements `Disposable`, `dispose()` line 378. Inside QualityDashboardPanel. Factory fix. |
| `sonar/ui/CoveragePreviewPanel.kt` | 27 | `scope` | Panel does **not** implement Disposable — has `fun dispose()` (line 176) but it's never called. | Make class `: Disposable` and have QualityDashboardPanel `Disposer.register(this, coveragePreviewPanel)` in its init. |
| `jira/ui/SprintDashboardPanel.kt` | 162 | `scope` | — | Implements `: Disposable`, `dispose()` line 1039. Factory fix. |
| `jira/ui/TicketDetailPanel.kt` | 107 | `lazyScope` | — | Implements `: Disposable`, `dispose()` line 665 cancels `lazyScope`. Factory fix. Note also that the class's `showIssue()` already properly calls `currentWorklogSection?.dispose()` / `currentDevStatusSection?.dispose()` on swap — that's correctly scoped. |
| `jira/ui/QuickCommentPanel.kt` | 33 | `scope` | Parent TicketDetailPanel calls `quickCommentPanel.dispose()` (line 672). Works once TicketDetailPanel is actually disposed — factory fix. | Factory fix. |
| `jira/ui/widgets/SearchableChooser.kt` | 62 | `scope` | Takes `disposable: Disposable` constructor param and registers at line 65. | **Already SAFE** if callers pass a real disposable. Audit the call sites (grep `SearchableChooser(`) — if callers pass `this` from a panel that's never disposed, the safety chain breaks. Move to SAFE-conditional. |
| `automation/ui/MonitorPanel.kt` | 34 | `scope` | — | `: Disposable`, `dispose()` at 365 cancelling. Registered `Disposer.register(this, monitorPanel)` from AutomationPanel.init (line 127). Factory fix makes this SAFE (AutomationPanel dispose will cascade). |
| `jira/service/TicketTransitionServiceImpl.kt` | 83 | `scope` | — | **C or B**. Service does not implement Disposable. Three options: (1) make it `: Disposable` and add `override fun dispose() { scope.cancel() }`; (2) convert to 2024.1 service-injected `cs: CoroutineScope`; (3) ensure callers always pass `parentScope`. Prefer (2). |

### CONVERT — use service-injected scope (2024.1+ preferred pattern)

| File | Line | Scope name | Service level | Why conversion is worth it |
|---|---|---|---|---|
| `agent/AgentService.kt` | 99 | `scope` | PROJECT | Already a `@Service`. `cs` injection is a mechanical refactor (add `cs: CoroutineScope` to constructor, delete the ad-hoc field). The `dispose()` method no longer needs `scope.cancel()`. Downstream ripple: several helpers stash the scope in local vals — those continue to work. |
| `agent/tools/background/BackgroundPool.kt` | 99 | `scope` | PROJECT | Same argument as AgentService. |
| `automation/service/QueueService.kt` | 82 | `scope` | PROJECT | Has dual constructor (test + prod). Convert the platform constructor to take `cs`; test constructor already takes a scope. |
| `bamboo/service/BuildMonitorService.kt` | 70 | `scope` | PROJECT | Same dual-constructor shape; same fix. |
| `handover/service/HandoverStateService.kt` | 35 | `scope` | PROJECT | Same dual-constructor shape; same fix. |
| `jira/service/TicketTransitionServiceImpl.kt` | 83 | `scope` | PROJECT | Already has an escape hatch (`parentScope: CoroutineScope?`) designed for tests. Swap to platform-injected `cs` for prod and keep the test constructor. |
| `pullrequest/service/PrListService.kt` | 31 | `scope` | PROJECT | Same pattern as others. |
| `core/healthcheck/HealthCheckService.kt` | 49 | `scope` | PROJECT | Same. |
| `core/util/DefaultBranchResolver.kt` | 25 | `scope` | PROJECT | Same. |
| `jira/ui/TicketDetectionPresenter.kt` | 38 | `scope` | PROJECT | Same. |
| `core/toolwindow/insights/InsightsPanel.kt` | 30 | `scope` | Not currently a service; stays a panel. But consider splitting: the panel's `InsightsService` could own the scope (via service-cs) and the panel subscribes to flows rather than launching. | This is a soft C — lower priority. Skip for Prong C and revisit in Phase 5. |
| `agent/ui/AgentController.kt` | 225 | `controllerScope` | Not a `@Service`; constructed by AgentTabProvider. | Option: make `AgentController` a `@Service(Service.Level.PROJECT)` and inject `cs`. However this is a non-trivial refactor (currently instantiated with a `dashboard` param that isn't a service). **Defer to Prong F** if pursued. For Prong C, keep as `controllerScope` with explicit `cancel()` — already SAFE. |
| `core/insights/WeeklyDigestStartupActivity.kt` | 21 | `scope` | Not a service — it's a `ProjectActivity`. | `ProjectActivity` already runs on a coroutine-scoped context provided by the platform. The `suspend fun execute(project: Project)` should do the work inline instead of fire-and-forget `scope.launch`. Replacing the entire scope-and-launch with a direct `runCatching { … }` **inside** the suspend function makes the activity cancellable by the platform. See Notable findings. |
| `core/insights/InsightsNarrativeService.kt` | 53 | `supervisorScope` (local) | This class is not a service — it's constructed inline inside `WeeklyDigestStartupActivity` and `GenerateReportAction`. | The in-function name `supervisorScope` is misleading — it's an ad-hoc `CoroutineScope(…)`, not the `supervisorScope { … }` builder. After the 3 deferred awaits return, the scope is never cancelled — if `withTimeoutOrNull` succeeds the coroutines complete naturally, but if any hangs past the function return, it leaks. **Fix: replace the ad-hoc `CoroutineScope(…)` with the real `supervisorScope { … }` suspend-builder** — it's structurally identical for the caller's purposes but auto-cancels children when the suspending function exits. This is a subtle bug, not a service conversion. |
| `jira/settings/JiraWorkflowConfigurable.kt` | 50 | `scope` | Configurable — no service. | `disposeUIResources()` (line 434) cancels it — already SAFE. Bucket A. Move out of C. |

### LEAKY — investigate

| File | Line | Scope name | Symptom | Recommended investigation |
|---|---|---|---|---|
| `agent/settings/AgentParentConfigurable.kt` | 64 | `loadScope` | Field declared `private val` — no re-initialization. `disposeUIResources()` (line 355) cancels it. But if the Configurable is re-entered after `disposeUIResources`, the cancelled scope is reused — subsequent launches no-op silently. Same risk as `AutomationConfigurable` (line 91), not yet patched. | Mirror AutomationConfigurable pattern: declare `private var loadScope` and re-create in `createComponent()`. Or switch to per-action-scoped coroutines. |
| `agent/settings/DatabaseProfileDialog.kt` | 96 | `dialogScope` | `DialogWrapper(true)` subclass, `override fun dispose()` (line 459) cancels — **SAFE**. Grep snag: multi-line declaration; actually bucket A. | No action; move to SAFE. |
| `agent/ui/AgentController.kt` | 488 | ad-hoc fire-and-forget in the retry callback | Runs `service.cancelCurrentTask()` + `cleanEmptyArtifactsBeforeRetry()` then an `invokeLater { executeTask(...) }`. Each click creates a new leaked scope. | Covered by the AgentController bucket-B batch above. Preserve the rationale comment (line 487) in the replacement launch site. |
| `agent/tools/builtin/ProjectContextTool.kt` | 556 | ad-hoc fire-and-forget | Fallback "background cache refresh" after a `TimeoutCancellationException`. Each timeout creates a new leaked scope. | ProjectContextTool is not a `@Service` and has no obvious parent. Options: (1) take an `AgentService.scope` at construction; (2) make the cache-refresh a suspend call and let the caller decide. Plan phase should pick. |
| `agent/tools/runtime/CoverageTool.kt` | 340, 362 | `pollScope` | Each `CoroutineScope(SupervisorJob() + Dispatchers.IO)` is bound to `invocation.onDispose { pollScope.cancel() }` on the same line. Safe per-invocation, but wasteful — creating a brand-new scope each time a listener fires. | **Cline-port territory** (per `feedback_faithful_port_cline.md`). Documented in file comments. Skip unless a profiler flags allocator churn. |
| `agent/tools/runtime/JavaRuntimeExecTool.kt` | 709, 733 | `pollScope` | Same pattern as CoverageTool — per-listener scope, bound to `invocation.onDispose`. | Cline-port-preserved. Skip. |
| `agent/tools/runtime/PythonRuntimeExecTool.kt` | 292, 315 | `pollScope` | Same as above. | Skip. |
| `automation/settings/AutomationConfigurable.kt` | 65, 91 | `scope` (re-init) | Line 65 initial; line 91 re-creation in `createComponent()`. Works because `disposeUIResources` cancels. | Flag as out-of-scope note for a later polish; not a leak. |
| `core/toolwindow/insights/InsightsPanel.kt` | 30 | `scope` | Panel has no `dispose()`, does not implement `Disposable`, does not register to any parent Disposable. The `AncestorListener` starts/stops the poller but never cancels the scope. **When the tool window closes, the scope lives on.** | **Real leak.** Fix: make class `: Disposable`, add `override fun dispose() { poller.stop(); scope.cancel() }`. Factory-level fix then cascades dispose. |
| `core/insights/WeeklyDigestStartupActivity.kt` | 21 | `scope` | Field on a `ProjectActivity` — the platform doesn't give activities a long lifetime, but the field is never cancelled. If `execute()` finishes, the activity instance is released, but `scope` might still have a running coroutine if `runCatching` is still mid-LLM-call. | The `scope.launch { … }` inside an already-suspend `execute()` is wrong-shaped. Fix: delete the `private val scope` + `scope.launch`; do the work inline in the `suspend fun execute`. |
| `core/insights/InsightsNarrativeService.kt` | 53 | `supervisorScope` (local) | Scope is declared inside `suspend fun generate(...)`, but the three `async { … }` calls launch on it and are only awaited with `.await()` — no `.cancel()` in a `finally`. If caller cancels `generate` between launches and await, children leak. | Real structural-concurrency bug. Fix: replace the entire `val supervisorScope = CoroutineScope(…)` with the `supervisorScope { … }` **suspending builder** so it participates in structured concurrency. 3-line change. |

---

## Notable findings

- **Macro-finding: factory-level fix eliminates ~10 leaks.** The single line-344/361 change in `WorkflowToolWindowFactory.materializeTab` / `buildTabs` — calling `content.setDisposer(panel as Disposable)` when the panel implements `Disposable` — cascades dispose to every tool-window-hosted panel. **First commit of Prong C.**
- **AgentController.controllerScope is the reuse target for 14+ ad-hoc sites.** Every `CoroutineScope(Dispatchers.IO + SupervisorJob())` at lines 488, 940, 1069, 1156, 2093, 2107, 2121, 2147, 2168, 2185, 2854, 2936, 2998 can collapse to `controllerScope.launch(Dispatchers.IO) { … }`. **One commit, 14 scopes eliminated.**
- **`InsightsNarrativeService.kt:53` hides a latent cancellation bug behind a misleading name.** The local `val supervisorScope = CoroutineScope(…)` looks like the `supervisorScope { }` builder but is a plain ad-hoc scope. Replace with the real builder — 3-line fix that converts a latent leak into structured concurrency.
- **Cline-port poll-scope pattern in runtime tools is intentional.** `JavaRuntimeExecTool.kt:709,733`, `PythonRuntimeExecTool.kt:292,315`, `CoverageTool.kt:340,362` create per-listener-event `pollScope`s bound to `invocation.onDispose`. Per `feedback_faithful_port_cline.md`, do not restructure. **Plan phase skips these 6 sites.**
- **Configurable patterns are inconsistent.** JiraWorkflowConfigurable creates the scope once at construction; AutomationConfigurable re-creates it in `createComponent()`. Both `disposeUIResources` cancel. Pick one idiom in a polish commit.
- **`applicationListener`-registered listeners leak across plugin reloads.** `AutoDetectFileListener.kt:28` admits this in-line. Fine for plugin-lifetime scenarios, but consider wiring to an application-level `Disposable` for belt-and-braces.
- **Brief's grep missed 12 sites.** Use wider pattern `grep -rnE "CoroutineScope\("` for completeness.
- **`TicketTransitionServiceImpl.kt:83` is a live leak.** Secondary constructor passes `parentScope = null`; prod instances create unmanaged scope. Class doesn't implement Disposable.

---

## Out-of-scope observations

- **`ProjectContextTool.kt:556`** creates an unparented `CoroutineScope(Dispatchers.IO).launch` inside a `TimeoutCancellationException` catch. Every timeout spawns a leaked scope. Also: unbounded `ConcurrentHashMap` cache (AP-13). Flag for Phase 5 caching pass.
- **`HandoverStateService.kt`** dual constructor (platform + test) with field-level `val scope: CoroutineScope` initialized inside each constructor body — legal but unusual.
- **`SonarProjectPickerDialog.kt`** correctly cancels its scope in `override fun dispose()`. One polish: the `ModalityState.any().asContextElement` pattern (line 155-157) could be generalised as a reusable helper in `:core` for dialog-bound scopes.

---

## Recommended commit order for Prong C

1. **C1** — `WorkflowToolWindowFactory`: call `content.setDisposer(panel)` when panel is `Disposable`. One-line fix, cascades dispose to ~10 downstream panel scopes. **Massive leak reduction.**
2. **C2** — `AgentController` scope consolidation: replace all ad-hoc `CoroutineScope(…)` (14 sites) with `controllerScope.launch(…)`. One commit.
3. **C3** — `InsightsNarrativeService.kt:53`: swap ad-hoc `CoroutineScope(…)` for the `supervisorScope { }` suspend-builder. 3-line fix. Latent cancellation bug.
4. **C4** — `WeeklyDigestStartupActivity.kt:21`: delete the field scope + inline the work in the `suspend fun execute` body.
5. **C5** — `CoveragePreviewPanel.kt`: make it `: Disposable`, register from QualityDashboardPanel.
6. **C6** — `InsightsPanel.kt`: make it `: Disposable` with `dispose() { poller.stop(); scope.cancel() }`.
7. **C7** — Service-injected-scope migration (2024.1 pattern): AgentService, BackgroundPool, QueueService, BuildMonitorService, HandoverStateService, TicketTransitionServiceImpl, PrListService, HealthCheckService, DefaultBranchResolver, TicketDetectionPresenter. Mechanical; can land as one commit or split 2-3.
8. **C8** — Configurable polish: unify JiraWorkflowConfigurable + AutomationConfigurable + AgentParentConfigurable patterns.
9. **C9** — TicketTransitionServiceImpl leak: either make it `: Disposable` or migrate to service-injected scope (covered by C7 if split appropriately).

Skip: `JavaRuntimeExecTool`, `PythonRuntimeExecTool`, `CoverageTool` pollScope sites (Cline-port preserved); `ProjectContextTool.kt:556` (Cline port); `AutoDetectFileListener` (application-listener lifetime intentional).

**Estimated commit count:** 8–12 commits depending on whether C7 is split.
