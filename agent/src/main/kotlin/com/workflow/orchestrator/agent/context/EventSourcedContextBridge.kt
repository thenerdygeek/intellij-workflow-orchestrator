package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.condenser.*
import com.workflow.orchestrator.agent.context.events.*
import com.workflow.orchestrator.core.model.ApiResult
import java.io.File
import java.util.UUID

/**
 * Bridge between the legacy [ContextManager]-based loop and the new event-sourced
 * context management system ([EventStore] + [CondenserPipeline] + [ConversationMemory]).
 *
 * **Dual-write strategy:** Every mutation writes to BOTH the legacy ContextManager AND
 * the EventStore. This allows the existing loop (BudgetEnforcer, LoopGuard, etc.) to
 * continue working while we incrementally migrate to event-sourced context.
 *
 * **Migration path:**
 * 1. [Phase 1 — current] Dual-write: both systems receive all events. ContextManager
 *    drives LLM calls. EventStore records for persistence and future condensation.
 * 2. [Phase 2 — next] EventStore drives LLM calls via ConversationMemory. ContextManager
 *    kept for BudgetEnforcer compatibility only.
 * 3. [Phase 3 — final] Remove ContextManager entirely. BudgetEnforcer reads from EventStore.
 *
 * Thread safety: Same as ContextManager — single coroutine context (the ReAct loop).
 */
