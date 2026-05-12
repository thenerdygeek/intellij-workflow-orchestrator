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
    fun `after one empty on chat-completions, second text-only call downgrades to stream endpoint`() = runBlocking {
        // Threshold tightened to 1 in v0.85.12-alpha — the LiteLLM #20347 pattern
        // (stop+empty+no-tools) doesn't deserve a second wasted call on the same
        // dead endpoint. The very next text-only call should switch to /stream.
        val openAi = SequenceFakeBrain(listOf(emptyResponse(), textResponse("should not be reached")))
        val stream = FakeStreamClient(text = "recovered via stream endpoint")
        val router = BrainRouter(openAi, stream, AttachmentStore(tempDir), { "test::model" }, null)

        // Call 1 — empty
        val r1 = router.chatStream(
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            tools = emptyList(),
            maxTokens = 100,
            onChunk = {},
        )
        assertTrue(r1 is ApiResult.Success, "call 1 must succeed (empty body, still 200)")
        assertEquals(1, openAi.callCount, "call 1 stays on chat/completions")
        assertEquals(0, stream.callCount)

        // Call 2 — counter ≥ 1 → DOWNGRADE to /stream
        val r2 = router.chatStream(
            messages = listOf(ChatMessage(role = "user", content = "please work")),
            tools = emptyList(),
            maxTokens = 100,
            onChunk = {},
        )
        assertTrue(r2 is ApiResult.Success, "call 2 must succeed via stream endpoint")
        assertEquals(1, openAi.callCount, "call 2 must NOT hit chat/completions")
        assertEquals(1, stream.callCount, "call 2 must hit /completions/stream")
        val text = (r2 as ApiResult.Success).data.choices.first().message.content
        assertEquals("recovered via stream endpoint", text)
    }

    @Test
    fun `successful response between empties resets the downgrade counter`() = runBlocking {
        val openAi = SequenceFakeBrain(listOf(
            emptyResponse(),         // call 1 — empty, counter=1
            textResponse("ok"),      // call 2 — DOWNGRADE fires (counter≥1), but this fake returns text-only so we need the stream to also return text
            emptyResponse(),         // never reached because the downgrade goes through stream
            textResponse("ok again"),// never reached
        ))
        // The threshold-1 downgrade means call 2 goes through /stream. We need the
        // stream client to return successfully so the counter resets afterward.
        val stream = FakeStreamClient(text = "recovered")
        val router = BrainRouter(openAi, stream, AttachmentStore(tempDir), { "test::model" }, null)

        // Call 1: empty on chat/completions
        router.chatStream(messages = listOf(ChatMessage(role = "user", content = "1")), tools = emptyList(), maxTokens = 100, onChunk = {})
        // Call 2: downgrade → stream returns text → counter resets
        router.chatStream(messages = listOf(ChatMessage(role = "user", content = "2")), tools = emptyList(), maxTokens = 100, onChunk = {})

        assertEquals(1, openAi.callCount, "only the first call hit chat/completions; the second downgraded")
        assertEquals(1, stream.callCount, "downgrade engaged exactly once")

        // Call 3 — counter was reset by successful stream recovery, should go back to chat/completions.
        // (We only seeded openAi with 4 responses; the SequenceFakeBrain has 3 left after call 1.)
        router.chatStream(messages = listOf(ChatMessage(role = "user", content = "3")), tools = emptyList(), maxTokens = 100, onChunk = {})
        assertEquals(2, openAi.callCount, "after counter reset, call 3 goes back to chat/completions")
        assertEquals(1, stream.callCount, "stream stays at 1 — no new downgrade")
    }

    @Test
    fun `image-bearing turns never engage the downgrade counter`() = runBlocking {
        // Image turns always use /stream regardless of counter. They should never
        // touch the counter so they don't influence subsequent text-only routing.
        val openAi = SequenceFakeBrain(listOf(textResponse("text-only ok")))
        val stream = FakeStreamClient(text = "image processed")
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)

        // Image turn — must use stream regardless of empties counter state
        router.chatStream(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(
                        com.workflow.orchestrator.core.ai.dto.ContentPart.Image(ref.sha256, "image/png", null),
                        com.workflow.orchestrator.core.ai.dto.ContentPart.Text("?")
                    ),
                )
            ),
            tools = emptyList(),
            maxTokens = 100,
            onChunk = {},
        )
        assertEquals(1, stream.callCount, "image turn used /stream")
        assertEquals(0, openAi.callCount, "image turn did NOT hit chat/completions")

        // Subsequent text-only turn — counter was never incremented by image turn, so stays on chat/completions
        router.chatStream(
            messages = listOf(ChatMessage(role = "user", content = "follow-up")),
            tools = emptyList(),
            maxTokens = 100,
            onChunk = {},
        )
        assertEquals(1, stream.callCount, "stream stays at 1 — image turn didn't pollute counter")
        assertEquals(1, openAi.callCount, "text-only follow-up goes to chat/completions (counter is 0)")
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
