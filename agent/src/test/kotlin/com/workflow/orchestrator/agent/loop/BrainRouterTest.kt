package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.core.ai.HttpException
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.SourcegraphCompletionsStreamClient
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.Choice
import com.workflow.orchestrator.core.ai.dto.CompletionStreamRequest
import com.workflow.orchestrator.core.ai.dto.CompletionStreamResult
import com.workflow.orchestrator.core.ai.dto.ContentPart
import com.workflow.orchestrator.core.ai.dto.FunctionCall
import com.workflow.orchestrator.core.ai.dto.FunctionDefinition
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.hasImageParts
import com.workflow.orchestrator.core.ai.dto.ToolCall
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Phase 6 of multimodal-agent plan — `BrainRouter` dispatches each turn to the
 * correct brain per the hybrid routing rule:
 *
 * - text-only / text+tools → [com.workflow.orchestrator.core.ai.OpenAiCompatBrain]
 * - image-only             → [SourcegraphCompletionsStreamClient]
 * - image+tools            → two-step workaround (vision-summarize → tools call)
 *
 * Tests in this class were written before the implementation per TDD discipline
 * (Phase 2/3/4 convention): the BrainRouter reference would not compile until
 * the production class lands.
 *
 * Plan-vs-real signature divergences resolved here:
 * - `ChatMessage.role` is `String` (no `Role` enum exists in this codebase)
 * - Tools use `ToolDefinition(type, function = FunctionDefinition(...))`
 * - `ToolCall` requires `id`, `type`, and `function: FunctionCall(name, arguments)`
 * - BrainRouter is in `:agent`, NOT `:core` (DAG violation otherwise — direct
 *   import of `AttachmentStore` from `:agent.session`)
 */
class BrainRouterTest {

    @TempDir lateinit var tempDir: Path

