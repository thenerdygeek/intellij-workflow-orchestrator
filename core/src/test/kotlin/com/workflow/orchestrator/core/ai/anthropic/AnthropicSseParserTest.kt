package com.workflow.orchestrator.core.ai.anthropic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AnthropicSseParser] — Phase 4a Task 6.
 *
 * Covers:
 *  - text_delta + tool_use accumulation → canonical XML at message_stop
 *  - thinking_delta block-level wrapping (one contiguous <thinking>…</thinking> span)
 *  - event: error → errorClass via AnthropicNativeProtocol.classifyStreamLine
 */
class AnthropicSseParserTest {

    private fun parse(sse: String): Pair<String, AnthropicSseParser.Result> {
        val out = StringBuilder()
        val r = AnthropicSseParser.parse(sse.trimIndent().lineSequence()) { out.append(it) }
        return out.toString() to r
    }

    @Test
    fun `text + tool_use stream emits prose then canonical XML at stop`() {
        val (out, r) = parse(
            """
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Reading."}}
            event: content_block_stop
            data: {"type":"content_block_stop","index":0}
            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"read_file"}}
            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"F.kt\"}"}}
            event: content_block_stop
            data: {"type":"content_block_stop","index":1}
            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}
            event: message_stop
            data: {"type":"message_stop"}
            """,
        )
        assertEquals("tool_use", r.finishReason)
        assertEquals(12, r.usageOutputTokens)
        assertTrue(out.contains("Reading."))
        assertTrue(out.contains("<read_file><path>F.kt</path></read_file>"))
    }

    @Test
    fun `thinking_delta is wrapped in thinking tags`() {
        val (out, _) = parse(
            """
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}
            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me reason"}}
            event: content_block_stop
            data: {"type":"content_block_stop","index":0}
            event: message_stop
            data: {"type":"message_stop"}
            """,
        )
        assertTrue(out.contains("<thinking>Let me reason</thinking>"))
    }

    @Test
    fun `event error sets errorClass`() {
        val (_, r) = parse(
            """
            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}
            """,
        )
        assertNotNull(r.errorClass)
    }
}
