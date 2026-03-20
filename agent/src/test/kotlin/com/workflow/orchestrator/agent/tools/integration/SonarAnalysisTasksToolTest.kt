package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarAnalysisTasksToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = SonarAnalysisTasksTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("sonar_analysis_tasks", tool.name)
        assertTrue(tool.parameters.required.contains("project_key"))
        assertTrue(tool.description.contains("analysis"))
    }

    @Test
    fun `returns error when project_key is missing`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("project_key"))
    }
}
