package com.workflow.orchestrator.core.prreview

import kotlinx.serialization.Serializable

@Serializable
data class PrReviewFinding(
    val id: String,
    val prId: String,
    val sessionId: String,
    val file: String? = null,
    val lineStart: Int? = null,
    val lineEnd: Int? = null,
    val anchorSide: AnchorSide? = null,
    val severity: FindingSeverity,
    val message: String,
    val pushed: Boolean = false,
    val pushedCommentId: String? = null,
    val pushedAt: Long? = null,
    val discarded: Boolean = false,
    val editedLocally: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long,
)

@Serializable
enum class AnchorSide { ADDED, REMOVED, CONTEXT }

@Serializable
enum class FindingSeverity { NORMAL, BLOCKER }
