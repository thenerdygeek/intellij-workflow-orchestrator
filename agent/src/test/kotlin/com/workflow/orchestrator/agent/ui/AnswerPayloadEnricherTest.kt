package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.tools.builtin.Question
import com.workflow.orchestrator.agent.tools.builtin.QuestionOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnswerPayloadEnricherTest {

    private fun q(
        id: String,
        question: String = "Pick?",
        options: List<QuestionOption> = emptyList(),
    ) = Question(id = id, question = question, type = "single", options = options)

    private fun opt(id: String, label: String) = QuestionOption(id = id, label = label)

    // ── SIMPLE ────────────────────────────────────────────────────────────────

    @Test
    fun `simple resolves selected ids to labels joined by comma`() {
        val question = q("q1", options = listOf(opt("a", "Apple"), opt("b", "Banana")))
        val result = AnswerPayloadEnricher.buildSimple(
            questions = listOf(question),
            collectedAnswers = mapOf("q1" to """["a","b"]"""),
            skippedIds = emptySet(),
        )
        assertEquals("Apple, Banana", result)
    }

    @Test
    fun `simple returns the SKIPPED sentinel when the question was skipped`() {
        val question = q("q1", options = listOf(opt("a", "Apple")))
        val result = AnswerPayloadEnricher.buildSimple(
            questions = listOf(question),
            collectedAnswers = mapOf("q1" to """["a"]"""),
            skippedIds = setOf("q1"),
        )
        assertEquals("[SKIPPED]", result)
    }

    @Test
    fun `simple falls back to the raw id when no matching option label exists`() {
        val question = q("q1", options = listOf(opt("a", "Apple")))
        val result = AnswerPayloadEnricher.buildSimple(
            questions = listOf(question),
            collectedAnswers = mapOf("q1" to """["a","zzz"]"""),
            skippedIds = emptySet(),
        )
        assertEquals("Apple, zzz", result)
    }

    @Test
    fun `simple returns empty string when there are no answers`() {
        val result = AnswerPayloadEnricher.buildSimple(
            questions = listOf(q("q1")),
            collectedAnswers = emptyMap(),
            skippedIds = emptySet(),
        )
        assertEquals("", result)
    }

    // ── WIZARD ────────────────────────────────────────────────────────────────

    @Test
    fun `wizard emits selected id-label pairs as enriched json`() {
        val question = q("q1", question = "Pick?", options = listOf(opt("a", "Apple")))
        val result = AnswerPayloadEnricher.buildWizard(
            questions = listOf(question),
            collectedAnswers = mapOf("q1" to """["a"]"""),
            skippedIds = emptySet(),
        )
        assertEquals("""{"q1":{"question":"Pick?","selected":[{"id":"a","label":"Apple"}]}}""", result)
    }

    @Test
    fun `wizard emits skipped true instead of selected for a skipped question`() {
        val question = q("q1", question = "Pick?", options = listOf(opt("a", "Apple")))
        val result = AnswerPayloadEnricher.buildWizard(
            questions = listOf(question),
            collectedAnswers = mapOf("q1" to """["a"]"""),
            skippedIds = setOf("q1"),
        )
        assertEquals("""{"q1":{"question":"Pick?","skipped":true}}""", result)
    }

    @Test
    fun `wizard joins multiple questions with a comma`() {
        val result = AnswerPayloadEnricher.buildWizard(
            questions = listOf(
                q("q1", question = "One?", options = listOf(opt("a", "A"))),
                q("q2", question = "Two?", options = listOf(opt("b", "B"))),
            ),
            collectedAnswers = mapOf("q1" to """["a"]""", "q2" to """["b"]"""),
            skippedIds = emptySet(),
        )
        assertEquals(
            """{"q1":{"question":"One?","selected":[{"id":"a","label":"A"}]},""" +
                """"q2":{"question":"Two?","selected":[{"id":"b","label":"B"}]}}""",
            result,
        )
    }
}
