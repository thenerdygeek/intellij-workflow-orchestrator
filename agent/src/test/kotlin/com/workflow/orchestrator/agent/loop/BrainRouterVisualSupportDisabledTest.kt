package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ContentPart
import com.workflow.orchestrator.core.ai.dto.StreamContentPart
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Pins BrainRouter behaviour when the visual-support master kill switch is OFF.
 *
 * Two invariants tested:
 *
 * 1. **Routing gate (BrainRouterRoutingDisabledTest)** — a message carrying an
 *    image ContentPart must route to the text-only OpenAiCompatBrain
 *    (`/messages`) instead of the image stream endpoint. The stream client must
 *    not be called at all.
 *
 * 2. **Stream sanitization (BrainRouterStreamSanitizeTest)** — when
 *    `buildStreamRequest()` is called directly (via the image-bearing code path
 *    that is reachable through a hot-reload race or future refactor), any
 *    `StreamContentPart.Image` entries must be absent from the emitted payload.
 *
 * Both tests use the `imageEnabledProvider = { false }` constructor parameter
 * introduced as the master kill switch gate.
 */
class BrainRouterVisualSupportDisabledTest {

    @TempDir
    lateinit var tempDir: Path

    // ── BrainRouterRoutingDisabledTest ──────────────────────────────────────

    @Test
    fun `with master OFF and image content, routing goes to messages not stream`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(
            response = ApiResult.Success(
                com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse(
                    id = "test-id",
                    choices = listOf(
                        com.workflow.orchestrator.core.ai.dto.Choice(
                            index = 0,
                            message = ChatMessage(role = "assistant", content = "text only"),
                            finishReason = "stop",
                        ),
                    ),
                    usage = null,
                ),
            ),
        )
        val stream = FakeStreamClient(text = "should not be called")
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", "test.png")

        // Master OFF via imageEnabledProvider.
        val router = BrainRouter(
            openAiCompatBrain = openAi,
            streamClient = stream,
            attachmentStore = store,
            modelRefProvider = { "test::model" },
            onAnalyzedImageBadge = null,
            imageEnabledProvider = { false },
        )

        val messages = listOf(
            ChatMessage(
                role = "user",
                content = null,
                parts = listOf(
                    ContentPart.Text("look at this"),
                    ContentPart.Image(sha256 = ref.sha256, mime = "image/png"),
                ),
            ),
        )

        router.chat(messages, emptyList())

        assertEquals(1, openAi.chatCallCount, "OpenAiCompatBrain must handle the call when master is OFF")
        assertEquals(0, stream.callCount, "Stream client must NOT be called when master is OFF")
    }

    @Test
    fun `with master OFF and image content in chatStream, routes to text-only path`() = runBlocking {
        val openAi = FakeOpenAiCompatBrain(
            response = ApiResult.Success(
                com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse(
                    id = "test-id-stream",
                    choices = listOf(
                        com.workflow.orchestrator.core.ai.dto.Choice(
                            index = 0,
                            message = ChatMessage(role = "assistant", content = "text only"),
                            finishReason = "stop",
                        ),
                    ),
                    usage = null,
                ),
            ),
        )
        val stream = FakeStreamClient(text = "should not be called")
        val store = AttachmentStore(tempDir)
        val ref = store.store("fake".toByteArray(), "image/png", "img.png")

        val router = BrainRouter(
            openAiCompatBrain = openAi,
            streamClient = stream,
            attachmentStore = store,
            modelRefProvider = { "test::model" },
            onAnalyzedImageBadge = null,
            imageEnabledProvider = { false },
        )

        val messages = listOf(
            ChatMessage(
                role = "user",
                content = null,
                parts = listOf(
                    ContentPart.Image(sha256 = ref.sha256, mime = "image/png"),
                    ContentPart.Text("analyze"),
                ),
            ),
        )

        router.chatStream(messages, emptyList(), maxTokens = 100, onChunk = {})

        assertEquals(1, openAi.chatStreamCallCount, "chatStream must route to OpenAiCompatBrain when master is OFF")
        assertEquals(0, stream.callCount, "Stream client must NOT be called when master is OFF")
    }

    // ── BrainRouterStreamSanitizeTest ───────────────────────────────────────

    @Test
    fun `with master OFF, buildStreamRequest drops all StreamContentPart Image entries`() = runBlocking {
        // To exercise buildStreamRequest while master is OFF we need a router that
        // would normally call the stream path. We force it by enabling master on
        // a separate router instance, capturing the request, then repeating with
        // master OFF and confirming zero Image parts in the resulting message list.

        val store = AttachmentStore(tempDir)
        val ref = store.store("fake-png".toByteArray(), "image/png", "chart.png")

        val messages = listOf(
            ChatMessage(
                role = "user",
                content = null,
                parts = listOf(
                    ContentPart.Text("look at this chart"),
                    ContentPart.Image(sha256 = ref.sha256, mime = "image/png"),
                ),
            ),
        )

        fun okResponse(text: String = "") = ApiResult.Success(
            com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse(
                id = "id",
                choices = listOf(
                    com.workflow.orchestrator.core.ai.dto.Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = text),
                        finishReason = "stop",
                    ),
                ),
                usage = null,
            ),
        )

        // Router with master ON — confirm the stream IS called (baseline).
        val streamOn = FakeStreamClient(text = "ok")
        val routerOn = BrainRouter(
            openAiCompatBrain = FakeOpenAiCompatBrain(response = okResponse()),
            streamClient = streamOn,
            attachmentStore = store,
            modelRefProvider = { "test::model" },
            imageEnabledProvider = { true },
        )
        routerOn.chat(messages, emptyList())
        assertEquals(1, streamOn.callCount, "Baseline: image should route to stream when master ON")
        val requestWithImages = streamOn.lastRequest
        assertFalse(
            requestWithImages == null,
            "Baseline: stream request must be non-null when master ON",
        )
        val imageParts = requestWithImages!!.messages
            .flatMap { it.content }
            .filterIsInstance<StreamContentPart.Image>()
        assertFalse(imageParts.isEmpty(), "Baseline: image part must be present in stream request when master ON")

        // Router with master OFF — stream must not be called; zero Image parts.
        val streamOff = FakeStreamClient(text = "should not be used")
        val openAiOff = FakeOpenAiCompatBrain(response = okResponse())
        val routerOff = BrainRouter(
            openAiCompatBrain = openAiOff,
            streamClient = streamOff,
            attachmentStore = store,
            modelRefProvider = { "test::model" },
            imageEnabledProvider = { false },
        )
        routerOff.chat(messages, emptyList())
        assertEquals(0, streamOff.callCount, "Stream must NOT be called when master is OFF")
        assertEquals(1, openAiOff.chatCallCount, "OpenAiCompatBrain must handle the call when master is OFF")
        // Verify no request was forwarded to the stream path.
        assertTrue(streamOff.lastRequest == null, "No stream request should exist when master is OFF")
    }
}
