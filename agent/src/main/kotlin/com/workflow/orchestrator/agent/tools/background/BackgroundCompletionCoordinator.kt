package com.workflow.orchestrator.agent.tools.background

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.agent.loop.queue.BackgroundQueuePolicy
import com.workflow.orchestrator.agent.loop.queue.QueueSourceKind
import com.workflow.orchestrator.agent.loop.queue.QueuedMessage

/**
 * Routes [BackgroundPool] completion events to the unified queue as [QueueSourceKind.BACKGROUND]
 * items — extracted from `AgentService` (Phase 3 cut F).
 *
 * For a given completed background process it either:
 *  1. hands the formatted message to an installed test capturer (test seam), or
 *  2. enqueues it as a [QueuedMessage] via [enqueue] so the UnifiedMessageQueue + enqueueToSession
 *     own both persistence and idle-wake logic (Task 2.2).
 *
 * The old `activeLoopForSession` + `autoWake` + `BackgroundPersistence` plumbing is replaced by
 * the single [enqueue] lambda, wired in `AgentService` to `::enqueueToSession`.
 */
class BackgroundCompletionCoordinator(
    private val project: Project,
    private val enqueue: (sessionId: String, msg: QueuedMessage) -> Unit,
) : Disposable {

    private val log = Logger.getInstance(BackgroundCompletionCoordinator::class.java)

    /**
     * Test-only capture hooks keyed by sessionId — when set, completion messages for that
     * session are delivered to the callback instead of the queue.
     */
    internal val steeringCapturersForTest = java.util.concurrent.ConcurrentHashMap<String, (String) -> Unit>()

    /**
     * One completion event → exactly one queue item (no batching) so the LLM can react to
     * each process exit individually. The listener is torn down with this coordinator.
     */
    private val listenerDisposable: Disposable =
        BackgroundPool.getInstance(project).addCompletionListener { onBackgroundCompletion(it) }

    /** Install a capture callback for background-completion messages (test seam). */
    fun setSteeringCapturerForTest(sessionId: String, capture: (String) -> Unit) {
        steeringCapturersForTest[sessionId] = capture
    }

    /**
     * Handle a [BackgroundCompletionEvent]: test-capturer (short-circuit) → enqueue as a
     * [QueueSourceKind.BACKGROUND] message. The queue + [enqueue] own idle-wake and persistence.
     */
    fun onBackgroundCompletion(event: BackgroundCompletionEvent) {
        val message = buildCompletionSystemMessage(event)
        steeringCapturersForTest[event.sessionId]?.let { capture ->
            runCatching { capture(message) }.onFailure { log.warn("capturer failed: ${it.message}") }
            return
        }
        enqueue(
            event.sessionId,
            QueuedMessage(
                id = "bg-${event.bgId}-${System.nanoTime()}",
                kind = QueueSourceKind.BACKGROUND,
                body = message,
                timestamp = System.currentTimeMillis(),
                priority = BackgroundQueuePolicy.priority,
                coalesceKey = event.bgId,
                meta = mapOf(
                    "bgId" to event.bgId,
                    "card" to kotlinx.serialization.json.Json.encodeToString(
                        com.workflow.orchestrator.agent.session.AsyncEventCardData.serializer(),
                        com.workflow.orchestrator.agent.ui.AsyncEventCardPresenter.fromBackground(event),
                    ),
                ),
            ),
        )
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
         * Build the system message body for a background-process completion event.
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
    }
}
