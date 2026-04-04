package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult

/**
 * Simple message list with token tracking and 3-stage compaction.
 *
 * Modeled after Codex CLI's ContextManager:
 * - Messages stored as a mutable list
 * - Token tracking: API-reported (authoritative) or bytes/4 (fallback)
 * - Compaction: 3 stages triggered by utilization thresholds
 *
 * Stage 1 (>70%): Trim old tool results — no LLM call
 * Stage 2 (>85%): LLM summarization — ask cheap model to summarize old context
 * Stage 3 (>95%): Sliding window — keep last 30%, drop the rest
 */
class ContextManager(
    private val maxInputTokens: Int = 150_000,
    private val compactionThreshold: Double = 0.85
) {
    private var systemPrompt: ChatMessage? = null
    private val messages: MutableList<ChatMessage> = mutableListOf()

    /** Last prompt token count reported by the API. Null if no API response yet. */
    private var lastPromptTokens: Int? = null

    // ---- Message management ----

    fun setSystemPrompt(prompt: String) {
        systemPrompt = ChatMessage(role = "system", content = prompt)
    }

    fun addUserMessage(content: String) {
        messages.add(ChatMessage(role = "user", content = content))
    }

    fun addAssistantMessage(message: ChatMessage) {
        messages.add(message)
    }

    fun addToolResult(toolCallId: String, content: String, isError: Boolean) {
        val body = if (isError) "[ERROR] $content" else content
        messages.add(ChatMessage(role = "tool", content = body, toolCallId = toolCallId))
    }

    /**
     * Returns all messages: system prompt (if set) followed by conversation messages.
     */
    fun getMessages(): List<ChatMessage> {
        return buildList {
            systemPrompt?.let { add(it) }
            addAll(messages)
        }
    }

    // ---- Token tracking ----

    /**
     * Called after each API response with the actual prompt token count.
     */
    fun updateTokens(promptTokens: Int) {
        lastPromptTokens = promptTokens
    }

    /**
     * Returns utilization as a percentage (0-100+).
     * Uses API-reported tokens if available, otherwise falls back to bytes/4 estimate.
     */
    fun utilizationPercent(): Double {
        val tokens = lastPromptTokens ?: tokenEstimate()
        return (tokens.toDouble() / maxInputTokens) * 100.0
    }

    /**
     * True if utilization exceeds the compaction threshold.
     */
    fun shouldCompact(): Boolean {
        return utilizationPercent() > compactionThreshold * 100.0
    }

    /**
     * Estimate total tokens using bytes/4 heuristic (Codex CLI pattern).
     * Counts message content AND tool call names/arguments.
     */
    fun tokenEstimate(): Int {
        var bytes = 0
        systemPrompt?.content?.let { bytes += it.toByteArray(Charsets.UTF_8).size }
        for (msg in messages) {
            msg.content?.let { bytes += it.toByteArray(Charsets.UTF_8).size }
            msg.toolCalls?.forEach { tc ->
                bytes += tc.function.name.toByteArray(Charsets.UTF_8).size
                bytes += tc.function.arguments.toByteArray(Charsets.UTF_8).size
            }
            // Per-message overhead (role, delimiters)
            bytes += 4
        }
        return bytes / 4
    }

    fun messageCount(): Int = messages.size

    // ---- Compaction ----

    /**
     * Run 3-stage compaction based on current utilization.
     *
     * Stage 1 (>70%): Trim old tool results
     * Stage 2 (>85%): LLM summarization of oldest 70% of messages
     * Stage 3 (>95%): Sliding window — keep last 30%
     */
    suspend fun compact(brain: LlmBrain) {
        val util = utilizationPercent()

        if (util > 70.0) {
            trimOldToolResults(keepRecent = 5)
        }

        if (util > 85.0) {
            llmSummarize(brain)
        }

        if (util > 95.0) {
            slidingWindow(keepFraction = 0.3)
        }
    }

    /**
     * Find a safe split point near [targetIndex] that preserves tool_call/result pairing.
     * Searches forward from targetIndex to find the next "user" message boundary.
     * A user message always starts a fresh turn, so splitting there is safe.
     * If no user message found forward, searches backward.
     */
    internal fun findSafeSplitPoint(targetIndex: Int): Int {
        var idx = targetIndex.coerceIn(0, messages.size)
        // Search forward for the next user message (start of a new turn)
        while (idx < messages.size) {
            if (messages[idx].role == "user") return idx
            idx++
        }
        // If no user message found after target, search backward
        idx = targetIndex
        while (idx > 0) {
            idx--
            if (messages[idx].role == "user") return idx
        }
        // Can't find safe point — don't split
        return messages.size
    }

    /**
     * Stage 1: Replace old tool result content with a placeholder.
     * Keeps the [keepRecent] most recent tool results intact.
     */
    fun trimOldToolResults(keepRecent: Int = 5) {
        // Find indices of all tool result messages
        val toolIndices = messages.indices.filter { messages[it].role == "tool" }
        if (toolIndices.size <= keepRecent) return

        val toTrim = toolIndices.dropLast(keepRecent)
        for (idx in toTrim) {
            val old = messages[idx]
            val originalLength = old.content?.length ?: 0
            messages[idx] = old.copy(content = "[Result trimmed -- was $originalLength chars]")
        }
    }

    /**
     * Stage 3: Keep only the most recent fraction of messages. Last resort.
     * Uses [findSafeSplitPoint] to avoid splitting tool_call/result pairs.
     */
    fun slidingWindow(keepFraction: Double = 0.3) {
        val keepCount = maxOf(1, (messages.size * keepFraction).toInt())
        val rawSplitPoint = messages.size - keepCount
        if (rawSplitPoint <= 0) return
        val safeSplitPoint = findSafeSplitPoint(rawSplitPoint)
        if (safeSplitPoint > 0 && safeSplitPoint < messages.size) {
            messages.subList(0, safeSplitPoint).clear()
        }
    }

    /**
     * Stage 2: Ask the LLM to summarize the oldest 70% of messages into structured format.
     * Replaces summarized messages with a single user message containing the summary.
     * Uses [findSafeSplitPoint] to avoid splitting tool_call/result pairs.
     */
    private suspend fun llmSummarize(brain: LlmBrain) {
        val splitPoint = findSafeSplitPoint((messages.size * 0.7).toInt())
        if (splitPoint <= 0) return

        val oldMessages = messages.subList(0, splitPoint).toList()

        // Build summarization prompt
        val summaryRequest = buildString {
            appendLine("Summarize the following conversation context into this format:")
            appendLine("TASK: <what the user asked for>")
            appendLine("FILES: <files read or modified>")
            appendLine("DONE: <what has been completed>")
            appendLine("ERRORS: <any errors encountered>")
            appendLine("PENDING: <what still needs to be done>")
            appendLine()
            appendLine("Conversation to summarize:")
            for (msg in oldMessages) {
                appendLine("[${msg.role}] ${msg.content ?: "(tool call)"}")
            }
        }

        val summaryMessages = listOf(
            ChatMessage(role = "user", content = summaryRequest)
        )

        val result = brain.chat(summaryMessages, maxTokens = 1024)

        // On LLM failure, skip compaction entirely — leave messages as-is.
        // The next stage (sliding window) will handle it if needed.
        if (result is ApiResult.Error) return

        val summaryContent = (result as ApiResult.Success).data.choices.firstOrNull()?.message?.content
            ?: return // No content in response — skip compaction rather than losing context

        // Remove old messages, insert summary
        messages.subList(0, splitPoint).clear()
        messages.add(0, ChatMessage(role = "user", content = "[Context Summary]\n$summaryContent"))
    }
}
