package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.util.JsEscape
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * End-to-end tests for the ask_followup_question flow:
 *
 *   LLM response → AskQuestionsTool.execute() → callback → wizard JSON → JsEscape → JS bridge
 *
 * Each test reproduces the exact data the LLM sends and verifies it survives every
 * transformation stage. A failure at ANY stage leaves the UI stuck with a spinner —
 * the "ask followup question bug."
 *
 * The test names include the stage they target so failures are instantly diagnosable:
 *   [Stage 2] = tool execution / callback invocation
 *   [Stage 3] = wizard JSON construction
 *   [Stage 4] = JsEscape double-escaping
 *   [Stage 5] = JS-side JSON.parse simulation
 */
@DisplayName("ask_followup_question: full flow (tool → callback → JSON → JsEscape → JS parse)")
class AskFollowupQuestionFlowTest {

    private val tool = AskQuestionsTool()
    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    @AfterEach
    fun cleanup() {
        AskQuestionsTool.showSimpleQuestionCallback = null
        AskQuestionsTool.showQuestionsCallback = null
        AskQuestionsTool.pendingQuestions = null
    }

    // ════════════════════════════════════════════
    //  Stage 2: Callback invocation
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("[Stage 2] Callback invocation with various option formats")
    inner class CallbackInvocation {

        @Test
        fun `callback fires with options when LLM sends JSON string array`() = runTest {
            var receivedQuestion: String? = null
            var receivedOptions: String? = null

            AskQuestionsTool.showSimpleQuestionCallback = { q, opts ->
                receivedQuestion = q
                receivedOptions = opts
            }

            val job = launch {
                tool.execute(buildJsonObject {
                    put("question", "What would you like to work on today?")
                    put("options", """["Help with PROJECT-12345 (some API fixes)", "Something else"]""")
                }, project)
            }

            kotlinx.coroutines.yield()

            assertEquals("What would you like to work on today?", receivedQuestion)
            assertNotNull(receivedOptions, "Options should NOT be null — the LLM sent options")
            // The callback re-serializes options, so it should be a valid JSON array
            val parsed = json.decodeFromString<List<String>>(receivedOptions!!)
            assertEquals(2, parsed.size)
            assertEquals("Help with PROJECT-12345 (some API fixes)", parsed[0])
            assertEquals("Something else", parsed[1])

            AskQuestionsTool.pendingQuestions?.complete("user answer")
            job.join()
        }

        @Test
        fun `callback fires with null options when LLM sends no options`() = runTest {
            var receivedOptions: String? = "sentinel"

            AskQuestionsTool.showSimpleQuestionCallback = { _, opts ->
                receivedOptions = opts
            }

            val job = launch {
                tool.execute(buildJsonObject {
                    put("question", "What should I do?")
                }, project)
            }

            kotlinx.coroutines.yield()
            assertNull(receivedOptions, "Options should be null when not provided")

            AskQuestionsTool.pendingQuestions?.complete("answer")
            job.join()
        }

        @Test
        fun `callback fires with null options when options is empty string`() = runTest {
            var receivedOptions: String? = "sentinel"

            AskQuestionsTool.showSimpleQuestionCallback = { _, opts ->
                receivedOptions = opts
            }

            val job = launch {
                tool.execute(buildJsonObject {
                    put("question", "Pick one")
                    put("options", "")
                }, project)
            }

            kotlinx.coroutines.yield()
            assertNull(receivedOptions, "Empty string options should normalize to null")

            AskQuestionsTool.pendingQuestions?.complete("answer")
            job.join()
        }

        @Test
        fun `callback fires with null options when options is invalid JSON`() = runTest {
            var receivedOptions: String? = "sentinel"

            AskQuestionsTool.showSimpleQuestionCallback = { _, opts ->
                receivedOptions = opts
            }

            val job = launch {
                tool.execute(buildJsonObject {
                    put("question", "Pick one")
                    put("options", "not valid json [")
                }, project)
            }

            kotlinx.coroutines.yield()
            // Invalid JSON → options parsed as empty list → re-serialized as null
            assertNull(receivedOptions, "Invalid JSON options should fall back to null (no options)")

            AskQuestionsTool.pendingQuestions?.complete("answer")
            job.join()
        }

        @Test
        fun `callback fires with 5 options`() = runTest {
            var receivedOptions: String? = null

            AskQuestionsTool.showSimpleQuestionCallback = { _, opts ->
                receivedOptions = opts
            }

            val options = (1..5).map { "Option $it with (special) chars & more" }
            val optionsJson = "[${options.joinToString(",") { "\"$it\"" }}]"

            val job = launch {
                tool.execute(buildJsonObject {
                    put("question", "Pick one")
                    put("options", optionsJson)
                }, project)
            }

            kotlinx.coroutines.yield()
            assertNotNull(receivedOptions)
            val parsed = json.decodeFromString<List<String>>(receivedOptions!!)
            assertEquals(5, parsed.size)

            AskQuestionsTool.pendingQuestions?.complete("answer")
            job.join()
        }
    }

    // ════════════════════════════════════════════
    //  Stage 3: Wizard JSON construction
    //  (reproduces the exact code in AgentController's showSimpleQuestionCallback)
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("[Stage 3] Wizard JSON construction")
    inner class WizardJsonConstruction {

        /**
         * Builds wizard JSON exactly as the AgentController callback does.
         * This is a copy of the callback's JSON-building code — if the callback
         * changes, this test will go stale, which is intentional (forces update).
         */
        private fun buildWizardJson(question: String, options: List<String>): String {
            return buildString {
                append("{\"questions\":[{\"id\":\"q1\",\"question\":")
                append(JsEscape.toJsonString(question))
                append(""","type":"single","options":[""")
                options.forEachIndexed { i, opt ->
                    if (i > 0) append(",")
                    append("{\"id\":\"o${i + 1}\",\"label\":")
                    append(JsEscape.toJsonString(opt))
                    append("}")
                }
                append("]}]}")
            }
        }

        @Test
        fun `wizard JSON is valid JSON for simple options`() {
            val wizardJson = buildWizardJson(
                "What would you like?",
                listOf("Option A", "Option B")
            )

            // Must be parseable as JSON
            val parsed = json.parseToJsonElement(wizardJson).jsonObject
            assertNotNull(parsed["questions"])
            val questions = parsed["questions"]!!.jsonArray
            assertEquals(1, questions.size)
            val q = questions[0].jsonObject
            assertEquals("What would you like?", q["question"]!!.jsonPrimitive.content)
            assertEquals("single", q["type"]!!.jsonPrimitive.content)
            val opts = q["options"]!!.jsonArray
            assertEquals(2, opts.size)
            assertEquals("Option A", opts[0].jsonObject["label"]!!.jsonPrimitive.content)
            assertEquals("Option B", opts[1].jsonObject["label"]!!.jsonPrimitive.content)
        }

        @Test
        fun `wizard JSON handles double quotes in options`() {
            val wizardJson = buildWizardJson(
                "Pick a value",
                listOf("""She said "hello"""", """It's a "test" value""")
            )

            val parsed = json.parseToJsonElement(wizardJson).jsonObject
            val opts = parsed["questions"]!!.jsonArray[0].jsonObject["options"]!!.jsonArray
            assertEquals("""She said "hello"""", opts[0].jsonObject["label"]!!.jsonPrimitive.content)
        }

        @Test
        fun `wizard JSON handles single quotes (apostrophes)`() {
            val wizardJson = buildWizardJson(
                "What's the plan?",
                listOf("It's a refactor", "Don't change", "Let's discuss")
            )

            val parsed = json.parseToJsonElement(wizardJson).jsonObject
            val q = parsed["questions"]!!.jsonArray[0].jsonObject
            assertEquals("What's the plan?", q["question"]!!.jsonPrimitive.content)
        }

        @Test
        fun `wizard JSON handles parentheses and brackets in options`() {
            val wizardJson = buildWizardJson(
                "Which task?",
                listOf(
                    "Fix PROJECT-12345 (critical bug in AuthService)",
                    "Refactor CORE-999 [low priority]",
                    "Something {else} entirely"
                )
            )

            val parsed = json.parseToJsonElement(wizardJson).jsonObject
            val opts = parsed["questions"]!!.jsonArray[0].jsonObject["options"]!!.jsonArray
            assertEquals(3, opts.size)
            assertTrue(opts[0].jsonObject["label"]!!.jsonPrimitive.content.contains("PROJECT-12345"))
        }

        @Test
        fun `wizard JSON handles newlines in question text`() {
            val wizardJson = buildWizardJson(
                "Choose one:\n- A refactor approach\n- A quick patch",
                listOf("Refactor", "Patch")
            )

            val parsed = json.parseToJsonElement(wizardJson).jsonObject
            assertTrue(parsed["questions"]!!.jsonArray[0].jsonObject["question"]!!
                .jsonPrimitive.content.contains("Choose one:"))
        }

        @Test
        fun `wizard JSON handles backslashes (file paths)`() {
            val wizardJson = buildWizardJson(
                "Which path?",
                listOf("src\\main\\kotlin\\App.kt", "C:\\Users\\dev\\project")
            )

            val parsed = json.parseToJsonElement(wizardJson).jsonObject
            val opts = parsed["questions"]!!.jsonArray[0].jsonObject["options"]!!.jsonArray
            assertTrue(opts[0].jsonObject["label"]!!.jsonPrimitive.content.contains("src\\main"))
        }

        @Test
        fun `wizard JSON handles empty-ish options`() {
            val wizardJson = buildWizardJson(
                "Continue?",
                listOf("Yes", "No")
            )

            val parsed = json.parseToJsonElement(wizardJson).jsonObject
            val opts = parsed["questions"]!!.jsonArray[0].jsonObject["options"]!!.jsonArray
            assertEquals(2, opts.size)
        }
    }

    // ════════════════════════════════════════════
    //  Stage 4: JsEscape double-escaping
    //  (wizard JSON → toJsString → simulated executeJavaScript)
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("[Stage 4] JsEscape: wizard JSON survives callJs() round-trip")
    inner class JsEscapeRoundTrip {

        /**
         * Simulates the exact code path in AgentCefPanel.showQuestions():
         *   callJs("showQuestions(${JsEscape.toJsString(questionsJson)})")
         *
         * The resulting string must be a valid JS expression. We can't run JS here,
         * but we can verify:
         * 1. The outer wrapper is single-quoted
         * 2. The inner content, when unescaped, equals the original JSON
         * 3. No unescaped characters that would break JS parsing
         */
        private fun simulateCallJs(wizardJson: String): String {
            return "showQuestions(${JsEscape.toJsString(wizardJson)})"
        }

        /**
         * Reverse the JsEscape.toJsString escaping to recover the original string.
         * This simulates what the JS engine does when parsing the string literal.
         */
        private fun unescapeJsString(jsString: String): String {
            // Strip surrounding single quotes
            assertTrue(jsString.startsWith("'") && jsString.endsWith("'"),
                "JsEscape.toJsString must wrap in single quotes: $jsString")
            val inner = jsString.substring(1, jsString.length - 1)
            // Reverse escapes (order matters — backslash first)
            return inner
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
        }

        @Test
        fun `simple wizard JSON survives callJs round-trip`() {
            val wizardJson = "{\"questions\":[{\"id\":\"q1\",\"question\":\"Pick one\",\"type\":\"single\",\"options\":[{\"id\":\"o1\",\"label\":\"A\"},{\"id\":\"o2\",\"label\":\"B\"}]}]}"
            val jsCall = simulateCallJs(wizardJson)

            // Extract the argument from showQuestions('...')
            val argStart = jsCall.indexOf("(") + 1
            val argEnd = jsCall.lastIndexOf(")")
            val jsStringArg = jsCall.substring(argStart, argEnd)

            val recovered = unescapeJsString(jsStringArg)
            assertEquals(wizardJson, recovered, "JSON must survive JsEscape round-trip unchanged")

            // Recovered JSON must still parse
            val parsed = Json.parseToJsonElement(recovered).jsonObject
            assertNotNull(parsed["questions"])
        }

        @Test
        fun `wizard JSON with double quotes in labels survives round-trip`() {
            val wizardJson = buildString {
                append("{\"questions\":[{\"id\":\"q1\",\"question\":")
                append(JsEscape.toJsonString("""She said "hello""""))
                append(",\"type\":\"single\",\"options\":[{\"id\":\"o1\",\"label\":")
                append(JsEscape.toJsonString("""Use "quotes" here"""))
                append("}]}]}")
            }

            val jsCall = simulateCallJs(wizardJson)
            val argStart = jsCall.indexOf("(") + 1
            val argEnd = jsCall.lastIndexOf(")")
            val jsStringArg = jsCall.substring(argStart, argEnd)
            val recovered = unescapeJsString(jsStringArg)

            // Must parse as valid JSON
            val parsed = Json.parseToJsonElement(recovered)
            assertNotNull(parsed)
        }

        @Test
        fun `wizard JSON with single quotes survives round-trip`() {
            val wizardJson = buildString {
                append("{\"questions\":[{\"id\":\"q1\",\"question\":")
                append(JsEscape.toJsonString("What's the plan?"))
                append(",\"type\":\"single\",\"options\":[{\"id\":\"o1\",\"label\":")
                append(JsEscape.toJsonString("It's a refactor"))
                append("},{\"id\":\"o2\",\"label\":")
                append(JsEscape.toJsonString("Don't change"))
                append("}]}]}")
            }

            val jsCall = simulateCallJs(wizardJson)
            val argStart = jsCall.indexOf("(") + 1
            val argEnd = jsCall.lastIndexOf(")")
            val jsStringArg = jsCall.substring(argStart, argEnd)
            val recovered = unescapeJsString(jsStringArg)

            val parsed = Json.parseToJsonElement(recovered).jsonObject
            val q = parsed["questions"]!!.jsonArray[0].jsonObject
            assertEquals("What's the plan?", q["question"]!!.jsonPrimitive.content)
        }

        @Test
        fun `wizard JSON with backslashes survives round-trip`() {
            val wizardJson = buildString {
                append("{\"questions\":[{\"id\":\"q1\",\"question\":")
                append(JsEscape.toJsonString("Which path?"))
                append(",\"type\":\"single\",\"options\":[{\"id\":\"o1\",\"label\":")
                append(JsEscape.toJsonString("src\\main\\kotlin\\App.kt"))
                append("}]}]}")
            }

            val jsCall = simulateCallJs(wizardJson)
            val argStart = jsCall.indexOf("(") + 1
            val argEnd = jsCall.lastIndexOf(")")
            val jsStringArg = jsCall.substring(argStart, argEnd)
            val recovered = unescapeJsString(jsStringArg)

            val parsed = Json.parseToJsonElement(recovered).jsonObject
            val label = parsed["questions"]!!.jsonArray[0].jsonObject["options"]!!
                .jsonArray[0].jsonObject["label"]!!.jsonPrimitive.content
            assertTrue(label.contains("src\\main"), "Backslashes should survive: got '$label'")
        }
    }

    // ════════════════════════════════════════════
    //  Stage 5: Simulated JS-side bridge parsing
    //  (reproduces the logic in jcef-bridge.ts showQuestions)
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("[Stage 5] Simulated JS bridge parsing")
    inner class JsBridgeParsing {

        /**
         * Simulates the JS bridge's showQuestions function:
         *   const parsed = JSON.parse(questionsJson);
         *   const rawQuestions = Array.isArray(parsed) ? parsed : parsed.questions;
         *   questions = rawQuestions.map(q => ({ ...q, text: q.text ?? q.question, type: ... }))
         *
         * We simulate this in Kotlin to verify the JSON structure is correct.
         */
        private fun simulateJsBridgeParsing(wizardJson: String): List<Map<String, Any>> {
            val parsed = Json.parseToJsonElement(wizardJson).jsonObject
            val rawQuestions = parsed["questions"]?.jsonArray
                ?: throw IllegalArgumentException("No 'questions' key in JSON")

            return rawQuestions.map { qElement ->
                val q = qElement.jsonObject
                val mappedType = when (q["type"]?.jsonPrimitive?.content) {
                    "single" -> "single-select"
                    "multiple" -> "multi-select"
                    else -> q["type"]?.jsonPrimitive?.content ?: "unknown"
                }
                mapOf(
                    "id" to (q["id"]?.jsonPrimitive?.content ?: ""),
                    "text" to (q["text"]?.jsonPrimitive?.content
                        ?: q["question"]?.jsonPrimitive?.content ?: ""),
                    "type" to mappedType,
                    "options" to (q["options"]?.jsonArray?.map { opt ->
                        mapOf(
                            "id" to opt.jsonObject["id"]!!.jsonPrimitive.content,
                            "label" to opt.jsonObject["label"]!!.jsonPrimitive.content
                        )
                    } ?: emptyList<Map<String, String>>())
                )
            }
        }

        @Test
        fun `JS bridge correctly parses wizard JSON with two options`() {
            val wizardJson = buildWizardJsonForTest(
                "What would you like to work on today?",
                listOf("Help with PROJECT-12345 (some API fixes)", "Something else")
            )

            val questions = simulateJsBridgeParsing(wizardJson)

            assertEquals(1, questions.size)
            assertEquals("q1", questions[0]["id"])
            assertEquals("What would you like to work on today?", questions[0]["text"])
            assertEquals("single-select", questions[0]["type"])

            @Suppress("UNCHECKED_CAST")
            val options = questions[0]["options"] as List<Map<String, String>>
            assertEquals(2, options.size)
            assertEquals("Help with PROJECT-12345 (some API fixes)", options[0]["label"])
            assertEquals("Something else", options[1]["label"])
        }

        @Test
        fun `JS bridge correctly maps question field to text`() {
            // Kotlin sends "question", JS expects "text" — the bridge maps q.question → text
            val wizardJson = buildWizardJsonForTest("My question", listOf("A"))
            val questions = simulateJsBridgeParsing(wizardJson)

            assertEquals("My question", questions[0]["text"],
                "Bridge must map 'question' field to 'text' (Kotlin sends 'question', TS expects 'text')")
        }

        @Test
        fun `JS bridge correctly maps single type to single-select`() {
            val wizardJson = buildWizardJsonForTest("Pick", listOf("A"))
            val questions = simulateJsBridgeParsing(wizardJson)

            assertEquals("single-select", questions[0]["type"],
                "Bridge must map 'single' → 'single-select'")
        }

        @Test
        fun `full pipeline from XML parser params through tool callback wizard JSON JsEscape to JS parse`() {
            // This is THE test that reproduces the user's exact bug scenario
            val question = "What would you like to work on today?"
            val optionsJsonStr = """["Help with PROJECT-12345 (some API fixes)", "Something else"]"""

            // Step 1: Simulate what the XML parser extracts
            val xmlExtractedParams = buildJsonObject {
                put("question", question)
                put("options", optionsJsonStr)
            }

            // Step 2: Simulate what AskQuestionsTool.executeSimple does
            val parsedOptions = Json.decodeFromString<List<String>>(
                xmlExtractedParams["options"]!!.jsonPrimitive.content
            )
            val reserializedOptions = "[${parsedOptions.joinToString(",") { JsEscape.toJsonString(it) }}]"

            // Wait — reserializedOptions uses JsEscape.toJsonString which wraps in double quotes.
            // So reserializedOptions = ["Help with PROJECT-12345 (some API fixes)","Something else"]
            // This is actually a valid JSON array string!
            val reParsed = Json.decodeFromString<List<String>>(reserializedOptions)
            assertEquals(2, reParsed.size, "Re-serialized options must be valid JSON")

            // Step 3: Simulate what the AgentController callback does — build wizard JSON
            val wizardJson = buildWizardJsonForTest(question, parsedOptions)

            // Step 4: Verify wizard JSON is valid
            val parsedWizard = Json.parseToJsonElement(wizardJson).jsonObject
            assertNotNull(parsedWizard["questions"])

            // Step 5: Simulate JsEscape wrapping for callJs
            val jsString = JsEscape.toJsString(wizardJson)
            assertFalse(jsString.contains("undefined"), "JsEscape must not produce 'undefined'")

            // Step 6: Simulate JS-side parsing
            val questions = simulateJsBridgeParsing(wizardJson)
            assertEquals(1, questions.size)
            assertEquals(question, questions[0]["text"])

            @Suppress("UNCHECKED_CAST")
            val opts = questions[0]["options"] as List<Map<String, String>>
            assertEquals("Help with PROJECT-12345 (some API fixes)", opts[0]["label"])
            assertEquals("Something else", opts[1]["label"])
        }

        /** Helper: builds wizard JSON exactly as AgentController does */
        private fun buildWizardJsonForTest(question: String, options: List<String>): String {
            return buildString {
                append("{\"questions\":[{\"id\":\"q1\",\"question\":")
                append(JsEscape.toJsonString(question))
                append(""","type":"single","options":[""")
                options.forEachIndexed { i, opt ->
                    if (i > 0) append(",")
                    append("{\"id\":\"o${i + 1}\",\"label\":")
                    append(JsEscape.toJsonString(opt))
                    append("}")
                }
                append("]}]}")
            }
        }
    }

    // ════════════════════════════════════════════
    //  Stage 6: Deferred resolution / timeout
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("[Stage 6] Deferred lifecycle")
    inner class DeferredLifecycle {

        @Test
        fun `pendingQuestions is set while waiting and cleared after answer`() = runTest {
            AskQuestionsTool.showSimpleQuestionCallback = { _, _ -> }

            val job = launch {
                tool.execute(buildJsonObject {
                    put("question", "Test?")
                    put("options", """["A", "B"]""")
                }, project)
            }

            kotlinx.coroutines.yield()
            assertNotNull(AskQuestionsTool.pendingQuestions, "Deferred must be set while waiting")
            assertFalse(AskQuestionsTool.pendingQuestions!!.isCompleted, "Deferred must not be completed yet")

            AskQuestionsTool.pendingQuestions?.complete("""{"q1":["o1"]}""")
            job.join()

            assertNull(AskQuestionsTool.pendingQuestions, "Deferred must be cleared after completion")
        }

        @Test
        fun `cancellation resolves with cancelled JSON`() = runTest {
            AskQuestionsTool.showSimpleQuestionCallback = { _, _ -> }

            val job = launch {
                val result = tool.execute(buildJsonObject {
                    put("question", "Test?")
                }, project)
                assertFalse(result.isError)
                assertTrue(result.content.contains("dismissed"))
            }

            kotlinx.coroutines.yield()
            AskQuestionsTool.cancelQuestions()
            job.join()
        }

        @Test
        fun `tool returns error when no callback is set`() = runTest {
            // Neither showSimpleQuestionCallback nor showQuestionsCallback is set
            AskQuestionsTool.showSimpleQuestionCallback = null
            AskQuestionsTool.showQuestionsCallback = null

            val result = tool.execute(buildJsonObject {
                put("question", "Test?")
            }, project)

            assertTrue(result.isError, "Should return error when UI not available")
            assertTrue(result.content.contains("not available"))
        }

        @Test
        fun `tool returns answer wrapped in answer tags`() = runTest {
            AskQuestionsTool.showSimpleQuestionCallback = { _, _ -> }

            val job = launch {
                val result = tool.execute(buildJsonObject {
                    put("question", "What DB?")
                }, project)
                assertFalse(result.isError)
                assertTrue(result.content.contains("<answer>"))
                assertTrue(result.content.contains("PostgreSQL"))
                assertTrue(result.content.contains("</answer>"))
            }

            kotlinx.coroutines.yield()
            AskQuestionsTool.pendingQuestions?.complete("PostgreSQL")
            job.join()
        }
    }

    // ════════════════════════════════════════════
    //  Stage 7: Wizard fallback path
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("[Stage 7] Wizard callback fallback when simpleCallback is null")
    inner class WizardFallback {

        @Test
        fun `falls back to showQuestionsCallback when showSimpleQuestionCallback is null`() = runTest {
            AskQuestionsTool.showSimpleQuestionCallback = null

            var receivedJson: String? = null
            AskQuestionsTool.showQuestionsCallback = { json ->
                receivedJson = json
            }

            val job = launch {
                tool.execute(buildJsonObject {
                    put("question", "Pick one")
                    put("options", """["A", "B"]""")
                }, project)
            }

            kotlinx.coroutines.yield()

            assertNotNull(receivedJson, "Wizard callback must fire as fallback")
            // The fallback wraps the question in wizard format
            val parsed = Json.parseToJsonElement(receivedJson!!).jsonObject
            assertNotNull(parsed["questions"])

            AskQuestionsTool.pendingQuestions?.complete("answer")
            job.join()
        }
    }
}
