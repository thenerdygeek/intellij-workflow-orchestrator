package com.workflow.orchestrator.core.model

import kotlinx.serialization.Serializable
import java.nio.file.Path

/** How a caller addresses a chunk of the persisted artifact. `Offset` is the primitive; `Page`/`Section` resolve via [DocumentIndex]. */
sealed interface DocumentCursor {
    data class Offset(val value: Int) : DocumentCursor
    data class Page(val number: Int) : DocumentCursor
    data class Section(val heading: String) : DocumentCursor
}

/**
 * A served slice of the artifact plus navigation breadcrumbs for the continuation hint.
 *
 * @param availableSections Section-anchor labels present in the document (capped by the store),
 *   so the caller can render a discoverability hint and a section-miss can list valid targets.
 *   Empty when the document has no reliable section anchors.
 * @param sectionMatched For a `section=` request: `true` if the label resolved to an anchor,
 *   `false` if it did NOT (the slice fell back to offset 0 — an explicit miss, NOT a real
 *   heading legitimately at offset 0). `null` when the request was not a section lookup.
 */
data class DocumentSlice(
    val content: String,
    val startOffset: Int,
    val endOffset: Int,
    val remaining: Int,
    val pageOfStart: Int?,
    val totalPages: Int?,
    val availableSections: List<String> = emptyList(),
    val sectionMatched: Boolean? = null,
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
     * Resolves a heading label to its char offset. Match precedence (first hit wins within each
     * tier; tiers tried in order):
     *
     * 1. **Exact** — case-insensitive equality on the raw label.
     * 2. **Normalized-equal** — both sides are (a) case-folded, (b) stripped of a leading section
     *    number (`^\d+(\.\d+)*\.?\s+`), and (c) reduced to alphanumeric-only, then compared for
     *    equality. This bridges `"Digital Identity Model"` → `"4 Digital Identity Model"` and the
     *    slug `"fetch-product-metadata"` → `"Fetch Product Metadata"`.
     * 3. **Substring** — case-insensitive substring match (legacy behaviour): `"Revision history"`
     *    → `"1.3 Revision History (v2.0)"`.
     *
     * Returns null when nothing matches — the caller (`DocumentArtifactStore.slice`) treats null
     * as an explicit miss (surfacing available sections) rather than silently serving offset 0.
     */
    fun offsetForSection(heading: String): Int? {
        val needle = heading.trim()
        if (needle.isEmpty()) return null
        // Tier 1: exact (case-insensitive).
        sections.firstOrNull { it.key.equals(needle, ignoreCase = true) }?.let { return it.offset }
        // Tier 2: number-stripped + alphanumeric-only equality.
        val needleNorm = normalizeSectionKey(needle)
        if (needleNorm.isNotEmpty()) {
            sections.firstOrNull { normalizeSectionKey(it.key) == needleNorm }?.let { return it.offset }
        }
        // Tier 3: case-insensitive substring.
        return sections.firstOrNull { it.key.contains(needle, ignoreCase = true) }?.offset
    }

    /** Page whose recorded offset is the greatest value not exceeding [offset]; null if no page anchors. */
    fun pageAt(offset: Int): Int? =
        pages.lastOrNull { it.offset <= offset }?.key?.toIntOrNull()
}

/** Leading section number with optional trailing dot and following whitespace: "4 ", "1.2. ". */
private val LEADING_SECTION_NUMBER = Regex("^\\d+(?:\\.\\d+)*\\.?\\s+")

/**
 * Case-folds, drops a leading section number, then reduces to alphanumeric-only so that
 * "4 Digital Identity Model", "Digital Identity Model", and "digital-identity-model" all
 * normalize to "digitalidentitymodel". Returns "" for keys that are pure number/punctuation.
 *
 * Used by [DocumentIndex.offsetForSection]'s tier-2 normalized-equality match. Kept as a
 * file-private top-level helper (not a companion) so the `@Serializable`-generated
 * `DocumentIndex.Companion.serializer()` stays accessible to the persistence layer.
 */
private fun normalizeSectionKey(key: String): String =
    key.replaceFirst(LEADING_SECTION_NUMBER, "")
        .lowercase()
        .filter { it.isLetterOrDigit() }

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
