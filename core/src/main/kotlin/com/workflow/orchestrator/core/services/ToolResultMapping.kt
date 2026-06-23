package com.workflow.orchestrator.core.services

/**
 * Maps a [ToolResult]'s [ToolResult.data] payload while preserving the rest of the result
 * envelope ([summary]/[isError]/[hint]/[tokenEstimate]/[imageRefs]/[payload]). [transform]
 * is invoked only when [data] is non-null, so error results pass through untouched.
 *
 * Added for the Phase 0b-2 neutral connector seams: the CI seam's delegating methods remap
 * vendor DTOs (e.g. `PlanData`) to neutral ones (`PipelineData`) without rebuilding the envelope.
 */
fun <T, R> ToolResult<T>.mapData(transform: (T) -> R): ToolResult<R> =
    ToolResult(
        data = data?.let(transform),
        summary = summary,
        isError = isError,
        hint = hint,
        tokenEstimate = tokenEstimate,
        imageRefs = imageRefs,
        payload = payload,
    )
