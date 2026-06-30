package com.workflow.orchestrator.core.ai

/**
 * Static catalog of Anthropic models available for direct (non-Sourcegraph) API access.
 *
 * Provides context-window sizes, max-output limits, and convenience defaults consumed
 * by the brain layer (Task 3), context-window adapter (Task 4), and fallback chain (Task 5).
 *
 * All model IDs are bare strings (e.g. "claude-opus-4-8") — no provider::version::id form.
 * Miss-defaults: [FALLBACK_CONTEXT_WINDOW] and [FALLBACK_MAX_OUTPUT] (conservative floor).
 */
@Suppress("FunctionOnlyReturningConstant")
object AnthropicModelCatalog {

    /** Context-window size returned when a model ID is not found in [MODELS]. */
    const val FALLBACK_CONTEXT_WINDOW: Int = 200_000

    /** Max-output size returned when a model ID is not found in [MODELS]. */
    const val FALLBACK_MAX_OUTPUT: Int = 64_000

    data class Entry(
        val id: String,
        val contextWindow: Int,
        val maxOutput: Int,
        val supportsVision: Boolean,
    )

    val MODELS: List<Entry> = listOf(
        Entry(
            id = "claude-opus-4-8",
            contextWindow = 1_000_000,
            maxOutput = 128_000,
            supportsVision = true,
        ),
        Entry(
            id = "claude-sonnet-4-6",
            contextWindow = 1_000_000,
            maxOutput = 128_000,
            supportsVision = true,
        ),
        Entry(
            id = "claude-haiku-4-5",
            contextWindow = 200_000,
            maxOutput = 64_000,
            supportsVision = true,
        ),
        Entry(
            id = "claude-fable-5",
            contextWindow = 1_000_000,
            maxOutput = 128_000,
            supportsVision = true,
        ),
    )

    private val byId: Map<String, Entry> = MODELS.associateBy { it.id }

    fun entry(modelId: String): Entry? = byId[modelId]

    fun contextWindow(modelId: String): Int = byId[modelId]?.contextWindow ?: FALLBACK_CONTEXT_WINDOW

    fun maxOutput(modelId: String): Int = byId[modelId]?.maxOutput ?: FALLBACK_MAX_OUTPUT

    fun defaultModel(): String = "claude-opus-4-8"

    fun defaultSubagentModel(): String = "claude-sonnet-4-6"

    fun fallbackChain(): List<String> = listOf("claude-opus-4-8", "claude-sonnet-4-6")
}
