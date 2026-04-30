package com.workflow.orchestrator.core.api

import com.workflow.orchestrator.core.model.DocumentContent
import com.workflow.orchestrator.core.model.ExtractOptions
import com.workflow.orchestrator.core.services.ToolResult
import java.nio.file.Path

/**
 * Contract for extracting readable content from binary document files (PDF, DOCX, XLSX, PPTX, RTF, ODT, etc.)
 * into a structured [DocumentContent] that carries Markdown text and optional typed [com.workflow.orchestrator.core.model.DocumentBlock]s.
 *
 * ## Contract guarantees
 * - **Never throws.** All errors are returned as [ToolResult] with [ToolResult.isError] = `true`
 *   and a human-readable [ToolResult.summary]. The caller never needs a try/catch around [extract].
 * - **Immutable inputs.** [extract] does not modify the file at [path]; it is safe to call
 *   concurrently on the same path from multiple coroutines.
 * - **Suspend + IO-safe.** Implementations must perform all blocking I/O on [kotlinx.coroutines.Dispatchers.IO]
 *   and must not block the EDT or a coroutine scheduler thread.
 * - **Per-document instance state is owned by the implementation.** Callers should not share a single
 *   [DocumentExtractor] instance between concurrent calls unless the implementation documents that it is
 *   safe to do so. The bundled [com.workflow.orchestrator.document.service.TikaDocumentExtractor] uses a
 *   global [kotlinx.coroutines.sync.Semaphore] to bound heap pressure; each extraction is otherwise
 *   independent.
 *
 * ## Example
 * ```kotlin
 * val result = extractor.extract(Paths.get("/tmp/spec.pdf"))
 * if (result.isError) {
 *     logger.warn(result.summary)
 * } else {
 *     val content: DocumentContent = result.data
 *     agentContext += content.markdown
 * }
 * ```
 *
 * @see DocumentContent
 * @see ExtractOptions
 */
interface DocumentExtractor {
    /**
     * Extracts text and structure from the document at [path], applying the given [options].
     *
     * @param path  Absolute path to the document file. Must be readable at call time.
     * @param options  Extraction parameters. Defaults to [ExtractOptions] (30s timeout, no char cap override).
     * @return [ToolResult] carrying a [DocumentContent] on success, or an error [ToolResult.summary]
     *         on failure. Never throws.
     */
    suspend fun extract(
        path: Path,
        options: ExtractOptions = ExtractOptions(),
    ): ToolResult<DocumentContent>
}
