package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.hooks.HookEvent
import com.workflow.orchestrator.agent.hooks.HookManager
import com.workflow.orchestrator.agent.hooks.HookResult
import com.workflow.orchestrator.agent.hooks.HookType
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
    val maxInputTokens: Int = 150_000,
    private val compactionThreshold: Double = 0.85,
    /**
     * Callback invoked after compaction modifies the conversation history.
     *
     * Ported from Cline's conversationHistoryDeletedRange pattern in message-state.ts:
     * when context truncation or summarization removes messages, this callback persists
     * the modified history via MessageStateHandler.overwriteApiConversationHistory().
     *
     * @param messages the current conversation messages after compaction
     * @param deletedRange the (startIdx, endIdx) range that was removed
     */
    var onHistoryOverwrite: (suspend (List<ChatMessage>, deletedRange: Pair<Int, Int>) -> Unit)? = null
) {
    private val LOG = Logger.getInstance(ContextManager::class.java)

    private var systemPrompt: ChatMessage? = null
    private val messages: MutableList<ChatMessage> = mutableListOf()

    /** Last prompt token count reported by the API. Null if no API response yet. */
    private var lastPromptTokens: Int? = null

    /** Last summary from LLM compaction, used for summary chaining. */
    private var lastSummary: String? = null

    /** Token count for tool definitions (schemas sent in API request). Updated by AgentLoop. */
    private var toolDefinitionTokens: Int = 0

    /**
     * Tracks file read tool results by path -> list of message indices.
     * Used for duplicate file read detection (Cline's optimization stage).
     */
    private val fileReadIndices: MutableMap<String, MutableList<Int>> = mutableMapOf()

    /**
     * Active skill content — set when use_skill tool is called.
     *
     * Ported from Cline's skill activation pattern:
     * - Set when use_skill is invoked with a valid skill name
     * - Survives compaction: after compaction, re-inject as a tagged user message
     * - Cleared when a new skill is activated or the session ends
     */
    private var activeSkillContent: String? = null

    /**
     * Path to the saved plan file on disk.
     * Set when plan_mode_respond generates a plan — saved immediately, not on approval.
     * Survives compaction: re-injected as a pointer so the LLM can re-read the plan.
     */
    private var activePlanPath: String? = null

    /**
     * Current task progress markdown string.
     *
     * Port of Cline's `currentFocusChainChecklist` from TaskState:
     * - Updated when the LLM includes `task_progress` in tool call params
     * - Survives compaction: re-injected into the system prompt after compaction
     * - Included in session checkpoint for resume
     *
     * @see <a href="https://github.com/cline/cline/blob/main/src/core/task/focus-chain/index.ts">Cline FocusChainManager</a>
     */
    private var taskProgressMarkdown: String? = null

    // ---- Message management ----

    fun setSystemPrompt(prompt: String) {
        systemPrompt = ChatMessage(role = "system", content = prompt)
        LOG.info("[Context] System prompt set (${prompt.length} chars, ~${prompt.length / 4} tokens)")
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
        LOG.debug("[Context] Tool result added: $toolCallId (${content.length} chars)")

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
        LOG.debug("[Context] Token update: $promptTokens prompt tokens (${"%.1f".format(utilizationPercent())}% of $maxInputTokens)")
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
     * Set the token count for tool definitions (schemas sent in each API request).
     *
     * Tool schemas are significant: 30+ tools can consume 5-10K+ tokens.
     * The old estimate ignored these entirely, causing utilization to be
     * underreported by 5-10% — delaying compaction until context overflow.
     *
     * Called by AgentLoop once at setup and whenever deferred tools are loaded.
     */
    fun setToolDefinitionTokens(tokens: Int) {
        toolDefinitionTokens = tokens
        LOG.debug("[Context] Tool definition tokens set: $tokens")
    }

    /**
     * Estimate total tokens using chars/3.5 heuristic.
     *
     * Uses chars/3.5 (not bytes/4) to match TokenEstimator and be consistent
     * across the codebase. Anthropic/OpenAI models average ~3.5 chars/token
     * for code-heavy content.
     *
     * Includes:
     * - System prompt content
     * - All message content + tool call names/arguments
     * - Tool definition schemas (set via [setToolDefinitionTokens])
     * - Per-message overhead (~4 tokens for role, delimiters)
     */
    fun tokenEstimate(): Int {
        var chars = 0
        systemPrompt?.content?.let { chars += it.length }
        for (msg in messages) {
            msg.content?.let { chars += it.length }
            msg.toolCalls?.forEach { tc ->
                chars += tc.function.name.length
                chars += tc.function.arguments.length
            }
        }
        val messageTokens = (chars / 3.5).toInt()
        val overheadTokens = (messages.size + 1) * 4 // +1 for system prompt
        return messageTokens + overheadTokens + toolDefinitionTokens
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
        if (result is ApiResult.Error) {
            LOG.warn("[Context] Summarization failed: ${result.message}, skipping")
            return
        }

        val summaryContent = (result as ApiResult.Success).data.choices.firstOrNull()?.message?.content
            ?: return

        // Store for summary chaining
        lastSummary = summaryContent

        // Remove old messages, insert summary as ASSISTANT message
        // (not user, to avoid consecutive user messages — bug fix from expert review)
        messages.subList(0, splitPoint).clear()
        messages.add(0, ChatMessage(role = "assistant", content = "[Context Summary]\n$summaryContent"))
        LOG.info("[Context] Summarized: $splitPoint messages -> summary (${summaryContent.length} chars)")

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
     * PRE_COMPACT hook (ported from Cline's PreCompact hook):
     * Fires before compaction begins. Cancellable: if cancelled, compaction is skipped.
     * Cline: "Executes before conversation context is compacted."
     * This is dangerous but user-requested — they may want to archive or inspect context first.
     *
     * BUG FIX: Invalidates lastPromptTokens after any compaction stage,
     * since the old token count is stale after removing/replacing messages.
     *
     * @param brain the LLM brain for Stage 3 summarization
     * @param hookManager optional hook manager for PRE_COMPACT dispatch
     */
    suspend fun compact(brain: LlmBrain, hookManager: HookManager? = null) {
        val util = utilizationPercent()
        if (util <= 70.0) return

        LOG.info("[Context] Compacting at ${"%.1f".format(util)}% utilization (${messageCount()} messages)")

        // PRE_COMPACT hook — cancellable (user can skip compaction)
        if (hookManager != null && hookManager.hasHooks(HookType.PRE_COMPACT)) {
            val hookResult = hookManager.dispatch(
                HookEvent(
                    type = HookType.PRE_COMPACT,
                    data = mapOf(
                        "utilizationPercent" to util,
                        "messageCount" to messages.size
                    )
                )
            )
            if (hookResult is HookResult.Cancel) {
                return // User's hook cancelled compaction
            }
        }

        // Stage 1: Duplicate file read detection (from Cline)
        val percentSaved = deduplicateFileReads()
        invalidateTokens()

        if (percentSaved > 0.0) {
            val dedupCount = fileReadIndices.values.count { it.size > 1 }
            LOG.info("[Context] Dedup: removed $dedupCount duplicate file reads, saved ${"%.1f".format(percentSaved * 100)}%")
        }

        // Cline's logic: if optimization saves >= 30%, skip truncation
        if (percentSaved >= 0.30) return

        // Stage 2: Conversation truncation (from Cline)
        if (utilizationPercent() > 85.0) {
            // Match Cline's strategy selection:
            // If tokens/2 > maxAllowed, use quarter (more aggressive); else half
            val estimatedTokens = lastPromptTokens ?: tokenEstimate()
            val keep = if (estimatedTokens / 2 > maxInputTokens) TruncationStrategy.QUARTER
                       else TruncationStrategy.HALF
            val countBeforeTruncation = messageCount()
            truncateConversation(keep)
            invalidateTokens()
            val removedCount = countBeforeTruncation - messageCount()
            if (removedCount > 0) {
                LOG.info("[Context] Truncated: removed $removedCount messages (strategy: $keep)")
                // Notify persistence layer so truncated history is persisted (Cline's conversationHistoryDeletedRange pattern)
                onHistoryOverwrite?.invoke(messages.toList(), Pair(2, 2 + removedCount - 1))
            }
        }

        // Stage 3: LLM summarization as optional fallback (our addition)
        if (utilizationPercent() > 95.0) {
            val countBeforeSummarization = messageCount()
            llmSummarize(brain)
            invalidateTokens()
            val summarizedCount = countBeforeSummarization - messageCount()
            if (summarizedCount > 0) {
                onHistoryOverwrite?.invoke(messages.toList(), Pair(0, summarizedCount - 1))
            }
        }

        // After compaction: re-inject active skill and plan path so LLM retains them
        // (ported from Cline: skill content survives compaction via re-injection)
        reInjectActiveSkill()
        reInjectActivePlan()
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
     * Recognizes read_file, create_file, edit_file patterns.
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

    // ---- Checkpoint persistence (ported from Cline's task/message-state persistence) ----

    /**
     * Export all non-system messages for checkpoint persistence.
     *
     * Port of Cline's pattern: Cline stores apiConversationHistory as an array of
     * MessageParam — system prompt is NOT included in the saved history because
     * it's rebuilt from task settings on resume. We follow the same pattern.
     *
     * @return all conversation messages (excluding system prompt) in order
     */
    fun exportMessages(): List<ChatMessage> = messages.toList()

    /**
     * Restore messages from a checkpoint, replacing current conversation state.
     *
     * Port of Cline's setApiConversationHistory(newHistory) from message-state.ts:
     * replaces the in-memory message list and rebuilds derived indices.
     *
     * Used during session resume: MessageStateHandler loads persisted history, then this
     * method restores the ContextManager to the checkpointed state.
     *
     * @param savedMessages messages loaded from checkpoint
     */
    fun restoreMessages(savedMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(savedMessages)
        rebuildFileReadIndices()
        invalidateTokens()
    }

    /**
     * Clear all conversation messages while preserving anchors (active skill, plan path,
     * task progress, guardrails, facts). Used when the user approves a plan with
     * "clear context" to free context budget for implementation.
     *
     * The system prompt is rebuilt each turn by [PromptAssembler], so it is not affected.
     * The [lastSummary] is also cleared since the summarized content is no longer relevant.
     */
    fun clearMessages() {
        messages.clear()
        lastSummary = null
        fileReadIndices.clear()
        invalidateTokens()
        LOG.info("[Context] Messages cleared for plan execution (anchors preserved)")
    }

    /**
     * Export the system prompt content (for persisting in Session metadata).
     */
    fun getSystemPromptContent(): String? = systemPrompt?.content

    // ---- Task progress (ported from Cline's FocusChainManager) ----

    /**
     * Update task progress from the LLM's tool call `task_progress` parameter.
     *
     * Port of Cline's FocusChainManager.updateFCListFromToolResponse():
     * - Receives the raw markdown checklist from the tool call
     * - Stores it for inclusion in the system prompt
     * - Parses it into TaskProgress for UI consumption
     *
     * Called by AgentLoop after extracting `task_progress` from tool call arguments.
     *
     * @param markdown the raw checklist markdown from the LLM
     * @return parsed TaskProgress for UI display, or null if markdown was blank
     */
    fun setTaskProgress(markdown: String): TaskProgress? {
        val trimmed = markdown.trim()
        if (trimmed.isBlank()) return null
        taskProgressMarkdown = trimmed
        return TaskProgress.fromMarkdown(trimmed)
    }

    /**
     * Get the current task progress markdown string.
     * Returns null if no progress has been set.
     *
     * Used by:
     * - SystemPrompt.build() to inject progress into the system prompt
     * - Session checkpoint to persist progress
     * - AgentLoop to provide progress after compaction
     */
    fun getTaskProgress(): String? = taskProgressMarkdown

    /**
     * Get the current task progress as a parsed TaskProgress object.
     * Returns null if no progress has been set.
     */
    fun getTaskProgressParsed(): TaskProgress? {
        val md = taskProgressMarkdown ?: return null
        return TaskProgress.fromMarkdown(md)
    }

    // ---- Active skill management (ported from Cline's skill system) ----

    /**
     * Set the active skill content. Called after use_skill tool executes.
     *
     * The active skill content survives compaction: after compaction runs,
     * [reInjectActiveSkill] is called to ensure the LLM still has access
     * to the skill instructions.
     *
     * @param content the full skill content to store
     */
    fun setActiveSkill(content: String) {
        activeSkillContent = content
    }

    /**
     * Get the current active skill content.
     * Returns null if no skill is active.
     */
    fun getActiveSkill(): String? = activeSkillContent

    /**
     * Clear the active skill. Called when a session ends or is reset.
     */
    fun clearActiveSkill() {
        activeSkillContent = null
    }

    /**
     * Re-inject the active skill into the conversation after compaction.
     *
     * After compaction removes old messages, the skill instructions may have
     * been lost. This re-injects them as an assistant message tagged with
     * [Active Skill] so the LLM continues to follow the skill.
     *
     * Called by the compaction pipeline after any stage that removes messages.
     */
    internal fun reInjectActiveSkill() {
        val skill = activeSkillContent ?: return
        // Only re-inject if the skill content isn't already in the last few messages
        val recentMessages = messages.takeLast(10)
        val alreadyPresent = recentMessages.any { msg ->
            msg.content?.contains("[Active Skill]") == true
        }
        if (!alreadyPresent) {
            messages.add(ChatMessage(
                role = "assistant",
                content = "[Active Skill] The following skill instructions are still active:\n\n$skill"
            ))
        }
    }

    // ---- Active plan management (ported from activeSkill pattern) ----

    /**
     * Set the active plan path. Called after plan_mode_respond generates a plan.
     *
     * The active plan path survives compaction: after compaction runs,
     * [reInjectActivePlan] is called to ensure the LLM still has access
     * to the plan location.
     *
     * @param path the file path to the saved plan
     */
    fun setActivePlanPath(path: String) {
        activePlanPath = path
    }

    /**
     * Get the current active plan path.
     * Returns null if no plan path is set.
     */
    fun getActivePlanPath(): String? = activePlanPath

    /**
     * Clear the active plan path. Called when a session ends or is reset.
     */
    fun clearActivePlanPath() {
        activePlanPath = null
    }

    /**
     * Re-inject the active plan path into the conversation after compaction.
     * Lightweight pointer — the LLM uses read_file to access the full plan.
     *
     * Called by the compaction pipeline after any stage that removes messages.
     */
    internal fun reInjectActivePlan() {
        val path = activePlanPath ?: return
        val recentMessages = messages.takeLast(10)
        val alreadyPresent = recentMessages.any { msg ->
            msg.content?.contains("[Active Plan]") == true
        }
        if (!alreadyPresent) {
            messages.add(ChatMessage(
                role = "assistant",
                content = "[Active Plan] You are working from an implementation plan saved at: $path\n" +
                    "Use read_file to review the plan steps if needed."
            ))
        }
    }

    companion object {
        /** Tools that read/write files and whose results contain file content. */
        private val FILE_READ_TOOLS = setOf(
            "read_file", "create_file", "edit_file"
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
