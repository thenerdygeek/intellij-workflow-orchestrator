package com.workflow.orchestrator.core.ai.dto

import kotlinx.serialization.Serializable

/**
 * Sourcegraph `/.api/client-config` payload.
 *
 * Used by [com.workflow.orchestrator.core.ai.ModelCatalogService] to negotiate
 * the latest supported `?api-version=N` on `/.api/completions/stream` (Phase 3
 * of the multimodal-agent plan) and to feature-gate optional behavior.
 *
 * Spec: `docs/research/2026-05-02-multimodal-agent-design.md` §Architecture > ModelCatalogService
 */
@Serializable
data class ClientConfig(
    val codyEnabled: Boolean,
    val chatEnabled: Boolean,
    val autoCompleteEnabled: Boolean,
    val customCommandsEnabled: Boolean,
    val attributionEnabled: Boolean,
    val smartContextWindowEnabled: Boolean,
    val modelsAPIEnabled: Boolean,
    val latestSupportedCompletionsStreamAPIVersion: Int
)
