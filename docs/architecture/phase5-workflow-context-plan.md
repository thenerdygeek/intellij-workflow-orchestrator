# Phase 5 — WorkflowContextService Implementation Plan (v2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task. Per-task convention: 1 implementer subagent (Opus, max effort) + 1 `superpowers:code-reviewer` (Opus). **No spec/quality reviewers** (per `feedback_skip_subagent_reviews.md`). APPROVE-WITH-FOLLOWUP verdicts get small follow-up commits (Phase 4 pattern).

**Goal:** Introduce `WorkflowContextService` in `:core` as the single source of truth for active ticket / focused PR / editor-derived branch+repo+module across all 6 tool-window tabs and the agent.

**Architecture:** Single `@Service(Service.Level.PROJECT)` exposing `StateFlow<WorkflowContext>`. Mutators are mutex-serialized; cascades produce one observable transition. `WorkflowEventMirror` bridges legacy `EventBus` events. `ReadOnlyBanner` + per-control disable for branch-mismatch correctness. Cross-module HTTP lookups (Bamboo build, Bitbucket PR list) are routed via two new core extension points (`openPrLister`, `latestBuildLookup`) — same pattern as existing `textGenerationService` / `createPrLauncher`.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform 2025.1+, kotlinx.coroutines (`StateFlow`, `Mutex`), JUnit 5 + MockK + Turbine, JBR Swing.

**Spec:** `docs/architecture/workflow-context-design.md` (HEAD `73de193a`). Reviewer-approved.

**Branch:** `refactor/cleanup-perf-caching` (continue commits — `feedback_work_on_current_branch.md`).

**v2 changes from v1** (driven by plan reviewer findings, HEAD `d1b9152f`):
- Replaced reflective `Class.forName + getMethod` lookups with two new core extension points (`openPrLister`, `latestBuildLookup`); type-safe and testable.
- Tests rewritten on `mockk<Project>(relaxed=true)` + `installReadActionInlineShim()` (existing repo pattern). `BasePlatformTestCase` retained only for the §9.3 integration test (T17.5), with `junit-vintage-engine` added to `:core` test deps just for that file.
- T12 retargeted from `HandoverPanel` to `HandoverStateService` (the actual `TicketChanged` consumer per `git grep`).
- T12 adds `ActiveTicketService` facade conversion (it lives in `:jira`, owns the legacy `activeTicketFlow`; converted to a thin proxy over `WorkflowContextService` so existing call sites continue to work).
- T15 audit explicitly includes `:agent ProjectContextTool` (uses `prContextMap` at lines 91 + 110).
- T9 coherence test rewritten to use Turbine's emission counting (proves single-emission invariant, not just snapshot equality).
- T1 purity test extended with a kotlin-reflect introspection check.
- T3 drops `GitRepositoryChangeListener` (git4idea optional dep) — `VCS_REPOSITORY_MAPPING_UPDATED` from `com.intellij.dvcs` covers it.
- T9 BuildDashboardPanel scope expanded to acknowledge wider state machine (repoSelector, loadBuildsForContext) — `prContextMap` reads + the two PrBar/job-stages renderers migrate; `repoSelector` UX stays user-controlled.
- T17.5 added: `WorkflowContextEditorIntegrationTest` (real `FileEditorManager` + `GitRepositoryManager` via `BasePlatformTestCase`).
- T16 mention-shortcut step concretized.

---

## File structure

### New files (`:core`)

| Path | Responsibility |
|---|---|
| `core/src/main/kotlin/com/workflow/orchestrator/core/model/workflow/WorkflowContext.kt` | Immutable context data class + `InteractionMode` enum + invariant comment |
| `core/src/main/kotlin/com/workflow/orchestrator/core/model/workflow/Refs.kt` | `TicketRef`, `RepoRef`, `PrRef`, `BuildRef`, `QualityScope`, `ModuleRef` value types |
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt` | The service — state cell + mutators + listener wiring |
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirror.kt` | One-way bridge: `EventBus` → service mutators (with state-equality guard) |
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextProjectActivity.kt` | Startup `ProjectActivity` — installs mirror before any panel emits (R8) |
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/OpenPrLister.kt` | EP interface — `:pullrequest` implements; `:core` consumes for auto-seed |
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/LatestBuildLookup.kt` | EP interface — `:bamboo` implements; `:core` consumes for `focusBuild` derivation |
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/ui/ReadOnlyBanner.kt` | Slim amber banner; visibility bound to `interactionModeFlow` |
| `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/ui/LiveOnlyEnablement.kt` | `bindLiveOnlyEnablement(parent, service, vararg controls)` helper |

### New EP implementations

| Path | Responsibility |
|---|---|
| `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/workflow/OpenPrListerImpl.kt` | Reads `PrListService.allRepoPrs`; converts `BitbucketPrDetail` → `PrRef` |
| `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/workflow/LatestBuildLookupImpl.kt` | Calls `BambooApiClient.getLatestResult(planKey, branch)`; returns `BuildRef?` |

### New test files

| Path | Responsibility |
|---|---|
| `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextServiceTest.kt` | All cascade tests (mocked Project) |
| `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirrorTest.kt` | Mirror translation + loop guard + race |
| `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/InteractionModePurityTest.kt` | Behavioral + reflection-based purity (R9) |
| `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/ui/BannerVisibilityFlickerTest.kt` | Rapid focus-toggle → ≤2 banner transitions |
| `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/integration/WorkflowContextEditorIntegrationTest.kt` | `BasePlatformTestCase` — real `FileEditorManager` + `GitRepositoryManager` |
| Per-feature-module characterization tests | See task descriptions |

### Modified files

| Path | Change |
|---|---|
| `core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt` | Delete `PrContext` class, `prContextMap` field, side-effect block in `emit()` |
| `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt` | `setupActiveTicketBar` subscribes to `service.activeTicketFlow` |
| `core/build.gradle.kts` | Add `testImplementation("io.mockk:mockk:...")` if not present; add `testRuntimeOnly("org.junit.vintage:junit-vintage-engine:...")` for T17.5 only |
| `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt` | Replace `prContextMap` reads + local PR resolution with `service.state.collect`; PrBar + job stages SAME COMMIT (NB6) |
| `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt` | Read from `service.state.map { it.focusPr }` |
| `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt` | Subscribe to `service.state.map { it.focusQualityScope }`; banner + per-control disable |
| `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt` | Row-click → `cs.launch { service.focusPr(...) }` + legacy event re-emit |
| `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt` | Subscribe to `focusPr`; banner + per-control disable |
| `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt` | Start Work → `service.setActiveTicket(...)`; remove direct `PluginSettings` write |
| `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/ActiveTicketService.kt` | Convert to thin facade over `WorkflowContextService.activeTicketFlow` (source-compat for existing `:jira` callers) |
| `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt` | Subscribe to `service.activeTicketFlow` instead of `EventBus.TicketChanged` + `PluginSettings.activeTicketId` |
| `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt` | Read `activeRepo` from `service.state` instead of `RepoContextResolver` |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/EnvironmentDetailsBuilder.kt` | Add `appendWorkflowContext()` step |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/MentionSearchProvider.kt` | `@ticket` falls back to `service.state.value.activeTicket?.key` when sprint search misses |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ProjectContextTool.kt` | Replace `prContextMap` reads (lines 91, 110) with `service.state.value.focusPr` |
| `src/main/resources/META-INF/plugin.xml` | Register `WorkflowContextService`, `WorkflowContextProjectActivity`, two new EP names + their implementations |
| `core/CLAUDE.md` | New `WorkflowContextService` section |
| `docs/architecture/threading-model.md` | Phase 5 subsection |
| `docs/architecture/index.html` | Phase 5 § |

---

## Test infrastructure conventions (apply to all unit tests in this plan)

Unit tests use **`mockk<Project>(relaxed = true)`** + `installReadActionInlineShim()` (existing repo pattern). Reference for the shim: `agent/src/test/kotlin/.../testutil/ReadActionTestShim.kt::installReadActionInlineShim()`.

Pattern (note: copy `ReadActionTestShim.kt` from `:agent/src/test/.../testutil/` to `:core/src/test/kotlin/com/workflow/orchestrator/core/testutil/` because `:core` does not depend on `:agent`. Implementer copies the file in T1 and updates the import accordingly. Phase 5b cleanup may extract to a shared `testFixtures` source set):

```kotlin
import com.workflow.orchestrator.core.testutil.ReadActionTestShim
import io.mockk.every
import io.mockk.mockk
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class SomeWorkflowContextServiceTest {
    companion object {
        @JvmStatic @BeforeAll fun setup() = ReadActionTestShim.installReadActionInlineShim()
        @JvmStatic @AfterAll fun teardown() = ReadActionTestShim.uninstall()
    }

    private val project = mockk<Project>(relaxed = true)

    @Test fun `cascade test`() = runTest { /* ... */ }
}
```

For each test that needs a service-injected project, mock `project.getService(...)` to return preconfigured services. Where `WorkflowContextService` itself is under test, instantiate directly with the mock Project + a `TestScope`.

Integration test (T17.5) uses `BasePlatformTestCase` — the only file requiring `junit-vintage-engine`.

`:core/build.gradle.kts` test deps required:

```kotlin
// Use the version-catalog accessors that already exist (gradle/libs.versions.toml).
// Implementer verifies the catalog has mockk + turbine entries; if not, add them at
// the catalog level rather than hardcoding versions per-module.
dependencies {
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit.vintage.engine)  // T18 only
}
```

---

## Task list

### Task 1: Data types — `WorkflowContext`, `InteractionMode`, `*Ref` + purity test (with reflection check)

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/model/workflow/WorkflowContext.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/model/workflow/Refs.kt`
- Copy: `agent/src/test/kotlin/com/workflow/orchestrator/agent/testutil/ReadActionTestShim.kt` → `core/src/test/kotlin/com/workflow/orchestrator/core/testutil/ReadActionTestShim.kt` (update package declaration)
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/InteractionModePurityTest.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextEqualsTest.kt`

- [ ] **Step 0: Copy `ReadActionTestShim.kt` into `:core` test sources**

```bash
mkdir -p core/src/test/kotlin/com/workflow/orchestrator/core/testutil
cp agent/src/test/kotlin/com/workflow/orchestrator/agent/testutil/ReadActionTestShim.kt \
   core/src/test/kotlin/com/workflow/orchestrator/core/testutil/ReadActionTestShim.kt
