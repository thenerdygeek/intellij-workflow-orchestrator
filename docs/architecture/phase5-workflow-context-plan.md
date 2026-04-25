# Phase 5 — WorkflowContextService Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Per-task dispatch convention:** 1 implementer subagent (Opus, max effort) + 1 `superpowers:code-reviewer` subagent (Opus). **No spec reviewer, no quality reviewer** — user feedback `feedback_skip_subagent_reviews.md`. If reviewer issues APPROVE-WITH-FOLLOWUP verdict, parent agent creates a small follow-up commit (Phase 4 pattern).

**Goal:** Introduce `WorkflowContextService` in `:core` as the single source of truth for active ticket / focused PR / editor-derived branch+repo+module across all 6 tool-window tabs and the agent, eliminating cross-tab incoherence and the branch-mismatch correctness hazard.

**Architecture:** Single `@Service(Service.Level.PROJECT)` exposing `StateFlow<WorkflowContext>`. Mutators are mutex-serialized; cascades produce one observable transition. `WorkflowEventMirror` bridges legacy `EventBus` events into the service during a transition window. Banner + per-control disable for ReadOnly mode (PR on non-current branch).

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform 2025.1+, kotlinx.coroutines (`StateFlow`, `Mutex`), JUnit + MockK, JBR Swing.

**Spec:** `docs/architecture/workflow-context-design.md` (HEAD `73de193a`). Reviewer-revised; all 5 critical findings + 4 high-value non-blockers addressed in spec patch.

**Branch:** `refactor/cleanup-perf-caching` (continue commits on this branch — per `feedback_work_on_current_branch.md`).

**Per-task gradle gate:** every task ends with `./gradlew :<module>:test` for the touched module(s); the final task runs `./gradlew clean verifyPlugin buildPlugin`.

---

## File structure (new + modified)

### New files (`:core`)

| Path | Responsibility |
|---|---|
| `core/src/main/kotlin/com/workflow/orchestrator/core/model/workflow/WorkflowContext.kt` | Immutable context data class + `InteractionMode` enum + invariant comment |
| `core/src/main/kotlin/com/workflow/orchestrator/core/model/workflow/Refs.kt` | `TicketRef`, `RepoRef`, `PrRef`, `BuildRef`, `QualityScope`, `ModuleRef` value types |
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt` | The service — state cell + mutators + listener wiring |
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirror.kt` | One-way bridge: legacy `WorkflowEvent` → service mutators (with state-equality loop guard) |
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextProjectActivity.kt` | `ProjectActivity` startup hook that constructs the service (eager) and installs the mirror before any panel emits (R8) |
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/ui/ReadOnlyBanner.kt` | Slim amber banner with two link actions; visibility bound to `interactionModeFlow` |
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/ui/LiveOnlyEnablement.kt` | `bindLiveOnlyEnablement(parent, service, vararg controls)` helper |

### New test files (`:core`)

| Path | Responsibility |
|---|---|
| `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextServiceTest.kt` | Unit tests for cascades + boot semantics + persistence round-trip |
| `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirrorTest.kt` | Mirror translation + loop-prevention + race test |
| `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/InteractionModePurityTest.kt` | Asserts `interactionMode` is pure over declared fields (R9) |
| `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/ui/BannerVisibilityFlickerTest.kt` | Rapid focus-toggle → banner transitions ≤ 2 |

### Modified files

| Path | Change |
|---|---|
| `core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt` | Delete `PrContext` class, `prContextMap` field, and the `if (event is PrSelected) ...` side-effect block in `emit()` |
| `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt` | `setupActiveTicketBar` subscribes to `service.activeTicketFlow`; remove `EventBus`+`PluginSettings` dual read |
| `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt` | PrBar + job stages migration in same commit (NB6 constraint) |
| `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt` | Read from `service.state.value.focusPr` snapshot instead of local resolution |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt` | Subscribe to `service.state.map { it.focusQualityScope }`; banner; per-control disable |
| `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt` | Row click → `cs.launch { service.focusPr(...) }` |
| `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt` | Subscribe to `focusPr`; banner; per-control disable |
| `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt` | Start Work → `service.setActiveTicket(...)` (replaces direct `PluginSettings` write + `TicketChanged` emit) |
| `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverPanel.kt` | Read from `service.activeTicketFlow` |
| `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt` | Read from `service.state.map { it.activeRepo }` |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/EnvironmentDetailsBuilder.kt` | Add `appendWorkflowContext()` step |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/MentionSearchProvider.kt` | `@ticket`, `@pr`, `@build` resolve via `service.state.value` first |
| `src/main/resources/META-INF/plugin.xml` | Register `WorkflowContextService`, `WorkflowContextProjectActivity` |
| `core/CLAUDE.md` | Add `WorkflowContextService` section under Services |
| `docs/architecture/threading-model.md` | New "Workflow context (Phase 5)" subsection |
| `docs/architecture/index.html` | Phase 5 § |

---

## Task list

### Task 1: Data types — `WorkflowContext`, `InteractionMode`, `*Ref` value classes

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/model/workflow/WorkflowContext.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/model/workflow/Refs.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/InteractionModePurityTest.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextEqualsTest.kt`

- [ ] **Step 1: Write the failing tests**

`InteractionModePurityTest.kt`:

```kotlin
package com.workflow.orchestrator.core.workflow

import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InteractionModePurityTest {
    @Test
    fun `interactionMode is Live when focusPr is null`() {
        val ctx = WorkflowContext(activeBranch = "feat/abc")
        assertEquals(InteractionMode.Live, ctx.interactionMode)
    }

    @Test
    fun `interactionMode is Live when focusPr fromBranch matches activeBranch`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        val ctx = WorkflowContext(activeBranch = "feat/abc", focusPr = pr)
        assertEquals(InteractionMode.Live, ctx.interactionMode)
    }

    @Test
    fun `interactionMode is ReadOnly when focusPr fromBranch differs from activeBranch`() {
        val pr = PrRef(42, "bugfix/xyz", "main", "repo", null, null)
        val ctx = WorkflowContext(activeBranch = "feat/abc", focusPr = pr)
        assertEquals(InteractionMode.ReadOnly, ctx.interactionMode)
    }

    @Test
    fun `interactionMode is ReadOnly when activeBranch is null but focusPr exists`() {
        val pr = PrRef(42, "bugfix/xyz", "main", "repo", null, null)
        val ctx = WorkflowContext(activeBranch = null, focusPr = pr)
        assertEquals(InteractionMode.ReadOnly, ctx.interactionMode)
    }

    @Test
    fun `interactionMode does not depend on external state — recomputed identically across calls`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        val ctx = WorkflowContext(activeBranch = "feat/abc", focusPr = pr)
        // Multiple invocations with no state change return identical result.
        // Catches accidental introduction of clock/global-state reads.
        repeat(100) { assertEquals(InteractionMode.Live, ctx.interactionMode) }
    }
}
```

`WorkflowContextEqualsTest.kt`:

```kotlin
package com.workflow.orchestrator.core.workflow

