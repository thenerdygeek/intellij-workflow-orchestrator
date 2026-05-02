package com.workflow.orchestrator.core.ai.dto

import kotlinx.serialization.Serializable

/**
 * Sourcegraph `/.api/modelconfig/supported-models.json` payload.
 *
 * Multimodal-agent Phase 2 — replaces the codebase's hard-coded
 * 150K context assumption + name-heuristic vision detection with a
 * live, per-model catalog read.
 *
 * Spec: `docs/research/2026-05-02-multimodal-agent-design.md` §Architecture > ModelCatalogService
 */
@Serializable
data class ModelCatalog(
    val schemaVersion: String,
    val revision: String,
    val providers: List<Provider>,
    val models: List<Model>,
    val defaultModels: DefaultModels
)

@Serializable
data class Provider(
    val id: String,
    val displayName: String
)

@Serializable
data class Model(
    val modelRef: String,
    val displayName: String,
    val modelName: String,
    val capabilities: List<String>,
    val category: String,
    /** "experimental" | "beta" | "stable" | "deprecated" */
    val status: String,
    /** "free" | "pro" | "enterprise" */
    val tier: String,
    val contextWindow: ContextWindow,
    /**
     * Per-tier overrides. The top-level `contextWindow` on Sourcegraph's payload
     * is often a misleading minimum (e.g. 45K) — the REAL per-tier cap lives here
     * (e.g. enterprise → 132K non-thinking, 93K thinking). Probe-confirmed.
     */
    val modelConfigAllTiers: Map<String, TierOverride>? = null
)

@Serializable
data class ContextWindow(
    val maxInputTokens: Int,
    val maxOutputTokens: Int,
    val maxUserInputTokens: Int? = null
)

@Serializable
data class TierOverride(
    val contextWindow: ContextWindow
)

@Serializable
data class DefaultModels(
    val chat: String,
    val fastChat: String? = null,
    val codeCompletion: String? = null,
    val fallbackChat: String? = null
)
