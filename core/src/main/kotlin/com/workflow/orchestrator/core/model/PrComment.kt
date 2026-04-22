package com.workflow.orchestrator.core.models

data class PrComment(
    val id: String,
    val version: Int,
    val text: String,
    val author: PrCommentAuthor,
    val createdDate: Long,
    val updatedDate: Long,
    val anchor: PrCommentAnchor? = null,
    val state: PrCommentState,
    val severity: PrCommentSeverity,
    val replies: List<PrComment> = emptyList(),
    val parentId: String? = null,
    val permittedOperations: PrCommentPermittedOps? = null,
)

data class PrCommentAuthor(
    val name: String,
    val displayName: String,
    val emailAddress: String? = null,
    val avatarUrl: String? = null,
)

data class PrCommentAnchor(
    val path: String,
    val srcPath: String? = null,
    val line: Int? = null,
    val lineType: PrCommentLineType? = null,
    val fileType: PrCommentFileType? = null,
    val fromHash: String? = null,
    val toHash: String? = null,
)

enum class PrCommentLineType { ADDED, REMOVED, CONTEXT }
enum class PrCommentFileType { FROM, TO }

enum class PrCommentState { OPEN, RESOLVED }

enum class PrCommentSeverity { NORMAL, BLOCKER }

data class PrCommentPermittedOps(
    val editable: Boolean,
    val deletable: Boolean,
    val transitionable: Boolean,
)
