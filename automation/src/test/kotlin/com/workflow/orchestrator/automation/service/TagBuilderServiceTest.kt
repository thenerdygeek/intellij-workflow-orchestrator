package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.model.*
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildStageData
import com.workflow.orchestrator.core.model.bamboo.PlanVariableData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class TagBuilderServiceTest {

    private lateinit var bambooService: BambooService
    private lateinit var service: TagBuilderService

    @BeforeEach
    fun setUp() {
        bambooService = mockk()
        service = TagBuilderService(bambooService)
    }

    @Test
    fun `scoreAndRankRuns scores runs by release tags and stage results`() = runTest {
        val runs = listOf(
            makeBuildResultData(847, "Successful", listOf("Successful", "Successful", "Successful")),
            makeBuildResultData(848, "Failed", listOf("Successful", "Failed")),
            makeBuildResultData(846, "Successful", listOf("Successful", "Successful"))
        )
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10, null, null) } returns ToolResult.success(
            data = runs,
            summary = "Found 3 recent builds"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-847") } returns ToolResult.success(
            data = listOf(
                PlanVariableData("dockerTagsAsJson", """{"auth":"2.4.0","payments":"2.3.1","user":"1.9.0"}""")
            ),
            summary = "1 variable"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-848") } returns ToolResult.success(
            data = listOf(
                PlanVariableData("dockerTagsAsJson", """{"auth":"2.4.0","payments":"feature-abc"}""")
            ),
            summary = "1 variable"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-846") } returns ToolResult.success(
            data = listOf(
                PlanVariableData("dockerTagsAsJson", """{"auth":"2.3.0","payments":"2.3.1"}""")
            ),
            summary = "1 variable"
        )

        val (ranked, diagnostics) = service.scoreAndRankRuns("PROJ-AUTO")

        assertTrue(ranked.isNotEmpty())
        assertEquals(847, ranked[0].buildNumber)
        assertTrue(ranked[0].score > ranked[1].score)

        // Diagnostics track what happened
        assertEquals(3, diagnostics.buildsQueried)
        assertEquals(3, diagnostics.buildsWithVariables)
        assertEquals(3, diagnostics.buildsWithDockerTags)
        assertNull(diagnostics.bambooError)
    }

    @Test
    fun `loadBaseline returns tag entries from best-scored run`() = runTest {
        val runs = listOf(
            makeBuildResultData(847, "Successful", listOf("Successful", "Successful"))
        )
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10, null, null) } returns ToolResult.success(
            data = runs,
            summary = "Found 1 build"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-847") } returns ToolResult.success(
            data = listOf(
                PlanVariableData("dockerTagsAsJson", """{"auth":"2.4.0","payments":"2.3.1"}""")
            ),
            summary = "1 variable"
        )

        val entries = service.loadBaseline("PROJ-AUTO")

        assertEquals(2, entries.size)
        assertTrue(entries.any { it.serviceName == "auth" && it.currentTag == "2.4.0" })
        assertTrue(entries.all { it.source == TagSource.BASELINE })
        assertTrue(entries.all { it.registryStatus == RegistryStatus.UNKNOWN })
    }

    @Test
    fun `replaceCurrentRepoTag swaps tag for matching service`() {
        val entries = listOf(
            TagEntry("auth", "2.4.0", null, TagSource.BASELINE, RegistryStatus.UNKNOWN, false, false),
            TagEntry("payments", "2.3.1", null, TagSource.BASELINE, RegistryStatus.UNKNOWN, false, false)
        )
        val context = CurrentRepoContext(
            serviceName = "auth",
            branchName = "feature/PROJ-123",
            featureBranchTag = "feature-PROJ-123-a1b2c3d",
            detectedFrom = DetectionSource.PROJECT_NAME
        )

        val result = service.replaceCurrentRepoTag(entries, context)

        val authEntry = result.find { it.serviceName == "auth" }!!
        assertEquals("feature-PROJ-123-a1b2c3d", authEntry.currentTag)
        assertEquals(TagSource.AUTO_DETECTED, authEntry.source)
        assertTrue(authEntry.isCurrentRepo)

        val paymentsEntry = result.find { it.serviceName == "payments" }!!
        assertFalse(paymentsEntry.isCurrentRepo)
    }

    @Test
    fun `replaceCurrentRepoTag does nothing when service not found`() {
        val entries = listOf(
            TagEntry("auth", "2.4.0", null, TagSource.BASELINE, RegistryStatus.UNKNOWN, false, false)
        )
        val context = CurrentRepoContext("unknown-service", "main", "tag", DetectionSource.PROJECT_NAME)

        val result = service.replaceCurrentRepoTag(entries, context)

        assertFalse(result.any { it.isCurrentRepo })
    }

    @Test
    fun `buildJsonPayload produces valid JSON`() {
        val entries = listOf(
            TagEntry("auth", "2.4.0", null, TagSource.BASELINE, RegistryStatus.VALID, false, false),
            TagEntry("payments", "2.3.1", null, TagSource.BASELINE, RegistryStatus.VALID, false, false)
        )

        val payload = service.buildJsonPayload(entries)

        assertTrue(payload.contains("\"auth\""))
        assertTrue(payload.contains("\"2.4.0\""))
        assertTrue(payload.contains("\"payments\""))
        assertTrue(payload.contains("\"2.3.1\""))
    }

    @Test
    fun `buildTriggerVariables combines tags and extra vars`() {
        val entries = listOf(
            TagEntry("auth", "2.4.0", null, TagSource.BASELINE, RegistryStatus.VALID, false, false)
        )
        val extraVars = mapOf("suiteType" to "regression", "featureFlag" to "true")

        val vars = service.buildTriggerVariables(entries, extraVars)

        // A-P0-3: canonical variable name is "DockerTagsAsJSON" (probe-confirmed)
        assertTrue(vars.containsKey("DockerTagsAsJSON"),
            "Expected trigger variables to contain key 'DockerTagsAsJSON' but got: ${vars.keys}")
        assertEquals("regression", vars["suiteType"])
        assertEquals("true", vars["featureFlag"])
    }

    @Test
    fun `loadBaseline handles empty results gracefully`() = runTest {
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10, null, null) } returns ToolResult.success(
            data = emptyList(),
            summary = "No builds found"
        )

        val entries = service.loadBaseline("PROJ-AUTO")

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `loadBaseline handles API error gracefully`() = runTest {
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10, null, null) } returns ToolResult(
            data = emptyList(),
            summary = "timeout",
            isError = true
        )

        val entries = service.loadBaseline("PROJ-AUTO")

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `loadBaselineWithDiagnostics reports API error`() = runTest {
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10, null, null) } returns ToolResult(
            data = emptyList(),
            summary = "Bamboo returned 401",
            isError = true
        )

        val result = service.loadBaselineWithDiagnostics("PROJ-AUTO")

        assertTrue(result.tags.isEmpty())
        assertNull(result.selectedBuild)
        assertEquals("Bamboo returned 401", result.diagnostics.bambooError)
        assertEquals("Bamboo error: Bamboo returned 401", result.diagnostics.toStatusText())
    }

    @Test
    fun `loadBaselineWithDiagnostics reports missing variable`() = runTest {
        val runs = listOf(makeBuildResultData(100, "Successful", listOf("Successful")))
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10, null, null) } returns ToolResult.success(
            data = runs,
            summary = "1 build"
        )
        // Build has variables but NOT dockerTagsAsJson
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-100") } returns ToolResult.success(
            data = listOf(PlanVariableData("someOtherVar", "value")),
            summary = "1 variable"
        )

        val result = service.loadBaselineWithDiagnostics("PROJ-AUTO")

        assertTrue(result.tags.isEmpty())
        assertNull(result.selectedBuild)
        assertEquals(1, result.diagnostics.buildsQueried)
        assertEquals(1, result.diagnostics.buildsWithVariables)
        assertEquals(0, result.diagnostics.buildsWithDockerTags)
        assertTrue(result.diagnostics.toStatusText().contains("none had dockerTagsAsJson"))
    }

    @Test
    fun `loadBaselineWithDiagnostics reports success with build details`() = runTest {
        val runs = listOf(makeBuildResultData(847, "Successful", listOf("Successful", "Successful")))
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10, null, null) } returns ToolResult.success(
            data = runs,
            summary = "1 build"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-847") } returns ToolResult.success(
            data = listOf(PlanVariableData("dockerTagsAsJson", """{"auth":"2.4.0","payments":"2.3.1"}""")),
            summary = "1 variable"
        )

        val result = service.loadBaselineWithDiagnostics("PROJ-AUTO")

        assertEquals(2, result.tags.size)
        assertNotNull(result.selectedBuild)
        assertEquals(847, result.selectedBuild!!.buildNumber)
        assertEquals(2, result.selectedBuild!!.releaseTagCount)
        assertTrue(result.diagnostics.toStatusText().isEmpty()) // empty = success
    }

    @Test
    fun `detectDockerTag returns success with tag from log`() = runTest {
        coEvery { bambooService.getLatestBuild("CI-PLAN", "feature/test", null) } returns ToolResult.success(
            data = makeBuildResultData(42, "Successful", emptyList(), "CI-PLAN"),
            summary = "build found"
        )
        coEvery { bambooService.getBuildLog("CI-PLAN-42") } returns ToolResult.success(
            data = "Building...\nUnique Docker Tag : feature-test-abc123\nDone.",
            summary = "log fetched"
        )

        val result = service.detectDockerTag("CI-PLAN", "feature/test")

        assertTrue(result.detected)
        assertEquals("feature-test-abc123", result.tag)
        assertEquals("CI-PLAN-42", result.buildKey)
    }

    @Test
    fun `detectDockerTag returns noBuild when no build found`() = runTest {
        coEvery { bambooService.getLatestBuild("CI-PLAN", "feature/gone", null) } returns ToolResult(
            data = BuildResultData(planKey = "CI-PLAN", buildNumber = 0, state = "ERROR", durationSeconds = 0),
            summary = "Branch 'feature/gone' not found",
            isError = true
        )

        val result = service.detectDockerTag("CI-PLAN", "feature/gone")

        assertFalse(result.detected)
        assertNull(result.tag)
        assertTrue(result.reason.contains("feature/gone"))
    }

    @Test
    fun `detectDockerTag returns noTagInLog when pattern not found`() = runTest {
        coEvery { bambooService.getLatestBuild("CI-PLAN", "main", null) } returns ToolResult.success(
            data = makeBuildResultData(99, "Successful", emptyList(), "CI-PLAN"),
            summary = "build found"
        )
        coEvery { bambooService.getBuildLog("CI-PLAN-99") } returns ToolResult.success(
            data = "Building...\nTests passed.\nDone.",
            summary = "log fetched"
        )

        val result = service.detectDockerTag("CI-PLAN", "main")

        assertFalse(result.detected)
        assertNull(result.tag)
        assertTrue(result.reason.contains("CI-PLAN-99"))
    }

    @Test
    fun `extractDockerTagFromLog returns tag from log text`() {
        val log = "Building...\nUnique Docker Tag : feature-test-abc123\nDone."
        val tag = service.extractDockerTagFromLog(log)
        assertEquals("feature-test-abc123", tag)
    }

    @Test
    fun `extractDockerTagFromLog strips ANSI escape codes`() {
        val log = "Unique Docker Tag : \u001B[32mfeature-test-xyz\u001B[0m"
        val tag = service.extractDockerTagFromLog(log)
        assertEquals("feature-test-xyz", tag)
    }

    @Test
    fun `extractDockerTagFromLog returns null when pattern not found`() {
        val log = "Building...\nTests passed.\nDone."
        val tag = service.extractDockerTagFromLog(log)
        assertNull(tag)
    }

    @Test
    fun `extractDockerTagFromLog returns null for empty log`() {
        assertNull(service.extractDockerTagFromLog(""))
    }

    @Test
    fun `A-P2-2 pre-release tags do not count as releases in baseline score`() = runTest {
        // Two builds: build-A has clean releases ("2.4.0", "2.3.1"); build-B has
        // pre-release tags ("2.4.0-rc1", "2.3.1-SNAPSHOT") that the old regex would
        // wrongly match as releases. Tightened regex must score build-A strictly higher.
        val builds = listOf(
            makeBuildResultData(847, "Successful", listOf("Successful", "Successful")),
            makeBuildResultData(848, "Successful", listOf("Successful", "Successful"))
        )
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10, null, null) } returns ToolResult.success(
            data = builds,
            summary = "2 builds"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-847") } returns ToolResult.success(
            data = listOf(PlanVariableData("dockerTagsAsJson", """{"auth":"2.4.0","payments":"2.3.1"}""")),
            summary = "1 var"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-848") } returns ToolResult.success(
            data = listOf(PlanVariableData("dockerTagsAsJson", """{"auth":"2.4.0-rc1","payments":"2.3.1-SNAPSHOT"}""")),
            summary = "1 var"
        )

        val (ranked, _) = service.scoreAndRankRuns("PROJ-AUTO")

        val a = ranked.first { it.buildNumber == 847 }
        val b = ranked.first { it.buildNumber == 848 }
        assertEquals(2, a.releaseTagCount, "Clean semver tags should count as releases")
        assertEquals(0, b.releaseTagCount, "Pre-release tags must not count as releases")
        assertTrue(a.score > b.score, "Build with clean releases should outrank pre-release build")
    }

    @Test
    fun `A-P2-2 four-segment Maven-style version still counts as release`() = runTest {
        // "1.2.3.4" is the Maven-style 4-segment release form — kept eligible.
        val builds = listOf(makeBuildResultData(900, "Successful", listOf("Successful")))
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10, null, null) } returns ToolResult.success(
            data = builds,
            summary = "1 build"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-900") } returns ToolResult.success(
            data = listOf(PlanVariableData("dockerTagsAsJson", """{"auth":"1.2.3.4"}""")),
            summary = "1 var"
        )

        val (ranked, _) = service.scoreAndRankRuns("PROJ-AUTO")

        assertEquals(1, ranked.single().releaseTagCount)
    }

    // PR 7 #6 — pagination tests

    @Test
    fun `targetParseable honored when first 10 builds yield few parseable`() = runTest {
        // 12 builds total; only every other build has dockerTagsAsJson, so the
        // walker must pull the full 12 to accumulate 6 parseable results.
        val builds = (1..12).map { makeBuildResultData(it, "Successful", listOf("Successful")) }
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 12, null, null) } returns ToolResult.success(
            data = builds,
            summary = "12 builds"
        )
        for (b in builds) {
            val resultKey = "PROJ-AUTO-${b.buildNumber}"
            val vars = if (b.buildNumber % 2 == 0) {
                listOf(PlanVariableData("dockerTagsAsJson", """{"svc":"1.0.${b.buildNumber}"}"""))
            } else {
                listOf(PlanVariableData("noise", "x"))
            }
            coEvery { bambooService.getBuildVariables(resultKey) } returns ToolResult.success(
                data = vars,
                summary = "1 var"
            )
        }

        val (ranked, diagnostics) = service.scoreAndRankRuns(
            "PROJ-AUTO",
            targetParseable = 6,
            maxWalk = 12
        )

        // Got the requested 6 parseable builds (even-numbered).
        assertEquals(6, ranked.size)
        assertTrue(ranked.all { it.buildNumber % 2 == 0 })
        // Diagnostics counted everything walked.
        assertEquals(12, diagnostics.buildsQueried)
        assertEquals(12, diagnostics.buildsWithVariables)
        assertEquals(6, diagnostics.buildsWithDockerTags)
    }

    @Test
    fun `pagination early-exits once targetParseable is reached`() = runTest {
        // 10 builds, all parseable. Asking for targetParseable=3, maxWalk=10
        // means we should stop after walking 3 builds and never request
        // variables for #4-#10.
        val builds = (1..10).map { makeBuildResultData(it, "Successful", listOf("Successful")) }
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10, null, null) } returns ToolResult.success(
            data = builds,
            summary = "10 builds"
        )
        // Mock variable fetches for the first 3 only — if the walker keeps
        // going, mockk will throw an unstubbed-call error.
        for (n in 1..3) {
            coEvery { bambooService.getBuildVariables("PROJ-AUTO-$n") } returns ToolResult.success(
                data = listOf(PlanVariableData("dockerTagsAsJson", """{"svc":"1.0.$n"}""")),
                summary = "1 var"
            )
        }

        val (ranked, _) = service.scoreAndRankRuns(
            "PROJ-AUTO",
            targetParseable = 3,
            maxWalk = 10
        )

        assertEquals(3, ranked.size)
    }

    // PR 7 #7 — suite extras merge

    @Test
    fun `buildTriggerVariables merges suiteExtras alongside extraVars`() {
        val entries = listOf(
            TagEntry("auth", "2.4.0", null, TagSource.BASELINE, RegistryStatus.VALID, false, false)
        )
        val extraVars = mapOf("suiteType" to "regression")
        val suiteExtras = mapOf("featureFlag" to "true", "timeoutMs" to "30000")

        val vars = service.buildTriggerVariables(entries, extraVars, suiteExtras)

        // Both kinds of extras flow into the trigger payload.
        assertEquals("regression", vars["suiteType"])
        assertEquals("true", vars["featureFlag"])
        assertEquals("30000", vars["timeoutMs"])
        // Docker payload still present.
        assertTrue(vars.containsKey("DockerTagsAsJSON"))
    }

    @Test
    fun `buildTriggerVariables docker payload wins over suiteExtras with same key`() {
        // User-named extra collides with the canonical docker tag variable —
        // the auto-generated payload must win (PR 7 #7 conflict resolution).
        val entries = listOf(
            TagEntry("auth", "2.4.0", null, TagSource.BASELINE, RegistryStatus.VALID, false, false)
        )
        val suiteExtras = mapOf("DockerTagsAsJSON" to "should-be-ignored")

        val vars = service.buildTriggerVariables(entries, emptyMap(), suiteExtras)

        // The docker payload should reflect the entries, NOT the colliding extra.
        assertTrue(vars["DockerTagsAsJSON"]!!.contains("\"auth\""))
        assertTrue(vars["DockerTagsAsJSON"]!!.contains("\"2.4.0\""))
        assertFalse(vars["DockerTagsAsJSON"] == "should-be-ignored")
    }

    @Test
    fun `buildTriggerVariables suiteExtras override extraVars on key conflict`() {
        // Per the contract docstring, suiteExtras (rule 2) is applied AFTER
        // extraVars (rule 1) but before the docker payload (rule 3). So a
        // shared key resolves to the suiteExtras value.
        val entries = listOf(
            TagEntry("auth", "2.4.0", null, TagSource.BASELINE, RegistryStatus.VALID, false, false)
        )
        val extraVars = mapOf("env" to "staging")
        val suiteExtras = mapOf("env" to "production")

        val vars = service.buildTriggerVariables(entries, extraVars, suiteExtras)

        assertEquals("production", vars["env"])
    }

    // PR 7 #8 — baseline picker exposes ranked alternatives

    @Test
    fun `loadBaselineWithDiagnostics surfaces all ranked builds for picker`() = runTest {
        val runs = listOf(
            makeBuildResultData(847, "Successful", listOf("Successful", "Successful")),
            makeBuildResultData(848, "Successful", listOf("Successful", "Successful")),
            makeBuildResultData(846, "Successful", listOf("Successful", "Successful"))
        )
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10, null, null) } returns ToolResult.success(
            data = runs,
            summary = "3 builds"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-847") } returns ToolResult.success(
            data = listOf(PlanVariableData("dockerTagsAsJson", """{"a":"2.0.0","b":"3.0.0"}""")),
            summary = "1 var"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-848") } returns ToolResult.success(
            data = listOf(PlanVariableData("dockerTagsAsJson", """{"a":"2.0.0"}""")),
            summary = "1 var"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-846") } returns ToolResult.success(
            data = listOf(PlanVariableData("dockerTagsAsJson", """{"a":"1.5.0","b":"2.5.0"}""")),
            summary = "1 var"
        )

        val result = service.loadBaselineWithDiagnostics("PROJ-AUTO")

        // All 3 parseable builds are exposed for the dropdown.
        assertEquals(3, result.allRanked.size)
        // Selected build is the same as the top of the ranking.
        assertNotNull(result.selectedBuild)
        assertEquals(result.allRanked[0].buildNumber, result.selectedBuild!!.buildNumber)
    }

    @Test
    fun `tagsForRun returns TagEntry list for arbitrary picked run`() {
        val run = BaselineRun(
            buildNumber = 999,
            resultKey = "PROJ-AUTO-999",
            dockerTags = mapOf("auth" to "2.4.0", "payments" to "1.0.0"),
            releaseTagCount = 2,
            totalServices = 2,
            successfulStages = 2,
            failedStages = 0,
            triggeredAt = Instant.EPOCH,
            score = 35
        )

        val entries = service.tagsForRun(run)

        assertEquals(2, entries.size)
        val auth = entries.first { it.serviceName == "auth" }
        assertEquals("2.4.0", auth.currentTag)
        assertEquals(TagSource.BASELINE, auth.source)
        assertEquals(RegistryStatus.UNKNOWN, auth.registryStatus)
    }

    private fun makeBuildResultData(
        buildNumber: Int,
        state: String,
        stageStates: List<String>,
        planKey: String = "PROJ-AUTO"
    ): BuildResultData {
        val stages = stageStates.mapIndexed { i, s ->
            BuildStageData(
                name = "Stage-$i",
                state = s,
                durationSeconds = 300
            )
        }
        return BuildResultData(
            planKey = planKey,
            buildNumber = buildNumber,
            state = state,
            durationSeconds = 700,
            buildResultKey = "$planKey-$buildNumber",
            buildRelativeTime = "5 min ago",
            stages = stages
        )
    }
}
