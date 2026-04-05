package com.workflow.orchestrator.agent.observability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AgentFileLoggerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var logger: AgentFileLogger

    private val lenientJson = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        logger = AgentFileLogger(tempDir)
    }

    @AfterEach
    fun tearDown() {
        logger.close()
    }

    // ── File creation ────────────────────────────────────────────────

    @Nested
    inner class FileCreation {

        @Test
        fun `log file created with correct name on first write`() {
            logger.logSessionStart("sess-abc123xyz", "Fix login bug", 57)
            logger.flush()

            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val expectedName = "agent-$today.jsonl"
            val logFile = File(tempDir, expectedName)

            assertTrue(logFile.exists(), "Expected log file $expectedName to exist")
            assertTrue(logFile.length() > 0, "Log file should not be empty")
        }

        @Test
        fun `log file is created in the configured logDir`() {
            val subDir = File(tempDir, "custom-logs").also { it.mkdirs() }
            val customLogger = AgentFileLogger(subDir)
            try {
                customLogger.logRetry("sess-001abc", "format_error", 3)
                customLogger.flush()
                val files = subDir.listFiles()?.filter { it.name.endsWith(".jsonl") } ?: emptyList()
                assertEquals(1, files.size, "Expected exactly one JSONL file in custom logDir")
            } finally {
                customLogger.close()
            }
        }
    }

    // ── Session events ────────────────────────────────────────────────

    @Nested
    inner class SessionEvents {

        @Test
        fun `session_start event has correct fields`() {
            logger.logSessionStart("sess-abc123xyz456", "Refactor auth module", 42)
            logger.flush()

            val entry = readLastEntry()
            assertEquals("session_start", entry["event"]?.jsonPrimitive?.content)
            assertEquals("sess-abc123", entry["session"]?.jsonPrimitive?.content)
            assertNotNull(entry["ts"], "ts field must be present")
        }

        @Test
        fun `session_end event round-trips correctly`() {
            logger.logSessionEnd(
                sessionId = "sess-end999xyz",
                iterations = 12,
                totalTokens = 48000,
                durationMs = 95000L
            )
            logger.flush()

            val entry = readLastEntry()
            assertEquals("session_end", entry["event"]?.jsonPrimitive?.content)
            assertEquals("sess-end999", entry["session"]?.jsonPrimitive?.content)
            assertEquals(12, entry["iteration"]?.jsonPrimitive?.intOrNull)
            assertEquals(48000, entry["tokens"]?.jsonPrimitive?.intOrNull)
            assertEquals(95000L, entry["durationMs"]?.jsonPrimitive?.longOrNull)
            assertNull(entry["error"], "error field should be absent on success")
        }

        @Test
        fun `session_end with error includes error field`() {
            logger.logSessionEnd(
                sessionId = "sess-fail001xyz",
                iterations = 3,
                totalTokens = 5000,
                durationMs = 12000L,
                error = "Context window exceeded"
            )
            logger.flush()

            val entry = readLastEntry()
            assertEquals("session_end", entry["event"]?.jsonPrimitive?.content)
            assertEquals("Context window exceeded", entry["error"]?.jsonPrimitive?.content)
        }

        @Test
        fun `session_start and session_end produce two JSONL lines`() {
            logger.logSessionStart("sess-twolines1", "Task A", 10)
            logger.logSessionEnd("sess-twolines1", 1, 1000, 5000L)
            logger.flush()

            val lines = readAllLines()
            assertEquals(2, lines.size, "Expected exactly 2 JSONL lines")
        }
    }

    // ── Tool call events ────────────────────────────────────────────

    @Nested
    inner class ToolCallEvents {

        @Test
        fun `successful tool call has correct fields`() {
            logger.logToolCall(
                sessionId = "sess-tool01-xyz",
                toolName = "read_file",
                durationMs = 45L,
                isError = false,
                args = """{"path": "src/main/Foo.kt"}""",
                tokenEstimate = 200
            )
            logger.flush()

            val entry = readLastEntry()
            assertEquals("tool_call", entry["event"]?.jsonPrimitive?.content)
            assertEquals("sess-tool01", entry["session"]?.jsonPrimitive?.content)
            assertEquals("read_file", entry["tool"]?.jsonPrimitive?.content)
            assertEquals("ok", entry["status"]?.jsonPrimitive?.content)
            assertEquals(45L, entry["durationMs"]?.jsonPrimitive?.longOrNull)
            assertEquals(200, entry["tokens"]?.jsonPrimitive?.intOrNull)
            assertNotNull(entry["args"])
            assertNull(entry["error"], "error field should be absent on success")
        }

        @Test
        fun `tool call with error has error field and status=error`() {
            logger.logToolCall(
                sessionId = "sess-toolerr01",
                toolName = "edit_file",
                durationMs = 12L,
                isError = true,
                errorMessage = "File not found: src/missing.kt"
            )
            logger.flush()

            val entry = readLastEntry()
            assertEquals("tool_call", entry["event"]?.jsonPrimitive?.content)
            assertEquals("error", entry["status"]?.jsonPrimitive?.content)
            assertEquals("File not found: src/missing.kt", entry["error"]?.jsonPrimitive?.content)
        }

        @Test
        fun `args are truncated to 500 chars`() {
            val longArgs = "x".repeat(600)
            logger.logToolCall(
                sessionId = "sess-truncate1",
                toolName = "bash",
                durationMs = 5L,
                isError = false,
                args = longArgs
            )
            logger.flush()

            val entry = readLastEntry()
            val storedArgs = entry["args"]?.jsonPrimitive?.content ?: ""
            assertTrue(storedArgs.length <= 500, "args should be truncated to 500 chars, got ${storedArgs.length}")
        }

        @Test
        fun `error message is truncated to 500 chars`() {
            val longError = "e".repeat(600)
            logger.logToolCall(
                sessionId = "sess-errtrnc01",
                toolName = "bash",
                durationMs = 5L,
                isError = true,
                errorMessage = longError
            )
            logger.flush()

            val entry = readLastEntry()
            val storedError = entry["error"]?.jsonPrimitive?.content ?: ""
            assertTrue(storedError.length <= 500, "error should be truncated to 500 chars")
        }
    }

    // ── API call events ────────────────────────────────────────────

    @Nested
    inner class ApiCallEvents {

        @Test
        fun `api_call event includes latency and token fields`() {
            logger.logApiCall(
                sessionId = "sess-api001-xyz",
                latencyMs = 1230L,
                promptTokens = 15000,
                completionTokens = 800,
                finishReason = "end_turn"
            )
            logger.flush()

            val entry = readLastEntry()
            assertEquals("api_call", entry["event"]?.jsonPrimitive?.content)
            assertEquals("sess-api001", entry["session"]?.jsonPrimitive?.content)
            assertEquals(1230L, entry["latencyMs"]?.jsonPrimitive?.longOrNull)
            assertEquals(15000, entry["promptTokens"]?.jsonPrimitive?.intOrNull)
            assertEquals(800, entry["completionTokens"]?.jsonPrimitive?.intOrNull)
            assertEquals("end_turn", entry["finishReason"]?.jsonPrimitive?.content)
        }

        @Test
        fun `api_call without finishReason omits the field`() {
            logger.logApiCall(
                sessionId = "sess-api0002xyz",
                latencyMs = 500L,
                promptTokens = 5000,
                completionTokens = 300
            )
            logger.flush()

            val entry = readLastEntry()
            assertNull(entry["finishReason"], "finishReason should be absent when not provided")
        }
    }

    // ── Iteration events ────────────────────────────────────────────

    @Nested
    inner class IterationEvents {

        @Test
        fun `iteration event has correct fields`() {
            logger.logIteration(
                sessionId = "sess-iter01-xyz",
                iteration = 5,
                toolsCalled = listOf("read_file", "bash", "edit_file"),
                durationMs = 3200L
            )
            logger.flush()

            val entry = readLastEntry()
            assertEquals("iteration", entry["event"]?.jsonPrimitive?.content)
            assertEquals("sess-iter01", entry["session"]?.jsonPrimitive?.content)
            assertEquals(5, entry["iteration"]?.jsonPrimitive?.intOrNull)
            assertEquals(3200L, entry["durationMs"]?.jsonPrimitive?.longOrNull)
            assertNotNull(entry["toolsCalled"], "toolsCalled field must be present")
        }
    }

    // ── Compaction events ────────────────────────────────────────────

    @Nested
    inner class CompactionEvents {

        @Test
        fun `compaction event includes token counts before and after`() {
            logger.logCompaction(
                sessionId = "sess-cmpct1-yz",
                trigger = "threshold_80pct",
                tokensBefore = 140000,
                tokensAfter = 45000
            )
            logger.flush()

            val entry = readLastEntry()
            assertEquals("compaction", entry["event"]?.jsonPrimitive?.content)
            assertEquals("sess-cmpct1", entry["session"]?.jsonPrimitive?.content)
            assertEquals(140000, entry["tokensBefore"]?.jsonPrimitive?.intOrNull)
            assertEquals(45000, entry["tokensAfter"]?.jsonPrimitive?.intOrNull)
        }
    }

    // ── Retry events ────────────────────────────────────────────────

    @Nested
    inner class RetryEvents {

        @Test
        fun `retry event has reason and iteration`() {
            logger.logRetry(
                sessionId = "sess-retry1-xyz",
                reason = "malformed_json",
                iteration = 7
            )
            logger.flush()

            val entry = readLastEntry()
            assertEquals("retry", entry["event"]?.jsonPrimitive?.content)
            assertEquals("sess-retry1", entry["session"]?.jsonPrimitive?.content)
            assertEquals(7, entry["iteration"]?.jsonPrimitive?.intOrNull)
        }
    }

    // ── Loop detection events ────────────────────────────────────────

    @Nested
    inner class LoopDetectionEvents {

        @Test
        fun `soft loop detection event has correct event name and tool`() {
            logger.logLoopDetection(
                sessionId = "sess-loop001xyz",
                toolName = "bash",
                count = 5,
                isHard = false
            )
            logger.flush()

            val entry = readLastEntry()
            assertEquals("loop_soft", entry["event"]?.jsonPrimitive?.content)
            assertEquals("bash", entry["tool"]?.jsonPrimitive?.content)
        }

        @Test
        fun `hard loop detection event has loop_hard event name`() {
            logger.logLoopDetection(
                sessionId = "sess-loop002xyz",
                toolName = "read_file",
                count = 10,
                isHard = true
            )
            logger.flush()

            val entry = readLastEntry()
            assertEquals("loop_hard", entry["event"]?.jsonPrimitive?.content)
        }
    }

    // ── Log rotation and retention ───────────────────────────────────

    @Nested
    inner class RetentionAndRotation {

        @Test
        fun `old logs beyond 7 days are deleted on cleanOldLogs`() {
            // Create stale log files (8 and 10 days old)
            createFakeLogFile("agent-2020-01-01.jsonl")
            createFakeLogFile("agent-2020-01-02.jsonl")
            // Create recent files (within 7 days)
            val today = LocalDate.now()
            val yesterday = today.minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val twoDaysAgo = today.minusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE)
            createFakeLogFile("agent-$yesterday.jsonl")
            createFakeLogFile("agent-$twoDaysAgo.jsonl")

            logger.cleanOldLogs()

            val remaining = tempDir.listFiles()?.map { it.name } ?: emptyList()
            assertFalse(remaining.contains("agent-2020-01-01.jsonl"), "8-day-old log should be deleted")
            assertFalse(remaining.contains("agent-2020-01-02.jsonl"), "10-day-old log should be deleted")
            assertTrue(remaining.contains("agent-$yesterday.jsonl"), "Yesterday's log should be kept")
            assertTrue(remaining.contains("agent-$twoDaysAgo.jsonl"), "2-day-old log should be kept")
        }

        @Test
        fun `non-agent JSONL files are not deleted by cleanOldLogs`() {
            createFakeLogFile("other-service-2020-01-01.jsonl")
            createFakeLogFile("agent-2020-01-01.jsonl")

            logger.cleanOldLogs()

            val remaining = tempDir.listFiles()?.map { it.name } ?: emptyList()
            assertTrue(
                remaining.contains("other-service-2020-01-01.jsonl"),
                "Non-agent log files should NOT be deleted"
            )
        }

        @Test
        fun `today log file is never deleted even if pattern matches`() {
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            createFakeLogFile("agent-$today.jsonl")

            logger.cleanOldLogs()

            val remaining = tempDir.listFiles()?.map { it.name } ?: emptyList()
            assertTrue(remaining.contains("agent-$today.jsonl"), "Today's log should never be deleted")
        }

        private fun createFakeLogFile(name: String) {
            File(tempDir, name).writeText("""{"ts":"2020-01-01T00:00:00Z","event":"session_start","session":"test"}""" + "\n")
        }
    }

    // ── Session ID truncation ────────────────────────────────────────

    @Nested
    inner class SessionIdTruncation {

        @Test
        fun `session ID is truncated to 11 chars`() {
            logger.logRetry("sess-this-is-a-very-long-id-xyz", "timeout", 1)
            logger.flush()

            val entry = readLastEntry()
            val session = entry["session"]?.jsonPrimitive?.content ?: ""
            assertEquals(11, session.length, "session should always be truncated to 11 chars")
            assertEquals("sess-this-i", session)
        }

        @Test
        fun `short session ID is stored as-is`() {
            logger.logRetry("short", "timeout", 1)
            logger.flush()

            val entry = readLastEntry()
            val session = entry["session"]?.jsonPrimitive?.content ?: ""
            assertEquals("short", session, "Short session IDs should not be padded")
        }
    }

    // ── Each line is valid JSON ──────────────────────────────────────

    @Nested
    inner class JsonValidity {

        @Test
        fun `every written line is valid JSON`() {
            logger.logSessionStart("sess-json001xyz", "task", 10)
            logger.logToolCall("sess-json001xyz", "read_file", 10L, false)
            logger.logApiCall("sess-json001xyz", 500L, 1000, 100)
            logger.logIteration("sess-json001xyz", 1, listOf("read_file"), 1000L)
            logger.logCompaction("sess-json001xyz", "threshold", 100000, 30000)
            logger.logRetry("sess-json001xyz", "error", 1)
            logger.logLoopDetection("sess-json001xyz", "bash", 3, false)
            logger.logSessionEnd("sess-json001xyz", 1, 5000, 3000L)
            logger.flush()

            val lines = readAllLines()
            assertEquals(8, lines.size)
            lines.forEachIndexed { idx, line ->
                assertDoesNotThrow(
                    { lenientJson.parseToJsonElement(line) },
                    "Line $idx should be valid JSON: $line"
                )
            }
        }

        @Test
        fun `null fields are not included in output`() {
            logger.logToolCall("sess-nulltest1", "read_file", 10L, false)
            logger.flush()

            val rawLine = readAllLines().last()
            assertFalse(rawLine.contains("\"error\":null"), "null error should be omitted")
            assertFalse(rawLine.contains("\"result\":null"), "null result should be omitted")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun readAllLines(): List<String> {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val logFile = File(tempDir, "agent-$today.jsonl")
        if (!logFile.exists()) return emptyList()
        return logFile.readLines().filter { it.isNotBlank() }
    }

    private fun readLastEntry(): JsonObject {
        val lines = readAllLines()
        assertTrue(lines.isNotEmpty(), "Log file should have at least one entry")
        return lenientJson.parseToJsonElement(lines.last()).jsonObject
    }
}
