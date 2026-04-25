# Phase 4 Prong C — coroutine scope tightening

**Status:** draft, pending user approval before any code commit
**Branch:** `refactor/cleanup-perf-caching`
**Date:** 2026-04-25
**Audit:** `docs/architecture/phase4-prong-c-audit.md` (58 sites classified)

---

## Scope

Tighten coroutine scope lifecycle across the codebase so every `CoroutineScope(…)` either (a) is cancelled by a guaranteed lifecycle hook, (b) is replaced by the 2024.1+ platform-injected `cs: CoroutineScope` pattern, or (c) is removed in favour of structured concurrency. This is a **correctness prong** — scope leaks don't show on a profiler until the JVM eventually OOMs.

Live IDE not required. Everything is source + compile + test + grep verifiable.

---

## Re-audit summary — how big this is

The branch memory estimated "~6-10 sites". The actual audit found **58 ad-hoc scope sites**:
- **19 SAFE** (scope.cancel guaranteed to fire; no action)
- **14 FIX** (needs Disposable binding — most unlocked by one factory-level fix)
- **15 CONVERT** (service candidates — 2024.1+ pattern)
- **10 LEAKY** (real or latent leaks)

**Six sites are Cline-ported and explicitly skipped** per `feedback_faithful_port_cline.md`: `JavaRuntimeExecTool.kt:709,733`, `PythonRuntimeExecTool.kt:292,315`, `CoverageTool.kt:340,362`. They use a per-listener-event scope pattern documented in the files; leave intact.

---

## Commit plan — 9 commits

| # | Commit | Sites fixed | Risk | Model |
|---|---|---|---|---|
| C1 | `perf(core): cascade tool-window content dispose to Disposable panels` | ~10 panels (via factory-level fix) | Medium — touches every tool window tab | Opus |
| C2 | `perf(agent): consolidate 14 ad-hoc AgentController scopes onto controllerScope` | 14 | Medium — many call sites in one file | Opus |
| C3 | `fix(core): use supervisorScope suspend-builder in InsightsNarrativeService` | 1 | Low — 3-line structural fix | Sonnet |
| C4 | `fix(core): inline WeeklyDigestStartupActivity body, remove field scope` | 1 | Low — delete scope, use suspend context | Sonnet |
| C5 | `perf(sonar): make CoveragePreviewPanel Disposable` | 1 | Low | Sonnet |
| C6 | `perf(core): make InsightsPanel Disposable + stop poller on dispose` | 1 | Low | Sonnet |
| C7a | `perf(core): convert HealthCheckService / DefaultBranchResolver to service-injected scope` | 2 | Low — mechanical | Sonnet |
| C7b | `perf(feature-modules): convert feature services to service-injected scope` | 8 (BackgroundPool, BuildMonitorService, QueueService, HandoverStateService, PrListService, TicketDetectionPresenter, TicketTransitionServiceImpl, AgentService) | Medium — service DI change | Opus |
| C8 | `perf(settings): unify Configurable scope re-init pattern` | 3 (JiraWorkflowConfigurable, AutomationConfigurable, AgentParentConfigurable) | Low | Sonnet |

**Commit order rationale:**
- C1 is first because it converts ~10 bucket-B panels from leaking to safe in one change. Landing anything else that touches those panels first would be wasted work.
- C2 is second because AgentController is the largest consolidation (14 sites → 0), high impact, and unrelated to C1.
- C3/C4 are fast wins (structural cancellation bugs) that can slot anywhere after C2.
- C5/C6 are small, isolated, independent.
- C7 is the service-DI migration. Split into C7a (two `:core` services — lower blast radius) and C7b (feature + :agent — wider blast radius).
- C8 is cosmetic polish.

All 9 commits touch different files except for C1 + C7b (both may touch feature module service files — but C1 modifies the factory, not the services, so no overlap).

---

## Per-commit detail

### C1 — Cascade tool-window dispose to panels

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt`
**Target:** `materializeTab` (line ~344) and `buildTabs` (line ~361)

**Problem:** `ContentFactory.getInstance().createContent(panel, title, false)` does not cascade `Content.dispose()` to the panel. Every panel implementing `Disposable` (`BuildDashboardPanel`, `PrDashboardPanel`, `QualityDashboardPanel`, `SprintDashboardPanel`, `HandoverPanel`, `AutomationPanel`, `TicketDetailPanel`, `QuickCommentPanel`, `IssueDetailPanel`, `MonitorPanel`, agent tabs) has a written `dispose()` that never fires at project close.

**Fix:** after `createContent(…)`, if the panel implements `Disposable`, register it:
```kotlin
val content = ContentFactory.getInstance().createContent(panel, tab.title, false)
if (panel is Disposable) {
    content.setDisposer(panel)  // cascades content.dispose() → panel.dispose()
}
contentManager.addContent(content)
```

Apply in both `materializeTab` (for lazy tab creation) and `buildTabs` (for eager first-tab creation).

**Verification:**
- `./gradlew :core:compileKotlin` + `./gradlew :agent:compileKotlin` + all feature module compile targets.
- `./gradlew verifyPlugin buildPlugin`.
- Optional manual: run the dev IDE, open + close the Workflow tool window, confirm no stale references via memory snapshot (skippable if no live IDE).

**Commit message (tentative):**
```
perf(core): cascade tool-window content dispose to Disposable panels

