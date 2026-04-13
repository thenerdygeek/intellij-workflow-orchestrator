package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.util.JsEscape
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class Question(
    val id: String,
    val question: String,
    val type: String,
    val options: List<QuestionOption>
)

@Serializable
data class QuestionOption(
    val id: String,
    val label: String,
    val description: String = ""
)

/**
 * Unified question tool — handles both simple single questions and multi-question wizards.
 *
 * Faithful port of Cline's ask_followup_question with extension for structured multi-question flows.
 *
 * **Simple mode** (like Cline): pass `question` string + optional `options` array.
 * The question is shown in the chat, the loop blocks waiting for the user's typed answer.
 *
 * **Wizard mode** (our extension): pass `questions` JSON array with structured options.
 * A multi-step wizard UI is shown in the IDE. Each question has a type (single/multiple)
 * and a list of options.
 *
 * The LLM should use simple mode for most questions. Wizard mode is for structured
 * decision trees (e.g. "Pick a database, then pick a schema, then pick a migration strategy").
 */
class AskQuestionsTool : AgentTool {
    override val name = "ask_followup_question"
    override val description = "Ask the user a question to gather additional information needed to complete the task. " +
        "Use this when you encounter ambiguities, need clarification, or require more details to proceed effectively. " +
        "Use judiciously — prefer using available tools to find information over asking the user.\n\n" +
        "Two modes:\n" +
        "- **Simple** (most common): pass 'question' with optional 'options' array. User sees the question in chat and types an answer.\n" +
        "- **Wizard** (structured): pass 'questions' JSON array for multi-step structured questions with options."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "question" to ParameterProperty(
                type = "string",
                description = "A clear, specific question to ask the user. This is the simple mode — the question appears in the chat and the user types their answer."
            ),
            "options" to ParameterProperty(
                type = "string",
                description = "Optional JSON array of 2-5 string options for the user to choose from, e.g. [\"Option 1\", \"Option 2\"]. " +
                    "Saves the user from typing. Only used with the 'question' parameter (simple mode)."
            ),
            "questions" to ParameterProperty(
                type = "string",
                description = "JSON array of structured questions for the wizard mode. Only use this for complex multi-step decisions. " +
                    "Format: [{\"id\":\"q1\",\"question\":\"...\",\"type\":\"single|multiple\",\"options\":[{\"id\":\"o1\",\"label\":\"...\"}]}]"
            ),
            "title" to ParameterProperty(
                type = "string",
                description = "Optional title for the question wizard (only used with 'questions' parameter)."
            )
        ),
        required = emptyList() // Either 'question' or 'questions' required, validated in execute
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val QUESTION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

        /**
         * Grace period after the callback fires before we assume the UI failed to render.
         * If the deferred isn't resolved by the user AND the UI render confirmation hasn't
         * arrived within this window, we log a warning. The tool still waits for the full
         * [QUESTION_TIMEOUT_MS], but this shorter check enables early diagnostics.
         *
         * Set to 10s — enough for EDT scheduling + JCEF round-trip, short enough to detect
         * a stuck UI before the user gives up.
         */
        private const val UI_RENDER_GRACE_MS = 10_000L

        /** Callback to show the question wizard in the dashboard UI (wizard mode). */
        var showQuestionsCallback: ((String) -> Unit)? = null

        /** Callback to show a simple question in the chat UI (simple mode).
         *  Receives: questionText, optionsJson (nullable). */
        var showSimpleQuestionCallback: ((String, String?) -> Unit)? = null

        /** Deferred result that blocks tool execution until the user answers. */
        @Volatile
        var pendingQuestions: CompletableDeferred<String>? = null

        /**
         * Set to true by the UI layer (JCEF bridge round-trip) when the question
         * has been successfully rendered. Checked by [executeSimple]/[executeWizard]
         * after [UI_RENDER_GRACE_MS] to detect silent UI failures.
         */
        @Volatile
        var uiRenderConfirmed: Boolean = false

        /** Resolve the pending question(s) with the user's answer. */
        fun resolveQuestions(answersJson: String) {
            pendingQuestions?.complete(answersJson)
        }

        /** Cancel the pending question(s) (user dismissed). */
        fun cancelQuestions() {
            pendingQuestions?.complete("""{"cancelled":true}""")
        }
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val simpleQuestion = params["question"]?.jsonPrimitive?.content
        val questionsJson = params["questions"]?.let { element ->
            // Accept both string-encoded JSON and raw JSON arrays
            try { element.jsonPrimitive.content } catch (_: Exception) { element.toString() }
        }

        // Must provide either 'question' (simple) or 'questions' (wizard)
        if (simpleQuestion == null && questionsJson == null) {
            return ToolResult(
                "Error: Either 'question' (simple mode) or 'questions' (wizard mode) parameter is required.",
                "Error: missing question", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        // ── Simple mode: single question, user types answer ──
        if (simpleQuestion != null) {
            // Extract options — accept both string-encoded JSON and raw JSON arrays
            val optionsStr = params["options"]?.let { element ->
                try { element.jsonPrimitive.content } catch (_: Exception) { element.toString() }
            }
            return executeSimple(simpleQuestion, optionsStr)
        }

        // ── Wizard mode: structured multi-question wizard ──
        return executeWizard(questionsJson!!, params["title"]?.jsonPrimitive?.content)
    }

    /**
     * Simple mode — faithful port of Cline's ask_followup_question.
     * Shows question in chat, blocks for user's typed answer, returns it wrapped in <answer> tags.
     */
    private suspend fun executeSimple(question: String, optionsJson: String?): ToolResult {
        val deferred = CompletableDeferred<String>()
        pendingQuestions = deferred
        uiRenderConfirmed = false

        val (options, parseWarning) = parseSimpleOptions(optionsJson)

        // Show via simple question callback (chat-based) or wizard fallback
        val simpleCallback = showSimpleQuestionCallback
        val wizardCallback = showQuestionsCallback
        if (simpleCallback != null) {
            val reserializedOptions = if (options.isNotEmpty()) {
                "[${options.joinToString(",") { JsEscape.toJsonString(it) }}]"
            } else null
            simpleCallback(question, reserializedOptions)
        } else if (wizardCallback != null) {
            // Fallback: wrap as a single-question wizard
            val wrappedJson = buildString {
                append("""{"questions":[{"id":"q1","question":""")
                append(JsEscape.toJsonString(question))
                append(""","type":"single","options":[""")
                options.forEachIndexed { i, opt ->
                    if (i > 0) append(",")
                    append("""{"id":"o${i+1}","label":""")
                    append(JsEscape.toJsonString(opt))
                    append("}")
                }
                append("]}]}")
            }
            wizardCallback(wrappedJson)
        } else {
            pendingQuestions = null
            return ToolResult(
                "Error: Question UI not available. Ask questions as plain text instead.",
                "ask_followup_question: UI not available",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        // UI render watchdog: if the JCEF bridge hasn't confirmed the render within
        // UI_RENDER_GRACE_MS, assume the UI is stuck and auto-resolve the deferred
        // so the agent loop doesn't block for 5 minutes. Uses a daemon timer thread
        // to avoid structured concurrency issues (we can't launch a coroutine that
        // outlives the withTimeoutOrNull scope without completing it).
        val watchdogTimer = java.util.Timer("ask-question-watchdog", true)
        watchdogTimer.schedule(object : java.util.TimerTask() {
            override fun run() {
                if (!deferred.isCompleted && !uiRenderConfirmed) {
                    deferred.complete("[UI_RENDER_FAILED]")
                }
            }
        }, UI_RENDER_GRACE_MS)

        val answer = withTimeoutOrNull(QUESTION_TIMEOUT_MS) { deferred.await() }
        watchdogTimer.cancel()
        pendingQuestions = null

        if (answer == null) {
            return ToolResult(
                "User did not respond within 5 minutes.",
                "ask_followup_question: timeout",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        if (answer.contains("\"cancelled\":true")) {
            return ToolResult(
                "User dismissed the question.",
                "ask_followup_question: cancelled",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = false
            )
        }

        if (answer == "[UI_RENDER_FAILED]") {
            return ToolResult(
                "Error: The question UI failed to render (JCEF bridge timeout). " +
                    "The question was: \"$question\". " +
                    "Ask the question as plain text in your response instead of using this tool.",
                "ask_followup_question: UI render failed",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        // Match Cline's response format: <answer>text</answer>
        val content = buildString {
            if (parseWarning != null) appendLine(parseWarning).appendLine()
            appendLine("<answer>")
            appendLine(answer)
            appendLine("</answer>")
        }
        return ToolResult(
            content = content,
            summary = "User answered: ${answer.take(200)}",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    /**
     * Wizard mode — structured multi-question flow with options UI.
     */
    private suspend fun executeWizard(questionsJson: String, title: String?): ToolResult {
        // Validate
        val validationError = validateQuestions(questionsJson)
        if (validationError != null) return validationError

        val callback = showQuestionsCallback
        if (callback == null) {
            return ToolResult(
                "Error: Question wizard UI not available.",
                "ask_followup_question: wizard UI not available",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val wizardJson = if (title != null) {
            """{"title":${JsEscape.toJsonString(title)},"questions":$questionsJson}"""
        } else {
            """{"questions":$questionsJson}"""
        }

        val deferred = CompletableDeferred<String>()
        pendingQuestions = deferred
        uiRenderConfirmed = false
        callback(wizardJson)

        // UI render watchdog (same pattern as executeSimple)
        val watchdogTimer = java.util.Timer("ask-question-wizard-watchdog", true)
        watchdogTimer.schedule(object : java.util.TimerTask() {
            override fun run() {
                if (!deferred.isCompleted && !uiRenderConfirmed) {
                    deferred.complete("[UI_RENDER_FAILED]")
                }
            }
        }, UI_RENDER_GRACE_MS)

        val answersJson = withTimeoutOrNull(QUESTION_TIMEOUT_MS) { deferred.await() }
        watchdogTimer.cancel()
        pendingQuestions = null

        if (answersJson == null) {
            return ToolResult(
                "User did not respond within 5 minutes.",
                "ask_followup_question: wizard timeout",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        if (answersJson.contains("\"cancelled\":true")) {
            return ToolResult(
                "User cancelled the question wizard.",
                "ask_followup_question: wizard cancelled",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = false
            )
        }

        if (answersJson == "[UI_RENDER_FAILED]") {
            return ToolResult(
                "Error: The question wizard UI failed to render (JCEF bridge timeout). " +
                    "Ask the questions as plain text in your response instead of using this tool.",
                "ask_followup_question: wizard UI render failed",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val content = "User answered the questions:\n$answersJson"
        return ToolResult(
            content = content,
            summary = "ask_followup_question: user responded",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    // ── Validation helpers ──

    fun validateQuestions(questionsJson: String): ToolResult? {
        val questions = try {
            json.decodeFromString<List<Question>>(questionsJson)
        } catch (e: Exception) {
            return ToolResult(
                "Error: invalid questions JSON: ${e.message}. Expected format: [{\"id\":\"q1\",\"question\":\"...\",\"type\":\"single\",\"options\":[{\"id\":\"o1\",\"label\":\"...\"}]}]",
                "Error: invalid JSON", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        if (questions.isEmpty()) {
            return ToolResult("Error: questions array must be non-empty", "Error: empty questions", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
        if (questions.size > 20) {
            return ToolResult("Error: max 20 questions (got ${questions.size})", "Error: too many questions", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val seenQuestionIds = mutableSetOf<String>()
        for (q in questions) {
            if (!seenQuestionIds.add(q.id)) return ToolResult("Error: Duplicate question ID '${q.id}'", "Error: duplicate ID", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            if (q.type != "single" && q.type != "multiple") return ToolResult("Error: question '${q.id}' has invalid type '${q.type}'", "Error: invalid type", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            if (q.options.isEmpty()) return ToolResult("Error: question '${q.id}' must have options", "Error: no options", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            if (q.options.size > 10) return ToolResult("Error: question '${q.id}' has too many options (${q.options.size})", "Error: too many options", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            val seenOpts = mutableSetOf<String>()
            for (opt in q.options) {
                if (!seenOpts.add(opt.id)) return ToolResult("Error: Duplicate option ID '${opt.id}' in '${q.id}'", "Error: duplicate option", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }
        }
        return null
    }

    /**
     * Parse simple options from JSON string. Returns null on parse failure
     * so the caller can report the error to the LLM instead of silently
     * falling back to no-options mode.
     */
    private fun parseSimpleOptions(optionsJson: String?): Pair<List<String>, String?> {
        if (optionsJson.isNullOrBlank()) return emptyList<String>() to null
        return try {
            json.decodeFromString<List<String>>(optionsJson) to null
        } catch (e: Exception) {
            emptyList<String>() to "Warning: could not parse 'options' as JSON string array: ${e.message}. " +
                "Expected format: [\"Option A\", \"Option B\", \"Option C\"]. " +
                "Options were ignored — showing question as plain text."
        }
    }

}
