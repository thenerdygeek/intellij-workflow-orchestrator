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

    /**
     * Resolves a heading label to its char offset. Prefers an exact (case-insensitive) heading
     * match, then falls back to a case-insensitive substring match — so a caller can pass a
     * partial label ("Revision history") and still hit a richer indexed heading
     * ("1.3 Revision History (v2.0)"). First match wins. Matches the documented `section`
     * contract in `DocumentTool` (case-insensitive substring), which the prior exact-only
     * `equals` quietly violated, silently falling back to offset 0.
     */
    fun offsetForSection(heading: String): Int? {
        val needle = heading.trim()
        return sections.firstOrNull { it.key.equals(needle, ignoreCase = true) }?.offset
            ?: sections.firstOrNull { it.key.contains(needle, ignoreCase = true) }?.offset
    }

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

/**
 * Snapshot of in-flight document extraction progress, surfaced to the chat UI while a
 * read_document call blocks. `pagesTotal` is null for formats with no page concept
 * (XLSX/CSV) or before the page count is known; in that case the UI shows elapsed only.
 *
 * @param stage human-readable phase, e.g. "reading", "tables", "text", "finalizing".
 * @param pagesDone pages processed so far in the current paged pass (0 when not applicable).
 * @param pagesTotal total pages when known, else null.
 * @param elapsedMs wall-clock ms since extraction started.
 */
data class DocumentExtractionProgress(
    val stage: String,
    val pagesDone: Int,
    val pagesTotal: Int?,
    val elapsedMs: Long,
)