WorkflowToolWindowFactory.materializeTab and buildTabs previously created tab
content without wiring Content.dispose() → panel.dispose(). Every panel that
declared Disposable (~10 panels across Sprint, PR, Build, Quality, Automation,
Handover tabs) had a written dispose() that never fired on project close,
leaking their CoroutineScopes and any subscribers.

Wire content.setDisposer(panel as Disposable) after createContent so dispose
cascades. Subsequent Prong C commits now rely on this chain.

Phase 4 Prong C commit C1.
```

### C2 — AgentController scope consolidation

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
**Targets (14 sites):** lines 488, 940, 1069, 1156, 2093, 2107, 2121, 2147, 2168, 2185, 2854, 2936, 2998 — plus any captured by re-grep

**Problem:** `controllerScope` already exists at line 225 with `dispose()` cancellation (line 3129). Yet 14 other call sites create their own `CoroutineScope(Dispatchers.IO + SupervisorJob()).launch { }` fire-and-forget. Each click/retry/session-load creates a new unparented scope.

**Fix:** replace every `CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {` with `controllerScope.launch(Dispatchers.IO) {`. Preserve any inline comments about rationale.

**Verification:**
- `./gradlew :agent:compileKotlin`
- `./gradlew :agent:test` — expect only the 3 pre-existing flakes from Prong A
- `grep -c "CoroutineScope(" AgentController.kt` should drop from 15 to 1 (only `controllerScope` field at line 225 remains)

**Commit message:**
```
perf(agent): consolidate 14 ad-hoc AgentController scopes onto controllerScope

controllerScope already exists at line 225, cancelled in dispose() at line 3129.
Every click/retry/session-load previously created a new unparented
CoroutineScope(Dispatchers.IO + SupervisorJob()), leaking on every invocation.
Collapsed all 14 sites to controllerScope.launch(Dispatchers.IO) { ... }.

Sites fixed: 488, 940, 1069, 1156, 2093, 2107, 2121, 2147, 2168, 2185, 2854,
2936, 2998 + the method-local scope variables.

Phase 4 Prong C commit C2.
```

### C3 — InsightsNarrativeService supervisorScope builder

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/insights/InsightsNarrativeService.kt`
**Target:** line 53 `val supervisorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())`

**Problem:** Variable is named `supervisorScope` but it's a plain `CoroutineScope(…)`, not the `supervisorScope { }` builder. The three child `async { … }` calls are awaited but never cancelled in a `finally` — if the caller cancels the suspend function mid-flight, the async children orphan.

**Fix:** swap the ad-hoc scope for the real `supervisorScope { }` suspending builder:
```kotlin
// Before:
val supervisorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
val a = supervisorScope.async { … }
val b = supervisorScope.async { … }
val c = supervisorScope.async { … }
val result = /* combine */ a.await(), b.await(), c.await()

// After:
val result = supervisorScope {
    val a = async(Dispatchers.IO) { … }
    val b = async(Dispatchers.IO) { … }
    val c = async(Dispatchers.IO) { … }
    /* combine */ a.await(), b.await(), c.await()
}
```

**Verification:** compile, run existing tests (if any exercise this service), grep proves the ad-hoc scope is gone.

### C4 — WeeklyDigestStartupActivity

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/insights/WeeklyDigestStartupActivity.kt`
**Target:** line 21 `private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())`

**Problem:** `ProjectActivity.execute(project: Project)` is already a suspend function — the platform cancels it on project close. The field `scope.launch { … }` detaches work from the platform's cancellation.

**Fix:** delete the `scope` field; move the `runCatching { … }` body directly into `suspend fun execute`. The work now participates in platform cancellation.

### C5 — CoveragePreviewPanel Disposable

**File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoveragePreviewPanel.kt`
**Target:** line 27 `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`

**Problem:** Class has a `fun dispose()` but doesn't implement `Disposable` and isn't registered anywhere. Parent (`QualityDashboardPanel`) never calls `.dispose()`.

**Fix:** make the class `: Disposable`; add `override fun dispose() { scope.cancel() }`; in `QualityDashboardPanel.init`, `Disposer.register(this, coveragePreviewPanel)` so it cascades. (The C1 factory fix cascades dispose into QualityDashboardPanel.)

### C6 — InsightsPanel Disposable

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/insights/InsightsPanel.kt`
**Target:** line 30 `private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())`

**Problem:** Panel has no `dispose()`, doesn't implement `Disposable`. `AncestorListener` starts/stops the poller but never cancels the scope. Leaks on tool window close.

**Fix:** make class `: Disposable`; `override fun dispose() { poller.stop(); scope.cancel() }`. C1 factory cascade takes care of firing it.

### C7a — Convert core services to service-injected scope

**Files:**
- `core/healthcheck/HealthCheckService.kt`
- `core/util/DefaultBranchResolver.kt`

**Pattern:**
```kotlin
// Before:
@Service(Service.Level.PROJECT)
class HealthCheckService(private val project: Project) : Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun dispose() { scope.cancel() }
    …
}

