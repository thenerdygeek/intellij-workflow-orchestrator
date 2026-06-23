# Plugin Split — Phase 0b-2: `VcsHostClient` + `CiService` neutral seams — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce two genuinely-neutral connector seams in `:core` — `VcsHostClient` (over Bitbucket DC) and `CiService` (over Bamboo) — so a future GitHub/GitLab/Jenkins connector is *additive* and the engine can eventually talk to "a VCS host" / "a CI service" without vendor vocabulary. **Behavior-unchanged:** every new symbol is additive and unconsumed in this phase (sibling to how Phase 0b-1 shaped `NativeProtocol`).

**Architecture:** Each seam is an independent `@InternalApi public interface` in `:core` whose methods use neutral names/params and (for CI) neutral DTOs. The existing vendor impls implement *both* their concrete interface and the neutral one ("dual-implementation"):
- `BitbucketServiceImpl : BitbucketService, VcsHostClient` — VcsHostClient's signatures are byte-identical to BitbucketService's (only param *names* differ + one `typealias`), so the existing overrides satisfy it with **zero new methods**.
- `BambooServiceImpl : BambooService, CiService` — CiService renames Bamboo vocabulary (`chainKey`→`pipelineId`, `getPlans`→`getPipelines`, `PlanData`→`PipelineData`), so it needs **7 thin delegating methods** + 2 new neutral DTOs.

No `plugin.xml` change, no EP/service registration, no consumer touched. Resolution wiring (so a neutral consumer can resolve a `CiService`/`VcsHostClient`) is **deferred to the phase that adds the first neutral consumer** — exactly the `NativeProtocol` shape-reservation precedent.

**Tech Stack:** Kotlin 2.1.10 · Gradle + IntelliJ Platform Plugin v2 · IntelliJ IDEA 2025.1+ · JUnit5 (`kotlinx.coroutines.test.runTest`) · MockK (`mockk`/`spyk`/`coEvery`/`coVerify`) · konsist (architecture contracts).

**Grounding maps (read before starting):**
- `.superpowers/phase0b/0b2-explore-vcs.md` — full `BitbucketService` 41-method classification, consumers, DTO neutrality, registration.
- `.superpowers/phase0b/0b2-explore-ci.md` — full `BambooService` 30-method classification, neutral-subset recommendation, consumers, DTO neutrality.
- Spec: `docs/superpowers/specs/2026-06-22-plugin-split-design.md` §8.3 (VcsHostClient), §8.4 (CiService).
- Sibling phase (style reference): `docs/superpowers/plans/2026-06-23-plugin-split-phase0b-1-llm-seam.md`.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **BEHAVIOR-UNCHANGED.** No existing call site, no `plugin.xml` entry, no service registration, no existing interface, and no existing DTO may change semantics. Everything added here is additive and unconsumed in this phase. The proof obligation per task: "what observable behavior could change?" must answer "none."
- **`:core` ONE-`BasePlatformTestCase` invariant.** `:core`'s test JVM is un-forked; a *second* platform fixture causes a deterministic headless "Indexing timeout" (#51). **All new `:core` tests in this plan are pure JUnit5** (plain classes, no `BasePlatformTestCase`, no IntelliJ fixture). Every symbol added to `:core` here (DTOs, mapping fns, the `mapData` extension, the interfaces) is pure/data-only and unit-testable without a fixture. Do NOT add a `BasePlatformTestCase` to `:core`.
- **Seam interfaces are `public` + `@InternalApi`, NEVER `internal`.** Kotlin `internal` is module-scoped; a future plugin-B (separate plugin) could not implement an `internal` interface. `@InternalApi` is `@Retention(RUNTIME)` (`core/.../core/api/ApiStability.kt`) so reflection-based contract tests can see it. Do NOT use `@StableApi` (that is the deferred Phase-5 external freeze).
- **detekt: AUTOCORRECT, do NOT baseline.** After code changes, run `./gradlew :<module>:detekt --auto-correct` then verify clean. `verifyPlugin`/tests do NOT run detekt; CI `check` does — make detekt green before declaring done.
- **No `runBlocking` in `main/`** (pre-commit hook); use `runBlockingCancellable`. (Not expected to arise here.)
- **`--no-build-cache --rerun-tasks`** if any `NoSuchMethodError` appears at test runtime (Gradle compile-avoidance can keep stale bytecode when interface method sets change). Adding methods/supertypes *shouldn't* trigger it, but use it the moment a spurious `NoSuchMethodError` shows up.
- **Webview dist churn:** builds regenerate `agent/src/main/resources/webview/dist/` — never `git add` it; `git checkout -- agent/src/main/resources/webview/dist/` before committing if it shows up.
- **Frequent commits:** one commit per task, conventional-commit message, co-author trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **Don't trust the implementer's report** — the controller runs the authoritative build itself (Task 7 green gate).

---

## File Structure

**New files (`:core`):**
- `core/src/main/kotlin/com/workflow/orchestrator/core/services/ToolResultMapping.kt` — pure `ToolResult<T>.mapData(transform)` extension (Task 1).
- `core/src/main/kotlin/com/workflow/orchestrator/core/model/CiModels.kt` — neutral `PipelineData` + `CiGroupData` DTOs and the `PlanData.toPipelineData()` / `ProjectData.toCiGroupData()` adapter functions (Task 2).
- `core/src/main/kotlin/com/workflow/orchestrator/core/services/CiService.kt` — neutral CI seam interface (Task 3).
- `core/src/main/kotlin/com/workflow/orchestrator/core/services/VcsHostClient.kt` — neutral VCS-host seam interface + `VcsUserData` typealias (Task 5).

**New tests:**
- `core/src/test/kotlin/com/workflow/orchestrator/core/services/ToolResultMappingTest.kt` (Task 1).
- `core/src/test/kotlin/com/workflow/orchestrator/core/model/CiModelsTest.kt` (Task 2).
- `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImplCiServiceDelegationTest.kt` (Task 4).
- `pullrequest/src/test/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImplVcsHostClientTest.kt` (Task 5).

