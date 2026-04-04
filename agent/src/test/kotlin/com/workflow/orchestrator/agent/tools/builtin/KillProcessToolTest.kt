package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KillProcessToolTest {

    private val tool = KillProcessTool()
    private val project = mockk<Project> { every { basePath } returns "/tmp" }

    @AfterEach
    fun cleanup() {
        ProcessRegistry.killAll()
    }

    @Test
    fun `kills running process and returns partial output`() = runTest {
        // Start a long-running process and register it
        val process = ProcessBuilder("sh", "-c", "sleep 60").start()
        val managed = ProcessRegistry.register("test-kill-001", process, "sleep 60")

        // Add some output to outputLines to simulate partial output
        managed.outputLines.add("line one\n")
        managed.outputLines.add("line two\n")

        val params = buildJsonObject {
            put("process_id", "test-kill-001")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("[KILLED]"), "Expected [KILLED] in content, got: ${result.content}")
        assertTrue(result.content.contains("test-kill-001"), "Expected process ID in content, got: ${result.content}")
        assertTrue(result.content.contains("sleep 60"), "Expected command in content, got: ${result.content}")
        assertTrue(result.content.contains("Partial output:"), "Expected 'Partial output:' section, got: ${result.content}")

        // Verify process removed from registry
        assertNull(ProcessRegistry.get("test-kill-001"), "Expected process to be removed from registry after kill")
    }

    @Test
    fun `returns error for unknown process`() = runTest {
        val params = buildJsonObject {
            put("process_id", "nonexistent-process-id")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("not found") || result.content.contains("already exited"),
            "Expected 'not found' or 'already exited' in content, got: ${result.content}"
        )
    }

    @Test
    fun `returns error when process_id param is missing`() = runTest {
        val params = buildJsonObject {}

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("process_id"), "Expected 'process_id' in error message, got: ${result.content}")
    }

    @Test
    fun `tool metadata is correct`() {
        assertEquals("kill_process", tool.name)
        assertTrue(tool.parameters.required.contains("process_id"))
        assertTrue(tool.parameters.properties.containsKey("process_id"))
        assertEquals(setOf(com.workflow.orchestrator.agent.runtime.WorkerType.CODER), tool.allowedWorkers)
    }
}
