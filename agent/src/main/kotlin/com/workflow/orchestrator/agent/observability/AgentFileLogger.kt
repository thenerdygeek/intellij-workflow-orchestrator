package com.workflow.orchestrator.agent.observability

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Dedicated structured JSONL file logger for the AI agent.
 *
 * Writes one JSON object per line to `logDir/agent-YYYY-MM-DD.jsonl`.
 * Rotation is handled by filename (daily). Retention is enforced at construction
 * time via [cleanOldLogs] (7-day window).
 *
 * All writes are synchronous — callers are expected to already be on the IO
 * dispatcher. There is no toggle to disable this logger; observability is
 * always-on.
 *
 * @param logDir Directory in which to write log files. Created if absent.
 */
class AgentFileLogger(private val logDir: File) {

    private val log = Logger.getInstance(AgentFileLogger::class.java)

    private val jsonEncoder = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    /** Cached (date, writer) so we only open one file at a time. */
    private var currentDate: LocalDate? = null
    private var writer: PrintWriter? = null

    init {
        logDir.mkdirs()
        cleanOldLogs()
    }

    // ── Public log methods ──────────────────────────────────────────

    fun logToolCall(
        sessionId: String,
        toolName: String,
        durationMs: Long,
        isError: Boolean,
        args: String? = null,
        errorMessage: String? = null,
        tokenEstimate: Int? = null,
    ) {
        write(
            LogEntry(
                ts = now(),
                session = truncateSession(sessionId),
                event = if (isError) "tool_call" else "tool_call",
                tool = toolName,
                status = if (isError) "error" else "ok",
                args = args?.take(500),
                error = errorMessage?.take(500),
                durationMs = durationMs,
                tokens = tokenEstimate,
            )
        )
    }

    fun logApiCall(
        sessionId: String,
        latencyMs: Long,
        promptTokens: Int,
        completionTokens: Int,
        finishReason: String? = null,
    ) {
        write(
            LogEntry(
                ts = now(),
                session = truncateSession(sessionId),
                event = "api_call",
                latencyMs = latencyMs,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                finishReason = finishReason,
            )
        )
    }

    fun logIteration(
        sessionId: String,
        iteration: Int,
        toolsCalled: List<String>,
        durationMs: Long,
    ) {
        write(
            LogEntry(
                ts = now(),
                session = truncateSession(sessionId),
                event = "iteration",
                iteration = iteration,
                toolsCalled = toolsCalled,
                durationMs = durationMs,
            )
        )
    }

    fun logCompaction(
        sessionId: String,
        trigger: String,
        tokensBefore: Int,
        tokensAfter: Int,
    ) {
        write(
            LogEntry(
                ts = now(),
                session = truncateSession(sessionId),
                event = "compaction",
                tokensBefore = tokensBefore,
                tokensAfter = tokensAfter,
            )
        )
    }

    fun logRetry(
        sessionId: String,
        reason: String,
        iteration: Int,
    ) {
        write(
            LogEntry(
                ts = now(),
                session = truncateSession(sessionId),
                event = "retry",
                iteration = iteration,
                error = reason.take(500),
            )
        )
    }

    fun logSessionStart(
        sessionId: String,
        task: String,
        toolCount: Int,
    ) {
        write(
            LogEntry(
                ts = now(),
                session = truncateSession(sessionId),
                event = "session_start",
            )
        )
    }

    fun logSessionEnd(
        sessionId: String,
        iterations: Int,
        totalTokens: Int,
        durationMs: Long,
        error: String? = null,
    ) {
        write(
            LogEntry(
                ts = now(),
                session = truncateSession(sessionId),
                event = if (error != null) "session_end" else "session_end",
                iteration = iterations,
                tokens = totalTokens,
                durationMs = durationMs,
                error = error?.take(500),
            )
        )
    }

    fun logLoopDetection(
        sessionId: String,
        toolName: String,
        count: Int,
        isHard: Boolean,
    ) {
        write(
            LogEntry(
                ts = now(),
                session = truncateSession(sessionId),
                event = if (isHard) "loop_hard" else "loop_soft",
                tool = toolName,
            )
        )
    }

    /** Flush the underlying writer. Useful in tests to ensure writes are visible. */
    fun flush() {
        writer?.flush()
    }

    /** Close the underlying writer and release resources. */
    fun close() {
        writer?.close()
        writer = null
        currentDate = null
    }

    /**
     * Delete log files matching `agent-YYYY-MM-DD.jsonl` that are older than 7 days.
     * Public so tests can invoke it directly on a temp directory.
     */
    fun cleanOldLogs() {
        val cutoff = LocalDate.now().minusDays(7)
        val pattern = Regex("""^agent-(\d{4}-\d{2}-\d{2})\.jsonl$""")
        logDir.listFiles()?.forEach { file ->
            val match = pattern.matchEntire(file.name) ?: return@forEach
            val fileDate = try {
                LocalDate.parse(match.groupValues[1])
            } catch (_: DateTimeParseException) {
                return@forEach
            }
            if (fileDate.isBefore(cutoff)) {
                try {
                    file.delete()
                } catch (e: Exception) {
                    log.debug("AgentFileLogger: could not delete old log ${file.name}: ${e.message}")
                }
            }
        }
    }

    // ── Internal helpers ────────────────────────────────────────────

    private fun write(entry: LogEntry) {
        try {
            val today = LocalDate.now()
            if (today != currentDate) {
                writer?.close()
                val logFile = File(logDir, "agent-${today.format(DateTimeFormatter.ISO_LOCAL_DATE)}.jsonl")
                writer = PrintWriter(FileWriter(logFile, true), true)
                currentDate = today
            }
            writer?.println(jsonEncoder.encodeToString(entry))
        } catch (e: Exception) {
            log.debug("AgentFileLogger: write failed: ${e.message}")
        }
    }

    private fun now(): String =
        ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)

    private fun truncateSession(sessionId: String): String =
        sessionId.take(11)

    // ── Log entry data class ────────────────────────────────────────

    @Serializable
    private data class LogEntry(
        val ts: String,
        val session: String,
        val event: String,
        val tool: String? = null,
        val status: String? = null,
        val args: String? = null,
        val result: String? = null,
        val error: String? = null,
        val durationMs: Long? = null,
        val latencyMs: Long? = null,
        val tokens: Int? = null,
        val iteration: Int? = null,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val tokensBefore: Int? = null,
        val tokensAfter: Int? = null,
        val finishReason: String? = null,
        val toolsCalled: List<String>? = null,
    )
}
