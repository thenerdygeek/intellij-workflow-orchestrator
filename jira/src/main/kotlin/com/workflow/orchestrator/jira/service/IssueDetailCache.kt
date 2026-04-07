package com.workflow.orchestrator.jira.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.jira.JiraCommentData
import com.workflow.orchestrator.jira.api.dto.JiraAttachment
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-issue cache for lazy-loaded detail data (comments, attachments).
 * Session-scoped with LRU eviction at 200 entries.
 */
@Service(Service.Level.PROJECT)
class IssueDetailCache {

    private val cache = ConcurrentHashMap<String, IssueDetailData>()

    companion object {
        private const val MAX_SIZE = 200

        fun getInstance(project: Project): IssueDetailCache =
            project.getService(IssueDetailCache::class.java)
    }

    data class IssueDetailData(
        val comments: List<JiraCommentData>? = null,
        val attachments: List<JiraAttachment>? = null,
        val fetchedAt: Instant = Instant.now()
    )

    fun get(issueKey: String): IssueDetailData? = cache[issueKey]

    fun put(issueKey: String, data: IssueDetailData) {
        cache[issueKey] = data
        evictIfNeeded()
    }

    fun updateComments(issueKey: String, comments: List<JiraCommentData>) {
        val existing = cache[issueKey] ?: IssueDetailData()
        cache[issueKey] = existing.copy(comments = comments, fetchedAt = Instant.now())
    }

    fun updateAttachments(issueKey: String, attachments: List<JiraAttachment>) {
        val existing = cache[issueKey] ?: IssueDetailData()
        cache[issueKey] = existing.copy(attachments = attachments, fetchedAt = Instant.now())
    }

    private fun evictIfNeeded() {
        if (cache.size > MAX_SIZE) {
            // Remove oldest entries
            val sorted = cache.entries.sortedBy { it.value.fetchedAt }
            val toRemove = cache.size - MAX_SIZE
            sorted.take(toRemove).forEach { cache.remove(it.key) }
        }
    }
}
