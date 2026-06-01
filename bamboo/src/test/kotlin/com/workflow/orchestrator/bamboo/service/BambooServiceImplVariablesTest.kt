package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanDto
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanVariableDto
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooStageCollection
import com.workflow.orchestrator.bamboo.api.dto.BambooVariableCollection
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class BambooServiceImplVariablesTest {

    private val mockProject = mockk<Project>(relaxed = true)
    private val mockClient = mockk<BambooApiClient>()
    private val service = BambooServiceImpl(mockProject).also {
        it.testClientOverride = mockClient
    }

    @Test
    fun `getRecentBuilds propagates inline variables from DTO to BuildResultData`() = runTest {
        val dto = BambooResultDto(
            key = "PROJ-PLAN-1",
            buildResultKey = "PROJ-PLAN-1",
            buildNumber = 1,
            state = "Successful",
            lifeCycleState = "Finished",
            buildDurationInSeconds = 60,
            buildRelativeTime = "1 hour ago",
            plan = BambooPlanDto(key = "PROJ-PLAN", name = "My Plan", shortName = "My Plan"),
            stages = BambooStageCollection(),
            variables = BambooVariableCollection(
                variable = listOf(
                    BambooPlanVariableDto(name = "dockerTagsAsJson", value = """{"service":"1.2.3"}"""),
                    BambooPlanVariableDto(name = "buildEnv", value = "production")
                )
            )
        )

        coEvery { mockClient.getRecentResults("PROJ-PLAN", 25) } returns ApiResult.Success(listOf(dto))

        val result = service.getRecentBuilds("PROJ-PLAN", 25)

        assertFalse(result.isError, "Expected success, got: ${result.summary}")
        val builds = result.data!!
        assertEquals(1, builds.size)
        assertEquals(
            mapOf("dockerTagsAsJson" to """{"service":"1.2.3"}""", "buildEnv" to "production"),
            builds[0].variables
        )
    }

    @Test
    fun `getRecentBuilds emits empty variables map when DTO has none`() = runTest {
        val dto = BambooResultDto(
            key = "PROJ-PLAN-2",
            buildResultKey = "PROJ-PLAN-2",
            buildNumber = 2,
            state = "Failed",
            lifeCycleState = "Finished",
            buildDurationInSeconds = 30,
            buildRelativeTime = "2 hours ago",
            plan = BambooPlanDto(key = "PROJ-PLAN", name = "My Plan", shortName = "My Plan"),
            stages = BambooStageCollection(),
            variables = BambooVariableCollection()
        )

        coEvery { mockClient.getRecentResults("PROJ-PLAN", 25) } returns ApiResult.Success(listOf(dto))

        val result = service.getRecentBuilds("PROJ-PLAN", 25)

        assertFalse(result.isError, "Expected success, got: ${result.summary}")
        val builds = result.data!!
        assertEquals(1, builds.size)
        assertEquals(emptyMap<String, String>(), builds[0].variables)
    }
}
