package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.hooks.HookEvent
import com.workflow.orchestrator.agent.hooks.HookManager
import com.workflow.orchestrator.agent.hooks.HookResult
import com.workflow.orchestrator.agent.hooks.HookType
import com.workflow.orchestrator.agent.session.ApiMessage
import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.ModelCatalogService
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ContentPart
import com.workflow.orchestrator.core.ai.dto.hasImageParts
import com.workflow.orchestrator.core.model.ApiResult

/**
 * Context window management with single-stage CC-style LLM summarization.
 *
 * Redesigned from a 3-stage pipeline (dedup → truncation → summarization) to a
 * single-stage compactor modelled after Claude Code's own compactor:
 *
 *   1. Threshold check  — fires at 88% utilization (configurable)
 *   2. PRE_COMPACT hook — cancellable
 *   3. Dedup pre-pass   — replace repeated file reads with placeholder (cheap, ~1ms)
 *   4. Find split       — preserve last 30% of messages
 *   5. LLM summarize    — structured handoff prompt, max 2048 output tokens
 *   6. Replace prefix   — swap the prefix with a single assistant summary message
 *   7. Strip images     — remove image parts (bytes are on disk)
 *   8. Re-inject        — active skill + active plan survive compaction
 *
 * The 70/85/95% banding, Stage 2 truncateConversation, and per-stage gating are
 * all removed. One number, one LLM call, one summary message.
 *
 * See plan: `~/.claude/plans/do-not-start-editing-graceful-beacon.md`
 */
