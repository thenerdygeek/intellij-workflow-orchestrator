package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentArtifactMeta
import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentExtractionProgress
import com.workflow.orchestrator.core.model.DocumentIndex
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

    /** Resolves [cursor] to an absolute offset via [index], then returns the [DocumentSlice]. */
    suspend fun slice(
        artifact: DocumentArtifact,
        index: DocumentIndex,
        cursor: DocumentCursor,
        maxChars: Int,
    ): DocumentSlice {
        val md = readContent(artifact)
        val resolved = when (cursor) {
            is DocumentCursor.Offset -> cursor.value
            is DocumentCursor.Page -> index.offsetForPage(cursor.number) ?: 0
            is DocumentCursor.Section -> index.offsetForSection(cursor.heading) ?: 0
        }.coerceIn(0, md.length)

        val end = (resolved + maxChars).coerceAtMost(md.length)
        val content = md.substring(resolved, end)
        return DocumentSlice(
            content = content,
            startOffset = resolved,
            endOffset = end,
            remaining = md.length - end,
            pageOfStart = index.pageAt(resolved),
            totalPages = artifact.meta.pageCount,
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
}
