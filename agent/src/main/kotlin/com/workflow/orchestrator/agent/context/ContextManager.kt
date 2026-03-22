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
 *
 * THREAD SAFETY: Not thread-safe. Must be accessed from a single coroutine context
 * (the ReAct loop runs sequentially). Do not call addMessage/compress/reconcile concurrently.
 */
class ContextManager(
    private val maxInputTokens: Int = com.workflow.orchestrator.agent.settings.AgentSettings.DEFAULTS.maxInputTokens,
    private val brain: LlmBrain? = null,
    private val tMaxRatio: Double = 0.93,
    private val tRetainedRatio: Double = 0.70,
    private val toolResultMaxTokens: Int = 4000,
    private var reservedTokens: Int = 0,
    private val summarizer: (List<ChatMessage>) -> String = { msgs ->
        val sb = StringBuilder("Previous conversation summary:\n")
        for (msg in msgs) {
            val content = msg.content ?: continue
            when (msg.role) {
                "user" -> sb.appendLine("- User: ${content.take(200)}")
                "assistant" -> {
                    if (content.length > 5) sb.appendLine("- Agent: ${content.take(300)}")
                }
                "tool" -> sb.appendLine("- Tool result (${content.length} chars)")
                "system" -> {} // Skip system messages in summary
            }
            if (sb.length > 2000) break
        }
        sb.toString().take(2500)
    }
) {
    private val messages = mutableListOf<ChatMessage>()
    private val anchoredSummaries = mutableListOf<String>()
    /** Disk spillover for full tool outputs (OpenCode pattern). */
    var toolOutputStore: ToolOutputStore? = null
    /** Dedicated plan anchor — survives compression, updated in-place. */
    private var planAnchor: ChatMessage? = null
    /** Dedicated skill anchor — survives compression, updated on skill activation/deactivation. */
    private var skillAnchor: ChatMessage? = null
    /** Dedicated mention anchor — file content from @ mentions, survives compression. */
    private var mentionAnchor: ChatMessage? = null
    private var totalTokens = 0

    /** Effective budget after subtracting reserved tokens (tool defs, system prompt overhead, buffer). */
    private val effectiveBudget: Int get() = maxInputTokens - reservedTokens

    private val tMax: Int get() = (effectiveBudget * tMaxRatio).toInt()
    private val tRetained: Int get() = (effectiveBudget * tRetainedRatio).toInt()

    /** Current token usage across all messages. */
    val currentTokens: Int get() = totalTokens

    /** Current message count. */
    val messageCount: Int get() = messages.size

    /** The effective max input tokens (for budget warning calculations). */
    val effectiveMaxInputTokens: Int get() = maxInputTokens

    /**
     * Recalculate reserved tokens when the tool set changes.
     * This adjusts the effective budget and compression thresholds
     * so they stay accurate as tools expand during a session.
     */
    fun updateReservedTokens(newReserved: Int) {
        reservedTokens = newReserved
        // effectiveBudget, tMax, tRetained are computed properties — they auto-update
    }

    /**
     * Set or update the anchored plan summary. Dedicated slot separate from
     * the messages list — always included in getMessages(), never dropped by compress().
     */
    fun setPlanAnchor(message: ChatMessage?) {
        planAnchor = message
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    /**
     * Set or update the anchored skill summary. Dedicated slot separate from
     * the messages list — always included in getMessages(), never dropped by compress().
     */
    fun setSkillAnchor(message: ChatMessage?) {
        skillAnchor = message
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    /**
     * Set or update the anchored mention context (file content, folder trees from @ mentions).
     * Dedicated slot separate from the messages list — always included in getMessages(),
     * never dropped by compress().
     */
    fun setMentionAnchor(message: ChatMessage?) {
        mentionAnchor = message
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
        skillAnchor?.let { result.add(it) }
        mentionAnchor?.let { result.add(it) }

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
            // Phase 1: Prune old tool results first (fast, no LLM)
            pruneOldToolResults()
            // Phase 2: Full compression if still over budget
            if (totalTokens > tMax) {
                compress()
            }
        }
    }

    /**
     * Add a tool result to context. Full content sent to LLM (capped at 2000 lines / 50KB).
     * Full content also saved to disk for re-reads after pruning.
     *
     * Following OpenCode's pattern: LLM sees everything on first read.
     * Phase 1 pruning (pruneOldToolResults) handles aging — no premature compression.
     */
    fun addToolResult(toolCallId: String, content: String, summary: String) {
        // Save full content to disk (for re-reads after pruning)
        val diskPath = toolOutputStore?.save(toolCallId, content)

        // Cap at 2000 lines / 50KB (OpenCode's limits) — NOT the old 4K token compression
        val cappedContent = toolOutputStore?.capContent(content, diskPath) ?: content

        // Wrap in external_data tags for injection defense
        val wrapped = "<external_data>\n$cappedContent\n</external_data>"
        addMessage(ChatMessage(role = "tool", content = wrapped, toolCallId = toolCallId))
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
            // Only protect the FIRST system message (original prompt at index 0).
            // Allow other system messages (LoopGuard reminders, budget warnings) to be compressed.
            if (msg.role == "system" && i == 0) continue

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
        // Cap at 3 summaries — consolidate older ones
        if (anchoredSummaries.size > 3) {
            val consolidated = anchoredSummaries.joinToString("\n---\n")
            anchoredSummaries.clear()
            anchoredSummaries.add(consolidated.take(4000))
        }

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
            // Only protect the FIRST system message (original prompt at index 0).
            // Allow other system messages (LoopGuard reminders, budget warnings) to be compressed.
            if (msg.role == "system" && i == 0) continue

            val msgTokens = TokenEstimator.estimate(listOf(msg))
            messagesToDrop.add(msg)
            indicesToRemove.add(i)
            tokensToRemove -= msgTokens
        }

        if (messagesToDrop.isEmpty()) return

        val hasToolResults = messagesToDrop.any { it.role == "tool" }

        val summary = if (hasToolResults) {
            // Tool results contain high-information content — use LLM with structured template
            try {
                val promptContent = messagesToDrop.mapNotNull { it.content }.joinToString("\n---\n")
                val summarizePrompt = """
Summarize the conversation so far into a structured continuation prompt.
Use this exact format:

## Goal
What is the user trying to accomplish?

## Instructions
Key instructions or constraints the user specified.

## Discoveries
Important findings from code exploration, tool results, and analysis.
Include specific file paths, line numbers, and code patterns found.

## Accomplished
What has been completed so far. List specific changes made.

## Relevant Files
Files that were read, edited, or referenced. Include paths.

Be concise but preserve ALL technical details — file paths, line numbers,
error messages, code snippets, and specific findings. These details are
critical for continuing the task.
""".trimIndent()
                val summarizationPrompt = listOf(
                    ChatMessage(
                        role = "system",
                        content = summarizePrompt
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
        // Cap at 3 summaries — consolidate older ones
        if (anchoredSummaries.size > 3) {
            val consolidated = anchoredSummaries.joinToString("\n---\n")
            anchoredSummaries.clear()
            anchoredSummaries.add(consolidated.take(4000))
        }

        // Remove compressed messages (reverse order to preserve indices)
        indicesToRemove.reversed().forEach { messages.removeAt(it) }

        // Recalculate total tokens
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    /** Metadata about a tool call that triggered a tool result. */
    private data class ToolCallMeta(val toolName: String, val arguments: String)

    /**
     * Walk backward from a tool result message index to find the assistant message
     * containing the tool_call that triggered it (matched by toolCallId).
     */
    private fun findToolCallMetadata(toolResultIndex: Int): ToolCallMeta? {
        val toolCallId = messages[toolResultIndex].toolCallId ?: return null
        for (j in (toolResultIndex - 1) downTo 0) {
            val candidate = messages[j]
            if (candidate.role != "assistant") continue
            val matchingCall = candidate.toolCalls?.find { it.id == toolCallId }
            if (matchingCall != null) {
                return ToolCallMeta(
                    toolName = matchingCall.function.name,
                    arguments = matchingCall.function.arguments
                )
            }
        }
        return null
    }

    /**
     * Build a metadata-rich placeholder for a pruned tool result.
     * Contains tool name, arguments (truncated), content preview, disk path, and recovery hint.
     */
    private fun buildRichPlaceholder(
        originalContent: String?,
        meta: ToolCallMeta?,
        toolCallId: String?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("[Tool result pruned to save context]")

        // Tool name and arguments
        val toolName = meta?.toolName ?: "unknown_tool"
        sb.appendLine("Tool: $toolName")
        if (meta != null) {
            val truncatedArgs = if (meta.arguments.length > 300) {
                meta.arguments.take(300) + "..."
            } else {
                meta.arguments
            }
            sb.appendLine("Args: $truncatedArgs")
        }

        // Content preview — first 5 lines of the unwrapped original content
        if (!originalContent.isNullOrBlank()) {
            val unwrapped = originalContent
                .removePrefix("<external_data>\n")
                .removeSuffix("\n</external_data>")
                .removePrefix("<external_data>")
                .removeSuffix("</external_data>")
            val previewLines = unwrapped.lines().take(5)
            if (previewLines.isNotEmpty()) {
                sb.appendLine("Preview:")
                previewLines.forEach { sb.appendLine("  $it") }
                val totalLines = unwrapped.lines().size
                if (totalLines > 5) {
                    sb.appendLine("  ... (${totalLines - 5} more lines)")
                }
            }
        }

        // Disk path for recovery
        val diskPath = toolCallId?.let { toolOutputStore?.getPath(it) }
        if (diskPath != null) {
            sb.appendLine("Full output saved: $diskPath")
        }

        // Recovery hint
        val recoveryHint = buildRecoveryHint(toolName, meta?.arguments, diskPath)
        if (recoveryHint != null) {
            sb.appendLine("Recovery: $recoveryHint")
        }

        return "<external_data>${sb.toString().trimEnd()}</external_data>"
    }

    /**
     * Build an actionable recovery hint based on the tool that was pruned.
     */
    private fun buildRecoveryHint(toolName: String, arguments: String?, diskPath: String?): String? {
        // Try to extract a file path from arguments for file-related tools
        val filePath = arguments?.let {
            val pathMatch = Regex(""""(?:path|file_path|file)"\s*:\s*"([^"]+)"""").find(it)
            pathMatch?.groupValues?.get(1)
        }

        return when {
            toolName == "read_file" && filePath != null ->
                "use read_file on '$filePath' to re-read"
            toolName == "search_code" ->
                "re-run search_code with the same query to refresh results"
            toolName == "glob_files" ->
                "re-run glob_files with the same pattern to refresh results"
            toolName == "run_command" ->
                "re-run the command if the output is still needed"
            toolName == "diagnostics" ->
                "re-run diagnostics to get current results"
            diskPath != null ->
                "use read_file on '$diskPath' to recover full output"
            else -> null
        }
    }

    /**
     * Phase 1 compression: prune old tool results in-place.
     * Protects the most recent tool results (up to protectedTokens).
     * Older tool results are replaced with a metadata-rich placeholder
     * containing tool name, arguments, content preview, and recovery hints.
     */
    fun pruneOldToolResults(protectedTokens: Int = 40_000) {
        var protectedSoFar = 0
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            if (msg.role != "tool") continue
            val msgTokens = TokenEstimator.estimate(listOf(msg))
            if (protectedSoFar + msgTokens <= protectedTokens) {
                protectedSoFar += msgTokens
                continue
            }
            val toolCallId = msg.toolCallId
            val meta = findToolCallMetadata(i)
            val placeholder = buildRichPlaceholder(msg.content, meta, toolCallId)
            messages[i] = ChatMessage(
                role = "tool",
                content = placeholder,
                toolCallId = toolCallId
            )
        }
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
        skillAnchor = null
        mentionAnchor = null
        totalTokens = 0
    }

    /**
     * Reconcile the heuristic token count with the actual count from the API.
     *
     * Called after each LLM response with the server-reported prompt_tokens.
     * The API's tokenizer is authoritative — our character-based heuristic
     * (text.length / 3.5) can be 20-40% off, especially for code and JSON.
     *
     * This calibration ensures compression thresholds and budget nudges
     * fire at the right time.
     */
    fun reconcileWithActualTokens(actualPromptTokens: Int) {
        if (actualPromptTokens > 0) {
            // The API's promptTokens IS the authoritative context size.
            // It includes system prompt + tool definitions + all messages.
            // No need to subtract reservedTokens — the API already counted everything.
            // Our totalTokens should track the same thing the API tracks.
            totalTokens = actualPromptTokens
        }
    }

    /** Get remaining token budget (accounting for reserved tokens). */
    fun remainingBudget(): Int = effectiveBudget - totalTokens

    /** Check if budget is critically low (<10% remaining). */
    fun isBudgetCritical(): Boolean = remainingBudget() < (effectiveBudget * 0.10)
}
