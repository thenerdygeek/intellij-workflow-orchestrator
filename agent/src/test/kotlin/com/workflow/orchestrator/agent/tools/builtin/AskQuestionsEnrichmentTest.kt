package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.util.JsEscape
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the answer-enrichment logic introduced in AgentController.
 *
 * When the user answers an ask_followup_question wizard, the controller now sends an
 * enriched payload to AskQuestionsTool.resolveQuestions() instead of raw synthetic
 * option ids.
 *
 * Because AgentController requires a live IntelliJ Project + JCEF, we test the
 * enrichment logic directly here by:
 *   1. Replicating the exact same parsing + enrichment functions used by the controller
 *      (same pattern as AskFollowupQuestionFlowTest.WizardJsonConstruction).
 *   2. Testing the full callback → parse → enrich → resolveQuestions pipeline by
 *      wiring the static AskQuestionsTool callbacks and asserting on pendingQuestions,
 *      the same way the rest of the test suite does.
 *
 * If AgentController's enrichment logic changes, these tests MUST be updated too.
 */
@DisplayName("ask_followup_question answer enrichment (id to label resolution)")
class AskQuestionsEnrichmentTest {

    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
    private val lenientJson = Json { ignoreUnknownKeys = true }

    @AfterEach
    fun cleanup() {
        AskQuestionsTool.showSimpleQuestionCallback = null
        AskQuestionsTool.showQuestionsCallback = null
        AskQuestionsTool.pendingQuestions = null
    }

    // ── Exact replicas of the enrichment helpers in AgentController ──────────────
    // Kept in sync deliberately — a mismatch here means the controller changed and
    // these tests need to be updated.

    private enum class TestQuestionMode { SIMPLE, WIZARD }
    private data class TestLiveQuestions(val mode: TestQuestionMode, val questions: List<Question>)

    private fun buildEnrichedPayload(
        live: TestLiveQuestions,
        collectedAnswers: Map<String, String>
    ): String {
        return when (live.mode) {
            TestQuestionMode.SIMPLE -> {
                val q = live.questions.firstOrNull()
                val idsJson = collectedAnswers["q1"] ?: "[]"
                val selectedIds = try {
                    lenientJson.decodeFromString<List<String>>(idsJson)
                } catch (_: Exception) { emptyList() }
                val labels = selectedIds.map { id ->
                    q?.options?.find { it.id == id }?.label ?: id
                }
                labels.joinToString(", ")
            }
            TestQuestionMode.WIZARD -> {
                buildString {
                    append("{")
                    var first = true
                    for (q in live.questions) {
                        val idsJson = collectedAnswers[q.id] ?: "[]"
                        val selectedIds = try {
                            lenientJson.decodeFromString<List<String>>(idsJson)
                        } catch (_: Exception) { emptyList() }
                        if (!first) append(",")
                        first = false
                        append(JsEscape.toJsonString(q.id))
                        append(":{\"question\":")
                        append(JsEscape.toJsonString(q.question))
                        append(",\"selected\":[")
                        var firstOpt = true
                        for (id in selectedIds) {
                            val label = q.options.find { it.id == id }?.label ?: id
                            if (!firstOpt) append(",")
                            firstOpt = false
                            append("{\"id\":")
                            append(JsEscape.toJsonString(id))
                            append(",\"label\":")
                            append(JsEscape.toJsonString(label))
                            append("}")
                        }
                        append("]}")
                    }
                    append("}")
                }
            }
        }
    }

    /** Replicates the wizard JSON AgentController builds for simple questions. */
    private fun buildSimpleWizardJson(question: String, options: List<String>): String = buildString {
        append("""{"questions":[{"id":"q1","question":""")
        append(JsEscape.toJsonString(question))
        append(""","type":"single","options":[""")
        options.forEachIndexed { i, opt ->
            if (i > 0) append(",")
            append("""{"id":"o${i + 1}","label":""")
            append(JsEscape.toJsonString(opt))
            append("}")
        }
        append("]}]}")
    }

    /** Parse wizard JSON exactly as AgentController does to produce a LiveQuestions. */
    private fun parseSimpleLiveQuestions(wizardJson: String): TestLiveQuestions? {
        return try {
            val root = lenientJson.parseToJsonElement(wizardJson).jsonObject
            val questionsEl = root["questions"] ?: return null
            val parsed = lenientJson.decodeFromJsonElement<List<Question>>(questionsEl)
            TestLiveQuestions(TestQuestionMode.SIMPLE, parsed)
        } catch (_: Exception) { null }
    }

