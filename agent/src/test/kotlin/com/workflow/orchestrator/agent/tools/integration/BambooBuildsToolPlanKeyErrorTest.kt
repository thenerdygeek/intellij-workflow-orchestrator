package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BambooBuildsToolPlanKeyErrorTest {

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `recent_builds missing plan_key error mentions project_context`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val tool = BambooBuildsTool()
        val params = buildJsonObject { put("action", "recent_builds") }

        val result = tool.execute(params, project)

        val text = result.content
        assertTrue(result.isError, "Expected error result for missing plan_key")
        assertTrue(
            text.contains("project_context", ignoreCase = true),
            "Expected error to mention project_context; got: $text"
        )
        assertTrue(
            text.contains("Settings", ignoreCase = true),
            "Expected error to mention Settings as fallback; got: $text"
        )
    }

    @Test
    fun `get_running_builds missing plan_key error mentions project_context`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val tool = BambooBuildsTool()
        val params = buildJsonObject { put("action", "get_running_builds") }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("project_context", ignoreCase = true),
            "Expected error to mention project_context"
        )
    }
}
