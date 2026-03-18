package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.core.model.ApiResult

enum class TaskComplexity {
    SIMPLE, COMPLEX
}

/**
 * Classifies tasks as SIMPLE or COMPLEX using a single LLM call.
 * SIMPLE tasks are fast-pathed (single file edit, clear instruction).
 * COMPLEX tasks go through the full orchestrator planning loop.
 */
object ComplexityRouter {

    private const val SYSTEM_PROMPT =
        "Classify the following task: SIMPLE (single file edit, clear instruction) " +
            "or COMPLEX (multi-file, analysis needed). Respond with one word."

    /**
     * Route a task description to SIMPLE or COMPLEX classification.
     *
     * @param taskDescription The task to classify
     * @param brain The LLM brain to use for classification
     * @return TaskComplexity.SIMPLE or TaskComplexity.COMPLEX
     */
    suspend fun route(taskDescription: String, brain: LlmBrain): TaskComplexity {
        val messages = listOf(
            ChatMessage(role = "system", content = SYSTEM_PROMPT),
            ChatMessage(role = "user", content = taskDescription)
        )

        val result = brain.chat(messages, maxTokens = 10)

        return when (result) {
            is ApiResult.Success -> {
                val response = result.data.choices.firstOrNull()?.message?.content?.trim()?.uppercase() ?: ""
                if (response.contains("SIMPLE")) {
                    TaskComplexity.SIMPLE
                } else {
                    // Default to COMPLEX if unclear — safer to do more analysis
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
