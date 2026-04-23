package com.workflow.orchestrator.agent.tools.background

/**
 * Handle-agnostic contract for background units managed by BackgroundPool.
 *
 * v1 implementer: RunCommandBackgroundHandle (wraps ProcessRegistry.ManagedProcess).
 * Future implementers can wrap XDebugSession, RunContentDescriptor, HTTP handles, etc.
 * without BackgroundPool or background_process changing.
 */
interface BackgroundHandle {
    val bgId: String
    val kind: String
    val label: String
    val sessionId: String
    val startedAt: Long

    fun state(): BackgroundState
    fun exitCode(): Int?
    fun runtimeMs(): Long
    fun outputBytes(): Long

    /**
     * @param sinceOffset byte offset into the collected output. 0 = from start.
     * @param tailLines if non-null, returns last N lines of the available output window.
     */
    fun readOutput(sinceOffset: Long = 0, tailLines: Int? = null): OutputChunk

    /** @throws UnsupportedOperationException if kind does not support stdin. */
    fun sendStdin(input: String): Boolean = throw UnsupportedOperationException("UNSUPPORTED_FOR_KIND")

    /**
     * Re-enter a blocking monitor loop for this process.
     * @param timeoutMs hard ceiling; returns ATTACH_TIMEOUT beyond this.
     */
    suspend fun attach(timeoutMs: Long): AttachResult

    /**
     * Graceful two-phase kill (SIGTERM → wait → SIGKILL) via the underlying primitive.
     *
     * Idempotent: safe to call multiple times or on an already-terminated handle.
     *
     * @return true if the process was live and the kill was actually sent; false if
     *         the handle was already in a terminal state when called.
     */
    fun kill(): Boolean

    /**
     * Register a completion callback. Fired exactly once per registered callback, on
     * EXITED / KILLED / TIMED_OUT. Adding after the handle has already terminated
     * invokes immediately with the retained terminal state.
     *
     * Threading: the callback may be invoked from any thread (typically an IO /
     * process-listener thread). Callers MUST NOT perform EDT operations directly
     * inside the callback — post to a coroutine scope or use invokeLater if UI
     * work is needed.
     *
     * Exceptions thrown by the callback are caught and logged by the handle; they
     * do not prevent other registered callbacks from firing.
     *
     * Multiple registrations are permitted; each registered callback fires exactly
     * once.
     */
    fun onComplete(callback: (event: BackgroundCompletionEvent) -> Unit)
}

data class OutputChunk(
    val content: String,
    val nextOffset: Long,
    val truncated: Boolean,
    val spillPath: String?,
)

sealed class AttachResult {
    data class Exited(val exitCode: Int, val output: String) : AttachResult()
    /**
     * Process went idle (no output for the configured idle threshold) during attach.
     *
     * [reason] carries a short human-readable classification string (e.g.
     * `LIKELY_STDIN_PROMPT`, `LIKELY_PASSWORD_PROMPT`, `GENERIC_IDLE`). These
     * values are produced by `PromptHeuristics.IdleClassification` (added in a
     * later task); keep the classification names stable so downstream tool
     * output can match on them.
     */
    data class Idle(val reason: String, val lastOutput: String) : AttachResult()
    data class AttachTimeout(val elapsedMs: Long, val lastOutput: String) : AttachResult()
}
