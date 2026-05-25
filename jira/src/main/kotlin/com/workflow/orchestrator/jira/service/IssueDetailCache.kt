package com.workflow.orchestrator.jira.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.jira.JiraCommentData
import com.workflow.orchestrator.jira.api.dto.JiraAttachment
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Per-issue cache for lazy-loaded detail data (comments, attachments).
 * Session-scoped with LRU eviction at 200 entries.
 *
 * Implementation: access-ordered [LinkedHashMap] wrapped in [Collections.synchronizedMap].
 * Access-order mode means `get` promotes the accessed entry to the tail, so
 * [removeEldestEntry] automatically evicts the least-recently-used (LRU) entry in O(1)
 * when the map exceeds [MAX_SIZE]. This replaces the previous O(N log N) sort-on-every-add
 * approach that sorted all 200 entries on every cache update.
 *
 * All mutation methods (`put`, `updateComments`, `updateAttachments`) and the [get]
 * accessor are synchronised by the wrapper monitor. [get] triggers the LRU-promotion
 * side-effect in the access-ordered map, which is intentional — reading a key marks it
 * as recently used and defers its eviction.
 */
@Service(Service.Level.PROJECT)
class IssueDetailCache {

    private val cache: MutableMap<String, IssueDetailData> = Collections.synchronizedMap(
        object : LinkedHashMap<String, IssueDetailData>(16, 0.75f, /* accessOrder= */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IssueDetailData>?): Boolean =
                size > MAX_SIZE
        }
    )

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
        // Eviction is automatic via removeEldestEntry — no explicit evictIfNeeded() call needed.
    }

    fun updateComments(issueKey: String, comments: List<JiraCommentData>) {
        synchronized(cache) {
            val existing = cache[issueKey] ?: IssueDetailData()
            cache[issueKey] = existing.copy(comments = comments, fetchedAt = Instant.now())
        }
    }

    fun updateAttachments(issueKey: String, attachments: List<JiraAttachment>) {
        synchronized(cache) {
            val existing = cache[issueKey] ?: IssueDetailData()
            cache[issueKey] = existing.copy(attachments = attachments, fetchedAt = Instant.now())
        }
    }
}
