package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.serialization.json.*

enum class TaskComplexity {
    SIMPLE, COMPLEX
}

/**
 * Classifies tasks as SIMPLE or COMPLEX using a single LLM call with tool forcing.
 * SIMPLE tasks are fast-pathed (single file edit, clear instruction).
 * COMPLEX tasks go through the full orchestrator planning loop.
 *
 * Uses structured output via tool_choice forcing: the LLM is forced to call
 * the `classify_task` tool with a constrained `complexity` enum, eliminating
 * the need to parse free-text responses.
 */
object ComplexityRouter {

    private val CLASSIFY_TOOL = ToolDefinition(
        function = FunctionDefinition(
            name = "classify_task",
            description = "Classify a task as SIMPLE or COMPLEX based on its scope and requirements.",
            parameters = FunctionParameters(
                properties = mapOf(
                    "complexity" to ParameterProperty(
                        type = "string",
                        description = "SIMPLE: single file edit, clear instruction, rename/typo fix. COMPLEX: multi-file changes, analysis needed, ambiguous requirements, refactoring across modules.",
                        enumValues = listOf("SIMPLE", "COMPLEX")
                    )
                ),
                required = listOf("complexity")
            )
        )
    )

    /** Forced tool_choice object that requires the LLM to call classify_task. */
    private val FORCED_TOOL_CHOICE: JsonElement = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "classify_task")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Route a task description to SIMPLE or COMPLEX classification.
     *
     * @param taskDescription The task to classify
     * @param brain The LLM brain to use for classification
     * @return TaskComplexity.SIMPLE or TaskComplexity.COMPLEX
     */
    suspend fun route(taskDescription: String, brain: LlmBrain): TaskComplexity {
        val messages = listOf(
            ChatMessage(role = "system", content = "Classify the following task."),
            ChatMessage(role = "user", content = taskDescription)
        )

        val result = brain.chat(
            messages = messages,
            tools = listOf(CLASSIFY_TOOL),
            toolChoice = FORCED_TOOL_CHOICE
        )

        return when (result) {
            is ApiResult.Success -> {
                val toolCall = result.data.choices.firstOrNull()?.message?.toolCalls?.firstOrNull()
                if (toolCall != null) {
                    try {
                        val args = json.parseToJsonElement(toolCall.function.arguments).jsonObject
                        val complexity = args["complexity"]?.jsonPrimitive?.content
                        if (complexity == "SIMPLE") TaskComplexity.SIMPLE else TaskComplexity.COMPLEX
                    } catch (_: Exception) {
                        TaskComplexity.COMPLEX
                    }
                } else {
                    // No tool call in response — default to COMPLEX (safer)
                    TaskComplexity.COMPLEX
                }
            }
            is ApiResult.Error -> {
                // If we can't classify, default to COMPLEX (safer)
                TaskComplexity.COMPLEX
            }
        }
    }
}