    private val fooTool = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = "foo",
            description = "test tool",
            parameters = FunctionParameters(properties = emptyMap()),
        ),
    )

    private fun fooToolCall(id: String = "call_1") = ToolCall(
        id = id,
        type = "function",
        function = FunctionCall(name = "foo", arguments = "{}"),
    )

    private fun makeOk(text: String, toolCalls: List<ToolCall>? = null) =
        ApiResult.Success(
            ChatCompletionResponse(
                id = "id",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = text, toolCalls = toolCalls),
                        finishReason = if (toolCalls != null) "tool_calls" else "stop",
                    ),
                ),
                usage = null,
            ),
        )

    @Test
    fun `text-only turn routes to OpenAiCompatBrain`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = makeOk("hi"))
        val stream = FakeStreamClient(text = "")
        val router = BrainRouter(openAi, stream, AttachmentStore(tempDir), { "test::model" }, null)
        val resp = router.chat(
            messages = listOf(ChatMessage(role = "user", content = "hello")),
            tools = emptyList(),
        )
        val ok = resp as? ApiResult.Success<ChatCompletionResponse>
            ?: error("expected Success, got: $resp")
        assertEquals("hi", ok.data.choices.first().message.content)
        assertEquals(1, openAi.chatCallCount)
        assertEquals(0, stream.callCount)
    }

    @Test
    fun `text+tools turn routes to OpenAiCompatBrain`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = makeOk("ok", toolCalls = listOf(fooToolCall())))
        val stream = FakeStreamClient(text = "")
        val router = BrainRouter(openAi, stream, AttachmentStore(tempDir), { "test::model" }, null)
        val resp = router.chat(
            messages = listOf(ChatMessage(role = "user", content = "use foo")),
            tools = listOf(fooTool),
        )
        val ok = resp as? ApiResult.Success<ChatCompletionResponse>
            ?: error("expected Success, got: $resp")
        assertEquals(1, ok.data.choices.first().message.toolCalls?.size)
        assertEquals(1, openAi.chatCallCount)
        assertEquals(0, stream.callCount)
    }

    @Test
    fun `image-only turn routes to SourcegraphCompletionsStreamClient`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = makeOk(""))
        val stream = FakeStreamClient(text = "red")
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        val resp = router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = null,
                    parts = listOf(
                        ContentPart.Image(sha256 = ref.sha256, mime = "image/png", originalFilename = "x.png"),
                        ContentPart.Text("what color?"),
                    ),
                ),
            ),
            tools = emptyList(),
        )
        val ok = resp as? ApiResult.Success<ChatCompletionResponse>
            ?: error("expected Success, got: $resp")
        assertEquals("red", ok.data.choices.first().message.content)
        assertEquals(0, openAi.chatCallCount)
        assertEquals(1, stream.callCount)
    }

    @Test
    fun `image-only turn renders gateway rejection as user-facing assistant message`() = runBlocking {
        // format_lab probe (2026-05-05) found Sourcegraph emits HTTP 200 +
        // event: error for HEIC/HEIF/BMP/TIFF/AVIF/SVG. CompletionStreamResult
        // now carries rejectionReason; BrainRouter must surface it as visible
        // text instead of letting an empty assistant bubble render.
        val openAi = FakeOpenAiCompatBrain(response = makeOk(""))
        val stream = FakeStreamClient(
            text = "",
            rejectionReason = "media type image/heic not supported",
        )
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/heic", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        val resp = router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = null,
                    parts = listOf(
                        ContentPart.Image(sha256 = ref.sha256, mime = "image/heic", originalFilename = "x.heic"),
                        ContentPart.Text("what color?"),
                    ),
                ),
            ),
            tools = emptyList(),
        )
        val ok = resp as? ApiResult.Success<ChatCompletionResponse>
            ?: error("expected Success, got: $resp")
        val content = ok.data.choices.first().message.content!!
        assertTrue(content.contains("Sourcegraph rejected"),
            "Expected rejection prefix, got: $content")
        assertTrue(content.contains("media type image/heic not supported"),
            "Expected gateway reason in message, got: $content")
        assertTrue(content.contains("PNG, JPEG, WebP"),
            "Expected supported-formats hint, got: $content")
    }

    @Test
    fun `image-only turn passes through normal text when gateway accepted`() = runBlocking {
        // Negative control for the rejection test above — when the gateway
        // succeeds normally, no rejection fabrication should happen even
        // though the stream client supports the field.
        val openAi = FakeOpenAiCompatBrain(response = makeOk(""))
        val stream = FakeStreamClient(text = "Red", rejectionReason = null)
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        val resp = router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = null,
                    parts = listOf(
                        ContentPart.Image(sha256 = ref.sha256, mime = "image/png", originalFilename = "x.png"),
                        ContentPart.Text("what color?"),
                    ),
                ),
            ),
            tools = emptyList(),
        )
        val ok = resp as? ApiResult.Success<ChatCompletionResponse>
            ?: error("expected Success, got: $resp")
        assertEquals("Red", ok.data.choices.first().message.content)
    }

    @Test
    fun `image+tools via tool-result origin still routes through stream`() = runBlocking {
        // Tool-origin images (image bytes attached to a role=tool message via
        // jira.download_attachment etc.) must follow the same path as user-pasted
        // images. BrainRouter detects the image via `messages.any { it.hasImageParts() }`
        // — position-independent — and routes the entire conversation through /stream.
        val openAi = FakeOpenAiCompatBrain(response = makeOk("should not be used"))
        val stream = FakeStreamClient(text = "investigating")
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        val resp = router.chat(
            messages = listOf(
                ChatMessage(role = "user", content = "investigate JIRA-1"),
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCall("c1", "function", FunctionCall("download_attachment", "{}")),
                    ),
                ),
                ChatMessage(
                    role = "tool",
                    content = "downloaded ss.png",
                    toolCallId = "c1",
                    parts = listOf(
                        ContentPart.Text("downloaded ss.png"),
                        ContentPart.Image(ref.sha256, "image/png", "ss.png"),
                    ),
                ),
            ),
            tools = listOf(fooTool),
        )
        assertTrue(resp is ApiResult.Success, "expected Success, got: $resp")
        assertEquals(1, stream.callCount, "tool-origin image must hit the stream endpoint")
        assertEquals(0, openAi.chatCallCount, "OpenAiCompat must NOT be called")
        // Verify the entire conversation was forwarded — the stream now handles
        // multi-turn agent contexts natively (no more single-message vision payload).
        val req = stream.lastRequest ?: error("stream client received no request")
        assertEquals(3, req.messages.size,
            "all three turns must be forwarded; got speakers ${req.messages.map { it.speaker }}")
    }

    @Test
    fun `image+tools surfaces gateway rejection same as image-only`() = runBlocking {
        // The rejection-rendering path (added in commit 4) must work for
        // image+tools turns too — when an unsupported MIME is paired with
        // tools, the user still sees the rejection toast not an empty bubble.
        val openAi = FakeOpenAiCompatBrain(response = makeOk("should not be used"))
        val stream = FakeStreamClient(
            text = "",
            rejectionReason = "media type image/heic not supported",
        )
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/heic", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        val resp = router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(
                        ContentPart.Image(ref.sha256, "image/heic", null),
                        ContentPart.Text("call foo"),
                    ),
                ),
            ),
            tools = listOf(fooTool),
        )
        val ok = resp as? ApiResult.Success<ChatCompletionResponse>
            ?: error("expected Success, got: $resp")
        val content = ok.data.choices.first().message.content!!
        assertTrue(content.contains("Sourcegraph rejected"),
            "rejection rendering must work for tool-bearing turns too")
        assertEquals(0, openAi.chatCallCount, "no fallback to OpenAiCompat on rejection")
    }


    /** ChatStream variant — the path the agent loop calls. */
    @Test
    fun `chatStream text-only routes to OpenAiCompatBrain and forwards onChunk`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = makeOk("hi"))
        val stream = FakeStreamClient(text = "")
        val router = BrainRouter(openAi, stream, AttachmentStore(tempDir), { "test::model" }, null)
        val chunks = mutableListOf<StreamChunk>()
        val resp = router.chatStream(
            messages = listOf(ChatMessage(role = "user", content = "hello")),
            tools = emptyList(),
            maxTokens = 100,
            onChunk = { chunks.add(it) },
        )
        assertTrue(resp is ApiResult.Success)
        assertEquals(1, openAi.chatStreamCallCount)
        assertEquals(0, stream.callCount)
    }

    @Test
    fun `chatStream image-only routes to stream client and forwards delta as text`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = makeOk(""))
        val stream = FakeStreamClient(text = "red", deltas = listOf("re", "d"))
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        val chunks = mutableListOf<StreamChunk>()
        val resp = router.chatStream(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(ContentPart.Image(ref.sha256, "image/png", null), ContentPart.Text("?")),
                ),
            ),
            tools = emptyList(),
            maxTokens = 100,
            onChunk = { chunks.add(it) },
        )
        assertTrue(resp is ApiResult.Success)
        assertEquals(0, openAi.chatStreamCallCount)
        assertEquals(1, stream.callCount)
        // onChunk should have been called for each delta the stream client emitted.
        assertEquals(2, chunks.size)
        assertEquals("re", chunks[0].choices.first().delta.content)
        assertEquals("d", chunks[1].choices.first().delta.content)
    }

}

