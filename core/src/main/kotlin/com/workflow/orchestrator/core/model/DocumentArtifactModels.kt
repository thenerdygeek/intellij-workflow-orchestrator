package com.workflow.orchestrator.core.model

import kotlinx.serialization.Serializable
import java.nio.file.Path

/** How a caller addresses a chunk of the persisted artifact. `Offset` is the primitive; `Page`/`Section` resolve via [DocumentIndex]. */
sealed interface DocumentCursor {
    data class Offset(val value: Int) : DocumentCursor
    data class Page(val number: Int) : DocumentCursor
    data class Section(val heading: String) : DocumentCursor
}

/** A served slice of the artifact plus navigation breadcrumbs for the continuation hint. */
data class DocumentSlice(
    val content: String,
    val startOffset: Int,
    val endOffset: Int,
    val remaining: Int,
    val pageOfStart: Int?,
    val totalPages: Int?,
)

/** Persisted structural index: page-number -> char offset, heading -> char offset. Serialized as `index.json`. */
@Serializable
data class DocumentIndex(
    val pages: List<Anchor>,
    val sections: List<Anchor>,
) {
    @Serializable
    data class Anchor(val key: String, val offset: Int)

    fun offsetForPage(page: Int): Int? = pages.firstOrNull { it.key == page.toString() }?.offset

    fun offsetForSection(heading: String): Int? =
        sections.firstOrNull { it.key.equals(heading, ignoreCase = true) }?.offset

    /** Page whose recorded offset is the greatest value not exceeding [offset]; null if no page anchors. */
    fun pageAt(offset: Int): Int? =
        pages.lastOrNull { it.offset <= offset }?.key?.toIntOrNull()
}

/** Persisted descriptor written last as the commit sentinel (`meta.json`). */
@Serializable
data class DocumentArtifactMeta(
    val contentHash: String,
    val mime: String,
    val contentLength: Int,
    val pageCount: Int? = null,
    val createdAtEpochMs: Long,
)

/** Runtime handle to a materialized artifact on disk. */
data class DocumentArtifact(
    val meta: DocumentArtifactMeta,
    val contentPath: Path,
    val indexPath: Path,
)

/** Lifecycle of a single document's extraction. */
sealed interface ExtractionStatus {
    data object NotStarted : ExtractionStatus
    data class InProgress(val percent: Int?) : ExtractionStatus
    data class Ready(val artifact: DocumentArtifact) : ExtractionStatus
    data class Failed(val reason: String) : ExtractionStatus
}
