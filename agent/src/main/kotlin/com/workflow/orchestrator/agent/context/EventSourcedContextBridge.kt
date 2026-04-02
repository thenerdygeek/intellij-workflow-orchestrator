package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.condenser.*
import com.workflow.orchestrator.agent.context.events.*
import com.workflow.orchestrator.agent.runtime.ChangeLedger
import com.workflow.orchestrator.core.model.ApiResult
import java.io.File
import java.util.UUID

/**
 * Result of running the full condenser pipeline → ConversationMemory path.
 *
 * Either the pipeline produced ready-to-use [ChatMessage] objects, or it determined
 * that condensation is needed before the next LLM call can proceed.
 */
sealed class CondenserOutcome {
    /** The condenser pipeline produced messages ready for an LLM call. */
    data class Messages(val messages: List<ChatMessage>) : CondenserOutcome()
    /** The condenser pipeline determined condensation is needed first. */
    data class NeedsCondensation(val action: CondensationAction) : CondenserOutcome()
}

/**
 * Event-sourced context management — the single source of truth for the conversation.
 *
 * Manages the full conversation lifecycle through an append-only [EventStore],
 * with a [CondenserPipeline] for context window management and [ConversationMemory]
 * for converting events to LLM-ready messages.
 *
 * **Anchor management:** Compression-proof anchors (plan, skills, guardrails, facts,
 * mentions, change ledger) are stored directly in this bridge and appended to every
 * LLM call's message list, ensuring they survive condensation.
 *
 * Thread safety: Single coroutine context (the ReAct loop).
 */
