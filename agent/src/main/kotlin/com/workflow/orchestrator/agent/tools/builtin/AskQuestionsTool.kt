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

class AskQuestionsTool : AgentTool {
    override val name = "ask_questions"
    override val description = "Ask the user a question to gather additional information needed to complete the task. This tool should be used when you encounter ambiguities, need clarification, or require more details to proceed effectively. It allows for interactive problem-solving by enabling direct communication with the user. Use this tool judiciously to maintain a balance between gathering necessary information and avoiding excessive back-and-forth. Questions are shown as an interactive wizard in the IDE with structured options. Each question has a type ('single' for radio buttons, 'multiple' for checkboxes) and a list of options for the user to choose from."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "title" to ParameterProperty(type = "string", description = "Optional title for the question wizard. Should be a clear, specific title that addresses the information you need."),
            "questions" to ParameterProperty(type = "string", description = "JSON array of questions. Each question should be clear and specific, addressing the information you need. Format: [{\"id\":\"q1\",\"question\":\"Your question?\",\"type\":\"single|multiple\",\"options\":[{\"id\":\"o1\",\"label\":\"Option\",\"description\":\"Optional detail\"}]}]. Provide 2-5 options per question where possible to save the user from typing.")
        ),
        required = listOf("questions")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val QUESTION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

        /**
         * Gap 11: Callback to show the question wizard in the dashboard UI.
         * Set by AgentController during wiring.
         */
        var showQuestionsCallback: ((String) -> Unit)? = null

        /**
         * Gap 11: Deferred result that blocks tool execution until the user answers.
         * Resolved by the dashboard's question submitted callback.
         */
        @Volatile
        var pendingQuestions: CompletableDeferred<String>? = null

        /**
         * Resolve the pending questions with the user's answers JSON.
         * Called from the dashboard's onQuestionsSubmitted callback.
         */
        fun resolveQuestions(answersJson: String) {
            pendingQuestions?.complete(answersJson)
        }

        /**
         * Cancel the pending questions (user dismissed the wizard).
         */
        fun cancelQuestions() {
            pendingQuestions?.complete("""{"cancelled":true}""")
        }
    }

    /**
     * Validates the questions parameter and returns a [ToolResult] error if invalid, or null if valid.
     * Exposed as internal for testing without needing a Project.
     */
    fun validateQuestions(params: JsonObject): ToolResult? {
        val questionsJson = params["questions"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'questions' parameter required",
                "Error: missing questions", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        val questions = try {
            json.decodeFromString<List<Question>>(questionsJson)
        } catch (e: Exception) {
            return ToolResult(
                "Error: invalid questions JSON: ${e.message}. Expected format: [{\"id\":\"q1\",\"question\":\"...\",\"type\":\"single\",\"options\":[{\"id\":\"o1\",\"label\":\"...\"}]}]",
                "Error: invalid JSON", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        if (questions.isEmpty()) {
            return ToolResult(
                "Error: questions array must contain at least 1 question",
                "Error: empty questions", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        if (questions.size > 20) {
            return ToolResult(
                "Error: questions array must contain at most 20 questions (got ${questions.size})",
                "Error: too many questions", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val seenQuestionIds = mutableSetOf<String>()
        for (question in questions) {
            if (!seenQuestionIds.add(question.id)) {
                return ToolResult(
                    "Error: Duplicate question ID '${question.id}'",
                    "Error: duplicate question ID", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
            }

            if (question.type != "single" && question.type != "multiple") {
                return ToolResult(
                    "Error: question '${question.id}' has invalid type '${question.type}'. Must be 'single' or 'multiple'",
                    "Error: invalid question type", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
            }

            if (question.options.isEmpty()) {
                return ToolResult(
                    "Error: question '${question.id}' must have at least 1 option",
                    "Error: no options", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
            }

            if (question.options.size > 10) {
                return ToolResult(
                    "Error: question '${question.id}' has ${question.options.size} options (max 10)",
                    "Error: too many options", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
            }

            val seenOptionIds = mutableSetOf<String>()
            for (option in question.options) {
                if (!seenOptionIds.add(option.id)) {
                    return ToolResult(
                        "Error: Duplicate option ID '${option.id}' in question '${question.id}'",
                        "Error: duplicate option ID", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }
            }
        }

        return null // valid
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // Validate parameters
        validateQuestions(params)?.let { return it }

        val questionsJson = params["questions"]?.jsonPrimitive?.content!!
        val title = params["title"]?.jsonPrimitive?.content

        // Gap 11: If the show callback is wired, display the question wizard and wait
        val callback = showQuestionsCallback
        if (callback != null) {
            // Build the full wizard JSON including title
            val escapedTitle = title?.replace("\\", "\\\\")?.replace("\"", "\\\"")
            val wizardJson = if (escapedTitle != null) {
                """{"title":"$escapedTitle","questions":$questionsJson}"""
            } else {
                """{"questions":$questionsJson}"""
            }

            // Create deferred to block until user answers
            val deferred = CompletableDeferred<String>()
            pendingQuestions = deferred

            // Show the question wizard in the UI
            callback(wizardJson)

            // Wait for user response with timeout
            val answersJson = withTimeoutOrNull(QUESTION_TIMEOUT_MS) { deferred.await() }
            pendingQuestions = null

            if (answersJson == null) {
                return ToolResult(
                    content = "User did not respond to questions within 5 minutes. Ask in plain text instead.",
                    summary = "ask_questions: timeout",
                    tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            // Check for cancellation
            if (answersJson.contains("\"cancelled\":true")) {
                return ToolResult(
                    content = "User cancelled the question wizard. You may ask questions as plain text in the chat.",
                    summary = "ask_questions: cancelled by user",
                    tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = false
                )
            }

            val content = "User answered the questions:\n$answersJson"
            return ToolResult(
                content = content,
                summary = "ask_questions: user responded",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }

        // Fallback: no UI callback wired
        return ToolResult(
            content = "Error: Question wizard UI not available. Ask questions as plain text instead.",
            summary = "ask_questions: UI not available",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }
}
