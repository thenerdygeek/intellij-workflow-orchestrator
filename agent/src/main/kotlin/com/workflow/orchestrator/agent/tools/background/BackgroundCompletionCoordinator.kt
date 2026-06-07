package com.workflow.orchestrator.agent.tools.background

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.agent.loop.AgentLoop
import java.io.File

/**
 * Routes [BackgroundPool] completion events to the right place — extracted from `AgentService`
 * (Phase 3 cut F). For a given completed background process it either:
 *  1. hands the formatted message to an installed test capturer (test seam), or
 *  2. enqueues it as a steering message on the session's live [AgentLoop], or
 *  3. (idle session) persists it for resume-replay AND fires a guarded auto-wake.
 *
 * The shared auto-wake substrate — the single `IdleSessionWaker`, its guard state, and the
 * controller-registered listener — deliberately stays on `AgentService` (it is also used by
 * cross-IDE delegation and the monitor framework). It is injected here as [autoWake] so all
 * async-completion paths keep sharing ONE guard cap/cooldown. [activeLoopForSession] and the
 * agent dir (lazy — `agentDir` is `lateinit`) are injected for the same reason.
 */
class BackgroundCompletionCoordinator(
    private val project: Project,
    private val agentDirProvider: () -> File,
    private val activeLoopForSession: (String) -> AgentLoop?,
    private val autoWake: (sessionId: String, syntheticText: String, source: String) -> Unit,
) : Disposable {

    private val log = Logger.getInstance(BackgroundCompletionCoordinator::class.java)

    /**
     * Test-only capture hooks keyed by sessionId — when set, completion messages for that
     * session are delivered to the callback instead of the live loop / persistence.
     */
    private val steeringCapturersForTest = java.util.concurrent.ConcurrentHashMap<String, (String) -> Unit>()

    private val persistence: BackgroundPersistence by lazy { BackgroundPersistence(agentDirProvider().toPath()) }

    /**
     * One completion event → exactly one steering message (no batching) so the LLM can react to
     * each process exit individually. The listener is torn down with this coordinator.
     */
    private val listenerDisposable: Disposable =
        BackgroundPool.getInstance(project).addCompletionListener { onBackgroundCompletion(it) }

    /** Install a capture callback for background-completion messages (test seam). */
    fun setSteeringCapturerForTest(sessionId: String, capture: (String) -> Unit) {
        steeringCapturersForTest[sessionId] = capture
    }

    /**
     * Handle a [BackgroundCompletionEvent]: test-capturer → live-loop steering → (idle) persist +
     * guarded auto-wake. Always persists before waking so the completion survives a guard-rejected
     * / deferred wake and replays on the next resume.
     */
    fun onBackgroundCompletion(event: BackgroundCompletionEvent) {
        val message = buildCompletionSystemMessage(event)

        steeringCapturersForTest[event.sessionId]?.let { capture ->
            runCatching { capture(message) }.onFailure {
                log.warn("steering capturer for ${event.sessionId} failed: ${it.message}")
            }
            return
        }

        val loop = activeLoopForSession(event.sessionId)
        if (loop != null) {
            loop.enqueueSteeringMessage(message)
        } else {
            // Idle loop. Persist first so the completion survives even if auto-wake is skipped
            // (disabled / capped / cooled / no listener) — the resume pickup replays it.
            persistForLaterResume(event)
            autoResumeForBackgroundCompletion(event.sessionId, event)
        }
    }

    private fun autoResumeForBackgroundCompletion(sessionId: String, event: BackgroundCompletionEvent) {
        autoWake(sessionId, buildAutoResumeSyntheticMessage(event), "bg ${event.bgId}")
    }

    private fun persistForLaterResume(event: BackgroundCompletionEvent) {
        runCatching {
            persistence.appendCompletion(event.sessionId, event)
        }.onFailure {
            log.warn(
                "[BackgroundCompletionCoordinator] BackgroundPersistence append failed for " +
                    "${event.sessionId}: ${it.message}",
                it,
            )
        }
    }

    override fun dispose() {
        Disposer.dispose(listenerDisposable)
    }

    companion object {
        /** Max chars of a process label echoed into a completion message. */
        private const val LABEL_MAX_CHARS = 80

        /** Number of trailing output lines included in a completion message. */
        private const val TAIL_LINES = 20

        /**
         * Build the system message injected as a steering message when a background process exits.
         * Stable `[BACKGROUND COMPLETION]` prefix; tail bounded to the last 20 lines (full output at
         * [BackgroundCompletionEvent.spillPath] when present). Pinned by
         * `BackgroundCompletionCoordinatorTest`.
         */
        fun buildCompletionSystemMessage(event: BackgroundCompletionEvent): String = buildString {
            appendLine("[BACKGROUND COMPLETION]")
            appendLine("Process ${event.bgId} (${event.kind}: \"${event.label.take(LABEL_MAX_CHARS)}\")")
            append("State: ${event.state}, exit code: ${event.exitCode}, ")
            appendLine("runtime: ${event.runtimeMs}ms")
            appendLine("Output (tail 20 lines):")
            event.tailContent.lines().takeLast(TAIL_LINES).forEach { appendLine("  $it") }
            if (event.spillPath != null) appendLine("Full output: ${event.spillPath}")
        }

        /**
         * Build the synthetic `[BACKGROUND COMPLETION — AUTO-RESUMED]` user message delivered to the
         * session on auto-wake. Keep the markers and section headers stable for downstream LLM
         * parsing. Pinned by `BackgroundCompletionCoordinatorTest`.
         */
        fun buildAutoResumeSyntheticMessage(event: BackgroundCompletionEvent): String = buildString {
            appendLine("[BACKGROUND COMPLETION — AUTO-RESUMED]")
            appendLine("Your previous turn ended, but a background process just completed:")
            appendLine()
            appendLine("Process ${event.bgId} (${event.kind}: \"${event.label.take(LABEL_MAX_CHARS)}\")")
            appendLine("State: ${event.state}, exit code: ${event.exitCode}, runtime: ${event.runtimeMs}ms")
            appendLine("Output (tail 20 lines):")
            event.tailContent.lines().takeLast(TAIL_LINES).forEach { appendLine("  $it") }
            if (event.spillPath != null) appendLine("Full output: ${event.spillPath}")
            appendLine()
            appendLine("Decide whether this needs action. If it completes the original task or")
            appendLine("requires no follow-up, call attempt_completion. Otherwise continue working.")
        }
    }
}
