package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolRegistryTest {

    @Test
    fun `register and retrieve tool by name`() {
        val registry = ToolRegistry()
        val tool = FakeAgentTool(name = "read_file", allowedWorkers = setOf(WorkerType.CODER))
        registry.register(tool)
        assertEquals(tool, registry.getTool("read_file"))
    }

    @Test
    fun `getTool returns null for unregistered name`() {
        val registry = ToolRegistry()
        assertNull(registry.getTool("nonexistent"))
    }

    @Test
    fun `getToolsForWorker filters by worker type`() {
        val registry = ToolRegistry()
        registry.register(FakeAgentTool("read_file", setOf(WorkerType.CODER, WorkerType.REVIEWER)))
        registry.register(FakeAgentTool("edit_file", setOf(WorkerType.CODER)))
        registry.register(FakeAgentTool("jira_get", setOf(WorkerType.TOOLER)))

        val coderTools = registry.getToolsForWorker(WorkerType.CODER)
        assertEquals(2, coderTools.size)
        assertTrue(coderTools.any { it.name == "read_file" })
        assertTrue(coderTools.any { it.name == "edit_file" })

        val toolerTools = registry.getToolsForWorker(WorkerType.TOOLER)
        assertEquals(1, toolerTools.size)
        assertEquals("jira_get", toolerTools.first().name)
    }

    @Test
    fun `getToolsForWorker returns empty list when no tools match`() {
        val registry = ToolRegistry()
        registry.register(FakeAgentTool("edit_file", setOf(WorkerType.CODER)))

        val analyzerTools = registry.getToolsForWorker(WorkerType.ANALYZER)
        assertTrue(analyzerTools.isEmpty())
    }

    @Test
    fun `getToolDefinitionsForWorker returns OpenAI-compatible schemas`() {
        val registry = ToolRegistry()
        registry.register(FakeAgentTool("read_file", setOf(WorkerType.CODER)))

        val defs = registry.getToolDefinitionsForWorker(WorkerType.CODER)
        assertEquals(1, defs.size)
        assertEquals("function", defs.first().type)
        assertEquals("read_file", defs.first().function.name)
    }

    @Test
    fun `allTools returns all registered tools`() {
        val registry = ToolRegistry()
        registry.register(FakeAgentTool("tool_a", setOf(WorkerType.CODER)))
        registry.register(FakeAgentTool("tool_b", setOf(WorkerType.ANALYZER)))
        registry.register(FakeAgentTool("tool_c", setOf(WorkerType.TOOLER)))

        assertEquals(3, registry.allTools().size)
    }

    @Test
    fun `register replaces existing tool with same name`() {
        val registry = ToolRegistry()
        val tool1 = FakeAgentTool("read_file", setOf(WorkerType.CODER), description = "v1")
        val tool2 = FakeAgentTool("read_file", setOf(WorkerType.CODER), description = "v2")
        registry.register(tool1)
        registry.register(tool2)

        assertEquals("v2", registry.getTool("read_file")?.description)
        assertEquals(1, registry.allTools().size)
    }
}

class FakeAgentTool(
    override val name: String,
    override val allowedWorkers: Set<WorkerType>,
    override val description: String = "Fake tool for testing",
    override val parameters: FunctionParameters = FunctionParameters(properties = emptyMap())
) : AgentTool {
    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return ToolResult(content = "fake result", summary = "fake", tokenEstimate = 10)
    }
}
