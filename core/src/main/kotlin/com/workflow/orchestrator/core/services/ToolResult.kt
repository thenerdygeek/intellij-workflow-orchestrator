package com.workflow.orchestrator.core.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Universal result type for service operations.
 * Serves both UI panels (via typed data) and AI agent (via text summary).
 *
 * - UI consumers use [data] for typed, renderable content.
 * - AI agent consumers use [summary] for token-efficient plain text.
 *
 * Note: @Serializable is for documentation intent. Actual serialization of [T]
 * depends on the concrete type provided at the call site.
 */
@Serializable
data class ToolResult<T>(
    /** Typed structured data — consumed by UI panels and programmatic code. */
    val data: T,

    /** LLM-optimized plain text summary — consumed by the AI agent's context window. */
    val summary: String,

    /** Whether this result represents an error. */
    val isError: Boolean = false,

    /** Optional hint for the AI agent about what to do next. */
    val hint: String? = null,

    /** Token estimate for the summary text. */
    val tokenEstimate: Int = 0,

    /**
     * Image attachments this tool produced and stored in the active session's
     * AttachmentStore. The agent loop materializes these into
     * `ContentBlock.ImageRef` blocks alongside the `ContentBlock.ToolResult`
     * so `BrainRouter.hasImageParts()` routes the next request through the
     * Sourcegraph `/.api/completions/stream` vision path — exactly the same
     * path user-pasted images take. Default empty list keeps existing tools
     * unchanged.
     */
    val imageRefs: List<ImageRefData> = emptyList(),

    /**
     * Optional structured payload for programmatic consumers.
     * Used by services that need to carry typed error or result details alongside
     * the human-readable [summary] (e.g. [TransitionError] subtypes in TicketTransitionService).
     * UI panels and agent tools that only need [summary] can ignore this field.
     *
     * @Transient: excluded from kotlinx.serialization because [Any?] has no generic serializer.
     * The class-level @Serializable is aspirational (noted in the kdoc above); [payload] is
     * a runtime-only field for programmatic callers.
     */
    @Transient
    val payload: Any? = null
) {
    /**
     * Image attachment a tool produced and stored in the active session's
     * AttachmentStore. The agent loop materializes these into
     * `ContentBlock.ImageRef` blocks alongside the `ContentBlock.ToolResult`
     * so `BrainRouter.hasImageParts()` routes the next request through the
     * Sourcegraph `/.api/completions/stream` vision path — exactly the same
     * path user-pasted images take.
     *
     * Field shape mirrors `ContentBlock.ImageRef` but lives in :core so
     * feature modules don't depend on :agent.
     */
    @Serializable
    data class ImageRefData(
        val sha256: String,
        val mime: String,
        val size: Long,
        val originalFilename: String? = null,
    )

    companion object {
        fun <T> success(data: T, summary: String, hint: String? = null): ToolResult<T> =
            ToolResult(data = data, summary = summary, hint = hint)

        @Suppress("UNCHECKED_CAST")
        fun <T> error(summary: String, hint: String? = null): ToolResult<T> =
            ToolResult(data = null as T, summary = summary, isError = true, hint = hint)
    }
}