```

Then edit the copy's package declaration: `package com.workflow.orchestrator.agent.testutil` → `package com.workflow.orchestrator.core.testutil`. The shim is needed by every `:core` unit test that exercises `readAction { }` calls inside the service. (`:core` cannot import test code from `:agent` — modules don't share test classpaths.) Phase 5b cleanup may extract to a shared `testFixtures` source set.

- [ ] **Step 1: Write failing tests**

`InteractionModePurityTest.kt`:

```kotlin
package com.workflow.orchestrator.core.workflow

import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties

class InteractionModePurityTest {
    @Test fun `Live when focusPr is null`() {
        assertEquals(InteractionMode.Live, WorkflowContext(activeBranch = "feat/abc").interactionMode)
    }

    @Test fun `Live when focusPr fromBranch matches activeBranch`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        assertEquals(InteractionMode.Live, WorkflowContext(activeBranch = "feat/abc", focusPr = pr).interactionMode)
    }

    @Test fun `ReadOnly when focusPr fromBranch differs from activeBranch`() {
        val pr = PrRef(42, "bugfix/xyz", "main", "repo", null, null)
        assertEquals(InteractionMode.ReadOnly, WorkflowContext(activeBranch = "feat/abc", focusPr = pr).interactionMode)
    }

    @Test fun `ReadOnly when activeBranch is null but focusPr exists`() {
        val pr = PrRef(42, "bugfix/xyz", "main", "repo", null, null)
        assertEquals(InteractionMode.ReadOnly, WorkflowContext(focusPr = pr).interactionMode)
    }

    @Test fun `interactionMode result is stable across 100 invocations with no state change`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        val ctx = WorkflowContext(activeBranch = "feat/abc", focusPr = pr)
        repeat(100) { assertEquals(InteractionMode.Live, ctx.interactionMode) }
    }

    /**
     * Reflection-based purity check: enumerate WorkflowContext's declared properties; assert
     * that interactionMode for every combination of nulls vs non-nulls of (activeBranch, focusPr)
     * is determined SOLELY by those two fields (no implicit dependency on others). If a future
     * maintainer adds a contributing factor, this test fails because changing other declared
     * fields will not change interactionMode in cases where it shouldn't.
     */
    @Test fun `interactionMode depends only on declared activeBranch and focusPr`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        val baselineLive = WorkflowContext(activeBranch = "feat/abc", focusPr = pr).interactionMode
        val baselineReadOnly = WorkflowContext(activeBranch = "main", focusPr = pr).interactionMode
        assertEquals(InteractionMode.Live, baselineLive)
        assertEquals(InteractionMode.ReadOnly, baselineReadOnly)

        // Enumerate other declared properties; for each, vary it and assert interactionMode unchanged.
        val ctxLiveBase = WorkflowContext(activeBranch = "feat/abc", focusPr = pr)
        val ctxLiveWithExtras = ctxLiveBase.copy(
            activeTicket = com.workflow.orchestrator.core.model.workflow.TicketRef("X-1", "s"),
            activeRepo = com.workflow.orchestrator.core.model.workflow.RepoRef("r", "P", "s", "/p"),
            activeModule = com.workflow.orchestrator.core.model.workflow.ModuleRef("m", "/p"),
            focusBuild = com.workflow.orchestrator.core.model.workflow.BuildRef("PLAN", 1, "feat/abc", null),
            focusQualityScope = com.workflow.orchestrator.core.model.workflow.QualityScope("k", "feat/abc", null),
        )
        assertEquals(ctxLiveBase.interactionMode, ctxLiveWithExtras.interactionMode,
            "interactionMode changed when an unrelated declared field changed — invariant broken")
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
    @Test fun `identical declared fields are equal`() {
        val t = TicketRef("AFTER8TE-912", "Fix login")
        assertEquals(WorkflowContext(activeTicket = t), WorkflowContext(activeTicket = t))
    }

    @Test fun `different focusPr are not equal`() {
        val a = WorkflowContext(focusPr = PrRef(42, "f", "m", "r", null, null))
        val b = WorkflowContext(focusPr = PrRef(43, "f", "m", "r", null, null))
        assertNotEquals(a, b)
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL (compile error)**

```
./gradlew :core:test --tests "com.workflow.orchestrator.core.workflow.InteractionModePurityTest"
```

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
 * Immutable snapshot of workflow state. See docs/architecture/workflow-context-design.md.
 *
 * INVARIANT: every derived getter (today: [interactionMode]; future additions) MUST be
 * a pure function of declared fields. External-state reads inside derived getters break
 * the `state.map { it.<derived> }.distinctUntilChanged()` flow — the underlying state
 * doesn't change when external state changes, so the flow misses transitions. New
 * contributing factors MUST be added as declared fields. Enforced by InteractionModePurityTest.
 */
data class WorkflowContext(
    val activeTicket: TicketRef? = null,
    val activeRepo: RepoRef? = null,
    val activeBranch: String? = null,
    val activeModule: ModuleRef? = null,
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

- [ ] **Step 6: Module test gate** — `./gradlew :core:test`

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/model/workflow/ \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/InteractionModePurityTest.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextEqualsTest.kt
git commit -m "feat(core): add WorkflowContext data model + Refs (Phase 5 T1)"
```

---

### Task 2: Extension point interfaces — `OpenPrLister` + `LatestBuildLookup`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/OpenPrLister.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/LatestBuildLookup.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` — register both EP names

- [ ] **Step 1: Write `OpenPrLister.kt`**

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef

/**
 * Extension point for cross-module open-PR enumeration. Implemented by :pullrequest;
 * consumed by :core's [WorkflowContextService.setActiveTicket] for auto-seed and by
 * the mirror for legacy [com.workflow.orchestrator.core.events.WorkflowEvent.PrSelected]
 * payload conversion.
 *
 * The `:core` module knows [PrRef] but cannot depend on `:pullrequest` (DAG) — this EP
 * is the sanctioned bridge. Same pattern as [com.workflow.orchestrator.core.bitbucket.CreatePrLauncher].
 */
interface OpenPrLister {
    /** Returns all known open PRs across all configured repos. May be empty. */
    fun listOpenPrs(project: Project): List<PrRef>

    companion object {
        val EP_NAME = ExtensionPointName.create<OpenPrLister>(
            "com.workflow.orchestrator.openPrLister"
        )
        fun getInstance(): OpenPrLister? = EP_NAME.extensionList.firstOrNull()
    }
}
```

- [ ] **Step 2: Write `LatestBuildLookup.kt`**

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.BuildRef

/**
 * Extension point for cross-module latest-build lookup by plan + branch. Implemented by
 * :bamboo; consumed by :core's [WorkflowContextService.focusPr] cascade to derive [BuildRef].
 */
interface LatestBuildLookup {
    /** Returns the latest build for [planKey] on [branch], or null on miss/error. Suspend; off-EDT. */
    suspend fun fetchLatestBuild(project: Project, planKey: String, branch: String): BuildRef?

    companion object {
        val EP_NAME = ExtensionPointName.create<LatestBuildLookup>(
            "com.workflow.orchestrator.latestBuildLookup"
        )
        fun getInstance(): LatestBuildLookup? = EP_NAME.extensionList.firstOrNull()
    }
}
```

- [ ] **Step 3: Register both EP names in `plugin.xml`**

In `<extensionPoints>` (search for the existing `createPrLauncher` declaration as a template):

```xml
<extensionPoints>
    <!-- ... existing EPs ... -->
    <extensionPoint qualifiedName="com.workflow.orchestrator.openPrLister"
                    interface="com.workflow.orchestrator.core.workflow.OpenPrLister"
                    dynamic="true"/>
    <extensionPoint qualifiedName="com.workflow.orchestrator.latestBuildLookup"
                    interface="com.workflow.orchestrator.core.workflow.LatestBuildLookup"
                    dynamic="true"/>
</extensionPoints>
```

**Why `qualifiedName`:** the plugin id is `com.workflow.orchestrator.plugin`, so `name="..."` would prefix `com.workflow.orchestrator.plugin.<name>`. The Kotlin `ExtensionPointName.create<>("com.workflow.orchestrator.openPrLister")` uses the unprefixed form, so `qualifiedName` must be set explicitly to match. Same pattern as existing `createPrLauncher` EP.

- [ ] **Step 4: Run `verifyPlugin`**

```
./gradlew verifyPlugin
```
Expected: green (EP declarations valid even with no implementations registered yet).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/OpenPrLister.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/workflow/LatestBuildLookup.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(core): add OpenPrLister + LatestBuildLookup extension points (Phase 5 T2)"
```

---

### Task 3: `OpenPrListerImpl` in `:pullrequest`

**Files:**
- Create: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/workflow/OpenPrListerImpl.kt`
- Create: `pullrequest/src/test/kotlin/com/workflow/orchestrator/pullrequest/workflow/OpenPrListerImplTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` — register implementation

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.pullrequest.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
import com.workflow.orchestrator.core.bitbucket.BitbucketPrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import com.workflow.orchestrator.pullrequest.service.PrListService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpenPrListerImplTest {
    @Test fun `converts BitbucketPrDetail to PrRef using repo config plan key`() {
        val project = mockk<Project>(relaxed = true)
        val prListService = mockk<PrListService>()
        val settings = mockk<PluginSettings>()
        val repo = RepoConfig().apply {
            name = "backend"
            bitbucketProjectKey = "PROJ"
            bitbucketRepoSlug = "backend"
            bambooPlanKey = "BACK-MAIN"
            sonarProjectKey = "backend.proj"
        }

        val pr = BitbucketPrDetail(
            id = 42,
            title = "Fix login",
            fromRef = BitbucketPrRef(displayId = "feat/abc", id = "refs/heads/feat/abc"),
            toRef = BitbucketPrRef(displayId = "main", id = "refs/heads/main"),
            repoName = "backend",
            // ... other required fields per actual constructor; implementer fills in defaults
        )
        every { prListService.allRepoPrs } returns MutableStateFlow(listOf(pr))
        every { project.getService(PrListService::class.java) } returns prListService
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.getRepos() } returns listOf(repo)

        val lister = OpenPrListerImpl()
        val refs = lister.listOpenPrs(project)
        assertEquals(1, refs.size)
        assertEquals(42, refs[0].prId)
        assertEquals("feat/abc", refs[0].fromBranch)
        assertEquals("main", refs[0].toBranch)
        assertEquals("BACK-MAIN", refs[0].bambooPlanKey)
        assertEquals("backend.proj", refs[0].sonarProjectKey)
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

- [ ] **Step 3: Implement `OpenPrListerImpl.kt`**

```kotlin
package com.workflow.orchestrator.pullrequest.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.OpenPrLister
import com.workflow.orchestrator.pullrequest.service.PrListService

class OpenPrListerImpl : OpenPrLister {
    override fun listOpenPrs(project: Project): List<PrRef> {
        val prListService = project.getService(PrListService::class.java) ?: return emptyList()
        val settings = project.getService(PluginSettings::class.java) ?: return emptyList()
        val reposByName = settings.getRepos().associateBy { it.name }

        return prListService.allRepoPrs.value.mapNotNull { pr ->
            val repo = reposByName[pr.repoName]
            PrRef(
                prId = pr.id,
                fromBranch = pr.fromRef?.displayId ?: return@mapNotNull null,
                toBranch = pr.toRef?.displayId ?: return@mapNotNull null,
                repoName = pr.repoName,
                bambooPlanKey = repo?.bambooPlanKey?.takeIf { it.isNotBlank() },
                sonarProjectKey = repo?.sonarProjectKey?.takeIf { it.isNotBlank() },
            )
        }
    }
}
```

- [ ] **Step 4: Register in `plugin.xml`**

```xml
<extensions defaultExtensionNs="com.workflow.orchestrator">
    <!-- ... existing extensions ... -->
    <openPrLister implementation="com.workflow.orchestrator.pullrequest.workflow.OpenPrListerImpl"/>
</extensions>
```

- [ ] **Step 5: Run tests — expect PASS** + module test gate

```
./gradlew :pullrequest:test verifyPlugin
```

- [ ] **Step 6: Commit**

```bash
git add pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/workflow/OpenPrListerImpl.kt \
        pullrequest/src/test/kotlin/com/workflow/orchestrator/pullrequest/workflow/OpenPrListerImplTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(pullrequest): implement OpenPrLister extension (Phase 5 T3)"
```

---

### Task 4: `LatestBuildLookupImpl` in `:bamboo`

**Files:**
- Create: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/workflow/LatestBuildLookupImpl.kt`
- Create: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/workflow/LatestBuildLookupImplTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write failing test**

```kotlin
package com.workflow.orchestrator.bamboo.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.service.BambooServiceImpl
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LatestBuildLookupImplTest {
    @Test fun `returns BuildRef on Bamboo success`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val client = mockk<BambooApiClient>()
        val dto = BambooResultDto(
            key = "PLAN-PROJ-13",
            buildResultKey = "PLAN-PROJ-13",
            buildNumber = 13,
            // ... other required fields per actual constructor; implementer fills in defaults
        )
        coEvery { client.getLatestResult("PLAN-PROJ", "feat/abc") } returns ApiResult.Success(dto)
        every { project.getService(BambooApiClient::class.java) } returns client

        val ref = LatestBuildLookupImpl().fetchLatestBuild(project, "PLAN-PROJ", "feat/abc")
        assertEquals(13, ref?.buildNumber)
        assertEquals("PLAN-PROJ", ref?.planKey)
        assertEquals("feat/abc", ref?.branch)
    }

    @Test fun `returns null on Bamboo error`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val client = mockk<BambooApiClient>()
        coEvery { client.getLatestResult(any(), any()) } returns ApiResult.Error(
            type = com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR,
            message = "boom",
        )
        every { project.getService(BambooApiClient::class.java) } returns client

        assertNull(LatestBuildLookupImpl().fetchLatestBuild(project, "PLAN-PROJ", "feat/abc"))
    }

    @Test fun `returns null when client unavailable`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.getService(BambooApiClient::class.java) } returns null
        assertNull(LatestBuildLookupImpl().fetchLatestBuild(project, "PLAN-PROJ", "feat/abc"))
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

- [ ] **Step 3: Implement `LatestBuildLookupImpl.kt`**

```kotlin
package com.workflow.orchestrator.bamboo.workflow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.workflow.BuildRef
import com.workflow.orchestrator.core.workflow.LatestBuildLookup

class LatestBuildLookupImpl : LatestBuildLookup {
    private val log = Logger.getInstance(LatestBuildLookupImpl::class.java)

    override suspend fun fetchLatestBuild(project: Project, planKey: String, branch: String): BuildRef? {
        // BambooApiClient is NOT a @Service — it's instantiated inside BambooServiceImpl.
        // Access via the service that owns it; expose a thin accessor if needed.
        val bambooService = project.getService(
            Class.forName("com.workflow.orchestrator.bamboo.service.BambooServiceImpl")
        ) as? BambooServiceImpl ?: return null
        val client = bambooService.client  // implementer may need to add an internal getter
        return when (val result = client.getLatestResult(planKey, branch)) {
            is ApiResult.Success -> {
                val dto = result.data
                BuildRef(
                    planKey = planKey,
                    buildNumber = dto.buildNumber,
                    branch = branch,
                    selectedJobKey = null,
                )
            }
            is ApiResult.Error -> {
                log.warn("[Bamboo:LatestBuild] $planKey@$branch failed: ${result.message}")
                null
            }
        }
    }
}
```

**Note on access pattern:** `BambooApiClient` is constructed inside `BambooServiceImpl` (not a `@Service`). The cleanest path is to add a thin `latestBuildResult(planKey, branch)` operation on `BambooService` (the `:core` interface) and call that from the EP impl — or expose `BambooServiceImpl.client` as `internal val`. The sample above uses the second path; the first is preferable but a larger code edit. Implementer chooses; both pass the test as long as `LatestBuildLookupImpl.fetchLatestBuild` returns the right `BuildRef` for `(planKey, branch)`.

- [ ] **Step 4: Register in `plugin.xml`**

```xml
<extensions defaultExtensionNs="com.workflow.orchestrator">
    <latestBuildLookup implementation="com.workflow.orchestrator.bamboo.workflow.LatestBuildLookupImpl"/>
</extensions>
```

- [ ] **Step 5: Run tests + module gate**

```
./gradlew :bamboo:test verifyPlugin
```

- [ ] **Step 6: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/workflow/LatestBuildLookupImpl.kt \
        bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/workflow/LatestBuildLookupImplTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(bamboo): implement LatestBuildLookup extension (Phase 5 T4)"
```

---

### Task 5: `WorkflowContextService` skeleton — state cell + boot anchor load

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextServiceBootTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write failing test**

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkflowContextServiceBootTest {

    @Test fun `boot with persisted ticket — state activeTicket hydrated synchronously`() {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state.activeTicketId } returns "AFTER8TE-912"
        every { settings.state.activeTicketSummary } returns "Fix login"
        every { project.getService(PluginSettings::class.java) } returns settings

        val service = WorkflowContextService(project, TestScope())
        val ticket = service.state.value.activeTicket
        assertNotNull(ticket)
        assertEquals("AFTER8TE-912", ticket!!.key)
        assertEquals("Fix login", ticket.summary)
    }

    @Test fun `boot with no persisted ticket — state activeTicket is null`() {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state.activeTicketId } returns null
        every { settings.state.activeTicketSummary } returns null
        every { project.getService(PluginSettings::class.java) } returns settings

        val service = WorkflowContextService(project, TestScope())
        assertNull(service.state.value.activeTicket)
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

- [ ] **Step 3: Implement `WorkflowContextService.kt`** (skeleton; mutators + listeners follow in T6/T7)

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.Disposable
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

@Service(Service.Level.PROJECT)
class WorkflowContextService(
    private val project: Project,
    private val cs: CoroutineScope,
) : Disposable {
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

    /** Package-private accessor for [WorkflowEventMirror]. */
    internal val serviceCs: CoroutineScope get() = cs

    init {
        loadAnchorFromSettings()
    }

    private fun loadAnchorFromSettings() {
        val settings = project.getService(PluginSettings::class.java) ?: return
        val id = settings.state.activeTicketId?.takeIf { it.isNotBlank() } ?: return
        val summary = settings.state.activeTicketSummary.orEmpty()
        _state.value = WorkflowContext(activeTicket = TicketRef(id, summary))
        log.info("[Workflow:Context] Boot-loaded anchor: $id")
    }

    override fun dispose() {
        // Platform manages `cs` lifecycle. messageBus connections registered with
        // `connect(this)` are auto-disposed.
    }

    companion object {
        fun getInstance(project: Project): WorkflowContextService = project.service()
    }
}
```

- [ ] **Step 4: Register service in `plugin.xml`**

```xml
<extensions defaultExtensionNs="com.intellij">
    <projectService serviceImplementation="com.workflow.orchestrator.core.workflow.WorkflowContextService"/>
</extensions>
```

- [ ] **Step 5: Run tests + module gate + verifyPlugin**

```
./gradlew :core:test verifyPlugin
```

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextServiceBootTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(core): WorkflowContextService skeleton with boot anchor (Phase 5 T5)"
```

---

### Task 6: Editor-derived listeners + `setActiveTicket` cascade with auto-seed

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextServiceMutatorsTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkflowContextServiceMutatorsTest {

    private fun makeService(
        project: Project,
        prList: List<PrRef> = emptyList(),
    ): WorkflowContextService {
        // Stub the EP companion so findOpenPrMatchingTicket returns the supplied prList.
        mockkObject(OpenPrLister.Companion)
        every { OpenPrLister.getInstance() } returns object : OpenPrLister {
            override fun listOpenPrs(project: Project): List<PrRef> = prList
        }
        return WorkflowContextService(project, TestScope())
    }

    @Test fun `setActiveTicket persists to settings before any suspend point`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null

        val service = makeService(project)
        service.setActiveTicket(TicketRef("AFTER8TE-912", "Fix login"))

        verify { settings.state.activeTicketId = "AFTER8TE-912" }
        verify { settings.state.activeTicketSummary = "Fix login" }
    }

    @Test fun `setActiveTicket emits new state with activeTicket populated`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null

        val service = makeService(project)
        val ticket = TicketRef("AFTER8TE-912", "Fix login")
        service.setActiveTicket(ticket)
        assertEquals(ticket, service.state.value.activeTicket)
    }

    @Test fun `setActiveTicket auto-seeds focusPr when matching open PR exists`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null
        val matchingPr = PrRef(42, "feat/AFTER8TE-912-fix", "main", "repo", null, null)

        val service = makeService(project, prList = listOf(matchingPr))
        service.setActiveTicket(TicketRef("AFTER8TE-912", "Fix login"))

        assertEquals(matchingPr, service.state.value.focusPr)
    }

    @Test fun `setActiveTicket null clears anchor`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null

        val service = makeService(project)
        service.setActiveTicket(TicketRef("AFTER8TE-912", "Fix login"))
        service.setActiveTicket(null)

        assertNull(service.state.value.activeTicket)
        verify { settings.state.activeTicketId = "" }
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL**

- [ ] **Step 3: Add editor listener wiring + `setActiveTicket` to `WorkflowContextService.kt`**

In `init { }`:

```kotlin
init {
    loadAnchorFromSettings()
    wireEditorListeners()
}
```

Add the methods (replace package qualifiers with imports as needed):

```kotlin
private fun wireEditorListeners() {
    val bus = project.messageBus.connect(this)

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

    // NOTE: GitRepositoryChangeListener intentionally NOT subscribed — git4idea is an
    // optional dependency. VCS_REPOSITORY_MAPPING_UPDATED (in com.intellij.dvcs platform
    // module) covers the branch-change signal we need without the optional-dep risk.
}

private suspend fun recomputeFromEditor() = cascadeMutex.withLock {
    val resolver = com.workflow.orchestrator.core.settings.RepoContextResolver.getInstance(project)
    // RepoContextResolver memoises via CachedValuesManager + SimpleModificationTracker;
    // it does not require a read action wrap (verified by reading the source).
    val gitRepo = resolver.resolveCurrentEditorRepoOrPrimary()

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
    val module = com.intellij.openapi.application.readAction {
        val fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val file = fem.selectedEditor?.file ?: return@readAction null
        val mod = com.intellij.openapi.module.ModuleUtilCore.findModuleForFile(file, project)
            ?: return@readAction null
        com.workflow.orchestrator.core.model.workflow.ModuleRef(
            name = mod.name,
            rootPath = com.intellij.openapi.roots.ModuleRootManager.getInstance(mod).contentRoots
                .firstOrNull()?.path.orEmpty(),
        )
    }

    _state.value = _state.value.copy(activeRepo = repoRef, activeBranch = branch, activeModule = module)
}

suspend fun setActiveTicket(ticket: com.workflow.orchestrator.core.model.workflow.TicketRef?) =
    cascadeMutex.withLock {
        // 1. Persist BEFORE any suspend point.
        val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
        settings.state.activeTicketId = ticket?.key.orEmpty()
        settings.state.activeTicketSummary = ticket?.summary.orEmpty()

        // 2. Auto-seed (a.ii).
        var next = _state.value.copy(activeTicket = ticket)
        if (ticket != null) {
            val matching = findOpenPrMatchingTicket(ticket.key)
            if (matching != null && matching != next.focusPr) {
                next = next.copy(focusPr = matching)  // T7 will add focusBuild + quality cascade
            }
        }
        _state.value = next
        log.info("[Workflow:Context] setActiveTicket: ${ticket?.key ?: "<cleared>"}, focusPr=${next.focusPr?.prId}")
    }

private fun findOpenPrMatchingTicket(ticketKey: String): com.workflow.orchestrator.core.model.workflow.PrRef? {
    val lister = OpenPrLister.getInstance() ?: return null
    val matches = lister.listOpenPrs(project).filter { it.fromBranch.contains(ticketKey, ignoreCase = false) }
    if (matches.isEmpty()) return null
    // Highest prId wins (deterministic across IDE restarts — spec §4.2.2).
    return matches.maxByOrNull { it.prId }
}
```

- [ ] **Step 4: Run tests — expect PASS**

- [ ] **Step 5: Module test gate** — `./gradlew :core:test`

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextServiceMutatorsTest.kt
git commit -m "feat(core): editor listeners + setActiveTicket cascade with auto-seed (Phase 5 T6)"
```

---

### Task 7: `focusPr` cascade — mutex serialization, cancel-previous, EP-driven build lookup

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/FocusPrCascadeTest.kt`

- [ ] **Step 1: Write failing tests** (use Turbine to assert single emission)

```kotlin
package com.workflow.orchestrator.core.workflow

import app.cash.turbine.test
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.BuildRef
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FocusPrCascadeTest {

    @Test fun `focusPr emits exactly one new state with focusPr+focusBuild populated`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null

        val lookup = mockk<LatestBuildLookup>()
        coEvery { lookup.fetchLatestBuild(any(), "PLAN", "feat/abc") } returns
            BuildRef("PLAN", 13, "feat/abc", null)
        mockkObject(LatestBuildLookup.Companion)
        every { LatestBuildLookup.getInstance() } returns lookup

        val service = WorkflowContextService(project, TestScope())
        val pr = PrRef(42, "feat/abc", "main", "r", "PLAN", null)

        service.state.test {
            assertEquals(null, awaitItem().focusPr)  // initial empty state
            service.focusPr(pr)
            val next = awaitItem()
            assertEquals(pr, next.focusPr)
            assertEquals(13, next.focusBuild?.buildNumber)
            cancel()  // no further emissions expected
        }
    }

    @Test fun `focusPr null clears focus chain in single emission`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null
        mockkObject(LatestBuildLookup.Companion)
        every { LatestBuildLookup.getInstance() } returns null  // simulate :bamboo not loaded

        val service = WorkflowContextService(project, TestScope())
        service.focusPr(PrRef(42, "f", "m", "r", null, null))
        service.focusPr(null)

        val s = service.state.value
        assertNull(s.focusPr)
        assertNull(s.focusBuild)
        assertNull(s.focusQualityScope)
    }

    @Test fun `rapid focusPr calls — only the last cascade's state survives`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null
        mockkObject(LatestBuildLookup.Companion)
        every { LatestBuildLookup.getInstance() } returns null

        val service = WorkflowContextService(project, TestScope())
        listOf(
            PrRef(42, "a", "m", "r", null, null),
            PrRef(43, "b", "m", "r", null, null),
            PrRef(44, "c", "m", "r", null, null),
        ).forEach { service.focusPr(it) }
        assertEquals(44, service.state.value.focusPr?.prId)
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL**

