package com.workflow.orchestrator.agent.delegation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-channel holder for the single in-flight question's answer deferred.
 *
 * The atomic compare-and-clear is the race-safety primitive that lets either
 * Agent-A's delegation_answer OR the IDE-B human's local answer win, but never
 * both. The losing party gets back a non-success signal it can handle gracefully.
 *
 * Lifecycle:
 * - [armIfClear] when sending a Question; returns false if a question is already pending.
 * - [tryResolve] when an Answer arrives (from either side); returns true if it won the race.
 * - [cancel] when the question is invalidated (e.g., delegation closed before answer).
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §4.2 + §6.3.
 */
class PendingQuestionToken {

    private data class Entry(val questionId: String, val deferred: CompletableDeferred<String>)

    private val slot = AtomicReference<Entry?>(null)

    /** Atomically arm the slot if empty. Returns true on success, false if a question is already pending. */
    fun armIfClear(questionId: String, deferred: CompletableDeferred<String>): Boolean =
        slot.compareAndSet(null, Entry(questionId, deferred))

    /**
     * Atomically clear the slot and complete the deferred — but only if [questionId] matches.
     * Returns true if this call won (and the deferred is now completed with [answer]).
     */
    fun tryResolve(questionId: String, answer: String): Boolean {
        val current = slot.get() ?: return false
        if (current.questionId != questionId) return false
        if (!slot.compareAndSet(current, null)) return false
        return current.deferred.complete(answer)
    }

    /** Clear the slot without delivering an answer. The deferred is cancelled with [reason]. */
    fun cancel(questionId: String, reason: String) {
        val current = slot.get() ?: return
        if (current.questionId != questionId) return
        if (slot.compareAndSet(current, null)) {
            current.deferred.cancel(CancellationException(reason))
        }
    }

    /**
     * Atomically clears the slot if any question is armed, and completes its deferred
     * with [answer]. Returns the questionId that was cleared, or null if nothing was armed.
     *
     * This eliminates the TOCTOU between reading [armedQuestionId] and calling [tryResolve]:
     * the stale-id window is gone because the clear+complete happen in a single CAS.
     */
    fun tryResolveCurrent(answer: String): String? {
        val current = slot.get() ?: return null
        if (!slot.compareAndSet(current, null)) return null
        current.deferred.complete(answer)
        return current.questionId
    }

    /** Returns the questionId currently armed, or null if none. */
    val armedQuestionId: String? get() = slot.get()?.questionId
}