class ContextManager(
    /**
     * Fallback budget used when no [modelCatalogService] + [currentModelRef] are wired.
     */
    val maxInputTokens: Int = 150_000,
    private val compactionThreshold: Double = 0.88,
    /**
     * Callback invoked after compaction modifies the conversation history.
     * The Pair<Int, Int> argument is the DELETED message index range (startIdx, endIdx),
     * NOT a (tokensBefore, tokensAfter) pair — preserves the existing semantic.
     */
    var onHistoryOverwrite: (suspend (List<ChatMessage>, deletedRange: Pair<Int, Int>) -> Unit)? = null,
    private val modelCatalogService: ModelCatalogService? = null,
    private val currentModelRef: (() -> String?)? = null,
) {
    private val LOG = Logger.getInstance(ContextManager::class.java)

    private var systemPrompt: ChatMessage? = null
    private val messages: MutableList<ChatMessage> = mutableListOf()

    /** Last prompt token count reported by the API. Null if no API response yet. */
    private var lastPromptTokens: Int? = null

    /** Token count for tool definitions (schemas sent in API request). Updated by AgentLoop. */
    private var toolDefinitionTokens: Int = 0

    /** Active skill content — set when use_skill tool is called. Survives compaction via re-injection. */
    private var activeSkillContent: String? = null

    /** Path to the saved plan file on disk. Survives compaction via re-injection. */
    private var activePlanPath: String? = null

    /** Summary from the most recent LLM compaction. Fed into the next summarization prompt (chaining). */
    private var previousSummary: String? = null

    /** Optional TaskStore reference for the typed task system. */
    private var taskStore: com.workflow.orchestrator.agent.session.TaskStore? = null

    /**
     * Whether the most recent [compact] call actually ran LLM summarization.
     * The UI reads this to label the post-compaction marker correctly.
     */
    var lastCompactionRanSummary: Boolean = false
        private set

    // ── Task store ───────────────────────────────────────────────────────────

    fun attachTaskStore(store: com.workflow.orchestrator.agent.session.TaskStore) {
        taskStore = store
    }

    /**
     * Render current tasks as a Markdown checklist. Returns null if no store attached or no tasks.
     */
    fun renderTaskProgressMarkdown(): String? {
        val store = taskStore ?: return null
        val tasks = store.listTasks().filter { it.status != TaskStatus.DELETED }
        if (tasks.isEmpty()) return null
        return tasks.joinToString("\n") { t ->
            val box = if (t.status == TaskStatus.COMPLETED) "[x]" else "[ ]"
            "- $box ${t.subject}"
        }
    }

    /**
     * Snapshot of non-deleted tasks for environment_details injection.
     */
    fun currentTasks(): List<Task> {
        val store = taskStore ?: return emptyList()
        return store.listTasks().filter { it.status != TaskStatus.DELETED }
    }

    // ── Message management ───────────────────────────────────────────────────

    fun setSystemPrompt(prompt: String) {
        systemPrompt = ChatMessage(role = "system", content = prompt)
        LOG.info("[Context] System prompt set (${prompt.length} chars, ~${prompt.length / 4} tokens)")
    }

    fun addUserMessage(content: String) {
        messages.add(ChatMessage(role = "user", content = content))
    }

    fun addUserMessageWithParts(parts: List<ContentPart>) {
        val flatText = parts.filterIsInstance<ContentPart.Text>().joinToString(" ") { it.text }
        val images = parts.filterIsInstance<ContentPart.Image>()
        messages.add(
            ChatMessage(
                role = "user",
                content = flatText.ifEmpty { null },
                parts = parts,
            ),
        )
        if (images.isNotEmpty()) {
            LOG.info("[multimodal] ContextManager seeded user turn with ${images.size} image part(s)")
        }
    }

    fun addAssistantMessage(message: ChatMessage) {
        messages.add(message)
    }

    /**
     * Add a tool result. The fileReadIndices tracking removed from here —
     * the index is now built lazily inside [deduplicateFileReads] on demand.
     */
    fun addToolResult(
        toolCallId: String,
        content: String,
        isError: Boolean,
        toolName: String? = null,
        imageRefs: List<ContentBlock.ImageRef> = emptyList(),
    ) {
        val body = if (isError) "[ERROR] $content" else content
        val msg = if (imageRefs.isEmpty()) {
            ChatMessage(role = "tool", content = body, toolCallId = toolCallId)
        } else {
            val parts = buildList<ContentPart> {
                add(ContentPart.Text(body))
                imageRefs.forEach { add(ContentPart.Image(sha256 = it.sha256, mime = it.mime)) }
            }
            ChatMessage(role = "tool", content = body, toolCallId = toolCallId, parts = parts)
        }
        messages.add(msg)
        if (imageRefs.isNotEmpty()) {
            LOG.info("[multimodal] ContextManager seeded tool-result turn with ${imageRefs.size} image part(s) (toolName=$toolName)")
        } else {
            LOG.debug("[Context] Tool result added: $toolCallId (${content.length} chars)")
        }
        // NOTE: fileReadIndices tracking is intentionally NOT done here.
        // The index is built lazily inside deduplicateFileReads() so addToolResult
        // has no side-effect on dedup tracking (dead-tracking fix from redesign plan).
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

    // ── Nudge-pair pruning ───────────────────────────────────────────────────

    fun pruneTrailingNudgePairs(nudgeText: String): Int {
        var pairsRemoved = 0
        var end = messages.size - 2
        while (end >= 1) {
            val user = messages[end]
            val prev = messages[end - 1]
            val isNudge = user.role == "user" && user.content == nudgeText
            val isTextOnlyAssistant = prev.role == "assistant" && prev.toolCalls.isNullOrEmpty()
            if (isNudge && isTextOnlyAssistant) {
                messages.removeAt(end)
                messages.removeAt(end - 1)
                pairsRemoved++
                end -= 2
            } else {
                break
            }
        }
        if (pairsRemoved > 0) {
            LOG.info("[Context] Pruned $pairsRemoved trailing nudge pair(s)")
        }
        return pairsRemoved
    }

    fun pruneAllNudgePairs(nudgeText: String): Int {
        var pairsRemoved = 0
        var i = 1
        while (i < messages.size) {
            val user = messages[i]
            val prev = messages[i - 1]
            val isNudge = user.role == "user" && user.content == nudgeText
            val isTextOnlyAssistant = prev.role == "assistant" && prev.toolCalls.isNullOrEmpty()
            if (isNudge && isTextOnlyAssistant) {
                messages.removeAt(i)
                messages.removeAt(i - 1)
                pairsRemoved++
            } else {
                i++
            }
        }
        if (pairsRemoved > 0) {
            LOG.info("[Context] Pruned $pairsRemoved total nudge pair(s)")
        }
        return pairsRemoved
    }

    fun pruneTrailingEmptyAssistants(): Int {
        var removed = 0
        while (messages.isNotEmpty()) {
            val tail = messages.last()
            val isEmptyAssistant = tail.role == "assistant"
                && com.workflow.orchestrator.core.util.StringUtils.isEffectivelyBlank(tail.content)
                && tail.toolCalls.isNullOrEmpty()
            if (!isEmptyAssistant) break
            messages.removeAt(messages.size - 1)
            removed++
        }
        if (removed > 0) {
            LOG.info("[Context] Pruned $removed trailing empty-assistant turn(s)")
        }
        return removed
    }

    fun collapseLastCompletionToolPair(): Boolean {
        if (messages.size < 2) return false
        val tail = messages.last()
        if (tail.role != "tool") return false
        val toolCallId = tail.toolCallId ?: return false

        val penult = messages[messages.size - 2]
        if (penult.role != "assistant") return false
        val penultToolCalls = penult.toolCalls
        if (penultToolCalls.isNullOrEmpty()) return false
        val matchingCall = penultToolCalls.firstOrNull {
            it.id == toolCallId && it.function.name in COMPLETION_TOOL_NAMES
        } ?: return false

        val resultText = (tail.content ?: "").removePrefix("[ERROR] ")
        val streamingText = penult.content?.takeIf { it.isNotBlank() }
        val combined = when {
            streamingText != null && resultText.isNotBlank() -> "$streamingText\n\n$resultText"
            streamingText != null -> streamingText
            else -> resultText
        }

        messages.removeAt(messages.size - 1)
        messages.removeAt(messages.size - 1)
        messages.add(ChatMessage(role = "assistant", content = combined))
        LOG.info("[Context] Collapsed completion tool pair (toolCallId=$toolCallId, name=${matchingCall.function.name})")
        return true
    }

    // ── Token tracking ───────────────────────────────────────────────────────

    fun updateTokens(promptTokens: Int) {
        lastPromptTokens = promptTokens
        LOG.debug("[Context] Token update: $promptTokens prompt tokens (${"%.1f".format(utilizationPercent())}% of ${effectiveMaxInputTokens()})")
    }

    fun effectiveMaxInputTokens(): Int {
        val ref = currentModelRef?.invoke() ?: return maxInputTokens
        val catalog = modelCatalogService ?: return maxInputTokens
        val window = catalog.getContextWindow(ref) ?: return maxInputTokens
        return window.maxInputTokens
    }

    fun utilizationPercent(): Double {
        val tokens = lastPromptTokens ?: tokenEstimate()
        val max = effectiveMaxInputTokens()
        return (tokens.toDouble() / max) * 100.0
    }

    fun currentInputTokens(): Int = lastPromptTokens ?: tokenEstimate()

    fun maxInputTokensFor(modelRef: String): Int {
        val window = modelCatalogService?.getContextWindow(modelRef, tier = currentTier())
        return window?.maxInputTokens ?: FALLBACK_MAX_INPUT_TOKENS
    }

    private fun currentTier(): String = "enterprise"

    fun shouldCompact(): Boolean = utilizationPercent() > compactionThreshold * 100.0

    fun setToolDefinitionTokens(tokens: Int) {
        toolDefinitionTokens = tokens
        LOG.debug("[Context] Tool definition tokens set: $tokens")
    }

    fun tokenEstimate(): Int {
        var chars = 0
        var imageCount = 0
        systemPrompt?.content?.let { chars += it.length }
        for (msg in messages) {
            msg.content?.let { chars += it.length }
            msg.toolCalls?.forEach { tc ->
                chars += tc.function.name.length
                chars += tc.function.arguments.length
            }
            msg.parts?.forEach { p ->
                if (p is ContentPart.Image) imageCount++
                if (p is ContentPart.Text) chars += p.text.length
            }
        }
        val messageTokens = (chars / 3.5).toInt()
        val imageTokens = imageCount * IMAGE_TOKEN_ESTIMATE_DEFAULT
        val overheadTokens = (messages.size + 1) * 4
        return messageTokens + imageTokens + overheadTokens + toolDefinitionTokens
    }

    fun estimateMessageTokens(msg: ChatMessage): Int {
        var chars = 0
        var imageCount = 0
        msg.content?.let { chars += it.length }
        msg.toolCalls?.forEach { tc ->
            chars += tc.function.name.length
            chars += tc.function.arguments.length
        }
        msg.parts?.forEach { p ->
            if (p is ContentPart.Image) imageCount++
            if (p is ContentPart.Text) chars += p.text.length
        }
        val textTokens = (chars / 3.5).toInt()
        val imageTokens = imageCount * IMAGE_TOKEN_ESTIMATE_DEFAULT
        return textTokens + imageTokens + 4
    }

    fun compactTurn(msg: ChatMessage): ChatMessage =
        if (msg.hasImageParts()) {
            val placeholder = " [image attached earlier; bytes preserved on disk]"
            val newContent = (msg.content ?: "") + placeholder
            msg.copy(parts = null, content = newContent.trim())
        } else {
            msg
        }

    fun messageCount(): Int = messages.size

    // ── Compaction (single-stage CC-style summarizer) ────────────────────────

    /**
     * Single-stage LLM compaction. Eight steps:
     *   1. Threshold check — bail if below 88% and !force
     *   2. PRE_COMPACT hook — bail with Cancelled if hook vetoes
     *   3. Dedup pre-pass — replace repeated file reads with placeholder (no LLM call)
     *   4. Find split — keep last 30% of messages
     *   5. LLM summarize — structured handoff prompt sent to brain
     *   6. Replace prefix — swap the prefix with a single assistant summary message
     *   7. Strip images — remove image parts (bytes remain on disk)
     *   8. Re-inject — active skill + active plan
     *   9. Persist — invalidate tokens, invoke onHistoryOverwrite
     *
     * @return [CompactResult] describing what happened
     */
    suspend fun compact(
        brain: LlmBrain,
        hookManager: HookManager? = null,
        force: Boolean = false,
    ): CompactResult {
        lastCompactionRanSummary = false
        val utilBefore = utilizationPercent()
        val tokensBefore = currentInputTokens()

        // Step 1: threshold check
        if (!force && !shouldCompact()) {
            return CompactResult.Skipped(utilBefore)
        }

        LOG.info("[Context] Compacting at ${"%.1f".format(utilBefore)}% utilization (${messages.size} messages)${if (force) " [forced]" else ""}")

        // Step 2: PRE_COMPACT hook (cancellable)
        if (hookManager != null && hookManager.hasHooks(HookType.PRE_COMPACT)) {
            val hookResult = hookManager.dispatch(
                HookEvent(
                    type = HookType.PRE_COMPACT,
                    data = mapOf(
                        "utilizationPercent" to utilBefore,
                        "messageCount" to messages.size
                    )
                )
            )
            if (hookResult is HookResult.Cancel) {
                LOG.info("[Context] Compaction cancelled by PRE_COMPACT hook: ${hookResult.reason}")
                return CompactResult.Cancelled(hookResult.reason)
            }
        }

        // Step 3: Dedup pre-pass — lazy-build file read index and replace duplicates.
        // Without this, a session that re-reads foo.kt 5 times feeds 5x80K-char tool
        // results into brain.chat(), which can exceed the model's context window.
        deduplicateFileReads()

        // Step 4: Find safe split — preserve last 30% of messages (rounded to role boundary)
        val targetSplitIdx = (messages.size * (1.0 - KEEP_FRACTION)).toInt()
        val splitIdx = findSafeSplitPoint(targetSplitIdx)
        if (splitIdx <= 1) {
            LOG.info("[Context] Not enough history to split (splitIdx=$splitIdx), skipping")
            return CompactResult.Skipped(utilBefore)
        }

        // Step 5: LLM-summarize the prefix
        val prefix = messages.subList(0, splitIdx).toList()
        val summary = summarizePrefix(brain, prefix, previousSummary)
            ?: return CompactResult.Failed("Summarization LLM call returned no content")

        // Step 6: Replace prefix with single assistant message containing summary
        val tail = messages.subList(splitIdx, messages.size).toList()
        messages.clear()
        messages.add(ChatMessage(role = "assistant", content = formatSummaryMessage(summary)))
        messages.addAll(tail)

        previousSummary = summary
        lastCompactionRanSummary = true

        // Step 7: Strip image parts (consolidated into summary text now)
        stripImagePartsFromAllMessages()

        // Step 8: Re-inject active skill + active plan
        reInjectActiveSkill()
        reInjectActivePlan()

        // Step 9: Invalidate token cache and persist
        lastPromptTokens = null
        val tokensAfter = currentInputTokens()

        // NOTE: onHistoryOverwrite Pair<Int, Int> is the DELETED MESSAGE INDEX RANGE
        // (0 to splitIdx), NOT a (tokensBefore, tokensAfter) pair.
        onHistoryOverwrite?.invoke(messages.toList(), 0 to splitIdx)

        LOG.info("[Context] Compacted: ${"%.1f".format(utilBefore)}% → ${"%.1f".format(utilizationPercent())}% ($tokensBefore → $tokensAfter tokens, summary ${summary.length} chars)")
        return CompactResult.Compacted(tokensBefore, tokensAfter, summary.length)
    }

    /**
     * Build the summarization prompt and call the LLM brain.
     *
     * CRITICAL: must include image-part placeholders AND tool-call name fallbacks
     * because msg.content is null for tool-call-only turns and image bytes live in msg.parts.
     */
    private suspend fun summarizePrefix(
        brain: LlmBrain,
        prefix: List<ChatMessage>,
        previousSummary: String?,
    ): String? {
        val promptText = buildString {
            append("Summarize the conversation prefix below into a structured handoff. ")
            append("The summary REPLACES these messages; the agent must continue from it. ")
            append("Capture: TASK (original user intent + sub-goals), FILES (paths touched + role of each), ")
            append("DECISIONS (key choices made and why), STATE (what's done, what's in progress), ")
            append("ERRORS (unresolved errors / blockers), PENDING (next steps).\n")
            append("If any [+N image(s)] markers appear, describe what was relevant about them from context.\n\n")
            if (previousSummary != null) {
                append("PREVIOUS SUMMARY (from prior compaction — fold into the new summary):\n")
                append(previousSummary).append("\n\n")
            }
            append("CONVERSATION PREFIX:\n")
            prefix.forEach { msg ->
                val text = msg.content
                    ?: msg.toolCalls?.firstOrNull()?.function?.name?.let { "(tool_call: $it)" }
                    ?: "(empty)"
                val imageCount = msg.parts?.count { it is ContentPart.Image } ?: 0
                val imageHint = if (imageCount > 0) " [+${imageCount} image(s) attached]" else ""
                append("[${msg.role}] ").append(text).append(imageHint).append("\n\n")
            }
        }

        val summaryMessages = listOf(ChatMessage(role = "user", content = promptText))
        val result = brain.chat(summaryMessages, maxTokens = 2048)

        return when (result) {
            is ApiResult.Success -> result.data.choices.firstOrNull()?.message?.content
            is ApiResult.Error -> {
                LOG.warn("[Context] Summarization LLM call failed: ${result.message}")
                null
            }
        }
    }

    private fun formatSummaryMessage(summary: String): String =
        "[Context Summary — earlier conversation was compacted]\n$summary"

    // ── File-read deduplication (pre-pass, NOT a stage) ──────────────────────

    /**
     * Replace duplicate file read tool results with notices. Lazy-builds the file
     * read index from the current message list on each call — no persistent tracking
     * in addToolResult (dead-tracking fix).
     *
     * For each file path read multiple times, keeps the most recent read intact
     * and replaces earlier reads with a short placeholder notice.
     *
     * @return fraction of characters saved (0.0–1.0), or 0.0 if nothing was deduped
     */
    internal fun deduplicateFileReads(): Double {
        // Lazily build the file read index from current messages
        val fileReadIndices: MutableMap<String, MutableList<Int>> = mutableMapOf()
        for (i in messages.indices) {
            val msg = messages[i]
            val msgContent = msg.content
            if (msg.role == "tool" && !msgContent.isNullOrEmpty()) {
                if (msgContent.startsWith("[File content for ") || msgContent.startsWith("[Result trimmed")) continue
                val toolName = findToolNameForResult(i)
                if (toolName != null) {
                    val filePath = extractFilePathFromToolResult(toolName, msgContent)
                    if (filePath != null) {
                        fileReadIndices.getOrPut(filePath) { mutableListOf() }.add(i)
                    }
                }
            }
        }

        if (fileReadIndices.isEmpty()) return 0.0

        var totalChars = 0L
        var savedChars = 0L
        var anyReplaced = false

        for ((filePath, indices) in fileReadIndices) {
            if (indices.size <= 1) continue

            val toReplace = indices.dropLast(1)
            for (idx in toReplace) {
                if (idx < messages.size) {
                    val old = messages[idx]
                    if (old.hasImageParts()) continue
                    val oldContent = old.content ?: continue
                    val oldLength = oldContent.length
                    totalChars += oldLength

                    val toolNameForMsg = findToolNameForResult(idx)
                    val notice = buildDedupNotice(filePath, toolNameForMsg)
                    savedChars += (oldLength - notice.length)
                    anyReplaced = true
                    messages[idx] = old.copy(content = notice)
                }
            }
            val lastIdx = indices.last()
            if (lastIdx < messages.size) {
                messages[lastIdx].content?.let { totalChars += it.length }
            }
        }

        if (!anyReplaced) return 0.0
        return if (totalChars == 0L) 0.0 else maxOf(savedChars.toDouble() / totalChars.toDouble(), 0.01)
    }

    // ── Sliding window (fallback for CompactResult.Failed call sites) ─────────

    /**
     * Keep only the most recent fraction of messages. Used as a hard-truncation
     * safety net at every compaction call site when compact() returns Failed.
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

    // ── Trim old tool results ─────────────────────────────────────────────────

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

    // ── Safe split point ──────────────────────────────────────────────────────

    /**
     * Find a safe split point near [targetIndex] that biases to a user-role
     * message boundary to avoid splitting tool_call/result pairs.
     *
     * Searches forward first (toward end) for a user message, then backward.
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

    // ── Image helpers ─────────────────────────────────────────────────────────

    private fun stripImagePartsFromAllMessages() {
        var changed = false
        for (i in messages.indices) {
            val original = messages[i]
            if (original.hasImageParts()) {
                messages[i] = compactTurn(original)
                changed = true
            }
        }
        if (changed) {
            lastPromptTokens = null
            LOG.info("[Context] Stripped image parts from compacted messages")
        }
    }

    // ── Skill management ──────────────────────────────────────────────────────

    fun setActiveSkill(content: String) {
        activeSkillContent = content
    }

    fun getActiveSkill(): String? = activeSkillContent

    fun clearActiveSkill() {
        activeSkillContent = null
    }

    internal fun reInjectActiveSkill() {
        val skill = activeSkillContent ?: return
        val recentMessages = messages.takeLast(10)
        val alreadyPresent = recentMessages.any { msg ->
            msg.content?.contains("[Active Skill]") == true
        }
        if (!alreadyPresent) {
            messages.add(
                ChatMessage(
                    role = "assistant",
                    content = "[Active Skill] The following skill instructions are still active:\n\n$skill"
                )
            )
        }
    }

    // ── Plan management ───────────────────────────────────────────────────────

    fun setActivePlanPath(path: String) {
        activePlanPath = path
    }

    fun getActivePlanPath(): String? = activePlanPath

    fun clearActivePlanPath() {
        activePlanPath = null
    }

    internal fun reInjectActivePlan() {
        val path = activePlanPath ?: return
        val recentMessages = messages.takeLast(10)
        val alreadyPresent = recentMessages.any { msg ->
            msg.content?.contains("[Active Plan]") == true
        }
        if (!alreadyPresent) {
            messages.add(
                ChatMessage(
                    role = "assistant",
                    content = "[Active Plan] You are working from an implementation plan saved at: $path\n" +
                        "Use read_file to review the plan steps if needed."
                )
            )
        }
    }

    // ── Export / restore ──────────────────────────────────────────────────────

    fun exportMessages(): List<ChatMessage> = messages.toList()

    fun restoreMessages(savedMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(savedMessages)
        lastPromptTokens = null
        LOG.info("[Context] Restored ${savedMessages.size} messages")
    }

    fun clearMessages() {
        messages.clear()
        previousSummary = null
        lastPromptTokens = null
        LOG.info("[Context] Messages cleared")
    }

    fun getSystemPromptContent(): String? = systemPrompt?.content

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun extractFilePathFromToolResult(toolName: String, content: String): String? {
        if (toolName !in FILE_READ_TOOLS) return null
        val headerMatch = TOOL_RESULT_HEADER.find(content)
        if (headerMatch != null) return headerMatch.groupValues[2]
        val tagMatch = FILE_CONTENT_TAG.find(content)
        if (tagMatch != null) return tagMatch.groupValues[1]
        return null
    }

    private fun buildDedupNotice(filePath: String, toolName: String?): String {
        val nameHint = if (toolName != null) " ($toolName)" else ""
        return "[File content for '$filePath' was previously read$nameHint — see latest read below]"
    }

    private fun findToolNameForResult(toolResultIndex: Int): String? {
        val toolMsg = messages.getOrNull(toolResultIndex) ?: return null
        val targetId = toolMsg.toolCallId ?: return null
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

    // ── CompactResult sealed class ────────────────────────────────────────────

    sealed class CompactResult {
        /** Compaction was not needed — utilization below threshold. */
        data class Skipped(val utilizationPercent: Double) : CompactResult()

        /** PRE_COMPACT hook vetoed the compaction. */
        data class Cancelled(val reason: String) : CompactResult()

        /** LLM summarization call failed or returned null content. */
        data class Failed(val reason: String) : CompactResult()

        /** Compaction succeeded: prefix replaced with a summary message. */
        data class Compacted(
            val tokensBefore: Int,
            val tokensAfter: Int,
            val summaryChars: Int,
        ) : CompactResult()
    }

    // ── Companion object ──────────────────────────────────────────────────────

    companion object {

        fun isImageBearingMessage(message: ApiMessage): Boolean =
            message.content.any { it is ContentBlock.ImageRef }

        /** Conservative fallback for maxInputTokensFor when the catalog is unavailable. */
        const val FALLBACK_MAX_INPUT_TOKENS = 90_000

        /** Per-image token estimate used by tokenEstimate. */
        const val IMAGE_TOKEN_ESTIMATE_DEFAULT = 1500

        /**
         * Tools that signal the loop's exit (attempt_completion, task_report).
         * Used by collapseLastCompletionToolPair.
         */
        val COMPLETION_TOOL_NAMES: Set<String> = setOf("attempt_completion", "task_report")

        /** Fraction of messages kept from the tail during compaction (last 30%). */
        private const val KEEP_FRACTION = 0.30

        /** Tools that read/write files and whose results contain file content. */
        private val FILE_READ_TOOLS = setOf("read_file", "create_file", "edit_file")

        /** Matches "[tool_name for '/path/to/file'] Result:" header format. */
        private val TOOL_RESULT_HEADER = Regex("""^\[(\S+) for '([^']+)'] Result:""")

        /** Matches <file_content path="...">...</file_content> tags. */
        private val FILE_CONTENT_TAG = Regex("""<file_content path="([^"]*?)">""")
    }
}
