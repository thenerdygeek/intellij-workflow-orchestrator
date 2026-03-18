package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Manages the conversation history for a worker session.
 *
 * Implements two-threshold compression (Factory.ai pattern):
 * - T_max: When total tokens reach this threshold, compression triggers
 * - T_retained: After compression, total tokens are reduced to this level
 *
 * Anchored summaries: Only newly dropped messages are summarized.
 * Already-summarized spans are preserved as-is.
 *
 * When [brain] is provided, compression uses LLM-powered summarization for
 * higher-quality context retention. Otherwise, falls back to simple truncation.
 */
class ContextManager(
    private val maxInputTokens: Int = 150_000,
    private val brain: LlmBrain? = null,
    private val tMaxRatio: Double = 0.70,
    private val tRetainedRatio: Double = 0.40,
    private val toolResultMaxTokens: Int = 500,
    private val summarizer: (List<ChatMessage>) -> String = { messages ->
        // Default summarizer: extract key points from messages
        val content = messages.mapNotNull { it.content }.joinToString("\n")
        if (content.length > 500) {
            "Previous context summary: ${content.take(500)}..."
        } else {
            "Previous context summary: $content"
        }
    }
) {
    private val messages = mutableListOf<ChatMessage>()
    private val anchoredSummaries = mutableListOf<String>()
    private var totalTokens = 0

    private val tMax: Int get() = (maxInputTokens * tMaxRatio).toInt()
    private val tRetained: Int get() = (maxInputTokens * tRetainedRatio).toInt()

    /** Current token usage across all messages. */
    val currentTokens: Int get() = totalTokens

    /** Current message count. */
    val messageCount: Int get() = messages.size

    /** Get all messages including any summary prefixes. */
    fun getMessages(): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()

        // Prepend anchored summaries as a system message if any exist
        if (anchoredSummaries.isNotEmpty()) {
            result.add(ChatMessage(
                role = "system",
                content = anchoredSummaries.joinToString("\n\n")
            ))
        }

        result.addAll(messages)
        return result
    }

    /** Add a message to the conversation history. Triggers compression if budget exceeded. */
    fun addMessage(message: ChatMessage) {
        // Use the list-based estimator which correctly counts tool call tokens,
        // not just content (assistant messages with tool_calls have null content)
        val tokenCount = TokenEstimator.estimate(listOf(message))
        messages.add(message)
        totalTokens += tokenCount

        if (totalTokens > tMax) {
            compress()
        }
    }

    /** Add a tool result, compressing it first if it exceeds the tool result budget. */
    fun addToolResult(toolCallId: String, content: String, summary: String) {
        val compressed = ToolResultCompressor.compress(content, summary, toolResultMaxTokens)
        addMessage(ChatMessage(
            role = "tool",
            content = compressed,
            toolCallId = toolCallId
        ))
    }

    /** Add an assistant message (LLM response). */
    fun addAssistantMessage(message: ChatMessage) {
        addMessage(message)
    }

    /**
     * Compress the conversation to fit within T_retained.
     * Strategy: Summarize the oldest non-system messages and replace them
     * with a single summary message. Keep the most recent messages intact.
     */
    private fun compress() {
        if (messages.size <= 2) return // Nothing to compress

        // Find how many messages to drop to get below T_retained
        var tokensToRemove = totalTokens - tRetained
        val messagesToSummarize = mutableListOf<ChatMessage>()
        val indicesToRemove = mutableListOf<Int>()

        for (i in messages.indices) {
            if (tokensToRemove <= 0) break
            val msg = messages[i]
            // Never compress system messages
            if (msg.role == "system") continue

            val msgTokens = TokenEstimator.estimate(listOf(msg))
            messagesToSummarize.add(msg)
            indicesToRemove.add(i)
            tokensToRemove -= msgTokens
        }

        if (messagesToSummarize.isEmpty()) return

        // Create anchored summary of dropped messages.
        // Uses LLM-powered summarization when brain is available, otherwise falls back
        // to the default truncation summarizer.
        val summary = summarizeMessages(messagesToSummarize)
        anchoredSummaries.add(summary)

        // Remove compressed messages (reverse order to preserve indices)
        indicesToRemove.reversed().forEach { messages.removeAt(it) }

        // Recalculate total tokens
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    /**
     * Summarize messages using LLM when available, falling back to truncation.
     *
     * NOTE: Uses runBlocking on Dispatchers.IO because this is called from compress(),
     * which is called synchronously from addMessage(). Compression runs infrequently
     * (only when approaching budget threshold) so the blocking call is acceptable.
     * The IO dispatcher ensures we don't block the EDT.
     */
    private fun summarizeMessages(messagesToSummarize: List<ChatMessage>): String {
        if (brain == null) {
            return summarizer(messagesToSummarize)
        }

        return runBlocking(Dispatchers.IO) {
            try {
                val content = messagesToSummarize.mapNotNull { it.content }.joinToString("\n").take(4000)
                val result = brain.chat(
                    listOf(
                        ChatMessage(
                            "system",
                            "Summarize the key findings, decisions, and file changes from this conversation in under 200 tokens. Focus on what matters for continuing the task."
                        ),
                        ChatMessage("user", content)
                    )
                )
                when (result) {
                    is ApiResult.Success -> result.data.choices.firstOrNull()?.message?.content
                        ?: summarizer(messagesToSummarize)
                    is ApiResult.Error -> {
                        // Fallback to truncation if LLM fails
                        val fallbackContent = messagesToSummarize.mapNotNull { it.content }.joinToString("\n")
                        "Previous context: ${fallbackContent.take(500)}..."
                    }
                }
            } catch (_: Exception) {
                // Fallback to truncation if LLM call throws
                summarizer(messagesToSummarize)
            }
        }
    }

    /** Reset the context (for a new worker session). */
    fun reset() {
        messages.clear()
        anchoredSummaries.clear()
        totalTokens = 0
    }

    /** Get remaining token budget. */
    fun remainingBudget(): Int = maxInputTokens - totalTokens

    /** Check if budget is critically low (<10% remaining). */
    fun isBudgetCritical(): Boolean = remainingBudget() < (maxInputTokens * 0.10)
}
