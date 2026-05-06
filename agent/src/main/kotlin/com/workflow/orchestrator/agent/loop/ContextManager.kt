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
    /**
     * Fallback budget used by [effectiveMaxInputTokens] when:
     *   - no [modelCatalogService] is wired (tests, sub-agent budgets), OR
     *   - the catalog is unreachable, OR
     *   - [currentModelRef] returns null
     *
     * Production sites should wire [modelCatalogService] + [currentModelRef]
     * so this constructor field is never the authoritative number — the
     * Sourcegraph catalog is. v0.83.44+ removed the `AgentSettings.maxInputTokens`
     * user setting; this field now exists ONLY for tests that need a
     * deterministic budget and for sub-agent budgets where the parent's
     * per-model number isn't the right unit.
     */
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
    var onHistoryOverwrite: (suspend (List<ChatMessage>, deletedRange: Pair<Int, Int>) -> Unit)? = null,
    /**
     * Optional [ModelCatalogService] for per-model context-budget lookup.
     *
     * Multimodal-agent Phase 2 — when present, [maxInputTokensFor] reads the
     * per-tier `modelConfigAllTiers.<tier>.contextWindow.maxInputTokens` from the
     * Sourcegraph catalog (the REAL value — e.g. enterprise → 132K non-thinking,
     * 93K thinking) instead of relying on the legacy `maxInputTokens` constructor
     * default (150K). When `null` or the catalog has not loaded, callers fall
     * back to [FALLBACK_MAX_INPUT_TOKENS] (90K).
     *
     * Optional + nullable for backward compatibility with existing call sites
     * (AgentService, AgentController, SubagentRunner, tests).
     */
    private val modelCatalogService: ModelCatalogService? = null,
    /**
     * Provider for the live, currently-active model ref.
     *
     * v0.83.44 — paired with [modelCatalogService] this lets [effectiveMaxInputTokens]
     * follow the active model. The provider is invoked on every read so a
     * mid-session model fallback (NETWORK_ERROR → cheaper tier) instantly
     * recomputes the budget against the new model's catalog entry. Null in
     * tests and sub-agent contexts where the per-model number is not the
     * right authority.
     */
    private val currentModelRef: (() -> String?)? = null
) {
    private val LOG = Logger.getInstance(ContextManager::class.java)

    private var systemPrompt: ChatMessage? = null
    private val messages: MutableList<ChatMessage> = mutableListOf()

    /** Last prompt token count reported by the API. Null if no API response yet. */
    private var lastPromptTokens: Int? = null

    /** Last summary from LLM compaction, used for summary chaining. */
    private var lastSummary: String? = null

    /**
     * Whether the most recent [compact] call actually ran Stage 3 (LLM summarization).
     * The UI reads this to label the post-compaction marker correctly
     * ("Compacted with summary" vs "Compacted (heuristic)").
     */
    var lastCompactionRanStage3: Boolean = false
        private set

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
     * Optional TaskStore reference for the typed task system (Phase 3+).
     * When attached, [renderTaskProgressMarkdown] reads live task state from the store.
     */
    private var taskStore: com.workflow.orchestrator.agent.session.TaskStore? = null

    fun attachTaskStore(store: com.workflow.orchestrator.agent.session.TaskStore) {
        taskStore = store
    }

    /**
     * Render current tasks as a Markdown checklist. Returns null if no store attached or no tasks.
     * Format: "- [x] completed" / "- [ ] other". DELETED tasks are filtered out.
     */
    fun renderTaskProgressMarkdown(): String? {
        val store = taskStore ?: return null
        val tasks = store.listTasks().filter { it.status != com.workflow.orchestrator.agent.loop.TaskStatus.DELETED }
        if (tasks.isEmpty()) return null
        return tasks.joinToString("\n") { t ->
            val box = if (t.status == com.workflow.orchestrator.agent.loop.TaskStatus.COMPLETED) "[x]" else "[ ]"
            "- $box ${t.subject}"
        }
    }

    /**
     * Snapshot of non-deleted tasks for environment_details injection. Empty list if no
     * store attached or no tasks. Single-coroutine dirty-read (see TaskStore.listTasks).
     */
    fun currentTasks(): List<com.workflow.orchestrator.agent.loop.Task> {
        val store = taskStore ?: return emptyList()
        return store.listTasks().filter { it.status != com.workflow.orchestrator.agent.loop.TaskStatus.DELETED }
    }

    // ---- Message management ----

    fun setSystemPrompt(prompt: String) {
        systemPrompt = ChatMessage(role = "system", content = prompt)
        LOG.info("[Context] System prompt set (${prompt.length} chars, ~${prompt.length / 4} tokens)")
    }

    fun addUserMessage(content: String) {
        messages.add(ChatMessage(role = "user", content = content))
    }

    /**
     * Multimodal-agent Phase 7 followup F-P6FU-5 — add a user message with
     * structured [ContentPart] entries (image and/or text). Required so test
     * fixtures and tool-call code paths don't have to round-trip through
     * `addAssistantMessage(ChatMessage(role="user", parts=...))` — which works
     * but obscures intent.
     *
     * Sets `content` to a flat-text mirror of the [parts] (concatenated
     * `Text` values) for backward compatibility with consumers that still
     * read `ChatMessage.content` directly.
     */
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
            LOG.info("[multimodal] ContextManager seeded user turn with ${images.size} image part(s): " +
                images.joinToString(",") { "${it.sha256.take(12)}…/${it.mime}" })
        }
    }

    fun addAssistantMessage(message: ChatMessage) {
        messages.add(message)
    }

    /**
     * Remove contiguous trailing `[assistant-text-only, user-nudge]` pairs sitting
     * immediately before the last message.
     *
     * Rationale: when the model keeps replying with text-only (or empty) responses,
     * we inject an identical error/nudge each turn. The chain
     * `(assistant-text, [ERROR] nudge, assistant-text, [ERROR] nudge, ...)` accumulates
     * in context and can prime the model to keep mimicking the "no tool" pattern it
     * sees repeated. Collapsing the chain keeps at most one visible nudge at the tail.
     *
     * Scope is intentionally narrow: only CONTIGUOUS trailing pairs whose user content
     * equals `nudgeText` are removed. Legitimate older nudges that are no longer at the
     * tail (because real work happened after) stay untouched.
     *
     * Not persisted via `onHistoryOverwrite` because nudges are never written to
     * api_conversation_history (they live only in this in-memory context).
     *
     * @return number of pairs removed
     */
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
            LOG.info("[Context] Pruned $pairsRemoved trailing nudge pair(s) to avoid LLM mimicry of the error pattern")
        }
        return pairsRemoved
    }

    /**
     * Remove ALL `[assistant-text-only, user-nudge]` pairs anywhere in the conversation
     * where the user content equals [nudgeText] — not just the contiguous trailing ones.
     *
     * Rationale: after a chain of empty/text-only responses exits the loop (MAX retries
     * or max-mistakes), the [assistant-empty, nudge] pair sits interior in context. If
     * the user then continues and later hits another empty/text-only response, the
     * trailing-only pruner leaves the interior pair alone — so the context ends up with
     * two separated `[empty, ERROR]` pairs. Across enough "continue" cycles that pattern
     * accumulates and primes the model on "empty response with ERROR reply is normal
     * here." This variant strips every prior occurrence before we add a fresh one.
     *
     * Called by the Case B and Case C insertion sites in `AgentLoop` right before
     * `addUserMessage(nudgeText)`. Case A keeps using [pruneTrailingNudgePairs] because
     * a successful tool call shouldn't retroactively erase an earlier stall's audit trail
     * mid-run — it should only clean up the stall it just broke out of.
     *
     * Not persisted via `onHistoryOverwrite` — same rationale as [pruneTrailingNudgePairs].
     *
     * @return number of pairs removed
     */
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
                // Don't advance i — the pair that WAS at index i+1 (if any) is now at
                // index i-1 after the two removals, and we want to re-check it in case
                // the assistant-text before it now pairs with a different structure.
            } else {
                i++
            }
        }
        if (pairsRemoved > 0) {
            LOG.info("[Context] Pruned $pairsRemoved total nudge pair(s) (all occurrences) to avoid cross-episode priming")
        }
        return pairsRemoved
    }

    /**
     * Remove contiguous trailing assistant messages that have neither text content nor
     * tool calls. These are "empty" turns produced by provider errors (degenerate
     * zero-temp sampling, truncated upstream stream, etc.) — they carry no information
     * and, left in context, train the model to mimic the pattern on the next call.
     *
     * Pairs well with Case C in `AgentLoop` (empty-response branch): each retry cycle
     * used to append `[empty-assistant, EMPTY_RESPONSE_ERROR-user]` to context; the
     * user-side of the pair is handled by [pruneTrailingNudgePairs], and this helper
     * cleans the assistant side on retry/resume where nudges were never persisted.
     *
     * Intentionally narrow: only strips *trailing* empties. Earlier empties (with real
     * work after them) are left alone so prior state stays auditable.
     *
     * Not self-persisting: caller decides whether to invoke `onHistoryOverwrite`.
     * On the retry/resume paths we mirror the disk side via
     * `MessageStateHandler.pruneTrailingEmptyAssistants`.
     *
     * @return number of empty assistant messages removed from the tail
     */
    fun pruneTrailingEmptyAssistants(): Int {
        var removed = 0
        while (messages.isNotEmpty()) {
            val tail = messages.last()
            // isEffectivelyBlank also catches U+200B-only echoes — see StringUtils.
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

    /**
     * Collapse a trailing `[assistant w/ completion tool_call, tool_result]` pair into a
     * single plain assistant text turn. The "completion tools" are `attempt_completion`
     * and `task_report` — both signal the loop's exit and produce `LoopResult.Completed`.
     *
     * **Why this exists.** Sourcegraph's `sanitizeMessages` converts `role: tool` into
     * `role: user` with a `"TOOL RESULT:\n..."` prefix and then merges consecutive
     * same-role messages. If we leave the trailing tool result in place, two things
     * break:
     *   1. The next user turn (typed message or `next_step` hint accept) gets merged
     *      into a single user message with the prior completion result as its
     *      dominant content — the LLM sees "TOOL RESULT: <prior summary>\n\n<user
     *      hint>" and re-issues `attempt_completion` with the same arguments.
     *   2. Resume of any session whose tail isn't `RESUME_COMPLETED_TASK` (e.g. a
     *      crashed session that had a prior completion mid-history) auto-iterates
     *      the LLM on the dangling tool result.
     *
     * Collapsing the pair preserves the assistant's streaming narrative (if any) and
     * appends the tool's result text, then drops both `tool_calls` and the tool
     * result entry. The conversation reads cleanly as `[..., assistant: "<narrative
     * + result>", user: "<follow-up>"]` — exactly what the LLM expects.
     *
     * **Caller side-effects.** Self-mutating only — caller is responsible for
     * mirroring the change to the persisted disk-side history via
     * [MessageStateHandler.collapseLastCompletionToolPair]. Both sites must run
     * together so the in-memory and on-disk views stay aligned (otherwise resume
     * loads the on-disk shape and re-introduces the bug).
     *
     * @return true if a matching pair was found and collapsed, false otherwise.
     */
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

        // Strip the "[ERROR] " prefix that addToolResult adds for failed tool calls so
        // the assistant text reads naturally. attempt_completion / task_report only
        // succeed when args validate; an error here would be unusual but we degrade
        // gracefully rather than emitting "[ERROR] " in the rewritten history.
        val resultText = (tail.content ?: "").removePrefix("[ERROR] ")
        val streamingText = penult.content?.takeIf { it.isNotBlank() }
        val combined = when {
            streamingText != null && resultText.isNotBlank() -> "$streamingText\n\n$resultText"
            streamingText != null -> streamingText
            else -> resultText
        }

        messages.removeAt(messages.size - 1)  // tool result
        messages.removeAt(messages.size - 1)  // assistant w/ completion tool_call
        messages.add(ChatMessage(role = "assistant", content = combined))
        LOG.info("[Context] Collapsed completion tool pair (toolCallId=$toolCallId, name=${matchingCall.function.name}) into plain assistant turn")
        return true
    }

    /**
     * Add a tool result and track file reads for deduplication.
     *
     * When a tool result contains file content, we track which file was read
     * and at which message index. This enables Stage 1 (duplicate file read
     * detection) during compaction.
     */
    fun addToolResult(
        toolCallId: String,
        content: String,
        isError: Boolean,
        toolName: String? = null,
        imageRefs: List<ContentBlock.ImageRef> = emptyList(),
    ) {
        val body = if (isError) "[ERROR] $content" else content
        val idx = messages.size
        // Multimodal: when the tool produced image attachments (e.g. jira.download_attachment
        // on a PNG), seed the in-memory ChatMessage with structured `parts` so
        // BrainRouter.hasImageParts() fires on the next turn. Without this the persisted
        // ApiMessage carries ContentBlock.ImageRef alongside ToolResult, but the LLM-side
        // context is text-only and the model genuinely cannot see the bytes.
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
            LOG.info("[multimodal] ContextManager seeded tool-result turn with ${imageRefs.size} image part(s) (toolName=$toolName): " +
                imageRefs.joinToString(",") { "${it.sha256.take(12)}…/${it.mime}" })
        } else {
            LOG.debug("[Context] Tool result added: $toolCallId (${content.length} chars)")
        }

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
        LOG.debug("[Context] Token update: $promptTokens prompt tokens (${"%.1f".format(utilizationPercent())}% of ${effectiveMaxInputTokens()})")
    }

    /**
     * Returns the live max-input-token budget. v0.83.44+ — when the catalog +
     * currentModelRef are wired, this reads from `ModelCatalogService` so
     * compaction follows whatever Sourcegraph reports for the active model
     * (e.g. Sonnet → 132K, Sonnet-thinking → 93K, Opus-thinking → varies).
     * Falls back to [maxInputTokens] (the constructor field) when:
     *   - catalog or provider not wired, OR
     *   - currentModelRef returns null, OR
     *   - the catalog has no entry for that model (cold-cache or unknown model)
     *
     * Invoked on every [utilizationPercent] / [shouldCompact] / [compact] call,
     * so a mid-session model fallback recomputes the budget instantly.
     */
    fun effectiveMaxInputTokens(): Int {
        val ref = currentModelRef?.invoke() ?: return maxInputTokens
        val catalog = modelCatalogService ?: return maxInputTokens
        val window = catalog.getContextWindow(ref) ?: return maxInputTokens
        return window.maxInputTokens
    }

    /**
     * Returns utilization as a percentage (0-100+).
     * Uses API-reported tokens if available, otherwise falls back to bytes/4 estimate.
     * Denominator is [effectiveMaxInputTokens] so the percentage tracks the
     * live per-model budget, not the constructor fallback.
     */
    fun utilizationPercent(): Double {
        val tokens = lastPromptTokens ?: tokenEstimate()
        val max = effectiveMaxInputTokens()
        return (tokens.toDouble() / max) * 100.0
    }

    /**
     * Multimodal-agent Phase 7 — current authoritative input-token count for
     * the live conversation. Used by the chat input usage indicator
     * (`window.workflowAgent.getContextUsage()`); paired with
     * [maxInputTokensFor] for the percentage display.
     *
     * Resolution order matches [utilizationPercent]:
     *   1. `lastPromptTokens` (set by the most recent API response — this is
     *      the authoritative number reported by the gateway)
     *   2. `tokenEstimate()` chars/3.5 fallback (used during the first turn
     *      before the API has reported back)
     */
    fun currentInputTokens(): Int = lastPromptTokens ?: tokenEstimate()

    /**
     * Per-model context budget read live from [ModelCatalogService].
     *
     * Multimodal-agent Phase 2 — replaces hard-coded model assumptions with the
     * per-tier `modelConfigAllTiers.<tier>.contextWindow.maxInputTokens` from
     * `/.api/modelconfig/supported-models.json`. Tier is hard-coded to
     * `enterprise` for v1 (the user's instance is enterprise per probe baseline);
     * a proper `pluginSettings.getCurrentTier()` setting is deferred to v2.
     *
     * Returns [FALLBACK_MAX_INPUT_TOKENS] (90K) when:
     * - No [modelCatalogService] is wired (legacy/test call sites)
     * - The catalog has not been loaded yet
     * - The model is unknown to the catalog
     *
     * Note: this is a read-only lookup. The legacy [maxInputTokens] constructor
     * field is unchanged so utilization/compaction remain stable; callers that
     * want per-model budgets (e.g. upcoming Phase 6 image-token estimation,
     * Phase 7 chat-input usage indicator) call this method directly.
     */
    fun maxInputTokensFor(modelRef: String): Int {
        val window = modelCatalogService?.getContextWindow(modelRef, tier = currentTier())
        return window?.maxInputTokens ?: FALLBACK_MAX_INPUT_TOKENS
    }

    /**
     * The current Sourcegraph tier. Hard-coded to "enterprise" for v1 — the user's
     * instance is enterprise per probe baseline. A proper
     * `pluginSettings.getCurrentTier()` setting is deferred to v2 (the plan
     * called this out as a phantom API).
     */
    private fun currentTier(): String = "enterprise"

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
        var imageCount = 0
        systemPrompt?.content?.let { chars += it.length }
        for (msg in messages) {
            msg.content?.let { chars += it.length }
            msg.toolCalls?.forEach { tc ->
                chars += tc.function.name.length
                chars += tc.function.arguments.length
            }
            // Multimodal-agent Phase 6 — image parts cost a fixed estimate per
            // image (default ~1500 tokens). Authoritative cost is `usage.prompt_tokens`
            // from the API response; this estimate only pre-trues compaction
            // gating before the response is in.
            msg.parts?.forEach { p ->
                if (p is ContentPart.Image) imageCount++
                if (p is ContentPart.Text) chars += p.text.length
            }
        }
        val messageTokens = (chars / 3.5).toInt()
        val imageTokens = imageCount * IMAGE_TOKEN_ESTIMATE_DEFAULT
        val overheadTokens = (messages.size + 1) * 4 // +1 for system prompt
        return messageTokens + imageTokens + overheadTokens + toolDefinitionTokens
    }

    /**
     * Per-message token estimate including image-part cost.
     *
     * Multimodal-agent Phase 6 — used by Phase 7's chat-input usage indicator
     * and any future per-message budget gating. Returns the same value the
     * aggregate [tokenEstimate] would credit for this message.
     */
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
        return textTokens + imageTokens + 4 // per-message overhead
    }

    /**
     * Returns a compacted copy of [msg] with image parts stripped and a
     * placeholder appended to the text content. Multimodal-agent Phase 6
     * (Decision 6 — "Strip during compaction, replace with text placeholder").
     *
     * Compaction exists *because* we're over budget — preserving images
     * defeats the savings goal. Bytes stay on disk under the session's
     * `attachments/` dir; only the in-context payload loses the image part.
     *
     * Idempotent: messages without image parts pass through unchanged.
     *
     * Tested by `ContextManagerCatalogIntegrationTest "compaction strips
     * image parts and substitutes placeholder"`.
     */
    fun compactTurn(msg: ChatMessage): ChatMessage =
        if (msg.hasImageParts()) {
            val placeholder = " [image attached earlier; bytes preserved on disk]"
            val newContent = (msg.content ?: "") + placeholder
            msg.copy(parts = null, content = newContent.trim())
        } else {
            msg
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
                    // Cline pinning (tool-produced-images Phase 5): skip image-bearing
                    // messages — they're load-bearing context. Replacing the text
                    // content with a dedup notice strands the image part because
                    // the LLM can no longer see what the image refers to.
                    if (old.hasImageParts()) continue
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

        // Cline pinning (tool-produced-images Phase 5): if the candidate
        // truncation range contains any image-bearing message, skip the
        // truncation pass entirely. Re-sending the same screenshot every
        // turn is more expensive than keeping it pinned, and dropping it
        // mid-conversation strands tool outputs the LLM may need to refer
        // back to. Stage 3 (LLM summarization) is the escape hatch when
        // Stage 2 can't make progress.
        //
        // Approach (b) per the plan: skip-truncation. Approach (a)
        // (split-range around pinned indices) is correct but materially more
        // complex; conservative skip preserves byte-identical behavior for
        // non-image scenarios.
        val containsPinned = (rangeStart..rangeEnd).any { idx ->
            idx < messages.size && messages[idx].hasImageParts()
        }
        if (containsPinned) {
            LOG.info("[Context] Skipping Stage 2 truncation: candidate range [$rangeStart..$rangeEnd] contains image-bearing message(s) (Cline pinning rule)")
            return
        }

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
     * @param force when true, bypasses ALL utilization gates so a single user-driven
     *   click runs the full pipeline:
     *   - 70% entry floor → bypassed
     *   - Stage 1 30% savings exit → bypassed (always proceed to Stage 2)
     *   - Stage 2 85% gate → bypassed, and strategy switches to QUARTER for a bigger
     *     single-click drop instead of the conservative HALF used by auto-compaction
     *   - Stage 3 95% gate → bypassed when there are enough messages to summarize
     *     meaningfully (`> FORCE_STAGE3_MIN_MESSAGES`); the user explicitly authorized
     *     the LLM call by clicking
     *   Auto-compaction (force=false) keeps the conservative gating: HALF strategy,
     *   95% Stage 3 gate, 30% Stage 1 short-circuit — so the loop's preventive
     *   behavior is unchanged.
     */
    suspend fun compact(brain: LlmBrain, hookManager: HookManager? = null, force: Boolean = false) {
        lastCompactionRanStage3 = false
        val util = utilizationPercent()
        if (!force && util <= 70.0) return

        LOG.info("[Context] Compacting at ${"%.1f".format(util)}% utilization (${messageCount()} messages)${if (force) " [forced]" else ""}")

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

        // Cline's logic: if optimization saves >= 30%, skip truncation.
        // When forced, the user has explicitly asked for full cleanup, so we proceed
        // to truncation regardless of dedup savings.
        if (!force && percentSaved >= 0.30) return

        // Stage 2: Conversation truncation (from Cline). Forced manual compaction
        // bypasses the 85% inner gate — at low utilization there's still value in
        // dropping polluted middle messages (e.g. nudge-pair chains) the user wants gone.
        // Under force, use QUARTER (drop ~75% of middle) for a bigger single-click drop;
        // auto-compaction keeps the conservative HALF default.
        if (force || utilizationPercent() > 85.0) {
            val estimatedTokens = lastPromptTokens ?: tokenEstimate()
            val keep = when {
                force -> TruncationStrategy.QUARTER
                estimatedTokens / 2 > effectiveMaxInputTokens() -> TruncationStrategy.QUARTER
                else -> TruncationStrategy.HALF
            }
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

        // Stage 3: LLM summarization. Auto-compaction gates at >95% to avoid wasteful
        // API calls. Forced manual compaction unlocks Stage 3 whenever there are enough
        // messages to summarize meaningfully — the user explicitly authorized the LLM
        // call by clicking the compact button, and Stage 3 is what produces a "huge
        // drop" (replaces the early conversation with a single TASK/FILES/DONE/ERRORS/
        // PENDING summary message).
        val shouldRunStage3 = utilizationPercent() > 95.0 ||
            (force && messageCount() > FORCE_STAGE3_MIN_MESSAGES)
        if (shouldRunStage3) {
            val countBeforeSummarization = messageCount()
            llmSummarize(brain)
            invalidateTokens()
            val summarizedCount = countBeforeSummarization - messageCount()
            if (summarizedCount > 0) {
                lastCompactionRanStage3 = true
                onHistoryOverwrite?.invoke(messages.toList(), Pair(0, summarizedCount - 1))
            }
        }

        // After compaction: strip image parts from any remaining messages
        // (Multimodal-agent Phase 6 — Decision 6). Compaction exists *because*
        // we're over budget; preserving image bytes in-context defeats the
        // savings goal. Bytes stay on disk for replay if needed.
        stripImagePartsFromAllMessages()

        // After compaction: re-inject active skill and plan path so LLM retains them
        // (ported from Cline: skill content survives compaction via re-injection)
        reInjectActiveSkill()
        reInjectActivePlan()
    }

    /**
     * Walks the in-memory message list and replaces each image-bearing turn
     * with its [compactTurn] equivalent. Called at the tail of [compact] so
     * any image part that survived dedup + truncation + summarization is
     * stripped before the next API request.
     *
     * Multimodal-agent Phase 6 (Decision 6).
     */
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
            invalidateTokens()
            LOG.info("[Context] Stripped image parts from compacted messages (Decision 6)")
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
        /**
         * Cline-style pinning rule (tool-produced-images Phase 5).
         *
         * Any USER [ApiMessage] whose [ApiMessage.content] list contains a
         * [ContentBlock.ImageRef] is exempt from Stage 1 (duplicate file-read
         * dedup) and Stage 2 (middle-block truncation) of the compaction
         * pipeline. Vision tokens are expensive — re-sending the same
         * screenshot every turn is worse than keeping it pinned, and dropping
         * it mid-conversation strands tool outputs the LLM may need to refer
         * back to.
         *
         * NB: this only protects the message slot. If the user explicitly
         * runs a "compact conversation" command, Stage 3 LLM summarization
         * may still consolidate the slot — by that point the model has
         * already produced text describing the image, so loss is acceptable.
         *
         * The internal `messages` list is `ChatMessage`-typed (post-conversion
         * via `ApiMessage.toChatMessage()`); Stage 1 and Stage 2 use the
         * sibling `ChatMessage.hasImageParts()` helper to make the same
         * decision at that layer. This [ApiMessage]-level overload is the
         * canonical contract and is also useful for future callers (e.g. an
         * "Active Image" status indicator) that operate on ApiMessage.
         */
        fun isImageBearingMessage(message: ApiMessage): Boolean =
            message.content.any { it is ContentBlock.ImageRef }

        /**
         * Conservative fallback for [maxInputTokensFor] when the catalog is unreachable
         * or the model is unknown. 90K covers all known non-thinking enterprise tiers
         * (Sonnet 4.5 = 132K, Opus 4 = 200K, Gemini 2.5 = 1M, etc.) without overshooting
         * thinking-variant budgets (~93K) where the lower cap matters for compaction.
         *
         * Multimodal-agent Phase 2.
         */
        const val FALLBACK_MAX_INPUT_TOKENS = 90_000

        /**
         * Tools that signal the loop's exit and produce `LoopResult.Completed`. Both
         * leave the api conversation history with a trailing `[assistant w/ tool_call,
         * tool_result]` pair that needs to be collapsed to avoid sanitization merging
         * on follow-up turns and auto-iteration on resume. See
         * [collapseLastCompletionToolPair] and the mirror in `MessageStateHandler`.
         */
        val COMPLETION_TOOL_NAMES: Set<String> = setOf("attempt_completion", "task_report")

        /**
         * Per-image token estimate used by [tokenEstimate] and [estimateMessageTokens]
         * when a message carries an image part. Mirrors
         * `PluginSettings.imageTokenEstimateDefault`.
         *
         * The authoritative cost of an image is the API's `usage.prompt_tokens`
         * field on the response; this constant only pre-trues compaction gating
         * before the response is in (Multimodal-agent Phase 6).
         *
         * Anthropic charges roughly 765 + (W * H / 750) tokens per image — for
         * common screenshot dimensions (e.g. 1500×1000) that's around 2700; for
         * smaller stills (~640×480) closer to 1100. 1500 is a defensible mid-band
         * default. The chat-input usage indicator (Phase 7) will refine after
         * the first response sets `lastPromptTokens`.
         */
        const val IMAGE_TOKEN_ESTIMATE_DEFAULT = 1500

        /**
         * Minimum message count for Stage 3 (LLM summarization) to run under force=true.
         * Below this, summarization wastes an API call — there's not enough conversation
         * to meaningfully summarize. Floor preserves first user-asst pair (2) + at least
         * one removable user-asst pair (2) + recent tail user-asst pair (2) = 6.
         */
        private const val FORCE_STAGE3_MIN_MESSAGES = 6

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
