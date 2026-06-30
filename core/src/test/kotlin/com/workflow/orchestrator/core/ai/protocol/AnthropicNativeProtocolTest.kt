package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.ToolUseContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AnthropicNativeProtocolTest {

    private val p = AnthropicNativeProtocol()

    @Test
    fun `presentTools is null and dialect guard off`() {
        assertNull(p.presentTools(emptyList()))
        assertFalse(p.requiresDialectGuard)
    }

    @Test
    fun `parseToolCalls delegates to XML parse`() {
        val calls = p.parseToolCalls(
            "<read_file><path>x</path></read_file>",
            setOf("read_file"),
            setOf("path"),
        ).filterIsInstance<ToolUseContent>()
        assertEquals("read_file", calls.single().name)
    }

    @Test
    fun `classifyHttpError maps overloaded and rate limit`() {
        assertNotNull(p.classifyHttpError(529, "overloaded"))
        assertNotNull(p.classifyHttpError(429, "slow down"))
    }

    @Test
    fun `classifyHttpError maps context length and validation and auth`() {
        assertNotNull(p.classifyHttpError(413, "request too large"))
        assertNotNull(p.classifyHttpError(400, "bad request"))
        assertNotNull(p.classifyHttpError(401, "unauthorized"))
        assertNotNull(p.classifyHttpError(403, "forbidden"))
    }

    @Test
    fun `classifyHttpError returns null for unmapped status codes`() {
        assertNull(p.classifyHttpError(200, "ok"))
        assertNull(p.classifyHttpError(500, "internal server error"))
        assertNull(p.classifyHttpError(404, "not found"))
    }

    @Test
    fun `classifyStreamLine recognizes overloaded error frame`() {
        assertNotNull(
            p.classifyStreamLine("""{"type":"error","error":{"type":"overloaded_error"}}"""),
        )
    }

    @Test
    fun `classifyStreamLine recognizes rate limit error frame`() {
        assertNotNull(
            p.classifyStreamLine("""{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""),
        )
    }

    @Test
    fun `classifyStreamLine returns null for non-error frames`() {
        assertNull(p.classifyStreamLine("""{"type":"content_block_delta","index":0}"""))
        assertNull(p.classifyStreamLine("""{"type":"message_stop"}"""))
        assertNull(p.classifyStreamLine("not json at all"))
        assertNull(p.classifyStreamLine(""))
    }

    @Test
    fun `classifyStreamLine returns the inner error type string`() {
        val result = p.classifyStreamLine(
            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
        )
        assertEquals("overloaded_error", result)
    }
}
