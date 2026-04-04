package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RuntimeConfigToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = RuntimeConfigTool()

    @Test
    fun `tool name is runtime_config`() {
        assertEquals("runtime_config", tool.name)
    }

    @Test
    fun `action enum contains all 4 actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(4, actions!!.size)
        assertTrue("get_run_configurations" in actions)
        assertTrue("create_run_config" in actions)
        assertTrue("modify_run_config" in actions)
        assertTrue("delete_run_config" in actions)
    }

    @Test
    fun `only action is required`() {
        assertEquals(listOf("action"), tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes all expected types`() {
        assertEquals(
            setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("runtime_config", def.function.name)
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.description.isNotBlank())
    }

    @Test
    fun `missing action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("action"))
    }

    @Test
    fun `unknown action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "nonexistent") }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown action"))
    }
}
