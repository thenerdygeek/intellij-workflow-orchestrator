package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SessionTraceTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `session lifecycle is traced end-to-end`() {
        val trace = SessionTrace("test-session", tempDir.toString())

        trace.sessionStarted("Fix the bug", toolCount = 20, reservedTokens = 4900, effectiveBudget = 145100)
        trace.iterationStarted(1, budgetUsedTokens = 1200, budgetPercent = 0)
        trace.toolExecuted("read_file", durationMs = 15, resultTokens = 50, isError = false)
        trace.iterationCompleted(1, promptTokens = 2000, completionTokens = 500, toolsCalled = listOf("read_file"), finishReason = "stop")
        trace.sessionCompleted(totalTokens = 2500, iterations = 1, artifacts = listOf("src/Main.kt"))

        val file = File(tempDir.toFile(), ".workflow/agent/traces/test-session.trace.jsonl")
        assertTrue(file.exists(), "Trace file should be created")

        val lines = file.readLines()
        assertEquals(5, lines.size, "Should have 5 trace entries")

        // Parse first line
        val first = json.decodeFromString<SessionTrace.TraceEntry>(lines[0])
        assertEquals("session_started", first.type)
        assertEquals("Fix the bug", first.task)
        assertEquals(20, first.toolCount)
        assertEquals(4900, first.reservedTokens)

        // Parse last line
        val last = json.decodeFromString<SessionTrace.TraceEntry>(lines[4])
        assertEquals("session_completed", last.type)
        assertEquals(2500, last.totalTokens)
        assertEquals(1, last.iteration)
        assertTrue(last.durationMs!! >= 0)
    }

    @Test
    fun `failure dumps conversation state`() {
        val trace = SessionTrace("fail-session", tempDir.toString())

        val messages = listOf(
            ChatMessage(role = "system", content = "You are an assistant"),
            ChatMessage(role = "user", content = "Fix the bug in UserService.kt"),
            ChatMessage(role = "assistant", content = "I'll read the file first"),
            ChatMessage(role = "tool", content = "class UserService { ... }", toolCallId = "call-1")
        )

        trace.dumpConversationState(messages, "llm_call_failed: Connection refused")
        trace.sessionFailed("Connection refused", totalTokens = 3000, iterations = 2)

        val file = File(tempDir.toFile(), ".workflow/agent/traces/fail-session.trace.jsonl")
        val lines = file.readLines()
        assertEquals(2, lines.size)

        val dump = json.decodeFromString<SessionTrace.TraceEntry>(lines[0])
        assertEquals("conversation_dump", dump.type)
        assertEquals(4, dump.messageCount)
        assertNotNull(dump.messageSummaries)
        assertEquals(4, dump.messageSummaries!!.size)
        assertEquals("system", dump.messageSummaries!![0].role)
        assertEquals("tool", dump.messageSummaries!![3].role)
    }

    @Test
    fun `tool execution captures timing and errors`() {
        val trace = SessionTrace("tool-session", tempDir.toString())

        trace.toolExecuted("read_file", durationMs = 25, resultTokens = 100, isError = false)
        trace.toolExecuted("edit_file", durationMs = 150, resultTokens = 0, isError = true, errorMessage = "old_string not found")

        val file = File(tempDir.toFile(), ".workflow/agent/traces/tool-session.trace.jsonl")
        val lines = file.readLines()

        val success = json.decodeFromString<SessionTrace.TraceEntry>(lines[0])
        assertEquals("tool_executed", success.type)
        assertEquals("read_file", success.toolName)
        assertEquals(25, success.durationMs)
        assertEquals(false, success.isError)

        val failure = json.decodeFromString<SessionTrace.TraceEntry>(lines[1])
        assertEquals("edit_file", failure.toolName)
        assertEquals(true, failure.isError)
        assertEquals("old_string not found", failure.error)
    }

    @Test
    fun `compression events track token savings`() {
        val trace = SessionTrace("compress-session", tempDir.toString())

        trace.compressionTriggered("budget_enforcer", tokensBefore = 85000, tokensAfter = 55000, messagesDropped = 12)

        val file = File(tempDir.toFile(), ".workflow/agent/traces/compress-session.trace.jsonl")
        val line = file.readLines().first()
        val entry = json.decodeFromString<SessionTrace.TraceEntry>(line)

        assertEquals("compression", entry.type)
        assertEquals("budget_enforcer", entry.compressionTrigger)
        assertEquals(85000, entry.tokensBefore)
        assertEquals(55000, entry.tokensAfter)
        assertEquals(12, entry.messagesDropped)
    }

    @Test
    fun `HTTP request and response are traced`() {
        val trace = SessionTrace("http-session", tempDir.toString())

        trace.httpRequest("POST", "/.api/llm/chat/completions", bodyLength = 12500, messageCount = 8, toolDefCount = 20, maxTokens = 4000)
        trace.httpResponse(statusCode = 200, bodyLength = 3500, durationMs = 2800, promptTokens = 3000, completionTokens = 800, finishReason = "stop")

        val file = File(tempDir.toFile(), ".workflow/agent/traces/http-session.trace.jsonl")
        val lines = file.readLines()

        val req = json.decodeFromString<SessionTrace.TraceEntry>(lines[0])
        assertEquals("http_request", req.type)
        assertEquals("POST", req.httpMethod)
        assertEquals(12500, req.httpBodyLength)
        assertEquals(20, req.toolCount)

        val resp = json.decodeFromString<SessionTrace.TraceEntry>(lines[1])
        assertEquals("http_response", resp.type)
        assertEquals(200, resp.httpStatusCode)
        assertEquals(2800, resp.durationMs)
        assertEquals("stop", resp.finishReason)
    }

    @Test
    fun `each trace entry has timestamp and session ID`() {
        val trace = SessionTrace("ts-session", tempDir.toString())

        val before = System.currentTimeMillis()
        trace.sessionStarted("test", toolCount = 5, reservedTokens = 1000, effectiveBudget = 149000)
        val after = System.currentTimeMillis()

        val file = File(tempDir.toFile(), ".workflow/agent/traces/ts-session.trace.jsonl")
        val entry = json.decodeFromString<SessionTrace.TraceEntry>(file.readLines().first())

        assertEquals("ts-session", entry.sessionId)
        assertTrue(entry.timestamp in before..after, "Timestamp should be between before and after")
    }
}
