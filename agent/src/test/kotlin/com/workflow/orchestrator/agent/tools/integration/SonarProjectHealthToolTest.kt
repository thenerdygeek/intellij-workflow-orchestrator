package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarProjectHealthToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = SonarProjectHealthTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("sonar_project_health", tool.name)
        assertTrue(tool.parameters.required.contains("project_key"))
        assertTrue(tool.description.contains("health"))
    }

    @Test
    fun `returns error when project_key is missing`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("project_key"))
    }
}
