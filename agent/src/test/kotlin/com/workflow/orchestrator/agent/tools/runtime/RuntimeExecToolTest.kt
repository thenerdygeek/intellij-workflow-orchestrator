package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RuntimeExecToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = RuntimeExecTool()

    @Test
    fun `tool name is runtime_exec`() {
        assertEquals("runtime_exec", tool.name)
    }

    @Test
    fun `action enum contains only 3 universal actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(3, actions!!.size)
        assertTrue("get_running_processes" in actions)
        assertTrue("get_run_output" in actions)
        assertTrue("get_test_results" in actions)
        // These two moved to java_runtime_exec / python_runtime_exec.
        assertFalse("run_tests" in actions)
        assertFalse("compile_module" in actions)
    }

    @Test
    fun `description no longer mentions run_tests or compile_module as supported actions`() {
        // Description should point the LLM at the IDE-specific variants for test/compile.
        val desc = tool.description
        assertTrue(desc.contains("java_runtime_exec"), "description should mention java_runtime_exec")
        assertTrue(desc.contains("python_runtime_exec"), "description should mention python_runtime_exec")
        assertFalse(
            desc.lineSequence().any { it.trimStart().startsWith("- run_tests(") },
            "run_tests should no longer appear as a supported action bullet"
        )
        assertFalse(
            desc.lineSequence().any { it.trimStart().startsWith("- compile_module(") },
            "compile_module should no longer appear as a supported action bullet"
        )
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
        assertEquals("runtime_exec", def.function.name)
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

    @Test
    fun `run_tests returns routing error pointing at java and python variants`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "run_tests") }, project)
        assertTrue(result.isError, "run_tests should route to an error since it's not handled here")
        assertTrue(result.content.contains("java_runtime_exec"), "error should direct LLM to java_runtime_exec")
        assertTrue(result.content.contains("python_runtime_exec"), "error should direct LLM to python_runtime_exec")
    }

    @Test
    fun `compile_module returns routing error pointing at java and python variants`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "compile_module") }, project)
        assertTrue(result.isError, "compile_module should route to an error since it's not handled here")
        assertTrue(result.content.contains("java_runtime_exec"), "error should direct LLM to java_runtime_exec")
        assertTrue(result.content.contains("python_runtime_exec"), "error should direct LLM to python_runtime_exec")
    }
}