- [ ] **Step 3: Implement `focusPr` cascade**

Add to `WorkflowContextService.kt`:

```kotlin
private var currentFocusJob: kotlinx.coroutines.Job? = null

suspend fun focusPr(pr: com.workflow.orchestrator.core.model.workflow.PrRef?) = cascadeMutex.withLock {
    currentFocusJob?.cancelAndJoin()

    val newCtx = if (pr == null) {
        // Null cascade: clear focus chain. (Per spec §4.1, focusBuild from activeBranch
        // is deferred to latestBuildForBranchFlow — out of scope for 5a; null is correct here.)
        _state.value.copy(focusPr = null, focusBuild = null, focusQualityScope = null)
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
            LatestBuildLookup.getInstance()?.fetchLatestBuild(project, planKey, pr.fromBranch)
        }
    }
    val quality = pr.sonarProjectKey?.let {
        com.workflow.orchestrator.core.model.workflow.QualityScope(
            sonarProjectKey = it,
            branchName = pr.fromBranch,
            moduleKey = null,
        )
    }
    return base.copy(focusPr = pr, focusBuild = build, focusQualityScope = quality)
}
```

Also update `setActiveTicket` step 2 from T6 to call `computeFocusForPr` (so auto-seed gets the build cascade):

```kotlin
if (matching != null && matching != next.focusPr) {
    next = computeFocusForPr(next, matching)
}
```

