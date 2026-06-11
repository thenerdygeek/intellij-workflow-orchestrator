package com.workflow.orchestrator.agent.walkthrough

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WalkthroughStepParsingTest {

    private val goodJson = """
        [{"file": "core/src/A.kt", "start_line": 4, "end_line": 7,
          "title": "Entry", "body_md": "The **entry** point."},
         {"file": "core/src/B.kt", "start_line": 1, "end_line": 1,
          "body_md": "No title here."}]
    """.trimIndent()

    @Test
    fun `parses a JSON array carried inside a string primitive`() {
        val result = parseStepsJson(JsonPrimitive(goodJson))
        assertEquals(2, result.steps.size)
        assertTrue(result.errors.isEmpty())
        assertEquals(WalkthroughStep("core/src/A.kt", 4, 7, "Entry", "The **entry** point."), result.steps[0])
        assertEquals(null, result.steps[1].title)
    }

    @Test
    fun `parses a native JsonArray defensively`() {
        val result = parseStepsJson(Json.parseToJsonElement(goodJson))
        assertEquals(2, result.steps.size)
    }

    @Test
    fun `missing param and non-array string are itemized errors`() {
        assertEquals(listOf("steps parameter is missing"), parseStepsJson(null).errors)
        val bad = parseStepsJson(JsonPrimitive("not json"))
        assertTrue(bad.steps.isEmpty())
        assertTrue(bad.errors.single().contains("JSON array"))
    }

    @Test
    fun `invalid steps are rejected individually with positional messages, valid ones kept`() {
        val mixed = JsonPrimitive(
            """[{"file": "A.kt", "start_line": 1, "end_line": 2, "body_md": "ok"},
                {"start_line": 1, "end_line": 2, "body_md": "no file"},
                {"file": "C.kt", "start_line": 5, "end_line": 3, "body_md": "inverted"},
                {"file": "D.kt", "start_line": 0, "end_line": 3, "body_md": "zero start"},
                {"file": "E.kt", "start_line": 1, "end_line": 2, "body_md": ""}]"""
        )
        val result = parseStepsJson(mixed)
        assertEquals(1, result.steps.size)
        assertEquals(4, result.errors.size)
        assertTrue(result.errors[0].startsWith("step 2:"))
        assertTrue(result.errors[1].contains("end_line"))
        assertTrue(result.errors[2].contains("start_line"))
        assertTrue(result.errors[3].contains("body_md"))
    }
}