**Modified files:**
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt:37` — class header `+ , CiService`; add 7 delegating overrides (Task 4).
- `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt:33` — class header `+ , VcsHostClient` (Task 5).
- `core/src/test/kotlin/com/workflow/orchestrator/core/ai/protocol/SeamApiStabilityTest.kt:11` — add `CiService`, `VcsHostClient` to the seam list (Tasks 3, 5).
- `konsist/src/test/kotlin/com/workflow/orchestrator/konsist/PublicApiSurfaceTest.kt:8-17` — add `"CiService"`, `"VcsHostClient"` to `bFacingEpInterfaces` (Tasks 3, 5).
- `core/CLAUDE.md` — document the two new seams (Task 6).
- `docs/superpowers/specs/2026-06-22-plugin-split-design.md` — fold the resolved 0b-2 design (Task 6).

**Deliberately NOT touched (documented scope boundaries):**
- `BitbucketService.kt` / `BambooService.kt` — unchanged (dual-impl, not supertype-extraction → no edits to the concrete interfaces).
- `getDefaultBranch` / default-reviewer ops — live on the lower `BitbucketBranchClient` (not `BitbucketService`); `DefaultBranchResolver` already calls them directly. Adding a neutral `getDefaultBranch` to `VcsHostClient` would be a *new operation* (new impl logic), which belongs to **Phase 1** (default-branch de-convention, where it gets a real consumer), not this behavior-unchanged phase.
- `getLinkedJiraIssues` / `getRequiredBuilds` — vendor-coupled (Bitbucket↔Jira link plugin; `RequiredBuildsCondition.buildParentKeys` are Bamboo plan keys). **Excluded** from `VcsHostClient`; they stay `BitbucketService`-only.
- The Bamboo-specific surface (`enablePlanBranch`, `getPlanVariables`, `getPlanBranches`, `getBuildVariables`, `triggerStage`, `getPlanShortName`, `autoDetectPlan` ×2) — stays `BambooService`-only; excluded from `CiService`.
- **Residual coupling (documented, deferred):** `CiService` still references DTOs packaged under `core.model.bamboo` (`BuildResultData`, `BuildTriggerData`, `TestResultsData`, `ArtifactData`, `BuildChangeData`). Their *names* are already neutral; relocating their *package* touches every consumer and is out of scope for behavior-unchanged 0b-2.

---

## Task 1: `ToolResult.mapData` pure extension

**Why:** The CI delegating methods (Task 4) must transform `ToolResult<List<PlanData>>` → `ToolResult<List<PipelineData>>` while preserving the result envelope (`summary`/`isError`/`hint`/`tokenEstimate`/`imageRefs`/`payload`). `ToolResult` has **no** `map` helper today (verified: `core/.../services/ToolResult.kt`). One pure, tested extension avoids 4 hand-rolled reconstructions.

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/services/ToolResultMapping.kt`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/services/ToolResultMappingTest.kt`

**Interfaces:**
- Consumes: `ToolResult<T>` (`core/.../services/ToolResult.kt`).
- Produces: `fun <T, R> ToolResult<T>.mapData(transform: (T) -> R): ToolResult<R>` — applies `transform` to `data` when non-null, copying all other fields verbatim. **Used by Task 4.**

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.core.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolResultMappingTest {

    @Test fun `mapData transforms data and preserves the envelope`() {
        val src = ToolResult(
            data = listOf(1, 2, 3),
            summary = "three numbers",
            isError = false,
            hint = "a hint",
            tokenEstimate = 7,
        )

        val mapped = src.mapData { nums -> nums.map { it * 10 } }

        assertEquals(listOf(10, 20, 30), mapped.data)
        assertEquals("three numbers", mapped.summary)
        assertEquals(false, mapped.isError)
        assertEquals("a hint", mapped.hint)
        assertEquals(7, mapped.tokenEstimate)
    }

    @Test fun `mapData leaves null data null and does not invoke transform`() {
        val src = ToolResult.error<List<Int>>(summary = "boom", hint = "retry")

        val mapped = src.mapData { error("transform must not run on null data") }

        assertNull(mapped.data)
        assertTrue(mapped.isError)
        assertEquals("boom", mapped.summary)
        assertEquals("retry", mapped.hint)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.services.ToolResultMappingTest"`
Expected: FAIL — compile error / unresolved reference `mapData`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.workflow.orchestrator.core.services

/**
 * Maps a [ToolResult]'s [ToolResult.data] payload while preserving the rest of the result
 * envelope ([summary]/[isError]/[hint]/[tokenEstimate]/[imageRefs]/[payload]). [transform]
 * is invoked only when [data] is non-null, so error results pass through untouched.
 *
 * Added for the Phase 0b-2 neutral connector seams: the CI seam's delegating methods remap
 * vendor DTOs (e.g. `PlanData`) to neutral ones (`PipelineData`) without rebuilding the envelope.
 */
fun <T, R> ToolResult<T>.mapData(transform: (T) -> R): ToolResult<R> =
    ToolResult(
        data = data?.let(transform),
        summary = summary,
        isError = isError,
        hint = hint,
        tokenEstimate = tokenEstimate,
        imageRefs = imageRefs,
        payload = payload,
    )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.services.ToolResultMappingTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: detekt + commit**

```bash
./gradlew :core:detekt --auto-correct && ./gradlew :core:detekt
git add core/src/main/kotlin/com/workflow/orchestrator/core/services/ToolResultMapping.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/services/ToolResultMappingTest.kt
git commit -m "feat(core): pure ToolResult.mapData extension (envelope-preserving) for 0b-2 CI seam

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Neutral CI DTOs `PipelineData` / `CiGroupData` + adapters

**Why:** `PlanData` and `ProjectData` (`core/.../model/bamboo/BambooModels.kt`) carry Bamboo vocabulary in their names and the `projectKey`/`projectName` fields. The CI seam returns neutral `PipelineData`/`CiGroupData` instead. The pure adapter functions (`toPipelineData`/`toCiGroupData`) are unit-tested here, so Task 4's delegation test need not re-verify field mapping.

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/model/CiModels.kt`
- Test: `core/src/test/kotlin/com/workflow/orchestrator/core/model/CiModelsTest.kt`

**Interfaces:**
- Consumes: `PlanData(key, name, shortName, projectKey, projectName, enabled)` and `ProjectData(key, name, description)` from `core.model.bamboo` (verified field shapes).
- Produces:
  - `data class PipelineData(key: String, name: String, shortName: String = "", groupKey: String, groupName: String, enabled: Boolean = true)`
  - `data class CiGroupData(key: String, name: String, description: String? = null)`
  - `fun PlanData.toPipelineData(): PipelineData` (mapping: `projectKey→groupKey`, `projectName→groupName`, rest 1:1)
  - `fun ProjectData.toCiGroupData(): CiGroupData` (1:1)
  - **Used by Tasks 3 (interface return types) and 4 (impl mapping).**

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.core.model

