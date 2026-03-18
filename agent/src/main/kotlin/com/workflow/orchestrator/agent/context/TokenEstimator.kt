package com.workflow.orchestrator.agent.context

/**
 * Estimates token count using character-based heuristic.
 * OpenAI/Anthropic models average ~4 characters per token for English text,
 * ~3.5 for code. We use 3.5 as a conservative estimate for code-heavy context.
 */
object TokenEstimator {
    private const val TOKENS_PER_CHAR = 1.0 / 3.5

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
}
