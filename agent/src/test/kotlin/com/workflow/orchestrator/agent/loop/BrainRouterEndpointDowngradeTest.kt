package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.core.ai.SourcegraphCompletionsStreamClient
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.Choice
import com.workflow.orchestrator.core.ai.dto.CompletionStreamRequest
import com.workflow.orchestrator.core.ai.dto.CompletionStreamResult
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Fix C (2026-05-12) — endpoint downgrade on consecutive empties.
 *
 * Probe `format_lab.tools_only_on_stream`: PASS across all Claude models at
 * api-version=9. So `/.api/completions/stream` is a viable text-only fallback
 * when `/.api/llm/chat/completions` repeatedly returns empty. No surveyed agent
 * does this in production; the plugin already has both endpoints wired via
 * `BrainRouter` for the image path, so the downgrade is a small extension.
 *
 * Rule: after 2 consecutive empty responses (no content, no tool calls) on the
 * OpenAI-compat path for text-only turns, the next text-only call should be
 * routed through `/.api/completions/stream` instead. Same model, same token.
 * A non-empty response anywhere resets the counter.
 *
 * Image-bearing turns are unchanged (they already use `/stream`).
 */
class BrainRouterEndpointDowngradeTest {

    @TempDir lateinit var tempDir: Path

    private fun emptyResponse() = ApiResult.Success(
        ChatCompletionResponse(
            id = "empty",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = null, toolCalls = null),
                    finishReason = "stop",
                ),
            ),
            usage = null,
        )
    )

    private fun textResponse(text: String) = ApiResult.Success(
        ChatCompletionResponse(
            id = "ok",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = text),
                    finishReason = "stop",
                ),
            ),
            usage = null,
        )
    )

    @Test
    fun `after two empties on chat-completions, third text-only call downgrades to stream endpoint`() = runBlocking {
        val openAi = SequenceFakeBrain(listOf(emptyResponse(), emptyResponse(), textResponse("should not be reached")))
        val stream = FakeStreamClient(text = "recovered via stream endpoint")
        val router = BrainRouter(openAi, stream, AttachmentStore(tempDir), { "test::model" }, null)

        // Call 1 — empty, no downgrade yet
        val r1 = router.chatStream(
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            tools = emptyList(),
            maxTokens = 100,
            onChunk = {},
        )
        assertTrue(r1 is ApiResult.Success, "call 1 must succeed (empty body, still 200)")
        assertEquals(1, openAi.callCount, "call 1 stays on chat/completions")
        assertEquals(0, stream.callCount)

        // Call 2 — empty again, counter at 2 but downgrade fires NEXT call
        val r2 = router.chatStream(
            messages = listOf(ChatMessage(role = "user", content = "again")),
            tools = emptyList(),
            maxTokens = 100,
            onChunk = {},
        )
        assertTrue(r2 is ApiResult.Success)
        assertEquals(2, openAi.callCount, "call 2 still on chat/completions")
        assertEquals(0, stream.callCount)

        // Call 3 — counter ≥ 2 → DOWNGRADE to /stream
        val r3 = router.chatStream(
            messages = listOf(ChatMessage(role = "user", content = "please work")),
            tools = emptyList(),
            maxTokens = 100,
            onChunk = {},
        )
        assertTrue(r3 is ApiResult.Success, "call 3 must succeed via stream endpoint")
        assertEquals(2, openAi.callCount, "call 3 must NOT hit chat/completions")
        assertEquals(1, stream.callCount, "call 3 must hit /completions/stream")
        val text = (r3 as ApiResult.Success).data.choices.first().message.content
        assertEquals("recovered via stream endpoint", text)
    }

    @Test
    fun `successful response between empties resets the downgrade counter`() = runBlocking {
        val openAi = SequenceFakeBrain(listOf(
            emptyResponse(),         // call 1
            textResponse("ok"),      // call 2 — success, reset
            emptyResponse(),         // call 3 — counter=1
            textResponse("ok again") // call 4 — success
        ))
        val stream = FakeStreamClient(text = "should not be reached")
        val router = BrainRouter(openAi, stream, AttachmentStore(tempDir), { "test::model" }, null)

        repeat(4) {
            router.chatStream(
                messages = listOf(ChatMessage(role = "user", content = "msg")),
                tools = emptyList(),
                maxTokens = 100,
                onChunk = {},
            )
        }

        assertEquals(4, openAi.callCount, "all 4 calls must stay on chat/completions — counter reset between empties")
        assertEquals(0, stream.callCount, "stream endpoint never engaged when empties are non-consecutive")
    }

    @Test
    fun `successful response on stream downgrade resets the counter`() = runBlocking {
        val openAi = SequenceFakeBrain(listOf(
            emptyResponse(),         // 1: empty
            emptyResponse(),         // 2: empty (counter=2)
            // 3: downgrade fires → stream returns success → counter resets
            textResponse("ok now"),  // 4: this should stay on chat/completions (counter=0 again)
        ))
        val stream = FakeStreamClient(text = "recovered")
        val router = BrainRouter(openAi, stream, AttachmentStore(tempDir), { "test::model" }, null)

        // Calls 1, 2 — both empty
        router.chatStream(messages = listOf(ChatMessage(role = "user", content = "1")), tools = emptyList(), maxTokens = 100, onChunk = {})
        router.chatStream(messages = listOf(ChatMessage(role = "user", content = "2")), tools = emptyList(), maxTokens = 100, onChunk = {})

        // Call 3 — downgrade
        val r3 = router.chatStream(messages = listOf(ChatMessage(role = "user", content = "3")), tools = emptyList(), maxTokens = 100, onChunk = {})
        assertTrue(r3 is ApiResult.Success)
        assertEquals(1, stream.callCount, "downgrade should fire")

        // Call 4 — counter reset after successful stream recovery
        val r4 = router.chatStream(messages = listOf(ChatMessage(role = "user", content = "4")), tools = emptyList(), maxTokens = 100, onChunk = {})
        assertTrue(r4 is ApiResult.Success)
        assertEquals(3, openAi.callCount, "call 4 must return to chat/completions after the recovery reset")
        assertEquals(1, stream.callCount, "stream endpoint stays at 1 — counter reset")
    }
}

/** Sequential fake — returns each response in order, errors if asked for more. */
internal class SequenceFakeBrain(
    private val responses: List<ApiResult<ChatCompletionResponse>>,
) : com.workflow.orchestrator.core.ai.LlmBrain {
    var callCount = 0
        private set

    override val modelId: String = "fake::test::model"

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        toolChoice: JsonElement?,
    ): ApiResult<ChatCompletionResponse> = next()

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        onChunk: suspend (com.workflow.orchestrator.core.ai.dto.StreamChunk) -> Unit,
    ): ApiResult<ChatCompletionResponse> = next()

    private fun next(): ApiResult<ChatCompletionResponse> {
        check(callCount < responses.size) {
            "SequenceFakeBrain exhausted — wanted call ${callCount + 1} of ${responses.size}"
        }
        return responses[callCount++]
    }

    override fun estimateTokens(text: String): Int = text.length / 4
}
