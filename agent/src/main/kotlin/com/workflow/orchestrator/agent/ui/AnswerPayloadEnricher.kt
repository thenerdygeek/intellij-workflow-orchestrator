package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.tools.builtin.Question
import com.workflow.orchestrator.agent.util.JsEscape
import kotlinx.serialization.json.Json

/**
 * Pure enrichment of `ask_followup_question` answers into the payload the tool result carries
 * (Phase 3 cut — extracted from `AgentController.buildEnrichedAnswerPayload`). Resolves selected
 * option ids → human labels and preserves the distinction the LLM relies on between "user actively
 * skipped" and "no options chosen". Dependency-free (operates on the public `Question` model), so
 * the SKIPPED-sentinel + label-resolution logic is unit-testable without the JCEF UI.
 *
 * `collectedAnswers` maps a question id (or `"q1"` for SIMPLE) to a JSON array string of the
 * selected option ids, exactly as the webview reports them.
 */
object AnswerPayloadEnricher {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    private fun parseSelectedIds(idsJson: String?): List<String> =
        try {
            lenientJson.decodeFromString<List<String>>(idsJson ?: "[]")
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * SIMPLE mode: resolve the first question's selected ids → labels, joined with ", ". Returns the
     * `[SKIPPED]` sentinel when the question was skipped so the tool can emit a distinct
     * "User skipped this question." result instead of an empty answer. Unknown ids fall back to the
     * raw id.
     */
    fun buildSimple(
        questions: List<Question>,
        collectedAnswers: Map<String, String>,
        skippedIds: Set<String>,
    ): String {
        val q = questions.firstOrNull()
        if (q != null && q.id in skippedIds) return "[SKIPPED]"
        val selectedIds = parseSelectedIds(collectedAnswers["q1"])
        return selectedIds.joinToString(", ") { id ->
            q?.options?.find { it.id == id }?.label ?: id
        }
    }

    /**
     * WIZARD mode: enriched JSON keyed by question id. A skipped question emits `"skipped":true`
     * instead of `"selected":[]` so the LLM can tell "actively skipped" from "no options chosen".
     */
    fun buildWizard(
        questions: List<Question>,
        collectedAnswers: Map<String, String>,
        skippedIds: Set<String>,
    ): String = buildString {
        append("{")
        var first = true
        for (q in questions) {
            if (!first) append(",")
            first = false
            append(JsEscape.toJsonString(q.id))
            append(":{\"question\":")
            append(JsEscape.toJsonString(q.question))
            if (q.id in skippedIds) {
                append(",\"skipped\":true}")
                continue
            }
            val selectedIds = parseSelectedIds(collectedAnswers[q.id])
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
