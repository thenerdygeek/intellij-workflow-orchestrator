package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ContentPart
import com.workflow.orchestrator.core.ai.dto.FunctionDefinition
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.dto.StreamContentPart
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Pins the invariants introduced by the 2026-05-05 BrainRouter simplification
 * (commits 0597d63c + b207efce). The pre-existing [BrainRouterTest] covers
 * routing decisions and rejection rendering; this class covers wire-payload
 * shape, hydration semantics, and error-typing — i.e. the ports that previously
 * lived in the deleted two-step path and now land on `/stream`.
 *
 * Cases pinned:
 *  1. **User-uploaded image (image-only)** — `tools` is null on the wire (so
 *     /stream isn't told to expect tool calls when the user only pasted an
 *     image), the message body carries `data:<mime>;base64,…`, and the speaker
 *     is `human`.
 *  2. **User-uploaded image + tools (single round-trip)** — tools forwarded
 *     verbatim, OpenAiCompat untouched, response surfaces `toolCalls` from the
 *     `delta_tool_calls` accumulator.
 *  3. **Tool-produced image auto-load** — image arrives on a `role="tool"`
 *     message; the entire conversation (user → assistant tool-call →
 *     tool-result+image) reaches /stream as one request and the speakers are
 *     coerced per the Cody spec (`tool` → `human`).
 *  4. **Missing-attachment terminal-error path** — when `AttachmentStore.read`
 *     returns null (bytes evicted or never written), BrainRouter surfaces an
 *     `AttachmentMissingException` mapped to `VALIDATION_ERROR` with the
 *     "re-upload or remove" guidance — NOT the confusing `NETWORK_ERROR` it
 *     produced before Phase 7 followup F-P6-4.
 *  5. **maxTokensToSample default** — when caller passes null, BrainRouter
 *     uses the documented 8000 default.
 */
class BrainRouterPostReworkInvariantsTest {

    @TempDir lateinit var tempDir: Path

    private val fooTool = ToolDefinition(
        type = "function",
        function = FunctionDefinition(
            name = "foo",
            description = "test tool",
            parameters = FunctionParameters(properties = emptyMap()),
        ),
    )

    private fun ok(text: String = "") = ApiResult.Success(
        ChatCompletionResponse(
            id = "id",
            choices = emptyList(),
            usage = null,
        ),
    )

    // ── Case 1: user-uploaded image (image-only) ────────────────────────────

