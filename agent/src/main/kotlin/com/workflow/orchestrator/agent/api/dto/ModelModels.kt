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

    /** Extract the API version date from the model ID (e.g., "2024-10-22"). */
    val apiVersion: String get() {
        val parts = id.split("::")
        return if (parts.size >= 3) parts[1] else ""
    }

    /** Human-readable display name with proper formatting. */
    val displayName: String get() = formatModelName(modelName)

    /** Provider name with proper capitalization. */
    val displayProvider: String get() = when (provider.lowercase()) {
        "anthropic" -> "Anthropic"
        "openai" -> "OpenAI"
        "google" -> "Google"
        "fireworks" -> "Fireworks"
        "amazon-bedrock" -> "Amazon Bedrock"
        "azure-openai" -> "Azure OpenAI"
        else -> provider.replaceFirstChar { it.uppercase() }
    }

    /** Whether this is a thinking/reasoning model. */
    val isThinkingModel: Boolean get() {
        val lower = id.lowercase()
        return lower.contains("thinking") ||
            lower.contains("o1") ||
            lower.contains("o3") ||
            lower.contains("reasoner") ||
            lower.contains("deep-think")
    }

    /** Whether this is an Opus-class model (highest capability). */
    val isOpusClass: Boolean get() = modelName.lowercase().contains("opus")

    /** Model tier for sorting: opus > sonnet > haiku > other. */
    val tier: Int get() = when {
        modelName.lowercase().contains("opus") -> 0
        modelName.lowercase().contains("sonnet") -> 1
        modelName.lowercase().contains("haiku") -> 2
        modelName.lowercase().contains("gpt-4") -> 1
        modelName.lowercase().contains("gpt-3") -> 2
        modelName.lowercase().contains("gemini-pro") -> 1
        else -> 3
    }

    companion object {
        /**
         * Format a raw model name like "claude-opus-4-5-thinking-latest" into
         * a human-readable name like "Claude Opus 4.5 Thinking".
         *
         * Handles:
         * - Date suffixes: "-20250514" → removed
         * - "latest" suffix: "-latest" → removed
         * - Version numbers: "4-5" → "4.5" (when preceded by a model family name)
         * - Known brand names: proper casing
         */
        fun formatModelName(raw: String): String {
            // Remove date suffix (e.g., -20250514, -20241022)
            var cleaned = raw.replace(Regex("-\\d{8}$"), "")
            // Remove "-latest" suffix
            cleaned = cleaned.replace(Regex("-latest$"), "")

            // Merge version parts: "4-5" → "4.5" (a digit followed by hyphen and digit)
            cleaned = cleaned.replace(Regex("(\\d)-(\\d)"), "$1.$2")

            // Split on hyphens and capitalize
            val parts = cleaned.split("-").map { part ->
                when (part.lowercase()) {
                    "claude" -> "Claude"
                    "gpt" -> "GPT"
                    "o1", "o3" -> part.uppercase()
                    "pro" -> "Pro"
                    "mini" -> "Mini"
                    "gemini" -> "Gemini"
                    "flash" -> "Flash"
                    "opus" -> "Opus"
                    "sonnet" -> "Sonnet"
                    "haiku" -> "Haiku"
                    "thinking" -> "Thinking"
                    "preview" -> "Preview"
                    else -> part.replaceFirstChar { it.uppercase() }
                }
            }

            return parts.joinToString(" ")
        }
    }
}
