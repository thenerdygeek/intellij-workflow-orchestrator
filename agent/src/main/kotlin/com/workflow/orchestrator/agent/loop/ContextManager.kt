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
    private val effectiveContextWindow: com.workflow.orchestrator.agent.model.EffectiveContextWindow? = null,
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

    /**
     * Provider that returns the list of session documents (user attachments + tool downloads)
     * available this session. Invoked after compaction to re-inject an exact-path manifest so
     * the LLM never has to guess or reconstruct a path from a filename.
     */
    private var sessionDocumentsProvider: (() -> List<com.workflow.orchestrator.agent.session.SessionDocument>)? = null

    /**
     * Summary of pre-user prefix from the most recent compaction. Reused verbatim in Case B
     * (no new user message since last compaction); folded into the new L1 prompt in Case A.
     */
    private var previousPreUserSummary: String? = null

    /**
     * Summary of post-user working memory from the most recent compaction. Folded into the
     * new L3 prompt (Case A summarizes from scratch; Case B chains over this).
     */
    private var previousPostUserSummary: String? = null

    /** Monotonic counter incremented every time a user message is added. */
    private var totalUserMessageCount: Int = 0

    /**
     * Snapshot of [totalUserMessageCount] at the end of the previous compaction.
     * Null until the first compaction completes. Equal to [totalUserMessageCount] →
     * Case B (no new user since last compaction). Less than → Case A.
     */
    private var lastCompactionUserMessageCount: Int? = null

    // Test-only accessors (internal — visible from same module's test classes).
    internal fun getTotalUserMessageCountForTest(): Int = totalUserMessageCount
    internal fun getPreviousPreUserSummaryForTest(): String? = previousPreUserSummary
    internal fun getPreviousPostUserSummaryForTest(): String? = previousPostUserSummary
    internal fun getLastCompactionUserMessageCountForTest(): Int? = lastCompactionUserMessageCount

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
     * Per-background-process read cursor (byte offset) for environment_details. Lets the
     * "Actively Running Processes" section surface only the NEW output produced since the
     * previous turn. Session-scoped (this ContextManager is per-session).
     */
    private val backgroundOutputCursors = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Last byte offset already surfaced for [bgId] in environment_details (0 if never). */
    fun backgroundOutputCursor(bgId: String): Long = backgroundOutputCursors[bgId] ?: 0L

    /** Records the byte offset surfaced for [bgId] so the next turn shows only the delta. */
    fun setBackgroundOutputCursor(bgId: String, offset: Long) {
        backgroundOutputCursors[bgId] = offset
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
        totalUserMessageCount++
    }

    // --- Time stamping (decoupled from environment_details so it survives env dedup) ---

    /** Injectable wall clock (epoch millis). Overridable in tests. */
    internal var clockMillis: () -> Long = { System.currentTimeMillis() }
    private var lastStampedEpochMinute: Long = Long.MIN_VALUE

    /** Matches a full `<environment_details>…</environment_details>` block plus leading blank lines. */
    private val ENV_DETAILS_REGEX = Regex("(?s)\\n*<environment_details>.*?</environment_details>")

    private fun formatTimeLine(epochMillis: Long): String {
        val zdt = java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault())
        val formatted = zdt.format(java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy, h:mm:ss a"))
        return "Current time: $formatted (${zdt.zone.id}, UTC${zdt.offset})"
    }

    /**
     * Full datetime stamp for a REAL user message — always emitted and retained.
     * Records the current wall-clock minute as the new baseline so a tool result in the
     * same minute is not re-stamped.
     */
    fun userTimeStamp(): String {
        val now = clockMillis()
        lastStampedEpochMinute = now / 60_000
        return formatTimeLine(now)
    }

    /**
     * Full datetime stamp for a tool result — emitted ONLY when the wall-clock minute has
     * advanced past the last stamp (user message or prior tool result). Returns null within
     * the same minute, so rapid successive tool calls don't repeat the timestamp.
     */
    fun toolResultTimeStampOrNull(): String? {
        val now = clockMillis()
        val minute = now / 60_000
        if (minute <= lastStampedEpochMinute) return null
        lastStampedEpochMinute = minute
        return formatTimeLine(now)
    }

    /**
     * Removes `<environment_details>…</environment_details>` blocks from ALL existing
     * messages, retaining the surrounding user text + time stamp. Called right before a new
     * user turn appends a fresh env block, so only the latest block ever survives in history
     * (dedup). Persists the edit via [onHistoryOverwrite] (full rewrite, same as compaction);
     * indices are unchanged (content edit, not removal), so ui_messages mapping stays valid.
     * No-op (no persist) when nothing carried an env block.
     */
    suspend fun stripStaleEnvironmentDetails() {
        var changed = false
        for (i in messages.indices) {
            val m = messages[i]
            val newContent = stripEnvBlock(m.content)
            val newParts = m.parts?.map { p ->
                if (p is ContentPart.Text) ContentPart.Text(stripEnvBlock(p.text) ?: "") else p
            }
            if (newContent != m.content || newParts != m.parts) {
                messages[i] = m.copy(content = newContent, parts = newParts)
                changed = true
            }
        }
        if (changed) onHistoryOverwrite?.invoke(messages.toList(), 0 to 0)
    }

    /** Strips env blocks from [text]; returns the original if it had none, or null in/out. */
    private fun stripEnvBlock(text: String?): String? {
        if (text == null || "<environment_details>" !in text) return text
        val stripped = ENV_DETAILS_REGEX.replace(text, "").trimEnd()
        // Defensive: never blank out a message entirely (user turns always have text + time).
        return if (stripped.isBlank()) text else stripped
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
        totalUserMessageCount++
        if (images.isNotEmpty()) {
            LOG.info("[multimodal] ContextManager seeded user turn with ${images.size} image part(s)")
        }
    }

    /**
     * Add a user-role-shaped message for internal agent-loop nudges (recovery hints,
     * format reminders, doom-loop warnings, length-cut retries, etc.) — NOT real user
     * input.
     *
     * Functionally identical to [addUserMessage] except it does NOT increment
     * [totalUserMessageCount]. The Case B detector in [compact] uses that counter as
     * "did the user speak since the last compaction" — synthetic nudges must not
     * inflate it, or Case B will silently degrade to Case A and re-run L1 LLM calls
     * on long-running sessions.
     */
    fun addNudgeMessage(content: String) {
        messages.add(ChatMessage(role = "user", content = content))
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

        val penult = messages[messages.size - 2]
        if (penult.role != "assistant") return false

        // Legacy path (pre-2026-05-13): structured ToolCall blocks with a matching id.
        val toolCallId = tail.toolCallId
        val penultToolCalls = penult.toolCalls
        val matchingCallName: String? = if (!penultToolCalls.isNullOrEmpty() && toolCallId != null) {
            penultToolCalls.firstOrNull {
                it.id == toolCallId && it.function.name in COMPLETION_TOOL_NAMES
            }?.function?.name
        } else null

        // New-shape path (post-2026-05-13 XML-in-content migration): tool calls are
        // embedded as XML inside the assistant's text content; the toolCalls field is
        // always null/empty. Check the content string for the XML tag instead.
        val matchingXmlName: String? = if (matchingCallName == null) {
            val assistantContent = penult.content ?: ""
            COMPLETION_TOOL_NAMES.firstOrNull { name ->
                assistantContent.contains("<$name>") || assistantContent.contains("<$name ")
            }
        } else null

        val resolvedName = matchingCallName ?: matchingXmlName ?: return false

        val resultText = (tail.content ?: "").removePrefix("[ERROR] ")
        val rawStreamingText = penult.content?.trim() ?: ""
        // For the XML-in-content path, strip the tool call XML so the collapsed
        // message only carries prose (the XML is ephemeral scaffolding).
        val streamingText = if (matchingXmlName != null && rawStreamingText.isNotBlank()) {
            stripXmlToolCall(rawStreamingText, matchingXmlName)
        } else {
            rawStreamingText.takeIf { it.isNotBlank() }
        }

        val combined = when {
            streamingText != null && resultText.isNotBlank() -> "$streamingText\n\n$resultText"
            streamingText != null -> streamingText
            else -> resultText
        }

        messages.removeAt(messages.size - 1)
        messages.removeAt(messages.size - 1)
        messages.add(ChatMessage(role = "assistant", content = combined))
        LOG.info("[Context] Collapsed completion tool pair (resolvedName=$resolvedName)")
        return true
    }

    /**
     * Strip a single XML tool call invocation block from [text].
     * Removes `<toolName>…</toolName>` (and everything between) from the text.
     * Mirrors [com.workflow.orchestrator.agent.session.MessageStateHandler.stripXmlToolCall].
     */
    private fun stripXmlToolCall(text: String, toolName: String): String? {
        val open = "<$toolName>"
        val openAttr = "<$toolName "
        val close = "</$toolName>"
        val startIdx = text.indexOf(open).takeIf { it >= 0 }
            ?: text.indexOf(openAttr).takeIf { it >= 0 }
            ?: return text.takeIf { it.isNotBlank() }
        val endIdx = text.indexOf(close, startIdx)
        val prose = if (endIdx >= 0) {
            text.removeRange(startIdx, endIdx + close.length)
        } else {
            text.substring(0, startIdx)
        }.trim()
        return prose.takeIf { it.isNotBlank() }
    }

    // ── Token tracking ───────────────────────────────────────────────────────

    fun updateTokens(promptTokens: Int) {
        lastPromptTokens = promptTokens
        LOG.debug("[Context] Token update: $promptTokens prompt tokens (${"%.1f".format(utilizationPercent())}% of ${effectiveMaxInputTokens()})")
    }

    fun effectiveMaxInputTokens(): Int {
        // RUNTIME key = the running model (currentModelRef = currentBrainModelId). Honors overrides
        // for the model the brain is actually calling — correct under L2 tier escalation.
        effectiveContextWindow?.let { return it.maxInputTokens(currentModelRef?.invoke()) }
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
        // Route through the resolver so per-model/global overrides apply (not a catalog-only bypass).
        effectiveContextWindow?.let { return it.maxInputTokens(modelRef) }
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

    // ── Compaction (two-tier layered summarizer) ────────────────────────────

    /**
     * Two-tier compaction. See docs/superpowers/specs/2026-05-18-context-compaction-two-tier-design.md.
     *
     * Layered post-compaction structure:
     *   [L1 assistant] pre-user handoff summary (omitted if pre-user prefix is empty)
     *   [L2 user]      the most recent user message, verbatim
     *   [L3 assistant] post-user working memory summary (omitted if iterations <= 5 and util OK)
     *   [L4 tool/...]  most recent ~20% of post-user tokens, verbatim
     *
     * @param iterationsSinceLastUser agent-loop iterations since the most recent user/steering
     *   message. Defaults to Int.MAX_VALUE so manual triggers (Compact button, programmatic) always
     *   allow L3 to be built. The 5-iteration gate is a cost optimization; when post-L1 utilization
     *   would still be >= 88%, L3 is forced regardless.
     */
    suspend fun compact(
        brain: LlmBrain,
        hookManager: HookManager? = null,
        force: Boolean = false,
        iterationsSinceLastUser: Int = Int.MAX_VALUE,
    ): CompactResult {
        lastCompactionRanSummary = false
        val utilBefore = utilizationPercent()
        val tokensBefore = currentInputTokens()

        // Step 1: threshold check
        if (!force && !shouldCompact()) {
            return CompactResult.Skipped(utilBefore)
        }

        LOG.info("[Context] Compacting at ${"%.1f".format(utilBefore)}% utilization (${messages.size} messages, iters=$iterationsSinceLastUser)${if (force) " [forced]" else ""}")

        // Step 2: PRE_COMPACT hook (cancellable)
        if (hookManager != null && hookManager.hasHooks(HookType.PRE_COMPACT)) {
            val hookResult = hookManager.dispatch(
                HookEvent(
                    type = HookType.PRE_COMPACT,
                    data = mapOf(
                        "utilizationPercent" to utilBefore,
                        "messageCount" to messages.size,
                    ),
                ),
            )
            if (hookResult is HookResult.Cancel) {
                LOG.info("[Context] Compaction cancelled by PRE_COMPACT hook: ${hookResult.reason}")
                return CompactResult.Cancelled(hookResult.reason)
            }
        }

        // Step 3: Dedup pre-pass — collapses repeated file reads before LLM input
        deduplicateFileReads()

        // Step 4: Find last user message
        val lastUserIdx = findLastUserIndex()
        if (lastUserIdx == -1) {
            // Degenerate: no user message in history → fall back to single-summary path
            return compactDegenerate(brain, utilBefore, tokensBefore)
        }

        // Step 5: Case A vs Case B detection
        val isCaseB = lastCompactionUserMessageCount != null
                && totalUserMessageCount == lastCompactionUserMessageCount

        // Step 6: Build L1
        val prefix = messages.subList(0, lastUserIdx).toList()
        var l1Failed = false
        val l1Content: String? = when {
            prefix.isEmpty() -> null  // skip — no L1 needed
            isCaseB && previousPreUserSummary != null -> previousPreUserSummary
            else -> {
                val summary = summarizePreUser(brain, prefix, previousPreUserSummary)
                if (summary == null) l1Failed = true
                summary
            }
        }

        // Step 7: L2 = last user message
        val l2 = messages[lastUserIdx]

        // Step 8: Decide whether to build L3 + L4
        val postUserStart = lastUserIdx + 1
        val postUserSlice = messages.subList(postUserStart, messages.size).toList()
        val l1EstTokens = if (l1Content != null) 2048 else 0
        val postL1EstTokens = l1EstTokens + postUserSlice.sumOf { estimateMessageTokens(it) }
        val postL1Utilization = (postL1EstTokens.toDouble() / effectiveMaxInputTokens()) * 100.0
        val needsL3 = (iterationsSinceLastUser > 5) || (postL1Utilization >= 88.0)
        val buildL3 = needsL3 && postUserSlice.isNotEmpty()

        // Step 9: Build L3 + L4
        var l3Failed = false
        var l3Content: String? = null
        var cutIdx = messages.size
        if (buildL3) {
            val targetTokensInL4 = (0.20 * effectiveMaxInputTokens()).toInt()
            val rawCutIdx = findTokenWeightedCutForLayer4(
                sliceStart = postUserStart,
                sliceEnd = messages.size,
                targetTokensFromEnd = targetTokensInL4,
            )
            val snappedCutIdx = snapToToolBoundary(rawCutIdx, sliceStart = postUserStart)
            if (snappedCutIdx < messages.size) {
                cutIdx = snappedCutIdx
                val l3Input = messages.subList(postUserStart, cutIdx).toList()
                if (l3Input.isNotEmpty()) {
                    val summary = summarizePostUser(brain, l3Input, previousPostUserSummary)
                    if (summary == null) {
                        l3Failed = true
                    } else {
                        l3Content = summary
                    }
                }
            }
        }

        // Step 10: Reassemble
        val l4 = if (l3Content != null) messages.subList(cutIdx, messages.size).toList()
                 else postUserSlice
        messages.clear()
        if (l1Content != null) messages.add(ChatMessage(role = "assistant", content = formatPreUserSummary(l1Content)))
        messages.add(l2)
        if (l3Content != null) messages.add(ChatMessage(role = "assistant", content = formatPostUserSummary(l3Content)))
        messages.addAll(l4)

        // Step 11: Post-summary cleanup (unchanged behavior)
        stripImagePartsFromAllMessages()
        reInjectActiveSkill()
        reInjectActivePlan()
        reInjectSessionDocuments()

        // Step 12: Save state for next compaction
        previousPreUserSummary = l1Content ?: previousPreUserSummary
        previousPostUserSummary = l3Content ?: previousPostUserSummary
        lastCompactionUserMessageCount = totalUserMessageCount
        lastCompactionRanSummary = (l1Content != null && !isCaseB) || l3Content != null

        // Step 13: Invalidate token cache and persist
        lastPromptTokens = null
        val tokensAfter = currentInputTokens()
        val deletedRangeEnd = if (l3Content != null) cutIdx else lastUserIdx
        onHistoryOverwrite?.invoke(messages.toList(), 0 to deletedRangeEnd)

        LOG.info("[Context] Compacted: ${"%.1f".format(utilBefore)}% → ${"%.1f".format(utilizationPercent())}% ($tokensBefore → $tokensAfter tokens, L1=${l1Content?.length ?: 0} chars, L3=${l3Content?.length ?: 0} chars, case=${if (isCaseB) "B" else "A"})")

        // Step 14: Result — if at least one summarization was attempted and none succeeded, fail
        val anySummarizationAttempted = l1Failed || l3Failed
        val anySummarizationSucceeded = l1Content != null || l3Content != null
        return if (anySummarizationAttempted && !anySummarizationSucceeded) {
            CompactResult.Failed("Summarization failed (L1=${if (l1Failed) "failed" else "ok"}, L3=${if (l3Failed) "failed" else "skipped/ok"})")
        } else {
            CompactResult.Compacted(tokensBefore, tokensAfter, (l1Content?.length ?: 0) + (l3Content?.length ?: 0))
        }
    }

    /**
     * Degenerate fallback for histories with no user message at all.
     * Summarize everything into a single assistant message; matches the no-user-anchor
     * pathology fallback described in the spec's "Edge cases" table.
     */
    private suspend fun compactDegenerate(
        brain: LlmBrain,
        utilBefore: Double,
        tokensBefore: Int,
    ): CompactResult {
        if (messages.isEmpty()) return CompactResult.Skipped(utilBefore)
        val all = messages.toList()
        val summary = summarizePreUser(brain, all, previousPreUserSummary)
            ?: return CompactResult.Failed("Degenerate-path summarization failed")
        messages.clear()
        messages.add(ChatMessage(role = "assistant", content = formatPreUserSummary(summary)))
        stripImagePartsFromAllMessages()
        reInjectActiveSkill()
        reInjectActivePlan()
        reInjectSessionDocuments()
        previousPreUserSummary = summary
        lastCompactionUserMessageCount = totalUserMessageCount
        lastCompactionRanSummary = true
        lastPromptTokens = null
        val tokensAfter = currentInputTokens()
        onHistoryOverwrite?.invoke(messages.toList(), 0 to all.size)
        LOG.info("[Context] Compacted (degenerate, no user): ${"%.1f".format(utilBefore)}% → ${"%.1f".format(utilizationPercent())}% ($tokensBefore → $tokensAfter tokens)")
        return CompactResult.Compacted(tokensBefore, tokensAfter, summary.length)
    }

    /**
     * Summarize the pre-user prefix into a long-term context handoff.
     * The result replaces messages before the most recent user message.
     * Returns null on empty prefix (no LLM call) or on LLM error.
     */
    private suspend fun summarizePreUser(
        brain: LlmBrain,
        prefix: List<ChatMessage>,
        priorSummary: String?,
    ): String? {
        if (prefix.isEmpty()) return null
        val promptText = buildString {
            append("You are summarizing the conversation BEFORE the user's most recent instruction. ")
            append("The summary REPLACES these messages; the agent must continue from it as a handoff. ")
            append("Capture: TASK (original user intent + sub-goals), FILES (paths touched + role of each), ")
            append("DECISIONS (key choices made and why), STATE (what's done, what's in progress), ")
            append("ERRORS (unresolved errors / blockers), PENDING (next steps).\n")
            append("If any [+N image(s)] markers appear, describe what was relevant about them from context.\n\n")
            if (priorSummary != null) {
                append("PRIOR PRE-USER SUMMARY (from earlier compaction — fold into the new summary):\n")
                append(priorSummary).append("\n\n")
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
        return invokeSummaryBrain(brain, promptText)
    }

    /**
     * Summarize the post-user working memory slice into a compressed working-state turn.
     * The result replaces L3 in the rebuilt history. Folds [priorPostSummary] into the
     * prompt so Case B re-compactions preserve continuity.
     * Returns null on empty slice or on LLM error.
     */
    private suspend fun summarizePostUser(
        brain: LlmBrain,
        slice: List<ChatMessage>,
        priorPostSummary: String?,
    ): String? {
        if (slice.isEmpty()) return null
        val promptText = buildString {
            append("You are summarizing the agent's work-so-far in response to the user's most recent instruction. ")
            append("The summary REPLACES these messages as working memory; the agent must continue from it. ")
            append("Capture: WORK (what tool calls were made and to what effect), ")
            append("FILES (paths read/modified + role of each), ")
            append("FINDINGS (key facts learned), DECISIONS (choices made and why), ")
            append("ERRORS (unresolved errors / blockers), PENDING (next steps).\n")
            append("If any [+N image(s)] markers appear, describe what was relevant about them from context.\n\n")
            if (priorPostSummary != null) {
                append("PRIOR POST-USER SUMMARY (from earlier compaction — fold into the new summary):\n")
                append(priorPostSummary).append("\n\n")
            }
            append("CONVERSATION SLICE:\n")
            slice.forEach { msg ->
                val text = msg.content
                    ?: msg.toolCalls?.firstOrNull()?.function?.name?.let { "(tool_call: $it)" }
                    ?: "(empty)"
                val imageCount = msg.parts?.count { it is ContentPart.Image } ?: 0
                val imageHint = if (imageCount > 0) " [+${imageCount} image(s) attached]" else ""
                append("[${msg.role}] ").append(text).append(imageHint).append("\n\n")
            }
        }
        return invokeSummaryBrain(brain, promptText)
    }

    /** Shared LLM-invocation path for both summarizers. */
    private suspend fun invokeSummaryBrain(brain: LlmBrain, promptText: String): String? {
        val summaryMessages = listOf(ChatMessage(role = "user", content = promptText))
        return when (val result = brain.chat(summaryMessages, maxTokens = 2048)) {
            is ApiResult.Success -> result.data.choices.firstOrNull()?.message?.content
            is ApiResult.Error -> {
                LOG.warn("[Context] Summarization LLM call failed: ${result.message}")
                null
            }
        }
    }

    private fun formatPreUserSummary(summary: String): String =
        "[Context Handoff — earlier conversation was compacted]\n$summary"

    private fun formatPostUserSummary(summary: String): String =
        "[Working Memory — agent activity since the user's last message was compacted]\n$summary"

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
     *
     * When the history has no `user`-role message (the degenerate case that
     * triggers [compactDegenerate] and can also arise when compactDegenerate
     * itself fails) [findSafeSplitPoint] returns `messages.size` and nothing
     * would be removed.  In that case we fall back to a role-agnostic
     * keep-last-N cut so an all-assistant history over 88% utilization still
     * gets trimmed rather than soft-looping until max-iterations (F-22).
     */
    fun slidingWindow(keepFraction: Double = 0.3) {
        val keepCount = maxOf(1, (messages.size * keepFraction).toInt())
        val rawSplitPoint = messages.size - keepCount
        if (rawSplitPoint <= 0) return
        val safeSplitPoint = findSafeSplitPoint(rawSplitPoint)
        if (safeSplitPoint > 0 && safeSplitPoint < messages.size) {
            messages.subList(0, safeSplitPoint).clear()
        } else {
            // Degenerate case: no user message found by findSafeSplitPoint.
            // Role-agnostic tail-keep: unconditionally drop the oldest messages
            // so the context-overflow loop makes progress on the next iteration.
            messages.subList(0, rawSplitPoint).clear()
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

    // ── Two-tier split helpers ────────────────────────────────────────────────

    /**
     * Scan messages backward, return the index of the most recent `role == "user"` message.
     * Returns -1 if no user message exists.
     *
     * Pure function — does not mutate messages.
     */
    internal fun findLastUserIndex(): Int {
        for (i in messages.indices.reversed()) {
            if (messages[i].role == "user") return i
        }
        return -1
    }

    /**
     * Walk backward from [sliceEnd] (exclusive), accumulating estimateMessageTokens(msg).
     * Return the lowest index where the running sum >= [targetTokensFromEnd].
     * Clamped: result is always >= [sliceStart], so L4 never extends into L2 or earlier.
     *
     * Pure function — does not mutate messages.
     */
    internal fun findTokenWeightedCutForLayer4(
        sliceStart: Int,
        sliceEnd: Int,
        targetTokensFromEnd: Int,
    ): Int {
        if (sliceEnd <= sliceStart) return sliceEnd
        var runningTokens = 0
        var idx = sliceEnd
        while (idx > sliceStart) {
            idx--
            runningTokens += estimateMessageTokens(messages[idx])
            if (runningTokens >= targetTokensFromEnd) return idx
        }
        return sliceStart
    }

    /**
     * Adjust [candidateIdx] so the first message of Layer 4 is NOT an assistant turn.
     * The Sourcegraph client (MessageSanitizer.kt:70) merges consecutive same-role turns,
     * which would silently merge L3's assistant summary with an assistant-first L4.
     *
     * If messages[candidateIdx].role == "assistant", walk backward looking for a `tool`
     * message. Return that tool's index.
     *
     * If we reach [sliceStart] without finding a tool (slice is all-assistant after sliceStart),
     * return messages.size as a sentinel — the caller treats this as "skip L3 entirely."
     *
     * Pure function — does not mutate messages.
     */
    internal fun snapToToolBoundary(candidateIdx: Int, sliceStart: Int): Int {
        if (candidateIdx >= messages.size) return messages.size
        if (messages[candidateIdx].role != "assistant") return candidateIdx
        var idx = candidateIdx
        while (idx > sliceStart) {
            idx--
            if (messages[idx].role == "tool") return idx
        }
        return messages.size  // sentinel: all-assistant slice, skip L3
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

    // ── Session Documents ─────────────────────────────────────────────────────

    fun setSessionDocumentsProvider(provider: () -> List<com.workflow.orchestrator.agent.session.SessionDocument>) {
        sessionDocumentsProvider = provider
    }

    /**
     * Builds the session-documents manifest message body, or null if no provider is set or
     * it returns an empty list. Made [internal] so the test can call it directly as a seam.
     */
    internal fun buildSessionDocumentsMessage(): String? {
        val docs = sessionDocumentsProvider?.invoke().orEmpty()
        if (docs.isEmpty()) return null
        return buildString {
            append("[Session Documents] Files available to read this session. Use these EXACT absolute paths ")
            append("with read_document / read_file — do NOT guess or reconstruct a path from a filename:\n")
            docs.forEach { append("- ${it.displayName} → ${it.absolutePath}\n") }
        }
    }

    internal fun reInjectSessionDocuments() {
        val content = buildSessionDocumentsMessage() ?: return
        val recentMessages = messages.takeLast(10)
        val alreadyPresent = recentMessages.any { msg ->
            msg.content?.contains("[Session Documents]") == true
        }
        if (!alreadyPresent) {
            messages.add(ChatMessage(role = "assistant", content = content))
        }
    }

    // ── Export / restore ──────────────────────────────────────────────────────

    fun exportMessages(): List<ChatMessage> = messages.toList()

    fun restoreMessages(savedMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(savedMessages)
        lastPromptTokens = null
        // Two-tier compaction state — recompute counter from history, clear chained summaries.
        totalUserMessageCount = savedMessages.count { it.role == "user" }
        lastCompactionUserMessageCount = null
        previousPreUserSummary = null
        previousPostUserSummary = null
        LOG.info("[Context] Restored ${savedMessages.size} messages, totalUserMessageCount=$totalUserMessageCount")
    }

    fun clearMessages() {
        messages.clear()
        previousPreUserSummary = null
        previousPostUserSummary = null
        totalUserMessageCount = 0
        lastCompactionUserMessageCount = null
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
