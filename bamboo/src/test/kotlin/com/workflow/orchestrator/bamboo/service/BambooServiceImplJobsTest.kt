package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooJobResultCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooJobResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanDto
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooStageDto
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BambooServiceImplJobsTest {

    private val mockProject = mockk<Project>(relaxed = true)
    private val mockClient = mockk<BambooApiClient>()
    private val service = BambooServiceImpl(mockProject).also {
        it.testClientOverride = mockClient
    }

    private val sampleDto = BambooResultDto(
        key = "PROJ-PLAN138-4",
        buildResultKey = "PROJ-PLAN138-4",
        buildNumber = 4,
        state = "Failed",
        lifeCycleState = "Finished",
        buildDurationInSeconds = 312,
        buildRelativeTime = "2 hours ago",
        plan = BambooPlanDto(key = "PROJ-PLAN138", name = "feature/foo", shortName = "feature/foo"),
        stages = BambooStageCollection(
            stage = listOf(
                BambooStageDto(
                    name = "Build",
                    state = "Successful",
                    lifeCycleState = "Finished",
                    manual = false,
                    buildDurationInSeconds = 120,
                    results = BambooJobResultCollection(
                        result = listOf(
                            BambooJobResultDto(
                                key = "PROJ-PLAN138-COMPILE-4",
                                buildResultKey = "PROJ-PLAN138-COMPILE-4",
                                state = "Successful",
                                lifeCycleState = "Finished",
                                buildDurationInSeconds = 120,
                                plan = BambooPlanDto(key = "PROJ-PLAN138-COMPILE", name = "Compile", shortName = "Compile")
                            )
                        )
                    )
                ),
                BambooStageDto(
                    name = "Test",
                    state = "Failed",
                    lifeCycleState = "Finished",
                    manual = false,
                    buildDurationInSeconds = 192,
                    results = BambooJobResultCollection(
                        result = listOf(
                            BambooJobResultDto(
                                key = "PROJ-PLAN138-UNIT-4",
                                buildResultKey = "PROJ-PLAN138-UNIT-4",
                                state = "Failed",
                                lifeCycleState = "Finished",
                                buildDurationInSeconds = 102,
                                plan = BambooPlanDto(key = "PROJ-PLAN138-UNIT", name = "Unit Tests", shortName = "Unit Tests")
                            ),
                            BambooJobResultDto(
                                key = "PROJ-PLAN138-INTEG-4",
                                buildResultKey = "PROJ-PLAN138-INTEG-4",
                                state = "Successful",
                                lifeCycleState = "Finished",
                                buildDurationInSeconds = 90,
                                plan = BambooPlanDto(key = "PROJ-PLAN138-INTEG", name = "Integration Tests", shortName = "Integration Tests")
                            )
                        )
                    )
                )
            )
        )
    )

    @Test
    fun `getBuild populates BuildStageData jobs with per-job resultKey`() = runTest {
        coEvery { mockClient.getBuildResult("PROJ-PLAN138-4") } returns ApiResult.Success(sampleDto)

        val result = service.getBuild("PROJ-PLAN138-4")

        assertFalse(result.isError, "Expected success, got: ${result.summary}")
        val data = result.data!!
        assertEquals(2, data.stages.size, "Expected 2 stages")

        val buildStage = data.stages[0]
        assertEquals("Build", buildStage.name)
        assertEquals(1, buildStage.jobs.size)
        assertEquals("PROJ-PLAN138-COMPILE-4", buildStage.jobs[0].resultKey)
        assertEquals("Compile", buildStage.jobs[0].name)
        assertEquals("Successful", buildStage.jobs[0].state)

        val testStage = data.stages[1]
        assertEquals("Test", testStage.name)
        assertEquals(2, testStage.jobs.size)
        assertEquals("PROJ-PLAN138-UNIT-4", testStage.jobs[0].resultKey)
        assertEquals("Failed", testStage.jobs[0].state)
        assertEquals("Unit Tests", testStage.jobs[0].name)
        assertEquals("PROJ-PLAN138-INTEG-4", testStage.jobs[1].resultKey)
    }

    @Test
    fun `getBuild buildResultKey carries through (regression test for mapBuildResult)`() = runTest {
        coEvery { mockClient.getBuildResult("PROJ-PLAN138-4") } returns ApiResult.Success(sampleDto)

        val result = service.getBuild("PROJ-PLAN138-4")

        assertEquals("PROJ-PLAN138-4", result.data!!.buildResultKey,
            "buildResultKey was historically dropped by mapBuildResult — Task 2.3 fixes this")
    }
}