    private fun parseWizardLiveQuestions(questionsJson: String): TestLiveQuestions? {
        return try {
            val root = lenientJson.parseToJsonElement(questionsJson).jsonObject
            val questionsEl = root["questions"] ?: return null
            val parsed = lenientJson.decodeFromJsonElement<List<Question>>(questionsEl)
            TestLiveQuestions(TestQuestionMode.WIZARD, parsed)
        } catch (_: Exception) { null }
    }

    // ════════════════════════════════════════════════════════
    //  Unit tests for the enrichment helper logic
    // ════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Simple mode id-to-label resolution")
    inner class SimpleModeEnrichment {

        @Test
        fun `single selected option resolves to its label`() {
            val wizardJson = buildSimpleWizardJson("Which DB?", listOf("PostgreSQL", "MySQL", "SQLite"))
            val live = parseSimpleLiveQuestions(wizardJson)!!
            val answers = mapOf("q1" to """["o1"]""")

            val result = buildEnrichedPayload(live, answers)

            assertEquals("PostgreSQL", result,
                "Simple mode must resolve o1 to PostgreSQL, not send the raw id")
        }

        @Test
        fun `multi-select resolves all selected options joined by comma-space`() {
            val wizardJson = buildSimpleWizardJson("Which features?", listOf("Auth", "Caching", "Search"))
            val live = parseSimpleLiveQuestions(wizardJson)!!
            val answers = mapOf("q1" to """["o1","o3"]""")

            val result = buildEnrichedPayload(live, answers)

            assertEquals("Auth, Search", result,
                "Multi-select must join resolved labels with ', '")
        }

        @Test
        fun `unknown option id falls back to the id itself`() {
            val wizardJson = buildSimpleWizardJson("Pick one", listOf("Option A", "Option B"))
            val live = parseSimpleLiveQuestions(wizardJson)!!
            val answers = mapOf("q1" to """["oXYZ"]""")

            val result = buildEnrichedPayload(live, answers)

            assertEquals("oXYZ", result,
                "Unknown id must fall back to the raw id string (no crash, no empty string)")
        }

        @Test
        fun `empty selection produces empty string`() {
            val wizardJson = buildSimpleWizardJson("Continue?", listOf("Yes", "No"))
            val live = parseSimpleLiveQuestions(wizardJson)!!
            val answers = mapOf("q1" to """[]""")

            val result = buildEnrichedPayload(live, answers)

            assertEquals("", result)
        }

        @Test
        fun `option label with special characters survives round-trip`() {
            val label = "She said \"hello\" and it's fine"
            val wizardJson = buildSimpleWizardJson("Pick label", listOf(label, "Other"))
            val live = parseSimpleLiveQuestions(wizardJson)!!
            val answers = mapOf("q1" to """["o1"]""")

            val result = buildEnrichedPayload(live, answers)

            assertEquals(label, result,
                "Special characters in option labels must survive the JSON parse to resolve round-trip")
        }
    }

