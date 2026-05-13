package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ContentPart
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
 * Converts ApiMessage to ChatMessage.
 *
 * Legacy [ContentBlock.ToolUse] blocks (on-disk sessions written before the
 * 2026-05-13 XML-in-content migration) are rendered as inline XML in the
 * assistant's text content via [ToolUseXmlRenderer]. After the migration, no
 * new writes produce ContentBlock.ToolUse — but this read path must keep working
 * for session resume across the cutover.
 *
 * [ChatMessage.toolCalls] is always null on outputs from this function.
 *
 * `UnsupportedContentBlock` (forward-compat fallback for unknown polymorphic
 * discriminators — see Phase 1 of multimodal-agent plan) is rendered as
 * `[unsupported attachment]` placeholder text so v1 readers degrade gracefully
 * when loading v2+ session files.
 */
@Suppress("DEPRECATION")  // we still need to render legacy ContentBlock.Image
fun ApiMessage.toChatMessage(): ChatMessage {
    val toolResults = content.filterIsInstance<ContentBlock.ToolResult>()
    val imageRefs = content.filterIsInstance<ContentBlock.ImageRef>()

    // Inline legacy ContentBlock.ToolUse blocks as XML in the text content.
    // After the 2026-05-13 XML-in-content migration, no new writes produce
    // ContentBlock.ToolUse — but on-disk sessions from before the cutover
    // still contain them, and resume must keep working.
    val textPieces = content.mapNotNull { block ->
        when (block) {
            is ContentBlock.Text -> block.text
            is ContentBlock.ToolUse -> ToolUseXmlRenderer.render(block)
            is UnsupportedContentBlock -> "[unsupported attachment]"
            is ContentBlock.Image -> "[image: ${block.mediaType}]"
            is ContentBlock.ImageRef -> null  // structural on `parts`
            else -> null
        }
    }
    val textContent = textPieces.joinToString("\n\n").takeIf { it.isNotBlank() }

    // Build structured parts list whenever ImageRef is present. This is the signal
    // BrainRouter.hasImageParts() looks for; without it the request goes to the
    // text-only OpenAI-compat endpoint and the model sees nothing.
    val parts: List<ContentPart>? = if (imageRefs.isNotEmpty()) {
        buildList {
            if (!textContent.isNullOrBlank()) add(ContentPart.Text(textContent))
            // For tool_result + ImageRef, surface the tool result text inside parts too,
            // so the human-coerced Cody stream payload still carries the result content.
            if (textContent.isNullOrBlank() && toolResults.isNotEmpty()) {
                add(ContentPart.Text(toolResults.first().content))
            }
            imageRefs.forEach { add(ContentPart.Image(sha256 = it.sha256, mime = it.mime)) }
        }
    } else null

    return when {
        // Tool result message (role="tool" in OpenAI format) — sanitizer coerces to user
        toolResults.isNotEmpty() -> ChatMessage(
            role = "tool",
            content = toolResults.first().content,
            toolCallId = toolResults.first().toolUseId,
            parts = parts
        )
        // Plain assistant or user message (including assistant turns that originally
        // had tool_use blocks — now rendered as inline XML in textContent).
        else -> ChatMessage(
            role = role.name.lowercase(),
            content = textContent ?: "",
            toolCalls = null,  // toolCalls is dead after migration
            parts = parts
        )
    }
}

/**
 * Convert ChatMessage back to ApiMessage (for migration from old format).
 *
 * [ChatMessage.toolCalls] is deliberately ignored — after the XML-in-content
 * migration (2026-05-13), ChatMessage.toolCalls is always null on the production
 * path; any legacy input that still carries them would be re-coded as Text
 * already by callers.
 */
fun ChatMessage.toApiMessage(): ApiMessage {
    val blocks = mutableListOf<ContentBlock>()
    if (!content.isNullOrBlank()) blocks.add(ContentBlock.Text(content!!))
    // toolCalls deliberately ignored — after the XML-in-content migration,
    // ChatMessage.toolCalls is always null on the production path; any legacy
    // input that still carries them would be re-coded as Text already by callers.
    if (role == "tool" && toolCallId != null) {
        return ApiMessage(
            role = ApiRole.USER,
            content = listOf(ContentBlock.ToolResult(toolUseId = toolCallId!!, content = content ?: ""))
        )
    }
    val apiRole = if (role == "assistant") ApiRole.ASSISTANT else ApiRole.USER
    return ApiMessage(role = apiRole, content = blocks)
}
