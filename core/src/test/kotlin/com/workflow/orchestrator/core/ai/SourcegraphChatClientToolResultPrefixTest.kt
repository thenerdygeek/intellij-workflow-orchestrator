package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Reproduction of the "Human: TOOL RESULT:" hallucination chain.
 *
 * The user reported the LLM emitting prose like
 *     `Here you go. Human: TOOL RESULT: <verbatim file content...>`
 * inside its assistant text — both for `read_document` AND for tools that
 * return small code (e.g. `read_file`), so the size of the result is NOT the
 * sole driver. The common root cause is the all-caps `TOOL RESULT:\n` sentinel
 * that `sanitizeMessages` slaps in front of every tool-role message before
 * coercing it to `role="user"`. To a Claude-family model trained on legacy
 * Anthropic-completions transcripts (`\n\nHuman: ...\n\nAssistant: ...`), that
 * sentinel + content shape looks like a turn delimiter, so the model echoes
 * the same shape back in its next reply.
 *
 * This test pins the precondition: the wire body MUST contain "TOOL RESULT:\n"
 * verbatim and the tool-role message MUST have been coerced to "user". When
 * we change the prefix (e.g. drop the all-caps sentinel for a structured
 * `<result tool="...">…</result>` wrapper), this test will start failing
 * and a sibling test asserting the new format takes over.
 */
class SourcegraphChatClientToolResultPrefixTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun buildClient(): SourcegraphChatClient =
        SourcegraphChatClient(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "test-token" },
            model = "test-model",
            httpClientOverride = OkHttpClient.Builder().build(),
        )

    private fun stubMinimalResponse(): MockResponse {
        // Non-streaming response — the simplest path; sendMessageStream still
        // exercises sanitizeMessages identically.
        val ok = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        val body = "data: $ok\n\n"
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    @Test
    fun `tool role message is coerced to user with TOOL RESULT prefix on the wire`() = runTest {
        server.enqueue(stubMinimalResponse())

        val codeFromReadFile = """
            class TokenService(private val cache: TokenCache) {
                fun mint(userId: String): String = cache.put(userId, sign(userId))
            }
        """.trimIndent()

        buildClient().sendMessageStream(
            messages = listOf(
                ChatMessage(role = "user", content = "show me TokenService"),
                ChatMessage(role = "assistant", content = "Reading the file."),
                // This is the message we want to track on the wire:
                ChatMessage(role = "tool", content = codeFromReadFile, toolCallId = "tc_1"),
            ),
            tools = null,
            onChunk = {},
        )

        val recorded = server.takeRequest()
        val wireBody = recorded.body.readUtf8()

        // 1) The sentinel prefix is on the wire verbatim
        assertTrue(
            wireBody.contains("TOOL RESULT:"),
            "Expected the sentinel 'TOOL RESULT:' in the wire body but did not find it. Body sample:\n${wireBody.take(800)}"
        )

        // 2) The tool's verbatim content follows the prefix (the LLM has full source to regurgitate)
        assertTrue(
            wireBody.contains("class TokenService"),
            "Expected the tool's code body to be embedded verbatim under the sentinel."
        )

        // 3) The tool role was coerced to "user" (no "role":"tool" on the wire)
        assertFalse(
            wireBody.contains("\"role\":\"tool\""),
            "Tool role must be coerced to user before the wire — but the body still contains role=tool."
        )
        assertTrue(
            wireBody.contains("\"role\":\"user\""),
            "Expected a user-role message on the wire after coercion."
        )
    }

    @Test
    fun `even tiny code result gets the sentinel - confirms size is not the driver`() = runTest {
        // Same shape as a `read_file` on a tiny config returning ~120 chars.
        server.enqueue(stubMinimalResponse())

        val tinyJson = """{"feature":"auth","enabled":true,"ttl":86400}"""

        buildClient().sendMessageStream(
            messages = listOf(
                ChatMessage(role = "user", content = "what's in config.json?"),
                ChatMessage(role = "assistant", content = "Reading."),
                ChatMessage(role = "tool", content = tinyJson, toolCallId = "tc_2"),
            ),
            tools = null,
            onChunk = {},
        )

        val wireBody = server.takeRequest().body.readUtf8()

        // The whole point: a 45-byte result is also wrapped in the sentinel,
        // proving the prefix-format theory (transcript-style turn marker), not
        // the size theory, is what's driving the hallucination.
        assertTrue(
            wireBody.contains("TOOL RESULT:"),
            "Even a tiny code result gets the sentinel — proves the format is the issue, not size."
        )
        assertTrue(
            wireBody.contains("ttl"),
            "Tiny content is embedded under the sentinel just like a giant document body."
        )
    }

    @Test
    fun `assistant text content is not modified - only tool role gets the prefix`() = runTest {
        server.enqueue(stubMinimalResponse())

        buildClient().sendMessageStream(
            messages = listOf(
                ChatMessage(role = "user", content = "tell me about TOOL RESULT framing"),
                ChatMessage(role = "assistant", content = "Plain assistant prose, no sentinel."),
            ),
            tools = null,
            onChunk = {},
        )

        val wireBody = server.takeRequest().body.readUtf8()

        // The sentinel is *only* added when a tool-role message is coerced —
        // a user mentioning the phrase or an assistant turn does NOT acquire it.
        // (User's own "TOOL RESULT framing" wording is harmless prose.)
        val sentinelOccurrences = "TOOL RESULT:".toRegex().findAll(wireBody).count()
        assertEquals(
            0,
            sentinelOccurrences,
            "Sanitizer should not inject the sentinel into non-tool roles."
        )
    }
}
