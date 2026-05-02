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
 * Serialize path: writes a minimal stub `{type:"UnsupportedContentBlock", originalType, rawJson}`.
 *
 * **IMPORTANT — this serializer IS invoked on every session save** (`addToApiConversationHistory`,
 * `pruneTrailingEmptyAssistants`, `overwriteApiConversationHistory`, `rewriteMostRecentToolResult`,
 * `saveBoth`). Whenever a v1 plugin loads a v2 session and then mutates the in-memory
 * `apiHistory`, every `UnsupportedContentBlock` in that history gets re-serialized through
 * this method.
 *
 * The current implementation is **intentionally lossy** for v1's local needs:
 *   - On the first round-trip, `type` becomes `"UnsupportedContentBlock"` (not the original
 *     v2 discriminator like `"image_url_ref"`).
 *   - On the second read, the polymorphic fallback fires AGAIN because
 *     `"UnsupportedContentBlock"` is not in the registered subclass set, producing a
 *     doubly-wrapped block whose `originalType` field is now `"UnsupportedContentBlock"`
 *     and whose `rawJson` contains the wrapper.
 *
 * **Implication for downgrade-then-upgrade scenarios:** if the user runs v2 → writes a
 * v2 session → downgrades to v1 → mutates the session → upgrades back to v2, the v2
 * reader will NOT see the original `image_url_ref` discriminator on the mutated turns;
 * it will see a doubly-wrapped `UnsupportedContentBlock` with no way to recover the
 * original payload.
 *
 * For the project's single-user threat model (one user upgrades both versions on the
 * same machine), this is acceptable — there is no realistic downgrade-then-mutate path.
 * If we ever need full lossless round-trip, change `serialize` to write the verbatim
 * `rawJson` via `encoder.asJsonEncoder().encodeJsonElement(Json.parseToJsonElement(value.rawJson))`.
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
