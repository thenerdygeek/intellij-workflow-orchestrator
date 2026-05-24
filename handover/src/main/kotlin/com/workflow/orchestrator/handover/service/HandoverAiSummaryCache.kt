package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.ai.TextGenerationService
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import com.workflow.orchestrator.handover.model.HandoverPlaceholderValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-scoped cache for AI-generated handover summaries.
 *
 * Cache key: [CacheKey] — `(ticketId, sha, kind)`. Using `Deferred<HandoverPlaceholderValue>`
 * as the map value means concurrent callers share one in-flight compute: the second caller
 * `.await()`s the same [Deferred] rather than launching a duplicate LLM request.
 *
 * Invalidation: subscribes to [EventBus] and calls [invalidate] on [WorkflowEvent.BranchChanged]
 * and [WorkflowEvent.TicketChanged] so stale results are never served after context switches.
 *
 * Failure handling: if [TextGenerationService] is missing or throws, returns
 * [HandoverPlaceholderValue.unavailable] and fires a [WorkflowNotificationService.notifyWarning]
 * once per session (guarded by [notifiedOnce] [AtomicBoolean]).
 *
 * Diff capture is intentionally deferred (T8 follow-up): [Kind.CHANGE_SUMMARY] prompts include a
 * placeholder string instead of a real `git diff`. The cache machinery, EP wiring, and
 * invalidation all work end-to-end; wiring a real diff is a follow-up task.
 */
@Service(Service.Level.PROJECT)
class HandoverAiSummaryCache {

    /** Identifies a cacheable AI generation request. */
    data class CacheKey(val ticketId: String, val sha: String, val kind: Kind)

    enum class Kind { CHANGE_SUMMARY, TICKET_SUMMARY }

    /**
     * Internal functional interface wrapping the actual LLM call.
     * Exists so the @TestOnly constructor can inject a mock without needing a Project.
     */
    fun interface TextGenerator {
        suspend fun generate(prompt: String): String?
    }

    private val log = Logger.getInstance(HandoverAiSummaryCache::class.java)

    private val generator: TextGenerator?
    private val workflowContext: WorkflowContextService
    private val notifications: WorkflowNotificationService?
    private val cs: CoroutineScope

    // ConcurrentHashMap so reads never block; Deferred coalesces concurrent misses.
    private val cache = ConcurrentHashMap<CacheKey, Deferred<HandoverPlaceholderValue>>()

    // Suppress notification spam — only warn once per plugin session.
    private val notifiedOnce = AtomicBoolean(false)

    // Stable cache-bust token used when no real commit SHA is available.
    private val NO_SHA = "no-sha"

    /** IntelliJ DI constructor. */
    constructor(project: Project, cs: CoroutineScope) {
        val svc = TextGenerationService.getInstance()
        this.generator = svc?.let { s -> TextGenerator { prompt -> s.generateText(project, prompt) } }
        this.workflowContext = WorkflowContextService.getInstance(project)
        this.notifications = WorkflowNotificationService.getInstance(project)
        this.cs = cs
        subscribeToInvalidationEvents(project)
    }

