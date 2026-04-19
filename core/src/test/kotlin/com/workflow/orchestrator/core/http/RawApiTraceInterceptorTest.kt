package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for [RawApiTraceInterceptor].
 *
 * Uses [MockWebServer] (already a test dependency) as the server side.
 * A fresh [RawApiTraceConfig]-like config object is NOT re-created per test because
 * [RawApiTraceConfig] is an `object` — each test mutates it and restores at tearDown.
 */
class RawApiTraceInterceptorTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var server: MockWebServer
    private lateinit var traceDir: File

    // Capture config state before each test so we can restore it
    private var savedMode = RawApiTraceMode.OFF
    private var savedRetentionDays = 3
    private var savedMaxBodyBytes = 10L * 1024 * 1024
    private var savedRedactPromptBody = false

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        traceDir = File(tempDir, "traces")
        traceDir.mkdirs()

        // Snapshot config
        savedMode = RawApiTraceConfig.mode
        savedRetentionDays = RawApiTraceConfig.retentionDays
        savedMaxBodyBytes = RawApiTraceConfig.maxBodyBytes
        savedRedactPromptBody = RawApiTraceConfig.redactPromptBody
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        // Restore config to avoid leaking state between tests
        RawApiTraceConfig.mode = savedMode
        RawApiTraceConfig.retentionDays = savedRetentionDays
        RawApiTraceConfig.maxBodyBytes = savedMaxBodyBytes
        RawApiTraceConfig.redactPromptBody = savedRedactPromptBody
        RawApiTraceConfig.setBurstCount(0)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildClient(config: RawApiTraceConfig = RawApiTraceConfig): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(RawApiTraceInterceptor(config = config, traceDir = { traceDir }))
            .build()
    }

    private fun postRequest(path: String = "/test", body: String = "hello"): okhttp3.Response {
        val client = buildClient()
        val request = Request.Builder()
            .url(server.url(path))
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "token super-secret-token")
            .build()
        return client.newCall(request).execute()
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `creates request and response files when ALWAYS_ON`() {
        RawApiTraceConfig.mode = RawApiTraceMode.ALWAYS_ON
        server.enqueue(MockResponse().setBody("data: {\"id\":1}\n\ndata: [DONE]\n\n").setResponseCode(200))

        postRequest().use { it.body?.string() } // consume body to trigger ForwardingSource close

        val files = traceDir.listFiles() ?: emptyArray()
        val requestFile = files.firstOrNull { it.name.endsWith(".request.http") }
        val responseFile = files.firstOrNull { it.name.endsWith(".response.http") }

        assertNotNull(requestFile, "Expected .request.http file to be created")
        assertNotNull(responseFile, "Expected .response.http file to be created")
    }

    @Test
    fun `authorization header is redacted in request file`() {
        RawApiTraceConfig.mode = RawApiTraceMode.ALWAYS_ON
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        postRequest().use { it.body?.string() }

        val requestFile = traceDir.listFiles()?.firstOrNull { it.name.endsWith(".request.http") }
        assertNotNull(requestFile, "request.http file must exist")

        val content = requestFile!!.readText()
        assertFalse(content.contains("super-secret-token"), "Raw token must not appear in request file")
        assertTrue(content.contains("***REDACTED***"), "Redaction marker must appear in request file")
    }

    @Test
    fun `SSE chunks appear verbatim in response file`() {
        RawApiTraceConfig.mode = RawApiTraceMode.ALWAYS_ON
        val sseBody = "data: {\"id\":1,\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: [DONE]\n\n"
        server.enqueue(MockResponse().setBody(sseBody).setResponseCode(200))

        postRequest().use { it.body?.string() } // consume

        val responseFile = traceDir.listFiles()?.firstOrNull { it.name.endsWith(".response.http") }
        assertNotNull(responseFile, "response.http file must exist")

        val content = responseFile!!.readText()
        assertTrue(content.contains("data: {\"id\":1"), "SSE chunk must appear verbatim in response file")
        assertTrue(content.contains("[DONE]"), "SSE done sentinel must appear in response file")
    }

    @Test
    fun `body is truncated when maxBodyBytes is small`() {
        RawApiTraceConfig.mode = RawApiTraceMode.ALWAYS_ON
        RawApiTraceConfig.maxBodyBytes = 50

        val longBody = "A".repeat(200)
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        val client = buildClient()
        val request = Request.Builder()
            .url(server.url("/test"))
            .post(longBody.toRequestBody("text/plain".toMediaType()))
            .build()
        client.newCall(request).execute().use { it.body?.string() }

        val requestFile = traceDir.listFiles()?.firstOrNull { it.name.endsWith(".request.http") }
        assertNotNull(requestFile, "request.http file must exist")

        val content = requestFile!!.readText()
        assertTrue(
            content.contains("---TRUNCATED AT 50 BYTES---"),
            "Truncation marker must appear when body exceeds maxBodyBytes. Content was:\n$content"
        )
    }

    @Test
    fun `no files created when mode is OFF`() {
        RawApiTraceConfig.mode = RawApiTraceMode.OFF
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        postRequest().use { it.body?.string() }

        val files = traceDir.listFiles() ?: emptyArray()
        assertEquals(0, files.size, "No trace files should be created when mode=OFF")
    }

    @Test
    fun `burst mode - first call traces, second call does not`() {
        RawApiTraceConfig.mode = RawApiTraceMode.BURST
        RawApiTraceConfig.setBurstCount(1)

        server.enqueue(MockResponse().setBody("first").setResponseCode(200))
        server.enqueue(MockResponse().setBody("second").setResponseCode(200))

        val client = buildClient()

        // First call — should trace
        client.newCall(
            Request.Builder()
                .url(server.url("/test"))
                .post("msg1".toRequestBody("text/plain".toMediaType()))
                .build()
        ).execute().use { it.body?.string() }

        val filesAfterFirst = traceDir.listFiles()?.size ?: 0
        assertTrue(filesAfterFirst > 0, "Expected trace files after first (burst) call")

        // Second call — burst exhausted, should NOT trace
        client.newCall(
            Request.Builder()
                .url(server.url("/test"))
                .post("msg2".toRequestBody("text/plain".toMediaType()))
                .build()
        ).execute().use { it.body?.string() }

        val filesAfterSecond = traceDir.listFiles()?.size ?: 0
        assertEquals(
            filesAfterFirst,
            filesAfterSecond,
            "No additional trace files should be created after burst is exhausted"
        )
    }

    @Test
    fun `request file contains HTTP method and path`() {
        RawApiTraceConfig.mode = RawApiTraceMode.ALWAYS_ON
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        postRequest(path = "/.api/llm/chat/completions").use { it.body?.string() }

        val requestFile = traceDir.listFiles()?.firstOrNull { it.name.endsWith(".request.http") }
        assertNotNull(requestFile)
        val content = requestFile!!.readText()
        assertTrue(content.startsWith("POST "), "Request file must start with HTTP method")
        assertTrue(content.contains("/.api/llm/chat/completions"), "Request file must contain path")
    }

    @Test
    fun `response file contains HTTP status line`() {
        RawApiTraceConfig.mode = RawApiTraceMode.ALWAYS_ON
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        postRequest().use { it.body?.string() }

        val responseFile = traceDir.listFiles()?.firstOrNull { it.name.endsWith(".response.http") }
        assertNotNull(responseFile)
        val content = responseFile!!.readText()
        assertTrue(content.contains("200"), "Response file must contain HTTP status code")
    }

    @Test
    fun `PreSanitizeDumper writes pre-sanitize json file`() {
        val messages = listOf(
            ChatMessage(role = "user", content = "Hello, agent!"),
            ChatMessage(role = "assistant", content = "Hello, user!")
        )
        val reqId = "120000-001"

        PreSanitizeDumper.dump(messages, reqId, traceDir)

        val preSanitizeFile = File(traceDir, "$reqId.pre-sanitize.json")
        assertTrue(preSanitizeFile.exists(), "Pre-sanitize file must be created")
        val content = preSanitizeFile.readText()
        assertTrue(content.contains("\"user\""), "Pre-sanitize JSON must contain role")
        assertTrue(content.contains("Hello, agent!"), "Pre-sanitize JSON must contain message content")
    }

    @Test
    fun `trace id header correlates request and response files`() {
        RawApiTraceConfig.mode = RawApiTraceMode.ALWAYS_ON
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RawApiTraceInterceptor(config = RawApiTraceConfig, traceDir = { traceDir }))
            .build()

        val request = Request.Builder()
            .url(server.url("/test"))
            .post("body".toRequestBody("application/json".toMediaType()))
            .header(RawApiTraceInterceptor.TRACE_HEADER, "custom-trace-id")
            .build()
        client.newCall(request).execute().use { it.body?.string() }

        val files = traceDir.listFiles() ?: emptyArray()
        val requestFile = files.firstOrNull { it.name == "custom-trace-id.request.http" }
        val responseFile = files.firstOrNull { it.name == "custom-trace-id.response.http" }
        assertNotNull(requestFile, "Request file must use custom trace ID from header")
        assertNotNull(responseFile, "Response file must use custom trace ID from header")
    }

    @Test
    fun `cookie header is also redacted in request file`() {
        RawApiTraceConfig.mode = RawApiTraceMode.ALWAYS_ON
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        val client = buildClient()
        val request = Request.Builder()
            .url(server.url("/test"))
            .post("body".toRequestBody("application/json".toMediaType()))
            .header("Cookie", "session=abc123; token=xyz")
            .build()
        client.newCall(request).execute().use { it.body?.string() }

        val requestFile = traceDir.listFiles()?.firstOrNull { it.name.endsWith(".request.http") }
        assertNotNull(requestFile)
        val content = requestFile!!.readText()
        assertFalse(content.contains("abc123"), "Cookie value must not appear in request file")
        assertTrue(content.contains("***REDACTED***"), "Cookie redaction marker must appear")
    }
}
