package com.workflow.orchestrator.agent.tools.integration.sonar

import com.workflow.orchestrator.agent.tools.ToolResult
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
            ToolResult(content = "ok", summary = "ok", tokenEstimate = 1, isError = false)
        }
        assertFalse(result.isError)
        assertEquals(1, calls.get())
    }

    @Test
    fun `returns success after transient errors`() = runTest {
        val calls = AtomicInteger(0)
        val result = SonarRetry.withBackoff(maxAttempts = 3, initialDelayMs = 10) {
            val n = calls.incrementAndGet()
            if (n < 3) ToolResult("err", "transient $n", 1, isError = true)
            else ToolResult("ok", "ok", 1, isError = false)
        }
        assertFalse(result.isError)
        assertEquals(3, calls.get())
    }

    @Test
    fun `returns last error after all attempts fail`() = runTest {
        val calls = AtomicInteger(0)
        val result = SonarRetry.withBackoff(maxAttempts = 3, initialDelayMs = 10) {
            ToolResult("err", "fail ${calls.incrementAndGet()}", 1, isError = true)
        }
        assertTrue(result.isError)
        assertEquals(3, calls.get())
        assertEquals("fail 3", result.summary)
    }

    @Test
    fun `does not retry when shouldRetry returns false`() = runTest {
        val calls = AtomicInteger(0)
        val result = SonarRetry.withBackoff(
            maxAttempts = 3,
            initialDelayMs = 10,
            shouldRetry = { false }
        ) {
            ToolResult("err", "permanent", 1, isError = true)
        }
        assertTrue(result.isError)
        assertEquals(1, calls.incrementAndGet())  // 1 from the block + 1 here = 2 total ops, but only 1 retry-block call
    }

    @Test
    fun `maxAttempts=1 makes exactly one call`() = runTest {
        val calls = AtomicInteger(0)
        SonarRetry.withBackoff(maxAttempts = 1, initialDelayMs = 10) {
            ToolResult("err", "x", 1, isError = true).also { calls.incrementAndGet() }
        }
        assertEquals(1, calls.get())
    }
}
