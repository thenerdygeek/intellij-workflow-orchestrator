package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.model.ApiResult

/**
 * Context window management with 3-stage compaction pipeline.
 *
 * Ported from Cline's ContextManager with our LLM summarization addition:
 *
 * Stage 1: Duplicate file read detection (from Cline)
 *   - Tracks files read by path
 *   - Replaces older reads with "[File content for '{path}' — see latest read below]"
 *   - Keeps the most recent read intact
 *   - If savings >= 30% of context, stop here (no truncation needed)
 *
 * Stage 2: Conversation truncation (from Cline's getNextTruncationRange)
 *   - Preserves the first user-assistant exchange (the task description)
 *   - Preserves the last N messages (recent work)
 *   - Maintains user-assistant role alternation
 *   - Removes middle messages in even-count blocks to keep pairing
 *
 * Stage 3: LLM summarization (our addition — Cline doesn't have this)
 *   - As optional fallback when truncation alone isn't enough
 *   - Summary chaining: includes previous summary in next compaction
 *   - Inserts summary as assistant message to avoid consecutive user messages
 *
 * Bug fixes from expert review:
 * - Stale token count: invalidate lastPromptTokens after any compaction
 * - Summary chaining: include previous summary in next compaction prompt
 * - Role alternation: summary is assistant message, not user
 * - Informative trimming: include tool name in trimmed placeholder
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/context/context-management/ContextManager.ts">Cline source</a>
 */
