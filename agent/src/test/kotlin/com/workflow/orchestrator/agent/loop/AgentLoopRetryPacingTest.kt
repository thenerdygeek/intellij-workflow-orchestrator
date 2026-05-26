package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text guardrails pinning that every LLM recovery/retry path in AgentLoop is PACED
 * (preceded by a backoff delay) and BOUNDED. These paths previously did a bare `continue`
 * with no delay, producing the "3 retries happen instantly" behavior. Same readSource pattern
 * as FailureReasonWiringTest.
 */
class AgentLoopRetryPacingTest {

    private val src by lazy { readSource("loop", "AgentLoop.kt") }

    @Test
    fun `output-length truncation recovery is capped and paced`() {
        assertTrue(src.contains("truncatedRetries++"), "length path must increment truncatedRetries")
        assertTrue(src.contains("MAX_TRUNCATED_RETRIES"), "length path must cap via MAX_TRUNCATED_RETRIES")
        assertTrue(
            src.contains("delay(computeBackoffMs(truncatedRetries))"),
            "length path must back off before re-calling the LLM (was a bare continue)."
        )
    }

    @Test
    fun `upstream gateway-timeout recovery is capped and paced`() {
        assertTrue(src.contains("upstreamTimeoutRetries++"), "upstream_timeout path must increment its counter")
        assertTrue(src.contains("MAX_UPSTREAM_TIMEOUT_RETRIES"), "upstream_timeout path must cap")
        assertTrue(
            src.contains("delay(computeBackoffMs(upstreamTimeoutRetries))"),
            "upstream_timeout path must back off before re-calling the LLM (was a bare continue)."
        )
    }

    @Test
    fun `context-overflow replay is paced`() {
        assertTrue(
            src.contains("delay(computeBackoffMs(contextOverflowRetries))"),
            "context-overflow replay must back off before re-calling the LLM (was a bare continue)."
        )
    }

    @Test
    fun `truncation and timeout counters reset on a clean response`() {
        assertTrue(src.contains("truncatedRetries = 0"), "truncatedRetries must reset on a clean response")
        assertTrue(src.contains("upstreamTimeoutRetries = 0"), "upstreamTimeoutRetries must reset on a clean response")
    }

    @Test
    fun `backoff helper uses an equal-jitter floor, not full jitter`() {
        // half + random(0, half) guarantees result >= computed/2 — no near-instant retry.
        assertTrue(
            src.contains("half + Random.nextLong(half + 1)"),
            "computeBackoffMs must use equal jitter (computed/2 floor), not full jitter [0, computed]."
        )
    }

    private fun readSource(subPackage: String, name: String): String {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        val root = File(userDir)
        val rel = "src/main/kotlin/com/workflow/orchestrator/agent/$subPackage/$name"
        val moduleRooted = File(root, rel)
        val repoRooted = File(root, "agent/$rel")
        val path = when {
            moduleRooted.isFile -> moduleRooted
            repoRooted.isFile -> repoRooted
            else -> error("Source '$name' not found at ${moduleRooted.absolutePath} or ${repoRooted.absolutePath}")
        }
        return path.readText()
    }
}
