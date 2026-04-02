package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.model.Conflict
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.PlanVariableData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConflictDetectorServiceTest {

    private lateinit var bambooService: BambooService
    private lateinit var service: ConflictDetectorService

    @BeforeEach
    fun setUp() {
        bambooService = mockk()
        service = ConflictDetectorService(bambooService)
    }

    @Test
    fun `checkConflicts detects overlapping services`() = runTest {
        val runningBuild = BuildResultData(
            planKey = "PROJ-AUTO",
            buildNumber = 849,
            state = "InProgress",
            durationSeconds = 0,
            buildResultKey = "PROJ-AUTO-849"
        )
        coEvery { bambooService.getRunningBuilds("PROJ-AUTO", null) } returns ToolResult.success(
            data = listOf(runningBuild),
            summary = "1 running build"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-849") } returns ToolResult.success(
            data = listOf(
                PlanVariableData("dockerTagsAsJson", """{"auth":"2.4.0","payments":"2.3.1"}""")
            ),
            summary = "1 variable"
        )

        val stagedTags = mapOf("auth" to "feature-abc", "user" to "1.9.0")
        val conflicts = service.checkConflicts("PROJ-AUTO", stagedTags)

        assertEquals(1, conflicts.size)
        assertEquals("auth", conflicts[0].serviceName)
        assertEquals("feature-abc", conflicts[0].yourTag)
        assertEquals("2.4.0", conflicts[0].otherTag)
        assertTrue(conflicts[0].isRunning)
    }

    @Test
    fun `checkConflicts returns empty when no running builds`() = runTest {
        coEvery { bambooService.getRunningBuilds("PROJ-AUTO", null) } returns ToolResult.success(
            data = emptyList(),
            summary = "No running builds"
        )

        val conflicts = service.checkConflicts("PROJ-AUTO", mapOf("auth" to "2.4.0"))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `checkConflicts returns empty when no overlap`() = runTest {
        val runningBuild = BuildResultData(
            planKey = "PROJ-AUTO",
            buildNumber = 849,
            state = "InProgress",
            durationSeconds = 0,
            buildResultKey = "PROJ-AUTO-849"
        )
        coEvery { bambooService.getRunningBuilds("PROJ-AUTO", null) } returns ToolResult.success(
            data = listOf(runningBuild),
            summary = "1 running build"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-849") } returns ToolResult.success(
            data = listOf(PlanVariableData("dockerTagsAsJson", """{"user":"1.9.0"}""")),
            summary = "1 variable"
        )

        val conflicts = service.checkConflicts("PROJ-AUTO", mapOf("auth" to "2.4.0"))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `checkConflicts handles API error gracefully`() = runTest {
        coEvery { bambooService.getRunningBuilds("PROJ-AUTO", null) } returns ToolResult(
            data = emptyList(),
            summary = "timeout",
            isError = true
        )

        val conflicts = service.checkConflicts("PROJ-AUTO", mapOf("auth" to "2.4.0"))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `checkConflicts handles malformed dockerTagsAsJson`() = runTest {
        val runningBuild = BuildResultData(
            planKey = "PROJ-AUTO",
            buildNumber = 849,
            state = "InProgress",
            durationSeconds = 0,
            buildResultKey = "PROJ-AUTO-849"
        )
        coEvery { bambooService.getRunningBuilds("PROJ-AUTO", null) } returns ToolResult.success(
            data = listOf(runningBuild),
            summary = "1 running build"
        )
        coEvery { bambooService.getBuildVariables("PROJ-AUTO-849") } returns ToolResult.success(
            data = listOf(PlanVariableData("dockerTagsAsJson", "not-valid-json")),
            summary = "1 variable"
        )

        val conflicts = service.checkConflicts("PROJ-AUTO", mapOf("auth" to "2.4.0"))
        assertTrue(conflicts.isEmpty())
    }
}
