package com.workflow.orchestrator.agent.tools.integration.sonar

import com.workflow.orchestrator.core.services.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SonarRetryTest {

    @Test
    fun `returns success on first attempt`() = runTest {
        val calls = AtomicInteger(0)
        val result = SonarRetry.withBackoff(maxAttempts = 3, initialDelayMs = 10) {
            calls.incrementAndGet()
            ToolResult(data = "ok", summary = "ok", isError = false)
        }
        assertFalse(result.isError)
        assertEquals(1, calls.get())
    }

    @Test
    fun `returns success after transient errors`() = runTest {
        val calls = AtomicInteger(0)
        val result = SonarRetry.withBackoff(maxAttempts = 3, initialDelayMs = 10) {
            val n = calls.incrementAndGet()
            if (n < 3) ToolResult(data = "", summary = "transient $n", isError = true)
            else ToolResult(data = "ok", summary = "ok", isError = false)
        }
        assertFalse(result.isError)
        assertEquals(3, calls.get())
    }

    @Test
    fun `returns last error after all attempts fail`() = runTest {
        val calls = AtomicInteger(0)
        val result = SonarRetry.withBackoff<String>(maxAttempts = 3, initialDelayMs = 10) {
            ToolResult(data = "", summary = "fail ${calls.incrementAndGet()}", isError = true)
        }
        assertTrue(result.isError)
        assertEquals(3, calls.get())
        assertEquals("fail 3", result.summary)
    }

    @Test
    fun `does not retry when retryWhile returns false`() = runTest {
        val calls = AtomicInteger(0)
        val result = SonarRetry.withBackoff<String>(
            maxAttempts = 3,
            initialDelayMs = 10,
            retryWhile = { false }
        ) {
            calls.incrementAndGet()
            ToolResult(data = "", summary = "permanent", isError = true)
        }
        assertTrue(result.isError)
        assertEquals(1, calls.get())
    }

    @Test
    fun `maxAttempts=1 makes exactly one call`() = runTest {
        val calls = AtomicInteger(0)
        SonarRetry.withBackoff<String>(maxAttempts = 1, initialDelayMs = 10) {
            calls.incrementAndGet()
            ToolResult(data = "", summary = "x", isError = true)
        }
        assertEquals(1, calls.get())
    }
}