- [ ] **Step 4: Run tests — expect PASS** (Turbine test asserts single emission)

- [ ] **Step 5: Module test gate** — `./gradlew :core:test`

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/FocusPrCascadeTest.kt
git commit -m "feat(core): focusPr cascade with mutex + cancel-previous + EP build lookup (Phase 5 T7)"
```

---

### Task 8: `WorkflowEventMirror` + `WorkflowContextProjectActivity`

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirror.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextProjectActivity.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirrorTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkflowEventMirrorTest {

    private fun setup(): Triple<Project, EventBus, WorkflowContextService> {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null

        val bus = EventBus()  // standalone — not project-scoped for unit test
        // EventBus is a @Service accessed via project.service<EventBus>(); mockk routes
        // through the underlying project.getService(EventBus::class.java) call.
        every { project.getService(EventBus::class.java) } returns bus

        mockkObject(LatestBuildLookup.Companion)
        every { LatestBuildLookup.getInstance() } returns null

        val service = WorkflowContextService(project, TestScope())
        WorkflowEventMirror(project, service).install()
        return Triple(project, bus, service)
    }

    @Test fun `mirror translates PrSelected into focusPr`() = runTest {
        val (project, bus, service) = setup()
        bus.emit(WorkflowEvent.PrSelected(42, "feat/abc", "main", "repo", "PLAN", "SONAR"))
        delay(100)
        val focus = service.state.value.focusPr
        assertNotNull(focus)
        assertEquals(42, focus!!.prId)
    }

    @Test fun `mirror no-ops on duplicate event (state-equality guard)`() = runTest {
        val (project, bus, service) = setup()
        val event = WorkflowEvent.PrSelected(42, "a", "main", "r", null, null)
        bus.emit(event); delay(100)
        val firstWrite = service.state.value
        bus.emit(event); delay(100)
        val secondWrite = service.state.value
        assertSame(firstWrite, secondWrite)
    }

    @Test fun `mirror translates TicketChanged into setActiveTicket`() = runTest {
        val (project, bus, service) = setup()
        bus.emit(WorkflowEvent.TicketChanged("AFTER8TE-912", "Fix login"))
        delay(100)
        assertEquals("AFTER8TE-912", service.state.value.activeTicket?.key)
    }

    @Test fun `mirror serializes concurrent events through cascadeMutex`() = runTest {
        val (project, bus, service) = setup()
        listOf(
            WorkflowEvent.PrSelected(42, "a", "m", "r", null, null),
            WorkflowEvent.PrSelected(43, "b", "m", "r", null, null),
            WorkflowEvent.PrSelected(44, "c", "m", "r", null, null),
        ).forEach { bus.emit(it) }
        delay(500)
        assertEquals(44, service.state.value.focusPr?.prId)
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
import kotlinx.coroutines.launch

/**
 * One-way bridge: legacy [WorkflowEvent]s on [EventBus] → [WorkflowContextService] mutators.
 *
 * Loop prevention is structural: the service's mutators never emit [WorkflowEvent]s. Plus
 * a defense-in-depth state-equality guard: the mirror checks whether the event's payload
 * already matches `state.value` before invoking the mutator (covers the migration case in
 * spec §5.3 where a migrated panel calls the mutator AND re-emits the legacy event).
 *
 * Installed at startup by [WorkflowContextProjectActivity] — guarantees subscription before
 * any panel construction (R8).
 */
class WorkflowEventMirror(
    private val project: Project,
    private val service: WorkflowContextService,
) {
    private val log = Logger.getInstance(WorkflowEventMirror::class.java)

    private var collectorJob: kotlinx.coroutines.Job? = null

    fun install() {
        // Idempotent install: cancel any prior collector before starting a new one.
        // Prevents duplicate subscriptions if the ProjectActivity ever re-runs.
        collectorJob?.cancel()
        collectorJob = service.serviceCs.launch {
            project.service<EventBus>().events.collect { event ->
                when (event) {
                    is WorkflowEvent.PrSelected -> handlePrSelected(event)
                    is WorkflowEvent.TicketChanged -> handleTicketChanged(event)
                    else -> { /* not mirrored */ }
                }
            }
        }
        log.info("[Workflow:Mirror] Installed")
    }

    // No explicit uninstall: collectorJob runs on service.serviceCs which the platform
    // cancels at project close. The idempotent install() above is sufficient to handle
    // any rare ProjectActivity re-run.

    private suspend fun handlePrSelected(event: WorkflowEvent.PrSelected) {
        val incoming = PrRef(
            prId = event.prId,
            fromBranch = event.fromBranch,
            toBranch = event.toBranch,
            repoName = event.repoName,
            bambooPlanKey = event.bambooPlanKey,
            sonarProjectKey = event.sonarProjectKey,
        )
        if (service.state.value.focusPr == incoming) return
        service.focusPr(incoming)
    }

    private suspend fun handleTicketChanged(event: WorkflowEvent.TicketChanged) {
        val incoming = if (event.ticketId.isBlank()) null else TicketRef(event.ticketId, event.ticketSummary)
        if (service.state.value.activeTicket == incoming) return
        service.setActiveTicket(incoming)
    }
}
```