    @Nested
    @DisplayName("Wizard mode enriched JSON with question text and selected labels")
    inner class WizardModeEnrichment {

        @Test
        fun `single question single answer produces correct enriched JSON`() {
            val questionsJson = """{"questions":[{"id":"q1","question":"Which database?","type":"single","options":[
                {"id":"o1","label":"PostgreSQL"},{"id":"o2","label":"MySQL"}
            ]}]}"""
            val live = parseWizardLiveQuestions(questionsJson)!!
            val answers = mapOf("q1" to """["o1"]""")

            val result = buildEnrichedPayload(live, answers)

            val parsed = lenientJson.parseToJsonElement(result).jsonObject
            val q1 = parsed["q1"]!!.jsonObject
            assertEquals("Which database?", q1["question"]!!.jsonPrimitive.content)
            val q1Selected = q1["selected"]!!.toString()
            assertTrue(q1Selected.contains("PostgreSQL"), "Enriched JSON must contain the label PostgreSQL")
            assertTrue(q1Selected.contains("o1"), "Enriched JSON must contain the option id o1")
            assertFalse(result.contains("MySQL"), "Unselected option label must not appear")
        }

        @Test
        fun `two questions two answers both enriched`() {
            val questionsJson = """{
                "questions":[
                    {"id":"q1","question":"Which database?","type":"single","options":[
                        {"id":"o1","label":"PostgreSQL"},{"id":"o2","label":"MySQL"}
                    ]},
                    {"id":"q2","question":"Which ORM?","type":"multiple","options":[
                        {"id":"o1","label":"Prisma"},{"id":"o2","label":"TypeORM"},{"id":"o3","label":"Sequelize"}
                    ]}
                ]
            }"""
            val live = parseWizardLiveQuestions(questionsJson)!!
            val answers = mapOf(
                "q1" to """["o1"]""",
                "q2" to """["o2","o3"]"""
            )

            val result = buildEnrichedPayload(live, answers)

            val parsed = lenientJson.parseToJsonElement(result).jsonObject
            assertTrue(parsed.containsKey("q1"), "q1 must be in the enriched JSON")
            assertTrue(parsed.containsKey("q2"), "q2 must be in the enriched JSON")

            val q1 = parsed["q1"]!!.jsonObject
            assertEquals("Which database?", q1["question"]!!.jsonPrimitive.content)

            val q2 = parsed["q2"]!!.jsonObject
            assertEquals("Which ORM?", q2["question"]!!.jsonPrimitive.content)
            val q2Selected = q2["selected"]!!.toString()
            assertTrue(q2Selected.contains("TypeORM"), "TypeORM must appear in q2 selected")
            assertTrue(q2Selected.contains("Sequelize"), "Sequelize must appear in q2 selected")
            assertFalse(q2Selected.contains("Prisma"), "Unselected Prisma must not appear in q2 selected")
        }

        @Test
        fun `unanswered question gets empty selected array`() {
            val questionsJson = """{
                "questions":[
                    {"id":"q1","question":"DB?","type":"single","options":[
                        {"id":"o1","label":"Postgres"}
                    ]}
                ]
            }"""
            val live = parseWizardLiveQuestions(questionsJson)!!
            val answers = emptyMap<String, String>()

            val result = buildEnrichedPayload(live, answers)

            val parsed = lenientJson.parseToJsonElement(result).jsonObject
            val q1 = parsed["q1"]!!.jsonObject
            assertEquals("[]", q1["selected"]!!.toString(),
                "Unanswered question must produce empty selected array")
        }

        @Test
        fun `wizard enriched JSON is valid JSON`() {
            val questionsJson = """{
                "questions":[
                    {"id":"q1","question":"What's the plan?","type":"single","options":[
                        {"id":"o1","label":"It's a refactor"},
                        {"id":"o2","label":"Don't change"}
                    ]}
                ]
            }"""
            val live = parseWizardLiveQuestions(questionsJson)!!
            val answers = mapOf("q1" to """["o1"]""")

            val result = buildEnrichedPayload(live, answers)

            assertDoesNotThrow { lenientJson.parseToJsonElement(result) }
            assertTrue(result.contains("It's a refactor"),
                "Single-quoted label must appear verbatim in output")
        }
    }

    // ════════════════════════════════════════════════════════
    //  End-to-end pipeline tests via AskQuestionsTool callbacks
    //  (simulates what AgentController does with the static fields)
    // ════════════════════════════════════════════════════════