    /**
     * Test constructor — allows injecting collaborators without a running IDE.
     *
     * Pass `null` for [generator] to simulate a missing [TextGenerationService] EP.
     */
    @TestOnly
    constructor(
        generator: TextGenerator?,
        workflowContext: WorkflowContextService,
        notifications: WorkflowNotificationService?,
        eventBus: EventBus,
        scope: CoroutineScope,
    ) {
        this.generator = generator
        this.workflowContext = workflowContext
        this.notifications = notifications
        this.cs = scope
        subscribeToInvalidationEvents(eventBus)
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns an AI-generated change summary for the active ticket.
     *
     * Cache hit: returns the same [Deferred] result — no duplicate LLM call.
     * Cache miss: launches one async compute; all concurrent callers await the same [Deferred].
     *
     * Diff capture is deferred (see class KDoc) — the prompt uses a placeholder string.
     */
    suspend fun changeSummary(): HandoverPlaceholderValue {
        val ticketId = activeTicketId()
            ?: return HandoverPlaceholderValue.unavailable("no active ticket")
        val key = CacheKey(ticketId, NO_SHA, Kind.CHANGE_SUMMARY)
        return getOrCompute(key) { buildPromptFor(Kind.CHANGE_SUMMARY, ticketId) }
    }

    /**
     * Returns an AI-generated ticket summary for the active ticket.
     *
     * Cache hit / miss semantics identical to [changeSummary].
     */
    suspend fun ticketSummary(): HandoverPlaceholderValue {
        val ticketId = activeTicketId()
            ?: return HandoverPlaceholderValue.unavailable("no active ticket")
        val key = CacheKey(ticketId, NO_SHA, Kind.TICKET_SUMMARY)
        return getOrCompute(key) { buildPromptFor(Kind.TICKET_SUMMARY, ticketId) }
    }

    /** Clears all cached entries. Called by event invalidation; also usable in tests. */
    fun invalidate() {
        cache.clear()
    }

    // -------------------------------------------------------------------------
    // Core cache logic
    // -------------------------------------------------------------------------

    /**
     * Returns the cached [Deferred] for [key], computing a new one on a miss.
     *
     * [promptBuilder] is only evaluated on a cache miss. [ConcurrentHashMap.computeIfAbsent]
     * is atomic — concurrent misses for the same key produce exactly one [Deferred].
     *
     * D5 (audit finding handover:F-3) — cancelled entry cleanup:
     * The `async { }` lambda is parented to a transient `coroutineScope { }` that exits
     * as soon as `computeIfAbsent` returns. If the calling coroutine is cancelled before
     * or during `await()`, the Deferred is left in a cancelled state but remains in the
     * ConcurrentHashMap permanently — poisoning the cache key for future callers, who
     * would immediately get a CancellationException on their own `.await()` instead of
     * triggering a fresh compute.
     *
     * Fix: wrap `await()` in try/catch. On [CancellationException] OR any other failure,
     * remove the entry from the map before rethrowing, so the next caller gets a clean miss.
     */
    private suspend fun getOrCompute(
        key: CacheKey,
        promptBuilder: () -> String,
    ): HandoverPlaceholderValue {
        val gen = generator
            ?: return unavailableAndMaybeNotify("AI text generation service is not available")

        val deferred: Deferred<HandoverPlaceholderValue> = coroutineScope {
            cache.computeIfAbsent(key) {
                async {
                    runCatching {
                        val prompt = promptBuilder()
                        val result = gen.generate(prompt)
                        if (result != null) {
                            HandoverPlaceholderValue.available(result)
                        } else {
                            unavailableAndMaybeNotify("AI service returned no result")
                        }
                    }.getOrElse { ex ->
                        log.warn("[Handover:AiCache] Generation failed for $key: ${ex.message}")
                        unavailableAndMaybeNotify("AI service error: ${ex.message ?: "unknown"}")
                    }
                }
            }
        }
        return try {
            deferred.await()
        } catch (e: Exception) {
            // D5: remove poisoned entry so the next caller gets a fresh compute.
            // This covers CancellationException (coroutine was cancelled mid-await)
            // and any exception thrown by the async block itself.
            cache.remove(key, deferred)
            throw e
        }
    }

    // -------------------------------------------------------------------------
    // Prompt construction
    // -------------------------------------------------------------------------

    private fun buildPromptFor(kind: Kind, ticketId: String): String {
        val ticket = workflowContext.state.value.activeTicket
        return when (kind) {
            Kind.CHANGE_SUMMARY -> {
                // Diff capture is deferred — see class KDoc.
                val diffPlaceholder = "<diff capture deferred — see T8 follow-up>"
                """
                Summarise the changes being handed over for ticket $ticketId.
                Ticket summary: ${ticket?.summary ?: ticketId}

                Git diff (abbreviated):
                $diffPlaceholder

                Write a concise 2-3 sentence change summary suitable for a QA handover note.
                """.trimIndent()
            }

            Kind.TICKET_SUMMARY -> {
                val summary = ticket?.summary ?: ticketId
                """
                Write a concise 1-2 sentence description of what ticket $ticketId is about,
                based on its summary: "$summary"

                Focus on what the user-facing outcome is, not implementation details.
                """.trimIndent()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun unavailableAndMaybeNotify(reason: String): HandoverPlaceholderValue {
        if (notifiedOnce.compareAndSet(false, true)) {
            notifications?.notifyWarning(
                groupId = "workflow.handover",
                title = "AI Summary Unavailable",
                content = reason
            )
            log.warn("[Handover:AiCache] $reason (notification suppressed after first miss)")
        }
        return HandoverPlaceholderValue.unavailable(reason)
    }

    // -------------------------------------------------------------------------
    // EventBus subscription
    // -------------------------------------------------------------------------

    private fun subscribeToInvalidationEvents(project: Project) {
        subscribeToInvalidationEvents(project.getService(EventBus::class.java))
    }

    private fun subscribeToInvalidationEvents(eventBus: EventBus) {
        cs.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is WorkflowEvent.BranchChanged,
                    is WorkflowEvent.TicketChanged -> {
                        log.debug("[Handover:AiCache] Invalidating cache on ${event::class.simpleName}")
                        invalidate()
                    }
                    else -> Unit
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun activeTicketId(): String? =
        workflowContext.state.value.activeTicket?.key?.takeIf { it.isNotBlank() }

    companion object {
        fun getInstance(project: Project): HandoverAiSummaryCache =
            project.getService(HandoverAiSummaryCache::class.java)
    }
}
