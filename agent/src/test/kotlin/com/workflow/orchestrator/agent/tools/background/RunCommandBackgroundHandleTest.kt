package com.workflow.orchestrator.agent.tools.background

import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunCommandBackgroundHandleTest {

    @Test
    fun `handle reports EXITED after short process exits`() = runTest {
        val proc = ProcessBuilder("sh", "-c", "echo hi").redirectErrorStream(true).start()
        val managed = ProcessRegistry.register("tc-bg-1", proc, "echo hi")
        // Drain stdout like RunCommandTool would.
        Thread {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach {
                    managed.outputLines.add(it + "\n")
                    managed.lastOutputAt.set(System.currentTimeMillis())
                }
            }
            managed.readerDone.countDown()
        }.also { it.isDaemon = true }.start()

        val handle = RunCommandBackgroundHandle(
            bgId = "bg_t1", sessionId = "s", managed = managed, label = "echo hi"
        )

        // Wait for the process to finish.
        while (handle.state() == BackgroundState.RUNNING) { delay(50) }

        assertEquals(BackgroundState.EXITED, handle.state())
        assertEquals(0, handle.exitCode())
        val out = handle.readOutput()
        assertTrue(out.content.contains("hi"), "expected 'hi' in output, got: ${out.content}")
        ProcessRegistry.unregister("tc-bg-1")
    }
}
