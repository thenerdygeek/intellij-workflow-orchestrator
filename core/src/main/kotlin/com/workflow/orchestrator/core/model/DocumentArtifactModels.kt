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
 *   Empty when the document has no reliable section anchors. On a section= MISS the store biases
 *   this window toward the query's number-prefix neighborhood (e.g. query `2.4.4.1` surfaces the
 *   `2.4.x` family first) so the relevant subsections are visible even past the cap.
 * @param totalSectionCount The TRUE number of section anchors in the document, BEFORE the
 *   [availableSections] cap. When this exceeds `availableSections.size` the list is truncated, and
 *   the caller must say so explicitly ("showing N of M") rather than letting the truncation be
 *   silent. Equals `availableSections.size` when nothing was dropped.
 * @param sectionMatched For a `section=` request: `true` if the label resolved to an anchor,
 *   `false` if it did NOT (the slice fell back to offset 0 — an explicit miss, NOT a real
 *   heading legitimately at offset 0). `null` when the request was not a section lookup.
 * @param availableTables Table-caption anchor labels present in the document (capped by the
 *   store), surfaced SEPARATELY from [availableSections] so the discoverability hint can list
 *   "Available tables: …" without bloating the section list. `section=` resolves against these
 *   too (heading first, then table), so a caller can navigate to a table by its number, full
 *   caption, or title. Empty when the document has no detected table captions.
 */
data class DocumentSlice(
    val content: String,
    val startOffset: Int,
    val endOffset: Int,
    val remaining: Int,
    val pageOfStart: Int?,
    val totalPages: Int?,
    val availableSections: List<String> = emptyList(),
    val totalSectionCount: Int = 0,
    val sectionMatched: Boolean? = null,
    val availableTables: List<String> = emptyList(),
)

/**
 * One ranked hit returned by a `read_document(search=…)` call. Carries the navigation
 * breadcrumbs the LLM needs to read more at the hit: the char [offset] (authoritative — pass it
 * back as `offset=`), the [page] number, and the nearest preceding [section] heading. [snippet] is
 * a context window around the match with the matched region delimited by `«…»`.
 *
 * @param offset absolute char offset of the match start in the extracted Markdown.
 * @param page 1-based page number containing the match, or null when the document has no page anchors.
 * @param section nearest preceding section-anchor label (`offset <= matchOffset`), or null when none precede it.
 * @param snippet the trimmed context window with the match delimited by `«…»`.
 */
data class DocumentSearchMatch(
    val offset: Int,
    val page: Int?,
    val section: String?,
    val snippet: String,
)

/**
 * Result of a `read_document(search=…)` call. A search is its own MODE — mutually exclusive with
 * the slice path — so it gets its own typed result rather than overloading [DocumentSlice].
 *
 * @param query the (trimmed) query as searched.
 * @param matches the ranked, capped hits (most relevant first; capped at [resultCap]).
 * @param totalHits the total number of hits found BEFORE the cap — so truncation is never silent.
 * @param resultCap the cap that was applied (matches.size <= resultCap).
 * @param availableSections section-anchor labels (capped by the store) so a no-match search can
 *   still guide the LLM toward valid navigation, mirroring the slice-path miss banner.
 */
data class DocumentSearchResult(
    val query: String,
    val matches: List<DocumentSearchMatch>,
    val totalHits: Int,
    val resultCap: Int,
    val availableSections: List<String> = emptyList(),
)

/**
 * Persisted structural index: page-number -> char offset, heading -> char offset, table-caption ->
 * char offset. Serialized as `index.json`.
 *
 * @param tables Table-caption anchors (e.g. `"Table 45. Fare Parameters"`, `"TABLE 1-2: PINOUT
 *   I/O DESCRIPTIONS"`), kept in their OWN list — distinct from [sections] — so they don't bloat
 *   the heading list and can be surfaced separately as "Available tables". `section=` resolution
 *   ([offsetForSection]) falls back to these after headings. Defaulted to empty for backward
 *   compatibility with `index.json` files written before table indexing existed.
 */
