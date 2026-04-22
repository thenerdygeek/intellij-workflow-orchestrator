package com.workflow.orchestrator.pullrequest.ui

import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.PrComment
import com.workflow.orchestrator.core.services.BitbucketService

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
    private val _comments: MutableList<PrComment> = mutableListOf()
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
        if (result.isError) {
            lastError = result.summary.ifBlank { "Failed to list comments" }
        } else {
            lastError = null
            _comments.clear()
            _comments.addAll(result.data)
            eventBus?.emit(
                WorkflowEvent.PrCommentsUpdated(
                    projectKey = projectKey,
                    repoSlug = repoSlug,
                    prId = prId,
                    total = _comments.size,
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
