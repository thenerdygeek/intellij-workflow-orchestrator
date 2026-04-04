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

class SendStdinToolTest {

    private val project = mockk<Project> { every { basePath } returns "/tmp" }

    @AfterEach
    fun cleanup() {
        ProcessRegistry.killAll()
        Thread.sleep(100)
    }

    @Test
    fun `returns error for unknown process ID`() = runTest {
        val tool = SendStdinTool()
        val params = buildJsonObject {
            put("process_id", "nonexistent-id-12345")
            put("input", "hello\n")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found"), "Expected 'not found' in: ${result.content}")
    }

    @Test
    fun `returns error when stdin limit exceeded`() = runTest {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val process = if (isWindows) {
            ProcessBuilder("cmd.exe", "/c", "pause").start()
        } else {
            ProcessBuilder("sh", "-c", "read line; echo done").start()
        }

        val toolCallId = "test-stdin-limit-exceeded"
        val managed = ProcessRegistry.register(toolCallId, process, "read line; echo done")

        // Set stdinCount to the limit
        managed.stdinCount.set(10)

        val tool = SendStdinTool()
        val params = buildJsonObject {
            put("process_id", toolCallId)
            put("input", "hello\n")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("limit") || result.content.contains("exceeded"),
            "Expected limit message in: ${result.content}"
        )

        process.destroyForcibly()
    }

    @Test
    fun `sends input and returns new output`() = runTest {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        // Use a command that: prints a prompt, reads a line, prints it back, then keeps running briefly
        // so SendStdinTool has time to detect the response before process exits
        val process = if (isWindows) {
            ProcessBuilder("cmd.exe", "/c", "set /p line= && echo Got: %line%").start()
        } else {
            // Print a prompt so there's initial output, then read+echo+sleep so the tool can capture output
            ProcessBuilder("sh", "-c", "echo 'Enter:'; read line; echo \"Got: \$line\"; sleep 2").start()
        }

        val toolCallId = "test-send-stdin-echo"
        val managed = ProcessRegistry.register(toolCallId, process, "echo prompt; read line; echo Got: line")

        // Start a reader thread to populate outputLines and lastOutputAt (simulating RunCommandTool's reader)
        Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    var bytesRead = reader.read(buffer)
                    while (bytesRead != -1) {
                        val chunk = String(buffer, 0, bytesRead)
                        managed.outputLines.add(chunk)
                        managed.lastOutputAt.set(System.currentTimeMillis())
                        bytesRead = reader.read(buffer)
                    }
                }
            } catch (_: Exception) {
                // Process killed or stream closed
            }
        }.apply {
            isDaemon = true
            name = "SendStdinTest-Output-$toolCallId"
            start()
        }

        // Wait for the process to start and print its prompt
        val deadline = System.currentTimeMillis() + 3000
        while (managed.outputLines.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        val tool = SendStdinTool()
        val params = buildJsonObject {
            put("process_id", toolCallId)
            put("input", "hello\n")
        }

        val result = tool.execute(params, project)

        assertFalse(result.isError, "Expected success, got error: ${result.content}")
        assertTrue(
            result.content.contains("Got: hello") || result.content.contains("hello") || result.content.contains("Exit code: 0"),
            "Expected echo output or success in result, got: ${result.content}"
        )

        process.destroyForcibly()
    }
}
