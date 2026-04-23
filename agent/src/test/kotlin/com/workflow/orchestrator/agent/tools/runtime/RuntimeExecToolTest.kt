package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.every
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
    fun `action enum contains 5 universal actions including run_config lifecycle`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(5, actions!!.size)
        // Observation actions (original 3)
        assertTrue("get_running_processes" in actions)
        assertTrue("get_run_output" in actions)
        assertTrue("get_test_results" in actions)
        // Run-config lifecycle actions
        assertTrue("run_config" in actions)
        assertTrue("stop_run_config" in actions)
        // restart_run_config deleted — run_config is now idempotent
        assertFalse("restart_run_config" in actions)
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

    // ─────────────────────────────────────────────────────────────────────
    // selectDescriptorByName — regression guard for terminated-shadows-live bug
    // (user ran a Spring Boot config, stopped it, then re-ran in Debug. get_run_output
    //  returned the terminated run instead of the live debug session.)
    // ─────────────────────────────────────────────────────────────────────

    private fun descriptor(name: String, terminated: Boolean): RunContentDescriptor {
        val handler = mockk<ProcessHandler>()
        every { handler.isProcessTerminated } returns terminated
        every { handler.isProcessTerminating } returns false
        val d = mockk<RunContentDescriptor>()
        every { d.displayName } returns name
        every { d.processHandler } returns handler
        return d
    }

    @Test
    fun `selectDescriptorByName prefers live over terminated with same name`() {
        val terminatedRun = descriptor("MyService", terminated = true)
        val liveDebug = descriptor("MyService", terminated = false)
        // Registration order: terminated run came first (user ran it, then debugged).
        val all = listOf(terminatedRun, liveDebug)

        val selection = tool.selectDescriptorByName(all, "MyService")
        assertNotNull(selection)
        assertSame(liveDebug, selection!!.descriptor, "should pick the live descriptor")
        assertTrue(selection.pickedLive)
        assertEquals(listOf(terminatedRun), selection.others)
    }

    @Test
    fun `selectDescriptorByName falls back to most recent when all terminated`() {
        val older = descriptor("MyService", terminated = true)
        val newer = descriptor("MyService", terminated = true)
        val selection = tool.selectDescriptorByName(listOf(older, newer), "MyService")
        assertNotNull(selection)
        assertSame(newer, selection!!.descriptor, "should pick the last (most recent) when none are live")
        assertFalse(selection.pickedLive)
    }

    @Test
    fun `selectDescriptorByName returns null when nothing matches`() {
        val other = descriptor("OtherService", terminated = false)
        assertNull(tool.selectDescriptorByName(listOf(other), "MyService"))
    }

    @Test
    fun `selectDescriptorByName is case-insensitive and substring-based`() {
        val match = descriptor("MyServiceApplication", terminated = false)
        val selection = tool.selectDescriptorByName(listOf(match), "myservice")
        assertNotNull(selection)
        assertSame(match, selection!!.descriptor)
    }

    @Test
    fun `selectDescriptorByName picks latest live when multiple are live`() {
        val olderLive = descriptor("MyService", terminated = false)
        val newerLive = descriptor("MyService", terminated = false)
        val selection = tool.selectDescriptorByName(listOf(olderLive, newerLive), "MyService")
        assertSame(newerLive, selection!!.descriptor)
    }
}
