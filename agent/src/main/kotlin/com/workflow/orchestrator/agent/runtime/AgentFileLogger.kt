package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.agent.security.CredentialRedactor
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Structured JSONL logger for agent activity, written to a daily rotating file at
 * `~/.workflow-orchestrator/agent/logs/agent-YYYY-MM-DD.jsonl`.
 *
 * Always active, zero UI impact. Writes are async (fire-and-forget via an IO-bound
 * coroutine scope) so callers are never blocked. Thread safety on file rotation is
 * guaranteed by `@Synchronized` on [getWriter].
 *
 * On init:
 * - The log directory is created if absent.
 * - Files older than 7 days are pruned.
 *
 * Lifecycle: tied to a parent [Disposable]. When the disposable is disposed the
 * coroutine scope is cancelled and the current [PrintWriter] is closed.
 *
 * All string fields (args, results, errors) are:
 * - Credential-redacted via [CredentialRedactor.redact]
 * - Truncated to a safe maximum to prevent unbounded file growth
 */
class AgentFileLogger(parent: Disposable, private val projectBasePath: String) : Disposable {

    // -------------------------------------------------------------------------
    // Companion / constants
    // -------------------------------------------------------------------------

    companion object {
        private val LOG = Logger.getInstance(AgentFileLogger::class.java)

        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TS_FMT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

        private const val MAX_ARGS_CHARS    = 500
        private const val MAX_RESULT_CHARS  = 300
        private const val MAX_ERROR_CHARS   = 500

        private val JSON = Json {
            encodeDefaults = false   // omit null fields → compact JSONL lines
            explicitNulls  = false
        }
    }

    // -------------------------------------------------------------------------
    // Infrastructure
    // -------------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val logDir: File = ProjectIdentifier.logsDir(projectBasePath).also { it.mkdirs() }

    /** Active writer — replaced on day roll-over. Protected by [getWriter]. */
    @Volatile
    private var currentWriter: PrintWriter? = null

    /** The date suffix of the file that [currentWriter] is open on. */
    @Volatile
    private var currentDateKey: String = ""