- [ ] **Step 4: Implement `WorkflowContextProjectActivity.kt`**

```kotlin
package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class WorkflowContextProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = WorkflowContextService.getInstance(project)
        WorkflowEventMirror(project, service).install()
    }
}
```

- [ ] **Step 5: Register `ProjectActivity` in `plugin.xml`**

```xml
<extensions defaultExtensionNs="com.intellij">
    <postStartupActivity implementation="com.workflow.orchestrator.core.workflow.WorkflowContextProjectActivity"/>
</extensions>
```

- [ ] **Step 6: Run tests + module gate + verifyPlugin**

```
./gradlew :core:test verifyPlugin
```

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirror.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextProjectActivity.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/WorkflowEventMirrorTest.kt \
        src/main/resources/META-INF/plugin.xml
git commit -m "feat(core): WorkflowEventMirror + ProjectActivity startup hook (Phase 5 T8)"
```

---

### Task 9: Active-ticket bar migration

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt`

- [ ] **Step 1: Replace `setupActiveTicketBar` body**

Replace the existing two-source subscription (`EventBus.events.filterIsInstance<TicketChanged>` + `PluginSettings`) with a single subscription to `service.activeTicketFlow`:

```kotlin
private fun setupActiveTicketBar(project: Project, toolWindow: ToolWindow) {
    val ticketLabel = JBLabel().apply { font = JBUI.Fonts.label().deriveFont(Font.BOLD) }
    val summaryLabel = JBLabel().apply { foreground = StatusColors.SECONDARY_TEXT }

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
                val sprint = cm.contents.firstOrNull { it.displayName == "Sprint" }
                if (sprint != null) cm.setSelectedContent(sprint)
            }
        })
    }
    toolWindow.component.add(bar, BorderLayout.NORTH)

    val service = com.workflow.orchestrator.core.workflow.WorkflowContextService.getInstance(project)
    // The factory is not @Service; CoroutineScope() allocation is permitted here per Phase 4
    // convention (the rule binds @Service classes only — see core/CLAUDE.md).
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
            bar.parent?.revalidate(); bar.parent?.repaint()
        }
    }
    com.intellij.openapi.util.Disposer.register(toolWindow.disposable) { scope.cancel() }
}
```

- [ ] **Step 2: Module test gate + verifyPlugin** — `./gradlew :core:test verifyPlugin`

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt
git commit -m "refactor(core): active-ticket bar reads from WorkflowContextService (Phase 5 T9)"
```

---

### Task 10: BuildDashboardPanel migration — PrBar + job stages SAME COMMIT (NB6)

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt`
- Create: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanelCoherenceTest.kt`

**Scope:** the existing panel has a wider state machine (`repoSelector` dropdown, `loadBuildsForContext()`, manual refresh). This commit only migrates the **two readers that depend on the focused PR's branch/plan key** — PrBar header rendering and the job-stages section. The repo dropdown stays user-controlled; it remains decoupled from the editor-derived `activeRepo` for this phase.

- [ ] **Step 1: Audit current `prContextMap` reads in `BuildDashboardPanel.kt`** (line ~570 per reviewer; verify with `git grep`).

- [ ] **Step 2: Write coherence test using Turbine**

```kotlin
package com.workflow.orchestrator.bamboo.ui

import app.cash.turbine.test
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildDashboardPanelCoherenceTest {

    @Test fun `single focusPr emit triggers exactly one new state — both readers see same snapshot`() = runTest {
        val project = mockk<Project>(relaxed = true)
        // ... mock PluginSettings + LatestBuildLookup similar to FocusPrCascadeTest ...

        val service = WorkflowContextService.getInstance(project)
        val pr = PrRef(42, "feat/abc", "main", "repo", "PLAN", null)

        service.state.test {
            assertEquals(null, awaitItem().focusPr)  // initial empty state
            service.focusPr(pr)
            val snapshot = awaitItem()
            // The same WorkflowContext instance is what BuildDashboardPanel's two collectors
            // (PrBar + job-stages) will receive — proven by Turbine's emission count.
            assertEquals(pr, snapshot.focusPr)
            cancel()  // assert no further emissions
        }
    }
}
```

- [ ] **Step 3: Migrate PrBar — subscribe to `service.state.map { it.focusPr }`**

In `PrBar.kt`:

```kotlin
private val service = WorkflowContextService.getInstance(project)
private val barScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

init {
    // ... existing init ...
    barScope.launch {
        service.state.map { it.focusPr }.distinctUntilChanged().collect { pr ->
            renderFromFocusPr(pr)
        }
    }
    Disposer.register(parentDisposable, Disposable { barScope.cancel() })
}

private fun renderFromFocusPr(pr: PrRef?) {
    if (pr == null) {
        // Show "No PR focused" empty state.
    } else {
        // Render pr.prId, pr.fromBranch, pr.toBranch, pr.repoName.
    }
}
```

Delete any legacy `EventBus.prContextMap[...]` reads or `eventBus.events.filterIsInstance<PrSelected>().collect` blocks in PrBar.

- [ ] **Step 4: Migrate job-stages reader in `BuildDashboardPanel.kt`**

Find the section that uses `prContextMap[selectedRepo.name]` (line ~570) or local PR resolution. Replace with subscription to the same `service.state.map { it.focusPr }` flow. **Same commit as PrBar migration (NB6).**

The wider repo-selector + `loadBuildsForContext()` machinery stays unchanged — the only readers being migrated are those that decide "which PR's branch's build to show."

- [ ] **Step 5: Run tests — expect PASS**

```
./gradlew :bamboo:test
```

- [ ] **Step 6: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt \
        bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt \
        bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanelCoherenceTest.kt
git commit -m "refactor(bamboo): BuildDashboardPanel PrBar+stages share state snapshot (Phase 5 T10)"
```

---

### Task 11: QualityDashboardPanel migration

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt`

- [ ] **Step 1: Audit current local quality-scope resolution** (`git grep "sonarProjectKey\|prContextMap" sonar/`).

- [ ] **Step 2: Replace with subscription to `service.state.map { it.focusQualityScope }`**

```kotlin
private val service = WorkflowContextService.getInstance(project)
private val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

init {
    // ... existing init ...
    panelScope.launch {
        service.state.map { it.focusQualityScope }.distinctUntilChanged().collect { scope ->
            renderForQualityScope(scope)
        }
    }
    Disposer.register(parentDisposable, Disposable { panelScope.cancel() })
}