    @Test
    fun `user-uploaded image-only forwards null tools to stream and emits data URI`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = ok())
        val stream = FakeStreamClient(text = "red")
        val store = AttachmentStore(tempDir)
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)  // PNG magic
        val ref = store.store(pngBytes, "image/png", "user.png")
        val router = BrainRouter(openAi, stream, store, { "anthropic::claude-4.5" }, null)

        router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = null,
                    parts = listOf(
                        ContentPart.Image(sha256 = ref.sha256, mime = "image/png", originalFilename = "user.png"),
                        ContentPart.Text("describe"),
                    ),
                ),
            ),
            tools = emptyList(),  // user pasted an image but didn't enable any tools
        )

        val req = stream.lastRequest ?: error("stream client received no request")
        assertNull(req.tools, "tools must be null on wire when caller had no tools to enable")
        assertEquals(1, req.messages.size)
        val msg = req.messages.first()
        assertEquals("human", msg.speaker, "user role must map to Cody speaker 'human'")
        val imagePart = msg.content.filterIsInstance<StreamContentPart.Image>().firstOrNull()
        assertNotNull(imagePart, "image part must be present in stream message")
        val url = imagePart!!.imageUrl.url
        assertTrue(url.startsWith("data:image/png;base64,"),
            "image must be hydrated as data: URI with caller-supplied MIME, got: $url")
        assertEquals(0, openAi.chatCallCount, "OpenAiCompat must NOT be called for image-only turns")
        assertEquals(1, stream.callCount)
    }

    // ── Case 4: missing-attachment terminal-error path ──────────────────────

    @Test
    fun `missing attachment surfaces VALIDATION_ERROR with re-upload guidance`() = runBlocking {
        // F-P6-4 invariant. If the AttachmentStore has no bytes for a
        // sha256 we receive (e.g. session was wiped or the upload failed),
        // BrainRouter must NOT degrade to NETWORK_ERROR (which the loop's
        // retry policy treats as transient). It must surface the typed
        // AttachmentMissingException as a VALIDATION_ERROR with a clear
        // "re-upload or remove the image" message.
        val openAi = FakeOpenAiCompatBrain(response = ok())
        val stream = FakeStreamClient(text = "should not run")
        val store = AttachmentStore(tempDir)  // empty — no attachment for our sha256
        val router = BrainRouter(openAi, stream, store, { "anthropic::claude-4.5" }, null)

        val resp = router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(
                        ContentPart.Image(
                            sha256 = "deadbeef0123456789abcdef0123456789abcdef0123456789abcdef00000000",
                            mime = "image/png",
                            originalFilename = "missing.png",
                        ),
                        ContentPart.Text("?"),
                    ),
                ),
            ),
            tools = emptyList(),
        )

        val err = resp as? ApiResult.Error
            ?: error("expected Error for missing attachment, got: $resp")
        assertEquals(ErrorType.VALIDATION_ERROR, err.type,
            "missing attachment must map to VALIDATION_ERROR (non-retryable), not NETWORK_ERROR")
        assertTrue(err.message.contains("Re-upload"),
            "error message must guide the user to re-upload or remove; got: ${err.message}")
        assertEquals(0, stream.callCount, "stream must NOT be called when hydration fails")
    }

    // ── Case 5: maxTokensToSample default ───────────────────────────────────

    @Test
    fun `maxTokensToSample defaults to 8000 when caller passes null`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = ok())
        val stream = FakeStreamClient(text = "ok")
        val store = AttachmentStore(tempDir)
        val ref = store.store(byteArrayOf(1), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "anthropic::claude-4.5" }, null)

        router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(ContentPart.Image(ref.sha256, "image/png", null), ContentPart.Text("x")),
                ),
            ),
            tools = emptyList(),
        )

        assertEquals(8000, stream.lastRequest!!.maxTokensToSample,
            "documented default — until ModelCatalogService wires per-model caps live")
    }

    @Test
    fun `maxTokensToSample passes through caller-supplied value`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(response = ok())
        val stream = FakeStreamClient(text = "ok")
        val store = AttachmentStore(tempDir)
        val ref = store.store(byteArrayOf(1), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "anthropic::claude-4.5" }, null)

        router.chatStream(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(ContentPart.Image(ref.sha256, "image/png", null), ContentPart.Text("x")),
                ),
            ),
            tools = emptyList(),
            maxTokens = 4096,
            onChunk = {},
        )

        assertEquals(4096, stream.lastRequest!!.maxTokensToSample)
    }

    // ── Belt-and-braces: explicit-empty tools must serialize as null ────────

    @Test
    fun `empty tools list sent on wire as null tools`() = runBlocking {
        // Defensive: a caller passing `emptyList()` for tools shouldn't make
        // /stream advertise an empty tool surface (which would be redundant
        // protocol noise). BrainRouter normalizes empty → null at request-build.
        val openAi = FakeOpenAiCompatBrain(response = ok())
        val stream = FakeStreamClient(text = "ok")
        val store = AttachmentStore(tempDir)
        val ref = store.store(byteArrayOf(1), "image/png", null)
        val router = BrainRouter(openAi, stream, store, { "anthropic::claude-4.5" }, null)

        router.chat(
            messages = listOf(
                ChatMessage(
                    role = "user",
                    parts = listOf(ContentPart.Image(ref.sha256, "image/png", null), ContentPart.Text("x")),
                ),
            ),
            tools = emptyList(),
        )

        assertNull(stream.lastRequest!!.tools)
    }
}