    init {
        // Register with parent so disposal is automatic
        Disposer.register(parent, this)

        // Best-effort retention cleanup — on IO thread (directory already created at init)
        scope.launch {
            try {
                pruneOldLogs()
            } catch (e: Exception) {
                LOG.warn("AgentFileLogger: init cleanup failed", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Disposable
    // -------------------------------------------------------------------------

    override fun dispose() {
        scope.cancel()
        synchronized(this) {
            currentWriter?.close()
            currentWriter = null
        }
    }

    // -------------------------------------------------------------------------
    // Public API — log methods
    // -------------------------------------------------------------------------

    /**
     * Log a single tool call (success, error, or skipped).
     */
    fun logToolCall(
        sessionId: String,
        toolName: String,
        args: String?,
        status: String,           // "success" | "error" | "skipped"
        result: String? = null,
        errorMessage: String? = null,
        durationMs: Long? = null,
        tokenEstimate: Int? = null
    ) {
        enqueue(AgentLogEntry(
            ts        = now(),
            session   = shortId(sessionId),
            event     = "tool_call",
            tool      = toolName,
            status    = status,
            args      = args?.let    { redact(it, MAX_ARGS_CHARS) },
            result    = result?.let  { redact(it, MAX_RESULT_CHARS) },
            error     = errorMessage?.let { redact(it, MAX_ERROR_CHARS) },
            durationMs = durationMs,
            tokens    = tokenEstimate
        ))
    }

    /**
     * Log the completion of one ReAct iteration.
     */
    fun logIteration(
        sessionId: String,
        iteration: Int,
        promptTokens: Int,
        completionTokens: Int,
        finishReason: String?,
        toolsCalled: List<String>,
        durationMs: Long
    ) {
        enqueue(AgentLogEntry(
            ts               = now(),
            session          = shortId(sessionId),
            event            = "iteration",
            iteration        = iteration,
            promptTokens     = promptTokens,
            completionTokens = completionTokens,
            finishReason     = finishReason,
            toolsCalled      = toolsCalled.ifEmpty { null },
            durationMs       = durationMs
        ))
    }

    /**
     * Log a retry event (rate-limit, context overflow, etc.).
     */
    fun logRetry(
        sessionId: String,
        reason: String,
        iteration: Int
    ) {
        enqueue(AgentLogEntry(
            ts        = now(),
            session   = shortId(sessionId),
            event     = "retry",
            error     = redact(reason, MAX_ERROR_CHARS),
            iteration = iteration
        ))
    }

    /**
     * Log a malformed / unparseable tool call detected in the LLM response.
     */
    fun logMalformedToolCall(
        sessionId: String,
        toolName: String?,
        rawArgs: String?,
        reason: String
    ) {
        enqueue(AgentLogEntry(
            ts      = now(),
            session = shortId(sessionId),
            event   = "malformed_tool_call",
            tool    = toolName,
            args    = rawArgs?.let { redact(it, MAX_ARGS_CHARS) },
            error   = redact(reason, MAX_ERROR_CHARS)
        ))
    }

    /**
     * Log session start — captures intent and tool surface size.
     */
    fun logSessionStart(
        sessionId: String,
        task: String,
        toolCount: Int
    ) {
        enqueue(AgentLogEntry(
            ts      = now(),
            session = shortId(sessionId),
            event   = "session_start",
            args    = redact(task, MAX_ARGS_CHARS),   // task as args field
            tokens  = toolCount                        // toolCount as tokens field (context)
        ))
    }

    /**
     * Log session end — success or failure.
     */
    fun logSessionEnd(
        sessionId: String,
        iterations: Int,
        totalTokens: Int,
        durationMs: Long,
        error: String? = null
    ) {
        enqueue(AgentLogEntry(
            ts         = now(),
            session    = shortId(sessionId),
            event      = if (error == null) "session_end" else "session_failed",
            iteration  = iterations,
            tokens     = totalTokens,
            durationMs = durationMs,
            error      = error?.let { redact(it, MAX_ERROR_CHARS) }
        ))
    }

    /**
     * Log a context-compression event with token before/after.
     */
    fun logCompression(
        sessionId: String,
        trigger: String,
        tokensBefore: Int,
        tokensAfter: Int
    ) {
        enqueue(AgentLogEntry(
            ts           = now(),
            session      = shortId(sessionId),
            event        = "compression",
            args         = trigger,
            promptTokens = tokensBefore,
            completionTokens = tokensAfter
        ))
    }

    // -------------------------------------------------------------------------
    // Internal — async dispatch
    // -------------------------------------------------------------------------

    private fun enqueue(entry: AgentLogEntry) {
        scope.launch {
            try {
                val line = JSON.encodeToString(entry)
                val safeLine = CredentialRedactor.redact(line)
                getWriter().println(safeLine)
            } catch (e: Exception) {
                LOG.debug("AgentFileLogger: failed to write log entry: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal — writer management with daily rotation
    // -------------------------------------------------------------------------

    @Synchronized
    private fun getWriter(): PrintWriter {
        val today = LocalDate.now(ZoneOffset.UTC).format(DATE_FMT)
        if (today != currentDateKey) {
            // Roll over to new file
            currentWriter?.close()
            currentDateKey = today
            val file = File(logDir, "agent-$today.jsonl")
            currentWriter = PrintWriter(FileWriter(file, /* append = */ true), /* autoFlush = */ true)
        }
        return currentWriter!!
    }

    // -------------------------------------------------------------------------
    // Internal — 7-day retention cleanup
    // -------------------------------------------------------------------------

    private fun pruneOldLogs() {
        val cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(7)
        logDir.listFiles { f ->
            f.isFile && f.name.startsWith("agent-") && f.name.endsWith(".jsonl")
        }?.forEach { file ->
            try {
                val dateStr = file.name.removePrefix("agent-").removeSuffix(".jsonl")
                val fileDate = LocalDate.parse(dateStr, DATE_FMT)
                if (fileDate.isBefore(cutoff)) {
                    if (file.delete()) {
                        LOG.debug("AgentFileLogger: pruned old log ${file.name}")
                    }
                }
            } catch (_: Exception) {
                // Skip files that don't match the expected naming pattern
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal — helpers
    // -------------------------------------------------------------------------

    private fun now(): String = TS_FMT.format(Instant.now())

    private fun shortId(sessionId: String): String = sessionId.take(11)

    private fun redact(text: String, maxChars: Int): String =
        CredentialRedactor.redact(text).take(maxChars)
}

// ---------------------------------------------------------------------------
// Log entry model
// ---------------------------------------------------------------------------

/**
 * A single structured log entry serialized as one JSONL line.
 *
 * Uses a flat nullable-field design (same pattern as [SessionTrace.TraceEntry]) so:
 * - Every event type maps to the same class — no polymorphism overhead
 * - Fields absent for a given event type serialize as omitted (via `explicitNulls = false`)
 * - Consumers can parse with `ignoreUnknownKeys = true` and evolve the schema safely
 */
@Serializable
data class AgentLogEntry(
    val ts: String,
    val session: String,
    val event: String,
    val tool: String? = null,
    val status: String? = null,       // "success" | "error" | "skipped"
    val args: String? = null,         // tool args or task description (≤ 500 chars)
    val result: String? = null,       // tool result summary (≤ 300 chars)
    val error: String? = null,        // error or retry reason (≤ 500 chars)
    val durationMs: Long? = null,
    val tokens: Int? = null,          // generic token count (tool estimate / total / tool count)
    val iteration: Int? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val finishReason: String? = null,
    val toolsCalled: List<String>? = null
)
