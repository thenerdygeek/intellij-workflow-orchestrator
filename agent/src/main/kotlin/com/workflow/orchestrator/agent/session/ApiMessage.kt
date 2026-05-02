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

    /**
     * Pre-Phase-4 inline-base64 image variant. Was always dead code (no
     * production caller ever wrote it; only one stale `else -> false`
     * reference in `MessageStateHandler.isEmptyAssistant`).
     *
     * Kept @Deprecated and still readable so any historical session that
     * happened to land via this path can be loaded; new writes go through
     * [ImageRef] instead. Phase 4 of multimodal-agent plan.
     */
    @Deprecated(
        "Use ImageRef. The inline-base64 shape is read-only after Phase 4 of the multimodal-agent plan.",
        ReplaceWith("ImageRef(sha256, mediaType, data.length.toLong(), null)")
    )
    @Serializable
    @SerialName("image")
    data class Image(val mediaType: String, val data: String) : ContentBlock

    /**
     * Content-addressed image reference. Bytes live on disk under
     * `sessions/{id}/attachments/<sha256>.<ext>` (managed by `AttachmentStore`).
     * Persisted JSON shape:
     * ```
     * {"type":"image_url_ref","sha256":"...","mime":"image/png","size":12345,"originalFilename":"x.png"}
     * ```
     * Phase 4 of multimodal-agent plan.
     */
    @Serializable
    @SerialName("image_url_ref")
    data class ImageRef(
        val sha256: String,
        val mime: String,
        val size: Long,
        val originalFilename: String? = null,
    ) : ContentBlock
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
 *
 * `UnsupportedContentBlock` (forward-compat fallback for unknown polymorphic
 * discriminators — see Phase 1 of multimodal-agent plan) is rendered as
 * `[unsupported attachment]` placeholder text so v1 readers degrade gracefully
 * when loading v2+ session files.
 */
@Suppress("DEPRECATION")  // we still need to render legacy ContentBlock.Image
fun ApiMessage.toChatMessage(): ChatMessage {
    val textPieces = content.mapNotNull { block ->
        when (block) {
            is ContentBlock.Text -> block.text
            is UnsupportedContentBlock -> "[unsupported attachment]"
            is ContentBlock.ImageRef -> "[image: ${block.mime}, ${block.size} bytes]"
            is ContentBlock.Image -> "[image: ${block.mediaType}]"
            else -> null
        }
    }
    val textContent = textPieces.joinToString("\n").takeIf { it.isNotBlank() }

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
