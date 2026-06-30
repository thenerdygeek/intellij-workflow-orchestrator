package com.workflow.orchestrator.core.ai.anthropic

import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.ToolUseContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolUseXmlRoundTripTest {

    private fun roundTrip(name: String, input: JsonObject, params: Set<String>): ToolUseContent {
        val xml = ToolUseXmlSerializer.toXml(name, input)
        return AssistantMessageParser.parse(xml, setOf(name), params)
            .filterIsInstance<ToolUseContent>().single()
    }

    @Test
    fun `single param round-trips canonically`() {
        val call = roundTrip("read_file", buildJsonObject { put("path", "src/Foo.kt") }, setOf("path"))
        assertEquals("read_file", call.name)
        assertEquals("src/Foo.kt", call.params["path"])
    }

    @Test
    fun `code-carrying param with embedded angle brackets round-trips`() {
        val call = roundTrip(
            "edit_file",
            buildJsonObject {
                put("path", "F.kt")
                put("old_string", "a<b")
                put("new_string", "c")
            },
            setOf("path", "old_string", "new_string"),
        )
        assertEquals("a<b", call.params["old_string"])
    }

    @Test
    fun `array value serializes as compact JSON`() {
        val call = roundTrip(
            "grep",
            buildJsonObject {
                putJsonArray("globs") {
                    add(JsonPrimitive("a"))
                    add(JsonPrimitive("b"))
                }
            },
            setOf("globs"),
        )
        assertEquals("""["a","b"]""", call.params["globs"])
    }

    @Test
    fun `non-code param containing its own close tag is rejected at serialize time`() {
        val ex = assertThrows<IllegalStateException> {
            ToolUseXmlSerializer.toXml("run_command", buildJsonObject { put("command", "echo </command>") })
        }
        assertTrue(ex.message!!.contains("collision"))
    }
}
