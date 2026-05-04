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
import com.workflow.orchestrator.core.ai.dto.StreamContentPart
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
    fun `image+tools triggers two-step workaround and badge`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = makeOk("tool result", toolCalls = listOf(fooToolCall())))
        val stream = FakeStreamClient(text = "image shows a red circle")
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        var badgeFired = false
        val router = BrainRouter(openAi, stream, store, { "test::model" }) { badgeFired = true }
        val resp = router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(
                        ContentPart.Image(sha256 = ref.sha256, mime = "image/png", originalFilename = null),
                        ContentPart.Text("call the tool"),
                    ),
                ),
            ),
            tools = listOf(fooTool),
        )
        assertTrue(resp is ApiResult.Success, "expected Success, got: $resp")
        assertEquals(1, stream.callCount) // step 1: vision-summarize
        assertEquals(1, openAi.chatCallCount) // step 2: tools call
        assertTrue(badgeFired, "onAnalyzedImageBadge should fire after a successful two-step")
    }

    @Test
    fun `two-step workaround triggers when image arrives via tool result, not user message`() = runBlocking {
        // Phase 7 Task 7.2 — tool-origin images must follow the same two-step path
        // as user-pasted images. BrainRouter routes via `messages.any { it.hasImageParts() }`
        // (position-independent), so a `role="tool"` message carrying ContentPart.Image
        // (the shape produced by Phase 1's ApiMessage.toChatMessage()) must trigger
        // step 1 on the stream client and step 2 on OpenAiCompat with the image
        // already swapped to a text description.
        val openAi = FakeOpenAiCompatBrain(response = makeOk("invoking foo", toolCalls = listOf(fooToolCall())))
        val stream = FakeStreamClient(text = "image shows a red error dialog")
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        var badgeFired = false
        val router = BrainRouter(openAi, stream, store, { "test::model" }) { badgeFired = true }
        val resp = router.chat(
            messages = listOf(
                ChatMessage(role = "user", content = "investigate JIRA-1"),
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ToolCall(
                            id = "c1",
                            type = "function",
                            function = FunctionCall(name = "download_attachment", arguments = "{}"),
                        ),
                    ),
                ),
                ChatMessage(
                    role = "tool",
                    content = "downloaded ss.png",
                    toolCallId = "c1",
                    parts = listOf(
                        ContentPart.Text("downloaded ss.png"),
                        ContentPart.Image(sha256 = ref.sha256, mime = "image/png", originalFilename = "ss.png"),
                    ),
                ),
            ),
            tools = listOf(fooTool),
        )
        assertTrue(resp is ApiResult.Success, "expected Success, got: $resp")
        // Step 1: vision-summarize ran on the stream client.
        assertEquals(1, stream.callCount, "step 1 must hit stream client for tool-origin image")
        // Step 2: tools call ran on OpenAiCompat.
        assertEquals(1, openAi.chatCallCount, "step 2 must hit OpenAiCompat after vision-summarize")
        // Step 2 must have received the image already swapped to a text description —
        // no message in the rebuilt list still carries a ContentPart.Image.
        val step2Messages = openAi.lastMessages
            ?: error("OpenAiCompat received no messages")
        assertTrue(
            step2Messages.none { it.hasImageParts() },
            "step 2 messages must have all images stripped to text descriptions; got: $step2Messages",
        )
        // Badge must fire after a successful two-step regardless of image origin.
        assertTrue(badgeFired, "onAnalyzedImageBadge should fire after a successful tool-origin two-step")
    }

    @Test
    fun `step-2 framing of vision description is authoritative not weak metadata`() = runBlocking {
        // Regression guard: the original framing was `[image description: …]` which
        // is weak enough that the step-2 model still triggered its trained
        // "I can't analyze image files directly" tool-use refusal (Windows logs
        // 2026-05-04). The framing must establish the description IS the model's
        // perception of the image, not metadata about it. This test pins the
        // load-bearing phrases — anyone weakening them will break this test.
        val openAi = FakeOpenAiCompatBrain(response = makeOk("ok", toolCalls = listOf(fooToolCall())))
        val visionDescription = "A flowchart showing three boxes labeled A, B, C with arrows."
        val stream = FakeStreamClient(text = visionDescription)
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(
                        ContentPart.Image(sha256 = ref.sha256, mime = "image/png", originalFilename = null),
                        ContentPart.Text("describe and call foo"),
                    ),
                ),
            ),
            tools = listOf(fooTool),
        )
        val step2Messages = openAi.lastMessages ?: error("OpenAiCompat received no messages")
        val combined = step2Messages.joinToString("\n") { it.content ?: "" }
        // Authoritative framing — must establish the description as authoritative perception.
        assertTrue(
            combined.contains("VISION ANALYSIS", ignoreCase = false),
            "step-2 framing must label the vision output as VISION ANALYSIS to discourage refusal; got: $combined"
        )
        assertTrue(
            combined.contains("actual content of the attached image", ignoreCase = false),
            "step-2 framing must say the description IS the image content, not just metadata"
        )
        assertTrue(
            combined.contains("Do NOT say you cannot view or analyze images"),
            "step-2 framing must explicitly forbid the canned refusal"
        )
        // The actual description must still be embedded.
        assertTrue(combined.contains(visionDescription), "step-2 must carry the verbatim description")
    }

    @Test
    fun `step-1 vision prompt is positive framing not double-negative`() = runBlocking {
        // Regression guard: the v0.83.57 prompt used "Do NOT refuse, hedge, or
        // say you cannot see the image" double-negatives that empirically
        // (Windows logs 2026-05-05) caused the Cody endpoint to return 0-char
        // responses — likely a safety/content-filter path that suppresses
        // output rather than emitting an explicit refusal we could classify.
        // Positive framing avoids the trigger. This test pins both the new
        // positive directive AND the absence of "Do NOT refuse" so a future
        // commit reverting to the heavier wording fails this test.
        val openAi = FakeOpenAiCompatBrain(response = makeOk("ok", toolCalls = listOf(fooToolCall())))
        val stream = FakeStreamClient(text = "a screenshot of an error dialog")
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(
                        ContentPart.Image(sha256 = ref.sha256, mime = "image/png", originalFilename = null),
                        ContentPart.Text("call foo"),
                    ),
                ),
            ),
            tools = listOf(fooTool),
        )
        val step1Request = stream.lastRequest ?: error("stream client received no request")
        val step1Prompt = step1Request.messages.flatMap { it.content }
            .filterIsInstance<StreamContentPart.Text>()
            .joinToString("\n") { it.text }
        assertTrue(
            step1Prompt.contains("Describe what you see in this image", ignoreCase = false),
            "step-1 prompt must use positive framing; got: $step1Prompt"
        )
        assertFalse(
            step1Prompt.contains("Do NOT refuse", ignoreCase = false),
            "step-1 prompt must NOT use 'Do NOT refuse' double-negative — empirically caused 0-char responses"
        )
    }

    @Test
    fun `image+tools with empty step-1 description aborts before step 2`() = runBlocking {
        // Regression guard: when the Cody endpoint returns 200 OK but no text
        // events (silent safety-filter), step 1's parsed description is "".
        // Pre-fix, this empty string flowed into step 2's framing as
        // [VISION ANALYSIS]\n\n[END VISION ANALYSIS] and the step-2 model
        // honestly reported "vision analysis came back empty" — exactly what
        // Windows users saw 2026-05-05. Post-fix, empty/blank step-1 output
        // is treated as a distinct VisionResult.Empty and aborts step 2 with
        // a user-visible message.
        val openAi = FakeOpenAiCompatBrain(response = makeOk("should not be used"))
        val stream = FakeStreamClient(text = "")  // <-- the smoking-gun condition
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        val resp = router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(
                        ContentPart.Image(sha256 = ref.sha256, mime = "image/png", originalFilename = null),
                        ContentPart.Text("call the tool"),
                    ),
                ),
            ),
            tools = listOf(fooTool),
        )
        // Step 1 ran; step 2 must NOT have fired.
        assertEquals(1, stream.callCount, "step 1 must have been called once")
        assertEquals(0, openAi.chatCallCount, "step 2 must NOT fire when step 1 returned empty")
        val ok = resp as? ApiResult.Success<ChatCompletionResponse>
            ?: error("expected Success synthetic response, got: $resp")
        val msg = ok.data.choices.first().message.content ?: ""
        assertTrue(
            msg.contains("returned no description") || msg.contains("filtered or unreadable"),
            "synthetic abort message must explain the empty-output cause; got: $msg"
        )
        // Pin the abort id so observability tooling can distinguish empty from abstain/fail.
        assertEquals("router-step1-empty", ok.data.id)
    }

    @Test
    fun `image+tools with whitespace-only step-1 description also aborts before step 2`() = runBlocking {
        // Whitespace-only output is functionally equivalent to empty — step 2's
        // framing would be [VISION ANALYSIS]\n   \n[END] which is just as useless.
        // isBlank() catches both cases.
        val openAi = FakeOpenAiCompatBrain(response = makeOk("should not be used"))
        val stream = FakeStreamClient(text = "   \n  \t  ")
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(
                        ContentPart.Image(sha256 = ref.sha256, mime = "image/png", originalFilename = null),
                        ContentPart.Text("call foo"),
                    ),
                ),
            ),
            tools = listOf(fooTool),
        )
        assertEquals(0, openAi.chatCallCount, "whitespace-only description must also abort step 2")
    }

    @Test
    fun `image+tools with abstaining step-1 description aborts before step 2`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = makeOk("should not be used"))
        val stream = FakeStreamClient(text = "I cannot see this image clearly.")
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        val resp = router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(
                        ContentPart.Image(sha256 = ref.sha256, mime = "image/png", originalFilename = null),
                        ContentPart.Text("call the tool"),
                    ),
                ),
            ),
            tools = listOf(fooTool),
        )
        // Step 1 ran; step 2 did NOT
        assertEquals(1, stream.callCount)
        assertEquals(0, openAi.chatCallCount)
        val ok = resp as? ApiResult.Success<ChatCompletionResponse>
            ?: error("expected Success, got: $resp")
        assertTrue(ok.data.choices.first().message.content!!.contains("couldn't analyze", ignoreCase = true))
    }

    @Test
    fun `image+tools with step-1 HTTP 500 surfaces error toast`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = makeOk("should not be used"))
        val stream = FakeStreamClient(throwOnCall = HttpException(500, "server error"))
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        val resp = router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(ContentPart.Image(ref.sha256, "image/png", null), ContentPart.Text("x")),
                ),
            ),
            tools = listOf(fooTool),
        )
        assertEquals(0, openAi.chatCallCount)
        val ok = resp as? ApiResult.Success<ChatCompletionResponse>
            ?: error("expected Success, got: $resp")
        assertTrue(ok.data.choices.first().message.content!!.contains("Image analysis failed", ignoreCase = true))
    }

    @Test
    fun `image+tools with step-1 HTTP 401 surfaces error toast`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = makeOk("should not be used"))
        val stream = FakeStreamClient(throwOnCall = HttpException(401, "unauthorized"))
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        val resp = router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(ContentPart.Image(ref.sha256, "image/png", null), ContentPart.Text("x")),
                ),
            ),
            tools = listOf(fooTool),
        )
        assertEquals(0, openAi.chatCallCount)
        val ok = resp as? ApiResult.Success<ChatCompletionResponse>
            ?: error("expected Success, got: $resp")
        assertTrue(ok.data.choices.first().message.content!!.contains("Image analysis failed", ignoreCase = true))
    }

    @Test
    fun `image+tools with step-1 HTTP 413 surfaces error toast`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = makeOk("should not be used"))
        val stream = FakeStreamClient(throwOnCall = HttpException(413, "payload too large"))
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        val resp = router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(ContentPart.Image(ref.sha256, "image/png", null), ContentPart.Text("x")),
                ),
            ),
            tools = listOf(fooTool),
        )
        assertEquals(0, openAi.chatCallCount)
        val ok = resp as? ApiResult.Success<ChatCompletionResponse>
            ?: error("expected Success, got: $resp")
        assertTrue(ok.data.choices.first().message.content!!.contains("Image analysis failed", ignoreCase = true))
    }

    @Test
    fun `image+tools with step-1 HTTP 429 surfaces error toast`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = makeOk("should not be used"))
        val stream = FakeStreamClient(throwOnCall = HttpException(429, "rate limited"))
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
        val resp = router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(ContentPart.Image(ref.sha256, "image/png", null), ContentPart.Text("x")),
                ),
            ),
            tools = listOf(fooTool),
        )
        assertEquals(0, openAi.chatCallCount)
        val ok = resp as? ApiResult.Success<ChatCompletionResponse>
            ?: error("expected Success, got: $resp")
        assertTrue(ok.data.choices.first().message.content!!.contains("Image analysis failed", ignoreCase = true))
    }

    /** Pin every entry in `ABSTENTION_PHRASES` so a future edit to the list can't silently regress. */
    @Test
    fun `every abstention phrase aborts step 2`() = runBlocking {
        val phrases = listOf(
            "I cannot see this image",
            "I can't see what's in the image",
            "I don't see anything",
            "no image was provided",
            "unable to view this picture",
            "cannot view the attached file",
            "can't view your image",
            "I'm unable to process this image",
        )
        for (phrase in phrases) {
            val openAi = FakeOpenAiCompatBrain(response = makeOk("should not be used"))
            val stream = FakeStreamClient(text = phrase)
            val store = AttachmentStore(tempDir)
            val ref = store.store(phrase.toByteArray(), "image/png", null)
            val router = BrainRouter(openAi, stream, store, { "test::model" }, null)
            val resp = router.chat(
                messages = listOf(
                    ChatMessage(
                        role = "user",
                        parts = listOf(ContentPart.Image(ref.sha256, "image/png", null), ContentPart.Text("x")),
                    ),
                ),
                tools = listOf(fooTool),
            )
            assertEquals(0, openAi.chatCallCount, "step 2 must NOT run for phrase: '$phrase'")
            val ok = resp as? ApiResult.Success<ChatCompletionResponse>
                ?: error("expected Success for phrase '$phrase', got: $resp")
            assertTrue(
                ok.data.choices.first().message.content!!.contains("couldn't analyze", ignoreCase = true),
                "abort message expected for phrase: '$phrase'",
            )
        }
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
        return CompletionStreamResult(text = text, stopReason = null, durationMs = 1L)
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