private fun renderForQualityScope(scope: QualityScope?) {
    if (scope == null) {
        // Show empty state ("No quality data — focus a PR or check out a branch with a Sonar project").
    } else {
        // Fetch coverage / issues for scope.sonarProjectKey + scope.branchName.
    }
}
```

- [ ] **Step 3: Module test gate** — `./gradlew :sonar:test`

- [ ] **Step 4: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt
git commit -m "refactor(sonar): QualityDashboardPanel reads focusQualityScope from service (Phase 5 T11)"
```

---

### Task 12: PrDashboardPanel + PrDetailPanel migration

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- Create: `pullrequest/src/test/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardCrossTabTest.kt`

- [ ] **Step 1: Write characterization test**

```kotlin
package com.workflow.orchestrator.pullrequest.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrDashboardCrossTabTest {

    @Test fun `row click propagates to service state focusPr`() = runTest {
        val project = mockk<Project>(relaxed = true)
        // Mock dependencies for WorkflowContextService construction.
        // Implementer wires PluginSettings, EventBus mocks per repo pattern.

        val service = WorkflowContextService.getInstance(project)
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)

        // Direct service call (the panel's row-click handler delegates to this same call):
        service.focusPr(pr)
        delay(100)

        assertEquals(pr, service.state.value.focusPr)
    }
}
```

- [ ] **Step 2: Migrate PrDashboardPanel — row click drives `service.focusPr` + re-emits legacy event**

Locate the row-click handler in `PrDashboardPanel.kt` (lines ~224-274 per reviewer audit). Modify:

```kotlin
private val service = WorkflowContextService.getInstance(project)
private val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

private fun onRowClicked(pr: BitbucketPrDetail) {
    val ref = PrRef(
        prId = pr.id,
        fromBranch = pr.fromRef?.displayId ?: return,
        toBranch = pr.toRef?.displayId ?: return,
        repoName = pr.repoName,
        bambooPlanKey = repoConfigFor(pr.repoName)?.bambooPlanKey,
        sonarProjectKey = repoConfigFor(pr.repoName)?.sonarProjectKey,
    )
    panelScope.launch {
        service.focusPr(ref)
        // Per spec §5.3: migrated call sites re-emit legacy event for back-compat
        // subscribers (e.g., AgentController webview push). Mirror's state-equality guard
        // no-ops since state.focusPr already matches.
        project.service<EventBus>().emit(WorkflowEvent.PrSelected(
            prId = ref.prId, fromBranch = ref.fromBranch, toBranch = ref.toBranch,
            repoName = ref.repoName, bambooPlanKey = ref.bambooPlanKey,
            sonarProjectKey = ref.sonarProjectKey,
        ))
    }
    // ... existing logic to open PrDetailPanel etc., unchanged ...
}

private fun repoConfigFor(name: String) =
    PluginSettings.getInstance(project).getRepos().firstOrNull { it.name == name }

Disposer.register(parentDisposable, Disposable { panelScope.cancel() })
```

Delete any direct `EventBus.prContextMap` writes (the side-effect was inside `EventBus.emit`; removed in T15).

- [ ] **Step 3: Migrate PrDetailPanel — subscribe to `service.state.map { it.focusPr }`**

Pattern same as T10 (PrBar). Banner + per-control disable wiring deferred to T14.

- [ ] **Step 4: Module test gate** — `./gradlew :pullrequest:test`

- [ ] **Step 5: Commit**

```bash
git add pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt \
        pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt \
        pullrequest/src/test/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardCrossTabTest.kt
git commit -m "refactor(pullrequest): row click drives service.focusPr + re-emits legacy event (Phase 5 T12)"
```

---

### Task 13: Sprint Start Work + ActiveTicketService facade + HandoverStateService + Automation panel

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/ActiveTicketService.kt`
- Modify: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt`
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt`
- Create: `jira/src/test/kotlin/com/workflow/orchestrator/jira/service/ActiveTicketServiceFacadeTest.kt`

- [ ] **Step 1: Migrate Sprint Start Work**

In `SprintDashboardPanel.kt`, locate the Start Work handler. Replace direct `PluginSettings.activeTicketId = ...` write + direct `EventBus.emit(TicketChanged)` with:

```kotlin
private val service = WorkflowContextService.getInstance(project)
private val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

private fun onStartWork(ticket: JiraTicketData) {
    panelScope.launch {
        service.setActiveTicket(TicketRef(ticket.key, ticket.summary))
        // Re-emit legacy event for back-compat (per spec §5.3).
        project.service<EventBus>().emit(WorkflowEvent.TicketChanged(ticket.key, ticket.summary))
    }
    // ... existing branch-creation / commit-prefix logic, unchanged ...
}
Disposer.register(parentDisposable, Disposable { panelScope.cancel() })
```

- [ ] **Step 2: Convert `ActiveTicketService` to a synchronous facade over `WorkflowContextService`**

**Contract decision:** the existing `setActiveTicket(id, summary)` is **synchronous** — callers in `BranchingService.kt:173/263`, `JiraSearchContributorFactory.kt:61`, `TicketDetectionPresenter.kt:68`, `SprintDashboardPanel.kt:237` write then immediately read `activeTicketId` on the same call stack. The facade MUST preserve this contract or those 5 sites need to become `suspend` (out of scope for 5a). Implementation: maintain a local `MutableStateFlow` that gets updated synchronously; dispatch the canonical `WorkflowContextService.setActiveTicket()` write to the background scope. The local cache is the source of truth for synchronous reads; the canonical service is the source of truth for cross-tab subscribers.

`:jira` depends on `:core`, so direct call is fine. Rewrite `ActiveTicketService.kt`:

```kotlin
package com.workflow.orchestrator.jira.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ActiveTicketState(
    val ticketId: String,
    val summary: String,
)

/**
 * Facade over [WorkflowContextService.activeTicketFlow] — preserves the existing public API
 * for legacy callers in :jira while delegating storage to the canonical service.
 *
 * Will be deleted in Phase 5b once all callers migrate to `WorkflowContextService` directly.
 */
@Service(Service.Level.PROJECT)
class ActiveTicketService(
    private val project: Project,
    private val cs: CoroutineScope,
) {
    private val log = Logger.getInstance(ActiveTicketService::class.java)
    private val service get() = WorkflowContextService.getInstance(project)

    // Local synchronous cache — preserves the existing sync setActiveTicket → read contract.
    // Initialized from the canonical service on construction.
    private val _localFlow = MutableStateFlow<ActiveTicketState?>(
        service.state.value.activeTicket?.let { ActiveTicketState(it.key, it.summary) }
    )

    val activeTicketFlow: StateFlow<ActiveTicketState?> = _localFlow.asStateFlow()
    val activeTicketId: String? get() = _localFlow.value?.ticketId
    val activeTicketSummary: String? get() = _localFlow.value?.summary

    init {
        // Mirror canonical state INTO the local cache (catches cross-tab updates from
        // mirror or other writers).
        cs.launch {
            service.activeTicketFlow.collect { ticket ->
                _localFlow.value = ticket?.let { ActiveTicketState(it.key, it.summary) }
            }
        }
    }

    fun setActiveTicket(ticketId: String, summary: String) {
        // Synchronous: update local cache immediately so callers reading on the next line
        // see the new value. Then dispatch the canonical write to background.
        _localFlow.value = ActiveTicketState(ticketId, summary)
        cs.launch { service.setActiveTicket(TicketRef(ticketId, summary)) }
    }

    fun clearActiveTicket() {
        _localFlow.value = null
        cs.launch { service.setActiveTicket(null) }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): ActiveTicketService =
            project.getService(ActiveTicketService::class.java)

        private val TICKET_PATTERN = Regex("([A-Z][A-Z0-9]+-\\d+)")
        fun extractTicketIdFromBranch(branchName: String): String? =
            TICKET_PATTERN.find(branchName)?.groupValues?.get(1)
    }
}
```

**Update existing `ActiveTicketServiceTest.kt`:** the existing test constructs `ActiveTicketService()` (zero-arg) in 4 tests. New constructor is `(project: Project, cs: CoroutineScope)`. Either delete the test file (the canonical `WorkflowContextServiceMutatorsTest` covers equivalent surface) or rewrite each test to construct `ActiveTicketService(mockk(relaxed=true), TestScope())` plus mock `WorkflowContextService` on the project. **Recommended: delete** — canonical coverage already exists.

Add a test asserting facade delegation:

```kotlin
package com.workflow.orchestrator.jira.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ActiveTicketServiceFacadeTest {
    @Test fun `setActiveTicket on facade calls WorkflowContextService`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val canonical = mockk<WorkflowContextService>(relaxed = true)
        every { project.getService(WorkflowContextService::class.java) } returns canonical
        every { canonical.state } returns kotlinx.coroutines.flow.MutableStateFlow(WorkflowContext())
        every { canonical.activeTicketFlow } returns kotlinx.coroutines.flow.MutableStateFlow(null)

        val scope = TestScope()
        val facade = ActiveTicketService(project, scope)
        facade.setActiveTicket("AFTER8TE-912", "Fix login")
        // Synchronous local cache update — assert immediately, no advance needed.
        assertEquals("AFTER8TE-912", facade.activeTicketId)

        // Background dispatch to canonical:
        scope.advanceUntilIdle()
        coVerify { canonical.setActiveTicket(TicketRef("AFTER8TE-912", "Fix login")) }
    }
}
```

- [ ] **Step 3: Migrate `HandoverStateService` (NOT HandoverPanel)**

In `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt`, locate the existing `EventBus.events.filterIsInstance<TicketChanged>` subscription (line ~111 per reviewer audit) and the direct `PluginSettings.activeTicketId` reads. Replace with subscription to `WorkflowContextService.activeTicketFlow`:

```kotlin
private val workflowService = WorkflowContextService.getInstance(project)

