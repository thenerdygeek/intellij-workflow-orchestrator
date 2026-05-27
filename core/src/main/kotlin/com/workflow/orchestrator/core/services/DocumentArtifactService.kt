package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.DocumentCursor
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
}
