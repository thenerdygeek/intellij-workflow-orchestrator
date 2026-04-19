package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.FunctionCall
import com.workflow.orchestrator.core.ai.dto.ToolCall as CoreToolCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ApiRole { USER, ASSISTANT }

@Serializable
sealed interface ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(val id: String, val name: String, val input: String) : ContentBlock

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : ContentBlock

    @Serializable
    @SerialName("image")
    data class Image(val mediaType: String, val data: String) : ContentBlock
}

@Serializable
data class ApiRequestMetrics(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cost: Double? = null,
    val cacheReadTokens: Int = 0,
    val cacheCreationTokens: Int = 0,
)

@Serializable
data class ApiMessage(
    val role: ApiRole,
    val content: List<ContentBlock>,
    val ts: Long = System.currentTimeMillis(),
    val modelInfo: ModelInfo? = null,
    val metrics: ApiRequestMetrics? = null,
)

/**
 * Lossless conversion from ApiMessage to ChatMessage.
 * Preserves tool_use blocks as ChatMessage.toolCalls and tool_result as role="tool".
 */
fun ApiMessage.toChatMessage(): ChatMessage {
    val textContent = content.filterIsInstance<ContentBlock.Text>()
        .joinToString("\n") { it.text }
        .takeIf { it.isNotBlank() }

    val toolUses = content.filterIsInstance<ContentBlock.ToolUse>()
    val toolResults = content.filterIsInstance<ContentBlock.ToolResult>()

    return when {
        // Assistant message with tool calls
        role == ApiRole.ASSISTANT && toolUses.isNotEmpty() -> ChatMessage(
            role = "assistant",
            content = textContent,
            toolCalls = toolUses.map { tu ->
                CoreToolCall(id = tu.id, function = FunctionCall(name = tu.name, arguments = tu.input))
            }
        )
        // Tool result message (role="tool" in OpenAI format)
        toolResults.isNotEmpty() -> ChatMessage(
            role = "tool",
            content = toolResults.first().content,
            toolCallId = toolResults.first().toolUseId
        )
        // Plain text message
        else -> ChatMessage(
            role = role.name.lowercase(),
            content = textContent ?: ""
        )
    }
}

/**
 * Convert ChatMessage back to ApiMessage (for migration from old format).
 */
fun ChatMessage.toApiMessage(): ApiMessage {
    val blocks = mutableListOf<ContentBlock>()
    if (!content.isNullOrBlank()) blocks.add(ContentBlock.Text(content!!))
    toolCalls?.forEach { tc ->
        blocks.add(ContentBlock.ToolUse(id = tc.id, name = tc.function.name, input = tc.function.arguments))
    }
    if (role == "tool" && toolCallId != null) {
        return ApiMessage(
            role = ApiRole.USER,
            content = listOf(ContentBlock.ToolResult(toolUseId = toolCallId!!, content = content ?: ""))
        )
    }
    val apiRole = if (role == "assistant") ApiRole.ASSISTANT else ApiRole.USER
    return ApiMessage(role = apiRole, content = blocks)
}
