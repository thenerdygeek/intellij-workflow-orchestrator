package com.workflow.orchestrator.agent.session

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ApiMessageProtocolDiscriminatorTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

    @Test fun `protocol defaults to null and a v2 file without the field still deserializes`() {
        // Backward compat: an existing on-disk message JSON with NO protocol field loads with protocol=null.
        val legacy = """{"role":"USER","content":[{"type":"text","text":"hi"}],"ts":1}"""
        val msg = json.decodeFromString(ApiMessage.serializer(), legacy)
        assertNull(msg.protocol)
    }

    @Test fun `a default-null protocol round-trips as null (M4 - encodeDefaults emits it, decode restores it)`() {
        // M4: NOT an omission test. encodeDefaults=true EMITS `"protocol":null` (it does not omit
        // defaults), so the correct invariant is ROUND-TRIP EQUALITY, not absence-from-output.
        val msg = ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("hi")), ts = 1)
        val out = json.encodeToString(ApiMessage.serializer(), msg)
        val restored = json.decodeFromString(ApiMessage.serializer(), out)
        assertEquals(msg.protocol, restored.protocol)
        assertNull(restored.protocol)
    }

    @Test fun `a reserved protocol value round-trips`() {
        val msg = ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("x")), ts = 1, protocol = "xml")
        val restored = json.decodeFromString(ApiMessage.serializer(), json.encodeToString(ApiMessage.serializer(), msg))
        assertEquals("xml", restored.protocol)
        assertFalse(restored.protocol == null)
    }
}
