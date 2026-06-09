package com.workflow.orchestrator.agent.model

import com.workflow.orchestrator.core.ai.dto.ContextWindow

/** Snapshot of the user's max-token overrides. `global == null` means no global override. */
data class MaxTokenOverrides(val global: Int?, val perModel: Map<String, Int>)

/**
 * Single resolution path for a model's max INPUT tokens, layering user overrides over the shared,
 * cached catalog (injected as a narrow [windowLookup] = `modelId -> ContextWindow?`).
 * Precedence: per-model override > global override > catalog > fallback.
 *
 * Used with TWO keys: DISPLAY (bar/picker/TopBar) keyed on the SELECTED model; RUNTIME
 * (compaction/sub-agent budget) keyed on the running currentBrainModelId.
 */
class EffectiveContextWindow(
    private val windowLookup: (String) -> ContextWindow?,
    private val overrides: () -> MaxTokenOverrides,
    private val fallback: Int = FALLBACK,
) {
    fun maxInputTokens(modelId: String?): Int {
        val ov = overrides()
        if (!modelId.isNullOrBlank()) ov.perModel[modelId]?.let { return it }
        ov.global?.let { return it }
        val catVal = modelId?.takeIf { it.isNotBlank() }?.let { windowLookup(it)?.maxInputTokens }
        return catVal ?: fallback
    }

    fun catalogMaxInputTokens(modelId: String): Int? = windowLookup(modelId)?.maxInputTokens

    companion object {
        /** Mirrors ContextManager.FALLBACK_MAX_INPUT_TOKENS (90_000). */
        const val FALLBACK = 90_000
    }
}
