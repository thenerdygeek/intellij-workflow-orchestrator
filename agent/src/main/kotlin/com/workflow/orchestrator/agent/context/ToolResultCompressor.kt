package com.workflow.orchestrator.agent.context

/**
 * Compresses tool results before they enter conversation history.
 * Results under the threshold pass through. Larger results are truncated
 * with a summary header, preserving the most useful information.
 */
object ToolResultCompressor {

    private const val DEFAULT_MAX_TOKENS = 500
    private const val TRUNCATION_MARKER = "\n... [truncated — full result offloaded to cache]"

    /**
     * Compress a tool result to fit within the token budget.
     * @param content The raw tool result content
     * @param summary A pre-computed summary of the result
     * @param maxTokens Maximum tokens for the compressed result
     * @return Compressed content that fits within maxTokens
     */
    fun compress(content: String, summary: String, maxTokens: Int = DEFAULT_MAX_TOKENS): String {
        val contentTokens = TokenEstimator.estimate(content)
        if (contentTokens <= maxTokens) {
            return content
        }

        // For large results, return summary + truncated head of content
        val summaryLine = "Summary: $summary"
        val summaryTokens = TokenEstimator.estimate(summaryLine) + TokenEstimator.estimate(TRUNCATION_MARKER)
        val remainingTokens = (maxTokens - summaryTokens).coerceAtLeast(50)

        // Estimate chars from remaining tokens (3.5 chars per token)
        val maxChars = (remainingTokens * 3.5).toInt()
        val truncatedContent = if (content.length > maxChars) {
            content.take(maxChars)
        } else {
            content
        }

        return "$summaryLine\n$truncatedContent$TRUNCATION_MARKER"
    }
}