// ---- Test fakes ----

/**
 * Minimal `LlmBrain` fake. Records call counts so the routing tests can assert
 * which path each message took. Returns a fixed [ApiResult] per call.
 */
internal class FakeOpenAiCompatBrain(
    private val response: ApiResult<ChatCompletionResponse>,
) : LlmBrain {
    var chatCallCount = 0
        private set
    var chatStreamCallCount = 0
        private set
    var lastMessages: List<ChatMessage>? = null
        private set
    var lastTools: List<ToolDefinition>? = null
        private set

    override val modelId: String = "fake::test::model"

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        toolChoice: JsonElement?,
    ): ApiResult<ChatCompletionResponse> {
        chatCallCount++
        lastMessages = messages
        lastTools = tools
        return response
    }

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        onChunk: suspend (StreamChunk) -> Unit,
    ): ApiResult<ChatCompletionResponse> {
        chatStreamCallCount++
        lastMessages = messages
        lastTools = tools
        return response
    }

    override fun estimateTokens(text: String): Int = text.length / 4
}

/**
 * Fake `SourcegraphCompletionsStreamClient`. Subclasses the real type so
 * routing code can hold the production type but the test controls behavior.
 *
 * `ModelCatalogService` is `open` per Phase 3 deviation; we pass
 * `httpClientOverride = OkHttpClient()` so no real HTTP fires (we don't reach
 * that code path because `chat()` is overridden).
 */
internal class FakeStreamClient(
    private val text: String = "",
    private val deltas: List<String> = emptyList(),
    private val throwOnCall: Throwable? = null,
    private val rejectionReason: String? = null,
    private val toolCalls: List<ToolCall> = emptyList(),
) : SourcegraphCompletionsStreamClient(
    baseUrl = "http://stub",
    tokenProvider = { "stub_token" },
    modelCatalogService = StubModelCatalogService(),
    httpClientOverride = okhttp3.OkHttpClient.Builder().build(),
) {
    var callCount = 0
        private set
    var lastRequest: CompletionStreamRequest? = null
        private set

    override suspend fun chat(
        request: CompletionStreamRequest,
        onDelta: suspend (String) -> Unit,
    ): CompletionStreamResult {
        callCount++
        lastRequest = request
        throwOnCall?.let { throw it }
        for (d in deltas) onDelta(d)
        return CompletionStreamResult(
            text = text,
            stopReason = if (toolCalls.isNotEmpty()) "tool_use" else null,
            durationMs = 1L,
            rejectionReason = rejectionReason,
            toolCalls = toolCalls,
        )
    }
}

/** Stub catalog service — only used to satisfy the SourcegraphCompletionsStreamClient ctor. */
private class StubModelCatalogService : com.workflow.orchestrator.core.ai.ModelCatalogService(
    baseUrl = "http://stub",
    tokenProvider = { "stub_token" },
    httpClientOverride = okhttp3.OkHttpClient.Builder().build(),
) {
    override fun getLatestStreamApiVersion(): Int = 8
}
