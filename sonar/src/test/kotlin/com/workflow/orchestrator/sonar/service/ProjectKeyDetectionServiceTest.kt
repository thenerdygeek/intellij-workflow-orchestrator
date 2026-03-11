package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.sonar.api.SonarApiClient
import com.workflow.orchestrator.sonar.api.dto.SonarProjectDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectKeyDetectionServiceTest {

    private val apiClient = mockk<SonarApiClient>()
    private val service = ProjectKeyDetectionService(apiClient)

    @Test
    fun `autoDetect returns single match`() = runTest {
        coEvery { apiClient.searchProjects("my-repo") } returns ApiResult.Success(
            listOf(SonarProjectDto("com.myapp:my-repo", "My Repo"))
        )

        val result = service.autoDetect("my-repo")

        assertTrue(result is ApiResult.Success)
        assertEquals("com.myapp:my-repo", (result as ApiResult.Success).data)
    }

    @Test
    fun `autoDetect returns null for multiple matches`() = runTest {
        coEvery { apiClient.searchProjects("app") } returns ApiResult.Success(
            listOf(
                SonarProjectDto("com.myapp:app-api", "App API"),
                SonarProjectDto("com.myapp:app-web", "App Web")
            )
        )

        val result = service.autoDetect("app")

        assertTrue(result is ApiResult.Success)
        assertNull((result as ApiResult.Success).data)
    }

    @Test
    fun `autoDetect returns null for zero matches`() = runTest {
        coEvery { apiClient.searchProjects("nonexistent") } returns ApiResult.Success(emptyList())

        val result = service.autoDetect("nonexistent")

        assertTrue(result is ApiResult.Success)
        assertNull((result as ApiResult.Success).data)
    }

    @Test
    fun `search delegates to apiClient`() = runTest {
        val projects = listOf(SonarProjectDto("key1", "Project 1"))
        coEvery { apiClient.searchProjects("query") } returns ApiResult.Success(projects)

        val result = service.search("query")

        assertTrue(result is ApiResult.Success)
        assertEquals(1, (result as ApiResult.Success).data.size)
    }
}
