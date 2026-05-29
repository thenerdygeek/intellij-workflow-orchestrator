package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentSearchResult
import com.workflow.orchestrator.core.model.DocumentSlice
import java.nio.file.Path

/**
 * Reads a slice of a document, extracting + persisting the artifact on first touch and
 * serving cheap slices thereafter. Implementations own single-flight + background-job lifecycle.
 *
 * Never throws — all errors are returned as [ToolResult.error]. A cold read that has not yet
 * finished extracting returns a NON-error [ToolResult] whose summary says extraction is in
 * progress; the caller should retry.
 */
interface DocumentArtifactService {
    suspend fun read(path: Path, cursor: DocumentCursor, maxChars: Int?): ToolResult<DocumentSlice>

    /**
     * Full-text search over the extracted content (G-10). Same extraction/single-flight lifecycle
     * as [read]: the artifact is materialized on first touch, then searched. Returns ranked matching
     * snippets with offset/page/section breadcrumbs so the LLM can navigate to read more.
     *
     * Never throws — errors come back as [ToolResult.error]. A no-match search is a NON-error result
     * with an empty match list (and available section names for navigation).
     */
    suspend fun search(path: Path, query: String, contextChars: Int?, resultCap: Int?): ToolResult<DocumentSearchResult>
}
