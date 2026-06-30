package com.workflow.orchestrator.core.ai.anthropic

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ContentPart
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import com.workflow.orchestrator.core.ai.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import kotlinx.serialization.json.Json

/**
 * Maps OpenAI-compatible [ChatMessage] / [ToolDefinition] history into an [AnthropicRequest]
 * for the Anthropic Messages API.
 *
 * Key mapping rules:
 * 1. `system`-role messages → top-level `system: List<TextBlock>`, each with an ephemeral
 *    `cache_control`. System messages are NOT included in `messages`.
 * 2. `tool`-role messages → `user` message carrying a `tool_result` block keyed by `toolCallId`.
 * 3. `assistant` messages with `toolCalls` → one `tool_use` block per call.
 * 4. `ContentPart.Image` → `image` block hydrated via the `imageBytes` lambda (skipped if null).
 * 5. `thinking`/`outputConfig` are set only when `thinkingEnabled = true`.
 * 6. No sampling parameters (temperature / top_p / top_k / budget_tokens) are emitted.
 *
 * Task 4 of Phase 4a (native Anthropic provider).
 */
object AnthropicRequestMapper {

    /**
     * Builds an [AnthropicRequest] from the supplied conversation state.
     *
     * @param messages      Full conversation history (OpenAI-compat roles).
     * @param tools         Tool definitions to advertise, or null for a tool-free request.
     * @param model         Bare model ID, e.g. `"claude-opus-4-8"`.
     * @param maxTokens     Maximum tokens the model may generate.
     * @param thinkingEnabled When true, adds adaptive thinking + output_config blocks.
     * @param effort        Effort level string forwarded to [OutputConfig] when thinking is on.
     * @param imageBytes    Resolver: `sha256 → Pair(mediaType, base64Data)?`. Returns null when
     *                      the attachment is unavailable; the image block is then skipped.
     */
    fun build(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        model: String,
        maxTokens: Int,
        thinkingEnabled: Boolean,
        effort: String,
        imageBytes: (String) -> Pair<String, String>?,
    ): AnthropicRequest {
        val systemBlocks = messages
            .filter { it.role == "system" }
            .map { msg ->
                TextBlock(
                    text = msg.content ?: "",
                    cacheControl = CacheControl(type = "ephemeral"),
                )
            }

        val anthropicMessages = messages
            .filter { it.role != "system" }
            .map { msg -> mapMessage(msg, imageBytes) }

        val anthropicTools = tools
            ?.map { td ->
                AnthropicTool(
                    name = td.function.name,
                    description = td.function.description,
                    inputSchema = mapInputSchema(td.function.parameters),
                )
            }
            ?.takeIf { it.isNotEmpty() }

        return AnthropicRequest(
            model = model,
            system = systemBlocks,
            messages = anthropicMessages,
            tools = anthropicTools,
            maxTokens = maxTokens,
            thinking = if (thinkingEnabled) Thinking(type = "adaptive", display = "summarized") else null,
            outputConfig = if (thinkingEnabled) OutputConfig(effort = effort) else null,
        )
    }

    private fun mapMessage(
        msg: ChatMessage,
        imageBytes: (String) -> Pair<String, String>?,
    ): AnthropicMessage = when (msg.role) {
        "tool" -> AnthropicMessage(
            role = "user",
            content = listOf(
                ContentBlock(
                    type = "tool_result",
                    toolUseId = msg.toolCallId,
                    content = msg.content,
                ),
            ),
        )
        "assistant" -> mapAssistantMessage(msg)
        else -> mapUserMessage(msg, imageBytes)
    }

    private fun mapAssistantMessage(msg: ChatMessage): AnthropicMessage {
        val blocks = mutableListOf<ContentBlock>()
        if (!msg.content.isNullOrEmpty()) {
            blocks += ContentBlock(type = "text", text = msg.content)
        }
        msg.toolCalls?.forEach { tc ->
            blocks += ContentBlock(
                type = "tool_use",
                id = tc.id,
                name = tc.function.name,
                input = Json.parseToJsonElement(tc.function.arguments),
            )
        }
        return AnthropicMessage(role = "assistant", content = blocks)
    }

    private fun mapUserMessage(
        msg: ChatMessage,
        imageBytes: (String) -> Pair<String, String>?,
    ): AnthropicMessage {
        val blocks = mutableListOf<ContentBlock>()
        if (msg.parts != null) {
            msg.parts.forEach { part ->
                when (part) {
                    is ContentPart.Text -> blocks += ContentBlock(type = "text", text = part.text)
                    is ContentPart.Image -> {
                        val resolved = imageBytes(part.sha256)
                        if (resolved != null) {
                            blocks += ContentBlock(
                                type = "image",
                                source = ImageSource(mediaType = resolved.first, data = resolved.second),
                            )
                        }
                    }
                }
            }
        } else if (!msg.content.isNullOrEmpty()) {
            blocks += ContentBlock(type = "text", text = msg.content)
        }
        return AnthropicMessage(role = msg.role, content = blocks)
    }

    private fun mapInputSchema(params: FunctionParameters): InputSchema = InputSchema(
        type = params.type,
        properties = params.properties.mapValues { (_, prop) -> mapProperty(prop) },
        required = params.required,
    )

    private fun mapProperty(prop: ParameterProperty): InputSchemaProperty = InputSchemaProperty(
        type = prop.type,
        description = prop.description,
        items = prop.items?.let { mapProperty(it) },
    )
}
