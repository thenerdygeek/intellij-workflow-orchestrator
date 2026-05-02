package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 1 of multimodal-agent plan — defensive deserialization for forward-compat read.
 *
 * `kotlinx-serialization`'s `ignoreUnknownKeys = true` covers unknown FIELDS within known
 * polymorphic subclasses, but it does NOT cover unknown polymorphic DISCRIMINATORS. A v1
 * plugin loading a v2 session file (which may contain `type: "image_url_ref"` from
 * Phase 4) would otherwise crash with `SerializationException: Serializer for subclass
 * '...' is not found in the polymorphic scope of 'ContentBlock'`.
 *
 * `UnsupportedContentBlock` + `defaultDeserializer` in the polymorphic module preserve
 * the unknown payload as a placeholder so v1 readers degrade gracefully to
 * `[unsupported attachment]` text instead of throwing.
 */
class UnknownContentBlockTest {

    private val json = MessageStateHandler.jsonForTesting()

    @Test
    fun `unknown content-block discriminator deserializes to UnsupportedContentBlock`() {
        // ApiRole serializes as the enum constant name (USER / ASSISTANT) by default.
        val raw = """
            {
              "role": "USER",
              "content": [
                {"type": "text", "text": "hello"},
                {"type": "some_future_type", "anyKey": "anyValue"}
              ]
            }
        """.trimIndent()

        val msg = json.decodeFromString(ApiMessage.serializer(), raw)
        assertEquals(2, msg.content.size)
        assertTrue(msg.content[0] is ContentBlock.Text)
        assertTrue(
            msg.content[1] is UnsupportedContentBlock,
            "Expected UnsupportedContentBlock for type='some_future_type', got ${msg.content[1]::class}"
        )
        val unsupported = msg.content[1] as UnsupportedContentBlock
        assertEquals("some_future_type", unsupported.originalType)
        assertTrue(
            unsupported.rawJson.contains("anyKey"),
            "rawJson should preserve original payload for debugging; got: ${unsupported.rawJson}"
        )
    }

    @Test
    fun `unsupported block round-trips through toChatMessage as placeholder text`() {
        val msg = ApiMessage(
            role = ApiRole.USER,
            content = listOf(
                ContentBlock.Text("see this:"),
                UnsupportedContentBlock("some_future_type", "{}")
            )
        )
        val chatMsg = msg.toChatMessage()
        assertNotNull(chatMsg.content)
        assertTrue(
            chatMsg.content!!.contains("see this:"),
            "Expected text content preserved; got: ${chatMsg.content}"
        )
        assertTrue(
            chatMsg.content!!.contains("[unsupported attachment]"),
            "Expected placeholder text for unsupported block; got: ${chatMsg.content}"
        )
    }

    @Test
    fun `known content-block discriminators continue to deserialize normally`() {
        // Regression guard — adding the polymorphic default must not break known types.
        val raw = """
            {
              "role": "ASSISTANT",
              "content": [
                {"type": "text", "text": "calling tool"},
                {"type": "tool_use", "id": "tu_1", "name": "read_file", "input": "{}"}
              ]
            }
        """.trimIndent()

        val msg = json.decodeFromString(ApiMessage.serializer(), raw)
        assertEquals(2, msg.content.size)
        assertTrue(msg.content[0] is ContentBlock.Text)
        assertTrue(msg.content[1] is ContentBlock.ToolUse)
    }
}
