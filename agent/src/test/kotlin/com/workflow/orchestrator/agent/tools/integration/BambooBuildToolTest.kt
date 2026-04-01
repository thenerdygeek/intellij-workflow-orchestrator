package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BambooBuildToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = BambooBuildsTool()

    @Test
    fun `tool name is bamboo_builds`() {
        assertEquals("bamboo_builds", tool.name)
    }

    @Test
    fun `action enum contains all 10 actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(10, actions!!.size)
        assertTrue("build_status" in actions)
        assertTrue("get_build" in actions)
        assertTrue("trigger_build" in actions)
        assertTrue("get_build_log" in actions)
        assertTrue("get_test_results" in actions)
        assertTrue("stop_build" in actions)
        assertTrue("cancel_build" in actions)
        assertTrue("get_artifacts" in actions)
        assertTrue("recent_builds" in actions)
        assertTrue("get_running_builds" in actions)
    }

    @Test
    fun `only action is required`() {
        assertEquals(listOf("action"), tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes TOOLER and ORCHESTRATOR`() {
        assertEquals(
            setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("bamboo_builds", def.function.name)
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
    }
}
