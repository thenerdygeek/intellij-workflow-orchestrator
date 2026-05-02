package com.workflow.orchestrator.agent.session

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A [ContentBlock] subtype that absorbs any unknown polymorphic discriminator.
 *
 * Lets v1 readers process v2+ session files without crashing — the unknown block
 * becomes a placeholder that round-trips through `ApiMessage.toChatMessage()` as
 * `"[unsupported attachment]"` text.
 *
 * The original `type` value and raw JSON are preserved so future tooling can audit
 * what was dropped. Phase 1 of the multimodal-agent plan.
 *
 * Wired into [MessageStateHandler]'s `Json` config via:
 * ```
 * polymorphic(ContentBlock::class) { defaultDeserializer { UnsupportedContentBlockSerializer } }
 * ```
 *
 * Note: `kotlinx-serialization`'s `ignoreUnknownKeys = true` only covers unknown
 * FIELDS within known polymorphic subclasses; unknown DISCRIMINATORS still throw.
 * This class plugs that gap.
 */
@Serializable(with = UnsupportedContentBlockSerializer::class)
data class UnsupportedContentBlock(
    val originalType: String,
    val rawJson: String,
) : ContentBlock

/**
 * Custom serializer for [UnsupportedContentBlock]. We need to capture the original
 * `type` field dynamically from the JSON (rather than from a fixed `@SerialName`)
 * so that the unknown discriminator is preserved verbatim for debugging.
 *
 * Serialize path: writes a minimal stub. We do NOT round-trip the unknown block back
 * onto the wire — write paths in [MessageStateHandler] only persist messages that
 * the running plugin understands, so this serializer is rarely (if ever) invoked
 * for output. The implementation is included for symmetry; the meaningful path is
 * `deserialize`.
 */
object UnsupportedContentBlockSerializer : KSerializer<UnsupportedContentBlock> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("UnsupportedContentBlock") {
            element("originalType", String.serializer().descriptor)
            element("rawJson", String.serializer().descriptor)
        }

    override fun serialize(encoder: Encoder, value: UnsupportedContentBlock) {
        val composite = encoder.beginStructure(descriptor)
        composite.encodeStringElement(descriptor, 0, value.originalType)
        composite.encodeStringElement(descriptor, 1, value.rawJson)
        composite.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): UnsupportedContentBlock {
        require(decoder is JsonDecoder) { "UnsupportedContentBlock requires JsonDecoder" }
        val element = decoder.decodeJsonElement()
        require(element is JsonObject) { "expected JsonObject, got $element" }
        val originalType = (element["type"] as? JsonPrimitive)?.content ?: "unknown"
        val rawJson = element.toString()
        return UnsupportedContentBlock(originalType, rawJson)
    }
}
