package com.workflow.orchestrator.core.ai.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Multimodal content part attached to a [ChatMessage].
 *
 * `ChatMessage.content` is a flat `String?` for backward compatibility with
 * every existing call site. Image-bearing turns populate the new
 * `ChatMessage.parts: List<ContentPart>?` sibling field, leaving `content`
 * either null or a text-flattened mirror.
 *
 * The image variant carries [sha256] + [mime] + [originalFilename] — never
 * the raw bytes. The actual image bytes live on disk under
 * `sessions/{id}/attachments/<sha256>.<ext>` (managed by `AttachmentStore`
 * in the `:agent` module). Routing code (`BrainRouter`, future) reads
 * [ContentPart.Image] and resolves the bytes when constructing the
 * vision-capable wire payload.
 *
 * Phase 4 of multimodal-agent plan. See
 * `docs/research/2026-05-02-multimodal-agent-design.md` §Type model evolution.
 */
@Serializable
sealed interface ContentPart {

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentPart

    @Serializable
    @SerialName("image_url")
    data class Image(
        val sha256: String,
        val mime: String,
        val originalFilename: String? = null,
    ) : ContentPart
}

/**
 * True iff this message carries any image-bearing content part. Used by the
 * routing rule (Phase 6) to decide between the OpenAI-compat brain (text +
 * tools) and the Sourcegraph completions stream brain (image-bearing).
 */
fun ChatMessage.hasImageParts(): Boolean =
    parts?.any { it is ContentPart.Image } == true
