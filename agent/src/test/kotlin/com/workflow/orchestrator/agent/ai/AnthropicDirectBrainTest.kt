package com.workflow.orchestrator.agent.ai

import com.workflow.orchestrator.core.ai.anthropic.AnthropicHttpTransport
import com.workflow.orchestrator.core.ai.anthropic.AnthropicRequest
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AnthropicDirectBrain] — Phase 4a Task 9.
 *
 * Verifies:
 * - temperature setter is a no-op (Anthropic 400s on sampling params)
 * - null maxTokens falls back to the catalog max-output for the model
 * - request carries no sampling params; thinking block present when enabled
 * - tool_use SSE streams produce canonical XML through onChunk
 */
class AnthropicDirectBrainTest {

    private val http = mockk<AnthropicHttpTransport>()
    private val reqSlot = slot<AnthropicRequest>()

    private fun brain(model: String = "claude-opus-4-8", thinking: Boolean = true) =
        AnthropicDirectBrain(model, http, mockk(relaxed = true), { thinking }, { "high" })

    @BeforeEach
    fun stub() {
        coEvery { http.postStream(capture(reqSlot), any()) } returns ApiResult.Success(Unit)
    }

    @Test
    fun `temperature setter is a no-op`() {
        val b = brain()
        b.temperature = 1.0
        assertEquals(0.0, b.temperature)
    }

    @Test
    fun `null maxTokens falls back to model max output`() = runBlocking {
        brain("claude-sonnet-4-6").chatStream(listOf(ChatMessage("user", "x")), null, null) {}
        assertEquals(128_000, reqSlot.captured.maxTokens)
    }

    @Test
    fun `request carries no sampling params and a thinking block when enabled`() = runBlocking {
        brain(thinking = true).chatStream(listOf(ChatMessage("user", "x")), null, 4096) {}
        val json = Json.encodeToString(reqSlot.captured)
        listOf("temperature", "top_p", "top_k", "budget_tokens").forEach { param ->
            assertFalse(json.contains(param), "Request must not contain sampling param: $param")
        }
        assertEquals("summarized", reqSlot.captured.thinking!!.display)
    }

    @Test
    fun `tool_use streams back as canonical XML through onChunk`() = runBlocking {
        coEvery { http.postStream(any(), any()) } answers {
            val onLine = secondArg<(String) -> Unit>()
            sseToolUseFixture().lines().forEach(onLine)
            ApiResult.Success(Unit)
        }
        val sb = StringBuilder()
        brain().chatStream(listOf(ChatMessage("user", "x")), null, 4096) { c: StreamChunk ->
            c.choices.firstOrNull()?.delta?.content?.let(sb::append)
        }
        assertTrue(
            sb.contains("<read_file><path>F.kt</path></read_file>"),
            "Expected canonical XML for read_file tool call in: $sb"
        )
    }

    // ── SSE fixture ────────────────────────────────────────────────────────────

    /**
     * Minimal Anthropic SSE stream that contains a `read_file` tool-use block.
     * Mirrors the Task-6 AnthropicSseParserTest fixture format.
     */
    private fun sseToolUseFixture(): String = """
        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu_1","name":"read_file"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"F.kt\"}"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":8}}

        event: message_stop
        data: {"type":"message_stop"}

    """.trimIndent()
}