import com.workflow.orchestrator.core.model.bamboo.PlanData
import com.workflow.orchestrator.core.model.bamboo.ProjectData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CiModelsTest {

    @Test fun `PlanData maps to PipelineData with project fields renamed to group`() {
        val plan = PlanData(
            key = "MY-PROJ-AUTOTESTS",
            name = "Auto Tests",
            shortName = "Auto",
            projectKey = "MY-PROJ",
            projectName = "My Project",
            enabled = false,
        )

        val pipeline = plan.toPipelineData()

        assertEquals(
            PipelineData(
                key = "MY-PROJ-AUTOTESTS",
                name = "Auto Tests",
                shortName = "Auto",
                groupKey = "MY-PROJ",
                groupName = "My Project",
                enabled = false,
            ),
            pipeline,
        )
    }

    @Test fun `ProjectData maps to CiGroupData one to one`() {
        val project = ProjectData(key = "MY-PROJ", name = "My Project", description = "desc")

        assertEquals(
            CiGroupData(key = "MY-PROJ", name = "My Project", description = "desc"),
            project.toCiGroupData(),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.model.CiModelsTest"`
Expected: FAIL — unresolved `PipelineData`/`CiGroupData`/`toPipelineData`/`toCiGroupData`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.workflow.orchestrator.core.model

import com.workflow.orchestrator.core.model.bamboo.PlanData
import com.workflow.orchestrator.core.model.bamboo.ProjectData

/**
 * Neutral CI-pipeline descriptor — the vendor-agnostic counterpart of Bamboo's [PlanData]
 * (Phase 0b-2 `CiService` seam). A "pipeline" is one buildable unit (Bamboo plan / Jenkins job /
 * GitHub-Actions workflow); a "group" ([CiGroupData]) is its container (Bamboo project / Jenkins
 * folder / GitHub org-or-repo). Field names avoid Bamboo's "plan/project" vocabulary.
 */
data class PipelineData(
    val key: String,
    val name: String,
    val shortName: String = "",
    val groupKey: String,
    val groupName: String,
    val enabled: Boolean = true,
)

/** Neutral CI grouping descriptor — vendor-agnostic counterpart of Bamboo's [ProjectData]. */
data class CiGroupData(
    val key: String,
    val name: String,
    val description: String? = null,
)

/** Adapter: Bamboo [PlanData] → neutral [PipelineData] (`projectKey/projectName` → `groupKey/groupName`). */
fun PlanData.toPipelineData(): PipelineData =
    PipelineData(
        key = key,
        name = name,
        shortName = shortName,
        groupKey = projectKey,
        groupName = projectName,
        enabled = enabled,
    )

/** Adapter: Bamboo [ProjectData] → neutral [CiGroupData] (1:1). */
fun ProjectData.toCiGroupData(): CiGroupData =
    CiGroupData(key = key, name = name, description = description)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.model.CiModelsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: detekt + commit**

```bash
./gradlew :core:detekt --auto-correct && ./gradlew :core:detekt
git add core/src/main/kotlin/com/workflow/orchestrator/core/model/CiModels.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/model/CiModelsTest.kt
git commit -m "feat(core): neutral PipelineData/CiGroupData DTOs + Bamboo adapters (0b-2 CI seam)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `CiService` neutral interface + contract tests

**Why:** Declares the neutral CI seam (18 ops) and pins it to the established seam-stability contract (public + `@InternalApi`). No behavior — the interface is the deliverable; its contract tests are the test.

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/services/CiService.kt`
- Modify: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/protocol/SeamApiStabilityTest.kt`
- Modify: `konsist/src/test/kotlin/com/workflow/orchestrator/konsist/PublicApiSurfaceTest.kt`

**Interfaces:**
- Consumes: `ToolResult` (`core.services`); `BuildResultData`, `BuildTriggerData`, `TestResultsData`, `ArtifactData`, `BuildChangeData` (`core.model.bamboo`); `PipelineData`, `CiGroupData` (`core.model`, Task 2).
- Produces: `interface CiService` with the 18 methods below. **Implemented by Task 4.** Method names/params are the neutral contract Task 4 must match exactly.

- [ ] **Step 1: Write the failing test** (extend the two existing contract tests)

In `core/src/test/kotlin/com/workflow/orchestrator/core/ai/protocol/SeamApiStabilityTest.kt`, add the import and extend the seam list:

```kotlin
import com.workflow.orchestrator.core.services.CiService
```

```kotlin
    private val seam = listOf(
        ToolProtocol::class.java,
        NativeProtocol::class.java,
        LlmProvider::class.java,
        CiService::class.java,
    )
```

In `konsist/src/test/kotlin/com/workflow/orchestrator/konsist/PublicApiSurfaceTest.kt`, add `"CiService"` to `bFacingEpInterfaces`:

```kotlin
    private val bFacingEpInterfaces = listOf(
        "AgentToolContributor",
        "WorkflowConfig",
        "AuthProvider",
        "FeatureRegistry",
        "JiraTicketProvider",
        "ToolProtocol",
        "NativeProtocol",
        "LlmProvider",
        "CiService",
    )
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.ai.protocol.SeamApiStabilityTest"`
Expected: FAIL — unresolved reference `CiService` (compile error; the `::class.java` literal guards against silent pass).

- [ ] **Step 3: Write the interface**

```kotlin
package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.api.InternalApi
import com.workflow.orchestrator.core.model.CiGroupData
import com.workflow.orchestrator.core.model.PipelineData
import com.workflow.orchestrator.core.model.bamboo.ArtifactData
import com.workflow.orchestrator.core.model.bamboo.BuildChangeData
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildTriggerData
import com.workflow.orchestrator.core.model.bamboo.TestResultsData

/**
 * Neutral CI-server seam layered ABOVE the vendor-specific [BambooService] (Phase 0b-2 of the
 * plugin split). Captures the host-agnostic subset of CI operations so a future Jenkins /
 * GitHub-Actions connector can implement [CiService] without inheriting Bamboo vocabulary.
 *
 * SHAPE RESERVATION ONLY in 0b-2: the sole implementation is [BambooServiceImpl] (which also
 * implements [BambooService]); no consumer resolves [CiService] yet and there is no service/EP
 * registration — both arrive in the phase that adds the first neutral consumer (sibling to how
 * `NativeProtocol` was shaped before Phase 4). `public` + [InternalApi] = unfrozen-by-policy.
 *
 * Identifier convention: [pipelineId] selects WHICH buildable unit (Bamboo branch-chain key;
 * Jenkins job path); [buildId] selects a SPECIFIC build result (Bamboo result key; Jenkins build
 * number). Both are opaque strings interpreted by each implementation.
 *
 * Bamboo-specific operations (stage-level trigger, plan variables, plan-branch enable, 5-tier
 * plan auto-detect, plan short-name) are intentionally NOT here — they remain on [BambooService].
 */
@InternalApi
interface CiService {

    /** Latest build result for a pipeline. */
    suspend fun getLatestBuild(pipelineId: String): ToolResult<BuildResultData>

    /** A specific build result by its opaque id. */
    suspend fun getBuild(buildId: String): ToolResult<BuildResultData>

    /** Trigger a build with optional variable overrides. (Stage-level granularity stays on [BambooService].) */
    suspend fun triggerBuild(
        pipelineId: String,
        variables: Map<String, String> = emptyMap(),
    ): ToolResult<BuildTriggerData>

    /** Test the CI connection. */
    suspend fun testConnection(): ToolResult<Unit>

    /** Full log text for a build. */
    suspend fun getBuildLog(buildId: String): ToolResult<String>

    /** Test-result summary for a build. */
    suspend fun getTestResults(buildId: String): ToolResult<TestResultsData>

    /** Re-run only the failed jobs from a prior build. */
    suspend fun retryFailedJobs(pipelineId: String, buildNumber: Int): ToolResult<Unit>

    /** Stop a running build. */
    suspend fun stopBuild(buildId: String): ToolResult<Unit>

    /** Cancel a queued (not-yet-running) build. */
    suspend fun cancelBuild(buildId: String): ToolResult<Unit>

    /** Artifacts produced by a build. */
    suspend fun getArtifacts(buildId: String): ToolResult<List<ArtifactData>>

    /** Download an artifact to a local file. Returns true on success. */
    suspend fun downloadArtifact(artifactUrl: String, targetFile: java.io.File): ToolResult<Boolean>

    /** Recent build results for a pipeline. */
    suspend fun getRecentBuilds(pipelineId: String, maxResults: Int = 10): ToolResult<List<BuildResultData>>

    /** All pipelines visible to the authenticated user. */
    suspend fun getPipelines(): ToolResult<List<PipelineData>>

    /** Pipelines within a group. */
    suspend fun getPipelinesForGroup(groupKey: String): ToolResult<List<PipelineData>>

    /** Search pipelines by name or key. */
    suspend fun searchPipelines(query: String): ToolResult<List<PipelineData>>

    /** Running and queued builds for a pipeline. */
    suspend fun getRunningBuilds(pipelineId: String): ToolResult<List<BuildResultData>>

    /** CI groups visible to the authenticated user. */
    suspend fun getGroups(): ToolResult<List<CiGroupData>>

    /** Commits included in a specific build. */
    suspend fun getBuildChanges(buildId: String): ToolResult<List<BuildChangeData>>
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.ai.protocol.SeamApiStabilityTest"`
Run: `./gradlew :konsist:test --tests "com.workflow.orchestrator.konsist.PublicApiSurfaceTest"`
Expected: both PASS.

- [ ] **Step 5: detekt + commit**

```bash
./gradlew :core:detekt --auto-correct && ./gradlew :core:detekt
git add core/src/main/kotlin/com/workflow/orchestrator/core/services/CiService.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/ai/protocol/SeamApiStabilityTest.kt \
        konsist/src/test/kotlin/com/workflow/orchestrator/konsist/PublicApiSurfaceTest.kt
git commit -m "feat(core): CiService neutral seam interface (@InternalApi) + contract tests (0b-2)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `BambooServiceImpl` implements `CiService` (dual-implementation)

**Why:** Proves the neutral CI shape is satisfiable by the Atlassian impl and wires the neutral methods to the existing Bamboo ones — behavior-unchanged (the new methods are unused by any consumer).

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt` (header at `:37`; add 7 overrides)
- Test: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImplCiServiceDelegationTest.kt`

**Interfaces:**
- Consumes: `CiService` (Task 3); `mapData` (Task 1); `toPipelineData`/`toCiGroupData` (Task 2); the existing `BambooService` methods on `this` (`getPlans`, `getProjectPlans`, `searchPlans`, `getProjects`, `triggerBuild(chainKey, variables, stages)`, `rerunFailedJobs`, `getRunningBuilds(planKey, repoName)`).
- Produces: a `BambooServiceImpl` that `IS-A CiService`. **11 of CiService's 18 methods are already satisfied** by existing overrides whose JVM signatures are identical (`getLatestBuild`, `getBuild`, `testConnection`, `getBuildLog`, `getTestResults`, `stopBuild`, `cancelBuild`, `getArtifacts`, `downloadArtifact`, `getRecentBuilds`, `getBuildChanges`) — add NO new method for those. The 7 below need new overrides.

⚠ **Recursion trap:** `triggerBuild` and `getRunningBuilds` share a *name* with a different-arity `BambooService` method. The delegating override MUST pass the disambiguating argument explicitly, or Kotlin resolves the call back to the override itself (infinite recursion). The code below does this (`stages = null`, `repoName = null`).

ℹ **Test-harness note:** this test uses `spyk(BambooServiceImpl(...))`, which no existing `:bamboo` test uses (they stub via the `testClientOverride` seam). It should work (MockK subclasses the open class; `@Volatile` fields don't impede this), but it's an untrodden path — if `spyk`/`coEvery` misbehaves, fall back to the `testClientOverride` pattern for the 4 mapping methods and keep `coVerify` for the 3 passthrough methods. The Step 4 run is the real proof.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.bamboo.PlanData
import com.workflow.orchestrator.core.model.bamboo.ProjectData
import com.workflow.orchestrator.core.services.CiService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Phase 0b-2: verifies [BambooServiceImpl] satisfies the neutral [CiService] seam and that each
 * renamed/reshaped CiService method delegates to the correct [BambooService] method. Delegation is
 * tested at the BambooService boundary via spyk (NOT at the API-client level) so this stays
 * independent of BambooApiClient internals; the PlanData->PipelineData field mapping is covered by
 * CiModelsTest.
 */
class BambooServiceImplCiServiceDelegationTest {

    private val service = spyk(BambooServiceImpl(mockk<Project>(relaxed = true)))
    private val ci: CiService = service

    @Test fun `BambooServiceImpl is a CiService`() {
        // Compile-time IS-A is the primary guarantee; this asserts the runtime upcast holds.
        assertEquals(true, ci is BambooServiceImpl)
    }

    @Test fun `getPipelines delegates to getPlans and maps to PipelineData`() = runTest {
        coEvery { service.getPlans() } returns ToolResult.success(
            listOf(PlanData("G-AUTO", "Auto", "Auto", "G", "Group", enabled = true)),
            "ok",
        )

        val result = ci.getPipelines()

        assertFalse(result.isError)
        val p = result.data!!.single()
        assertEquals("G-AUTO", p.key)
        assertEquals("G", p.groupKey)        // projectKey -> groupKey
        assertEquals("Group", p.groupName)   // projectName -> groupName
        coVerify(exactly = 1) { service.getPlans() }
    }

    @Test fun `getPipelinesForGroup delegates to getProjectPlans`() = runTest {
        coEvery { service.getProjectPlans("G") } returns ToolResult.success(
            listOf(PlanData("G-AUTO", "Auto", "Auto", "G", "Group", enabled = true)),
            "ok",
        )

        val result = ci.getPipelinesForGroup("G")

        assertFalse(result.isError)
        assertEquals("G", result.data!!.single().groupKey)
        coVerify(exactly = 1) { service.getProjectPlans("G") }
    }

    @Test fun `searchPipelines delegates to searchPlans`() = runTest {
        coEvery { service.searchPlans("auto") } returns ToolResult.success(
            listOf(PlanData("G-AUTO", "Auto", "Auto", "G", "Group", enabled = true)),
            "ok",
        )

        val result = ci.searchPipelines("auto")

        assertFalse(result.isError)
        assertEquals("G-AUTO", result.data!!.single().key)
        coVerify(exactly = 1) { service.searchPlans("auto") }
    }

    @Test fun `getGroups delegates to getProjects and maps to CiGroupData`() = runTest {
        coEvery { service.getProjects() } returns ToolResult.success(
            listOf(ProjectData("G", "Group", "desc")),
            "ok",
        )

        val result = ci.getGroups()

        assertFalse(result.isError)
        val g = result.data!!.single()
        assertEquals("G", g.key)
        assertEquals("Group", g.name)
        assertEquals("desc", g.description)
        coVerify(exactly = 1) { service.getProjects() }
    }

    @Test fun `triggerBuild delegates with explicit null stages (no recursion)`() = runTest {
        coEvery { service.triggerBuild("pid", emptyMap(), null) } returns ToolResult.error("stubbed")

        ci.triggerBuild("pid")

        coVerify(exactly = 1) { service.triggerBuild("pid", emptyMap(), null) }
    }

    @Test fun `retryFailedJobs delegates to rerunFailedJobs`() = runTest {
        coEvery { service.rerunFailedJobs("pid", 42) } returns ToolResult.error("stubbed")

        ci.retryFailedJobs("pid", 42)

        coVerify(exactly = 1) { service.rerunFailedJobs("pid", 42) }
    }

    @Test fun `getRunningBuilds delegates with explicit null repoName (no recursion)`() = runTest {
        coEvery { service.getRunningBuilds("pid", null) } returns ToolResult.error("stubbed")

        ci.getRunningBuilds("pid")

        coVerify(exactly = 1) { service.getRunningBuilds("pid", null) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.BambooServiceImplCiServiceDelegationTest"`
Expected: FAIL — `BambooServiceImpl` does not implement `CiService` (compile error: `ci is BambooServiceImpl` fine, but `val ci: CiService = service` fails to compile / class does not conform).

- [ ] **Step 3: Implement — add the supertype + 7 delegating overrides**

Edit the class header (`bamboo/.../service/BambooServiceImpl.kt:37`):

```kotlin
class BambooServiceImpl(private val project: Project) : BambooService, CiService {
```

Add these imports near the top of the file:

```kotlin
import com.workflow.orchestrator.core.model.CiGroupData
import com.workflow.orchestrator.core.model.PipelineData
import com.workflow.orchestrator.core.model.toCiGroupData
import com.workflow.orchestrator.core.model.toPipelineData
import com.workflow.orchestrator.core.services.CiService
import com.workflow.orchestrator.core.services.mapData
```

Add the 7 delegating overrides inside the class (group them under a `// --- CiService neutral seam (Phase 0b-2): delegates to the BambooService methods above ---` comment). The other 11 CiService methods need NOTHING — the existing overrides already satisfy them (identical JVM signatures).

```kotlin
    // --- CiService neutral seam (Phase 0b-2): delegates to the BambooService methods above ---
    // The other 11 CiService methods (getLatestBuild/getBuild/testConnection/getBuildLog/
    // getTestResults/stopBuild/cancelBuild/getArtifacts/downloadArtifact/getRecentBuilds/
    // getBuildChanges) are already satisfied by the BambooService overrides above — identical
    // JVM signatures, so no extra override is needed.
    // MAINTAINER NOTE: those 11 bind "for free" ONLY because each shares an identical JVM signature
    // with its BambooService counterpart. If a future BambooService overload reuses one of those
    // CiService method names (changing arity/types), re-verify the delegation still binds — add an
    // explicit override here if it no longer does (same disambiguation as triggerBuild/getRunningBuilds).

    override suspend fun triggerBuild(
        pipelineId: String,
        variables: Map<String, String>,
    ): ToolResult<BuildTriggerData> =
        // Explicit `stages = null` disambiguates from this 2-arg override (else infinite recursion).
        triggerBuild(pipelineId, variables, stages = null)

    override suspend fun retryFailedJobs(pipelineId: String, buildNumber: Int): ToolResult<Unit> =
        rerunFailedJobs(pipelineId, buildNumber)

    override suspend fun getRunningBuilds(pipelineId: String): ToolResult<List<BuildResultData>> =
        // Explicit `repoName = null` disambiguates from this 1-arg override (else infinite recursion).
        getRunningBuilds(pipelineId, repoName = null)

    override suspend fun getPipelines(): ToolResult<List<PipelineData>> =
        getPlans().mapData { plans -> plans.map { it.toPipelineData() } }

    override suspend fun getPipelinesForGroup(groupKey: String): ToolResult<List<PipelineData>> =
        getProjectPlans(groupKey).mapData { plans -> plans.map { it.toPipelineData() } }

    override suspend fun searchPipelines(query: String): ToolResult<List<PipelineData>> =
        searchPlans(query).mapData { plans -> plans.map { it.toPipelineData() } }

    override suspend fun getGroups(): ToolResult<List<CiGroupData>> =
        getProjects().mapData { projects -> projects.map { it.toCiGroupData() } }
```

> Note: `BuildResultData`/`BuildTriggerData`/`ToolResult` are already imported in `BambooServiceImpl.kt`. If the compiler reports the `getRunningBuilds`/`triggerBuild` overrides as conflicting/duplicate, confirm the existing `BambooService` versions keep their original signatures (`getRunningBuilds(planKey, repoName)`, `triggerBuild(chainKey, variables, stages)`) — the new ones are *additional* overloads from `CiService`, not replacements.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :bamboo:test --tests "com.workflow.orchestrator.bamboo.service.BambooServiceImplCiServiceDelegationTest"`
Expected: PASS (8 tests). If a `NoSuchMethodError` appears, re-run with `--no-build-cache --rerun-tasks`.

- [ ] **Step 5: Regression — run the full `:bamboo` suite**

Run: `./gradlew :bamboo:test`
Expected: PASS (existing `BambooServiceImpl*`/`BambooMonitorSource*` tests unaffected — behavior-unchanged).

- [ ] **Step 6: Update `bamboo/CLAUDE.md`** (module-doc rule: architecture change in same commit)

In `bamboo/CLAUDE.md`, in the "Architecture" bullet that reads `BambooServiceImpl — implements BambooService (in :core), returns ToolResult<T>`, extend it to note it now ALSO implements the neutral `CiService` seam (Phase 0b-2): the 11 identical-signature methods bind for free; 7 thin delegating methods map Bamboo vocabulary (`chainKey→pipelineId`, `getPlans→getPipelines`, `PlanData→PipelineData`) — behavior-unchanged, no consumer yet.

- [ ] **Step 7: detekt + commit**

```bash
./gradlew :bamboo:detekt --auto-correct && ./gradlew :bamboo:detekt
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt \
        bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImplCiServiceDelegationTest.kt \
        bamboo/CLAUDE.md
git commit -m "feat(bamboo): BambooServiceImpl implements neutral CiService (dual-impl, behavior-unchanged)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `VcsHostClient` neutral interface + `BitbucketServiceImpl` implements it

**Why:** Declares the neutral VCS-host seam and proves the Atlassian impl satisfies it. Because `BitbucketService`'s signatures are already neutral-shaped, the impl satisfies `VcsHostClient` with **zero new methods** — the only changes are the interface (neutral param names + `VcsUserData` typealias), one impl-header line, and the contract-test additions. The impl's *compilation* against both interfaces is what validates that every transcribed signature matches exactly.

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/services/VcsHostClient.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt` (header at `:33`)
- Modify: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/protocol/SeamApiStabilityTest.kt` (add `VcsHostClient`)
- Modify: `konsist/src/test/kotlin/com/workflow/orchestrator/konsist/PublicApiSurfaceTest.kt` (add `"VcsHostClient"`)
- Test: `pullrequest/src/test/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImplVcsHostClientTest.kt`

**Interfaces:**
- Consumes: `ToolResult`; the `core.model.bitbucket.*` DTOs (`RepoInfo`, `PullRequestData`, `CommitData`, `BranchData`, `BitbucketUserData`, `PullRequestDetailData`, `PrActivityData`, `PrChangeData`, `BuildStatusData`, `MergeStatusData`, `ParticipantData`, `BuildStatsData`); `PrComment` (`core.model`).
- Produces: `interface VcsHostClient` covering the genuinely-neutral BitbucketService operations (all 41 EXCEPT `getLinkedJiraIssues` + `getRequiredBuilds`), with `typealias VcsUserData = BitbucketUserData` and neutral comment-coordinate params (`repoOwner`/`repoName` instead of `projectKey`/`repoSlug`). **Implemented by `BitbucketServiceImpl`.**

> ⚠ **CORRECTION (from Task 4 execution — supersedes the original "identical including defaults" instruction below).** Kotlin forbids an override inheriting a default value for the same parameter from MORE THAN ONE supertype interface (`MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES`), **even when the default values are equal**. `BitbucketService` already declares defaults (`repoName: String? = null`, `state: String = "OPEN"`, `deleteSourceBranch: Boolean = false`, `onlyOpen/onlyInline: Boolean = false`, `diffType/fromHash/toHash: String? = null`, …). Therefore **`VcsHostClient` MUST declare NO default values on any parameter** — every parameter is required in the interface. Only `BitbucketService` then provides defaults, so the single existing impl override inherits them with no conflict; `VcsHostClient`-typed callers have no defaults, which is harmless because `VcsHostClient` has **zero consumers** in this phase (behavior-unchanged). This mirrors the Task-4 fix on `CiService.getRecentBuilds`.

**Transcription rule (follow exactly):** copy each method signature from `core/.../services/BitbucketService.kt`, with four edits:
1. `searchUsers` returns `ToolResult<List<VcsUserData>>` (typealias — same type, so the impl conforms).
2. The 6 comment methods (`listPrComments`, `getPrComment`, `editPrComment`, `deletePrComment`, `resolvePrComment`, `reopenPrComment`): rename params `projectKey`→`repoOwner`, `repoSlug`→`repoName` (types unchanged → identical JVM signatures).
3. Omit `getLinkedJiraIssues` and `getRequiredBuilds`.
4. **DROP EVERY default value** — strip all ` = null` / ` = "OPEN"` / ` = false` / ` = ...` from the parameters (see CORRECTION above). Param TYPES and ORDER stay identical to `BitbucketService` (so JVM signatures match and the impl conforms); only the ` = default` suffixes are removed.
Everything else (param types, order, return types) is identical to `BitbucketService`.

- [ ] **Step 1: Write the failing test** (extend contract tests + add an IS-A test)

In `SeamApiStabilityTest.kt`, add import + list entry:

```kotlin
import com.workflow.orchestrator.core.services.VcsHostClient
```

```kotlin
    private val seam = listOf(
        ToolProtocol::class.java,
        NativeProtocol::class.java,
        LlmProvider::class.java,
        CiService::class.java,
        VcsHostClient::class.java,
    )
```

In `PublicApiSurfaceTest.kt`, add `"VcsHostClient"` to `bFacingEpInterfaces`.

Create `pullrequest/src/test/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImplVcsHostClientTest.kt`:

```kotlin
package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.services.VcsHostClient
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Phase 0b-2: BitbucketServiceImpl satisfies the neutral VcsHostClient seam. Because every
 * VcsHostClient method has a JVM signature identical to its BitbucketService counterpart (only
 * param names differ + a VcsUserData typealias), the existing overrides conform with no new code —
 * so the compile-time IS-A relationship is the real guarantee, asserted here at runtime.
 */
class BitbucketServiceImplVcsHostClientTest {

    @Test fun `BitbucketServiceImpl is a VcsHostClient`() {
        val impl = BitbucketServiceImpl(mockk<Project>(relaxed = true))
        val vcs: VcsHostClient = impl
        assertEquals(true, vcs is BitbucketServiceImpl)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.ai.protocol.SeamApiStabilityTest"`
Expected: FAIL — unresolved `VcsHostClient`.

- [ ] **Step 3: Write the interface**

```kotlin
package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.api.InternalApi
import com.workflow.orchestrator.core.model.PrComment
import com.workflow.orchestrator.core.model.bitbucket.BitbucketUserData
import com.workflow.orchestrator.core.model.bitbucket.BranchData
import com.workflow.orchestrator.core.model.bitbucket.BuildStatsData
import com.workflow.orchestrator.core.model.bitbucket.BuildStatusData
import com.workflow.orchestrator.core.model.bitbucket.CommitData
import com.workflow.orchestrator.core.model.bitbucket.MergeStatusData
import com.workflow.orchestrator.core.model.bitbucket.ParticipantData
import com.workflow.orchestrator.core.model.bitbucket.PrActivityData
import com.workflow.orchestrator.core.model.bitbucket.PrChangeData
import com.workflow.orchestrator.core.model.bitbucket.PullRequestData
import com.workflow.orchestrator.core.model.bitbucket.PullRequestDetailData
import com.workflow.orchestrator.core.model.bitbucket.RepoInfo

/** Neutral name for a VCS-host user; the underlying DTO fields (name/displayName/emailAddress) are already vendor-agnostic. */
typealias VcsUserData = BitbucketUserData

/**
 * Neutral VCS-host seam layered ABOVE the vendor-specific [BitbucketService] (Phase 0b-2 of the
 * plugin split). Captures the host-agnostic branch / PR / review / file operations so a future
 * GitHub / GitLab connector can implement [VcsHostClient] without inheriting Bitbucket vocabulary.
 *
 * SHAPE RESERVATION ONLY in 0b-2: the sole implementation is [BitbucketServiceImpl] (which also
 * implements [BitbucketService]); no consumer resolves [VcsHostClient] yet and there is no new
 * service/EP registration (sibling to how `NativeProtocol` was shaped before Phase 4).
 * `public` + [InternalApi] = unfrozen-by-policy.
 *
 * Scope notes:
 *  - `getLinkedJiraIssues` / `getRequiredBuilds` are intentionally NOT here — they are vendor-coupled
 *    (Bitbucket↔Jira link plugin; required-builds conditions keyed by Bamboo plan keys) and remain
 *    on [BitbucketService].
 *  - Default-branch resolution is not yet on this seam: it lives on the lower `BitbucketBranchClient`
 *    and `DefaultBranchResolver`; threading it through a neutral op is a Phase-1 (de-convention) task.
 *  - PR-state vocabulary: a GitHub/GitLab adapter maps its `closed` to Bitbucket's `DECLINED`.
 *  - [getBuildStatuses] / [getCommitBuildStats] read the VCS HOST's commit build-status store
 *    (e.g. Bitbucket's `/rest/build-status/...` endpoints), NOT the CI server — they answer "what
 *    build results has the VCS host recorded against this commit." This is deliberately distinct from
 *    [CiService]'s build queries (which ask the CI server directly); an adapter MAY back both with the
 *    same system, but the two seams own different questions. Do not relocate these to [CiService].
 *  - NO DEFAULT VALUES anywhere below: every parameter is required (no ` = ...`). `BitbucketService`
 *    already declares the defaults; declaring them here too triggers Kotlin
 *    MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES on `BitbucketServiceImpl`. `VcsHostClient` has no
 *    consumers, so required params here are behavior-neutral. (The signatures shown below omit defaults.)
 */
@InternalApi
interface VcsHostClient {
    suspend fun listRepos(): ToolResult<List<RepoInfo>>

    suspend fun createPullRequest(
        title: String,
        description: String,
        fromBranch: String,
        toBranch: String,
        repoName: String?,
    ): ToolResult<PullRequestData>

    suspend fun getPullRequestCommits(prId: Int, repoName: String?): ToolResult<List<CommitData>>

    suspend fun addInlineComment(
        prId: Int,
        filePath: String,
        line: Int,
        lineType: String,
        text: String,
        repoName: String?,
        diffType: String?,
        fromHash: String?,
        toHash: String?,
    ): ToolResult<Unit>

    suspend fun replyToComment(prId: Int, parentCommentId: Int, text: String, repoName: String?): ToolResult<Unit>

    suspend fun setReviewerStatus(prId: Int, username: String, status: String, repoName: String?): ToolResult<Unit>

    suspend fun getFileContent(filePath: String, atRef: String, repoName: String?): ToolResult<String>

    suspend fun addReviewer(prId: Int, username: String, repoName: String?): ToolResult<Unit>

    suspend fun updatePrTitle(prId: Int, newTitle: String, repoName: String?): ToolResult<Unit>

    suspend fun testConnection(): ToolResult<Unit>

    suspend fun getBranches(filter: String?, repoName: String?): ToolResult<List<BranchData>>

    suspend fun createBranch(name: String, startPoint: String, repoName: String?): ToolResult<BranchData>

    suspend fun searchUsers(filter: String, repoName: String?): ToolResult<List<VcsUserData>>

    suspend fun getPullRequestsForBranch(branchName: String, repoName: String?): ToolResult<List<PullRequestData>>

    suspend fun getMyPullRequests(state: String, repoName: String?): ToolResult<List<PullRequestData>>

    suspend fun getReviewingPullRequests(state: String, repoName: String?): ToolResult<List<PullRequestData>>

    suspend fun getPullRequestDetail(prId: Int, repoName: String?): ToolResult<PullRequestDetailData>

    suspend fun getPullRequestActivities(prId: Int, repoName: String?): ToolResult<List<PrActivityData>>

    suspend fun getPullRequestChanges(prId: Int, repoName: String?): ToolResult<List<PrChangeData>>

    suspend fun getPullRequestDiff(prId: Int, repoName: String?): ToolResult<String>

    suspend fun getBuildStatuses(commitId: String, repoName: String?): ToolResult<List<BuildStatusData>>

    suspend fun approvePullRequest(prId: Int, repoName: String?): ToolResult<Unit>

    suspend fun unapprovePullRequest(prId: Int, repoName: String?): ToolResult<Unit>

    suspend fun mergePullRequest(
        prId: Int,
        strategy: String?,
        deleteSourceBranch: Boolean,
        commitMessage: String?,
        repoName: String?,
    ): ToolResult<Unit>

    suspend fun declinePullRequest(prId: Int, repoName: String?): ToolResult<Unit>

    suspend fun updatePrDescription(prId: Int, description: String, repoName: String?): ToolResult<Unit>

    suspend fun addPrComment(prId: Int, text: String, repoName: String?): ToolResult<Unit>

    suspend fun checkMergeStatus(prId: Int, repoName: String?): ToolResult<MergeStatusData>

    suspend fun removeReviewer(prId: Int, username: String, repoName: String?): ToolResult<Unit>

    suspend fun listPrComments(
        repoOwner: String,
        repoName: String,
        prId: Int,
        onlyOpen: Boolean,
        onlyInline: Boolean,
    ): ToolResult<List<PrComment>>

    suspend fun getPrComment(
        repoOwner: String,
        repoName: String,
        prId: Int,
        commentId: Long,
    ): ToolResult<PrComment>

    suspend fun editPrComment(
        repoOwner: String,
        repoName: String,
        prId: Int,
        commentId: Long,
        text: String,
        expectedVersion: Int,
    ): ToolResult<PrComment>

    suspend fun deletePrComment(
        repoOwner: String,
        repoName: String,
        prId: Int,
        commentId: Long,
        expectedVersion: Int,
    ): ToolResult<Unit>

    suspend fun resolvePrComment(
        repoOwner: String,
        repoName: String,
        prId: Int,
        commentId: Long,
    ): ToolResult<PrComment>

    suspend fun reopenPrComment(
        repoOwner: String,
        repoName: String,
        prId: Int,
        commentId: Long,
    ): ToolResult<PrComment>

    suspend fun getBlockerCommentsCount(prId: Int, repoName: String?): ToolResult<Int>

    suspend fun getPullRequestParticipants(prId: Int, repoName: String?): ToolResult<List<ParticipantData>>

    suspend fun getPullRequestsForCommit(sha: String, repoName: String?): ToolResult<List<PullRequestData>>

    suspend fun getCommitBuildStats(sha: String): ToolResult<BuildStatsData>
}
```

> If any imported DTO name above is wrong, the fix is to match the exact name in `core/.../model/bitbucket/BitbucketModels.kt` / `PrState.kt` / `core/.../model/PrComment.kt`. The signatures must be byte-identical to `BitbucketService`'s (modulo the 3 transcription edits) or `BitbucketServiceImpl` will not compile against both interfaces — which is exactly the validation we want.

- [ ] **Step 4: Implement — add the supertype to `BitbucketServiceImpl`**

Edit `pullrequest/.../service/BitbucketServiceImpl.kt:33`:

```kotlin
class BitbucketServiceImpl(private val project: Project) : BitbucketService, VcsHostClient {
```

Add the import:

```kotlin
import com.workflow.orchestrator.core.services.VcsHostClient
```

No method bodies change — every `VcsHostClient` method is already implemented via the existing `BitbucketService` overrides (identical JVM signatures).

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.ai.protocol.SeamApiStabilityTest"`
Run: `./gradlew :konsist:test --tests "com.workflow.orchestrator.konsist.PublicApiSurfaceTest"`
Run: `./gradlew :pullrequest:test --tests "com.workflow.orchestrator.pullrequest.service.BitbucketServiceImplVcsHostClientTest"`
Expected: all PASS. (A compile failure in `:pullrequest` here means a `VcsHostClient` signature diverged from `BitbucketService` — fix the interface, not the impl.)

- [ ] **Step 6: Regression — run the full `:pullrequest` suite**

Run: `./gradlew :pullrequest:test`
Expected: PASS (behavior-unchanged).

- [ ] **Step 7: Update `pullrequest/CLAUDE.md`** (module-doc rule: architecture change in same commit)

In `pullrequest/CLAUDE.md`, add a one-line note (Architecture/service section) that `BitbucketServiceImpl` now ALSO implements the neutral `VcsHostClient` seam (`:core`, Phase 0b-2) — every method binds for free via identical JVM signatures (only the `VcsUserData` typealias + neutral comment-param names differ); behavior-unchanged, no consumer yet. (If `pullrequest/CLAUDE.md` does not exist, create it with this single note under a `## Service architecture` heading.)

- [ ] **Step 8: detekt + commit**

```bash
./gradlew :core:detekt --auto-correct && ./gradlew :core:detekt
./gradlew :pullrequest:detekt --auto-correct && ./gradlew :pullrequest:detekt
git add core/src/main/kotlin/com/workflow/orchestrator/core/services/VcsHostClient.kt \
        pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt \
        core/src/test/kotlin/com/workflow/orchestrator/core/ai/protocol/SeamApiStabilityTest.kt \
        konsist/src/test/kotlin/com/workflow/orchestrator/konsist/PublicApiSurfaceTest.kt \
        pullrequest/src/test/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImplVcsHostClientTest.kt \
        pullrequest/CLAUDE.md
git commit -m "feat(core): VcsHostClient neutral seam + BitbucketServiceImpl implements it (0b-2, behavior-unchanged)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Documentation

**Why:** Architecture changes update module docs + spec in the same branch (project rule). No code.

**Files:**
- Modify: `core/CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-06-22-plugin-split-design.md`

- [ ] **Step 1: Update `core/CLAUDE.md`** — under the "Services" or a new "Connector seams (plugin split)" subsection, add:

```markdown
## Connector seams (plugin split, Phase 0b-2)

Neutral, vendor-agnostic seams layered ABOVE the concrete vendor services so a future
GitHub/GitLab/Jenkins connector is additive. Both are `public` + `@InternalApi` (unfrozen),
shape-reservation only (no consumer/registration yet — like `NativeProtocol` pre-Phase-4):

- `core/services/VcsHostClient.kt` — neutral VCS-host ops (branch/PR/review/file). Implemented by
  `BitbucketServiceImpl` (`:pullrequest`) alongside `BitbucketService` — identical JVM signatures, so
  zero extra methods. Excludes `getLinkedJiraIssues`/`getRequiredBuilds` (vendor-coupled).
  `typealias VcsUserData = BitbucketUserData`; comment ops use neutral `repoOwner`/`repoName` params.
- `core/services/CiService.kt` — neutral CI ops (build/trigger/log/test/pipeline list). Implemented by
  `BambooServiceImpl` (`:bamboo`) alongside `BambooService` via 7 thin delegating methods.
  Neutral DTOs `PipelineData`/`CiGroupData` (`core/model/CiModels.kt`) replace `PlanData`/`ProjectData`;
  opaque `pipelineId`/`buildId` strings. Bamboo-specific ops (plan branches/variables/stage trigger/
  auto-detect) stay on `BambooService`. `ToolResult.mapData` (`core/services/ToolResultMapping.kt`)
  is the envelope-preserving remap helper used by the delegating methods.
```

- [ ] **Step 2: Update the spec** — in `docs/superpowers/specs/2026-06-22-plugin-split-design.md`, append a dated note to §8.3/§8.4 (or the change-log area) recording the resolved 0b-2 design: *genuinely-neutral dual-implementation* (independent neutral interfaces + neutral DTOs; impls implement both; behavior-unchanged; no registration/consumer yet — deferred to the first neutral consumer), and the documented exclusions (`getDefaultBranch`/default-reviewers deferred to Phase 1; `getLinkedJiraIssues`/`getRequiredBuilds` stay vendor-only; `BuildResultData`-family package relocation deferred).

- [ ] **Step 3: Commit**

```bash
git add core/CLAUDE.md docs/superpowers/specs/2026-06-22-plugin-split-design.md \
        docs/superpowers/plans/2026-06-23-plugin-split-phase0b-2-vcs-ci-seam.md
git commit -m "docs(plugin-split): document 0b-2 VcsHostClient/CiService seams + plan

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Full green gate (controller-run integration checkpoint)

**Why:** Per-module focused tests can pass while an emergent aggregate property fails (the Phase-0a green gate caught a `:core` multi-fixture Indexing-timeout that every focused test passed). The controller runs the authoritative build itself.

- [ ] **Step 1: Full module test suites + verifyPlugin**

Run:
```bash
./gradlew :core:test :bamboo:test :pullrequest:test :konsist:test --rerun-tasks
./gradlew verifyPlugin
```
Expected: all GREEN. Specifically confirm **no `:core` "Indexing timeout"** (proves the new `:core` tests are pure JUnit5, no added fixture). If a `NoSuchMethodError` appears, re-run the offending module with `--no-build-cache --rerun-tasks`.

- [ ] **Step 2: detekt across touched modules**

Run: `./gradlew :core:detekt :bamboo:detekt :pullrequest:detekt :konsist:detekt`
Expected: GREEN (autocorrected in earlier tasks; do NOT baseline).

- [ ] **Step 3: Confirm no unintended diff**

Run: `git status` and `git diff --stat origin/feature/plugin-split..HEAD`
Expected: only the files listed in "File Structure". If `agent/src/main/resources/webview/dist/` appears, `git checkout --` it. Confirm `plugin.xml` is **unchanged** (no registration added — behavior-unchanged invariant).

---

## Self-Review (run by the plan author after writing — checklist, not a dispatch)

**1. Spec coverage**
- §8.3 `VcsHostClient` (neutral branch/PR/reviewers ops) → Task 5. ✓ (default-branch op explicitly deferred to Phase 1 with documented rationale — flag for plan review.)
- §8.4 `CiService` (neutral CI vocabulary above `BambooService`) → Tasks 1–4. ✓
- §8 "expose `public` interfaces marked `@InternalApi`, contract-test" → Tasks 3, 5 (SeamApiStabilityTest + PublicApiSurfaceTest). ✓
- §8 "declare an EP in A, ship a generic default" → **partially deferred**: no EP/registration in 0b-2 (interface-only shape, matching `NativeProtocol`); resolution arrives with the first neutral consumer. Flagged for plan review (is interface-only acceptable, or must an EP land now?).
- Behavior-unchanged + no `plugin.xml`/consumer change → enforced in Global Constraints + Task 7 Step 3. ✓

**2. Placeholder scan** — every code step contains complete, compilable code; every command has expected output. No "TBD"/"similar to"/"add error handling". ✓

**3. Type consistency**
- `mapData` signature identical in Task 1 (def) and Task 4 (use). ✓
- `PipelineData`/`CiGroupData` field names identical across Task 2 (def), Task 3 (return types), Task 4 (assertions). ✓
- `toPipelineData`/`toCiGroupData` names identical Task 2 ↔ Task 4. ✓
- `CiService` method names/arities identical Task 3 (def) ↔ Task 4 (overrides + delegation tests). ✓
- `VcsHostClient`/`VcsUserData` identical Task 5 (def) ↔ test. ✓
- Recursion-trap sites (`triggerBuild`/`getRunningBuilds`) handled with explicit disambiguating args in both impl (Step 3) and test (`coVerify` of the 3-arg/2-arg form). ✓

**Open items pressure-tested by the multi-round plan review (3 independent opus rounds: bytecode-accuracy + completeness + skeptic — all verified against actual source):**
1. **Interface-only vs EP-now.** RESOLVED → interface-only. Confirmed 0b-1's `LlmProvider`/`ToolProtocol`/`NativeProtocol` have **no** EP/registration in `plugin.xml` either — interface-only is the established phase pattern; registration lands with the first neutral consumer. Not a spec violation.
2. **VcsHostClient surface size (39 methods).** RESOLVED → keep the full neutral surface. Trimming would just defer missing ops to a later interface (worse for B's lockstep); every method is a genuine VCS-host operation.
3. **`getDefaultBranch` exclusion.** RESOLVED → defer to Phase 1. `getDefaultBranch` is not on `BitbucketService` (lives on `BitbucketBranchClient`); adding it = new impl logic, not behavior-unchanged shape-reservation. Phase 1 (default-branch de-convention) is the right home, where it gets a real consumer.
4. **DTO package residual.** RESOLVED → accept as documented residual. Neutralization criterion is the class *name* (PlanData/ProjectData leak "plan/project"); `BuildResultData`/`TestResultsData`/etc. are already neutral-named. Relocating the *package* is not behavior-unchanged-safe (touches every consumer). Deferred.
5. **Dual-impl vs supertype-extraction.** RESOLVED → dual-impl (deliberately diverges from the exploration map's "Option A"). Supertype-extraction would change the concrete interfaces' public supertype set (visible to all consumers + konsist boundary tests); dual-impl keeps them byte-identical. Safer.

**Review fixes folded in (doc-only, applied 2026-06-23):**
- [IMPORTANT] `bamboo/CLAUDE.md` (Task 4 Step 6) + `pullrequest/CLAUDE.md` (Task 5 Step 7) updated in the same commit as the impl change.
- [IMPORTANT] `VcsHostClient` KDoc disambiguates `getBuildStatuses`/`getCommitBuildStats` (VCS-host commit-status store) from `CiService` build queries.
- [MINOR] `BambooServiceImpl` maintainer note on re-verifying the 11 "free" delegations if a future BambooService overload reuses a name; `spyk` test-harness caveat noted in Task 4.

**Execution ledger:** `.superpowers/sdd/progress.md` currently holds the *previous* feature's ledger — reset/replace it for 0b-2 at the start of subagent-driven execution (the recovery map across compaction).
