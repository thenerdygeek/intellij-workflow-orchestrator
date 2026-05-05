package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.CompletionStreamRequest
import com.workflow.orchestrator.core.ai.dto.ImageUrl
import com.workflow.orchestrator.core.ai.dto.StreamContentPart
import com.workflow.orchestrator.core.ai.dto.StreamMessage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration-style tests for [SourcegraphCompletionsStreamClient] using
 * [MockWebServer]. Verifies the wire layer:
 * - URL routing through `/.api/completions/stream?api-version=N` with N from
 *   [ModelCatalogService.getLatestStreamApiVersion]
 * - Cody-shape body assembly (`speaker`, `maxTokensToSample`, `image_url` parts)
 * - SSE response parsing → `text`, `onDelta()` callback firing
 * - Auth scheme: `Authorization: token <sgp_...>` (NOT `Bearer`)
 * - HTTP non-2xx → typed [HttpException]
 *
 * Construction matches the Sourcegraph isolation pattern (`SourcegraphChatClient`
 * + `ModelCatalogService`): the client accepts an injectable `OkHttpClient`
 * instead of going through `HttpClientFactory.clientFor()` — see
 * `project_sourcegraph_isolation.md`.
 */
class SourcegraphCompletionsStreamClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SourcegraphCompletionsStreamClient
    private lateinit var fakeCatalog: FakeModelCatalogService

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        fakeCatalog = FakeModelCatalogService(latestApiVersion = 8)
        client = SourcegraphCompletionsStreamClient(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "sgp_test" },
            modelCatalogService = fakeCatalog,
            httpClientOverride = OkHttpClient.Builder().build()
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `chat assembles deltaText from canonical Cody SSE response`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: completion\ndata: {\"deltaText\":\"red\"}\n\nevent: done\ndata: {}\n")
        )

        val req = CompletionStreamRequest(
            model = "anthropic::2024-10-22::claude-sonnet-4-5-latest",
            messages = listOf(
                StreamMessage(
                    speaker = "human",
                    content = listOf(
                        StreamContentPart.Image(ImageUrl("data:image/png;base64,xxx")),
                        StreamContentPart.Text("What color?")
                    )
                )
            ),
            maxTokensToSample = 1000
        )
        val result = client.chat(req)
        assertEquals("red", result.text)
    }

    @Test
    fun `chat URL contains api-version from ModelCatalogService`() = runBlocking {
        fakeCatalog.latestApiVersion = 9
        server.enqueue(MockResponse().setBody("event: done\ndata: {}\n"))
        val req = CompletionStreamRequest(model = "x", messages = emptyList(), maxTokensToSample = 1)
        client.chat(req)
        val recorded = server.takeRequest()
        assertTrue(
            recorded.path!!.contains("api-version=9"),
            "Expected api-version=9 in URL, got ${recorded.path}"
        )
    }

    @Test
    fun `chat URL targets stream completions path`() = runBlocking {
        server.enqueue(MockResponse().setBody("event: done\ndata: {}\n"))
        val req = CompletionStreamRequest(model = "x", messages = emptyList(), maxTokensToSample = 1)
        client.chat(req)
        val recorded = server.takeRequest()
        assertTrue(
            recorded.path!!.startsWith("/.api/completions/stream"),
            "Expected /.api/completions/stream path, got ${recorded.path}"
        )
    }

    @Test
    fun `chat sends Accept text event-stream header`() = runBlocking {
        server.enqueue(MockResponse().setBody("event: done\ndata: {}\n"))
        val req = CompletionStreamRequest(model = "x", messages = emptyList(), maxTokensToSample = 1)
        client.chat(req)
        val recorded = server.takeRequest()
        assertNotNull(recorded.getHeader("Accept"))
        assertTrue(recorded.getHeader("Accept")!!.contains("text/event-stream"))
    }

    @Test
    fun `chat onDelta callback fires for each text delta`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                "event: completion\ndata: {\"deltaText\":\"a\"}\n\n" +
                    "event: completion\ndata: {\"deltaText\":\"b\"}\n\n" +
                    "event: done\ndata: {}\n"
            )
        )
        val deltas = mutableListOf<String>()
        val req = CompletionStreamRequest(model = "x", messages = emptyList(), maxTokensToSample = 1)
        client.chat(req) { delta -> deltas.add(delta) }
        assertEquals(listOf("a", "b"), deltas)
    }

    @Test
    fun `chat onDelta fires only the new tail for cumulative completion frames`() = runBlocking {
        // api-version 1 cumulative shape: server sends growing full text; client must
        // diff against accumulated and surface only the new tail to onDelta.
        server.enqueue(
            MockResponse().setBody(
                "event: completion\ndata: {\"completion\":\"first\"}\n\n" +
                    "event: completion\ndata: {\"completion\":\"first second\"}\n\n" +
                    "event: done\ndata: {}\n"
            )
        )
        val deltas = mutableListOf<String>()
        val req = CompletionStreamRequest(model = "x", messages = emptyList(), maxTokensToSample = 1)
        val result = client.chat(req) { delta -> deltas.add(delta) }
        assertEquals(listOf("first", " second"), deltas)
        assertEquals("first second", result.text)
    }

    @Test
    fun `chat throws HttpException on HTTP 401`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        val req = CompletionStreamRequest(model = "x", messages = emptyList(), maxTokensToSample = 1)
        val ex = assertThrows(HttpException::class.java) {
            runBlocking { client.chat(req) }
        }
        assertEquals(401, ex.statusCode)
    }

    @Test
    fun `chat body uses Cody-shape field names speaker and maxTokensToSample`() = runBlocking {
        server.enqueue(MockResponse().setBody("event: done\ndata: {}\n"))
        val req = CompletionStreamRequest(
            model = "test-model",
            messages = listOf(
                StreamMessage(
                    speaker = "human",
                    content = listOf(StreamContentPart.Text("hello"))
                )
            ),
            maxTokensToSample = 4096
        )
        client.chat(req)
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        // Wire shape MUST use Cody field names, not OpenAI ones.
        assertTrue(body.contains("\"speaker\":\"human\""), "Body must use 'speaker' (not 'role'): $body")
        assertTrue(body.contains("\"maxTokensToSample\":4096"), "Body must use 'maxTokensToSample' (not 'max_tokens'): $body")
        // Type discriminator on content parts is the Cody shape too.
        assertTrue(body.contains("\"type\":\"text\""), "Text part must serialize with type discriminator: $body")
    }

    @Test
    fun `chat body serializes image_url content part with type discriminator`() = runBlocking {
        server.enqueue(MockResponse().setBody("event: done\ndata: {}\n"))
        val req = CompletionStreamRequest(
            model = "test-model",
            messages = listOf(
                StreamMessage(
                    speaker = "human",
                    content = listOf(
                        StreamContentPart.Image(ImageUrl("data:image/png;base64,abc")),
                        StreamContentPart.Text("describe")
                    )
                )
            ),
            maxTokensToSample = 100
        )
        client.chat(req)
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"type\":\"image_url\""), "Body must include type=image_url discriminator: $body")
        assertTrue(body.contains("\"image_url\""), "Body must nest the image_url object: $body")
        assertTrue(body.contains("data:image/png;base64,abc"), "Body must include the data URL: $body")
    }

    @Test
    fun `chat returns text empty on stream that emits only done`() = runBlocking {
        server.enqueue(MockResponse().setBody("event: done\ndata: {}\n"))
        val req = CompletionStreamRequest(model = "x", messages = emptyList(), maxTokensToSample = 1)
        val result = client.chat(req)
        assertEquals("", result.text)
    }

    @Test
    fun `chat throws IllegalStateException when token provider returns null`() = runBlocking<Unit> {
        val noTokenClient = SourcegraphCompletionsStreamClient(
            baseUrl = server.url("/").toString(),
            tokenProvider = { null },
            modelCatalogService = fakeCatalog,
            httpClientOverride = OkHttpClient.Builder().build()
        )
        val req = CompletionStreamRequest(model = "x", messages = emptyList(), maxTokensToSample = 1)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { noTokenClient.chat(req) }
        }
    }

    /**
     * Phase 3 review followup (mirrors ModelCatalogServiceTest): locks in the
     * production auth-header contract.
     *
     * The default `httpClientOverride = null` path constructs an OkHttpClient
     * with `AuthInterceptor(tokenProvider, AuthScheme.TOKEN)`. This test asserts
     * the actual `Authorization: token <sgp_...>` header reaches the wire — the
     * other tests inject a plain OkHttpClient (no AuthInterceptor) and never
     * exercise this path.
     *
     * If a future refactor swaps `AuthScheme.TOKEN` to `BEARER`, the request
     * would get `Authorization: Bearer <token>`, which Sourcegraph rejects with
     * 401. Without this test, that regression would land green and silently
     * break production for image-bearing turns.
     */
    @Test
    fun `production httpClient default emits Authorization token header per Sourcegraph contract`() = runBlocking {
        val productionClient = SourcegraphCompletionsStreamClient(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "sgp_test_token_for_auth_check" },
            modelCatalogService = fakeCatalog
            // httpClientOverride omitted → uses the lazy default with AuthInterceptor
        )
        server.enqueue(MockResponse().setBody("event: done\ndata: {}\n"))
        val req = CompletionStreamRequest(model = "x", messages = emptyList(), maxTokensToSample = 1)
        productionClient.chat(req)
        val recorded = server.takeRequest()
        assertEquals(
            "token sgp_test_token_for_auth_check",
            recorded.getHeader("Authorization"),
            "Production httpClient must emit 'Authorization: token <sgp_...>' (NOT 'Bearer ...'). " +
                "AuthScheme.TOKEN is the only correct scheme for Sourcegraph endpoints per " +
                "project_sourcegraph_isolation.md. If this fails, check that " +
                "SourcegraphCompletionsStreamClient still uses " +
                "AuthInterceptor(tokenProvider, AuthScheme.TOKEN) in its default httpClient."
        )
    }

    @Test
    fun `chat propagates gateway rejection reason from event error frame`() = runBlocking {
        // Sourcegraph emits HTTP 200 + event: error for unsupported MIMEs
        // (HEIC, HEIF, BMP, TIFF, AVIF, SVG per format_lab 2026-05-05).
        // The result must carry rejectionReason so BrainRouter can surface
        // it as an assistant message instead of an empty bubble.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: error\n" +
                        "data: {\"error\":\"media type image/heic not supported\"}\n\n" +
                        "event: done\ndata: {}\n"
                )
        )
        val req = CompletionStreamRequest(model = "x", messages = emptyList(), maxTokensToSample = 1)
        val result = client.chat(req)
        assertEquals("", result.text, "Rejected request should have empty text")
        assertEquals("media type image/heic not supported", result.rejectionReason)
    }

    @Test
    fun `chat assembles tool calls from delta_tool_calls frames`() = runBlocking {
        // format_lab probe (2026-05-05) verified Sourcegraph forwards tool
        // calls on /.api/completions/stream at api-version=9. Wire pattern:
        // first frame carries id + name, continuation frames append to args.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: completion\ndata: {\"delta_tool_calls\":[{\"id\":\"toolu_01\"," +
                        "\"type\":\"function\",\"function\":{\"name\":\"must_call_this\"," +
                        "\"arguments\":\"\"}}]}\n\n" +
                        "event: completion\ndata: {\"delta_tool_calls\":[{\"id\":\"\"," +
                        "\"type\":\"function\",\"function\":{\"name\":\"\"," +
                        "\"arguments\":\"{\\\"reason\\\":\"}}]}\n\n" +
                        "event: completion\ndata: {\"delta_tool_calls\":[{\"id\":\"\"," +
                        "\"type\":\"function\",\"function\":{\"name\":\"\"," +
                        "\"arguments\":\"\\\"x\\\"}\"}}]}\n\n" +
                        "event: completion\ndata: {\"stopReason\":\"tool_use\"}\n\n" +
                        "event: done\ndata: {}\n"
                )
        )
        val req = CompletionStreamRequest(model = "x", messages = emptyList(), maxTokensToSample = 1)
        val result = client.chat(req)
        assertEquals(1, result.toolCalls.size, "Expected one assembled tool call")
        val call = result.toolCalls.first()
        assertEquals("toolu_01", call.id)
        assertEquals("must_call_this", call.function.name)
        assertEquals("{\"reason\":\"x\"}", call.function.arguments,
            "Continuation frames must concatenate into full arguments JSON")
        assertEquals("tool_use", result.stopReason)
    }

    @Test
    fun `chat leaves toolCalls empty when LLM emits only text`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                "event: completion\ndata: {\"deltaText\":\"hello\"}\n\n" +
                    "event: done\ndata: {}\n"
            )
        )
        val req = CompletionStreamRequest(model = "x", messages = emptyList(), maxTokensToSample = 1)
        val result = client.chat(req)
        assertEquals(emptyList<Any>(), result.toolCalls)
    }

    @Test
    fun `chat leaves rejectionReason null when no error frame is emitted`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                "event: completion\ndata: {\"deltaText\":\"hi\"}\n\n" +
                    "event: done\ndata: {}\n"
            )
        )
        val req = CompletionStreamRequest(model = "x", messages = emptyList(), maxTokensToSample = 1)
        val result = client.chat(req)
        assertEquals("hi", result.text)
        assertEquals(null, result.rejectionReason,
            "rejectionReason must be null on successful streams to avoid false-positive UX")
    }
}

/**
 * Test double for [ModelCatalogService]. Only the methods the stream client
 * actually calls need stubbing; everything else throws.
 *
 * Open + var so individual tests can mutate `latestApiVersion` mid-test.
 */
private class FakeModelCatalogService(
    var latestApiVersion: Int = 8,
) : ModelCatalogService(
    baseUrl = "http://unused.test",
    tokenProvider = { "unused" }
) {
    override fun getLatestStreamApiVersion(): Int = latestApiVersion
}
