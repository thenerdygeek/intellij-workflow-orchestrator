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

    /** Graceful two-phase kill (SIGTERM → SIGKILL) via the underlying primitive. */
    fun kill(): Boolean

    /**
     * Register a completion callback. Fired exactly once per handle lifetime, on
     * EXITED / KILLED / TIMED_OUT. Adding after exit invokes immediately with the
     * retained terminal state.
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
    data class Idle(val reason: String, val lastOutput: String) : AttachResult()
    data class AttachTimeout(val elapsedMs: Long, val lastOutput: String) : AttachResult()
}