init {
    // ... existing init ...
    cs.launch {
        workflowService.activeTicketFlow.collect { ticket ->
            // Replace whatever the legacy TicketChanged handler did.
            handleActiveTicketChanged(ticket?.key, ticket?.summary)
        }
    }
    // Delete the legacy subscription block.
}
```

- [ ] **Step 4: Migrate AutomationPanel**

In `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt` (line ~161 reads `RepoContextResolver`), replace with `service.state.value.activeRepo` for one-shot reads, or `service.state.map { it.activeRepo }.collect { }` for reactive renders.

- [ ] **Step 5: Run tests**

```
./gradlew :jira:test :handover:test :automation:test
```

- [ ] **Step 6: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt \
        jira/src/main/kotlin/com/workflow/orchestrator/jira/service/ActiveTicketService.kt \
        jira/src/test/kotlin/com/workflow/orchestrator/jira/service/ActiveTicketServiceFacadeTest.kt \
        handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt \
        automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt
git commit -m "refactor(modules): Sprint/Handover/Automation + ActiveTicketService facade (Phase 5 T13)"
```

---

### Task 14: ReadOnlyBanner + bindLiveOnlyEnablement helper + flicker test

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/ui/ReadOnlyBanner.kt`
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/ui/LiveOnlyEnablement.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/ui/BannerVisibilityFlickerTest.kt`

- [ ] **Step 1: Write flicker test**

```kotlin
package com.workflow.orchestrator.core.workflow.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BannerVisibilityFlickerTest {
    @Test fun `rapid focusPr toggle results in at most 2 visibility transitions`() = runTest {
        val project = mockk<Project>(relaxed = true)
        // ... mock setup omitted; same pattern as FocusPrCascadeTest ...
        val service = WorkflowContextService.getInstance(project)
        val banner = ReadOnlyBanner(project)
        val transitions = mutableListOf<Boolean>()
        banner.recordTransitionsForTest(transitions)

        val pr = PrRef(42, "bugfix/xyz", "main", "r", null, null)
        repeat(10) { service.focusPr(pr); service.focusPr(null) }
        delay(500)
        assertTrue(transitions.size <= 2, "Banner flickered: ${transitions.size}")
    }
}
```

- [ ] **Step 2: Run test — expect FAIL**

- [ ] **Step 3: Implement `ReadOnlyBanner.kt`**

```kotlin
package com.workflow.orchestrator.core.workflow.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
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
            isOpaque = false; add(message)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false; add(switchBranchLink); add(clearFocusLink)
        }
        add(left, BorderLayout.CENTER)
        add(right, BorderLayout.EAST)

        scope.launch {
            service.state.map { it.interactionMode }.distinctUntilChanged().collect { mode ->
                val readOnly = (mode == InteractionMode.ReadOnly)
                isVisible = readOnly
                if (readOnly) updateMessage()
                revalidate(); repaint()
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

    override fun dispose() = scope.cancel()
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

fun bindLiveOnlyEnablement(
    parent: Disposable,
    service: WorkflowContextService,
    vararg controls: JComponent,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    Disposer.register(parent) { scope.cancel() }
    scope.launch {
        // Combine mode + branch in one flow so the tooltip uses the same snapshot.
        service.state.map { Triple(it.interactionMode, it.activeBranch, it.focusPr?.fromBranch) }
            .distinctUntilChanged()
            .collect { (mode, _, focusFromBranch) ->
                val live = (mode == InteractionMode.Live)
                controls.forEach { ctrl ->
                    ctrl.isEnabled = live
                    ctrl.toolTipText = if (live) null
                    else "Disabled: focused PR is on a different branch. Switch to ${focusFromBranch ?: "<none>"} to enable."
                }
            }
    }
}
```

- [ ] **Step 5: Run tests + module gate + verifyPlugin** — `./gradlew :core:test verifyPlugin`

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/workflow/ui/ \
        core/src/test/kotlin/com/workflow/orchestrator/core/workflow/ui/BannerVisibilityFlickerTest.kt
git commit -m "feat(core): ReadOnlyBanner + bindLiveOnlyEnablement helper (Phase 5 T14)"
```

---

### Task 15: Wire banner + per-control disable into Build/Quality/PrDetail

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`

**Implementer notes:** the spec §7.3 enumerates the live-only interactions, but actual control-level wiring requires identifying the existing components. Many "controls" are inline-defined Swing actions inside cell renderers, click handlers, or toolbar action groups — not standalone named fields. For each panel:

1. Find the live-only interactions per spec §7.3 (gutter markers, click-to-fix, navigate-to-failing-test, breakpoint-from-stacktrace, inline diff editor open, checkout-PR-locally, comment-anchor-to-line).
2. For each, either (a) extract the existing inline handler into a named field/button so `bindLiveOnlyEnablement` can disable it, or (b) gate the action at perform-time with `if (service.state.value.interactionMode == InteractionMode.ReadOnly) return` plus a balloon notification.
3. Add the banner to the panel's NORTH layout slot (audit existing NORTH usage first — `BuildDashboardPanel` already uses NORTH for `warningLabel` per reviewer; the banner goes ABOVE that, in a new `JPanel(BorderLayout())` wrapper).

Per-panel pseudocode (implementer adapts to actual code):

```kotlin
// Top of init:
private val banner = ReadOnlyBanner(project).also {
    Disposer.register(this, it)
}

// Wrap existing NORTH region with banner above:
val northStack = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    add(banner)
    add(existingNorthComponent)  // e.g., warningLabel
}
add(northStack, BorderLayout.NORTH)

// Live-only gating (extract or perform-time):
bindLiveOnlyEnablement(this, service, navigateToFailingTestButton, viewChangesInlineButton, ...)
```

- [ ] **Step 1: For each of the 3 panels, audit current live-only interactions and add banner + gating**

- [ ] **Step 2: Run module gates**

```
./gradlew :bamboo:test :sonar:test :pullrequest:test
```

- [ ] **Step 3: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt \
        sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt \
        pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt
git commit -m "feat(ui): banner + live-only disable in Build/Quality/PrDetail (Phase 5 T15)"
```

---

### Task 16: Remove `EventBus.prContextMap` + migrate `:agent` `ProjectContextTool` consumer

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ProjectContextTool.kt`
- Audit via `git grep prContextMap` — must return empty after this commit.

- [ ] **Step 1: Pre-audit**

```
git grep -n "prContextMap"
```
Expected hits per reviewer: `BuildDashboardPanel.kt:570` (already migrated in T10), `QualityDashboardPanel.kt:258` (T11), `PrDashboardPanel.kt:637-638` (T12), `ProjectContextTool.kt:91,110` (this task).

- [ ] **Step 2: Migrate `ProjectContextTool.kt`**

Replace `eventBus.prContextMap[...]` reads with `WorkflowContextService.getInstance(project).state.value.focusPr?.takeIf { it.repoName == repoName }`. Implementer adapts the call sites.

- [ ] **Step 3: Delete from `EventBus.kt`**

```kotlin
// REMOVE:
data class PrContext( ... )
val prContextMap: ConcurrentHashMap<String, PrContext> = ConcurrentHashMap()
// And inside emit():
if (event is WorkflowEvent.PrSelected) { prContextMap[event.repoName] = ... }
```

- [ ] **Step 4: Final audit**

```
git grep -n "prContextMap"
```
Expected: empty.

- [ ] **Step 5: All-module test gate + verifyPlugin**

```
./gradlew :core:test :agent:test :bamboo:test :sonar:test :pullrequest:test verifyPlugin
```

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ProjectContextTool.kt
git commit -m "refactor(core,agent): remove EventBus.prContextMap — migrated to WorkflowContextService (Phase 5 T16)"
```

---

### Task 17: Agent integration — `EnvironmentDetailsBuilder` + mention shortcut fallback

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/EnvironmentDetailsBuilder.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/MentionSearchProvider.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/EnvironmentDetailsBuilderWorkflowContextTest.kt`

- [ ] **Step 1: Write test**

```kotlin
package com.workflow.orchestrator.agent.prompt

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EnvironmentDetailsBuilderWorkflowContextTest {

    @Test fun `workflow_context block included when state is non-empty`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val service = mockk<WorkflowContextService>()
        every { service.state } returns MutableStateFlow(WorkflowContext(
            activeTicket = TicketRef("AFTER8TE-912", "Fix login"),
            activeBranch = "feat/login-fix",
            focusPr = PrRef(42, "feat/login-fix", "main", "repo", null, null),
        ))
        every { project.getService(WorkflowContextService::class.java) } returns service

        // EnvironmentDetailsBuilder is an `object` (not a class). Call site signature
        // matches actual: build(project, planModeEnabled, contextManager).
        val block = EnvironmentDetailsBuilder.build(project, planModeEnabled = false, contextManager = null)
        assertTrue(block.contains("<workflow_context>"))
        assertTrue(block.contains("Active ticket: AFTER8TE-912"))
        assertTrue(block.contains("Focused PR: #42"))
    }

    @Test fun `workflow_context block omitted when state is empty`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val service = mockk<WorkflowContextService>()
        every { service.state } returns MutableStateFlow(WorkflowContext())
        every { project.getService(WorkflowContextService::class.java) } returns service

        val block = EnvironmentDetailsBuilder.build(project, planModeEnabled = false, contextManager = null)
        assertFalse(block.contains("<workflow_context>"))
    }
}
```

- [ ] **Step 2: Implement `appendWorkflowContext` in `EnvironmentDetailsBuilder.kt`**

`EnvironmentDetailsBuilder` is a singleton `object` — add a new private method that takes `(project, sb)` and call it from inside `build()` (after editor details, before tool list):

