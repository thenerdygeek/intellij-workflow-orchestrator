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

        val ranked = service.scoreAndRankRuns("PROJ-AUTO")

        assertTrue(ranked.isNotEmpty())
        assertEquals(847, ranked[0].buildNumber)
        assertTrue(ranked[0].score > ranked[1].score)
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

        assertTrue(vars.containsKey("dockerTagsAsJson"))
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

    private fun makeBuildResultData(
        buildNumber: Int,
        state: String,
        stageStates: List<String>
    ): BuildResultData {
        val stages = stageStates.mapIndexed { i, s ->
            BuildStageData(
                name = "Stage-$i",
                state = s,
                durationSeconds = 300
            )
        }
        return BuildResultData(
            planKey = "PROJ-AUTO",
            buildNumber = buildNumber,
            state = state,
            durationSeconds = 700,
            buildResultKey = "PROJ-AUTO-$buildNumber",
            buildRelativeTime = "5 min ago",
            stages = stages
        )
    }
}
