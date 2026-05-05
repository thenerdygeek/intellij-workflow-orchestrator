package com.workflow.orchestrator.core.ai

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

class GatewayErrorDetectorTest {

    @Test
    fun `detects context deadline exceeded process_completion frame`() {
        val line = """{"message":"context deadline exceeded","type":"completion.process_completion"}"""
        assertTrue(GatewayErrorDetector.isUpstreamTimeoutFrame(line))
    }

    @Test
    fun `detects frame with whitespace and ordering variations`() {
        val line = """  {"type" : "completion.process_completion", "message" : "context deadline exceeded"}  """
        assertTrue(GatewayErrorDetector.isUpstreamTimeoutFrame(line))
    }

    @Test
    fun `does not match normal data SSE chunks`() {
        val line = """data: {"choices":[{"delta":{"content":"hello"}}]}"""
        assertFalse(GatewayErrorDetector.isUpstreamTimeoutFrame(line))
    }

    @Test
    fun `does not match unrelated error JSON`() {
        val line = """{"message":"rate limit exceeded","type":"rate_limit"}"""
        assertFalse(GatewayErrorDetector.isUpstreamTimeoutFrame(line))
    }

    @Test
    fun `does not match SSE comment or DONE marker`() {
        assertFalse(GatewayErrorDetector.isUpstreamTimeoutFrame(": ping"))
        assertFalse(GatewayErrorDetector.isUpstreamTimeoutFrame("data: [DONE]"))
        assertFalse(GatewayErrorDetector.isUpstreamTimeoutFrame(""))
    }

    @Test
    fun `matches frame embedded in data prefix as well`() {
        // Sourcegraph occasionally wraps the error in an SSE data: frame.
        val line = """data: {"message":"context deadline exceeded","type":"completion.process_completion"}"""
        assertTrue(GatewayErrorDetector.isUpstreamTimeoutFrame(line))
    }
}
