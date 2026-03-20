package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.core.model.ApiResult

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
 * When [brain] is provided, [compressWithLlm] can use LLM-powered summarization
 * for tool results (which contain high-information content like file paths, line
 * numbers, and code changes). The synchronous [compress] method always uses the
 * truncation summarizer.
 */
class ContextManager(
    private val maxInputTokens: Int = 150_000,
    private val brain: LlmBrain? = null,
    private val tMaxRatio: Double = 0.70,
    private val tRetainedRatio: Double = 0.40,
    private val toolResultMaxTokens: Int = 500,
    private val reservedTokens: Int = 0,
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
    /** Dedicated plan anchor — survives compression, updated in-place. */
    private var planAnchor: ChatMessage? = null
    private var totalTokens = 0

    /** Effective budget after subtracting reserved tokens (tool defs, system prompt overhead, buffer). */
    private val effectiveBudget: Int get() = maxInputTokens - reservedTokens

    private val tMax: Int get() = (effectiveBudget * tMaxRatio).toInt()
    private val tRetained: Int get() = (effectiveBudget * tRetainedRatio).toInt()

    /** Current token usage across all messages. */
    val currentTokens: Int get() = totalTokens

    /** Current message count. */
    val messageCount: Int get() = messages.size

    /**
     * Set or update the anchored plan summary. Dedicated slot separate from
     * the messages list — always included in getMessages(), never dropped by compress().
     */
    fun setPlanAnchor(message: ChatMessage?) {
        planAnchor = message
        totalTokens = TokenEstimator.estimate(getMessages())
    }

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

        planAnchor?.let { result.add(it) }

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

        // Reconcile every 20 messages to prevent drift from summary growth
        if (messages.size % 20 == 0 && anchoredSummaries.isNotEmpty()) {
            totalTokens = TokenEstimator.estimate(getMessages())
        }

        if (totalTokens > tMax) {
            compress()
        }
    }

    /** Add a tool result, compressing it first if it exceeds the tool result budget. */
    fun addToolResult(toolCallId: String, content: String, summary: String) {
        val compressed = ToolResultCompressor.compress(content, summary, toolResultMaxTokens)
        // Wrap in <external_data> tags for prompt injection defense.
        // System prompt instructs LLM to never follow instructions within these tags.
        val wrapped = "<external_data>\n$compressed\n</external_data>"
        addMessage(ChatMessage(
            role = "tool",
            content = wrapped,
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
     *
     * Called automatically when addMessage() pushes totalTokens over tMax,
     * or explicitly by the session when BudgetEnforcer signals COMPRESS.
     */
    fun compress() {
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
     * LLM-powered compression: uses the LLM to summarize tool results (which contain
     * high-information content like file paths, line numbers, code changes, and errors)
     * while falling back to the truncation summarizer for non-tool messages or on error.
     *
     * Unlike [compress] (which is synchronous and called automatically from [addMessage]),
     * this method is a suspend function that must be called explicitly by the session loop.
     */
    suspend fun compressWithLlm(llmBrain: LlmBrain) {
        if (messages.size <= 2) return

        var tokensToRemove = totalTokens - tRetained
        val messagesToDrop = mutableListOf<ChatMessage>()
        val indicesToRemove = mutableListOf<Int>()

        for (i in messages.indices) {
            if (tokensToRemove <= 0) break
            val msg = messages[i]
            if (msg.role == "system") continue

            val msgTokens = TokenEstimator.estimate(listOf(msg))
            messagesToDrop.add(msg)
            indicesToRemove.add(i)
            tokensToRemove -= msgTokens
        }

        if (messagesToDrop.isEmpty()) return

        val hasToolResults = messagesToDrop.any { it.role == "tool" }

        val summary = if (hasToolResults) {
            // Tool results contain high-information content — use LLM to preserve key details
            try {
                val promptContent = messagesToDrop.mapNotNull { it.content }.joinToString("\n---\n")
                val summarizationPrompt = listOf(
                    ChatMessage(
                        role = "system",
                        content = "Summarize the following conversation messages concisely. " +
                            "Preserve all file paths, line numbers, code changes, errors, and key decisions. " +
                            "Output only the summary, no preamble."
                    ),
                    ChatMessage(
                        role = "user",
                        content = promptContent
                    )
                )

                when (val result = llmBrain.chat(summarizationPrompt, null, 500, null)) {
                    is ApiResult.Success -> {
                        val llmSummary = result.data.choices.firstOrNull()?.message?.content
                        if (!llmSummary.isNullOrBlank()) {
                            "Previous context summary (LLM): $llmSummary"
                        } else {
                            // Empty LLM response — fall back
                            summarizer(messagesToDrop)
                        }
                    }
                    is ApiResult.Error -> {
                        // LLM failed — fall back to truncation (never throw)
                        summarizer(messagesToDrop)
                    }
                }
            } catch (_: Exception) {
                // Catch-all safety net — fall back to truncation
                summarizer(messagesToDrop)
            }
        } else {
            // No tool results — truncation is sufficient and cheaper
            summarizer(messagesToDrop)
        }

        anchoredSummaries.add(summary)

        // Remove compressed messages (reverse order to preserve indices)
        indicesToRemove.reversed().forEach { messages.removeAt(it) }

        // Recalculate total tokens
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    /** Summarize messages using the truncation summarizer. */
    private fun summarizeMessages(messagesToSummarize: List<ChatMessage>): String {
        return summarizer(messagesToSummarize)
    }

    /** Reset the context (for a new worker session). */
    fun reset() {
        messages.clear()
        anchoredSummaries.clear()
        planAnchor = null
        totalTokens = 0
    }

    /** Get remaining token budget (accounting for reserved tokens). */
    fun remainingBudget(): Int = effectiveBudget - totalTokens

    /** Check if budget is critically low (<10% remaining). */
    fun isBudgetCritical(): Boolean = remainingBudget() < (effectiveBudget * 0.10)
}
