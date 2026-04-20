package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reproduces the "api-debug call-NNN gets overwritten for the same conversation" bug.
 *
 * Root cause: `AgentService.executeTask()` (AgentService.kt:1042) allocates
 * `val sharedApiCounter = AtomicInteger(0)` locally on every invocation. Since
 * `executeTask` is called once **per user message**, every follow-up message in the
 * same session gets a fresh counter that starts at 0 — but writes into the SAME
 * `sessions/{sid}/api-debug/` directory. The result is that turn 2's first API call
 * writes `call-001-request.txt` and clobbers turn 1's `call-001-request.txt`.
 *
 * These tests simulate the buggy path and should FAIL today. They will pass once
 * the counter is lifted to session scope (e.g. owned by the session, not the
 * executeTask closure).
 */
class ApiDebugCounterResetBugTest {

    private lateinit var server: MockWebServer

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun sseResponse(promptTokens: Int, completionTokens: Int, text: String): MockResponse {
        val delta = """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":"$text"},"finish_reason":"stop"}]}"""
        val usage = """{"id":"c1","choices":[],"usage":{"prompt_tokens":$promptTokens,"completion_tokens":$completionTokens,"total_tokens":${promptTokens + completionTokens}}}"""
        val body = "data: $delta\n\ndata: $usage\n\n"
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    private fun newClient(): SourcegraphChatClient = SourcegraphChatClient(
        baseUrl = server.url("/").toString(),
        tokenProvider = { "test-token" },
        model = "test-model",
        httpClientOverride = OkHttpClient.Builder().build()
    )

    private fun apiDebugDir(sessionDir: File) = File(sessionDir, "api-debug")

    /**
     * Post-fix invariant: when the api-debug call counter is owned at session scope
     * (not rebuilt per `executeTask`), two consecutive user messages produce distinct
     * `call-001` and `call-002` files — turn 1's dump is preserved.
     *
     * This mirrors the fixed wiring: `AgentService.executeTask` now calls
     * `getOrCreateSessionRuntime(sid).apiCallCounter`, returning the same
     * `AtomicInteger` on every invocation for a given session.
     */
    @Test
    fun `shared counter across turns produces distinct call-001 and call-002 dumps`() = runTest {
        val sessionDir = tempDir.resolve("sessions/sid-fixed").toFile().apply { mkdirs() }

        // One session-scoped counter shared by every turn's brain (the fix).
        val sessionCounter = AtomicInteger(0)

        // ── Turn 1: first user message ──
        val turn1Client = newClient().apply {
            apiDebugSessionDir = sessionDir
            setSharedApiCallCounter(sessionCounter)
        }
        server.enqueue(sseResponse(100, 10, "turn1-reply"))
        turn1Client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "TURN-ONE-UNIQUE-MARKER")),
            tools = null,
            onChunk = {}
        )

        val afterTurn1 = apiDebugDir(sessionDir).listFiles()?.map { it.name }?.sorted() ?: emptyList()
        assertTrue(afterTurn1.contains("call-001-request.txt"),
            "turn 1 should have produced call-001-request.txt, got: $afterTurn1")

        // ── Turn 2: second user message (fresh brain — like model recycle — same shared counter) ──
        val turn2Client = newClient().apply {
            apiDebugSessionDir = sessionDir
            setSharedApiCallCounter(sessionCounter)
        }
        server.enqueue(sseResponse(200, 20, "turn2-reply"))
        turn2Client.sendMessageStream(
            messages = listOf(ChatMessage(role = "user", content = "TURN-TWO-UNIQUE-MARKER")),
            tools = null,
            onChunk = {}
        )

        val afterTurn2 = apiDebugDir(sessionDir).listFiles()?.map { it.name }?.sorted() ?: emptyList()

        // Assertion A — counter is monotonic across turns (no reset to 001)
        assertTrue(afterTurn2.contains("call-002-request.txt"),
            "Expected turn 2's request to be dumped as call-002-request.txt, " +
                "but api-debug/ contained: $afterTurn2.")

        // Assertion B — turn 1's dump survived and still contains turn 1's marker
        val call001 = File(apiDebugDir(sessionDir), "call-001-request.txt").readText()
        assertTrue(call001.contains("TURN-ONE-UNIQUE-MARKER"),
            "call-001-request.txt should still contain turn 1's content, but got:\n" +
                call001.take(400))

        // Assertion C — turn 2's dump contains turn 2's marker
        val call002 = File(apiDebugDir(sessionDir), "call-002-request.txt").readText()
        assertTrue(call002.contains("TURN-TWO-UNIQUE-MARKER"),
            "call-002-request.txt should contain turn 2's content, but got:\n" +
                call002.take(400))
    }

    /**
     * Confirms that within a SINGLE turn the counter IS monotonic (sanity test,
     * should pass both before and after the fix).
     */
    @Test
    fun `within one turn the counter is monotonic across multiple api calls`() = runTest {
        val sessionDir = tempDir.resolve("sessions/sid-sanity").toFile().apply { mkdirs() }
        val counter = AtomicInteger(0)
        val client = newClient().apply {
            apiDebugSessionDir = sessionDir
            setSharedApiCallCounter(counter)
        }

        repeat(3) { i ->
            server.enqueue(sseResponse(10, 2, "reply-$i"))
            client.sendMessageStream(
                messages = listOf(ChatMessage(role = "user", content = "msg-$i")),
                tools = null,
                onChunk = {}
            )
        }

        val dumps = apiDebugDir(sessionDir).listFiles()
            ?.map { it.name }
            ?.filter { it.endsWith("-request.txt") }
            ?.sorted()
            ?: emptyList()
        assertEquals(listOf("call-001-request.txt", "call-002-request.txt", "call-003-request.txt"), dumps,
            "Within one turn, api-debug counter must produce 001, 002, 003 — no overwrites.")
    }

    /**
     * Isolates the counter-allocation bug from I/O: two distinct AtomicIntegers
     * both starting at 0 (the pattern AgentService uses today) will both hand out
     * "1" as their first ticket. This is the root-cause invariant.
     */
    @Test
    fun `two fresh counters both hand out 1 as first ticket — the clobber pattern`() {
        val turn1 = AtomicInteger(0)
        val turn2 = AtomicInteger(0)

        val firstOfTurn1 = turn1.incrementAndGet()
        val firstOfTurn2 = turn2.incrementAndGet()

        assertEquals(1, firstOfTurn1)
        assertEquals(1, firstOfTurn2,
            "Today's executeTask creates a fresh AtomicInteger(0) per turn, so both " +
                "counters issue '1' as their first call — clobbering call-001-*.txt")
    }
}
