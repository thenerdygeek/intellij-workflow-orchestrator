package com.workflow.orchestrator.pullrequest.ui

import com.workflow.orchestrator.core.prreview.PrReviewFinding
import com.workflow.orchestrator.core.prreview.PrReviewFindingsStore
import com.workflow.orchestrator.core.services.BitbucketService

class AiReviewViewModel(
    private val store: PrReviewFindingsStore,
    private val service: BitbucketService,
    private val projectKey: String,
    private val repoSlug: String,
    private val prId: Int,
    private val sessionId: String,
) {
    private val _findings: MutableList<PrReviewFinding> = mutableListOf()
    val findings: List<PrReviewFinding> get() = _findings.toList()

    var lastError: String? = null
        private set

    private val listeners = mutableListOf<() -> Unit>()
    fun addChangeListener(l: () -> Unit) { listeners += l }
    private fun fire() { listeners.forEach { runCatching { it() } } }

    suspend fun refresh() {
        val result = store.list("$projectKey/$repoSlug/PR-$prId", sessionId, includeArchived = false)
        if (result.isError) {
            lastError = result.summary ?: "failed to list findings"
        } else {
            lastError = null
            _findings.clear()
            _findings.addAll(result.data)
        }
        fire()
    }

    suspend fun pushFinding(finding: PrReviewFinding): Boolean {
        if (finding.pushed || finding.discarded) return false
        val anchorSide = finding.anchorSide?.name ?: "ADDED"
        val result = if (finding.file != null && finding.lineStart != null) {
            service.addInlineComment(
                prId = prId,
                filePath = finding.file!!,
                line = finding.lineStart!!,
                lineType = anchorSide,
                text = finding.message,
                repoName = null,
            )
        } else {
            service.addPrComment(prId = prId, text = finding.message, repoName = null)
        }
        if (result.isError) {
            lastError = result.summary ?: "push failed"
            fire()
            return false
        }
        // addPrComment/addInlineComment return Unit — no comment ID available from this API.
        // Pass empty string; Phase 5 polish can wire a richer comment-posting path if needed.
        store.markPushed(finding.id, "", System.currentTimeMillis())
        refresh()
        return true
    }

    suspend fun discard(id: String) {
        store.discard(id)
        refresh()
    }

    suspend fun pushAllKept(): Int {
        var pushed = 0
        val list = _findings.toList()
        for (f in list) {
            if (!f.pushed && !f.discarded) {
                if (pushFinding(f)) pushed++
            }
        }
        return pushed
    }
}
