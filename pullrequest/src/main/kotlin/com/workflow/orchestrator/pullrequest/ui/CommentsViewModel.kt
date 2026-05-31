package com.workflow.orchestrator.pullrequest.ui

import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.PrComment
import com.workflow.orchestrator.core.services.BitbucketService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Headless state + service orchestration for the Comments sub-tab.
 * No Swing imports — fully testable with MockK.
 *
 * Thread-safety: intended to be called from a single coroutine scope
 * (Dispatchers.IO). UI change listeners are fired from whatever thread
 * calls refresh/postGeneralComment/etc.; callers must dispatch to EDT.
 *
 * @param eventBus optional EventBus instance for cross-module event publication.
 *   Omit (null) in unit tests that don't need event coverage.
 */
class CommentsViewModel(
    private val service: BitbucketService,
    private val projectKey: String,
    private val repoSlug: String,
    private val prId: Int,
    private val eventBus: EventBus? = null,
) {
    private val commentsMutex = Mutex()
    private val _comments: MutableList<PrComment> = mutableListOf()

    /**
     * Snapshot read — safe for concurrent callers without holding the mutex.
     * All mutations go through [commentsMutex] to prevent data races (F-10 fix).
     */
    val comments: List<PrComment> get() = _comments.toList()

    var lastError: String? = null
        private set

    private val listeners = mutableListOf<() -> Unit>()

    fun addChangeListener(l: () -> Unit) {
        listeners += l
    }

    private fun fire() {
        listeners.forEach { runCatching { it() } }
    }

    suspend fun refresh() {
        val result = service.listPrComments(
            projectKey = projectKey,
            repoSlug = repoSlug,
            prId = prId,
            onlyOpen = false,
            onlyInline = false,
        )
        commentsMutex.withLock {
            if (result.isError) {
                lastError = result.summary.ifBlank { "Failed to list comments" }
            } else {
                lastError = null
                _comments.clear()
                _comments.addAll(result.data!!)
            }
        }
        if (!result.isError) {
            eventBus?.emit(
                WorkflowEvent.PrCommentsUpdated(
                    projectKey = projectKey,
                    repoSlug = repoSlug,
                    prId = prId,
                    total = comments.size,  // snapshot after lock released
                    unreadCount = 0,
                )
            )
        }
        fire()
    }

    suspend fun postGeneralComment(text: String): Boolean {
        val result = service.addPrComment(prId = prId, text = text)
        if (result.isError) {
            lastError = result.summary.ifBlank { "Failed to post comment" }
            fire()
            return false
        }
        refresh()
        return true
    }

    suspend fun reply(parentCommentId: Long, text: String): Boolean {
        // SEC-24: guard against overflow before narrowing to Int (mirrors ActivitySubPanel).
        if (parentCommentId < Int.MIN_VALUE || parentCommentId > Int.MAX_VALUE) {
            lastError = "Comment ID $parentCommentId exceeds Int range"
            fire()
            return false
        }
        val result = service.replyToComment(
            prId = prId,
            parentCommentId = parentCommentId.toInt(),
            text = text,
        )
        if (result.isError) {
            lastError = result.summary.ifBlank { "Failed to reply" }
            fire()
            return false
        }
        refresh()
        return true
    }

    suspend fun resolve(commentId: Long): Boolean {
        val result = service.resolvePrComment(
            projectKey = projectKey,
            repoSlug = repoSlug,
            prId = prId,
            commentId = commentId,
        )
        if (result.isError) {
            lastError = result.summary.ifBlank { "Failed to resolve comment" }
            fire()
            return false
        }
        refresh()
        return true
    }

    suspend fun reopen(commentId: Long): Boolean {
        val result = service.reopenPrComment(
            projectKey = projectKey,
            repoSlug = repoSlug,
            prId = prId,
            commentId = commentId,
        )
        if (result.isError) {
            lastError = result.summary.ifBlank { "Failed to reopen comment" }
            fire()
            return false
        }
        refresh()
        return true
    }
}
