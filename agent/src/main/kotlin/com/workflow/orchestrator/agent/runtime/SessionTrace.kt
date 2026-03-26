package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.security.CredentialRedactor
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Rich session trace for debugging agent sessions against real APIs.
 *
 * Unlike [AgentEventLog] which records *what* happened (audit trail),
 * SessionTrace records *everything needed to debug why* something happened:
 * - Per-iteration metrics (tokens, timing, budget utilization)
 * - HTTP wire data (request/response bodies, status codes, latencies)
 * - Conversation state snapshots
 * - Compression events with before/after token counts
 * - Full diagnostic dump on failure
 *
 * Output: `{sessionDir}/traces/trace.jsonl`
 *
 * Design: append-only JSONL with typed entries. Each line is self-contained
 * so partial traces are still useful if the process crashes mid-session.
 */
class SessionTrace(
    private val sessionId: String,
    private val sessionDir: File
) {
    companion object {
        private val LOG = Logger.getInstance(SessionTrace::class.java)
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }

    private val traceFile: File by lazy {
        val dir = File(sessionDir, "traces")
        dir.mkdirs()
        File(dir, "trace.jsonl")
    }

    private var sessionStartMs = System.currentTimeMillis()
    private var iterationStartMs = 0L

    // --- Session lifecycle ---

    fun sessionStarted(task: String, toolCount: Int, reservedTokens: Int, effectiveBudget: Int) {
        sessionStartMs = System.currentTimeMillis()
        append(TraceEntry(
            type = "session_started",
            task = task.take(500),
            toolCount = toolCount,
            reservedTokens = reservedTokens,
            effectiveBudget = effectiveBudget
        ))
    }

    fun sessionCompleted(totalTokens: Int, iterations: Int, artifacts: List<String>) {
        append(TraceEntry(
            type = "session_completed",
            totalTokens = totalTokens,
            iteration = iterations,
            durationMs = System.currentTimeMillis() - sessionStartMs,
            artifacts = artifacts
        ))
    }

    fun sessionFailed(error: String, totalTokens: Int, iterations: Int) {
        append(TraceEntry(
            type = "session_failed",
            error = error,
            totalTokens = totalTokens,
            iteration = iterations,
            durationMs = System.currentTimeMillis() - sessionStartMs
        ))
    }

    // --- Per-iteration metrics ---

    fun iterationStarted(iteration: Int, budgetUsedTokens: Int, budgetPercent: Int) {
        iterationStartMs = System.currentTimeMillis()
        append(TraceEntry(
            type = "iteration_started",
            iteration = iteration,
            budgetUsedTokens = budgetUsedTokens,
            budgetPercent = budgetPercent
        ))
    }

    fun iterationCompleted(
        iteration: Int,
        promptTokens: Int,
        completionTokens: Int,
        toolsCalled: List<String>,
        finishReason: String?
    ) {
        append(TraceEntry(
            type = "iteration_completed",
            iteration = iteration,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            durationMs = System.currentTimeMillis() - iterationStartMs,
            toolsCalled = toolsCalled,
            finishReason = finishReason
        ))
    }

    // --- HTTP wire logging ---

    fun httpRequest(
        method: String,
        url: String,
        bodyLength: Int,
        messageCount: Int,
        toolDefCount: Int,
        maxTokens: Int?
    ) {
        append(TraceEntry(
            type = "http_request",
            httpMethod = method,
            httpUrl = url,
            httpBodyLength = bodyLength,
            messageCount = messageCount,
            toolCount = toolDefCount,
            maxOutputTokens = maxTokens
        ))
    }

    fun httpResponse(
        statusCode: Int,
        bodyLength: Int,
        durationMs: Long,
        promptTokens: Int?,
        completionTokens: Int?,
        finishReason: String?,
        error: String? = null
    ) {
        append(TraceEntry(
            type = "http_response",
            httpStatusCode = statusCode,
            httpBodyLength = bodyLength,
            durationMs = durationMs,
            promptTokens = promptTokens ?: 0,
            completionTokens = completionTokens ?: 0,
            finishReason = finishReason,
            error = error
        ))
    }

    // --- Tool execution ---

    fun toolExecuted(
        toolName: String,
        durationMs: Long,
        resultTokens: Int,
        isError: Boolean,
        errorMessage: String? = null
    ) {
        append(TraceEntry(
            type = "tool_executed",
            toolName = toolName,
            durationMs = durationMs,
            totalTokens = resultTokens,
            isError = isError,
            error = errorMessage
        ))
    }

    // --- Compression events ---

    fun compressionTriggered(
        trigger: String, // "budget_enforcer" or "auto_tmax" or "context_exceeded"
        tokensBefore: Int,
        tokensAfter: Int,
        messagesDropped: Int
    ) {
        append(TraceEntry(
            type = "compression",
            compressionTrigger = trigger,
            tokensBefore = tokensBefore,
            tokensAfter = tokensAfter,
            messagesDropped = messagesDropped
        ))
    }

    // --- Diagnostic dump on failure ---

    fun dumpConversationState(messages: List<ChatMessage>, reason: String) {
        val messageSummaries = messages.map { msg ->
            val contentPreview = msg.content?.take(200) ?: "[null]"
            val toolCallSummary = msg.toolCalls?.joinToString(", ") { it.function.name } ?: ""
            MessageSummary(
                role = msg.role,
                contentLength = msg.content?.length ?: 0,
                contentPreview = contentPreview,
                toolCalls = toolCallSummary.ifEmpty { null },
                tokenEstimate = TokenEstimator.estimate(listOf(msg))
            )
        }
        append(TraceEntry(
            type = "conversation_dump",
            dumpReason = reason,
            messageCount = messages.size,
            messageSummaries = messageSummaries
        ))
    }

    // --- Rate limiting ---

    fun rateLimitRetry(attempt: Int, backoffMs: Long) {
        append(TraceEntry(
            type = "rate_limit_retry",
            retryAttempt = attempt,
            durationMs = backoffMs
        ))
    }

    fun contextExceededRetry(toolsBefore: Int, toolsAfter: Int, tokensBefore: Int) {
        append(TraceEntry(
            type = "context_exceeded_retry",
            toolCount = toolsAfter,
            tokensBefore = tokensBefore,
            toolCountBefore = toolsBefore
        ))
    }

    // --- Session metrics ---

    fun sessionMetrics(metricsJson: String) {
        append(TraceEntry(
            type = "session_metrics",
            metricsJson = metricsJson
        ))
    }

    // --- Internal ---

    private fun append(entry: TraceEntry) {
        val enriched = entry.copy(
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId
        )
        try {
            val jsonStr = json.encodeToString(enriched)
            traceFile.appendText(CredentialRedactor.redact(jsonStr) + "\n")
        } catch (e: Exception) {
            LOG.debug("SessionTrace: failed to write trace entry: ${e.message}")
        }
    }

    /**
     * A single trace entry. Uses a flat structure with nullable fields
     * rather than polymorphic types for simple JSONL parsing.
     */
    @Serializable
    data class TraceEntry(
        val type: String,
        val timestamp: Long = 0,
        val sessionId: String = "",

        // Session
        val task: String? = null,
        val toolCount: Int? = null,
        val reservedTokens: Int? = null,
        val effectiveBudget: Int? = null,
        val artifacts: List<String>? = null,

        // Iteration
        val iteration: Int? = null,
        val budgetUsedTokens: Int? = null,
        val budgetPercent: Int? = null,

        // Tokens
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val totalTokens: Int? = null,
        val maxOutputTokens: Int? = null,

        // Timing
        val durationMs: Long? = null,

        // HTTP
        val httpMethod: String? = null,
        val httpUrl: String? = null,
        val httpStatusCode: Int? = null,
        val httpBodyLength: Int? = null,
        val messageCount: Int? = null,

        // Tool
        val toolName: String? = null,
        val toolsCalled: List<String>? = null,
        val isError: Boolean? = null,

        // Compression
        val compressionTrigger: String? = null,
        val tokensBefore: Int? = null,
        val tokensAfter: Int? = null,
        val messagesDropped: Int? = null,
        val toolCountBefore: Int? = null,

        // Response
        val finishReason: String? = null,
        val error: String? = null,

        // Retry
        val retryAttempt: Int? = null,

        // Conversation dump
        val dumpReason: String? = null,
        val messageSummaries: List<MessageSummary>? = null,

        // Session metrics
        val metricsJson: String? = null
    )

    @Serializable
    data class MessageSummary(
        val role: String,
        val contentLength: Int,
        val contentPreview: String,
        val toolCalls: String? = null,
        val tokenEstimate: Int
    )
}