@Serializable
data class DocumentIndex(
    val pages: List<Anchor>,
    val sections: List<Anchor>,
    val tables: List<Anchor> = emptyList(),
) {
    @Serializable
    data class Anchor(val key: String, val offset: Int)

    fun offsetForPage(page: Int): Int? = pages.firstOrNull { it.key == page.toString() }?.offset

    /**
     * Resolves a `section=` label to its char offset. Headings are matched FIRST; if none match,
     * the same tolerant matcher is applied to the [tables] caption anchors, so a caller can
     * address a table by its number (`"Table 46"`), full caption (`"Table 46. Fare Parameters"`),
     * or title (`"Fare Parameters"`).
     *
     * Within each anchor list the match precedence is (first hit wins within each tier; tiers
     * tried in order):
     *
     * 1. **Exact** — case-insensitive equality on the raw label.
     * 2. **Normalized-equal** — both sides are (a) case-folded, (b) stripped of a leading section
     *    OR table number (`^(table\s+)?\w[\w.\-]*[.:]?\s*`), and (c) reduced to alphanumeric-only,
     *    then compared for equality. This bridges `"Digital Identity Model"` → `"4 Digital
     *    Identity Model"`, the slug `"fetch-product-metadata"` → `"Fetch Product Metadata"`, and
     *    `"Fare Parameters"` → `"Table 46. Fare Parameters"`.
     * 3. **Substring** — case-insensitive substring match (legacy behaviour): `"Revision history"`
     *    → `"1.3 Revision History (v2.0)"`, and `"Table 46"` → `"Table 46. Fare Parameters"`.
     *
     * Returns null when nothing matches — the caller (`DocumentArtifactStore.slice`) treats null
     * as an explicit miss (surfacing available sections/tables) rather than silently serving offset 0.
     */
    fun offsetForSection(heading: String): Int? {
        val needle = heading.trim()
        if (needle.isEmpty()) return null
        // Headings first, then table-caption anchors (reusing the same tolerant matcher).
        return matchAnchor(sections, needle) ?: matchAnchor(tables, needle)
    }

    /**
     * Tolerant three-tier match of [needle] against [anchors]. Shared by heading and table-caption
     * resolution so both navigation forms behave identically.
     */
    private fun matchAnchor(anchors: List<Anchor>, needle: String): Int? {
        // Tier 1: exact (case-insensitive).
        anchors.firstOrNull { it.key.equals(needle, ignoreCase = true) }?.let { return it.offset }
        // Tier 2: number-stripped + alphanumeric-only equality.
        val needleNorm = normalizeSectionKey(needle)
        if (needleNorm.isNotEmpty()) {
            anchors.firstOrNull { normalizeSectionKey(it.key) == needleNorm }?.let { return it.offset }
        }
        // Tier 3: case-insensitive substring.
        return anchors.firstOrNull { it.key.contains(needle, ignoreCase = true) }?.offset
    }

    /** Page whose recorded offset is the greatest value not exceeding [offset]; null if no page anchors. */
    fun pageAt(offset: Int): Int? =
        pages.lastOrNull { it.offset <= offset }?.key?.toIntOrNull()

    /**
     * Nearest preceding section-anchor label: the heading whose recorded offset is the greatest
     * value not exceeding [offset]; null when no section anchor precedes it (e.g. a match before the
     * first heading, or a document with no section anchors). The inverse of [offsetForSection] —
     * given a char offset, name the section that contains it. Used by the `read_document(search=…)`
     * path to attribute each match to its enclosing section.
     *
     * Relies on section anchors being recorded in ascending offset order (the assembler emits them
     * in document order); does not re-sort.
     */
    fun sectionAt(offset: Int): String? =
        sections.lastOrNull { it.offset <= offset }?.key
}

/** Leading section number with optional trailing dot and following whitespace: "4 ", "1.2. ". */
private val LEADING_SECTION_NUMBER = Regex("^\\d+(?:\\.\\d+)*\\.?\\s+")

/**
 * Leading table-caption prefix: the `Table`/`TABLE` keyword + a number token (digits, dots,
 * dashes, and optional letter suffix, e.g. "1-2", "8.3.2-1", "8-1a", "B.2-1") + the separator
 * (`.` or `:`) + following whitespace. Strips `"Table 46. "` / `"TABLE 1-2: "` so the bare title
 * remains. Case-insensitive. The number token must be followed by `.` or `:` so a prose reference
 * like "Table 8 specifies …" (no separator) is NOT stripped to its tail.
 */
private val LEADING_TABLE_CAPTION = Regex("^[Tt][Aa][Bb][Ll][Ee]\\s+[\\w][\\w.\\-]*[.:]\\s+")

/**
 * Case-folds, drops a leading section number OR table-caption prefix, then reduces to
 * alphanumeric-only so that "4 Digital Identity Model", "Digital Identity Model", and
 * "digital-identity-model" all normalize to "digitalidentitymodel", and "Table 46. Fare
 * Parameters" / "Fare Parameters" both normalize to "fareparameters". Returns "" for keys that are
 * pure number/punctuation.
 *
 * Used by [DocumentIndex.offsetForSection]'s tier-2 normalized-equality match (for both heading
 * and table-caption anchors). Kept as a file-private top-level helper (not a companion) so the
 * `@Serializable`-generated `DocumentIndex.Companion.serializer()` stays accessible to the
 * persistence layer.
 */
private fun normalizeSectionKey(key: String): String =
    key.replaceFirst(LEADING_TABLE_CAPTION, "")
        .replaceFirst(LEADING_SECTION_NUMBER, "")
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
