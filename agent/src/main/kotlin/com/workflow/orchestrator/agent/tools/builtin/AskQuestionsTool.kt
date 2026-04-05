package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class QuestionSet(
    val title: String? = null,
    val questions: List<Question>
)

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

@Serializable
data class QuestionAnswer(
    val questionId: String,
    val selectedOptions: List<String>,
    val chatMessage: String? = null
)

@Serializable
data class QuestionResult(
    val answers: Map<String, QuestionAnswer> = emptyMap(),
    val skipped: List<String> = emptyList(),
    val cancelled: Boolean = false
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

        /** Callback to show the question wizard in the dashboard UI (wizard mode). */
        var showQuestionsCallback: ((String) -> Unit)? = null

        /** Callback to show a simple question in the chat UI (simple mode).
         *  Receives: questionText, optionsJson (nullable). */
        var showSimpleQuestionCallback: ((String, String?) -> Unit)? = null

        /** Deferred result that blocks tool execution until the user answers. */
        @Volatile
        var pendingQuestions: CompletableDeferred<String>? = null

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
        val questionsJson = params["questions"]?.jsonPrimitive?.content

        // Must provide either 'question' (simple) or 'questions' (wizard)
        if (simpleQuestion == null && questionsJson == null) {
            return ToolResult(
                "Error: Either 'question' (simple mode) or 'questions' (wizard mode) parameter is required.",
                "Error: missing question", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        // ── Simple mode: single question, user types answer ──
        if (simpleQuestion != null) {
            return executeSimple(simpleQuestion, params["options"]?.jsonPrimitive?.content)
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

        // Show via simple question callback (chat-based) or wizard fallback
        val simpleCallback = showSimpleQuestionCallback
        val wizardCallback = showQuestionsCallback
        if (simpleCallback != null) {
            simpleCallback(question, optionsJson)
        } else if (wizardCallback != null) {
            // Fallback: wrap as a single-question wizard
            val options = parseSimpleOptions(optionsJson)
            val wrappedJson = buildString {
                append("""{"questions":[{"id":"q1","question":""")
                append(escapeJsonString(question))
                append(""","type":"single","options":[""")
                options.forEachIndexed { i, opt ->
                    if (i > 0) append(",")
                    append("""{"id":"o${i+1}","label":""")
                    append(escapeJsonString(opt))
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

        val answer = withTimeoutOrNull(QUESTION_TIMEOUT_MS) { deferred.await() }
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

        // Match Cline's response format: <answer>text</answer>
        val content = "<answer>\n$answer\n</answer>"
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

        val escapedTitle = title?.replace("\\", "\\\\")?.replace("\"", "\\\"")
        val wizardJson = if (escapedTitle != null) {
            """{"title":"$escapedTitle","questions":$questionsJson}"""
        } else {
            """{"questions":$questionsJson}"""
        }

        val deferred = CompletableDeferred<String>()
        pendingQuestions = deferred
        callback(wizardJson)

        val answersJson = withTimeoutOrNull(QUESTION_TIMEOUT_MS) { deferred.await() }
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

    private fun parseSimpleOptions(optionsJson: String?): List<String> {
        if (optionsJson.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<String>>(optionsJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun escapeJsonString(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        return "\"$escaped\""
    }
}
