package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentSlice
import com.workflow.orchestrator.core.services.DocumentArtifactService
import com.workflow.orchestrator.core.services.SessionDownloadDir
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.document.service.DocumentArtifactStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped [DocumentArtifactService]. Owns the single-flight map and runs extraction as a
 * background coroutine on the injected [cs] (project/service scope), decoupled from any single
 * read's [servingBudgetMs]. A read that times out abandons only its await, never the job.
 */
class SessionDocumentArtifactService(
    private val store: DocumentArtifactStore,
    private val cs: CoroutineScope,
    private val cacheDirProvider: suspend () -> Path?,
    private val servingBudgetMs: Long,
    private val jobBudgetMs: Long,
) : DocumentArtifactService {

    private val inFlight = ConcurrentHashMap<String, Deferred<Result<DocumentArtifact>>>()

    override suspend fun read(path: Path, cursor: DocumentCursor, maxChars: Int?): ToolResult<DocumentSlice> {
        val cacheRoot = cacheDirProvider()
            ?: return ToolResult.error("Document cache unavailable in this context.")
        val hash = runCatching { store.hashFile(path) }.getOrElse {
            return ToolResult.error("Cannot read '$path': ${it.message}")
        }
        val artDir = cacheRoot.resolve(hash)
        val cap = (maxChars ?: DEFAULT_SERVE_CHARS).coerceAtLeast(1)

        store.loadArtifact(artDir)?.let { artifact -> return serve(artifact, cursor, cap) }
        store.loadFailureIfFresh(artDir)?.let { reason ->
            return ToolResult.error("Document extraction failed: $reason")
        }

        val deferred = inFlight.computeIfAbsent(hash) {
            cs.async(Dispatchers.IO) {
                val outcome = withTimeoutOrNull(jobBudgetMs) {
                    runCatching { store.extractAndPersist(path, artDir, hash, jobBudgetMs) }
                } ?: Result.failure(RuntimeException("extraction exceeded ${jobBudgetMs / 1000}s budget"))
                if (outcome.isFailure) {
                    store.writeFailure(artDir, outcome.exceptionOrNull()?.message ?: "unknown error")
                }
                outcome
            }.also { d -> d.invokeOnCompletion { inFlight.remove(hash) } }
        }

        val joined = withTimeoutOrNull(servingBudgetMs) { deferred.await() }
            ?: return ToolResult.success(
                data = DocumentSlice("", 0, 0, 0, null, null),
                summary = "Document extraction in progress — call read_document again shortly.",
            )
        return joined.fold(
            onSuccess = { artifact -> serve(artifact, cursor, cap) },
            onFailure = { ToolResult.error("Document extraction failed: ${it.message}") },
        )
    }

    private suspend fun serve(artifact: DocumentArtifact, cursor: DocumentCursor, cap: Int): ToolResult<DocumentSlice> {
        val index = store.loadIndex(artifact)
        val slice = store.slice(artifact, index, cursor, cap)
        return ToolResult.success(
            data = slice,
            summary = "Read ${slice.content.length} chars (offset=${slice.startOffset}, remaining=${slice.remaining}).",
        )
    }

    companion object {
        const val DEFAULT_SERVE_CHARS = 200_000

        /** Default cache-dir resolver: `{sessionDir}/document-cache/`, derived from the downloads dir. */
        suspend fun defaultCacheDirProvider(): Path? =
            SessionDownloadDir.current()?.parent?.resolve("document-cache")
    }
}
