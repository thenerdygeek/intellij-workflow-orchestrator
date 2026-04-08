package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BambooPlansToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = BambooPlansTool()

    @Test
    fun `tool name is bamboo_plans`() {
        assertEquals("bamboo_plans", tool.name)
    }

    @Test
    fun `action enum contains all 9 actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(9, actions!!.size)
        assertTrue("auto_detect_plan" in actions)
        assertTrue("get_plans" in actions)
        assertTrue("get_project_plans" in actions)
        assertTrue("search_plans" in actions)
        assertTrue("get_plan_branches" in actions)
        assertTrue("get_build_variables" in actions)
        assertTrue("get_plan_variables" in actions)
        assertTrue("rerun_failed_jobs" in actions)
        assertTrue("trigger_stage" in actions)
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
        assertEquals("bamboo_plans", def.function.name)
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
