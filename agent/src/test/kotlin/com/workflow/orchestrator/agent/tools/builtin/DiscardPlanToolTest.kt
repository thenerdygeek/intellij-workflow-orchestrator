package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResultType
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiscardPlanToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = DiscardPlanTool()

    @Test
    fun `tool name is discard_plan`() {
        assertEquals("discard_plan", tool.name)
    }

    @Test
    fun `allowedWorkers contains only ORCHESTRATOR`() {
        assertEquals(setOf(WorkerType.ORCHESTRATOR), tool.allowedWorkers)
    }

    @Test
    fun `execute returns ToolResult with type PlanDiscarded`() = runTest {
        val result = tool.execute(buildJsonObject {}, project)

        assertTrue(result.type is ToolResultType.PlanDiscarded, "type should be PlanDiscarded, got ${result.type}")
    }

    @Test
    fun `execute returns non-error result`() = runTest {
        val result = tool.execute(buildJsonObject {}, project)

        assertFalse(result.isError, "should not be an error result")
    }

    @Test
    fun `execute result is not a completion signal`() = runTest {
        val result = tool.execute(buildJsonObject {}, project)

        assertFalse(result.isCompletion, "discard_plan should not signal task completion")
    }

    @Test
    fun `execute result content is non-empty`() = runTest {
        val result = tool.execute(buildJsonObject {}, project)

        assertTrue(result.content.isNotBlank(), "result content should not be blank")
    }

    @Test
    fun `execute result summary is non-empty`() = runTest {
        val result = tool.execute(buildJsonObject {}, project)

        assertTrue(result.summary.isNotBlank(), "result summary should not be blank")
    }

    @Test
    fun `parameters has no required fields`() {
        assertTrue(
            tool.parameters.required.isEmpty(),
            "discard_plan takes no parameters so required list should be empty"
        )
    }

    @Test
    fun `description mentions PLAN MODE`() {
        assertTrue(
            tool.description.contains("PLAN MODE"),
            "description should mention PLAN MODE"
        )
    }
}