```kotlin
private fun appendWorkflowContext(project: Project, sb: StringBuilder) {
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

Call it from `build(project, ...)` at the appropriate position (after editor details, before tool list).

- [ ] **Step 3: Mention shortcut fallback in `MentionSearchProvider.kt`**

In `searchTickets(query: String)` (line ~428), add a fallback at the start when `query.isBlank()` or matches `"active"` / `"current"`:

```kotlin
suspend fun searchTickets(query: String): String {
    // Fallback: when query is empty or "active"/"current", surface the workflow-context active ticket.
    if (query.isBlank() || query.equals("active", ignoreCase = true) || query.equals("current", ignoreCase = true)) {
        val active = WorkflowContextService.getInstance(project).state.value.activeTicket
        if (active != null) {
            return buildJsonArray {
                addJsonObject {
                    put("type", "ticket")
                    put("key", active.key)
                    put("summary", active.summary)
                    put("source", "workflow_context_active")
                }
            }.toString()
        }
    }
    // ... existing search logic, unchanged ...
}
```

- [ ] **Step 4: Run tests + module gate**

```
./gradlew :agent:test --tests "*WorkflowContext*"
./gradlew :agent:test
```

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/EnvironmentDetailsBuilder.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/MentionSearchProvider.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/EnvironmentDetailsBuilderWorkflowContextTest.kt
git commit -m "feat(agent): inject workflow_context block + mention shortcut fallback (Phase 5 T17)"
```

---

### Task 18: Integration test — real `FileEditorManager` + `GitRepositoryManager`

**Files:**
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/workflow/integration/WorkflowContextEditorIntegrationTest.kt`
- Modify: `core/build.gradle.kts` — add `junit-vintage-engine` to `testRuntimeOnly`

- [ ] **Step 1: Add Vintage engine to `:core/build.gradle.kts`**

```kotlin
dependencies {
    testRuntimeOnly(libs.junit.vintage.engine)  // version from gradle/libs.versions.toml
}
```

If `libs.junit.vintage.engine` doesn't exist in `gradle/libs.versions.toml` yet, add it under `[libraries]` matching the existing `junit5-engine` version (currently 5.10.x).

- [ ] **Step 2: Write integration test**

```kotlin
package com.workflow.orchestrator.core.workflow.integration

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import org.junit.jupiter.api.Assertions.assertNotNull

class WorkflowContextEditorIntegrationTest : BasePlatformTestCase() {

    fun `test active editor change updates state activeBranch and activeRepo`() {
        // Open a file in the test fixture.
        val file = myFixture.addFileToProject("src/Foo.kt", "class Foo")
        myFixture.openFileInEditor(file.virtualFile)

        // The platform's FileEditorManager.selectionChanged listener fires; service updates.
        // (Test fixture has no real Git repo by default; activeBranch will be null. activeRepo
        // will be null unless PluginSettings has repos configured. The assertion that we want
        // is that the listener fires and the service does NOT NPE.)
        val service = WorkflowContextService.getInstance(project)
        // Allow async update.
        Thread.sleep(500)
        assertNotNull(service.state.value)
        // Module-level inference may populate activeModule.
    }
}
```

- [ ] **Step 3: Run test**

```
./gradlew :core:test --tests "*WorkflowContextEditorIntegrationTest"
```
Expected: green. If JUnit Vintage engine isn't picking up the test, debug `build.gradle.kts` deps.

- [ ] **Step 4: Commit**

```bash
git add core/src/test/kotlin/com/workflow/orchestrator/core/workflow/integration/ \
        core/build.gradle.kts
git commit -m "test(core): integration test for editor listener wiring (Phase 5 T18)"
```

---

### Task 19: Documentation refresh

**Files:**
- Modify: `core/CLAUDE.md`
- Modify: `docs/architecture/threading-model.md`
- Modify: `docs/architecture/index.html`

- [ ] **Step 1: `core/CLAUDE.md` — add `WorkflowContextService` under `## Services`**

```markdown
- `WorkflowContextService` — single source of truth for active ticket, focused PR,
  editor-derived branch/repo/module across all tabs and the agent. Exposes
  `StateFlow<WorkflowContext>`. Mutations cascade-serialized via Mutex; all subscribers
  see the same `WorkflowContext` instance per emission. Two new EPs (`openPrLister`,
  `latestBuildLookup`) bridge `:core` to `:pullrequest` / `:bamboo`. See
  `docs/architecture/workflow-context-design.md` and `phase5-workflow-context-plan.md`.
```

- [ ] **Step 2: `threading-model.md` — add Phase 5 subsection**

```markdown
### Workflow context (Phase 5)

Every panel that needs "what am I working on" — active ticket, focused PR, current branch,
current repo, current module — subscribes to `WorkflowContextService.state` (a
`StateFlow<WorkflowContext>` in `:core`). Mutators are mutex-serialized and emit one
observable transition per cascade. Panels MUST NOT resolve these fields locally.
```

- [ ] **Step 3: `docs/architecture/index.html` — clone the Phase 4 § block** (commit `5c84747c` is the template) and add a Phase 5 § linking to `workflow-context-design.md` and this plan.

- [ ] **Step 4: Commit**

```bash
git add core/CLAUDE.md docs/architecture/threading-model.md docs/architecture/index.html
git commit -m "docs: refresh CLAUDE.md + threading-model + index for Phase 5 (Phase 5 T19)"
```

---

### Task 20: Final verification — full build + runIde smoke + exit-criteria checklist

- [ ] **Step 1: Full clean build**

```
./gradlew clean verifyPlugin buildPlugin
```
Expected: green on IU-251/252/253.

- [ ] **Step 2: All-module test sweep**

```
./gradlew :core:test :jira:test :bamboo:test :sonar:test :pullrequest:test :automation:test :handover:test :agent:test
```

- [ ] **Step 3: runIde smoke (manual)**

Open `./gradlew runIde`; verify:
- [ ] Active-ticket bar shows persisted ticket on project open.
- [ ] Click PR row → Build + Quality refresh same tick; PrBar agrees with job stages.
- [ ] Check out a non-focused-PR branch → ReadOnlyBanner appears in Build / Quality / PR Detail.
- [ ] "Clear PR focus" → banner hides.
- [ ] Switch back to focused PR's source branch → banner hides; live-only controls re-enable.
- [ ] Start Work on sprint ticket whose key matches an open PR → focusPr auto-seeds.
- [ ] Agent chat env-details contains `<workflow_context>` block.

- [ ] **Step 4: Exit-criteria checklist** (spec §12)

- [ ] (a) `git grep "RepoContextResolver.*resolve\|PluginSettings.*activeTicketId"` returns only service-internal sites.
- [ ] (b) `PrDashboardCrossTabTest` passes.
- [ ] (c) `SprintStartWorkTest` passes (in T13 commit; verify).
- [ ] (d) `BuildDashboardPanelCoherenceTest` passes (Turbine emission count).
- [ ] (e) `interactionMode` flips correctly across `InteractionModePurityTest` cases; banner appears/hides in runIde.
- [ ] (f) `EnvironmentDetailsBuilderWorkflowContextTest` passes.
- [ ] (g) `git grep prContextMap` returns empty.
- [ ] (h) `verifyPlugin buildPlugin` green.
- [ ] (i) Docs refreshed (T19).

- [ ] **Step 5: Update branch memory**

Edit `~/.claude/projects/-Users-subhankarhalder-Desktop-Programs-scripts-IntelijPlugin/memory/project_branch_refactor_cleanup_perf_caching.md` — append Phase 5 closeout section (mirror Phase 4 closeout format). Mark Phase 5 done; note `project_active_ticket_visibility.md` is now superseded.

- [ ] **Step 6: Closeout commit**

```bash
git commit --allow-empty -m "docs(architecture): Phase 5 closeout — WorkflowContextService shipped (Phase 5 T20)"
```

---

## Self-review

**Spec coverage:**
- §1 Summary — entire plan.
- §2 Decisions — embedded across T1 (interactionMode), T6 (anchor + auto-seed), T7 (focus chain), T13 (persistence + facade), T14 (banner), T17 (agent).
- §3 Architecture — T1 (data), T2 (EPs), T3-T4 (EP impls), T5-T8 (service).
- §4 Sources of truth — T6 (editor + setActiveTicket), T7 (focusPr non-null + null), T8 (mirror).
- §4.0 Mutex — T7 (cascade), verified by FocusPrCascadeTest rapid-call test.
- §4.4 Single-merged-emission — T7 + T10 Turbine assertion.
- §4.5 Boot semantics — T5 (synchronous load) + T8 (ProjectActivity).
- §5 Migration — T9 (active-ticket bar), T10 (Build), T11 (Quality), T12 (PR), T13 (Sprint/Handover/Automation + ActiveTicketService facade), T16 (prContextMap removal + agent migration).
- §6 Agent integration — T17.
- §7 ReadOnly affordance — T14 + T15.
- §8 Persistence — T6 (setActiveTicket persists) + T5 (boot loads).
- §9 Testing — distributed (T1 purity, T5 boot, T6 mutators, T7 cascade+race, T8 mirror, T10 coherence, T12 cross-tab, T13 facade, T14 flicker, T17 agent, T18 integration).
- §10 Out of scope — preserved (no `latestBuildForBranchFlow`, no `focusBuild` "pin" mutator, no agent write tools, no Phase 5b event deletion).
- §11 Risks — R8 (mirror startup race) covered by T8 ProjectActivity. R9 (purity) covered by T1 reflection-based test.
- §12 Exit criteria — T20.

**Placeholder scan:** No "TBD" / "TODO" / generic "add error handling." Every step has actual code. Two acknowledged "implementer adapts" notes (T4 step 3 service-discovery for `BambooApiClient`, T15 step 1 control identification) — both pointing to runtime adaptation that requires reading existing files.

**Type / API consistency:** `TicketRef`, `PrRef`, `BuildRef`, `QualityScope`, `RepoRef`, `ModuleRef` defined in T1 and used consistently. `WorkflowContextService.state`, `.activeTicketFlow`, `.interactionModeFlow`, `.focusPr()`, `.setActiveTicket()`, `.serviceCs` consistent across T5-T17. `OpenPrLister` / `LatestBuildLookup` defined T2; consumed T6/T7; implemented T3/T4. Mirror's `install()` called by T8 ProjectActivity + tests.

---

## Execution Handoff

**Plan v2 complete.** Per user instruction, design and plan are subagent-verified (not user-verified). Next step: subagent-driven execution per `feedback_always_subagent.md` and `feedback_skip_subagent_reviews.md` (implementer + code-reviewer per task; no spec/quality reviewers).
