package com.workflow.orchestrator.core.ai.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null, // JsonPrimitive("auto") or {"type":"function","function":{"name":"..."}}
    val temperature: Double = 0.0,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null
)

@Serializable
data class ChatMessage(
    val role: String, // "system", "user", "assistant", "tool"
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: UsageInfo? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class UsageInfo(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
    // Anthropic prompt-cache fields (Sourcegraph currently strips cache_control;
    // these will be 0 unless the API starts forwarding them).
    @SerialName("cache_read_input_tokens") val cacheReadTokens: Int = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationTokens: Int = 0,
)
