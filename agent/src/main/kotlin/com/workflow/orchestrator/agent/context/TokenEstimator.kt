package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Estimates token count using character-based heuristic.
 * OpenAI/Anthropic models average ~4 characters per token for English text,
 * ~3.5 for code. We use 3.5 as a conservative estimate for code-heavy context.
 */
object TokenEstimator {
    private const val TOKENS_PER_CHAR = 1.0 / 3.5
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun estimate(text: String): Int = (text.length * TOKENS_PER_CHAR).toInt() + 1

    fun estimate(messages: List<com.workflow.orchestrator.agent.api.dto.ChatMessage>): Int {
        return messages.sumOf { msg ->
            val contentTokens = estimate(msg.content ?: "")
            val toolCallTokens = msg.toolCalls?.sumOf { tc ->
                estimate(tc.function.name) + estimate(tc.function.arguments)
            } ?: 0
            contentTokens + toolCallTokens + 4 // 4 tokens overhead per message (role, separators)
        }
    }

    /**
     * Estimate tokens consumed by tool definitions when sent to the API.
     * Tool definitions are serialized as JSON in the request, consuming input tokens.
     */
    fun estimateToolDefinitions(tools: List<ToolDefinition>): Int {
        if (tools.isEmpty()) return 0
        val serialized = json.encodeToString(tools)
        return estimate(serialized)
    }
}
