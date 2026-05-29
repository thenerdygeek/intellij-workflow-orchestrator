package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.agent.session.DocumentManifestScanner
import com.workflow.orchestrator.core.model.DocumentArtifact
import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentExtractionProgress
import com.workflow.orchestrator.core.model.DocumentSearchResult
import com.workflow.orchestrator.core.model.DocumentSlice
import com.workflow.orchestrator.core.services.DocumentArtifactService
import com.workflow.orchestrator.core.services.SessionDownloadDir
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.document.service.DocumentArtifactStore
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped [DocumentArtifactService]. Owns the single-flight map and runs extraction as a
 * background coroutine on the injected [cs] (project/service scope). A [read] call blocks until
 * extraction completes (the background job is self-capped at [jobBudgetMs]); the optional
 * [progressSink] receives incremental [DocumentExtractionProgress] updates while waiting.
 */
class SessionDocumentArtifactService(
    private val store: DocumentArtifactStore,
    private val cs: CoroutineScope,
    private val cacheDirProvider: suspend () -> Path?,
    private val jobBudgetMs: Long,
    private val progressSink: ((DocumentExtractionProgress) -> Unit)? = null,
) : DocumentArtifactService {

    private val inFlight = ConcurrentHashMap<String, Deferred<Result<DocumentArtifact>>>()

    private sealed interface PathResolution {
        data class Use(val path: java.nio.file.Path, val note: String?) : PathResolution
        data class Ambiguous(val candidates: List<String>) : PathResolution
    }

    /**
     * If [given] exists, use it as-is. Otherwise try to resolve it by basename against the
     * session's known documents (attachments + downloads) — handles the case where the LLM
     * remembered the filename but hallucinated the directory (e.g. after compaction).
     */
    private fun resolvePath(given: java.nio.file.Path, cacheRoot: java.nio.file.Path): PathResolution {
        if (Files.exists(given)) return PathResolution.Use(given, null)
        val sessionDir = cacheRoot.parent ?: return PathResolution.Use(given, null)
        val wanted = given.fileName?.toString()?.lowercase() ?: return PathResolution.Use(given, null)
        val docs = DocumentManifestScanner.scan(sessionDir)
        val matches = docs.filter { doc ->
            doc.displayName.lowercase() == wanted ||
                java.nio.file.Paths.get(doc.absolutePath).fileName?.toString()?.lowercase() == wanted
        }.distinctBy { it.absolutePath }
        return when (matches.size) {
            0 -> PathResolution.Use(given, null) // no match → let the normal not-found path report it
            1 -> {
                val resolved = java.nio.file.Paths.get(matches[0].absolutePath)
                PathResolution.Use(resolved, "Note: '$given' was not found; resolved by filename to '${matches[0].absolutePath}'. Use that exact path next time.")
            }
            else -> PathResolution.Ambiguous(matches.map { it.absolutePath })
        }
    }

    override suspend fun read(path: Path, cursor: DocumentCursor, maxChars: Int?): ToolResult<DocumentSlice> {
        val cap = (maxChars ?: DEFAULT_SERVE_CHARS).coerceAtLeast(1)
        return when (val r = resolveArtifact(path)) {
            is ArtifactResolution.Failed -> ToolResult.error(r.reason)
            is ArtifactResolution.Ready -> serve(r.artifact, cursor, cap, r.note)
        }
    }

    override suspend fun search(
        path: Path,
        query: String,
        contextChars: Int?,
        resultCap: Int?,
    ): ToolResult<DocumentSearchResult> {
        return when (val r = resolveArtifact(path)) {
            is ArtifactResolution.Failed -> ToolResult.error(r.reason)
            is ArtifactResolution.Ready -> {
                val index = store.loadIndex(r.artifact)
                val result = store.search(
                    artifact = r.artifact,
                    index = index,
                    query = query,
                    contextChars = contextChars ?: DEFAULT_SNIPPET_CONTEXT,
                    resultCap = resultCap ?: DEFAULT_RESULT_CAP,
                )
                val summary = if (result.totalHits == 0) {
                    "No matches for \"${result.query}\"."
                } else if (result.totalHits > result.matches.size) {
                    "${result.totalHits} matches for \"${result.query}\" (showing first ${result.matches.size})."
                } else {
                    "${result.totalHits} matches for \"${result.query}\"."
                }
                ToolResult.success(data = result, summary = summary)
            }
        }
    }

    /** Outcome of resolving a path to a materialized artifact: either a typed failure or a ready artifact. */
    private sealed interface ArtifactResolution {
        data class Ready(val artifact: DocumentArtifact, val note: String?) : ArtifactResolution
        data class Failed(val reason: String) : ArtifactResolution
    }

    /**
     * Shared extraction orchestration for [read] and [search]: resolve the path (basename fallback),
     * hash, serve the persisted artifact if present, honor the fresh-failure negative cache, else run
     * the single-flight background extraction job and block on it. Never throws — returns a typed
     * [ArtifactResolution].
     */
    private suspend fun resolveArtifact(path: Path): ArtifactResolution {
        val cacheRoot = cacheDirProvider()
            ?: return ArtifactResolution.Failed("Document cache unavailable in this context.")

        val resolution = resolvePath(path, cacheRoot)
        val effectivePath: java.nio.file.Path
        val resolutionNote: String?
        when (resolution) {
            is PathResolution.Ambiguous -> return ArtifactResolution.Failed(
                "Multiple documents match '${path.fileName}'. Pass the full path. Candidates:\n" +
                    resolution.candidates.joinToString("\n") { "- $it" }
            )
            is PathResolution.Use -> { effectivePath = resolution.path; resolutionNote = resolution.note }
        }

        val hash = runCatching { store.hashFile(effectivePath) }.getOrElse {
            // Distinguish the common failure modes so the LLM can act, instead of a bare
            // "Cannot read". Temp-dir documents (Jira/Bamboo attachments) can be reaped by the
            // OS mid-session — surface that explicitly with recovery guidance.
            val msg = when (it) {
                is java.nio.file.NoSuchFileException, is java.io.FileNotFoundException ->
                    "'$effectivePath' no longer exists — temp/attachment files can be cleaned up " +
                        "mid-session. Re-download the attachment (the download tool re-fetches it), " +
                        "then call read_document again."
                is java.nio.file.AccessDeniedException ->
                    "Permission denied reading '$effectivePath'. Check the file's permissions."
                else -> "Cannot read '$effectivePath': ${it.message ?: it::class.simpleName}"
            }
            return ArtifactResolution.Failed(msg)
        }
        val artDir = cacheRoot.resolve(hash)

        store.loadArtifact(artDir)?.let { artifact -> return ArtifactResolution.Ready(artifact, resolutionNote) }
        store.loadFailureIfFresh(artDir)?.let { reason ->
            return ArtifactResolution.Failed("Document extraction failed: $reason")
        }

        // The background extraction job runs in a fresh coroutine on [cs]; it must carry a
        // SessionDownloadDir so TikaDocumentExtractor lands embedded images under the session
        // tree (where view_image can read them) instead of java.io.tmpdir. Prefer the caller's
        // element; when absent (sub-agent, resume, or an in-flight job first triggered outside
        // AgentLoopAttachmentScope) derive it from the cache dir — downloads/ is the sibling of
        // document-cache/, the exact inverse of defaultCacheDirProvider(). This decouples
        // image-path correctness from whether the caller happened to install the context element.
        val sessionCtx: kotlin.coroutines.CoroutineContext =
            coroutineContext[SessionDownloadDir]
                ?: SessionDownloadDir((cacheRoot.parent ?: cacheRoot).resolve("downloads"))

        val job = inFlight.computeIfAbsent(hash) {
            cs.async(Dispatchers.IO + sessionCtx) {
                val outcome = withTimeoutOrNull(jobBudgetMs) {
                    runCatching { store.extractAndPersist(effectivePath, artDir, hash, jobBudgetMs, progressSink) }
                } ?: Result.failure(RuntimeException("extraction exceeded ${jobBudgetMs / 1000}s budget"))
                if (outcome.isFailure) {
                    store.writeFailure(artDir, outcome.exceptionOrNull()?.message ?: "unknown error")
                }
                outcome
            }.also { d -> d.invokeOnCompletion { inFlight.remove(hash) } }
        }

        val outcome = job.await()   // blocks until extraction completes (deferred self-caps at jobBudgetMs)
        return outcome.fold(
            onSuccess = { artifact -> ArtifactResolution.Ready(artifact, resolutionNote) },
            onFailure = { ArtifactResolution.Failed("Document extraction failed: ${it.message}") },
        )
    }

    private suspend fun serve(artifact: DocumentArtifact, cursor: DocumentCursor, cap: Int, note: String? = null): ToolResult<DocumentSlice> {
        val index = store.loadIndex(artifact)
        val slice = store.slice(artifact, index, cursor, cap)
        val content = if (note != null) "$note\n\n${slice.content}" else slice.content
        val withNote = slice.copy(content = content)
        return ToolResult.success(
            data = withNote,
            summary = "Read ${slice.content.length} chars (offset=${slice.startOffset}, remaining=${slice.remaining}).",
        )
    }

    companion object {
        const val DEFAULT_SERVE_CHARS = 200_000

        /** Default chars of context on each side of a search hit (mirrors the store default). */
        const val DEFAULT_SNIPPET_CONTEXT = DocumentArtifactStore.DEFAULT_SNIPPET_CONTEXT

        /** Default cap on returned search matches (mirrors the store default). */
        const val DEFAULT_RESULT_CAP = DocumentArtifactStore.DEFAULT_RESULT_CAP

        /** Default cache-dir resolver: `{sessionDir}/document-cache/`, derived from the downloads dir. */
        suspend fun defaultCacheDirProvider(): Path? =
            SessionDownloadDir.current()?.parent?.resolve("document-cache")
    }
}
