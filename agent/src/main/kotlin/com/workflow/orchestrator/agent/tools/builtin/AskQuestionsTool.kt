package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class AskQuestionsTool : AgentTool {
    override val name = "ask_questions"
    override val description = "Ask the user structured questions with predefined options. Use when you need user input to make decisions (e.g., choosing a framework, selecting features, confirming approaches). Questions are shown as an interactive wizard in the IDE. Each question has a type ('single' for radio buttons, 'multiple' for checkboxes) and a list of options."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "title" to ParameterProperty(type = "string", description = "Optional title for the question wizard"),
            "questions" to ParameterProperty(type = "string", description = "JSON array of questions. Each question: {\"id\":\"q1\",\"question\":\"Your question?\",\"type\":\"single|multiple\",\"options\":[{\"id\":\"o1\",\"label\":\"Option\",\"description\":\"Optional detail\"}]}")
        ),
        required = listOf("questions")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    private val json = Json { ignoreUnknownKeys = true }

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

        val title = params["title"]?.jsonPrimitive?.content
        val questionsJson = params["questions"]!!.jsonPrimitive.content
        val questions = json.decodeFromString<List<Question>>(questionsJson)
        val questionSet = QuestionSet(title = title, questions = questions)

        val questionManager = try {
            AgentService.getInstance(project).currentQuestionManager
        } catch (_: Exception) { null }

        if (questionManager == null) {
            return ToolResult(
                "Error: no active session for question management",
                "Error: no session", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        if (questionManager.currentQuestions != null) {
            return ToolResult(
                "Error: a question wizard is already active. Wait for it to complete before asking new questions.",
                "Error: questions already active", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val result = try {
            withTimeoutOrNull(600_000L) { // 10 minute timeout
                suspendCancellableCoroutine<QuestionResult> { cont ->
                    val future = questionManager.submitQuestions(questionSet)
                    cont.invokeOnCancellation { future.cancel(true) }
                    future.whenComplete { value, error ->
                        if (error != null) {
                            if (!cont.isCompleted) cont.resumeWithException(error)
                        } else {
                            if (!cont.isCompleted) cont.resume(value)
                        }
                    }
                }
            } ?: return ToolResult(
                "Question wizard timed out after 10 minutes. Please try again.",
                "Questions timeout", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } catch (e: CancellationException) {
            return ToolResult(
                "Question wizard was cancelled.",
                "Questions cancelled", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        if (result.cancelled) {
            return ToolResult(
                "User cancelled the question wizard",
                "Questions cancelled by user",
                ToolResult.ERROR_TOKEN_ESTIMATE
            )
        }

        // Format results as structured text
        val sb = StringBuilder()
        if (title != null) {
            sb.appendLine("## $title")
            sb.appendLine()
        }
        sb.appendLine("User responses:")
        sb.appendLine()

        for (question in questions) {
            val answer = result.answers[question.id]
            if (answer != null) {
                sb.appendLine("### ${question.question}")
                val selectedLabels = answer.selectedOptions.mapNotNull { optId ->
                    question.options.find { it.id == optId }?.label
                }
                if (selectedLabels.isNotEmpty()) {
                    for (label in selectedLabels) {
                        sb.appendLine("  - $label")
                    }
                }
                if (answer.chatMessage != null) {
                    sb.appendLine("  Note: ${answer.chatMessage}")
                }
                sb.appendLine()
            } else if (question.id in result.skipped) {
                sb.appendLine("### ${question.question}")
                sb.appendLine("  (skipped)")
                sb.appendLine()
            }
        }

        val content = sb.toString().trimEnd()
        return ToolResult(
            content = content,
            summary = "User answered ${result.answers.size}/${questions.size} questions",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
