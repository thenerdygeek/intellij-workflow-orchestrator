package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ContextWindow

/**
 * Static, network-free [ModelCatalogService] backed by [AnthropicModelCatalog].
 *
 * **Why:** On the native Anthropic provider (`AgentSettings.llmProvider == "anthropic"`)
 * the Sourcegraph-backed catalog is keyed on a blank Sourcegraph URL with no token, so
 * every `getContextWindow(...)` returns null and `ContextManager.effectiveMaxInputTokens()`
 * falls back to `FALLBACK_MAX_INPUT_TOKENS` (90K) — which would compact `claude-opus-4-8`
 * (1M context) at ~79K, an ~11× premature-compaction regression that also corrupts the
 * `UsageIndicator` / model-picker capacity strip. This adapter serves the static
 * [AnthropicModelCatalog] instead, keyed on the **bare** model id (e.g. `claude-opus-4-8`).
 *
 * **Shape:** It subclasses [ModelCatalogService] with an empty base URL and a null token
 * provider so the inherited fetch paths are inert — `getCatalog()` / `getClientConfig()`
 * short-circuit on the null token and never touch the wire. Every accessor a consumer
 * actually calls is overridden to read the static catalog; the network/cache machinery in
 * the base is simply never exercised.
 *
 * Spec: `.superpowers/sdd/task-3-brief.md` (Phase 4a Task 3, C2).
 */
class AnthropicModelCatalogService : ModelCatalogService(
    baseUrl = "",
    tokenProvider = { null },
) {

    /**
     * Per-model context window from [AnthropicModelCatalog], keyed on the **bare** id.
     * Unknown ids return the catalog's conservative fallback window (200K input / 64K
     * output) rather than null, so a valid-but-unlisted native model never drops to the
     * 90K floor. The [tier] parameter is ignored — Anthropic context windows are not tiered.
     */
    override fun getContextWindow(modelRef: String, tier: String): ContextWindow =
        ContextWindow(
            maxInputTokens = AnthropicModelCatalog.contextWindow(modelRef),
            maxOutputTokens = AnthropicModelCatalog.maxOutput(modelRef),
        )

    /** Vision support read from the static catalog (all four bundled models support vision). */
    override fun supportsVision(modelRef: String): Boolean =
        AnthropicModelCatalog.entry(modelRef)?.supportsVision == true

    /** Every catalogued Anthropic model supports tool use; unknown ids report false. */
    override fun supportsTools(modelRef: String): Boolean =
        AnthropicModelCatalog.entry(modelRef) != null

    /** Catalogued Anthropic models are GA ("stable"); unknown ids report null (unknown). */
    override fun getStatus(modelRef: String): String? =
        AnthropicModelCatalog.entry(modelRef)?.let { "stable" }

    /**
     * Native Anthropic transport is not gated by Sourcegraph's completions stream API
     * versioning, so report the conservative default (v8) rather than reading client-config.
     */
    override fun getLatestStreamApiVersion(): Int = DEFAULT_STREAM_API_VERSION
}