    @Nested
    @DisplayName("End-to-end pipeline via callback wiring")
    inner class EndToEndPipeline {

        /**
         * Simulates the full simple-mode path AgentController wires:
         *   1. showSimpleQuestionCallback fires -> build wizardJson -> parse -> liveQuestions cached
         *   2. onSubmitted -> build enriched payload -> resolveQuestions(labels)
         *   3. executeSimple wraps the answer in <answer>...</answer> tags
         */
        @Test
        fun `simple mode pendingQuestions completed with label text not raw id`() = runTest {
            val tool = AskQuestionsTool()

            // Track what the enriched payload is
            val resolved = CompletableDeferred<String>()

            // Simulate AgentController's simple-mode wiring
            AskQuestionsTool.showSimpleQuestionCallback = { question, optionsJson ->
                val options = if (!optionsJson.isNullOrBlank()) {
                    try { lenientJson.decodeFromString<List<String>>(optionsJson) }
                    catch (_: Exception) { emptyList() }
                } else emptyList()

                if (options.isNotEmpty()) {
                    val wizardJson = buildSimpleWizardJson(question, options)
                    val live = parseSimpleLiveQuestions(wizardJson)!!

                    // Simulate user selecting o1 ("Option A"), then submit
                    val collectedAnswers = mapOf("q1" to """["o1"]""")
                    val enriched = buildEnrichedPayload(live, collectedAnswers)

                    AskQuestionsTool.resolveQuestions(enriched)
                    resolved.complete(enriched)
                }
            }

            val job = launch {
                tool.execute(
                    buildJsonObject {
                        put("question", "Which option?")
                        put("options", """["Option A","Option B","Option C"]""")
                    },
                    project
                )
            }

            kotlinx.coroutines.yield()

            val enrichedPayload = resolved.await()

            assertEquals("Option A", enrichedPayload,
                "Simple mode must resolve o1 to Option A, not send raw id")

            job.join()
        }

        /**
         * Simulates the full wizard-mode path AgentController wires:
         *   1. showQuestionsCallback fires -> parse questionsJson -> liveQuestions cached
         *   2. onSubmitted -> build enriched JSON -> resolveQuestions(enrichedJson)
         *   3. executeWizard prepends "User answered the questions:\n"
         */
        @Test
        fun `wizard mode pendingQuestions completed with enriched JSON containing question text and labels`() = runTest {
            val tool = AskQuestionsTool()

            val questionsPayload = """[
                {"id":"q1","question":"Which database?","type":"single","options":[
                    {"id":"o1","label":"PostgreSQL"},
                    {"id":"o2","label":"MySQL"}
                ]},
                {"id":"q2","question":"Which ORM?","type":"multiple","options":[
                    {"id":"o1","label":"Prisma"},
                    {"id":"o2","label":"TypeORM"}
                ]}
            ]"""

            val resolved = CompletableDeferred<String>()

            // Simulate AgentController's wizard-mode wiring
            AskQuestionsTool.showQuestionsCallback = { questionsJson ->
                val live = parseWizardLiveQuestions(questionsJson)!!

                // Simulate user answering q1 -> o1 (PostgreSQL), q2 -> o2 (TypeORM)
                val collectedAnswers = mapOf(
                    "q1" to """["o1"]""",
                    "q2" to """["o2"]"""
                )
                val enriched = buildEnrichedPayload(live, collectedAnswers)

                AskQuestionsTool.resolveQuestions(enriched)
                resolved.complete(enriched)
            }

            val job = launch {
                tool.execute(
                    buildJsonObject {
                        put("questions", questionsPayload)
                    },
                    project
                )
            }

            kotlinx.coroutines.yield()

            val enrichedPayload = resolved.await()

            val parsed = lenientJson.parseToJsonElement(enrichedPayload).jsonObject

            // q1 must contain question text + PostgreSQL label
            val q1 = parsed["q1"]!!.jsonObject
            assertEquals("Which database?", q1["question"]!!.jsonPrimitive.content,
                "q1 must contain the original question text, not just ids")
            val q1Selected = q1["selected"]!!.toString()
            assertTrue(q1Selected.contains("PostgreSQL"),
                "q1 selected must contain the label PostgreSQL, not just o1")
            assertFalse(q1Selected.contains("MySQL"),
                "q1 selected must NOT contain unselected MySQL")

            // q2 must contain question text + TypeORM label
            val q2 = parsed["q2"]!!.jsonObject
            assertEquals("Which ORM?", q2["question"]!!.jsonPrimitive.content,
                "q2 must contain the original question text")
            val q2Selected = q2["selected"]!!.toString()
            assertTrue(q2Selected.contains("TypeORM"),
                "q2 selected must contain the label TypeORM, not just o2")
            assertFalse(q2Selected.contains("Prisma"),
                "q2 selected must NOT contain unselected Prisma")

            job.join()
        }

        @Test
        fun `fallback path null liveQuestions produces raw joinToString format`() {
            // When liveQuestions is null (unexpected path), controller falls back to:
            // collectedAnswers.entries.joinToString(",", "{", "}") { "\"$qid\":$opts" }
            val collectedAnswers = mapOf(
                "q1" to """["o1"]""",
                "q2" to """["o2","o3"]"""
            )
            val fallback = collectedAnswers.entries.joinToString(",", "{", "}") { (qid, opts) ->
                "\"$qid\":$opts"
            }

            assertEquals("""{"q1":["o1"],"q2":["o2","o3"]}""", fallback,
                "Fallback format must produce raw ids JSON (backward-compatible format)")
        }
    }
}
