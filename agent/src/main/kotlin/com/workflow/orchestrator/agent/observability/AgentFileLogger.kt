package com.workflow.orchestrator.agent.observability

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.session.AtomicFileWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Max chars of the task text recorded in the `session_start` audit entry (D3). */
private const val TASK_SUMMARY_MAX_CHARS = 100

/**
 * Dedicated structured JSONL file logger for the AI agent.
 *
 * Writes one JSON object per line to `logDir/agent-YYYY-MM-DD.jsonl`.
 * Rotation is handled by filename (daily). Retention is enforced at construction
 * time via [cleanOldLogs] (7-day window).
 *
 * All writes are synchronous — callers are expected to already be on the IO dispatcher.
 *
 * @param logDir Directory in which to write log files. Created if absent.
 * @param retentionDays Age threshold for [cleanOldLogs]; files older than this are pruned at
 *   construction. Clamped to >= 1. Wired from `PluginSettings.retentionDays` (D1).
 * @param enabled When false, every log method is a no-op — honours the "Enable diagnostic JSONL
 *   logging" setting (D2). Default true so existing callers/tests keep the prior always-on behaviour.
 */
class AgentFileLogger(
    private val logDir: File,
    private val retentionDays: Int = 7,
    private val enabled: Boolean = true,
) {

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
        output: String? = null,
    ) {
        write(
            LogEntry(
                ts = now(),
                session = truncateSession(sessionId),
                event = "tool_call",
                tool = toolName,
                status = if (isError) "error" else "ok",
                args = args?.take(500),
                error = errorMessage?.take(500),
                durationMs = durationMs,
                tokens = tokenEstimate,
                // D4: command output, included only when the caller opts in
                // (PluginSettings.includeCommandOutputInLogs); null → field omitted.
                output = output,
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

    @Suppress("UNUSED_PARAMETER")
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
        failureType: com.workflow.orchestrator.core.model.FailureType? = null,
    ) {
        write(
            LogEntry(
                ts = now(),
                session = truncateSession(sessionId),
                event = "retry",
                iteration = iteration,
                error = reason.take(500),
                failureType = failureType?.name,
            )
        )
    }

    @Suppress("UNUSED_PARAMETER")
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
                // D3: anchor the session in the audit trail with what it was asked to do, so an
                // auditor can correlate a session without reading api_conversation_history.json.
                taskSummary = task.take(TASK_SUMMARY_MAX_CHARS),
            )
        )
    }

    fun logSessionEnd(
        sessionId: String,
        iterations: Int,
        totalTokens: Int,
        durationMs: Long,
        error: String? = null,
        failureType: com.workflow.orchestrator.core.model.FailureType? = null,
    ) {
        write(
            LogEntry(
                ts = now(),
                session = truncateSession(sessionId),
                event = "session_end",
                iteration = iterations,
                tokens = totalTokens,
                durationMs = durationMs,
                error = error?.take(500),
                failureType = failureType?.name,
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
        // D1: retention is wired from settings (clamped to >= 1 so a bad value can't disable pruning).
        val cutoff = LocalDate.now().minusDays(maxOf(1, retentionDays).toLong())
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
        if (!enabled) return  // D2: honour the "Enable diagnostic JSONL logging" setting
        try {
            val today = LocalDate.now()
            if (today != currentDate) {
                writer?.close()
                val logFile = File(logDir, "agent-${today.format(DateTimeFormatter.ISO_LOCAL_DATE)}.jsonl")
                // E2: On first creation, open with CREATE + owner-only POSIX perms so the log
                // is not world-readable. Subsequent opens use APPEND so existing content is preserved.
                // We gate the perms call on !exists() to avoid the redundant view lookup on every rotation.
                val logPath = logFile.toPath()
                val isNew = !Files.exists(logPath)
                val fileWriter = FileWriter(logFile, true)  // append=true is safe; initial perms set below
                if (isNew) {
                    AtomicFileWriter.applyOwnerOnlyPerms(logPath)
                }
                writer = PrintWriter(fileWriter, true)
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
        val output: String? = null,
        val taskSummary: String? = null,
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
        val failureType: String? = null,
    )
}
