package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.core.model.ApiResult

/**
 * Adapter that wraps [LlmBrain] to implement [SummarizationClient].
 *
 * Uses a short max_tokens (500) for summarization calls to keep costs low.
 * Falls back to null on any error, letting the condenser use its fallback strategy.
 */
class LlmBrainSummarizationClient(private val brain: LlmBrain) : SummarizationClient {

    override suspend fun summarize(messages: List<ChatMessage>): String? {
        return try {
            when (val result = brain.chat(messages, null, 1500, null)) {
                is ApiResult.Success -> {
                    result.data.choices.firstOrNull()?.message?.content
                }
                is ApiResult.Error -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
