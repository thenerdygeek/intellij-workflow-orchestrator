package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.model.*
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildStageData
import com.workflow.orchestrator.core.model.bamboo.PlanVariableData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
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
            makeBuildResultData(847, "Successful", listOf("Successful", "Successful", "Successful"),
                variables = mapOf("dockerTagsAsJson" to """{"auth":"2.4.0","payments":"2.3.1","user":"1.9.0"}""")),
            makeBuildResultData(848, "Failed", listOf("Successful", "Failed"),
                variables = mapOf("dockerTagsAsJson" to """{"auth":"2.4.0","payments":"feature-abc"}""")),
            makeBuildResultData(846, "Successful", listOf("Successful", "Successful"),
                variables = mapOf("dockerTagsAsJson" to """{"auth":"2.3.0","payments":"2.3.1"}"""))
        )
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10) } returns ToolResult.success(
            data = runs,
            summary = "Found 3 recent builds"
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
            makeBuildResultData(847, "Successful", listOf("Successful", "Successful"),
                variables = mapOf("dockerTagsAsJson" to """{"auth":"2.4.0","payments":"2.3.1"}"""))
        )
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10) } returns ToolResult.success(
            data = runs,
            summary = "Found 1 build"
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
    fun `loadBaseline handles empty results gracefully`() = runTest {
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10) } returns ToolResult.success(
            data = emptyList(),
            summary = "No builds found"
        )

        val entries = service.loadBaseline("PROJ-AUTO")

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `loadBaseline handles API error gracefully`() = runTest {
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10) } returns ToolResult(
            data = emptyList(),
            summary = "timeout",
            isError = true
        )

        val entries = service.loadBaseline("PROJ-AUTO")

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `loadBaselineWithDiagnostics reports API error`() = runTest {
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10) } returns ToolResult(
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
        val runs = listOf(
            // Build has variables but NOT dockerTagsAsJson
            makeBuildResultData(100, "Successful", listOf("Successful"),
                variables = mapOf("someOtherVar" to "value"))
        )
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10) } returns ToolResult.success(
            data = runs,
            summary = "1 build"
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
        val runs = listOf(
            makeBuildResultData(847, "Successful", listOf("Successful", "Successful"),
                variables = mapOf("dockerTagsAsJson" to """{"auth":"2.4.0","payments":"2.3.1"}"""))
        )
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10) } returns ToolResult.success(
            data = runs,
            summary = "1 build"
        )

        val result = service.loadBaselineWithDiagnostics("PROJ-AUTO")

        assertEquals(2, result.tags.size)
        assertNotNull(result.selectedBuild)
        assertEquals(847, result.selectedBuild!!.buildNumber)
        assertEquals(2, result.selectedBuild!!.releaseTagCount)
        assertTrue(result.diagnostics.toStatusText().isEmpty()) // empty = success
    }

    // ---- Phase B: chain-key-based detectDockerTag ----

    @Test
    fun `detectDockerTag(chainKey) returns success when log contains Unique Docker Tag`() = runTest {
        // Cache miss → REST path
        coEvery { bambooService.getLatestBuild("CI-PLAN523") } returns ToolResult.success(
            data = makeBuildResultData(42, "Successful", emptyList(), "CI-PLAN523"),
            summary = "build found"
        )
        coEvery { bambooService.getBuildLog("CI-PLAN523-42") } returns ToolResult.success(
            data = "Building...\nUnique Docker Tag : feature-test-abc123\nDone.",
            summary = "log fetched"
        )

        val result = service.detectDockerTag("CI-PLAN523")

        assertTrue(result.detected)
        assertEquals("feature-test-abc123", result.tag)
        assertEquals("CI-PLAN523-42", result.buildKey)
    }

    @Test
    fun `detectDockerTag(chainKey) returns noTagInLog when pattern not found`() = runTest {
        coEvery { bambooService.getLatestBuild("CI-PLAN523") } returns ToolResult.success(
            data = makeBuildResultData(99, "Successful", emptyList(), "CI-PLAN523"),
            summary = "build found"
        )
        coEvery { bambooService.getBuildLog("CI-PLAN523-99") } returns ToolResult.success(
            data = "Building...\nTests passed.\nDone.",
            summary = "log fetched"
        )

        val result = service.detectDockerTag("CI-PLAN523")

        assertFalse(result.detected)
        assertNull(result.tag)
        assertTrue(result.reason.contains("CI-PLAN523-99"))
    }

    @Test
    fun `detectDockerTag(chainKey) returns logFetchFailed when getBuildLog errors`() = runTest {
        coEvery { bambooService.getLatestBuild("CI-PLAN523") } returns ToolResult.success(
            data = makeBuildResultData(77, "Successful", emptyList(), "CI-PLAN523"),
            summary = "build found"
        )
        coEvery { bambooService.getBuildLog("CI-PLAN523-77") } returns ToolResult(
            data = "",
            summary = "timeout fetching log",
            isError = true
        )

        val result = service.detectDockerTag("CI-PLAN523")

        assertFalse(result.detected)
        assertNull(result.tag)
        // reason should reference the result key
        assertTrue(result.reason.contains("CI-PLAN523-77"))
    }

    @Test
    fun `detectDockerTag(chainKey) returns noBuild when getLatestBuild errors`() = runTest {
        coEvery { bambooService.getLatestBuild("CI-PLAN523") } returns ToolResult(
            data = BuildResultData(planKey = "CI-PLAN523", buildNumber = 0, state = "ERROR", durationSeconds = 0),
            summary = "no build found",
            isError = true
        )

        val result = service.detectDockerTag("CI-PLAN523")

        assertFalse(result.detected)
        assertNull(result.tag)
    }

    @Test
    fun `detectDockerTag(chainKey) cache hit short-circuits API call`() = runTest {
        // Pre-populate the cache with a SUCCESS event
        val cache = com.workflow.orchestrator.core.services.BuildLogCache()
        val cachedEvent = com.workflow.orchestrator.core.events.WorkflowEvent.BuildLogReady(
            planKey = "CI-PLAN523",
            buildNumber = 55,
            resultKey = "CI-PLAN523-55",
            status = com.workflow.orchestrator.core.events.WorkflowEvent.BuildEventStatus.SUCCESS,
            logText = "Unique Docker Tag : cached-feature-tag\nDone.",
            chainKey = "CI-PLAN523",
        )
        cache.put(cachedEvent)

        // Service with cache injected — bambooService is NOT called
        val serviceWithCache = TagBuilderService(bambooService, buildLogCache = cache)

        val result = serviceWithCache.detectDockerTag("CI-PLAN523")

        assertTrue(result.detected)
        assertEquals("cached-feature-tag", result.tag)
        assertEquals("CI-PLAN523-55", result.buildKey)
        // Verify bambooService was never called (mockk will throw if unstubbed calls are made)
    }

    @Test
    fun `detectDockerTag(chainKey) cache hit with FAILED build returns buildFailed`() = runTest {
        val cache = com.workflow.orchestrator.core.services.BuildLogCache()
        val failedEvent = com.workflow.orchestrator.core.events.WorkflowEvent.BuildLogReady(
            planKey = "CI-PLAN523",
            buildNumber = 60,
            resultKey = "CI-PLAN523-60",
            status = com.workflow.orchestrator.core.events.WorkflowEvent.BuildEventStatus.FAILED,
            logText = "",
            chainKey = "CI-PLAN523",
        )
        cache.put(failedEvent)

        val serviceWithCache = TagBuilderService(bambooService, buildLogCache = cache)

        val result = serviceWithCache.detectDockerTag("CI-PLAN523")

        assertFalse(result.detected)
        assertNull(result.tag)
        assertTrue(result.reason.startsWith("CI build failed"))
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
            makeBuildResultData(847, "Successful", listOf("Successful", "Successful"),
                variables = mapOf("dockerTagsAsJson" to """{"auth":"2.4.0","payments":"2.3.1"}""")),
            makeBuildResultData(848, "Successful", listOf("Successful", "Successful"),
                variables = mapOf("dockerTagsAsJson" to """{"auth":"2.4.0-rc1","payments":"2.3.1-SNAPSHOT"}"""))
        )
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10) } returns ToolResult.success(
            data = builds,
            summary = "2 builds"
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
        val builds = listOf(
            makeBuildResultData(900, "Successful", listOf("Successful"),
                variables = mapOf("dockerTagsAsJson" to """{"auth":"1.2.3.4"}"""))
        )
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10) } returns ToolResult.success(
            data = builds,
            summary = "1 build"
        )

        val (ranked, _) = service.scoreAndRankRuns("PROJ-AUTO")

        assertEquals(1, ranked.single().releaseTagCount)
    }

    // PR 7 #6 — pagination tests

    @Test
    fun `targetParseable honored when first 10 builds yield few parseable`() = runTest {
        // 12 builds total; only every other build has dockerTagsAsJson, so the
        // walker must pull the full 12 to accumulate 6 parseable results.
        val builds = (1..12).map { n ->
            val vars = if (n % 2 == 0) {
                mapOf("dockerTagsAsJson" to """{"svc":"1.0.$n"}""")
            } else {
                mapOf("noise" to "x")
            }
            makeBuildResultData(n, "Successful", listOf("Successful"), variables = vars)
        }
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 12) } returns ToolResult.success(
            data = builds,
            summary = "12 builds"
        )

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
        // means we should stop after walking 3 builds. Builds 4-10 have empty
        // variables so they would be skipped — the early-exit fires first.
        val builds = (1..10).map { n ->
            val vars = if (n <= 3) mapOf("dockerTagsAsJson" to """{"svc":"1.0.$n"}""") else emptyMap()
            makeBuildResultData(n, "Successful", listOf("Successful"), variables = vars)
        }
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10) } returns ToolResult.success(
            data = builds,
            summary = "10 builds"
        )

        val (ranked, _) = service.scoreAndRankRuns(
            "PROJ-AUTO",
            targetParseable = 3,
            maxWalk = 10
        )

        assertEquals(3, ranked.size)
    }

    // PR 7 #8 — baseline picker exposes ranked alternatives

    @Test
    fun `loadBaselineWithDiagnostics surfaces all ranked builds for picker`() = runTest {
        val runs = listOf(
            makeBuildResultData(847, "Successful", listOf("Successful", "Successful"),
                variables = mapOf("dockerTagsAsJson" to """{"a":"2.0.0","b":"3.0.0"}""")),
            makeBuildResultData(848, "Successful", listOf("Successful", "Successful"),
                variables = mapOf("dockerTagsAsJson" to """{"a":"2.0.0"}""")),
            makeBuildResultData(846, "Successful", listOf("Successful", "Successful"),
                variables = mapOf("dockerTagsAsJson" to """{"a":"1.5.0","b":"2.5.0"}"""))
        )
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10) } returns ToolResult.success(
            data = runs,
            summary = "3 builds"
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

    @Test
    fun `scoreAndRankRuns does not call getBuildVariables when variables are inline`() = runTest {
        coEvery { bambooService.getRecentBuilds("PROJ-AUTO", 10) } returns ToolResult.success(
            data = listOf(
                BuildResultData(
                    planKey = "PROJ-AUTO",
                    buildNumber = 200,
                    state = "Successful",
                    durationSeconds = 120,
                    buildResultKey = "PROJ-AUTO-200",
                    lifeCycleState = "Finished",
                    variables = mapOf("dockerTagsAsJson" to """{"svc-a":"1.0.0"}""")
                )
            ),
            summary = "ok"
        )

        val (ranked, _) = service.scoreAndRankRuns("PROJ-AUTO")

        assertEquals(1, ranked.size)
        assertEquals(mapOf("svc-a" to "1.0.0"), ranked[0].dockerTags)
        // The key contract: no per-build re-fetch.
        coVerify(exactly = 0) { bambooService.getBuildVariables(any()) }
    }

    private fun makeBuildResultData(
        buildNumber: Int,
        state: String,
        stageStates: List<String>,
        planKey: String = "PROJ-AUTO",
        variables: Map<String, String> = emptyMap()
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
            stages = stages,
            variables = variables
        )
    }
}
