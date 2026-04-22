package com.workflow.orchestrator.core.prreview

import com.workflow.orchestrator.core.services.ToolResult

interface PrReviewFindingsStore {
    suspend fun add(finding: PrReviewFinding): ToolResult<PrReviewFinding>
    suspend fun update(id: String, mutate: (PrReviewFinding) -> PrReviewFinding): ToolResult<PrReviewFinding>
    suspend fun discard(id: String): ToolResult<Unit>
    suspend fun markPushed(id: String, bitbucketCommentId: String, pushedAt: Long): ToolResult<Unit>
    suspend fun list(prId: String, sessionId: String? = null, includeArchived: Boolean = false): ToolResult<List<PrReviewFinding>>
    suspend fun archiveSession(prId: String, sessionId: String): ToolResult<Unit>
    suspend fun clear(prId: String, sessionId: String): ToolResult<Unit>
}