class EventSourcedContextBridge(
    val eventStore: EventStore,
    private val condenserPipeline: CondenserPipeline,
    private val conversationMemory: ConversationMemory,
    private val config: ContextManagementConfig = ContextManagementConfig.DEFAULT,
    private val maxInputTokens: Int = com.workflow.orchestrator.agent.settings.AgentSettings.DEFAULTS.maxInputTokens,
    private var reservedTokens: Int = 0
) {
    /** The initial user message for the current turn (needed by ConversationMemory). */
    private var initialUserAction: MessageAction? = null

    /** Tracks the response group ID for current LLM response's tool calls. */
    private var currentResponseGroupId: String = UUID.randomUUID().toString().take(8)

    /** Count of condensation loops to detect infinite condensation. */
    private var condensationLoopCount: Int = 0

    /**
     * Token count last reported by the LLM API (via usage.promptTokens).
     * Updated after each LLM call via [updateTokensFromUsage]. Used by [getMessagesViaCondenser]
     * to build an accurate [CondenserContext] without relying on heuristic estimates.
     */
    private var lastReportedPromptTokens: Int = 0

    // =========================================================================
    // Anchor storage (compression-proof, survive condensation)
    // =========================================================================

    /** Dedicated plan anchor — survives compression, updated in-place. */
    private var planAnchor: ChatMessage? = null

    /** Whether an active plan exists in the context. */
    val hasPlanAnchor: Boolean get() = planAnchor != null

    /** Dedicated skill anchor — survives compression. */
    private var skillAnchor: ChatMessage? = null

    /** Dedicated mention anchor — file content from @ mentions, survives compression. */
    private var mentionAnchor: ChatMessage? = null

    /** Dedicated facts anchor — compression-proof structured knowledge. */
    private var factsAnchor: ChatMessage? = null

    /** Dedicated guardrails anchor — compression-proof learned constraints. */
    private var guardrailsAnchor: ChatMessage? = null

    /** Compression-proof anchor containing the change ledger summary. */
    private var changeLedgerAnchor: ChatMessage? = null

    /** Facts store for recording verified findings that survive compression. */
    var factsStore: FactsStore? = null

    /** Disk spillover for full tool outputs. */
    var toolOutputStore: ToolOutputStore? = null

    // =========================================================================
    // System prompt and initialization
    // =========================================================================

    /**
     * Add the system prompt as the first event. Called once during session initialization.
     */
    fun addSystemPrompt(content: String) {
        eventStore.add(SystemMessageAction(content = content), EventSource.SYSTEM)
    }

    // =========================================================================
    // User messages
    // =========================================================================

    /**
     * Add a user message. Also records it as the initial user action for the current turn.
     */
    fun addUserMessage(content: String) {
        val action = MessageAction(content = content)
        val stored = eventStore.add(action, EventSource.USER)
        initialUserAction = stored as MessageAction
    }

    // =========================================================================
    // Assistant messages (LLM responses)
    // =========================================================================

    /**
     * Add an assistant message (text response with no tool calls).
     */
    fun addAssistantMessage(message: ChatMessage) {
        if (!message.content.isNullOrBlank()) {
            eventStore.add(
                MessageAction(content = message.content!!),
                EventSource.AGENT
            )
        }
    }

    /**
     * Record assistant tool calls as events. Called after LLM returns tool_calls.
     * The [message] is the full assistant ChatMessage including tool_calls.
     * Returns the response group ID for correlating tool results.
     */
    fun addAssistantToolCalls(message: ChatMessage): String {
        currentResponseGroupId = UUID.randomUUID().toString().take(8)
        val toolCalls = message.toolCalls ?: return currentResponseGroupId

        // Also record the text content if present (thinking before tool calls)
        if (!message.content.isNullOrBlank()) {
            eventStore.add(
                MessageAction(content = message.content!!),
                EventSource.AGENT
            )
        }

        for (tc in toolCalls) {
            val event = createToolAction(tc, currentResponseGroupId)
            eventStore.add(event, EventSource.AGENT)
        }

        return currentResponseGroupId
    }

    // =========================================================================
    // Tool results
    // =========================================================================

    /**
     * Add a tool result.
     */
    fun addToolResult(toolCallId: String, content: String, summary: String, toolName: String = "unknown") {
        eventStore.add(
            ToolResultObservation(
                toolCallId = toolCallId,
                content = content,
                isError = false,
                toolName = toolName
            ),
            EventSource.SYSTEM
        )
    }

    /**
     * Add a tool error result.
     */
    fun addToolError(toolCallId: String, content: String, summary: String, toolName: String = "unknown") {
        eventStore.add(
            ToolResultObservation(
                toolCallId = toolCallId,
                content = content,
                isError = true,
                toolName = toolName
            ),
            EventSource.SYSTEM
        )
    }

    // =========================================================================
    // System messages (warnings, nudges, etc.)
    // =========================================================================

    /**
     * Add a system message (LoopGuard nudges, budget warnings, etc.).
     */
    fun addSystemMessage(content: String) {
        eventStore.add(SystemMessageAction(content = content), EventSource.SYSTEM)
    }

    /**
     * Add an arbitrary ChatMessage (used by LoopGuard, BackpressureGate, etc.).
     * Routes to the appropriate event type based on role.
     */
    fun addMessage(message: ChatMessage) {
        when (message.role) {
            "system" -> eventStore.add(
                SystemMessageAction(content = message.content ?: ""),
                EventSource.SYSTEM
            )
            "user" -> eventStore.add(
                MessageAction(content = message.content ?: ""),
                EventSource.USER
            )
            "assistant" -> eventStore.add(
                MessageAction(content = message.content ?: ""),
                EventSource.AGENT
            )
            "tool" -> {
                // Tool messages should use addToolResult instead, but handle gracefully
                eventStore.add(
                    ToolResultObservation(
                        toolCallId = message.toolCallId ?: "unknown",
                        content = message.content ?: "",
                        isError = false,
                        toolName = "unknown"
                    ),
                    EventSource.SYSTEM
                )
            }
        }
    }

    // =========================================================================
    // Structured events (facts, plans, skills, guardrails, mentions)
    // =========================================================================

    /**
     * Record a fact event.
     */
    fun recordFact(factType: String, path: String?, content: String) {
        eventStore.add(
            FactRecordedAction(factType = factType, path = path, content = content),
            EventSource.AGENT
        )
    }

    /**
     * Record a plan update event.
     */
    fun recordPlanUpdate(planJson: String) {
        eventStore.add(
            PlanUpdatedAction(planJson = planJson),
            EventSource.AGENT
        )
    }

    /**
     * Record a skill activation event.
     */
    fun recordSkillActivated(skillName: String, content: String) {
        eventStore.add(
            SkillActivatedAction(skillName = skillName, content = content),
            EventSource.AGENT
        )
    }

    /**
     * Record a skill deactivation event.
     */
    fun recordSkillDeactivated(skillName: String) {
        eventStore.add(
            SkillDeactivatedAction(skillName = skillName),
            EventSource.AGENT
        )
    }

    /**
     * Record a guardrail event.
     */
    fun recordGuardrail(rule: String) {
        eventStore.add(
            GuardrailRecordedAction(rule = rule),
            EventSource.SYSTEM
        )
    }

    /**
     * Record a mention event (@ mention with file/folder content).
     */
    fun recordMention(paths: List<String>, content: String) {
        eventStore.add(
            MentionAction(paths = paths, content = content),
            EventSource.USER
        )
    }

    /**
     * Record an agent delegate action.
     */
    fun recordDelegate(agentType: String, prompt: String, thought: String? = null) {
        eventStore.add(
            DelegateAction(agentType = agentType, prompt = prompt, thought = thought),
            EventSource.AGENT
        )
    }

    // =========================================================================
    // Condensation (EventStore drives LLM calls)
    // =========================================================================

    /**
     * Run the full condenser pipeline → ConversationMemory path and return
     * [ChatMessage] objects ready for an LLM call, or a [CondenserOutcome.NeedsCondensation]
     * if condensation must happen before the call can proceed.
     *
     * **Algorithm:**
     * 1. Build a [View] from all events in the [EventStore].
     * 2. Compute token utilization using the last API-reported prompt token count.
     * 3. Run the condenser pipeline on the view.
     * 4. If the pipeline returns [Condensation], return [CondenserOutcome.NeedsCondensation]
     *    so the caller can add the action to the event store and re-step.
     * 5. If the pipeline returns [CondenserView], convert events → messages via
     *    [ConversationMemory] and return [CondenserOutcome.Messages].
     *
     * Infinite condensation loops are guarded by [condensationLoopCount].
     */
    fun getMessagesViaCondenser(): CondenserOutcome {
        val allEvents = eventStore.all()
        val view = View.fromEvents(allEvents)

        // Use the API-authoritative token count when available; fall back to heuristic
        val currentTokens = if (lastReportedPromptTokens > 0) lastReportedPromptTokens
                            else estimateCurrentTokens()
        val effectiveBudget = effectiveMaxInputTokens
        val tokenUtilization = if (effectiveBudget > 0) currentTokens.toDouble() / effectiveBudget.toDouble()
                               else 0.0

        val condenserContext = CondenserContext(
            view = view,
            tokenUtilization = tokenUtilization,
            effectiveBudget = effectiveBudget,
            currentTokens = currentTokens
        )

        return when (val result = condenserPipeline.condense(condenserContext)) {
            is Condensation -> {
                condensationLoopCount++
                if (condensationLoopCount > config.condensationLoopThreshold) {
                    // Condensation loop protection: fall back to ConversationMemory directly
                    condensationLoopCount = 0
                    val initial = initialUserAction ?: MessageAction(content = "[No initial message]")
                    val messages = conversationMemory.processEvents(view.events, initial, view.forgottenEventIds).toMutableList()
                    messages.addAll(getAnchorMessages())
                    CondenserOutcome.Messages(messages)
                } else {
                    CondenserOutcome.NeedsCondensation(result.action)
                }
            }
            is CondenserView -> {
                condensationLoopCount = 0
                val initial = initialUserAction ?: MessageAction(content = "[No initial message]")
                val messages = conversationMemory.processEvents(
                    result.view.events,
                    initial,
                    result.view.forgottenEventIds
                ).toMutableList()
                messages.addAll(getAnchorMessages())
                CondenserOutcome.Messages(messages)
            }
        }
    }

    /**
     * Update the bridge's token tracking state from the LLM API's reported usage.
     *
     * This is the token reconciliation step: after each LLM call, the caller passes
     * `usage.promptTokens` here so that [getMessagesViaCondenser] can compute accurate
     * token utilization in the next iteration.
     *
     * @param promptTokens The `prompt_tokens` value from the LLM API's usage object
     */
    fun updateTokensFromUsage(promptTokens: Int) {
        if (promptTokens > 0) {
            lastReportedPromptTokens = promptTokens
        }
    }

    /**
     * Current token utilization as a fraction (0.0–1.0), using the last API-reported
     * prompt token count when available, falling back to the heuristic estimate.
     */
    val tokenUtilization: Double
        get() {
            val current = if (lastReportedPromptTokens > 0) lastReportedPromptTokens
                          else estimateCurrentTokens()
            val budget = effectiveMaxInputTokens
            return if (budget > 0) current.toDouble() / budget.toDouble() else 0.0
        }

    /**
     * Run the condenser pipeline on the current event history.
     *
     * @return true if condensation occurred, false if the view was passed through
     */
    fun runCondensation(): Boolean {
        val allEvents = eventStore.all()
        val view = View.fromEvents(allEvents)

        val currentTokens = if (lastReportedPromptTokens > 0) lastReportedPromptTokens
                            else estimateCurrentTokens()
        val budget = effectiveMaxInputTokens
        val utilizationPercent = if (budget > 0) currentTokens.toDouble() / budget.toDouble() else 0.0

        val condenserContext = CondenserContext(
            view = view,
            tokenUtilization = utilizationPercent,
            effectiveBudget = budget,
            currentTokens = currentTokens
        )

        when (val result = condenserPipeline.condense(condenserContext)) {
            is Condensation -> {
                // Record the condensation action in the event store
                eventStore.add(result.action, EventSource.SYSTEM)
                condensationLoopCount++

                // Check for condensation loop (infinite condensation detection)
                if (condensationLoopCount > config.condensationLoopThreshold) {
                    eventStore.add(
                        ErrorObservation(content = "Condensation loop detected after $condensationLoopCount iterations"),
                        EventSource.SYSTEM
                    )
                    condensationLoopCount = 0
                    return false
                }

                return true
            }
            is CondenserView -> {
                condensationLoopCount = 0
                return false
            }
        }
    }

    /**
     * Request condensation (e.g., from BudgetEnforcer when context is near full).
     * Records a CondensationRequestAction in the event store.
     */
    fun requestCondensation() {
        eventStore.add(CondensationRequestAction(), EventSource.SYSTEM)
    }

    // =========================================================================
    // Message retrieval
    // =========================================================================

    /**
     * Get messages via the event-sourced path (ConversationMemory).
     * This is the primary path for LLM calls.
     */
    fun getMessages(): List<ChatMessage> {
        val allEvents = eventStore.all()
        val view = View.fromEvents(allEvents)
        val initial = initialUserAction ?: MessageAction(content = "[No initial message]")
        val messages = conversationMemory.processEvents(
            view.events,
            initial,
            view.forgottenEventIds
        ).toMutableList()
        messages.addAll(getAnchorMessages())
        return messages
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    /**
     * Flush new events to JSONL on disk.
     */
    fun flushEvents() {
        eventStore.appendToJsonl()
    }

    // =========================================================================
    // Token management
    // =========================================================================

    /** Current token count (API-reported, or heuristic estimate). */
    val currentTokens: Int get() = if (lastReportedPromptTokens > 0) lastReportedPromptTokens
                                   else estimateCurrentTokens()

    /** Effective max input tokens. */
    val effectiveMaxInputTokens: Int get() = maxInputTokens

    /** Whether budget is critically low. */
    fun isBudgetCritical(): Boolean = remainingBudget() < (effectiveBudget * 0.10)

    /** Remaining token budget. */
    fun remainingBudget(): Int = effectiveBudget - currentTokens

    /** Message count (from event store). */
    val messageCount: Int get() = eventStore.all().size

    /** Update reserved tokens. */
    fun updateReservedTokens(newReserved: Int) {
        reservedTokens = newReserved
    }

    /** Count system warnings. */
    fun countSystemWarnings(): Int {
        return eventStore.all().count { event ->
            event is SystemMessageAction && event.content.contains("system_warning")
        }
    }

    /** Remove oldest system warning. */
    fun removeOldestSystemWarning(): Boolean {
        // System warnings are in the event store but we can't remove events from an append-only store.
        // Instead, we add a forget action that masks the oldest warning.
        val events = eventStore.all()
        val warningEvent = events.firstOrNull { event ->
            event is SystemMessageAction && event.content.contains("system_warning")
        }
        if (warningEvent != null) {
            // Record a "forget" action to mask this event in subsequent views
            eventStore.add(
                SystemMessageAction(content = "[Warning dismissed]"),
                EventSource.SYSTEM
            )
            return true
        }
        return false
    }

    /** Effective budget after subtracting reserved tokens. */
    private val effectiveBudget: Int get() = maxInputTokens - reservedTokens

    /** Estimate current tokens from events (heuristic fallback). */
    private fun estimateCurrentTokens(): Int {
        val messages = getMessagesRaw()
        return TokenEstimator.estimate(messages)
    }

    /** Get messages without anchors (to avoid recursion in token estimation). */
    private fun getMessagesRaw(): List<ChatMessage> {
        val allEvents = eventStore.all()
        val view = View.fromEvents(allEvents)
        val initial = initialUserAction ?: MessageAction(content = "[No initial message]")
        return conversationMemory.processEvents(view.events, initial, view.forgottenEventIds)
    }

    // =========================================================================
    // Anchor management
    // =========================================================================

    fun setPlanAnchor(message: ChatMessage?) {
        planAnchor = message
    }

    fun setSkillAnchor(message: ChatMessage?) {
        skillAnchor = message
    }

    fun setMentionAnchor(message: ChatMessage?) {
        mentionAnchor = message
    }

    fun setGuardrailsAnchor(message: ChatMessage?) {
        guardrailsAnchor = message
    }

    /**
     * Update the facts anchor from the current FactsStore state.
     */
    fun updateFactsAnchor() {
        val store = factsStore ?: return
        val contextStr = store.toContextString()
        factsAnchor = if (contextStr.isNotEmpty()) {
            ChatMessage(role = "system", content = contextStr)
        } else null
    }

    /**
     * Update the change ledger anchor from the current ChangeLedger state.
     */
    fun updateChangeLedgerAnchor(changeLedger: ChangeLedger) {
        val contextStr = changeLedger.toContextString()
        changeLedgerAnchor = if (contextStr.isNotEmpty()) {
            ChatMessage(role = "system", content = "<change_ledger>\n$contextStr\n</change_ledger>")
        } else null
    }

    /**
     * Return all compression-proof anchor messages in their display order.
     *
     * Order: background anchors first (skill, mention, facts, guardrails,
     * changeLedger), then plan last (recency attention for U-shaped attention bias).
     */
    fun getAnchorMessages(): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        skillAnchor?.let { result.add(it) }
        mentionAnchor?.let { result.add(it) }
        factsAnchor?.let { result.add(it) }
        guardrailsAnchor?.let { result.add(it) }
        changeLedgerAnchor?.let { result.add(it) }
        planAnchor?.let { result.add(it) }
        return result
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Convert an LLM ToolCall into the appropriate ToolAction event type.
     */
    private fun createToolAction(tc: ToolCall, responseGroupId: String): ToolAction {
        val name = tc.function.name
        val args = tc.function.arguments

        return when (name) {
            "read_file" -> {
                val path = extractJsonString(args, "path") ?: ""
                FileReadAction(toolCallId = tc.id, responseGroupId = responseGroupId, path = path)
            }
            "edit_file" -> {
                val path = extractJsonString(args, "path") ?: ""
                val oldStr = extractJsonString(args, "old_string") ?: extractJsonString(args, "old_str")
                val newStr = extractJsonString(args, "new_string") ?: extractJsonString(args, "new_str")
                FileEditAction(toolCallId = tc.id, responseGroupId = responseGroupId, path = path, oldStr = oldStr, newStr = newStr)
            }
            "run_command" -> {
                val command = extractJsonString(args, "command") ?: ""
                val cwd = extractJsonString(args, "cwd")
                CommandRunAction(toolCallId = tc.id, responseGroupId = responseGroupId, command = command, cwd = cwd)
            }
            "search_code" -> {
                val query = extractJsonString(args, "query") ?: ""
                val path = extractJsonString(args, "path")
                SearchCodeAction(toolCallId = tc.id, responseGroupId = responseGroupId, query = query, path = path)
            }
            "diagnostics" -> {
                val path = extractJsonString(args, "path")
                DiagnosticsAction(toolCallId = tc.id, responseGroupId = responseGroupId, path = path)
            }
            // Meta-tools: jira, bamboo, sonar, bitbucket, git, runtime, debug, spring, build
            "jira", "bamboo_builds", "bamboo_plans", "sonar", "bitbucket_pr", "bitbucket_review", "bitbucket_repo", "git", "runtime_config", "runtime_exec", "debug_breakpoints", "debug_step", "debug_inspect", "spring", "build" -> {
                val actionName = extractJsonString(args, "action") ?: "unknown"
                MetaToolAction(
                    toolCallId = tc.id, responseGroupId = responseGroupId,
                    toolName = name, actionName = actionName, arguments = args
                )
            }
            else -> GenericToolAction(
                toolCallId = tc.id, responseGroupId = responseGroupId,
                toolName = name, arguments = args
            )
        }
    }

    /**
     * Simple JSON string extraction without pulling in a JSON library.
     * Handles: "key":"value" and "key": "value"
     */
    private fun extractJsonString(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"]*?)"""")
        return regex.find(json)?.groupValues?.get(1)
    }

    companion object {
        /**
         * Create a bridge for a new session.
         */
        fun create(
            sessionDir: File?,
            config: ContextManagementConfig = ContextManagementConfig.DEFAULT,
            summarizationClient: SummarizationClient? = null,
            maxInputTokens: Int = com.workflow.orchestrator.agent.settings.AgentSettings.DEFAULTS.maxInputTokens,
            reservedTokens: Int = 0
        ): EventSourcedContextBridge {
            val eventStore = EventStore(sessionDir)
            val pipeline = CondenserFactory.create(config, summarizationClient)
            val conversationMemory = ConversationMemory()

            return EventSourcedContextBridge(
                eventStore = eventStore,
                condenserPipeline = pipeline,
                conversationMemory = conversationMemory,
                config = config,
                maxInputTokens = maxInputTokens,
                reservedTokens = reservedTokens
            )
        }

        /**
         * Create a bridge for resuming a session (loading from disk).
         */
        fun loadFromDisk(
            sessionDir: File,
            config: ContextManagementConfig = ContextManagementConfig.DEFAULT,
            summarizationClient: SummarizationClient? = null,
            maxInputTokens: Int = com.workflow.orchestrator.agent.settings.AgentSettings.DEFAULTS.maxInputTokens,
            reservedTokens: Int = 0
        ): EventSourcedContextBridge {
            val eventStore = EventStore.loadFromJsonl(sessionDir)
            val pipeline = CondenserFactory.create(config, summarizationClient)
            val conversationMemory = ConversationMemory()

            return EventSourcedContextBridge(
                eventStore = eventStore,
                condenserPipeline = pipeline,
                conversationMemory = conversationMemory,
                config = config,
                maxInputTokens = maxInputTokens,
                reservedTokens = reservedTokens
            )
        }
    }
}
