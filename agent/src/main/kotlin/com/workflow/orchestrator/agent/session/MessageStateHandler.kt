package com.workflow.orchestrator.agent.session

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.util.StringUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import java.io.File

/**
 * Persisted wrapper around the api_conversation_history.json messages list.
 *
 * The pre-Phase-4 on-disk shape was a bare JSON array `[ApiMessage, ...]`.
 * Phase 4 introduces this wrapper with a [schemaVersion] field so future
 * schema changes can be detected at read time.
 *
 * The reader tries this wrapper first, then falls back to the bare array
 * for legacy v1 sessions. The writer always emits this wrapper at
 * `schemaVersion = 2`.
 *
 * Phase 4 of multimodal-agent plan.
 */
@Serializable
data class ApiHistoryFile(
    val schemaVersion: Int = 1,
    val messages: List<ApiMessage>,
)

class MessageStateHandler(
    private val baseDir: File,
    val sessionId: String,
    val taskText: String,
    /** Clock seam for the partial-save + global-index throttles (deterministic tests); production default. */
    private val uiSaveClock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    // Delegate to the shared, polymorphic-fallback-enabled Json instance in the
    // companion object so reads and writes share the same defensive deserialization
    // contract. See `configuredJson` below for the full configuration.
    private val json = configuredJson
    private val prettyJson = configuredPrettyJson

    private val uiMessages: MutableList<UiMessage> = mutableListOf()
    private val apiHistory: MutableList<ApiMessage> = mutableListOf()

    /**
     * Sealed once the handler is published for concurrent access.
     * Set to `true` before assigning `activeMessageStateHandler` in `AgentService`.
     * After that point, [setClineMessages] and [setApiConversationHistory] must NOT be
     * called — the `@GuardedBy("init-thread-only")` contract is enforced at runtime.
     */
    @Volatile private var published: Boolean = false

    /**
     * One-shot flag: true once [DialectDriftDetector] has flagged drift in this
     * session (either at write-time on a new assistant turn, or during the
     * one-pass [redactDialectXmlInHistory] on resume / retry). Consumed by
     * [consumeDialectDriftFlag] when building the next system prompt so the
     * `<system-reminder>` is injected exactly once per drift event.
     */
    private val dialectDriftFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Throttle global index updates to at most once per second during streaming. */
    @Volatile private var lastGlobalIndexUpdateMs = 0L
    @Volatile private var globalIndexDirty = false
    private val globalIndexThrottleMs = 1000L

    /** P0-1: throttle PARTIAL streaming UI saves to one disk write per window; memory stays authoritative. */
    @Volatile private var lastUiSaveMs = 0L

    /** P1-4: running totals for the global index — incremental on append, recomputed on bulk rewrites. */
    @Volatile private var totalTokensInCached = 0L

    @Volatile private var totalTokensOutCached = 0L

    @Volatile private var totalCostCached = 0.0

    @Volatile private var lastModelIdCached: String? = null

    /**
     * B3: guards the two mutable lists for non-suspend snapshot readers. The getters take
     * it alone; mutators take it while holding `mutex` — when both are held, stateLock is
     * always the INNER lock, and no suspend call ever runs under it.
     */
    private val stateLock = Any()

    /**
     * In-memory plan-mode flag for this session. Written by
     * [updateSessionPlanMode] and threaded into every [updateGlobalIndex] call
     * so the on-disk HistoryItem stays in sync.
     */
    @Volatile private var sessionPlanModeEnabled: Boolean = false

    /**
     * In-memory delegation metadata for this session. Written by
     * [updateSessionDelegationMetadata] and threaded into every [updateGlobalIndex]
     * call so the on-disk HistoryItem carries the delegation marker (spec §9.1).
     *
     * F2: populates HistoryItem.delegated in sessions.json; replaces the need for
     * a delegation.json sidecar as the canonical source of truth for the session list.
     */
    @Volatile private var sessionDelegationMetadata: DelegationMetadata? = null

    private val sessionDir: File get() = File(baseDir, "sessions/$sessionId")
    private val uiMessagesFile: File get() = File(sessionDir, "ui_messages.json")
    private val apiHistoryFile: File get() = File(sessionDir, "api_conversation_history.json")
    private val globalIndexFile: File get() = File(baseDir, "sessions.json")

    fun getClineMessages(): List<UiMessage> = synchronized(stateLock) { uiMessages.toList() }
    fun getApiConversationHistory(): List<ApiMessage> = synchronized(stateLock) { apiHistory.toList() }

    suspend fun addToClineMessages(message: UiMessage) = mutex.withLock {
        val histIdx = if (apiHistory.isEmpty()) null else apiHistory.size - 1
        val indexed = message.copy(conversationHistoryIndex = histIdx)
        synchronized(stateLock) {
            uiMessages.add(indexed)
        }
        saveInternal()
    }

    /**
     * Update the trailing PARTIAL streaming message's text in one atomic step (B17: the
     * live AgentLoop caller pattern snapshots lastIndex outside the lock, so a concurrent
     * append could land the partial text on the wrong message — that call site migrates to
     * this method in the follow-up wiring commit, plan Wave2-T4). Disk writes are
     * throttled (P0-1): the
     * in-memory list is always current; ui_messages.json lags at most [UI_SAVE_THROTTLE_MS]
     * mid-stream and is flushed by the next non-partial mutation or [saveBoth].
     *
     * No-op when the last message is not partial (e.g. a steering card landed after it) —
     * same semantics as the old call site.
     */
    suspend fun updateLastPartialMessage(text: String) = mutex.withLock {
        val lastIdx = uiMessages.lastIndex
        if (lastIdx < 0 || !uiMessages[lastIdx].partial) return@withLock
        synchronized(stateLock) {
            uiMessages[lastIdx] = uiMessages[lastIdx].copy(text = text)
        }
        saveInternalThrottled()
    }

    private suspend fun saveInternalThrottled() {
        // Skipped windows need no dirty flag: every save rewrites the WHOLE list, so the
        // next non-partial mutation or saveBoth() self-heals any throttled skip.
        if (uiSaveClock() - lastUiSaveMs >= UI_SAVE_THROTTLE_MS) {
            saveInternal()
        }
    }

    /**
     * Flip the persisted delegation ASKED card matching [questionId] to answered=true,
     * so a reopened delegated session renders it as resolved (not stuck "waiting").
     * No-op when no matching card is present. Cross-IDE delegation narration (2026-06-01).
     */
    suspend fun markDelegationQuestionAnswered(questionId: String) = mutex.withLock {
        var changed = false
        synchronized(stateLock) {
            for (i in uiMessages.indices) {
                val m = uiMessages[i]
                val card = m.delegationCardData
                if (card != null && card.kind == DelegationCardKind.ASKED &&
                    card.questionId == questionId && !card.answered
                ) {
                    uiMessages[i] = m.copy(delegationCardData = card.copy(answered = true))
                    changed = true
                }
            }
        }
        if (changed) saveInternal()
    }

    suspend fun updateClineMessage(index: Int, updated: UiMessage) = mutex.withLock {
        if (index in uiMessages.indices) {
            synchronized(stateLock) {
                uiMessages[index] = updated
            }
            saveInternal()
        }
    }

    suspend fun deleteClineMessage(index: Int) = mutex.withLock {
        if (index in uiMessages.indices) {
            synchronized(stateLock) {
                uiMessages.removeAt(index)
            }
            saveInternal()
        }
    }

    suspend fun addToApiConversationHistory(message: ApiMessage) = mutex.withLock {
        if (isEmptyAssistant(message)) {
            // Provider-error turns (empty content + no tool use) carry no information
            // and, persisted, accumulate across retries — training the model to mimic
            // the empty pattern. Dropped on the write path; paired nudge (if any) is
            // already in-memory only via ContextManager.
            return@withLock
        }
        if (hasDialectDrift(message)) {
            // Assistant emitted tool calls in an incompatible dialect (Anthropic
            // <function_calls><invoke> or Hermes <tool_call>{json}). The host parser
            // doesn't recognize these, so the tool didn't run — persisting the turn
            // would teach the model the dialect "worked" via in-context learning on
            // the next call (the same self-reinforcing loop documented as "context
            // poisoning" / "agent drift" in arxiv 2601.04170). Reject the turn and
            // flag the session so the next system prompt carries a corrective
            // <system-reminder>; AgentLoop will see no persisted turn and retry the
            // call.
            dialectDriftFlag.set(true)
            return@withLock
        }
        synchronized(stateLock) {
            apiHistory.add(message)
        }
        accumulateMetrics(message)
        saveApiHistoryInternal()
        // B15: a throttled-skipped index update must not strand stale data until saveBoth —
        // API turns are seconds apart, making this the natural boundary flush (P1-4 cadence).
        if (globalIndexDirty) {
            updateGlobalIndex()
            lastGlobalIndexUpdateMs = uiSaveClock()
            globalIndexDirty = false
        }
    }

    /** P1-4: O(1) counter update for the append path; bulk rewrites use [recomputeMetricsFromHistory]. */
    private fun accumulateMetrics(message: ApiMessage) {
        totalTokensInCached += (message.metrics?.inputTokens ?: 0).toLong()
        totalTokensOutCached += (message.metrics?.outputTokens ?: 0).toLong()
        totalCostCached += message.metrics?.cost ?: 0.0
        message.modelInfo?.modelId?.let { lastModelIdCached = it }
    }

    /** Recompute from scratch after any bulk rewrite (overwrite/truncate/prune/collapse/init). */
    private fun recomputeMetricsFromHistory() {
        totalTokensInCached = apiHistory.sumOf { (it.metrics?.inputTokens ?: 0).toLong() }
        totalTokensOutCached = apiHistory.sumOf { (it.metrics?.outputTokens ?: 0).toLong() }
        totalCostCached = apiHistory.sumOf { it.metrics?.cost ?: 0.0 }
        lastModelIdCached = apiHistory.lastOrNull { it.modelInfo != null }?.modelInfo?.modelId
    }

    /**
     * Strip trailing assistant messages that have neither text content nor tool use.
     * Mirror of `ContextManager.pruneTrailingEmptyAssistants` for the disk side.
     * Called by the retry and resume paths in `AgentService` to clean any pollution
     * that landed before the write-time guard was introduced.
     *
     * @return number of empty assistant entries removed
     */
    suspend fun pruneTrailingEmptyAssistants(): Int = mutex.withLock {
        var removed = 0
        synchronized(stateLock) {
            while (apiHistory.isNotEmpty() && isEmptyAssistant(apiHistory.last())) {
                apiHistory.removeAt(apiHistory.size - 1)
                removed++
            }
        }
        if (removed > 0) {
            recomputeMetricsFromHistory()
            saveApiHistoryInternal()
        }
        removed
    }

    /**
     * One-pass cleanup that redacts dialect-format tool-call XML from every
     * assistant turn in the persisted history. Mirrors the
     * [pruneTrailingEmptyAssistants] shape: called from `AgentService` at the
     * resume and retry entry points so a contaminated session can recover
     * without forcing the user to start a new chat.
     *
     * Per the contamination research (see [DialectDriftDetector] header) the
     * span is **redacted with a marker**, not translated. Translation would
     * make the bad turn look successful to the LLM on the next call and
     * re-anchor the dialect via in-context learning. The redaction marker
     * gives the model a placeholder with no format-like content to copy.
     *
     * Flags [dialectDriftFlag] when any redaction occurred so the next system
     * prompt picks up the corrective `<system-reminder>`.
     *
     * @return number of assistant turns rewritten.
     */
    suspend fun redactDialectXmlInHistory(): Int = mutex.withLock {
        var rewritten = 0
        synchronized(stateLock) {
            for (i in apiHistory.indices) {
                val msg = apiHistory[i]
                if (msg.role != ApiRole.ASSISTANT) continue

                var msgChanged = false
                val newContent = msg.content.map { block ->
                    if (block !is ContentBlock.Text) return@map block
                    val result = DialectDriftDetector.redactDialectMarkers(block.text)
                    if (result.modified) {
                        msgChanged = true
                        ContentBlock.Text(result.text)
                    } else block
                }
                if (msgChanged) {
                    apiHistory[i] = msg.copy(content = newContent)
                    rewritten++
                }
            }
        }
        if (rewritten > 0) {
            dialectDriftFlag.set(true)
            saveApiHistoryInternal()
        }
        rewritten
    }

    /**
     * Consumes the one-shot dialect-drift flag — returns true exactly once per
     * detection event, then resets. Called from `AgentService.systemPromptBuilder`
     * so the corrective `<system-reminder>` is injected on the immediately-next
     * LLM call and not on every call thereafter (per the research finding that
     * static prompt updates lose attention over long context; dynamic reminders
     * fired on state transitions steer better).
     */
    fun consumeDialectDriftFlag(): Boolean = dialectDriftFlag.getAndSet(false)

    private fun hasDialectDrift(message: ApiMessage): Boolean {
        if (message.role != ApiRole.ASSISTANT) return false
        return message.content.any { block ->
            block is ContentBlock.Text && DialectDriftDetector.hasDialectMarker(block.text)
        }
    }

    @Suppress("DEPRECATION")  // legacy ContentBlock.Image still has to exhaust the when
    private fun isEmptyAssistant(message: ApiMessage): Boolean {
        if (message.role != ApiRole.ASSISTANT) return false
        if (message.content.isEmpty()) return true
        return message.content.all { block ->
            when (block) {
                // isEffectivelyBlank covers `""`, `"   "`, AND U+200B-only echoes
                // (the LLM mirroring our own placeholder back). See StringUtils.
                is ContentBlock.Text -> StringUtils.isEffectivelyBlank(block.text)
                is ContentBlock.ToolUse -> false
                is ContentBlock.ToolResult -> false
                is ContentBlock.Image -> false
                is ContentBlock.ImageRef -> false
                else -> false
            }
        }
    }

    suspend fun overwriteApiConversationHistory(messages: List<ApiMessage>) = mutex.withLock {
        synchronized(stateLock) {
            apiHistory.clear()
            apiHistory.addAll(messages)
        }
        recomputeMetricsFromHistory()
        saveApiHistoryInternal()
    }

    /**
     * Mirror of `ContextManager.collapseLastCompletionToolPair` for the persisted api
     * history. Replaces the trailing `[ApiMessage(role=ASSISTANT, content=[..., ToolUse]),
     * ApiMessage(role=USER, content=[ToolResult])]` pair with a single text-only
     * assistant message when the tool is `attempt_completion` or `task_report`.
     *
     * Rationale: see [ContextManager.collapseLastCompletionToolPair]. Briefly: leaving
     * the trailing tool result on disk lets it leak into the next LLM request as a
     * `"TOOL RESULT:..."` user-role merge, and causes resume to auto-iterate. This
     * helper rewrites the persisted shape so neither failure mode is reachable.
     *
     * Mutex-guarded, atomic-write — same contract as every other handler mutator.
     *
     * @return true if a matching pair was found and collapsed, false otherwise.
     */
    suspend fun collapseLastCompletionToolPair(): Boolean = mutex.withLock {
        if (apiHistory.size < 2) return@withLock false
        val tail = apiHistory.last()
        if (tail.role != ApiRole.USER) return@withLock false
        val tailToolResult = tail.content.filterIsInstance<ContentBlock.ToolResult>().firstOrNull()
            ?: return@withLock false
        val toolUseId = tailToolResult.toolUseId

        val penult = apiHistory[apiHistory.size - 2]
        if (penult.role != ApiRole.ASSISTANT) return@withLock false

        // Legacy path: ToolUse block with a matching id (pre-2026-05-13 sessions on disk).
        val matchingToolUseName: String? = penult.content.filterIsInstance<ContentBlock.ToolUse>()
            .firstOrNull { it.id == toolUseId && it.name in COMPLETION_TOOL_NAMES_HERE }
            ?.name

        // New-shape path: single Text block whose text contains a completion-tool XML tag
        // (post-2026-05-13 XML-in-content migration). No ToolUse block is present.
        val matchingXmlToolName: String? = if (matchingToolUseName == null) {
            COMPLETION_TOOL_NAMES_HERE.firstOrNull { name ->
                penult.content.filterIsInstance<ContentBlock.Text>().any { block ->
                    block.text.contains("<$name>") || block.text.contains("<$name ")
                }
            }
        } else null

        if (matchingToolUseName == null && matchingXmlToolName == null) return@withLock false

        // Preserve any streaming text the assistant emitted; combine it with the tool
        // result content (which is what the LLM passed as the `result` argument).
        val rawStreamingText = penult.content
            .filterIsInstance<ContentBlock.Text>()
            .joinToString("\n") { it.text }
            .trim()
        // For the new XML-in-content shape, strip the tool call XML from the streaming text
        // so the collapsed message contains only prose (the tool XML is ephemeral scaffolding,
        // not user-visible content — same rationale as the ToolUse→text conversion on resume).
        val streamingText = if (matchingXmlToolName != null) {
            stripXmlToolCall(rawStreamingText, matchingXmlToolName)
        } else {
            rawStreamingText
        }
        val resultText = tailToolResult.content
        val combined = when {
            streamingText.isNotBlank() && resultText.isNotBlank() -> "$streamingText\n\n$resultText"
            streamingText.isNotBlank() -> streamingText
            else -> resultText
        }
        val replacement = penult.copy(content = listOf(ContentBlock.Text(combined)))

        synchronized(stateLock) {
            apiHistory.removeAt(apiHistory.size - 1)  // tool result (USER w/ ToolResult)
            apiHistory.removeAt(apiHistory.size - 1)  // assistant w/ ToolUse
            apiHistory.add(replacement)
        }
        recomputeMetricsFromHistory()
        saveApiHistoryInternal()
        true
    }

    /**
     * Tools whose `[assistant w/ ToolUse, USER w/ ToolResult]` trailing pair must be
     * collapsed by [collapseLastCompletionToolPair]. Mirror of the same set in
     * [com.workflow.orchestrator.agent.loop.ContextManager.COMPLETION_TOOL_NAMES] —
     * inlined here to avoid a `:agent` → `:agent` cyclic-import shape during refactors.
     */
    private val COMPLETION_TOOL_NAMES_HERE: Set<String> = setOf("attempt_completion", "task_report")

    /**
     * Removes the `<toolName>…</toolName>` XML block from [text], returning the
     * remaining prose trimmed of leading/trailing whitespace.
     *
     * Used by [collapseLastCompletionToolPair]'s new-shape path to strip the
     * ephemeral tool-call scaffolding before combining with the result text.
     * If the tag is not present the original text is returned unchanged.
     */
    private fun stripXmlToolCall(text: String, toolName: String): String {
        // Match the opening tag (with optional attributes), everything inside, and the closing tag.
        // Regex.escape defends against future tool names containing regex metacharacters.
        val escaped = Regex.escape(toolName)
        val pattern = Regex("<$escaped(?:\\s[^>]*)?>.*?</$escaped>", RegexOption.DOT_MATCHES_ALL)
        return pattern.replace(text, "").trim()
    }

    suspend fun overwriteClineMessages(messages: List<UiMessage>) = mutex.withLock {
        synchronized(stateLock) {
            uiMessages.clear()
            uiMessages.addAll(messages)
        }
        saveInternal()
    }

    /**
     * Truncate persisted messages at and after [targetMessageTs].
     *
     * Drops UI messages with ts >= targetMessageTs from `ui_messages.json` and the
     * trailing [droppedApiCount] entries from `api_conversation_history.json`.
     *
     * The caller (AgentService.revertToUserMessage) computes droppedApiCount because
     * the UI-message-ts to api-history-index mapping is not always 1:1 (e.g. streaming
     * partials, resume preambles). Passing it explicitly avoids guessing here.
     */
    suspend fun truncateMessagesAtTs(targetMessageTs: Long, droppedApiCount: Int) = mutex.withLock {
        val keptUi = uiMessages.filter { it.ts < targetMessageTs }
        synchronized(stateLock) {
            uiMessages.clear()
            uiMessages.addAll(keptUi)
        }

        val keepApiCount = (apiHistory.size - droppedApiCount).coerceAtLeast(0)
        val keptApi = apiHistory.take(keepApiCount)
        synchronized(stateLock) {
            apiHistory.clear()
            apiHistory.addAll(keptApi)
        }
        recomputeMetricsFromHistory()

        saveInternal()
        saveApiHistoryInternal()
    }

    /**
     * Rewrites the content of the most recent tool-result message for a given tool name.
     * Used by the plan discard flow to prevent the LLM from re-surfacing a discarded plan.
     *
     * Finds the most recent ASSISTANT message with a ToolUse of [toolName], then finds
     * the corresponding USER message with a matching ToolResult, and replaces its content
     * with [newContent]. Uses the existing mutex + atomic-write mechanism.
     *
     * @return true if a matching tool result was found and rewritten, false otherwise.
     */
    suspend fun rewriteMostRecentToolResult(toolName: String, newContent: String): Boolean = mutex.withLock {
        // Legacy path (pre-2026-05-13 sessions): find most recent ASSISTANT message with a
        // ContentBlock.ToolUse of this name, then find the corresponding USER ToolResult by id.
        val legacyToolUseId = apiHistory.lastOrNull { msg ->
            msg.role == ApiRole.ASSISTANT && msg.content.any { it is ContentBlock.ToolUse && it.name == toolName }
        }?.content?.filterIsInstance<ContentBlock.ToolUse>()?.lastOrNull { it.name == toolName }?.id

        if (legacyToolUseId != null) {
            // Legacy: find the most recent user message containing the matching ToolResult by id
            val idx = apiHistory.indexOfLast { msg ->
                msg.role == ApiRole.USER && msg.content.any { it is ContentBlock.ToolResult && it.toolUseId == legacyToolUseId }
            }
            if (idx >= 0) {
                val msg = apiHistory[idx]
                synchronized(stateLock) {
                    apiHistory[idx] = msg.copy(
                        content = msg.content.map { block ->
                            if (block is ContentBlock.ToolResult && block.toolUseId == legacyToolUseId) {
                                block.copy(content = newContent)
                            } else {
                                block
                            }
                        }
                    )
                }
                saveApiHistoryInternal()
                return@withLock true
            }
        }

        // New-shape path (post-2026-05-13 XML-in-content migration): assistant turns contain
        // a single ContentBlock.Text whose raw text includes the tool call XML inline. No
        // ToolUse block is present, so the legacy id-match above finds nothing.
        // Find the most recent ASSISTANT message with a Text block containing <toolName>...</toolName>,
        // then rewrite the ToolResult on the immediately-following USER turn.
        val assistantIdx = apiHistory.indexOfLast { msg ->
            msg.role == ApiRole.ASSISTANT && msg.content.filterIsInstance<ContentBlock.Text>().any { block ->
                block.text.contains("<$toolName>") || block.text.contains("<$toolName ")
            }
        }
        if (assistantIdx < 0) return@withLock false

        // The corresponding tool result MUST be on the immediately-following USER turn —
        // matching legacy id-pairing semantics. A non-adjacent ToolResult is a different
        // exchange and must not be rewritten.
        val userIdx = assistantIdx + 1
        if (userIdx >= apiHistory.size) return@withLock false
        val candidate = apiHistory[userIdx]
        if (candidate.role != ApiRole.USER || candidate.content.none { it is ContentBlock.ToolResult }) {
            return@withLock false
        }

        val msg = apiHistory[userIdx]
        synchronized(stateLock) {
            apiHistory[userIdx] = msg.copy(
                content = msg.content.map { block ->
                    if (block is ContentBlock.ToolResult) block.copy(content = newContent)
                    else block
                }
            )
        }
        saveApiHistoryInternal()
        true
    }

    /**
     * Mark this handler as published for concurrent access.
     * Must be called by `AgentService` immediately before assigning the handler to
     * `activeMessageStateHandler`. After this call, [setClineMessages] and
     * [setApiConversationHistory] will throw if invoked.
     */
    fun markPublished() {
        published = true
    }

    /** Call ONLY during initialization, before [markPublished] is called. */
    fun setClineMessages(messages: List<UiMessage>) {
        check(!published) { "setClineMessages must only be called during init, before markPublished()" }
        synchronized(stateLock) {
            uiMessages.clear()
            uiMessages.addAll(messages)
        }
    }

    /** Call ONLY during initialization, before [markPublished] is called. */
    fun setApiConversationHistory(messages: List<ApiMessage>) {
        check(!published) { "setApiConversationHistory must only be called during init, before markPublished()" }
        synchronized(stateLock) {
            apiHistory.clear()
            apiHistory.addAll(messages)
        }
        recomputeMetricsFromHistory()
    }

    private suspend fun saveInternal() {
        lastUiSaveMs = uiSaveClock()
        sessionDir.mkdirs()
        AtomicFileWriter.write(
            uiMessagesFile,
            json.encodeToString(synchronized(stateLock) { uiMessages.toList() }),
        )
        val now = uiSaveClock()
        if (now - lastGlobalIndexUpdateMs >= globalIndexThrottleMs) {
            updateGlobalIndex()
            lastGlobalIndexUpdateMs = now
            globalIndexDirty = false
        } else {
            globalIndexDirty = true
        }
    }

    private fun saveApiHistoryInternal() {
        sessionDir.mkdirs()
        // Phase 4: emit v2 wrapper { schemaVersion: 2, messages: [...] }.
        // Reader (loadApiHistory) tries this shape first and falls back to the
        // bare-array v1 shape for legacy sessions.
        val payload = ApiHistoryFile(schemaVersion = SCHEMA_VERSION_CURRENT, messages = apiHistory.toList())
        AtomicFileWriter.write(apiHistoryFile, json.encodeToString(ApiHistoryFile.serializer(), payload))
    }

    /**
     * Persist the plan-mode toggle for this session.
     *
     * Updates the in-memory flag (so the next [updateGlobalIndex] call picks it
     * up) and immediately rewrites `sessions.json` via the same atomic
     * globalIndexMutex + file-lock path used by all other index mutations.
     *
     * Safe to call from any coroutine — suspends on the session mutex (B2: the
     * index rebuild reads the message lists, so it must hold the same lock as
     * the list mutators), no EDT.
     */
    suspend fun updateSessionPlanMode(enabled: Boolean) = mutex.withLock {
        sessionPlanModeEnabled = enabled
        // Force a full global index flush so the new value lands on disk now,
        // not just on the next token-update throttle tick.
        updateGlobalIndex()
    }

    /**
     * Set the delegation metadata for this session.
     *
     * Updates the in-memory field and immediately rewrites `sessions.json` so the
     * HistoryItem in the global index carries the delegation marker as soon as the
     * delegated session starts (spec §9.1, F2 fix).
     *
     * Called by [AgentService.startDelegatedSession] before [executeTask] runs, so
     * the index entry is populated even before the first LLM turn.
     *
     * Safe to call from any coroutine — suspends on the session mutex (B2: the
     * index rebuild reads the message lists, so it must hold the same lock as
     * the list mutators), no EDT.
     */
    suspend fun updateSessionDelegationMetadata(metadata: DelegationMetadata) = mutex.withLock {
        sessionDelegationMetadata = metadata
        updateGlobalIndex()
    }

    suspend fun saveBoth() = mutex.withLock {
        saveInternal()
        saveApiHistoryInternal()
        // Flush any throttled global index update
        if (globalIndexDirty) {
            updateGlobalIndex()
            lastGlobalIndexUpdateMs = uiSaveClock()
            globalIndexDirty = false
        }
    }

    private suspend fun updateGlobalIndex() = globalIndexMutex.withLock {
        // Sub-agent sessions use sessionId = "$parentId/subagents/$agentId" (slash-containing).
        // Those nested IDs must not pollute the global sessions.json index — they would appear
        // as phantom non-resumable cards in the history UI. Sub-agent conversation history is
        // still persisted (the writes to api_conversation_history.json + ui_messages.json
        // happen separately) — only the index entry is suppressed.
        if (sessionId.contains('/')) return@withLock

        // P1-4: token/cost/model values come from the incrementally-maintained counters
        // (O(1) per index write instead of three O(history) sumOf passes). Counter writes
        // happen under the session mutex, which every caller of this method also holds.
        // B3: only the UI timestamp still needs a list read, snapshotted under stateLock.
        val lastUiTs: Long? = synchronized(stateLock) { uiMessages.lastOrNull()?.ts }

        val item = HistoryItem(
            id = sessionId,
            ts = lastUiTs ?: System.currentTimeMillis(),
            task = taskText.take(200),
            tokensIn = totalTokensInCached,
            tokensOut = totalTokensOutCached,
            totalCost = totalCostCached,
            modelId = lastModelIdCached,
            planModeEnabled = sessionPlanModeEnabled,
            // F2: thread delegation metadata into the index entry so sessions.json
            // carries the delegation marker without a secondary delegation.json read.
            delegated = sessionDelegationMetadata,
        )

        // Cross-process serialization: globalIndexMutex protects within this JVM, but two
        // IDE windows on the same project share the same sessions.json on disk. Wrap the
        // read-modify-write in a blocking FileLock so window A's update can't clobber
        // window B's concurrent update of a different session.
        baseDir.mkdirs()
        withGlobalIndexFileLock(baseDir) {
            val existingItems: MutableList<HistoryItem> = try {
                if (globalIndexFile.exists()) {
                    prettyJson.decodeFromString<MutableList<HistoryItem>>(globalIndexFile.readText())
                } else mutableListOf()
            } catch (_: Exception) { mutableListOf() }

            val idx = existingItems.indexOfFirst { it.id == sessionId }
            if (idx >= 0) {
                // Preserve user-controlled fields (isFavorited) from the existing entry.
                // planModeEnabled comes from the freshly-built item (already has the
                // in-memory sessionPlanModeEnabled value threaded in above).
                val preserved = item.copy(isFavorited = existingItems[idx].isFavorited)
                existingItems[idx] = preserved
            } else {
                existingItems.add(0, item)
            }

            // F-16: enforce a retention cap so sessions.json never grows without bound.
            // Keep the MAX_GLOBAL_INDEX_SIZE most-recent sessions; always preserve
            // favourited sessions regardless of age so the user never loses them.
            if (existingItems.size > MAX_GLOBAL_INDEX_SIZE) {
                val favourites = existingItems.filter { it.isFavorited }.map { it.id }.toSet()
                val pruned = existingItems
                    .take(MAX_GLOBAL_INDEX_SIZE)
                    .toMutableList()
                // Re-add any favourited sessions that were dropped by the age-based cut.
                val prunedIds = pruned.map { it.id }.toSet()
                for (extra in existingItems.drop(MAX_GLOBAL_INDEX_SIZE)) {
                    if (extra.id in favourites && extra.id !in prunedIds) {
                        pruned.add(extra)
                    }
                }
                existingItems.clear()
                existingItems.addAll(pruned)
            }

            AtomicFileWriter.write(globalIndexFile, prettyJson.encodeToString(existingItems))
        }
    }

    companion object {
        /**
         * Current schema version emitted by the writer. v1 (the pre-Phase-4
         * bare-array shape) is detected by [loadApiHistory]'s fallback and
         * normalized into the in-memory model with no version field.
         *
         * Phase 4 of multimodal-agent plan.
         */
        const val SCHEMA_VERSION_CURRENT: Int = 2

        /** P0-1: window for partial-stream ui_messages.json writes (memory stays authoritative). */
        private const val UI_SAVE_THROTTLE_MS = 500L

        /**
         * Maximum number of sessions retained in the global `sessions.json` index.
         * When a write would push the list beyond this cap the oldest non-favourited
         * entries are pruned (F-16).  Favourited sessions are always kept regardless
         * of their age so the user never loses bookmarked conversations.
         *
         * Rationale: at roughly 1 KB per HistoryItem entry and 500 entries the index
         * stays under 512 KB, which is negligible to load and holds a FileLock for
         * less than 1 ms on typical SSDs.  Users who run dozens of sessions per day
         * accumulate 500 entries in 2–3 weeks — well past reasonable "recent history"
         * expectations.  Increase this value if users report premature pruning.
         */
        const val MAX_GLOBAL_INDEX_SIZE: Int = 500

        private val LOG = Logger.getInstance(MessageStateHandler::class.java)

        /** Separate mutex for sessions.json to prevent races between concurrent sessions (I2 fix). */
        private val globalIndexMutex = Mutex()

        /**
         * Shared serializers module with a polymorphic fallback for unknown
         * [ContentBlock] discriminators. Phase 1 of multimodal-agent plan.
         *
         * `kotlinx-serialization`'s `ignoreUnknownKeys = true` covers unknown FIELDS
         * within known classes — it does NOT cover unknown polymorphic discriminators.
         * A v1 plugin loading a v2 session file (e.g. with `type: "image_url_ref"` from
         * Phase 4) would otherwise crash with `SerializationException`.
         */
        private val contentBlockModule = SerializersModule {
            polymorphic(ContentBlock::class) {
                // Existing subclasses (Text/ToolUse/ToolResult/Image) are auto-registered
                // via their `@SerialName` annotations. Unknown discriminators fall through
                // to UnsupportedContentBlock so v1 readers degrade gracefully.
                defaultDeserializer { UnsupportedContentBlockSerializer }
            }
        }

        // coerceInputValues = true: enum values absent from current source
        // (after a removal) deserialize to the field's default — null for
        // nullable enum fields — instead of throwing. Future-proofs against
        // any enum drift across releases.
        // Single canonical compact instance: used by all read/decode paths and
        // non-pretty write paths. encodeDefaults=true prevents config drift if
        // any caller ever encodes through this instance in future.
        private val configuredJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
            prettyPrint = false
            serializersModule = contentBlockModule
        }
        // Single canonical pretty instance: used by all global-index write paths.
        // Identical config to configuredJson except prettyPrint=true.
        private val configuredPrettyJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
            prettyPrint = true
            serializersModule = contentBlockModule
        }

        /**
         * Test-only accessor for the configured [Json] instance. Used by
         * `UnknownContentBlockTest` to verify the polymorphic fallback is wired.
         * Returns the same instance the production read/write paths use.
         */
        internal fun jsonForTesting(): Json = configuredJson

        fun loadUiMessages(sessionDir: File): List<UiMessage> {
            val file = File(sessionDir, "ui_messages.json")
            if (!file.exists()) return emptyList()
            return try {
                configuredJson.decodeFromString<List<UiMessage>>(file.readText())
            } catch (_: Exception) { emptyList() }
        }

        /**
         * Loads `api_conversation_history.json` with backward-compat for the
         * pre-Phase-4 bare-array shape.
         *
         * Order:
         *   1. Try v2 wrapper [ApiHistoryFile] — `{schemaVersion, messages}`.
         *   2. On failure, fall back to v1 bare array `List<ApiMessage>` and
         *      synthesize an implicit `schemaVersion = 1`.
         *   3. On both failing, return [emptyList] (corrupted file — better
         *      than crashing the session UI).
         *
         * Phase 4 of multimodal-agent plan.
         */
        fun loadApiHistory(sessionDir: File): List<ApiMessage> {
            val file = File(sessionDir, "api_conversation_history.json")
            if (!file.exists()) return emptyList()
            val text = file.readText()
            // Try v2 wrapper first
            return runCatching {
                configuredJson.decodeFromString(ApiHistoryFile.serializer(), text).messages
            }.recoverCatching {
                // v1 fallback: bare JSON array
                configuredJson.decodeFromString(ListSerializer(ApiMessage.serializer()), text)
            }.getOrElse { error ->
                // Both shapes failed. Most likely cause: forward-version file (v3+) being
                // read by this plugin, or a corrupted write. Log loudly so a user reporting
                // "I lost my history" has a breadcrumb in idea.log instead of silent loss.
                LOG.warn(
                    "MessageStateHandler.loadApiHistory: both v2 and v1 parsers failed for " +
                        "${file.absolutePath} (size=${text.length}); returning empty history. " +
                        "Cause: ${error.message}",
                    error
                )
                emptyList()
            }
        }

        fun loadGlobalIndex(baseDir: File): List<HistoryItem> {
            val file = File(baseDir, "sessions.json")
            if (!file.exists()) return emptyList()
            return try {
                configuredJson.decodeFromString<List<HistoryItem>>(file.readText())
            } catch (_: Exception) { emptyList() }
        }

        /**
         * Look up a single [HistoryItem] by [sessionId] from the global index on
         * disk. Returns null if the entry does not exist or the index is unreadable.
         *
         * Used by [AgentService] to seed [Session.planModeEnabled] from the
         * persisted value on session start and resume so that a session toggled
         * into plan mode and then paused resumes in the correct mode.
         */
        fun findHistoryItem(baseDir: File, sessionId: String): HistoryItem? =
            loadGlobalIndex(baseDir).firstOrNull { it.id == sessionId }

        private val SAFE_SESSION_ID = Regex("^[a-zA-Z0-9_-]+$")

        fun deleteSession(baseDir: File, sessionId: String) {
            if (!sessionId.matches(SAFE_SESSION_ID)) return
            val indexFile = File(baseDir, "sessions.json")
            if (indexFile.exists()) {
                withGlobalIndexFileLock(baseDir) {
                    try {
                        val items = configuredJson.decodeFromString<List<HistoryItem>>(indexFile.readText())
                        val filtered = items.filter { it.id != sessionId }
                        AtomicFileWriter.write(indexFile, configuredPrettyJson.encodeToString(filtered))
                    } catch (_: Exception) { /* corrupted index, skip */ }
                }
            }
            val sessionDir = File(baseDir, "sessions/$sessionId")
            if (sessionDir.exists()) {
                sessionDir.deleteRecursively()
            }
        }

        /**
         * Rewrite the `task` field on the existing index entry for [sessionId].
         * Used to replace the auto-generated first-message-takes-200 task text with a
         * descriptive title (e.g. from the Haiku-generated session title pass).
         *
         * No-op if the entry doesn't exist. Uses the same cross-process file lock as
         * the rest of the index mutators so it can't race other windows.
         */
        fun updateSessionTitle(baseDir: File, sessionId: String, title: String) {
            if (!sessionId.matches(SAFE_SESSION_ID)) return
            val indexFile = File(baseDir, "sessions.json")
            if (!indexFile.exists()) return
            withGlobalIndexFileLock(baseDir) {
                try {
                    val items = configuredJson.decodeFromString<List<HistoryItem>>(indexFile.readText())
                    val idx = items.indexOfFirst { it.id == sessionId }
                    if (idx < 0) return@withGlobalIndexFileLock
                    val updated = items.toMutableList().also {
                        it[idx] = it[idx].copy(task = title.take(200))
                    }
                    AtomicFileWriter.write(indexFile, configuredPrettyJson.encodeToString(updated))
                } catch (_: Exception) { /* corrupted index, skip */ }
            }
        }

        /**
         * Orphan-session cleanup pass — removes top-level directories under
         * `sessions/` that are NOT present in the global index AND whose last
         * activity is older than [olderThanMs].
         *
         * Catches two real failure modes:
         *  - a session that crashed between writing per-session files and updating
         *    `sessions.json`, leaving an unreachable directory on disk
         *  - residue from older plugin versions whose delete path didn't cascade
         *
         * Sub-agent dirs live at `sessions/{parentId}/subagents/{agentId}` — i.e. nested
         * under their parent's directory. They are caught for free when the parent is
         * either kept (no cleanup) or deleted (cascade); this pass only iterates
         * top-level entries so we never remove a sub-agent whose parent is still live.
         *
         * **Timestamp source (F-23):** We prefer the `ts` field from `ui_messages.json`
         * (the timestamp of the last UI message) over the filesystem `lastModified()`
         * of the directory.  Directory mtime is susceptible to clock-skew and is
         * routinely updated by backup tools (Time Machine, rsync, WSL).  If
         * `ui_messages.json` is absent or unreadable we fall back to the mtime of
         * `api_conversation_history.json` (a file we write on every API turn), and
         * only as a last resort to the directory mtime itself.  Either way, before
         * deleting we log the candidate at INFO level so operators can see what was
         * pruned without needing a dry-run.
         *
         * Best-effort, never throws — startup must not be blocked on disk hiccups.
         *
         * @return number of directories removed
         */
        fun cleanupOrphanSessions(baseDir: File, olderThanMs: Long = 30L * 24 * 60 * 60 * 1000): Int {
            val sessionsRoot = File(baseDir, "sessions")
            if (!sessionsRoot.isDirectory) return 0
            val knownIds: Set<String> = try {
                loadGlobalIndex(baseDir).map { it.id }.toSet()
            } catch (_: Exception) { return 0 }
            val cutoff = System.currentTimeMillis() - olderThanMs
            var removed = 0
            val children = sessionsRoot.listFiles() ?: return 0
            for (child in children) {
                if (!child.isDirectory) continue
                if (child.name in knownIds) continue
                val lastActivity = resolveSessionLastActivity(child)
                if (lastActivity >= cutoff) continue
                LOG.info("[SessionCleanup] Removing orphan session dir ${child.name} (lastActivity=${lastActivity}ms)")
                try {
                    if (child.deleteRecursively()) removed++
                } catch (_: Exception) { /* skip, try next */ }
            }
            return removed
        }

        /**
         * Resolve the most reliable "last activity" timestamp for an orphan session
         * directory.  Preference order (F-23):
         *
         * 1. `ts` of the last entry in `ui_messages.json`    — app-level timestamp,
         *    immune to backup-tool mtime updates
         * 2. `ts` of the last entry in `api_conversation_history.json` — same source
         * 3. `lastModified` of the session directory itself   — last resort
         *
         * File mtime is intentionally **not** used as a timestamp source: backup
         * tools (Time Machine, rsync) and filesystem events update file mtime without
         * any real session activity.  We prefer the application-written `ts` field
         * inside the JSON whenever at least one message was persisted.  If the files
         * are empty or unreadable (e.g., a session that crashed on the very first
         * write), we fall back to the directory mtime — same behaviour as before F-23.
         */
        private fun resolveSessionLastActivity(sessionDir: File): Long {
            // 1. Application-level timestamp from the last UI message
            try {
                val uiFile = File(sessionDir, "ui_messages.json")
                if (uiFile.exists()) {
                    val msgs = loadUiMessages(sessionDir)
                    val lastTs = msgs.lastOrNull()?.ts
                    if (lastTs != null && lastTs > 0L) return lastTs
                }
            } catch (_: Exception) { /* fall through */ }

            // 2. Application-level timestamp from the last API history entry
            try {
                val apiFile = File(sessionDir, "api_conversation_history.json")
                if (apiFile.exists()) {
                    val history = loadApiHistory(sessionDir)
                    val lastTs = history.lastOrNull()?.ts
                    if (lastTs != null && lastTs > 0L) return lastTs
                }
            } catch (_: Exception) { /* fall through */ }

            // 3. Directory mtime (susceptible to clock-skew / backup-tool updates)
            return sessionDir.lastModified()
        }

        fun toggleFavorite(baseDir: File, sessionId: String) {
            if (!sessionId.matches(SAFE_SESSION_ID)) return
            val indexFile = File(baseDir, "sessions.json")
            if (!indexFile.exists()) return
            withGlobalIndexFileLock(baseDir) {
                try {
                    val items = configuredJson.decodeFromString<List<HistoryItem>>(indexFile.readText())
                    val updated = items.map { item ->
                        if (item.id == sessionId) item.copy(isFavorited = !item.isFavorited) else item
                    }
                    if (updated != items) {
                        AtomicFileWriter.write(indexFile, configuredPrettyJson.encodeToString(updated))
                    }
                } catch (_: Exception) { /* corrupted index, skip */ }
            }
        }

        /**
         * Serializes read-modify-write on `sessions.json` across processes.
         *
         * Within one JVM `globalIndexMutex` serializes coroutines; this helper adds a
         * `java.nio` blocking [java.nio.channels.FileLock] on a sibling `sessions.json.lock`
         * so two IDE windows that happen to share the same project agent directory
         * (real case: a worktree + its main checkout pointing at the same SHA-keyed root)
         * can't clobber each other's updates.
         *
         * Best-effort: on lock-acquire failure we still run [block] so a permission /
         * filesystem error doesn't break history persistence outright — it just degrades
         * back to the previous coroutine-mutex-only semantics for that one write.
         */
        private inline fun withGlobalIndexFileLock(baseDir: File, block: () -> Unit) {
            baseDir.mkdirs()
            val lockFile = File(baseDir, "sessions.json.lock")
            var raf: java.io.RandomAccessFile? = null
            var lock: java.nio.channels.FileLock? = null
            try {
                raf = java.io.RandomAccessFile(lockFile, "rw")
                lock = try {
                    raf.channel.lock()
                } catch (_: Exception) { null }
                block()
            } finally {
                try { lock?.release() } catch (_: Exception) {}
                try { raf?.close() } catch (_: Exception) {}
            }
        }

    }
}