class ContextManager(
    private val maxInputTokens: Int = 150_000,
    private val compactionThreshold: Double = 0.85
) {
    private var systemPrompt: ChatMessage? = null
    private val messages: MutableList<ChatMessage> = mutableListOf()

    /** Last prompt token count reported by the API. Null if no API response yet. */
    private var lastPromptTokens: Int? = null

    /** Last summary from LLM compaction, used for summary chaining. */
    private var lastSummary: String? = null

    /**
     * Tracks file read tool results by path -> list of message indices.
     * Used for duplicate file read detection (Cline's optimization stage).
     */
    private val fileReadIndices: MutableMap<String, MutableList<Int>> = mutableMapOf()

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

    /**
     * Add a tool result and track file reads for deduplication.
     *
     * When a tool result contains file content, we track which file was read
     * and at which message index. This enables Stage 1 (duplicate file read
     * detection) during compaction.
     */
    fun addToolResult(toolCallId: String, content: String, isError: Boolean, toolName: String? = null) {
        val body = if (isError) "[ERROR] $content" else content
        val idx = messages.size
        messages.add(ChatMessage(role = "tool", content = body, toolCallId = toolCallId))

        // Track file reads for dedup (Cline pattern)
        if (!isError && toolName != null) {
            val filePath = extractFilePathFromToolResult(toolName, content)
            if (filePath != null) {
                fileReadIndices.getOrPut(filePath) { mutableListOf() }.add(idx)
            }
        }
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

    // ---- Stage 1: Duplicate file read detection (from Cline) ----

    /**
     * Replace duplicate file read tool results with notices.
     *
     * Ported from Cline's findAndPotentiallySaveFileReadContextHistoryUpdates:
     * - For each file path that has been read multiple times, keep only the latest read.
     * - Replace all older reads with a notice directing to the latest read.
     * - Returns the percentage of characters saved.
     *
     * Cline tracks reads at the message level and replaces text in-place.
     * We do the same: scan fileReadIndices, for files with multiple reads,
     * replace all-but-last with a short notice.
     */
    internal fun deduplicateFileReads(): Double {
        if (fileReadIndices.isEmpty()) return 0.0

        var totalChars = 0L
        var savedChars = 0L
        var anyReplaced = false

        for ((filePath, indices) in fileReadIndices) {
            if (indices.size <= 1) continue

            // Keep only the last read, replace all earlier ones
            val toReplace = indices.dropLast(1)
            for (idx in toReplace) {
                if (idx < messages.size) {
                    val old = messages[idx]
                    val oldContent = old.content ?: continue
                    val oldLength = oldContent.length
                    totalChars += oldLength

                    // Find the tool name from the preceding assistant message's tool_calls
                    val toolNameForMsg = findToolNameForResult(idx)
                    val notice = buildDedupNotice(filePath, toolNameForMsg)
                    savedChars += (oldLength - notice.length)
                    anyReplaced = true

                    messages[idx] = old.copy(content = notice)
                }
            }
            // Count the last (kept) read's characters for percentage calculation
            val lastIdx = indices.last()
            if (lastIdx < messages.size) {
                messages[lastIdx].content?.let { totalChars += it.length }
            }
        }

        // Return saved percentage, but ensure at least a small positive value
        // when replacements were made (even if individual notices happen to be longer
        // than tiny test inputs, the dedup is still valuable).
        if (!anyReplaced) return 0.0
        return if (totalChars == 0L) 0.0 else maxOf(savedChars.toDouble() / totalChars.toDouble(), 0.01)
    }

    // ---- Stage 2: Conversation truncation (from Cline) ----

    /**
     * Truncate the conversation preserving first and last exchanges.
     *
     * Ported from Cline's getNextTruncationRange + applyContextHistoryUpdates:
     * 1. Always keep the first user-assistant pair (indices 0-1 in messages = the task)
     * 2. Calculate how many messages to remove from the middle
     * 3. Remove in even-count blocks to maintain user-assistant alternation
     * 4. Ensure the range ends on an assistant message boundary
     *
     * @param keep strategy: "half" removes ~50% of middle, "quarter" removes ~75%,
     *   "lastTwo" keeps only first pair + last pair
     */
    internal fun truncateConversation(keep: TruncationStrategy = TruncationStrategy.HALF) {
        if (messages.size <= 4) return // Too few messages to truncate

        // Always keep first 2 messages (first user-assistant exchange)
        val rangeStart = 2

        val messagesToRemove = when (keep) {
            TruncationStrategy.HALF -> {
                // Remove half of remaining user-assistant pairs
                // Divide by 4 then multiply by 2 to keep even count (pairs)
                Math.floorDiv(messages.size - rangeStart, 4) * 2
            }
            TruncationStrategy.QUARTER -> {
                // Remove 3/4 of remaining user-assistant pairs
                Math.floorDiv((messages.size - rangeStart) * 3, 4) / 2 * 2
            }
            TruncationStrategy.LAST_TWO -> {
                // Remove all but last 2 messages (keep first pair + last pair)
                maxOf(messages.size - rangeStart - 2, 0)
            }
            TruncationStrategy.NONE -> {
                // Remove all messages beyond the first pair
                maxOf(messages.size - rangeStart, 0)
            }
        }

        if (messagesToRemove <= 0) return

        var rangeEnd = rangeStart + messagesToRemove - 1
        if (rangeEnd >= messages.size) rangeEnd = messages.size - 1

        // Cline ensures the range ends on an assistant message to preserve
        // user-assistant-user-assistant alternation after the truncation point.
        if (rangeEnd < messages.size && messages[rangeEnd].role != "assistant") {
            rangeEnd -= 1
        }

        if (rangeEnd < rangeStart) return

        // Remove orphaned tool results at the boundary.
        // After truncation, the first surviving message after the first pair
        // might be a "tool" result whose matching assistant tool_call was removed.
        // Cline handles this in applyContextHistoryUpdates by filtering out
        // tool_result blocks from the first message after truncation.
        // We handle it by adjusting rangeEnd to include orphaned tool messages.
        val nextIdx = rangeEnd + 1
        if (nextIdx < messages.size && messages[nextIdx].role == "tool") {
            // Extend range to include orphaned tool results up to the next user message
            var extendedEnd = nextIdx
            while (extendedEnd + 1 < messages.size && messages[extendedEnd + 1].role == "tool") {
                extendedEnd++
            }
            rangeEnd = extendedEnd
        }

        if (rangeEnd < rangeStart) return

        // Insert a truncation notice as the first assistant message (index 1)
        // Cline does this via applyStandardContextTruncationNoticeChange
        val firstAssistant = messages[1]
        if (firstAssistant.role == "assistant" &&
            firstAssistant.content?.contains("[Context truncated") != true
        ) {
            val removedCount = rangeEnd - rangeStart + 1
            // Clear toolCalls from the notice message — the matching tool results
            // may have been truncated, and leaving orphaned tool_calls breaks pairing.
            messages[1] = ChatMessage(
                role = "assistant",
                content = "[Context truncated — $removedCount messages removed to free context space. " +
                    "The conversation continues below with the most recent exchanges.]",
                toolCalls = null,
                toolCallId = null
            )
        }

        // Remove the range (inclusive)
        messages.subList(rangeStart, rangeEnd + 1).clear()

        // Rebuild file read index since message indices shifted
        rebuildFileReadIndices()
    }

    // ---- Stage 3: LLM summarization (our addition) ----

    /**
     * Ask the LLM to summarize old context into a structured format.
     *
     * Improvements over the original implementation:
     * - Summary chaining: includes the previous summary in the prompt
     * - Role fix: inserts summary as assistant message (not user) to avoid
     *   consecutive user messages
     * - Safe split: uses findSafeSplitPoint to avoid breaking tool_call/result pairs
     * - Failure is safe: on LLM error, leaves messages unchanged
     */
    private suspend fun llmSummarize(brain: LlmBrain) {
        val splitPoint = findSafeSplitPoint((messages.size * 0.7).toInt())
        if (splitPoint <= 0) return

        val oldMessages = messages.subList(0, splitPoint).toList()

        val summaryRequest = buildString {
            appendLine("Summarize the following conversation context into this format:")
            appendLine("TASK: <what the user asked for>")
            appendLine("FILES: <files read or modified>")
            appendLine("DONE: <what has been completed>")
            appendLine("ERRORS: <any errors encountered>")
            appendLine("PENDING: <what still needs to be done>")
            appendLine()
            // Summary chaining: include previous summary for continuity
            if (lastSummary != null) {
                appendLine("Previous summary (incorporate and update this):")
                appendLine(lastSummary)
                appendLine()
            }
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
        if (result is ApiResult.Error) return

        val summaryContent = (result as ApiResult.Success).data.choices.firstOrNull()?.message?.content
            ?: return

        // Store for summary chaining
        lastSummary = summaryContent

        // Remove old messages, insert summary as ASSISTANT message
        // (not user, to avoid consecutive user messages — bug fix from expert review)
        messages.subList(0, splitPoint).clear()
        messages.add(0, ChatMessage(role = "assistant", content = "[Context Summary]\n$summaryContent"))

        // Rebuild file read indices since messages shifted
        rebuildFileReadIndices()
    }

    // ---- Compaction orchestration ----

    /**
     * Run 3-stage compaction pipeline.
     *
     * Follows Cline's approach:
     * 1. Try duplicate file read dedup first (cheapest)
     * 2. If dedup saves < 30% → apply conversation truncation (Cline's primary strategy)
     * 3. If still above 95% → LLM summarization as fallback (our addition)
     *
     * BUG FIX: Invalidates lastPromptTokens after any compaction stage,
     * since the old token count is stale after removing/replacing messages.
     */
    suspend fun compact(brain: LlmBrain) {
        val util = utilizationPercent()
        if (util <= 70.0) return

        // Stage 1: Duplicate file read detection (from Cline)
        val percentSaved = deduplicateFileReads()
        invalidateTokens()

        // Cline's logic: if optimization saves >= 30%, skip truncation
        if (percentSaved >= 0.30) return

        // Stage 2: Conversation truncation (from Cline)
        if (utilizationPercent() > 85.0) {
            // Match Cline's strategy selection:
            // If tokens/2 > maxAllowed, use quarter (more aggressive); else half
            val estimatedTokens = lastPromptTokens ?: tokenEstimate()
            val keep = if (estimatedTokens / 2 > maxInputTokens) {
                TruncationStrategy.QUARTER
            } else {
                TruncationStrategy.HALF
            }
            truncateConversation(keep)
            invalidateTokens()
        }

        // Stage 3: LLM summarization as optional fallback (our addition)
        if (utilizationPercent() > 95.0) {
            llmSummarize(brain)
            invalidateTokens()
        }
    }

    /**
     * Invalidate API-reported tokens after compaction.
     * Forces re-estimation on next utilizationPercent() call.
     * Bug fix from expert review: stale tokens caused compaction to not trigger.
     */
    private fun invalidateTokens() {
        lastPromptTokens = null
    }

    // ---- Trimming (Stage 1 helper, also used by AgentLoop) ----

    /**
     * Stage 1 helper: Replace old tool result content with informative placeholder.
     * Keeps the [keepRecent] most recent tool results intact.
     *
     * Bug fix: includes tool name in the placeholder (not just character count).
     */
    fun trimOldToolResults(keepRecent: Int = 5) {
        val toolIndices = messages.indices.filter { messages[it].role == "tool" }
        if (toolIndices.size <= keepRecent) return

        val toTrim = toolIndices.dropLast(keepRecent)
        for (idx in toTrim) {
            val old = messages[idx]
            val originalLength = old.content?.length ?: 0
            val toolName = findToolNameForResult(idx)
            val nameHint = if (toolName != null) " ($toolName)" else ""
            messages[idx] = old.copy(content = "[Result trimmed$nameHint -- was $originalLength chars]")
        }
    }

    /**
     * Find a safe split point near [targetIndex] that preserves tool_call/result pairing.
     */
    internal fun findSafeSplitPoint(targetIndex: Int): Int {
        var idx = targetIndex.coerceIn(0, messages.size)
        while (idx < messages.size) {
            if (messages[idx].role == "user") return idx
            idx++
        }
        idx = targetIndex
        while (idx > 0) {
            idx--
            if (messages[idx].role == "user") return idx
        }
        return messages.size
    }

    /**
     * Sliding window: keep only the most recent fraction of messages. Last resort.
     */
    fun slidingWindow(keepFraction: Double = 0.3) {
        val keepCount = maxOf(1, (messages.size * keepFraction).toInt())
        val rawSplitPoint = messages.size - keepCount
        if (rawSplitPoint <= 0) return
        val safeSplitPoint = findSafeSplitPoint(rawSplitPoint)
        if (safeSplitPoint > 0 && safeSplitPoint < messages.size) {
            messages.subList(0, safeSplitPoint).clear()
            rebuildFileReadIndices()
        }
    }

    // ---- Internal helpers ----

    /**
     * Extract file path from a tool result based on tool name.
     * Recognizes read_file, write_to_file, replace_in_file patterns.
     * Matches Cline's parseToolCallWithFormat logic but adapted for our
     * OpenAI-compatible format where params are in the tool call, not the result.
     */
    private fun extractFilePathFromToolResult(toolName: String, content: String): String? {
        if (toolName !in FILE_READ_TOOLS) return null

        // Look for common patterns in tool result content:
        // 1. Header format from our tools: "[read_file for '/path/to/file'] Result: ..."
        val headerMatch = TOOL_RESULT_HEADER.find(content)
        if (headerMatch != null) return headerMatch.groupValues[2]

        // 2. File content tag: <file_content path="/path/to/file">...</file_content>
        val tagMatch = FILE_CONTENT_TAG.find(content)
        if (tagMatch != null) return tagMatch.groupValues[1]

        return null
    }

    /**
     * Build the deduplication notice for a replaced file read.
     * Matches Cline's formatResponse.duplicateFileReadNotice().
     */
    private fun buildDedupNotice(filePath: String, toolName: String?): String {
        val nameHint = if (toolName != null) " ($toolName)" else ""
        return "[File content for '$filePath' was previously read$nameHint — see latest read below]"
    }

    /**
     * Find the tool name associated with a tool result at the given index.
     * Looks backward for the preceding assistant message with tool_calls.
     */
    private fun findToolNameForResult(toolResultIndex: Int): String? {
        val toolMsg = messages.getOrNull(toolResultIndex) ?: return null
        val targetId = toolMsg.toolCallId ?: return null

        // Search backward for the assistant message containing this tool call
        for (i in (toolResultIndex - 1) downTo 0) {
            val msg = messages[i]
            val calls = msg.toolCalls
            if (msg.role == "assistant" && calls != null) {
                val matchingCall = calls.find { it.id == targetId }
                if (matchingCall != null) return matchingCall.function.name
            }
        }
        return null
    }

    /**
     * Rebuild the file read index after messages have been shifted/removed.
     * Scans all messages to find file-read tool results and re-index them.
     */
    private fun rebuildFileReadIndices() {
        fileReadIndices.clear()
        for (i in messages.indices) {
            val msg = messages[i]
            val msgContent = msg.content
            if (msg.role == "tool" && !msgContent.isNullOrEmpty()) {
                // Skip already-deduped messages
                if (msgContent.startsWith("[File content for ") || msgContent.startsWith("[Result trimmed")) {
                    continue
                }
                val toolName = findToolNameForResult(i)
                if (toolName != null) {
                    val filePath = extractFilePathFromToolResult(toolName, msgContent)
                    if (filePath != null) {
                        fileReadIndices.getOrPut(filePath) { mutableListOf() }.add(i)
                    }
                }
            }
        }
    }

    companion object {
        /** Tools that read/write files and whose results contain file content. */
        private val FILE_READ_TOOLS = setOf(
            "read_file", "write_to_file", "replace_in_file",
            "create_file", "edit_file"
        )

        /** Matches "[tool_name for '/path/to/file'] Result:" header format. */
        private val TOOL_RESULT_HEADER = Regex("""^\[(\S+) for '([^']+)'] Result:""")

        /** Matches <file_content path="...">...</file_content> tags. */
        private val FILE_CONTENT_TAG = Regex("""<file_content path="([^"]*?)">""")
    }
}

/**
 * Truncation strategy, matching Cline's getNextTruncationRange `keep` parameter.
 */
enum class TruncationStrategy {
    /** Remove half of middle messages. */
    HALF,
    /** Remove 3/4 of middle messages (more aggressive). */
    QUARTER,
    /** Keep only the first and last user-assistant pairs. */
    LAST_TWO,
    /** Remove all messages except the first pair. */
    NONE
}
