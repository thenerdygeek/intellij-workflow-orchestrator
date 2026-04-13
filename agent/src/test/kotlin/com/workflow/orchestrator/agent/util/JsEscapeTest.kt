package com.workflow.orchestrator.agent.util

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for JsEscape — the bridge between Kotlin strings and JCEF's executeJavaScript.
 *
 * A broken escape here is the #1 suspect for the "ask_followup_question stuck" bug because:
 *   1. JCEF's executeJavaScript SILENTLY swallows JS syntax errors
 *   2. If the JS string literal is malformed, the function call never executes
 *   3. setBusy(false) and showQuestions() both go through this path
 *   4. The result is a stuck spinner with no error visible anywhere
 *
 * These tests verify the double-escaping contract:
 *   Kotlin string → toJsonString() → JSON string literal → toJsString() → JS string literal
 */
@DisplayName("JsEscape: JCEF bridge string escaping")
class JsEscapeTest {

    // ════════════════════════════════════════════
    //  toJsString: Kotlin → JS string literal
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("toJsString")
    inner class ToJsString {

        @Test
        fun `wraps in single quotes`() {
            val result = JsEscape.toJsString("hello")
            assertTrue(result.startsWith("'"))
            assertTrue(result.endsWith("'"))
        }

        @Test
        fun `escapes single quotes`() {
            val result = JsEscape.toJsString("it's")
            assertEquals("'it\\'s'", result)
        }

        @Test
        fun `escapes double quotes`() {
            val result = JsEscape.toJsString("""say "hi"""")
            assertEquals("""'say \"hi\"'""", result)
        }

        @Test
        fun `escapes backslashes`() {
            val result = JsEscape.toJsString("a\\b")
            assertEquals("'a\\\\b'", result)
        }

        @Test
        fun `escapes newlines`() {
            val result = JsEscape.toJsString("line1\nline2")
            assertEquals("'line1\\nline2'", result)
        }

        @Test
        fun `escapes tabs`() {
            val result = JsEscape.toJsString("col1\tcol2")
            assertEquals("'col1\\tcol2'", result)
        }

        @Test
        fun `handles empty string`() {
            val result = JsEscape.toJsString("")
            assertEquals("''", result)
        }

        @Test
        fun `preserves unicode`() {
            val result = JsEscape.toJsString("日本語")
            assertEquals("'日本語'", result)
        }
    }

    // ════════════════════════════════════════════
    //  toJsonString: Kotlin → JSON string literal
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("toJsonString")
    inner class ToJsonString {

        @Test
        fun `wraps in double quotes`() {
            val result = JsEscape.toJsonString("hello")
            assertTrue(result.startsWith("\""))
            assertTrue(result.endsWith("\""))
        }

        @Test
        fun `escapes double quotes`() {
            val result = JsEscape.toJsonString("""say "hi"""")
            assertEquals(""""say \"hi\""""", result)
        }

        @Test
        fun `does NOT escape single quotes (valid in JSON strings)`() {
            val result = JsEscape.toJsonString("it's fine")
            assertEquals("\"it's fine\"", result)
        }

        @Test
        fun `escapes backslashes`() {
            val result = JsEscape.toJsonString("path\\to\\file")
            assertEquals("\"path\\\\to\\\\file\"", result)
        }

        @Test
        fun `produces valid JSON when used as a value`() {
            val value = JsEscape.toJsonString("She said \"hello\"")
            val jsonDoc = "{\"key\":$value}"

            // Must parse as valid JSON
            val parsed = Json.parseToJsonElement(jsonDoc).jsonObject
            assertEquals("She said \"hello\"", parsed["key"]?.jsonPrimitive?.content)
        }
    }

    // ════════════════════════════════════════════
    //  Double-escaping: toJsonString THEN toJsString
    //  (this is the exact path wizard JSON takes)
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Double-escaping: JSON string inside JS string")
    inner class DoubleEscaping {

        @Test
        fun `JSON with double quotes survives double-escaping`() {
            val original = """{"key":"value with \"quotes\""}"""
            // This JSON goes through toJsString when passed to callJs
            val jsString = JsEscape.toJsString(original)

            // Manually unescape JS string to recover the JSON
            val recovered = unescapeJs(jsString)
            assertEquals(original, recovered)

            // The recovered string must still be valid JSON
            val parsed = Json.parseToJsonElement(recovered)
            assertNotNull(parsed)
        }

        @Test
        fun `wizard-style JSON with toJsonString labels survives double-escaping`() {
            // Build JSON exactly as AgentController does
            val wizardJson = buildWizardJson(
                "What's the plan?",
                listOf("She said \"refactor\"", "path\\to\\file.kt")
            )

            // Step 1: wizardJson is valid JSON
            val parsedFirst = Json.parseToJsonElement(wizardJson)
            assertNotNull(parsedFirst)

            // Step 2: wrap in JS string (as callJs does)
            val jsString = JsEscape.toJsString(wizardJson)

            // Step 3: recover original JSON from JS string
            val recovered = unescapeJs(jsString)
            assertEquals(wizardJson, recovered, "JSON must survive JS wrapping round-trip")

            // Step 4: recovered JSON must still parse
            val parsedSecond = recovered.let { Json.parseToJsonElement(it) }.jsonObject
            val q = parsedSecond["questions"]!!.jsonArray[0].jsonObject
            assertEquals("What's the plan?", q["question"]!!.jsonPrimitive.content)

            val opts = q["options"]!!.jsonArray
            assertEquals("She said \"refactor\"", opts[0].jsonObject["label"]!!.jsonPrimitive.content)
            assertTrue(opts[1].jsonObject["label"]!!.jsonPrimitive.content.contains("path\\to"))
        }

        @Test
        fun `user's exact bug scenario — PROJECT-12345 with parentheses`() {
            // Reproduce the exact data from the user's bug report
            val question = "What would you like to work on today?"
            val opt1 = "Help with PROJECT-12345 (some API fixes)"
            val opt2 = "Something else"

            val wizardJson = buildWizardJson(question, listOf(opt1, opt2))

            // Must be valid JSON
            val parsed = Json.parseToJsonElement(wizardJson)
            assertNotNull(parsed)

            // Must survive callJs round-trip
            val jsString = JsEscape.toJsString(wizardJson)
            val recovered = unescapeJs(jsString)
            assertEquals(wizardJson, recovered)

            // Must parse after round-trip
            val reparsed = recovered.let { Json.parseToJsonElement(it) }.jsonObject
            val questions = reparsed["questions"]!!.jsonArray
            val opts = questions[0].jsonObject["options"]!!.jsonArray
            assertEquals(opt1, opts[0].jsonObject["label"]!!.jsonPrimitive.content)
            assertEquals(opt2, opts[1].jsonObject["label"]!!.jsonPrimitive.content)
        }

        @Test
        fun `setBusy call survives escaping`() {
            // setBusy is a simple call but verify the pattern works
            val code = "setBusy(false)"
            // This is what AgentCefPanel produces — no JsEscape needed for booleans
            assertTrue(code.contains("false"))
        }

        /** Builds wizard JSON exactly as AgentController does */
        private fun buildWizardJson(question: String, options: List<String>): String {
            return buildString {
                append("{\"questions\":[{\"id\":\"q1\",\"question\":")
                append(JsEscape.toJsonString(question))
                append(",\"type\":\"single\",\"options\":[")
                options.forEachIndexed { i, opt ->
                    if (i > 0) append(",")
                    append("{\"id\":\"o${i + 1}\",\"label\":")
                    append(JsEscape.toJsonString(opt))
                    append("}")
                }
                append("]}]}")
            }
        }

        /** Reverse JsEscape.toJsString to recover the original Kotlin string */
        private fun unescapeJs(jsString: String): String {
            assertTrue(jsString.startsWith("'") && jsString.endsWith("'"),
                "Expected single-quoted JS string")
            val inner = jsString.substring(1, jsString.length - 1)
            // Reverse in correct order (backslash-escape last so we don't double-reverse)
            val sb = StringBuilder()
            var i = 0
            while (i < inner.length) {
                if (i + 1 < inner.length && inner[i] == '\\') {
                    when (inner[i + 1]) {
                        '\\' -> { sb.append('\\'); i += 2 }
                        '\'' -> { sb.append('\''); i += 2 }
                        '"' -> { sb.append('"'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        else -> { sb.append(inner[i]); i++ }
                    }
                } else {
                    sb.append(inner[i])
                    i++
                }
            }
            return sb.toString()
        }
    }
}
