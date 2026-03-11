package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.automation.model.Conflict
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageCollection
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConflictDetectorServiceTest {

    private lateinit var bambooClient: BambooApiClient
    private lateinit var service: ConflictDetectorService

    @BeforeEach
    fun setUp() {
        bambooClient = mockk()
        service = ConflictDetectorService(bambooClient)
    }

    @Test
    fun `checkConflicts detects overlapping services`() = runTest {
        val runningBuild = BambooResultDto(
            key = "PROJ-AUTO-849",
            buildNumber = 849,
            state = "Unknown",
            lifeCycleState = "InProgress",
            stages = BambooStageCollection()
        )
        coEvery { bambooClient.getRunningAndQueuedBuilds("PROJ-AUTO") } returns
            ApiResult.Success(listOf(runningBuild))
        coEvery { bambooClient.getBuildVariables("PROJ-AUTO-849") } returns
            ApiResult.Success(mapOf(
                "dockerTagsAsJson" to """{"auth":"2.4.0","payments":"2.3.1"}"""
            ))

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
        coEvery { bambooClient.getRunningAndQueuedBuilds("PROJ-AUTO") } returns
            ApiResult.Success(emptyList())

        val conflicts = service.checkConflicts("PROJ-AUTO", mapOf("auth" to "2.4.0"))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `checkConflicts returns empty when no overlap`() = runTest {
        val runningBuild = BambooResultDto(
            key = "PROJ-AUTO-849",
            buildNumber = 849,
            state = "Unknown",
            lifeCycleState = "InProgress",
            stages = BambooStageCollection()
        )
        coEvery { bambooClient.getRunningAndQueuedBuilds("PROJ-AUTO") } returns
            ApiResult.Success(listOf(runningBuild))
        coEvery { bambooClient.getBuildVariables("PROJ-AUTO-849") } returns
            ApiResult.Success(mapOf("dockerTagsAsJson" to """{"user":"1.9.0"}"""))

        val conflicts = service.checkConflicts("PROJ-AUTO", mapOf("auth" to "2.4.0"))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `checkConflicts handles API error gracefully`() = runTest {
        coEvery { bambooClient.getRunningAndQueuedBuilds("PROJ-AUTO") } returns
            ApiResult.Error(com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR, "timeout")

        val conflicts = service.checkConflicts("PROJ-AUTO", mapOf("auth" to "2.4.0"))
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `checkConflicts handles malformed dockerTagsAsJson`() = runTest {
        val runningBuild = BambooResultDto(
            key = "PROJ-AUTO-849",
            buildNumber = 849,
            state = "Unknown",
            lifeCycleState = "InProgress",
            stages = BambooStageCollection()
        )
        coEvery { bambooClient.getRunningAndQueuedBuilds("PROJ-AUTO") } returns
            ApiResult.Success(listOf(runningBuild))
        coEvery { bambooClient.getBuildVariables("PROJ-AUTO-849") } returns
            ApiResult.Success(mapOf("dockerTagsAsJson" to "not-valid-json"))

        val conflicts = service.checkConflicts("PROJ-AUTO", mapOf("auth" to "2.4.0"))
        assertTrue(conflicts.isEmpty())
    }
}
