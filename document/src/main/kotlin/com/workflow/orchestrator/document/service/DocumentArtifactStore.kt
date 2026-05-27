package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentArtifactMeta
import com.workflow.orchestrator.core.model.DocumentIndex
import com.workflow.orchestrator.document.assembler.MarkdownAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
     */
    suspend fun extractAndPersist(source: Path, artDir: Path, contentHash: String): DocumentArtifact =
        withContext(Dispatchers.IO) {
            val blockResult = extractor.extractBlocks(source)
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