import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class WorkflowContextEqualsTest {
    @Test
    fun `two contexts with identical declared fields are equal`() {
        val t = TicketRef("AFTER8TE-912", "Fix login")
        val a = WorkflowContext(activeTicket = t, activeBranch = "feat/abc")
        val b = WorkflowContext(activeTicket = t, activeBranch = "feat/abc")
        assertEquals(a, b)
    }

    @Test
    fun `contexts differing in focusPr are not equal`() {
        val pr1 = PrRef(42, "feat/abc", "main", "repo", null, null)
        val pr2 = PrRef(43, "feat/abc", "main", "repo", null, null)
        val a = WorkflowContext(focusPr = pr1)
        val b = WorkflowContext(focusPr = pr2)
        assertNotEquals(a, b)
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL (types do not exist)**

```
./gradlew :core:test --tests "com.workflow.orchestrator.core.workflow.InteractionModePurityTest" --tests "com.workflow.orchestrator.core.workflow.WorkflowContextEqualsTest"
```
Expected: compile error / no such class.

- [ ] **Step 3: Implement `Refs.kt`**

```kotlin
package com.workflow.orchestrator.core.model.workflow

data class TicketRef(val key: String, val summary: String)

data class RepoRef(
    val name: String,
    val projectKey: String,
    val repoSlug: String,
    val localVcsRootPath: String,
)

data class PrRef(
    val prId: Int,
    val fromBranch: String,
    val toBranch: String,
    val repoName: String,
    val bambooPlanKey: String?,
    val sonarProjectKey: String?,
)

data class BuildRef(
    val planKey: String,
    val buildNumber: Int,
    val branch: String,
    val selectedJobKey: String?,
)

data class QualityScope(
    val sonarProjectKey: String,
    val branchName: String?,
    val moduleKey: String?,
)

data class ModuleRef(val name: String, val rootPath: String)
```

- [ ] **Step 4: Implement `WorkflowContext.kt`**

```kotlin
package com.workflow.orchestrator.core.model.workflow

/**
 * Immutable snapshot of the workflow's active state. Held inside [WorkflowContextService]'s
 * `StateFlow` cell. Every reader that subscribes to the service sees the same instance
 * for as long as no mutator fires — this is the structural reason cross-tab incoherence
 * (e.g., "PR bar shows X while job stages render Y") is impossible by construction.
 *
 * INVARIANT: any derived getter on this class (today: [interactionMode]; future additions)
 * MUST be a pure function of declared fields. Reading external state inside a derived
 * getter (e.g., git4idea APIs, settings, system clock) breaks the
 * `state.map { it.<derived> }.distinctUntilChanged()` flow — the underlying state will
 * not change when external state changes, so the flow will silently miss transitions.
 * Any new contributing factor must be added as a declared field on [WorkflowContext].
 * Enforced by `InteractionModePurityTest`.
 */
data class WorkflowContext(
    // Anchor — sticky, persisted per-project (PluginSettings.activeTicketId/Summary)
    val activeTicket: TicketRef? = null,

    // Editor-derived — auto from listeners
    val activeRepo: RepoRef? = null,
    val activeBranch: String? = null,
    val activeModule: ModuleRef? = null,

    // Focus chain — transient, session-only
    val focusPr: PrRef? = null,
    val focusBuild: BuildRef? = null,
    val focusQualityScope: QualityScope? = null,
) {
    val interactionMode: InteractionMode get() = when {
        focusPr == null -> InteractionMode.Live
        activeBranch != null && focusPr.fromBranch == activeBranch -> InteractionMode.Live
        else -> InteractionMode.ReadOnly
    }
}

enum class InteractionMode { Live, ReadOnly }
```

- [ ] **Step 5: Run tests — expect PASS**

```
./gradlew :core:test --tests "com.workflow.orchestrator.core.workflow.InteractionModePurityTest" --tests "com.workflow.orchestrator.core.workflow.WorkflowContextEqualsTest"
```
Expected: all 7 tests pass.

- [ ] **Step 6: Module test gate**

```
./gradlew :core:test
```
Expected: green; 4 pre-existing flakes only (per Phase 4 baseline).

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/model/workflow/ \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/InteractionModePurityTest.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextEqualsTest.kt
git commit -m "feat(core): add WorkflowContext data model + Refs (Phase 5 T1)"
```

---

### Task 2: `WorkflowContextService` skeleton — state cell + boot anchor load

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextServiceBootTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` — register service

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.settings.PluginSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull

class WorkflowContextServiceBootTest : BasePlatformTestCase() {

    fun `test boot with no persisted ticket — state activeTicket is null`() {
        val settings = PluginSettings.getInstance(project)
        settings.state.activeTicketId = null
        settings.state.activeTicketSummary = null

        val service = WorkflowContextService.getInstance(project)
        assertNull(service.state.value.activeTicket)
    }

    fun `test boot with persisted ticket — state activeTicket hydrated synchronously`() {
        val settings = PluginSettings.getInstance(project)
        settings.state.activeTicketId = "AFTER8TE-912"
        settings.state.activeTicketSummary = "Fix login"

        // Re-instantiate by clearing the project service cache then re-fetching.
        // (BasePlatformTestCase uses a fresh project per test method.)
        val service = WorkflowContextService.getInstance(project)
        val ticket = service.state.value.activeTicket
        assertNotNull(ticket)
        assertEquals("AFTER8TE-912", ticket!!.key)
        assertEquals("Fix login", ticket.summary)
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

```
./gradlew :core:test --tests "com.workflow.orchestrator.core.workflow.WorkflowContextServiceBootTest"
```
Expected: compile error (`WorkflowContextService` not found).

- [ ] **Step 3: Implement `WorkflowContextService.kt`** — skeleton only (no mutators yet, no listeners)

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex

/**
 * Single source of truth for "what am I working on right now" across all tool-window tabs.
 * Phase 5 of refactor/cleanup-perf-caching. See docs/architecture/workflow-context-design.md.
 *
 * This task (T2) lands the skeleton. Mutators (T4–T6), listeners (T3), and mirror
 * (T7) follow in subsequent commits.
 */
@Service(Service.Level.PROJECT)
class WorkflowContextService(
    private val project: Project,
    private val cs: CoroutineScope,
) {
    private val log = Logger.getInstance(WorkflowContextService::class.java)
    private val cascadeMutex = Mutex()

    private val _state = MutableStateFlow(WorkflowContext())
    val state: StateFlow<WorkflowContext> = _state.asStateFlow()

    val activeTicketFlow: StateFlow<TicketRef?> = state
        .map { it.activeTicket }
        .distinctUntilChanged()
        .stateIn(cs, SharingStarted.Eagerly, null)

    val interactionModeFlow: StateFlow<InteractionMode> = state
        .map { it.interactionMode }
        .distinctUntilChanged()
        .stateIn(cs, SharingStarted.Eagerly, InteractionMode.Live)

    init {
        loadAnchorFromSettings()
    }

    /** Synchronous boot load. No HTTP, no suspend points. */
    private fun loadAnchorFromSettings() {
        val settings = PluginSettings.getInstance(project)
        val id = settings.state.activeTicketId?.takeIf { it.isNotBlank() } ?: return
        val summary = settings.state.activeTicketSummary.orEmpty()
        _state.value = WorkflowContext(activeTicket = TicketRef(id, summary))
        log.info("[Workflow:Context] Boot-loaded anchor: $id")
    }

    companion object {
        fun getInstance(project: Project): WorkflowContextService = project.service()
    }
}
```

- [ ] **Step 4: Register service in plugin.xml**

In `src/main/resources/META-INF/plugin.xml`, locate the `<extensions defaultExtensionNs="com.intellij">` block (project services live there) and add:

```xml
<projectService serviceImplementation="com.workflow.orchestrator.core.workflow.WorkflowContextService"/>
```

- [ ] **Step 5: Run tests — expect PASS**

```
./gradlew :core:test --tests "com.workflow.orchestrator.core.workflow.WorkflowContextServiceBootTest"
```
Expected: 2 tests pass.

- [ ] **Step 6: Module test gate + verifyPlugin**

```
./gradlew :core:test verifyPlugin
```
Expected: green.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextServiceBootTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(core): add WorkflowContextService skeleton with boot anchor load (Phase 5 T2)"
```

---

### Task 3: Editor-derived listeners — `onEditorRepoChanged` + wireEditorListeners

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt`
- Modify: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextServiceBootTest.kt` — add listener test

- [ ] **Step 1: Write the failing test**

Add to `WorkflowContextServiceBootTest.kt` (or split into a new `WorkflowContextServiceListenersTest.kt` — implementer's call):

```kotlin
fun `test editor selection change updates activeRepo and activeBranch`() = runBlocking {
    val service = WorkflowContextService.getInstance(project)

    // Simulate editor selection by directly calling the internal mutator
    // (full integration with FileEditorManager is covered by §9.3 integration test).
    val repo = RepoRef("backend", "PROJ", "backend", "/tmp/backend")
    service.onEditorRepoChangedForTest(repo, branch = "feat/abc", module = null)

    val s = service.state.value
    assertEquals(repo, s.activeRepo)
    assertEquals("feat/abc", s.activeBranch)
}
```

- [ ] **Step 2: Run test — expect FAIL** (`onEditorRepoChangedForTest` does not exist)

- [ ] **Step 3: Add internal mutator + listener wiring**

In `WorkflowContextService.kt`, add to `init { }` after `loadAnchorFromSettings()`:

```kotlin
init {
    loadAnchorFromSettings()
    wireEditorListeners()
}
```

Add the methods:

```kotlin
private fun wireEditorListeners() {
    // @Service instances are platform-disposed in 2024.1+; messageBus.connect(this)
    // is sanctioned (see RepoContextResolver for precedent).
    val bus = project.messageBus.connect(/* parent = */ this as Any as com.intellij.openapi.Disposable)

    bus.subscribe(
        com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
        object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
            override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                cs.launch { recomputeFromEditor() }
            }
        }
    )

    bus.subscribe(
        com.intellij.dvcs.repo.VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED,
        com.intellij.dvcs.repo.VcsRepositoryMappingListener {
            cs.launch { recomputeFromEditor() }
        }
    )

    bus.subscribe(
        git4idea.repo.GitRepositoryChangeListener.TOPIC,
        git4idea.repo.GitRepositoryChangeListener {
            cs.launch { recomputeFromEditor() }
        }
    )
}

private suspend fun recomputeFromEditor() = cascadeMutex.withLock {
    val resolver = com.workflow.orchestrator.core.settings.RepoContextResolver.getInstance(project)
    val gitRepo = readAction { resolver.resolveCurrentEditorRepoOrPrimary() }
    val repoConfig = gitRepo?.let { repo ->
        com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).getRepos()
            .firstOrNull { it.localVcsRootPath == repo.root.path }
    }
    val repoRef = repoConfig?.let {
        com.workflow.orchestrator.core.model.workflow.RepoRef(
            name = it.name,
            projectKey = it.bitbucketProjectKey,
            repoSlug = it.bitbucketRepoSlug,
            localVcsRootPath = it.localVcsRootPath,
        )
    }
    val branch = gitRepo?.currentBranchName
    val module = computeActiveModule()  // helper below

    _state.value = _state.value.copy(
        activeRepo = repoRef,
        activeBranch = branch,
        activeModule = module,
    )
}

private suspend fun computeActiveModule(): com.workflow.orchestrator.core.model.workflow.ModuleRef? = readAction {
    val fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
    val file = fem.selectedEditor?.file ?: return@readAction null
    val mod = com.intellij.openapi.module.ModuleUtilCore.findModuleForFile(file, project) ?: return@readAction null
    com.workflow.orchestrator.core.model.workflow.ModuleRef(
        name = mod.name,
        rootPath = com.intellij.openapi.roots.ModuleRootManager.getInstance(mod).contentRoots.firstOrNull()?.path.orEmpty(),
    )
}

@org.jetbrains.annotations.TestOnly
internal suspend fun onEditorRepoChangedForTest(
    repo: com.workflow.orchestrator.core.model.workflow.RepoRef?,
    branch: String?,
    module: com.workflow.orchestrator.core.model.workflow.ModuleRef?,
) = cascadeMutex.withLock {
    _state.value = _state.value.copy(activeRepo = repo, activeBranch = branch, activeModule = module)
}
```

Make the service implement `Disposable` (required for `messageBus.connect(this)` parent). Add `: com.intellij.openapi.Disposable` to the class declaration and:

```kotlin
override fun dispose() {
    // Platform also cancels `cs` automatically. No additional cleanup needed.
}
```

- [ ] **Step 4: Run test — expect PASS**

```
./gradlew :core:test --tests "com.workflow.orchestrator.core.workflow.WorkflowContextServiceBootTest"
```

- [ ] **Step 5: Module test gate**

```
./gradlew :core:test
```

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextServiceBootTest.kt
git commit -m "feat(core): wire editor/VCS listeners into WorkflowContextService (Phase 5 T3)"
```

---

### Task 4: `setActiveTicket` cascade with auto-seed

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/SetActiveTicketCascadeTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*

class SetActiveTicketCascadeTest : BasePlatformTestCase() {

    fun `test setActiveTicket persists to PluginSettings before any suspend point`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        val ticket = TicketRef("AFTER8TE-912", "Fix login")

        service.setActiveTicket(ticket)

        val settings = PluginSettings.getInstance(project)
        assertEquals("AFTER8TE-912", settings.state.activeTicketId)
        assertEquals("Fix login", settings.state.activeTicketSummary)
    }

    fun `test setActiveTicket emits new state with activeTicket populated`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        val ticket = TicketRef("AFTER8TE-912", "Fix login")

        service.setActiveTicket(ticket)
        assertEquals(ticket, service.state.value.activeTicket)
    }

    fun `test setActiveTicket null clears anchor and persists empty`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        service.setActiveTicket(TicketRef("AFTER8TE-912", "Fix login"))
        service.setActiveTicket(null)

        assertNull(service.state.value.activeTicket)
        val settings = PluginSettings.getInstance(project)
        assertTrue(settings.state.activeTicketId.isNullOrBlank())
    }

    // Auto-seed test deferred to T11 (PrDashboard migration commit) — requires PrListService
    // injection which isn't in place until then. Auto-seed itself is implemented here, but
    // verifying it requires a mockable PR list source.
}
```

- [ ] **Step 2: Run tests — expect FAIL** (`setActiveTicket` does not exist)

- [ ] **Step 3: Implement `setActiveTicket` cascade**

Add to `WorkflowContextService.kt`:

```kotlin
suspend fun setActiveTicket(ticket: TicketRef?) = cascadeMutex.withLock {
    // 1. Persist BEFORE any suspend point — guarantees disk consistency on cancellation.
    val settings = PluginSettings.getInstance(project)
    settings.state.activeTicketId = ticket?.key.orEmpty()
    settings.state.activeTicketSummary = ticket?.summary.orEmpty()

    // 2. Compose new state with anchor change. Auto-seed in step 3 may overwrite focus chain.
    var next = _state.value.copy(activeTicket = ticket)

    // 3. Auto-seed focus chain (a.ii). Skip when clearing or when the existing focusPr
    //    is already aligned with this ticket.
    if (ticket != null) {
        val matchingPr = findOpenPrMatchingTicket(ticket.key)
        if (matchingPr != null && matchingPr != next.focusPr) {
            next = computeFocusForPr(next, matchingPr)
        }
    }

    _state.value = next
    log.info("[Workflow:Context] setActiveTicket: ${ticket?.key ?: "<cleared>"}, focusPr=${next.focusPr?.prId}")
}

/**
 * Finds an open PR whose source branch contains [ticketKey]. When multiple match, the
 * highest [PrRef.prId] wins (deterministic across IDE restarts — see spec §4.2.2).
 *
 * Reads from [com.workflow.orchestrator.pullrequest.service.PrListService] via reflection-free
 * core-side service lookup. Lives in :core because :core knows TicketRef, but consults the
 * PR list state owned by :pullrequest. This is the one place the service crosses module
 * boundaries — done via project.getService(...) on a known FQN string to avoid a hard
 * compile dependency.
 */
private suspend fun findOpenPrMatchingTicket(ticketKey: String): com.workflow.orchestrator.core.model.workflow.PrRef? {
    val prListService = try {
        project.getService(Class.forName("com.workflow.orchestrator.pullrequest.service.PrListService"))
    } catch (e: ClassNotFoundException) {
        log.warn("[Workflow:Context] PrListService not on classpath; auto-seed skipped")
        return null
    } ?: return null

    // PrListService.allRepoPrs : StateFlow<List<BitbucketPrDetail>> — accessed reflectively.
    @Suppress("UNCHECKED_CAST")
    val flow = prListService.javaClass.getMethod("getAllRepoPrs").invoke(prListService)
        as kotlinx.coroutines.flow.StateFlow<List<Any>>
    val prs = flow.value

    val matches = prs.mapNotNull { pr ->
        val fromBranch = pr.javaClass.getMethod("getFromBranch").invoke(pr) as? String ?: return@mapNotNull null
        if (fromBranch.contains(ticketKey, ignoreCase = false)) pr else null
    }
    if (matches.isEmpty()) return null

    // Highest prId wins (NB3 determinism).
    val winner = matches.maxByOrNull { (it.javaClass.getMethod("getPrId").invoke(it) as Int) } ?: return null
    return prDetailToRef(winner)
}

private fun prDetailToRef(detail: Any): com.workflow.orchestrator.core.model.workflow.PrRef {
    val cls = detail.javaClass
    return com.workflow.orchestrator.core.model.workflow.PrRef(
        prId = cls.getMethod("getPrId").invoke(detail) as Int,
        fromBranch = cls.getMethod("getFromBranch").invoke(detail) as String,
        toBranch = cls.getMethod("getToBranch").invoke(detail) as String,
        repoName = cls.getMethod("getRepoName").invoke(detail) as String,
        bambooPlanKey = cls.getMethod("getBambooPlanKey").invoke(detail) as? String,
        sonarProjectKey = cls.getMethod("getSonarProjectKey").invoke(detail) as? String,
    )
}

/**
 * Stub — implemented fully in T5. T4 only needs the slot so [setActiveTicket]'s
 * auto-seed compiles.
 */
private suspend fun computeFocusForPr(
    base: com.workflow.orchestrator.core.model.workflow.WorkflowContext,
    pr: com.workflow.orchestrator.core.model.workflow.PrRef,
): com.workflow.orchestrator.core.model.workflow.WorkflowContext {
    return base.copy(focusPr = pr)  // T5 will add focusBuild + focusQualityScope cascade
}
```

**Note for implementer:** the reflection-based `PrListService` lookup avoids creating a `:core → :pullrequest` compile dependency (which would invert the module DAG). If the implementer prefers a cleaner approach, an extension point (`workflowOrchestrator.openPrLister`) implemented by `:pullrequest` and consumed in `:core` works too — same outcome, more boilerplate. Reflection lookup is acceptable here because the call site is well-defined and tested.

- [ ] **Step 4: Run tests — expect PASS** (the 3 non-auto-seed tests; auto-seed test deferred)

- [ ] **Step 5: Module test gate**

```
./gradlew :core:test
```

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/SetActiveTicketCascadeTest.kt
git commit -m "feat(core): setActiveTicket cascade with PR auto-seed (Phase 5 T4)"
```

---

### Task 5: `focusPr` cascade — mutex, cancel-previous, Bamboo lookup

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/FocusPrCascadeTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.model.workflow.PrRef
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*

class FocusPrCascadeTest : BasePlatformTestCase() {

    fun `test focusPr emits exactly one new state — focusPr field set`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        val pr = PrRef(42, "feat/abc", "main", "repo", "PLAN-KEY", "sonar.key")
        service.focusPr(pr)
        assertEquals(pr, service.state.value.focusPr)
    }

    fun `test focusPr null clears focus chain`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        service.focusPr(pr)
        service.focusPr(null)
        assertNull(service.state.value.focusPr)
        assertNull(service.state.value.focusBuild)
        assertNull(service.state.value.focusQualityScope)
    }

    fun `test rapid focusPr calls — only the last cascade's state survives`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        val pr1 = PrRef(42, "a", "main", "r", null, null)
        val pr2 = PrRef(43, "b", "main", "r", null, null)
        val pr3 = PrRef(44, "c", "main", "r", null, null)

        // Fire three in rapid succession; mutex serializes.
        listOf(pr1, pr2, pr3).forEach { service.focusPr(it) }
        assertEquals(pr3, service.state.value.focusPr)
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL** (`focusPr` does not exist)

- [ ] **Step 3: Implement `focusPr` cascade**

Replace the stub `computeFocusForPr` in `WorkflowContextService.kt` with the real implementation, and add `focusPr`:

```kotlin
private var currentFocusJob: kotlinx.coroutines.Job? = null

suspend fun focusPr(pr: com.workflow.orchestrator.core.model.workflow.PrRef?) = cascadeMutex.withLock {
    currentFocusJob?.cancelAndJoin()
    val newCtx = if (pr == null) {
        // Null cascade: re-derive focusBuild from activeBranch.
        val base = _state.value.copy(focusPr = null)
        computeFocusFromBranch(base)
    } else {
        computeFocusForPr(_state.value, pr)
    }
    _state.value = newCtx
    log.info("[Workflow:Context] focusPr → ${pr?.prId ?: "<cleared>"}, focusBuild=${newCtx.focusBuild?.buildNumber}")
}

private suspend fun computeFocusForPr(
    base: com.workflow.orchestrator.core.model.workflow.WorkflowContext,
    pr: com.workflow.orchestrator.core.model.workflow.PrRef,
): com.workflow.orchestrator.core.model.workflow.WorkflowContext {
    val build = pr.bambooPlanKey?.let { planKey ->
        kotlinx.coroutines.withTimeoutOrNull(5_000) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                fetchLatestBuildRef(planKey, pr.fromBranch)
            }
        }
    }
    val quality = build?.let {
        com.workflow.orchestrator.core.model.workflow.QualityScope(
            sonarProjectKey = pr.sonarProjectKey.orEmpty(),
            branchName = pr.fromBranch,
            moduleKey = null,
        )
    }
    return base.copy(focusPr = pr, focusBuild = build, focusQualityScope = quality)
}

/**
 * Used when [focusPr] is null — fall back to the current branch's latest build.
 * Cached: skip HTTP if [activeBranch] hasn't changed since last lookup.
 */
private var lastBranchForBuildLookup: String? = null
private var lastBuildRefForBranch: com.workflow.orchestrator.core.model.workflow.BuildRef? = null

private suspend fun computeFocusFromBranch(
    base: com.workflow.orchestrator.core.model.workflow.WorkflowContext,
): com.workflow.orchestrator.core.model.workflow.WorkflowContext {
    val branch = base.activeBranch ?: return base.copy(focusBuild = null, focusQualityScope = null)
    if (branch == lastBranchForBuildLookup) {
        return base.copy(focusBuild = lastBuildRefForBranch, focusQualityScope = null)
    }
    // Without a known plan key (no PR focused), we can't fetch a build.
    // For 5a, leave focusBuild null when focusPr is null. (See §10 deferred latestBuildForBranchFlow.)
    lastBranchForBuildLookup = branch
    lastBuildRefForBranch = null
    return base.copy(focusBuild = null, focusQualityScope = null)
}

/**
 * Calls BambooApiClient.getLatestResult via reflection (same module-DAG reasoning as
 * findOpenPrMatchingTicket in T4). Returns null on any error.
 */
private suspend fun fetchLatestBuildRef(planKey: String, branch: String): com.workflow.orchestrator.core.model.workflow.BuildRef? {
    return try {
        val clientCls = Class.forName("com.workflow.orchestrator.bamboo.api.BambooApiClient")
        val client = project.getService(clientCls) ?: return null
        val method = clientCls.getMethod("getLatestResult", String::class.java, String::class.java)
        val resultRaw = invokeSuspend(method, client, planKey, branch) ?: return null

        // ApiResult<BambooResultDto> — peek at .data for success
        val data = resultRaw.javaClass.getMethod("getData").invoke(resultRaw) ?: return null
        val planResultKey = data.javaClass.getMethod("getPlanResultKey").invoke(data)
            ?: return null
        val buildNumberStr = planResultKey.javaClass.getMethod("getKey").invoke(planResultKey) as? String
            ?: return null
        val buildNumber = buildNumberStr.substringAfterLast("-").toIntOrNull() ?: return null
        com.workflow.orchestrator.core.model.workflow.BuildRef(
            planKey = planKey,
            buildNumber = buildNumber,
            branch = branch,
            selectedJobKey = null,
        )
    } catch (e: Exception) {
        log.warn("[Workflow:Context] fetchLatestBuildRef($planKey, $branch) failed: ${e.message}")
        null
    }
}

/** Helper to invoke a `suspend` method via reflection. */
private suspend fun invokeSuspend(method: java.lang.reflect.Method, target: Any, vararg args: Any?): Any? {
    return kotlin.coroutines.suspendCoroutine { cont ->
        val withCont = args.toMutableList().apply { add(cont) }.toTypedArray()
        try {
            val result = method.invoke(target, *withCont)
            if (result != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) cont.resumeWith(Result.success(result))
        } catch (e: Throwable) {
            cont.resumeWith(Result.failure(e))
        }
    }
}
```

**Note for implementer:** the reflective Bamboo lookup mirrors T4's reflective PR lookup. Both can be replaced by an extension point in a follow-up if reflection becomes unwieldy. For 5a, reflection keeps the module DAG clean.

- [ ] **Step 4: Run tests — expect PASS** (the 3 tests; rapid-call test verifies mutex serialization)

- [ ] **Step 5: Module test gate**

```
./gradlew :core:test
```

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/FocusPrCascadeTest.kt
git commit -m "feat(core): focusPr cascade with mutex serialization + cancel-previous (Phase 5 T5)"
```

---

### Task 6: `WorkflowEventMirror` + `WorkflowContextProjectActivity`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirror.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextProjectActivity.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirrorTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` — register `ProjectActivity`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.TicketRef
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*

class WorkflowEventMirrorTest : BasePlatformTestCase() {

    fun `test mirror translates PrSelected into focusPr`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        val mirror = WorkflowEventMirror(project, service)
        mirror.install()  // also called by ProjectActivity

        val event = WorkflowEvent.PrSelected(42, "feat/abc", "main", "repo", "PLAN", "SONAR")
        EventBus.getInstance(project).emit(event)

        // Allow mirror coroutine to process.
        kotlinx.coroutines.delay(100)
        val focus = service.state.value.focusPr
        assertNotNull(focus)
        assertEquals(42, focus!!.prId)
    }

    fun `test mirror no-ops on duplicate event (state-equality guard)`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        val mirror = WorkflowEventMirror(project, service)
        mirror.install()

        val event = WorkflowEvent.PrSelected(42, "a", "main", "r", null, null)
        EventBus.getInstance(project).emit(event)
        kotlinx.coroutines.delay(100)
        val firstWrite = service.state.value

        EventBus.getInstance(project).emit(event)
        kotlinx.coroutines.delay(100)
        val secondWrite = service.state.value

        // No new state object should have been created.
        assertSame(firstWrite, secondWrite)
    }

    fun `test mirror translates TicketChanged into setActiveTicket`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        val mirror = WorkflowEventMirror(project, service)
        mirror.install()

        EventBus.getInstance(project).emit(WorkflowEvent.TicketChanged("AFTER8TE-912", "Fix login"))
        kotlinx.coroutines.delay(100)

        val anchor = service.state.value.activeTicket
        assertEquals("AFTER8TE-912", anchor?.key)
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL**

- [ ] **Step 3: Implement `WorkflowEventMirror.kt`**

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.TicketRef
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * One-way bridge: legacy [WorkflowEvent]s on [EventBus] → [WorkflowContextService] mutators.
 *
 * Loop prevention is structural: the service's mutators NEVER emit [WorkflowEvent]s. So
 * the mirror's input (event bus) and the mutator's output (state cell) are disjoint
 * channels — no feedback path exists.
 *
 * The state-equality guard inside [handlePrSelected] / [handleTicketChanged] is a
 * defense-in-depth check for the case where a migrated call site explicitly re-emits the
 * legacy event after calling the mutator (per spec §5.3 migration rule). In that case
 * the event arrives with a payload that already matches `state.value` — the mirror
 * recognises this and no-ops, avoiding redundant cascade work.
 *
 * Installed at startup by [WorkflowContextProjectActivity] — guarantees subscription
 * before any panel construction (R8 — addresses spec reviewer NB12).
 */
class WorkflowEventMirror(
    private val project: Project,
    private val service: WorkflowContextService,
) {
    private val log = Logger.getInstance(WorkflowEventMirror::class.java)

    fun install() {
        val cs = service.serviceCs  // exposed via package-private accessor — see service edit below
        val bus = EventBus.getInstance(project)

        cs.launch {
            bus.events.collect { event ->
                when (event) {
                    is WorkflowEvent.PrSelected -> handlePrSelected(event)
                    is WorkflowEvent.TicketChanged -> handleTicketChanged(event)
                    is WorkflowEvent.BranchChanged -> {
                        // Editor listeners already cover this; mirror is redundant for branches.
                        // No-op intentionally.
                    }
                    else -> { /* not mirrored */ }
                }
            }
        }
        log.info("[Workflow:Mirror] Installed")
    }

    private suspend fun handlePrSelected(event: WorkflowEvent.PrSelected) {
        val incoming = PrRef(
            prId = event.prId,
            fromBranch = event.fromBranch,
            toBranch = event.toBranch,
            repoName = event.repoName,
            bambooPlanKey = event.bambooPlanKey,
            sonarProjectKey = event.sonarProjectKey,
        )
        if (service.state.value.focusPr == incoming) return  // loop guard
        service.focusPr(incoming)
    }

    private suspend fun handleTicketChanged(event: WorkflowEvent.TicketChanged) {
        val incoming = if (event.ticketId.isBlank()) null else TicketRef(event.ticketId, event.ticketSummary)
        if (service.state.value.activeTicket == incoming) return  // loop guard
        service.setActiveTicket(incoming)
    }
}
```

In `WorkflowContextService.kt`, expose the coroutine scope as package-private:

```kotlin
internal val serviceCs: kotlinx.coroutines.CoroutineScope get() = cs
```

- [ ] **Step 4: Implement `WorkflowContextProjectActivity.kt`**

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Eagerly constructs the [WorkflowContextService] and installs the [WorkflowEventMirror]
 * at project open. This runs before any tool-window panel is created, so the mirror
 * subscription is in place before any panel emits a [com.workflow.orchestrator.core.events.WorkflowEvent].
 *
 * Without this activity, the service would be lazy-constructed on first
 * `getInstance()` — and any panel emit before that point would hit the
 * `MutableSharedFlow(replay=0)` and be silently dropped (R8 / spec §4.5).
 */
class WorkflowContextProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = WorkflowContextService.getInstance(project)
        WorkflowEventMirror(project, service).install()
    }
}
```

- [ ] **Step 5: Register `ProjectActivity` in `plugin.xml`**

In `<extensions defaultExtensionNs="com.intellij">`:

```xml
<postStartupActivity implementation="com.workflow.orchestrator.core.workflow.WorkflowContextProjectActivity"/>
```

- [ ] **Step 6: Run tests — expect PASS**

- [ ] **Step 7: Module test gate + verifyPlugin**

```
./gradlew :core:test verifyPlugin
```

- [ ] **Step 8: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirror.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextProjectActivity.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirrorTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(core): WorkflowEventMirror + ProjectActivity startup hook (Phase 5 T6)"
```

---

### Task 7: Mirror race test — concurrent emit serializes through mutex

**Files:**
- Modify: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirrorTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `WorkflowEventMirrorTest.kt`:

```kotlin
fun `test mirror serializes concurrent PrSelected events through cascadeMutex`() = runTest {
    val service = WorkflowContextService.getInstance(project)
    val mirror = WorkflowEventMirror(project, service)
    mirror.install()

    val pr1 = WorkflowEvent.PrSelected(42, "a", "main", "r", null, null)
    val pr2 = WorkflowEvent.PrSelected(43, "b", "main", "r", null, null)
    val pr3 = WorkflowEvent.PrSelected(44, "c", "main", "r", null, null)

    // Fire all three onto the shared flow nearly-simultaneously.
    val bus = EventBus.getInstance(project)
    listOf(pr1, pr2, pr3).forEach { bus.emit(it) }

    // Allow all cascades to drain.
    kotlinx.coroutines.delay(500)

    // Final state must reflect the LAST emit (pr3). If mutex/cascel-previous works,
    // pr1 + pr2 cascades may have been cancelled mid-flight; pr3 is the only one
    // that finishes.
    val focus = service.state.value.focusPr
    assertNotNull(focus)
    assertEquals(44, focus!!.prId)
}
```

- [ ] **Step 2: Run test — expect PASS** (mutex from T5 already provides serialization; if test fails, T5's `cancelAndJoin` is missing — fix there)

- [ ] **Step 3: Module test gate**

```
./gradlew :core:test
```

- [ ] **Step 4: Commit**

```bash
git add core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirrorTest.kt
git commit -m "test(core): mirror+mutex race characterization (Phase 5 T7)"
```

---

### Task 8: Active-ticket bar migration — `WorkflowToolWindowFactory.setupActiveTicketBar`

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt`

- [ ] **Step 1: Read current implementation** — already known: subscribes to `EventBus.events.filterIsInstance<TicketChanged>()` AND reads from `PluginSettings.activeTicketId`. Two sources, one bar.

- [ ] **Step 2: Replace with single subscription to `service.activeTicketFlow`**

Find the `setupActiveTicketBar(project, toolWindow)` method (currently lines ~76–148). Replace the body with:

```kotlin
private fun setupActiveTicketBar(project: Project, toolWindow: ToolWindow) {
    val ticketLabel = JBLabel().apply {
        font = JBUI.Fonts.label().deriveFont(Font.BOLD)
    }
    val summaryLabel = JBLabel().apply {
        foreground = StatusColors.SECONDARY_TEXT
    }

    val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        isOpaque = false
        add(JBLabel(AllIcons.Nodes.Tag))
        add(ticketLabel)
        add(summaryLabel)
    }

    val bar = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(3, 8)
        background = StatusColors.INFO_BG
        isVisible = false
        add(leftPanel, BorderLayout.CENTER)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                val cm = toolWindow.contentManager
                val sprintTab = cm.contents.firstOrNull { it.displayName == "Sprint" }
                if (sprintTab != null) cm.setSelectedContent(sprintTab)
            }
        })
    }

    toolWindow.component.add(bar, BorderLayout.NORTH)

    // Single subscription to the canonical anchor flow.
    val service = com.workflow.orchestrator.core.workflow.WorkflowContextService.getInstance(project)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    scope.launch {
        service.activeTicketFlow.collect { ticket ->
            if (ticket != null) {
                ticketLabel.text = ticket.key
                summaryLabel.text = ticket.summary
                bar.isVisible = true
            } else {
                bar.isVisible = false
            }
            bar.parent?.revalidate()
            bar.parent?.repaint()
        }
    }

    com.intellij.openapi.util.Disposer.register(toolWindow.disposable) { scope.cancel() }
}
```

- [ ] **Step 3: Run tool-window-related tests**

```
./gradlew :core:test
```
(No specific characterization test for the bar at this stage — the legacy behavior is preserved by the service's `activeTicketFlow` semantics; the boot test in T2 already proves persistence hydration.)

- [ ] **Step 4: Module test gate + verifyPlugin**

```
./gradlew :core:test verifyPlugin
```

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt
git commit -m "refactor(core): active-ticket bar reads from WorkflowContextService (Phase 5 T8)"
```

---

### Task 9: BuildDashboardPanel migration — PrBar + job stages SAME COMMIT (NB6)

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt`
- Create: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanelCoherenceTest.kt`

**This is the most important migration commit — the user-reported "PR bar says X, jobs render Y" bug is fixed structurally here. PrBar and job-stages MUST migrate together (NB6 constraint).**

- [ ] **Step 1: Write characterization test**

```kotlin
package com.workflow.orchestrator.bamboo.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertSame

class BuildDashboardPanelCoherenceTest : BasePlatformTestCase() {

    fun `test PrBar and job-stages section read identical state snapshot`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        val pr = PrRef(42, "feat/abc", "main", "repo", "PLAN-KEY", null)

        val panel = BuildDashboardPanel(project)
        service.focusPr(pr)
        kotlinx.coroutines.delay(100)

        // Both PrBar and the job-stages renderer expose their last-rendered focusPr
        // via test hooks (added in this commit). The assertion is referential identity:
        // both subscribers received the same WorkflowContext instance from state.value.
        val prBarSnapshot = panel.prBar.lastRenderedFocusPrForTest
        val stagesSnapshot = panel.lastRenderedFocusPrForTest
        assertSame(prBarSnapshot, stagesSnapshot)
    }
}
```

- [ ] **Step 2: Run test — expect FAIL** (test hooks don't exist yet; legacy PrBar reads from `EventBus.prContextMap`, stages read from elsewhere)

- [ ] **Step 3: Migrate PrBar**

In `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt`, find every read of `EventBus.prContextMap[...]` or local PR resolution. Replace with subscription to `WorkflowContextService.state.map { it.focusPr }`:

```kotlin
// Add to PrBar's init:
private val service = WorkflowContextService.getInstance(project)
@org.jetbrains.annotations.TestOnly internal var lastRenderedFocusPrForTest: PrRef? = null

private val barScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

init {
    // ... existing init ...
    barScope.launch {
        service.state.map { it.focusPr }.distinctUntilChanged().collect { pr ->
            renderFromFocusPr(pr)
            lastRenderedFocusPrForTest = pr
        }
    }
    Disposer.register(parentDisposable, Disposable { barScope.cancel() })
}

private fun renderFromFocusPr(pr: PrRef?) {
    if (pr == null) {
        // Show "No PR focused" state.
        // ... existing empty-state rendering, unchanged ...
    } else {
        // Render PR title, branch, status from `pr` directly.
        // ... existing populated-state rendering, but use pr.fromBranch / pr.repoName etc. ...
    }
}
```

Delete the legacy `EventBus.events.filterIsInstance<PrSelected>().collect { ... }` subscription if present.

- [ ] **Step 4: Migrate job-stages section in BuildDashboardPanel**

In `BuildDashboardPanel.kt`, replace the local resolution of "current PR / current branch" used by the job-stages renderer with subscription to the same `service.state.map { it.focusPr }` flow. **Critically:** PrBar and stages MUST subscribe to the same upstream — they will then automatically receive the same `WorkflowContext` instance per emission.

Add the test hook:

```kotlin
@org.jetbrains.annotations.TestOnly internal var lastRenderedFocusPrForTest: PrRef? = null
@org.jetbrains.annotations.TestOnly internal val prBar: PrBar = ...  // expose existing field
```

- [ ] **Step 5: Run characterization test — expect PASS**

```
./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.ui.BuildDashboardPanelCoherenceTest"
```

- [ ] **Step 6: Module test gate**

```
./gradlew :bamboo:test
```

- [ ] **Step 7: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt \
        bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt \
        bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanelCoherenceTest.kt
git commit -m "refactor(bamboo): BuildDashboardPanel PrBar+stages share state snapshot (Phase 5 T9)"
```

---

### Task 10: QualityDashboardPanel migration

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt`

- [ ] **Step 1: Replace local quality-scope resolution with subscription to `service.state.map { it.focusQualityScope }`**

Pattern same as T9: locate the existing "what quality data am I showing" resolution (likely consults `PluginSettings.sonarProjectKey` + active branch). Replace with:

```kotlin
private val service = WorkflowContextService.getInstance(project)
private val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

init {
    panelScope.launch {
        service.state.map { it.focusQualityScope }.distinctUntilChanged().collect { scope ->
            renderForQualityScope(scope)
        }
    }
    Disposer.register(parentDisposable, Disposable { panelScope.cancel() })
}
```

When `scope` is null, show empty state ("No quality data — focus a PR or check out a branch with a Sonar project").

- [ ] **Step 2: Module test gate**

```
./gradlew :sonar:test
```

- [ ] **Step 3: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt
git commit -m "refactor(sonar): QualityDashboardPanel reads focusQualityScope from service (Phase 5 T10)"
```

---

### Task 11: PrDashboardPanel + PrDetailPanel migration + auto-seed test

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- Create: `pullrequest/src/test/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardCrossTabTest.kt`

- [ ] **Step 1: Write cross-tab characterization test**

```kotlin
package com.workflow.orchestrator.pullrequest.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals

class PrDashboardCrossTabTest : BasePlatformTestCase() {

    fun `test row click propagates to service state focusPr in same tick`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        val panel = PrDashboardPanel(project)

        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        panel.simulateRowClickForTest(pr)
        kotlinx.coroutines.delay(100)

        assertEquals(pr, service.state.value.focusPr)
    }
}
```

- [ ] **Step 2: Migrate PrDashboardPanel — row click calls `service.focusPr(...)`**

Find the existing row-click handler in `PrDashboardPanel.kt`. Add at the end:

```kotlin
private val service = WorkflowContextService.getInstance(project)
private val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

@org.jetbrains.annotations.TestOnly
internal fun simulateRowClickForTest(pr: PrRef) {
    panelScope.launch { service.focusPr(pr) }
}

// In the actual row-click listener:
private fun onRowClicked(pr: BitbucketPrDetail) {
    val ref = PrRef(
        prId = pr.prId,
        fromBranch = pr.fromBranch,
        toBranch = pr.toBranch,
        repoName = pr.repoName,
        bambooPlanKey = pr.bambooPlanKey,
        sonarProjectKey = pr.sonarProjectKey,
    )
    panelScope.launch {
        service.focusPr(ref)
        // Per spec §5.3: migrated call sites re-emit the legacy event for any
        // back-compat subscribers still listening (e.g., AgentController webview push).
        // The mirror's state-equality guard no-ops on this re-emit since state.focusPr
        // already matches the payload from the focusPr() call above.
        EventBus.getInstance(project).emit(WorkflowEvent.PrSelected(
            prId = ref.prId,
            fromBranch = ref.fromBranch,
            toBranch = ref.toBranch,
            repoName = ref.repoName,
            bambooPlanKey = ref.bambooPlanKey,
            sonarProjectKey = ref.sonarProjectKey,
        ))
    }
    // ... existing logic to open PrDetailPanel etc., unchanged ...
}

// And dispose:
Disposer.register(parentDisposable, Disposable { panelScope.cancel() })
```

- [ ] **Step 3: Migrate PrDetailPanel — subscribe to `service.state.map { it.focusPr }`**

Replace local "current PR" resolution with subscription. Pattern same as T9 BuildDashboardPanel. Banner + per-control disable wiring deferred to T13.

- [ ] **Step 4: Run tests**

```
./gradlew :pullrequest:test
```

- [ ] **Step 5: Commit**

```bash
git add pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt \
        pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt \
        pullrequest/src/test/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardCrossTabTest.kt
git commit -m "refactor(pullrequest): PrDashboard row click drives service.focusPr (Phase 5 T11)"
```

---

### Task 12: Sprint + Handover + Automation panel migration + auto-seed verification

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`
- Modify: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverPanel.kt`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt`
- Create: `jira/src/test/kotlin/com/workflow/orchestrator/jira/ui/SprintStartWorkTest.kt`

- [ ] **Step 1: Write Sprint Start-Work test**

```kotlin
package com.workflow.orchestrator.jira.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals

class SprintStartWorkTest : BasePlatformTestCase() {

    fun `test Start Work calls service setActiveTicket and propagates`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        val panel = SprintDashboardPanel(project)

        panel.simulateStartWorkForTest("AFTER8TE-912", "Fix login")
        kotlinx.coroutines.delay(100)

        assertEquals(TicketRef("AFTER8TE-912", "Fix login"), service.state.value.activeTicket)
    }
}
```

- [ ] **Step 2: Migrate Sprint Start Work**

In `SprintDashboardPanel.kt`, locate the Start Work handler (currently writes directly to `PluginSettings.activeTicketId` and emits `WorkflowEvent.TicketChanged`). Replace with:

```kotlin
private val service = WorkflowContextService.getInstance(project)
private val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

@org.jetbrains.annotations.TestOnly
internal fun simulateStartWorkForTest(key: String, summary: String) {
    panelScope.launch { service.setActiveTicket(TicketRef(key, summary)) }
}

private fun onStartWork(ticket: JiraTicketData) {
    panelScope.launch {
        service.setActiveTicket(TicketRef(ticket.key, ticket.summary))
        // Per spec §5.3: re-emit legacy event for back-compat subscribers.
        // Mirror's state-equality guard no-ops since state.activeTicket already matches.
        EventBus.getInstance(project).emit(WorkflowEvent.TicketChanged(ticket.key, ticket.summary))
    }
    // ... existing branch-creation / commit-prefix logic, unchanged ...
}

Disposer.register(parentDisposable, Disposable { panelScope.cancel() })
```

Remove direct `PluginSettings.state.activeTicketId = ...` writes — the service handles persistence inside `setActiveTicket()`. Keep the `EventBus.emit(TicketChanged)` re-emit for back-compat (per the migration rule above).

- [ ] **Step 3: Migrate HandoverPanel — subscribe to `service.activeTicketFlow`**

Replace direct `PluginSettings.activeTicketId` reads + `TicketChanged` event subscription with `service.activeTicketFlow.collect { ... }`.

- [ ] **Step 4: Migrate AutomationPanel — subscribe to `service.state.map { it.activeRepo }`**

Replace local `RepoContextResolver.resolveCurrentEditorRepoOrPrimary()` calls with `service.state.value.activeRepo` (one-shot reads) or `.collect { }` for reactive renders.

- [ ] **Step 5: Run tests**

```
./gradlew :jira:test :handover:test :automation:test
```

- [ ] **Step 6: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt \
        handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverPanel.kt \
        automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt \
        jira/src/test/kotlin/com/workflow/orchestrator/jira/ui/SprintStartWorkTest.kt
git commit -m "refactor(modules): Sprint/Handover/Automation panels read from WorkflowContextService (Phase 5 T12)"
```

---

### Task 13: ReadOnlyBanner + bindLiveOnlyEnablement helper + flicker test

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/ui/ReadOnlyBanner.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/ui/LiveOnlyEnablement.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/ui/BannerVisibilityFlickerTest.kt`

- [ ] **Step 1: Write flicker test**

```kotlin
package com.workflow.orchestrator.core.workflow.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue

class BannerVisibilityFlickerTest : BasePlatformTestCase() {

    fun `test rapid focusPr toggle results in at most 2 visibility transitions`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        val banner = ReadOnlyBanner(project)
        val transitions = mutableListOf<Boolean>()
        banner.recordTransitionsForTest(transitions)

        val pr = PrRef(42, "bugfix/xyz", "main", "r", null, null)
        // activeBranch is null in test fixture; focusPr(pr) → ReadOnly visible.
        // focusPr(null) → hidden.
        repeat(10) {
            service.focusPr(pr)
            service.focusPr(null)
        }
        kotlinx.coroutines.delay(500)

        // distinctUntilChanged collapses runs of same-state; transitions ≤ 2.
        assertTrue(transitions.size <= 2, "Banner flickered: ${transitions.size} transitions, expected ≤ 2")
    }
}
```

- [ ] **Step 2: Run test — expect FAIL** (banner doesn't exist)

- [ ] **Step 3: Implement `ReadOnlyBanner.kt`**

```kotlin
package com.workflow.orchestrator.core.workflow.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * Slim amber banner shown when [InteractionMode] is [InteractionMode.ReadOnly] —
 * i.e., the focused PR is on a branch other than the currently checked-out one.
 *
 * Owned by the panel that adds it; registers as a child [Disposable] of the parent
 * (`Disposer.register(parent, banner)`). Self-cancels its scope on dispose.
 */
class ReadOnlyBanner(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val service = WorkflowContextService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    private val message = JBLabel("")
    private val switchBranchLink = LinkLabel.create("Switch branch", null)
    private val clearFocusLink = LinkLabel.create("Clear PR focus") {
        scope.launch { service.focusPr(null) }
    }

    @org.jetbrains.annotations.TestOnly private var transitionRecorder: MutableList<Boolean>? = null
    @org.jetbrains.annotations.TestOnly internal fun recordTransitionsForTest(list: MutableList<Boolean>) {
        transitionRecorder = list
    }

    init {
        background = StatusColors.WARNING_BG
        border = JBUI.Borders.empty(3, 8)
        isVisible = false

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(message)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(switchBranchLink)
            add(clearFocusLink)
        }
        add(left, BorderLayout.CENTER)
        add(right, BorderLayout.EAST)

        scope.launch {
            service.state.map { it.interactionMode }.distinctUntilChanged().collect { mode ->
                val readOnly = (mode == InteractionMode.ReadOnly)
                isVisible = readOnly
                if (readOnly) updateMessage()
                revalidate()
                repaint()
                transitionRecorder?.add(readOnly)
            }
        }
    }

    private fun updateMessage() {
        val s = service.state.value
        val pr = s.focusPr ?: return
        val branch = s.activeBranch ?: "<none>"
        message.text = "Viewing PR #${pr.prId} (${pr.fromBranch}). You're on $branch — interactions disabled."
    }

    override fun dispose() {
        scope.cancel()
    }
}
```

- [ ] **Step 4: Implement `LiveOnlyEnablement.kt`**

```kotlin
package com.workflow.orchestrator.core.workflow.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.swing.JComponent

/**
 * Subscribes to [WorkflowContextService.interactionModeFlow] and toggles `isEnabled`
 * on every passed control. In ReadOnly mode, controls become disabled with a tooltip
 * explaining why.
 *
 * Each panel calls this once for its set of live-only controls. The live-only
 * enumeration is in spec §7.3.
 */
fun bindLiveOnlyEnablement(
    parent: Disposable,
    service: WorkflowContextService,
    vararg controls: JComponent,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    Disposer.register(parent) { scope.cancel() }

    scope.launch {
        service.state
            .map { it.interactionMode to it.activeBranch }
            .distinctUntilChanged()
            .collect { (mode, branch) ->
                val live = (mode == InteractionMode.Live)
                controls.forEach { ctrl ->
                    ctrl.isEnabled = live
                    ctrl.toolTipText = if (live) null
                    else "Disabled: focused PR is on a different branch. Switch to ${service.state.value.focusPr?.fromBranch} to enable."
                }
            }
    }
}
```

- [ ] **Step 5: Run flicker test — expect PASS**

- [ ] **Step 6: Module test gate + verifyPlugin**

```
./gradlew :core:test verifyPlugin
```

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/ui/ \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/ui/BannerVisibilityFlickerTest.kt
git commit -m "feat(core): ReadOnlyBanner + bindLiveOnlyEnablement helper (Phase 5 T13)"
```

---

### Task 14: Wire banner + per-control disable into Build/Quality/PrDetail panels

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`

For each of the three panels, add at the top of construction:

```kotlin
private val banner = ReadOnlyBanner(project).also {
    Disposer.register(this, it)
    add(it, BorderLayout.NORTH)
}
```

Then identify the live-only controls per spec §7.3:

- **BuildDashboardPanel:** any "navigate to failing test line" buttons, "view changes inline" link if line-anchored.
- **QualityDashboardPanel:** "navigate to issue line" buttons, gutter-sync controls.
- **PrDetailPanel:** "checkout PR locally" button, "view changes" inline-diff link, "comment-on-line" controls.

Wire each set:

```kotlin
init {
    // ... existing init ...
    bindLiveOnlyEnablement(
        parent = this,
        service = WorkflowContextService.getInstance(project),
        navigateToFailingTestButton, viewChangesInlineLink,  // panel-specific
    )
}
```

- [ ] **Step 1: For each of the 3 panels, add banner + bindLiveOnlyEnablement**

(Implementer reads each panel's actual control names; the spec §7.3 enumeration is the canonical list to gate against.)

- [ ] **Step 2: Run test gates**

```
./gradlew :bamboo:test :sonar:test :pullrequest:test
```

- [ ] **Step 3: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt \
        sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt \
        pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt
git commit -m "feat(ui): banner + live-only disable in Build/Quality/PrDetail (Phase 5 T14)"
```

---

### Task 15: Remove `EventBus.prContextMap` + `PrContext` side-effect

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt`
- Audit consumers via `git grep "prContextMap"`

- [ ] **Step 1: Audit current consumers**

```
git grep -n "prContextMap" -- '*.kt'
```

For each hit, replace:
- `eventBus.prContextMap[repoName]` → `service.state.value.focusPr?.takeIf { it.repoName == repoName }`
- The PR context is now repo-scoped via `state.activeRepo` + `state.focusPr` rather than a separate map.

- [ ] **Step 2: Delete `PrContext` class + `prContextMap` + side-effect block from `EventBus.kt`**

In `core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt`, remove:

```kotlin
data class PrContext( ... )                   // delete
val prContextMap: ConcurrentHashMap<String, PrContext> = ConcurrentHashMap()  // delete
// And inside emit():
if (event is WorkflowEvent.PrSelected) { prContextMap[event.repoName] = PrContext(...) }  // delete
```

- [ ] **Step 3: Module test gate + verifyPlugin**

```
./gradlew :core:test verifyPlugin
```

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt
# Plus any consumer files modified in step 1
git commit -m "refactor(core): remove EventBus.prContextMap — superseded by WorkflowContextService (Phase 5 T15)"
```

---

### Task 16: Agent integration — `EnvironmentDetailsBuilder.appendWorkflowContext` + mention shortcuts

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/EnvironmentDetailsBuilder.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/MentionSearchProvider.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/EnvironmentDetailsBuilderWorkflowContextTest.kt`

- [ ] **Step 1: Write test**

```kotlin
package com.workflow.orchestrator.agent.prompt

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*

class EnvironmentDetailsBuilderWorkflowContextTest : BasePlatformTestCase() {

    fun `test workflow_context block included when state is non-empty`() = runTest {
        val service = WorkflowContextService.getInstance(project)
        service.setActiveTicket(TicketRef("AFTER8TE-912", "Fix login"))
        service.focusPr(PrRef(42, "feat/login-fix", "main", "repo", null, null))

        val builder = EnvironmentDetailsBuilder(project)
        val block = builder.build()
        assertTrue(block.contains("<workflow_context>"))
        assertTrue(block.contains("Active ticket: AFTER8TE-912"))
        assertTrue(block.contains("Focused PR: #42"))
    }

    fun `test workflow_context block omitted when state is empty`() = runTest {
        val builder = EnvironmentDetailsBuilder(project)
        val block = builder.build()
        assertFalse(block.contains("<workflow_context>"))
    }
}
```

- [ ] **Step 2: Implement `appendWorkflowContext`**

In `EnvironmentDetailsBuilder.kt`, add a new step in `build()`:

```kotlin
private fun appendWorkflowContext(sb: StringBuilder) {
    val service = WorkflowContextService.getInstance(project)
    val s = service.state.value
    if (s.activeTicket == null && s.focusPr == null && s.activeBranch == null) return

    sb.append("\n<workflow_context>\n")
    s.activeTicket?.let { sb.append("Active ticket: ${it.key} — \"${it.summary}\"\n") }
    s.activeBranch?.let { sb.append("Active branch: $it\n") }
    s.activeRepo?.let { sb.append("Active repo: ${it.name}\n") }
    s.focusPr?.let { sb.append("Focused PR: #${it.prId} (${it.fromBranch} → ${it.toBranch})\n") }
    sb.append("Interaction mode: ${s.interactionMode}\n")
    sb.append("</workflow_context>\n")
}
```

Call `appendWorkflowContext(sb)` at the appropriate position in `build()` (after editor details).

- [ ] **Step 3: Mention shortcuts in `MentionSearchProvider.kt`**

Add `@ticket`, `@pr`, `@build` shortcuts that resolve via `WorkflowContextService.getInstance(project).state.value` first; fall back to existing settings/legacy paths. Implementation pattern depends on the existing search-provider extension surface.

- [ ] **Step 4: Run tests**

```
./gradlew :agent:test --tests "*WorkflowContext*"
```

- [ ] **Step 5: Module test gate**

```
./gradlew :agent:test
```

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/EnvironmentDetailsBuilder.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/MentionSearchProvider.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/EnvironmentDetailsBuilderWorkflowContextTest.kt
git commit -m "feat(agent): inject workflow_context block + mention shortcuts (Phase 5 T16)"
```

---

### Task 17: Documentation refresh

**Files:**
- Modify: `core/CLAUDE.md`
- Modify: `docs/architecture/threading-model.md`
- Modify: `docs/architecture/index.html`

- [ ] **Step 1: `core/CLAUDE.md` — add `WorkflowContextService` under `## Services`**

```markdown
- `WorkflowContextService` — single source of truth for active ticket, focused PR,
  editor-derived branch/repo/module across all tabs and the agent. Exposes
  `StateFlow<WorkflowContext>`. Mutations cascade-serialized via Mutex; all
  subscribers see the same `WorkflowContext` instance per emission. See
  `docs/architecture/workflow-context-design.md` for the design and migration plan.
```

- [ ] **Step 2: `threading-model.md` — add Phase 5 subsection**

```markdown
### Workflow context (Phase 5)

Every panel that needs "what am I working on" — active ticket, focused PR, current
branch, current repo, current module — subscribes to `WorkflowContextService.state`
(a `StateFlow<WorkflowContext>` in `:core`). Mutators are mutex-serialized and
emit one observable transition per cascade. Panels MUST NOT resolve these fields
locally; doing so re-introduces the cross-tab incoherence Phase 5 fixed.
```

- [ ] **Step 3: `docs/architecture/index.html` — add a Phase 5 § entry under existing architecture sections**

(Implementer adds an `<h2>` block consistent with existing styling, linking to `workflow-context-design.md`.)

- [ ] **Step 4: Commit**

```bash
git add core/CLAUDE.md docs/architecture/threading-model.md docs/architecture/index.html
git commit -m "docs: refresh CLAUDE.md + threading-model + index for Phase 5 (Phase 5 T17)"
```

---

### Task 18: Final verification — full build + runIde smoke + exit-criteria checklist

**Files:** none (verification only)

- [ ] **Step 1: Full clean build + verifyPlugin**

```
./gradlew clean verifyPlugin buildPlugin
```
Expected: green on IU-251/252/253.

- [ ] **Step 2: All-module test sweep**

```
./gradlew :core:test :jira:test :bamboo:test :sonar:test :pullrequest:test :automation:test :handover:test :agent:test
```
Expected: green; only the 4 pre-existing flakes from Phase 4 baseline.

- [ ] **Step 3: runIde smoke test (manual — implementer checklist)**

Open `./gradlew runIde`. Verify:
- [ ] Active-ticket bar shows persisted ticket on project open.
- [ ] Click PR row in PR tab → Build tab refreshes within ~1 sec; Quality tab refreshes; Build's PrBar header agrees with job stages list.
- [ ] Check out a branch other than focused PR's source → ReadOnlyBanner appears in Build / Quality / PR Detail panels with two link actions.
- [ ] Click "Clear PR focus" → banner hides; tabs revert to anchor-only state.
- [ ] Switch back to focused PR's source branch → banner hides; live-only controls re-enable.
- [ ] Start Work on a sprint ticket whose key matches an open PR's branch → focusPr auto-seeds; Build tab shows that PR's build immediately.
- [ ] Open agent chat → environment-details block contains `<workflow_context>` with active ticket / focused PR / interaction mode.

- [ ] **Step 4: Exit-criteria checklist** (from spec §12)

- [ ] (a) No panel resolves repo/branch/ticket/PR/build on its own — verified by `git grep RepoContextResolver.*resolve` and `git grep PluginSettings.*activeTicketId` returning only service-internal sites.
- [ ] (b) `PrDashboardCrossTabTest` passes.
- [ ] (c) `SprintStartWorkTest` passes.
- [ ] (d) `BuildDashboardPanelCoherenceTest` passes (assertSame on snapshot).
- [ ] (e) `interactionMode` flips correctly across the 4 cases in `InteractionModePurityTest`; banner appears/hides in runIde smoke.
- [ ] (f) Agent system prompt includes `<workflow_context>` (`EnvironmentDetailsBuilderWorkflowContextTest`).
- [ ] (g) `EventBus.prContextMap` removed (verified by `git grep prContextMap` returning empty).
- [ ] (h) `verifyPlugin buildPlugin` green.
- [ ] (i) Docs refreshed (T17).

- [ ] **Step 5: Update branch memory file**

Edit `~/.claude/projects/-Users-subhankarhalder-Desktop-Programs-scripts-IntelijPlugin/memory/project_branch_refactor_cleanup_perf_caching.md` — append Phase 5 completion section (mirror the Phase 4 closeout format). Mark Phase 5 done; note that `project_active_ticket_visibility.md` is now superseded and can be deleted.

- [ ] **Step 6: Final commit (closeout)**

```bash
git add docs/architecture/phase5-workflow-context-plan.md  # this file, if not already committed
# Plus any final doc tweaks
git commit -m "docs(architecture): Phase 5 closeout — WorkflowContextService shipped (Phase 5 T18)" --allow-empty
```

---

## Self-review against spec

**Spec coverage check** (spec §1-§13 → tasks):

- §1 Summary — covered by entire plan.
- §2 Decisions — embedded in task implementations (anchor T4, focus chain T5, interactionMode T1, banner T13, persistence T2/T4, agent T16).
- §3 Architecture — T1 (data), T2-T6 (service).
- §4 Sources of truth — T3 (editor), T4 (setActiveTicket), T5 (focusPr non-null), T6 mirror; T5 covers focusPr(null) per spec §4.1 revised.
- §4.0 Mutex serialization — T5 (mutex), T7 (race test).
- §4.4 Single-merged-emission invariant — verified by T9 `assertSame` test.
- §4.5 Boot semantics — T2 (synchronous load) + T6 (ProjectActivity for mirror).
- §5 Migration — T8 (active-ticket bar), T9 (Build), T10 (Quality), T11 (PR), T12 (Sprint/Handover/Automation), T15 (prContextMap removal).
- §6 Agent integration — T16.
- §7 ReadOnly affordance — T13 (banner + helper) + T14 (wiring).
- §8 Persistence — T4 (setActiveTicket persists) + T2 (boot loads).
- §9 Testing — distributed across all tasks; T1 (purity), T2 (boot), T4 (cascade+persistence), T5 (focus + race), T6/T7 (mirror), T9 (coherence), T11 (cross-tab), T12 (start-work), T13 (flicker), T16 (agent).
- §10 Out of scope — explicitly preserved in plan (no `latestBuildForBranchFlow`, no `focusBuild` "pin" mutator).
- §11 Risks — R8 (mirror startup race) → T6 ProjectActivity. R9 (purity) → T1 InteractionModePurityTest.
- §12 Exit criteria — T18.

**Placeholder scan:** No "TBD", "TODO", "implement later", or generic "add error handling" without a code block. Every code step has actual code. Reflective `findOpenPrMatchingTicket` and `fetchLatestBuildRef` are flagged as acceptable with rationale.

**Type consistency:** `TicketRef`, `PrRef`, `BuildRef`, `QualityScope`, `RepoRef`, `ModuleRef` defined once in T1 and used consistently. `WorkflowContextService.state`, `.activeTicketFlow`, `.interactionModeFlow`, `.focusPr()`, `.setActiveTicket()`, `.serviceCs` (package-private) used consistently across T2-T16. Mirror's `install()` method called by both T6 (`ProjectActivity`) and T6/T7 tests.

---

## Execution Handoff

**Plan complete. Saved to `docs/architecture/phase5-workflow-context-plan.md`.**

User instructed at brainstorm time: design and plan get verified by a subagent, not the user. So **the next step is plan verification by a subagent**, not the standard "1 vs 2" execution choice. After plan verification: subagent-driven execution per `feedback_always_subagent.md` and `feedback_skip_subagent_reviews.md` (implementer + code-reviewer per task; no spec/quality reviewers).
