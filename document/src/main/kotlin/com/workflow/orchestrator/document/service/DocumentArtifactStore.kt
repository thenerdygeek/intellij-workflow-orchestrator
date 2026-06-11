package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentArtifactMeta
import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentExtractionProgress
import com.workflow.orchestrator.core.model.DocumentIndex
import com.workflow.orchestrator.core.model.DocumentSearchMatch
import com.workflow.orchestrator.core.model.DocumentSearchResult
import com.workflow.orchestrator.core.model.DocumentSlice
import com.workflow.orchestrator.document.assembler.MarkdownAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.lang.ref.SoftReference
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

/**
 * Pure (no IntelliJ services) extraction -> persisted-artifact + slicing store.
 *
 * Layout per artifact dir: `content.md`, `index.json`, `meta.json` (commit sentinel, written
 * last), `failure.json` (negative cache, only on failure). All writes go through a sibling
 * `.tmp` in the SAME directory then `ATOMIC_MOVE` so a crash never yields a partial artifact.
 */
class DocumentArtifactStore(
    private val extractor: TikaDocumentExtractor,
    private val assembler: MarkdownAssembler = MarkdownAssembler(),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** Streaming SHA-256 of the file bytes, hex-encoded. */
    suspend fun hashFile(path: Path): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Extracts the full (uncapped) artifact and persists it. Throws on extraction failure so the
     * caller can route the throwable into [writeFailure]. Returns the materialized [DocumentArtifact].
     *
     * @param extractTimeoutMs Timeout forwarded to [TikaDocumentExtractor.extractBlocks] via
     *   [com.workflow.orchestrator.core.model.ExtractOptions.timeoutMs]. Defaults to 600 000 ms
     *   (10 min) so direct and test callers get a generous cap rather than the extractor's own
     *   30 s default.
     */
    suspend fun extractAndPersist(
        source: Path,
        artDir: Path,
        contentHash: String,
        extractTimeoutMs: Long = 600_000L,
        onProgress: ((DocumentExtractionProgress) -> Unit)? = null,
    ): DocumentArtifact =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()

            val onPage: ((Int, Int) -> Unit)? = onProgress?.let { sink ->
                { done, total ->
                    sink(
                        DocumentExtractionProgress(
                            stage = "tables",
                            pagesDone = done,
                            pagesTotal = total,
                            elapsedMs = System.currentTimeMillis() - startMs,
                        )
                    )
                }
            }

            val blockResult = extractor.extractBlocks(
                source,
                com.workflow.orchestrator.core.model.ExtractOptions(timeoutMs = extractTimeoutMs),
                onPage,
            )
            if (blockResult.isError) error(blockResult.summary)
            val be = blockResult.data!!

            val assembled = assembler.assembleIndexed(be.blocks)
            val meta = DocumentArtifactMeta(
                contentHash = contentHash,
                mime = be.mime,
                contentLength = assembled.contentLength,
                pageCount = be.pageCount,
                createdAtEpochMs = Instant.now().toEpochMilli(),
            )

            onProgress?.invoke(
                DocumentExtractionProgress(
                    stage = "finalizing",
                    pagesDone = 0,
                    pagesTotal = be.pageCount,
                    elapsedMs = System.currentTimeMillis() - startMs,
                )
            )

            Files.createDirectories(artDir)
            atomicWriteString(artDir.resolve("content.md"), assembled.markdown)
            atomicWriteString(artDir.resolve("index.json"), json.encodeToString(DocumentIndex.serializer(), assembled.index))
            // meta.json LAST = commit sentinel.
            atomicWriteString(artDir.resolve("meta.json"), json.encodeToString(DocumentArtifactMeta.serializer(), meta))

            DocumentArtifact(meta, artDir.resolve("content.md"), artDir.resolve("index.json"))
        }

    /** Loads a previously persisted artifact, or null if the meta sentinel is missing/invalid (cold). */
    suspend fun loadArtifact(artDir: Path): DocumentArtifact? = withContext(Dispatchers.IO) {
        val metaPath = artDir.resolve("meta.json")
        val contentPath = artDir.resolve("content.md")
        val indexPath = artDir.resolve("index.json")
        if (!Files.exists(metaPath) || !Files.exists(contentPath) || !Files.exists(indexPath)) return@withContext null
        runCatching {
            val meta = json.decodeFromString(DocumentArtifactMeta.serializer(), Files.readString(metaPath))
            DocumentArtifact(meta, contentPath, indexPath)
        }.getOrNull()
    }

    suspend fun loadIndex(artifact: DocumentArtifact): DocumentIndex = withContext(Dispatchers.IO) {
        json.decodeFromString(DocumentIndex.serializer(), Files.readString(artifact.indexPath))
    }

    suspend fun readContent(artifact: DocumentArtifact): String = withContext(Dispatchers.IO) {
        Files.readString(artifact.contentPath)
    }

    /**
     * Single-slot soft-ref memo of the full decoded content, keyed by (path, contentHash) — P2-19
     * (2026-06-10 perf audit). [search] genuinely needs a full-text scan so a ranged read cannot
     * help it; the memo makes repeat scans free while letting the GC reclaim the tens-of-MB string
     * under memory pressure. Single slot because document navigation is one-artifact-at-a-time in
     * practice; an interleaved second document merely degrades to the old read-per-call behavior.
     */
    @Volatile
    private var contentMemo: ContentMemo? = null

    private class ContentMemo(val path: Path, val contentHash: String, val ref: SoftReference<String>)

    private fun memoizedContent(artifact: DocumentArtifact): String? {
        val memo = contentMemo ?: return null
        val sameArtifact = memo.path == artifact.contentPath && memo.contentHash == artifact.meta.contentHash
        return if (sameArtifact) memo.ref.get() else null
    }

    private suspend fun fullContentMemoized(artifact: DocumentArtifact): String =
        memoizedContent(artifact) ?: readContent(artifact).also {
            contentMemo = ContentMemo(artifact.contentPath, artifact.meta.contentHash, SoftReference(it))
        }

    /**
     * Reads [count] chars of `content.md` starting at char offset [startChar] WITHOUT materializing
     * the whole file (P2-19). The persisted index anchors and [DocumentArtifactMeta.contentLength]
     * are CHAR (UTF-16 code-unit) offsets — they are produced from and applied against the Kotlin
     * String — while content.md is stored as UTF-8 bytes, so a byte-ranged read would corrupt any
     * multi-byte text. The read therefore happens in char space: [java.io.Reader.skip] decodes and
     * discards the prefix (cheap, no allocation) and only the requested window is materialized.
     * Serves from the search memo first when it happens to be populated.
     */
    private suspend fun readContentRange(artifact: DocumentArtifact, startChar: Int, count: Int): String {
        if (count <= 0) return ""
        val memoized = memoizedContent(artifact)
        if (memoized != null) {
            val start = startChar.coerceIn(0, memoized.length)
            return memoized.substring(start, (start + count).coerceAtMost(memoized.length))
        }
        return withContext(Dispatchers.IO) {
            Files.newBufferedReader(artifact.contentPath, StandardCharsets.UTF_8).use { reader ->
                var toSkip = startChar.toLong()
                while (toSkip > 0) {
                    val skipped = reader.skip(toSkip)
                    if (skipped <= 0) break
                    toSkip -= skipped
                }
                val buf = CharArray(count)
                var filled = 0
                while (filled < count) {
                    val n = reader.read(buf, filled, count - filled)
                    if (n < 0) break
                    filled += n
                }
                String(buf, 0, filled)
            }
        }
    }

    /**
     * Resolves [cursor] to an absolute offset via [index], then returns the [DocumentSlice].
     *
     * A [DocumentCursor.Section] that resolves to nothing is reported as an explicit miss
     * ([DocumentSlice.sectionMatched] = `false`) rather than silently serving offset 0, and the
     * available section anchors are always surfaced ([DocumentSlice.availableSections], capped at
     * [MAX_AVAILABLE_SECTIONS]) so the caller can guide the LLM toward valid navigation targets.
     *
     * Discoverability (fix a): the surfaced section window is capped but the TRUE count is reported
     * in [DocumentSlice.totalSectionCount] so the caller can render an explicit "showing N of M"
     * truncation note instead of letting deep subsections silently fall off the end. On a section=
     * MISS the window is BIASED toward the query's number-prefix neighborhood (a query `2.4.4.1`
     * surfaces the `2.4.x` family first) via [biasSectionsTowardQuery], so the relevant subsection
     * family is visible even on a spec with hundreds of headings.
     */
    suspend fun slice(
        artifact: DocumentArtifact,
        index: DocumentIndex,
        cursor: DocumentCursor,
        maxChars: Int,
    ): DocumentSlice {
        // P2-19: the slice never needs the whole document — total length comes from the persisted
        // meta (written atomically with the content) and the window itself is a ranged char read.
        val totalChars = artifact.meta.contentLength

        // For a section cursor, distinguish a real hit from a miss so the caller never confuses a
        // fallback-to-0 with a heading legitimately at offset 0.
        var sectionMatched: Boolean? = null
        val resolved = when (cursor) {
            is DocumentCursor.Offset -> cursor.value
            is DocumentCursor.Page -> index.offsetForPage(cursor.number) ?: 0
            is DocumentCursor.Section -> {
                val hit = index.offsetForSection(cursor.heading)
                sectionMatched = hit != null
                hit ?: 0
            }
        }.coerceIn(0, totalChars)

        val requested = ((resolved + maxChars).coerceAtMost(totalChars) - resolved).coerceAtLeast(0)
        val content = readContentRange(artifact, resolved, requested)
        val end = resolved + content.length

        // On a section= MISS, bias the surfaced window toward the query's number-prefix
        // neighborhood so the family the LLM was reaching for (e.g. the 2.4.x subsections) is
        // visible even past the cap; otherwise surface the natural document-order prefix.
        val sectionKeys = index.sections.map { it.key }
        val surfacedSections =
            if (sectionMatched == false && cursor is DocumentCursor.Section) {
                biasSectionsTowardQuery(sectionKeys, cursor.heading).take(MAX_AVAILABLE_SECTIONS)
            } else {
                sectionKeys.take(MAX_AVAILABLE_SECTIONS)
            }

        return DocumentSlice(
            content = content,
            startOffset = resolved,
            endOffset = end,
            remaining = (totalChars - end).coerceAtLeast(0),
            pageOfStart = index.pageAt(resolved),
            totalPages = artifact.meta.pageCount,
            availableSections = surfacedSections,
            totalSectionCount = sectionKeys.size,
            sectionMatched = sectionMatched,
            availableTables = index.tables.take(MAX_AVAILABLE_TABLES).map { it.key },
        )
    }

    /**
     * Reorders [sections] so the entries sharing the longest leading number-prefix with [query]
     * come first (document order preserved within each tier), then everything else in document
     * order. So a miss on `"2.4.4.1"` surfaces the `2.4.4.x` siblings, then the `2.4.x` family,
     * before unrelated sections — making the relevant neighborhood visible even when it would
     * otherwise fall past the cap. A query with no leading number returns [sections] unchanged.
     */
    private fun biasSectionsTowardQuery(sections: List<String>, query: String): List<String> {
        val queryNumber = leadingDottedNumber(query.trim()) ?: return sections
        // Score by the number of shared leading dotted segments (e.g. "2.4.4" shares 3 with
        // "2.4.4.2"; "2.4" shares 2). Higher score = closer neighbor. Stable sort keeps document
        // order within a score tier and leaves score-0 entries trailing in their original order.
        val querySegs = queryNumber.split('.')
        return sections.sortedByDescending { key ->
            val keyNumber = leadingDottedNumber(key) ?: return@sortedByDescending 0
            val keySegs = keyNumber.split('.')
            var shared = 0
            while (shared < querySegs.size && shared < keySegs.size && querySegs[shared] == keySegs[shared]) shared++
            shared
        }
    }

    /** Extracts a leading dotted number ("2.4.4.1") from a section label, or null if none. */
    private fun leadingDottedNumber(label: String): String? =
        LEADING_DOTTED_NUMBER.find(label)?.value

    /**
     * Full-text search over the persisted content (G-10). Returns ranked matching snippets, each
     * carrying the navigation breadcrumbs ([DocumentSearchMatch.offset]/page/section) so the LLM
     * can read more at the hit via `read_document(offset=/page=/section=)`.
     *
     * Semantics:
     *  - **Case-insensitive.**
     *  - **All-terms default.** [query] is split on whitespace; a candidate window must contain
     *    EVERY term. A single-token query degenerates to a plain substring match.
     *  - **Ranking.** Phrase hits (all terms adjacent in order) outrank scattered all-terms hits;
     *    among the latter, the tighter span (term density) wins; document order is the final tiebreak.
     *  - **Cap.** At most [resultCap] matches are returned; [DocumentSearchResult.totalHits] reports
     *    the true pre-cap count so truncation is never silent.
     *  - **Snippet.** A context window of ~[contextChars] chars on each side of the match, trimmed to
     *    word boundaries, with the matched region delimited by `«…»` and elided edges marked `…`.
     *  - **No match.** Returns empty matches plus [DocumentSearchResult.availableSections] (capped at
     *    [MAX_AVAILABLE_SECTIONS]) so the caller can guide navigation, mirroring the slice-path miss banner.
     *
     * Match → page is [DocumentIndex.pageAt]; match → section is [DocumentIndex.sectionAt].
     */
    suspend fun search(
        artifact: DocumentArtifact,
        index: DocumentIndex,
        query: String,
        contextChars: Int = DEFAULT_SNIPPET_CONTEXT,
        resultCap: Int = DEFAULT_RESULT_CAP,
    ): DocumentSearchResult = withContext(Dispatchers.IO) {
        // P2-19: search is a full-text scan by nature, so serve the content from the soft-ref memo
        // (repeat searches over a big PDF stop re-reading tens of MB from disk per call).
        val md = fullContentMemoized(artifact)
        DocumentSearchEngine.run(
            content = md,
            query = query,
            contextChars = contextChars.coerceAtLeast(8),
            resultCap = resultCap.coerceAtLeast(1),
            pageAt = index::pageAt,
            sectionAt = index::sectionAt,
            availableSections = index.sections.take(MAX_AVAILABLE_SECTIONS).map { it.key },
        )
    }

    suspend fun writeFailure(artDir: Path, reason: String, nowEpochMs: Long = Instant.now().toEpochMilli()): Unit =
        withContext(Dispatchers.IO) {
            Files.createDirectories(artDir)
            val obj = buildJsonObject {
                put("reason", reason)
                put("atEpochMs", nowEpochMs)
            }
            atomicWriteString(artDir.resolve("failure.json"), obj.toString())
        }

    /** Returns the failure reason if a non-expired `failure.json` exists, else null. */
    suspend fun loadFailureIfFresh(
        artDir: Path,
        nowEpochMs: Long = Instant.now().toEpochMilli(),
        ttlMs: Long = 3_600_000L,
    ): String? = withContext(Dispatchers.IO) {
        val p = artDir.resolve("failure.json")
        if (!Files.exists(p)) return@withContext null
        val obj = runCatching {
            json.parseToJsonElement(Files.readString(p)).jsonObject
        }.getOrNull() ?: return@withContext null
        val markerReason = obj["reason"]?.jsonPrimitive?.content ?: return@withContext null
        val markerAt = obj["atEpochMs"]?.jsonPrimitive?.long ?: return@withContext null
        if (nowEpochMs - markerAt <= ttlMs) markerReason else null
    }

    private fun atomicWriteString(target: Path, content: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.writeString(tmp, content)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /**
         * Upper bound on the number of section labels surfaced in [DocumentSlice.availableSections].
         * Raised from 30 (fix a): a 30-cap on a big spec only ever showed the first 30 (top-level,
         * early) sections, so deep subsections looked absent even when anchored. 200 covers the
         * section count of all but the largest specs while staying token-bounded; when it DOES
         * truncate, [DocumentSlice.totalSectionCount] reports the true total so the caller renders
         * an explicit "showing N of M" note (truncation is never silent).
         */
        const val MAX_AVAILABLE_SECTIONS = 200

        /** Leading dotted section number ("2", "2.4", "2.4.4.1") used for miss-neighborhood biasing. */
        private val LEADING_DOTTED_NUMBER = Regex("^\\d+(?:\\.\\d+)*")

        /**
         * Upper bound on the number of table-caption labels surfaced in [DocumentSlice.availableTables].
         * Tables get their OWN cap (separate from [MAX_AVAILABLE_SECTIONS]) so a table-dense document
         * doesn't crowd out the section hint, and vice versa.
         */
        const val MAX_AVAILABLE_TABLES = 30

        /** Default chars of context on each side of a search hit in the rendered snippet. */
        const val DEFAULT_SNIPPET_CONTEXT = 75

        /** Default cap on the number of ranked search matches returned (true total still reported). */
        const val DEFAULT_RESULT_CAP = 15
    }
}