class EventSourcedContextBridge(
    val contextManager: ContextManager,
    val eventStore: EventStore,
    private val condenserPipeline: CondenserPipeline,
    private val conversationMemory: ConversationMemory,
    private val config: ContextManagementConfig = ContextManagementConfig.DEFAULT
) {
    /** The initial user message for the current turn (needed by ConversationMemory). */
    private var initialUserAction: MessageAction? = null

    /** Tracks the response group ID for current LLM response's tool calls. */
    private var currentResponseGroupId: String = UUID.randomUUID().toString().take(8)

    /** Count of condensation loops to detect infinite condensation. */
    private var condensationLoopCount: Int = 0

    // =========================================================================
    // System prompt and initialization
    // =========================================================================

    /**
     * Add the system prompt as the first event. Called once during session initialization.
     */
    fun addSystemPrompt(content: String) {
        // Legacy path
        contextManager.addMessage(ChatMessage(role = "system", content = content))
        // Event-sourced path
        eventStore.add(SystemMessageAction(content = content), EventSource.SYSTEM)
    }

    // =========================================================================
    // User messages
    // =========================================================================

    /**
     * Add a user message. Also records it as the initial user action for the current turn.
     */
    fun addUserMessage(content: String) {
        // Legacy path
        contextManager.addMessage(ChatMessage(role = "user", content = content))
        // Event-sourced path
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
        // Legacy path
        contextManager.addAssistantMessage(message)
        // Event-sourced path
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
        // Legacy path — the full message with tool_calls
        contextManager.addAssistantMessage(message)

        // Event-sourced path — create individual ToolAction events
        currentResponseGroupId = UUID.randomUUID().toString().take(8)
        val toolCalls = message.toolCalls ?: return currentResponseGroupId

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
     * Add a tool result. Dual-writes to both ContextManager and EventStore.
     */
    fun addToolResult(toolCallId: String, content: String, summary: String, toolName: String = "unknown") {
        // Legacy path
        contextManager.addToolResult(toolCallId, content, summary)
        // Event-sourced path
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
        // Legacy path
        contextManager.addToolResult(toolCallId, content, summary)
        // Event-sourced path
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
        // Legacy path
        contextManager.addMessage(ChatMessage(role = "system", content = content))
        // Event-sourced path
        eventStore.add(SystemMessageAction(content = content), EventSource.SYSTEM)
    }

    /**
     * Add an arbitrary ChatMessage (used by LoopGuard, BackpressureGate, etc.).
     * Routes to the appropriate event type based on role.
     */
    fun addMessage(message: ChatMessage) {
        // Legacy path
        contextManager.addMessage(message)
        // Event-sourced path
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
    // Condensation (Phase 2 — future: replace ContextManager compression)
    // =========================================================================

    /**
     * Run the condenser pipeline on the current event history.
     *
     * In Phase 1 (dual-write), this is informational — the result is recorded but
     * ContextManager's own compression still drives the actual context window.
     *
     * In Phase 2, this will replace ContextManager.compress() entirely.
     *
     * @return true if condensation occurred, false if the view was passed through
     */
    fun runCondensation(): Boolean {
        val allEvents = eventStore.all()
        val view = View.fromEvents(allEvents)

        val utilizationPercent = contextManager.currentTokens.toDouble() /
            contextManager.effectiveMaxInputTokens.toDouble()

        val condenserContext = CondenserContext(
            view = view,
            tokenUtilization = utilizationPercent,
            effectiveBudget = contextManager.effectiveMaxInputTokens,
            currentTokens = contextManager.currentTokens
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
    // Delegation helpers
    // =========================================================================

    /**
     * Get messages for LLM call. In Phase 1, delegates to ContextManager.
     * In Phase 2, will use ConversationMemory to convert from EventStore.
     */
    fun getMessages(): List<ChatMessage> {
        // Phase 1: delegate to legacy ContextManager
        return contextManager.getMessages()
    }

    /**
     * Get messages via the event-sourced path (ConversationMemory).
     * Available for testing and Phase 2 migration.
     */
    fun getMessagesFromEvents(): List<ChatMessage> {
        val allEvents = eventStore.all()
        val view = View.fromEvents(allEvents)
        val initial = initialUserAction ?: MessageAction(content = "[No initial message]")
        return conversationMemory.processEvents(
            view.events,
            initial,
            view.forgottenEventIds
        )
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

    /**
     * Reconcile token count with actual API-reported tokens.
     */
    fun reconcileWithActualTokens(actualPromptTokens: Int) {
        contextManager.reconcileWithActualTokens(actualPromptTokens)
    }

    // =========================================================================
    // Delegated ContextManager properties (for backward compatibility)
    // =========================================================================

    /** Current token count. */
    val currentTokens: Int get() = contextManager.currentTokens

    /** Effective max input tokens. */
    val effectiveMaxInputTokens: Int get() = contextManager.effectiveMaxInputTokens

    /** Whether budget is critically low. */
    fun isBudgetCritical(): Boolean = contextManager.isBudgetCritical()

    /** Remaining token budget. */
    fun remainingBudget(): Int = contextManager.remainingBudget()

    /** Has a plan anchor. */
    val hasPlanAnchor: Boolean get() = contextManager.hasPlanAnchor

    /** Message count. */
    val messageCount: Int get() = contextManager.messageCount

    /** Update reserved tokens. */
    fun updateReservedTokens(newReserved: Int) = contextManager.updateReservedTokens(newReserved)

    /** Count system warnings. */
    fun countSystemWarnings(): Int = contextManager.countSystemWarnings()

    /** Remove oldest system warning. */
    fun removeOldestSystemWarning(): Boolean = contextManager.removeOldestSystemWarning()

    // =========================================================================
    // Delegated anchors (still managed by ContextManager in Phase 1)
    // =========================================================================

    fun setPlanAnchor(message: ChatMessage?) = contextManager.setPlanAnchor(message)
    fun setSkillAnchor(message: ChatMessage?) = contextManager.setSkillAnchor(message)
    fun setMentionAnchor(message: ChatMessage?) = contextManager.setMentionAnchor(message)
    fun setGuardrailsAnchor(message: ChatMessage?) = contextManager.setGuardrailsAnchor(message)
    fun updateFactsAnchor() = contextManager.updateFactsAnchor()

    // =========================================================================
    // Delegated compression (Phase 1: still uses ContextManager)
    // =========================================================================

    fun compress() = contextManager.compress()
    suspend fun compressWithLlm(brain: LlmBrain) = contextManager.compressWithLlm(brain)
    fun pruneOldToolResults() = contextManager.pruneOldToolResults()

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
            "jira", "bamboo", "sonar", "bitbucket", "git", "runtime", "debug", "spring", "build" -> {
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
            contextManager: ContextManager,
            sessionDir: File?,
            config: ContextManagementConfig = ContextManagementConfig.DEFAULT,
            summarizationClient: SummarizationClient? = null
        ): EventSourcedContextBridge {
            val eventStore = EventStore(sessionDir)
            val pipeline = CondenserFactory.create(config, summarizationClient)
            val conversationMemory = ConversationMemory()

            return EventSourcedContextBridge(
                contextManager = contextManager,
                eventStore = eventStore,
                condenserPipeline = pipeline,
                conversationMemory = conversationMemory,
                config = config
            )
        }

        /**
         * Create a bridge for resuming a session (loading from disk).
         */
        fun loadFromDisk(
            contextManager: ContextManager,
            sessionDir: File,
            config: ContextManagementConfig = ContextManagementConfig.DEFAULT,
            summarizationClient: SummarizationClient? = null
        ): EventSourcedContextBridge {
            val eventStore = EventStore.loadFromJsonl(sessionDir)
            val pipeline = CondenserFactory.create(config, summarizationClient)
            val conversationMemory = ConversationMemory()

            return EventSourcedContextBridge(
                contextManager = contextManager,
                eventStore = eventStore,
                condenserPipeline = pipeline,
                conversationMemory = conversationMemory,
                config = config
            )
        }
    }
}