// After:
@Service(Service.Level.PROJECT)
class HealthCheckService(private val project: Project, private val cs: CoroutineScope) {
    // No ad-hoc scope, no Disposable — platform cancels cs on project close.
    …
}
```

Rename `scope` → `cs` at all internal use sites. Delete the `Disposable` declaration + `dispose()` if it only cancelled scope; keep it if it also released other resources.

**Verification:** compile, run `:core:test`.

### C7b — Convert remaining services

**Files:**
- `agent/AgentService.kt`
- `agent/tools/background/BackgroundPool.kt`
- `automation/service/QueueService.kt`
- `bamboo/service/BuildMonitorService.kt`
- `handover/service/HandoverStateService.kt`
- `pullrequest/service/PrListService.kt`
- `jira/ui/TicketDetectionPresenter.kt`
- `jira/service/TicketTransitionServiceImpl.kt` (**closes C9 — the `parentScope = null` leak**)

Same pattern as C7a. `TicketTransitionServiceImpl` is the most complex because of its `parentScope: CoroutineScope? = null` fallback — the test constructor can stay; the platform DI now gives `cs` directly.

**Caveat for AgentService:** the `scope` field is referenced in many places (`:agent/AgentService.kt` alone uses it in 10+ sites). Rename-all pass, check for string references, verify tests. This is the riskiest sub-commit; if it looks tangled, split into its own commit.

**Verification:** full `./gradlew verifyPlugin buildPlugin` after this commit.

### C8 — Configurable scope re-init polish

**Files:**
- `jira/settings/JiraWorkflowConfigurable.kt`
- `automation/settings/AutomationConfigurable.kt`
- `agent/settings/AgentParentConfigurable.kt`

**Pattern:** pick one idiom (probably AutomationConfigurable's `var scope; re-init in createComponent`) and apply uniformly.

Lowest-priority — may skip if Prong C exceeds time budget.

---

## Exit criteria

1. `grep -rnE "CoroutineScope\(" --include='*.kt' core/src/main jira/src/main bamboo/src/main sonar/src/main pullrequest/src/main automation/src/main handover/src/main agent/src/main` — remaining matches are only:
   - Cline-ported runtime tools (6 sites; intentional)
   - `controllerScope` field in AgentController (after C2)
   - `supervisorScope { }` builder references (not the ad-hoc form)
   - Test-only scopes (if any)
2. `./gradlew verifyPlugin buildPlugin` green on IU-251/252/253.
3. `./gradlew :agent:test`, `:core:test`, `:jira:test`, `:bamboo:test`, `:sonar:test`, `:pullrequest:test`, `:automation:test`, `:handover:test` — no new failures beyond the 3 pre-existing flakes from Prong A.
4. Branch memory updated with Prong C commit SHAs.
5. `docs/architecture/phase4-prong-c-audit.md` preserved as historical audit record.

---

## Out of scope for Prong C

- `ProjectContextTool.kt:556` cache-refresh scope — Cline port; revisit in Phase 5 caching pass
- Cline-port runtime tools pollScope — skip
- `AutoDetectFileListener` application-listener lifetime — acceptable by design
- Converting `AgentController` itself to `@Service` — Prong F (deferred)
- Converting `InsightsPanel`/`InsightsNarrativeService` into a unified service — Phase 5

---

## Decisions for the user before execution

1. **Approve the plan?** (Y/N + changes)
2. **Split C7b into per-service commits,** or bundle as one? I recommend **one commit** unless a specific service's conversion turns out tangled; AgentService should probably be its own sub-commit because of blast radius.
3. **Include C8 (Configurable polish)?** Truly optional. I recommend **skip unless we have budget** — the current patterns work, even if inconsistent.
4. **Execution mode:** subagent-driven per `feedback_always_subagent.md` (1 subagent per commit, sequential because same files in some cases)? I'll use Opus for C1/C2/C7b and Sonnet for the small mechanical ones (C3/C4/C5/C6/C7a/C8).
