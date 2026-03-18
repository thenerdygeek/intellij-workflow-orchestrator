package com.workflow.orchestrator.agent.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from GET /.api/llm/models (Sourcegraph OpenAPI spec).
 */
@Serializable
data class ListModelsResponse(
    val data: List<ModelInfo> = emptyList()
)

/**
 * A single model from the Sourcegraph models list.
 *
 * Model ID format: `provider::apiVersion::modelName`
 * Example: `anthropic::2024-10-22::claude-sonnet-4-20250514`
 */
@Serializable
data class ModelInfo(
    val id: String,
    @SerialName("owned_by") val ownedBy: String = "",
    val created: Long = 0
) {
    /** Extract the provider name (e.g., "anthropic" from "anthropic::2024-10-22::claude-sonnet-4"). */
    val provider: String get() = id.substringBefore("::", "unknown")

    /** Extract the model name (e.g., "claude-sonnet-4" from "anthropic::2024-10-22::claude-sonnet-4"). */
    val modelName: String get() = id.substringAfterLast("::", id)

    /** Human-readable display name. */
    val displayName: String get() {
        val name = modelName
            .replace("-", " ")
            .replaceFirstChar { it.uppercase() }
        return "$name (${provider})"
    }

    /** Whether this is a thinking/reasoning model (extended thinking, o1, o3, etc). */
    val isThinkingModel: Boolean get() {
        val lower = id.lowercase()
        return lower.contains("thinking") ||
            lower.contains("o1") ||
            lower.contains("o3") ||
            lower.contains("reasoner") ||
            lower.contains("deep-think") ||
            lower.contains("claude-opus") // Opus models are typically used for deep reasoning
    }
}
