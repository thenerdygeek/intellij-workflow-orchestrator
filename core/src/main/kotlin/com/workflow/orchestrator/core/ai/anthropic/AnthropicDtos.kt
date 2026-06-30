package com.workflow.orchestrator.core.ai.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire DTOs for the Anthropic Messages API (direct, non-Sourcegraph path).
 *
 * Serialization contract:
 * - All wire names use @SerialName for snake_case fields.
 * - No sampling fields (temperature/top_p/top_k/budget_tokens) are declared — they
 *   cannot appear in serialized output regardless of Json configuration.
 * - Null fields may appear as "null" with the default Json; callers that need compact
 *   wire payloads should configure Json { explicitNulls = false }.
 *
 * Task 4 of Phase 4a (native Anthropic provider).
 */

/** Top-level request sent to POST /v1/messages. */
@Serializable
data class AnthropicRequest(
    val model: String,
    val system: List<TextBlock>,
    val messages: List<AnthropicMessage>,
    val tools: List<AnthropicTool>? = null,
    @SerialName("max_tokens") val maxTokens: Int,
    val thinking: Thinking? = null,
    @SerialName("output_config") val outputConfig: OutputConfig? = null,
)

/** A text block used in the top-level `system` array and in assistant/user content. */
@Serializable
data class TextBlock(
    val type: String = "text",
    val text: String,
    @SerialName("cache_control") val cacheControl: CacheControl? = null,
)

/** Prompt-cache control marker. `type = "ephemeral"` enables ephemeral caching. */
@Serializable
data class CacheControl(
    val type: String,
)

/** A single turn in the conversation (role = "user" | "assistant"). */
@Serializable
data class AnthropicMessage(
    val role: String,
    val content: List<ContentBlock>,
)

/**
 * A polymorphic content block inside a message.
 *
 * Fields used per `type`:
 * - `text`         → text
 * - `image`        → source
 * - `tool_use`     → id, name, input
 * - `tool_result`  → tool_use_id, content
 *
 * Unused fields for a given type remain null and are omitted when the caller
 * configures Json { explicitNulls = false }.
 */
@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null,
    val source: ImageSource? = null,
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val content: String? = null,
    val name: String? = null,
    val id: String? = null,
    val input: JsonElement? = null,
)

/** Base64-encoded image source for `type = "image"` blocks. */
@Serializable
data class ImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String,
)

/** Tool definition sent to the Anthropic API (native format). */
@Serializable
data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: InputSchema,
)

/** JSON Schema object describing a tool's input parameters. */
@Serializable
data class InputSchema(
    val type: String = "object",
    val properties: Map<String, InputSchemaProperty> = emptyMap(),
    val required: List<String> = emptyList(),
)

/** A single property entry within an [InputSchema]. Supports recursive `items` for arrays. */
@Serializable
data class InputSchemaProperty(
    val type: String,
    val description: String? = null,
    val items: InputSchemaProperty? = null,
)

/**
 * Extended thinking configuration.
 *
 * `type = "adaptive"` lets the model decide how much thinking to apply.
 * `display = "summarized"` shows a summary of the thinking process in the response.
 */
@Serializable
data class Thinking(
    val type: String,
    val display: String,
)

/** Output-generation configuration — carries the effort level for thinking mode. */
@Serializable
data class OutputConfig(
    val effort: String,
)
