package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.CondenserOutcome
import com.workflow.orchestrator.agent.context.EventSourcedContextBridge
import com.workflow.orchestrator.agent.context.Fact
import com.workflow.orchestrator.agent.context.FactType
import com.workflow.orchestrator.agent.context.FactsStore
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.context.events.CondensationRequestAction
import com.workflow.orchestrator.agent.context.events.EventSource
import com.workflow.orchestrator.agent.orchestrator.AgentProgress
import com.workflow.orchestrator.agent.orchestrator.ToolCallInfo
import com.workflow.orchestrator.agent.security.CredentialRedactor
import com.workflow.orchestrator.agent.security.OutputValidator
import com.workflow.orchestrator.agent.security.SecurityViolationException
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.util.AgentStringUtils
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Result of a single-agent session execution.
 */
sealed class SingleAgentResult {
    /** Task completed successfully in a single agent pass. */
    data class Completed(
        val content: String,
        val summary: String,
        val tokensUsed: Int,
        val artifacts: List<String>,
        val snapshotRef: String? = null,
        val scorecard: SessionScorecard? = null
    ) : SingleAgentResult()

    /** Task failed with an error. */
    data class Failed(
        val error: String,
        val tokensUsed: Int,
        val snapshotRef: String? = null,
        val scorecard: SessionScorecard? = null
    ) : SingleAgentResult()

    /** Context exhausted but state externalized for rotation to a new session. */
    data class ContextRotated(
        val summary: String,
        val rotationStatePath: String,
        val tokensUsed: Int,
        val scorecard: SessionScorecard? = null
    ) : SingleAgentResult()

}

/**
 * The default execution path: a single ReAct loop with ALL tools available.
 *
 * Unlike [WorkerSession] which is scoped to a specific worker type with filtered tools,
 * SingleAgentSession gives the LLM access to every registered tool. The LLM decides
 * whether to analyze, code, review, or interact with enterprise tools — all in one
 * conversation.
 *
 * Includes:
 * - [BudgetEnforcer] check before each LLM call
 * - [LoopGuard] for loop detection, error nudges, and auto-verification
 * - Sliding window compression (COMPRESS) and budget termination (TERMINATE)
 * - Graceful retry on rate limits (429) and context length exceeded
 * - [OutputValidator] on final content
 * - Progress callbacks for each iteration
 *
 * Max iterations: 50 (higher than WorkerSession's 10, since this handles full tasks).
 */
class SingleAgentSession(
    val cancelled: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
    /** Per-session metrics collector — records tool calls, circuit breaker, session counters. */
    val metrics: AgentMetrics = AgentMetrics(),
    /** Structured JSONL file logger — null-safe so callers that don't wire it pay zero cost. */
    private val agentFileLogger: AgentFileLogger? = null
) {
    /** Tracks consecutive iterations where tool calls were malformed/empty. Reset on success. */
    private var consecutiveMalformedRetries = 0
    /** Set to true after MAX_MALFORMED_RETRIES to force text-only response from LLM. */
    private var forceTextOnly = false

    // --- Completion gatekeeper state ---
    /** Tracks consecutive iterations with no tool calls (for nudge / implicit completion). */
    var consecutiveNoToolResponses = 0
    /** Completion gatekeeper — initialized inside execute() where selfCorrectionGate/loopGuard are available. */
    var completionGatekeeper: CompletionGatekeeper? = null

    // --- Session-level quality signal counters (for scorecard) ---
    /** Cumulative input tokens across all LLM calls in this session. */
    var sessionInputTokens: Long = 0L; private set
    /** Cumulative output tokens across all LLM calls in this session. */
    var sessionOutputTokens: Long = 0L; private set
    /** Count of hallucination flags from OutputValidator. */
    var hallucinationFlags: Int = 0; private set
    /** Count of credential leak detections from CredentialRedactor. */
    var credentialLeakAttempts: Int = 0; private set
    /** Count of doom loop detections from LoopGuard. */
    var doomLoopTriggers: Int = 0; private set
    /** Count of guardrail hits. */
    var guardrailHits: Int = 0; private set
    /** Task description for the current session (set by execute()). */
    private var currentTask: String = ""
    companion object {
        private val LOG = Logger.getInstance(SingleAgentSession::class.java)
        private val json = Json { ignoreUnknownKeys = true }
        /** Max retries for rate limits (429) and server errors (5xx). */
        private const val MAX_RETRIES = 5
        /** Base backoff for exponential retry with jitter. */
        private const val BASE_BACKOFF_MS = 1000L
        /** Maximum backoff cap. */
        private const val MAX_BACKOFF_MS = 30000L
        /** Max consecutive malformed tool call retries before forcing text-only. */
        private const val MAX_MALFORMED_RETRIES = 3
        /** Max consecutive no-tool-call nudges before switching to forceTextOnly and allowing implicit completion. */
        private const val MAX_NO_TOOL_NUDGES = 4

        /** Core tools kept during context reduction (when context_length_exceeded). */
        val CORE_TOOL_NAMES = setOf("read_file", "edit_file", "search_code", "run_command", "diagnostics", "agent", "think", "attempt_completion")


        /** Read-only tools safe to execute in parallel (no side effects on project state).
         *  Note: Meta-tools (sonar, spring, build) included here because ALL their actions are read-only.
         *  Meta-tools with mixed read/write actions (jira, bamboo, bitbucket, git, runtime, debug) are
         *  NOT included — they execute sequentially for safety. Action-level parallelism could be added
         *  in a future optimization. */
        private val READ_ONLY_TOOLS = setOf(
            "read_file", "search_code", "glob_files", "file_structure",
            "find_definition", "find_references", "type_hierarchy", "call_hierarchy",
            "diagnostics", "find_implementations",
            // Read-only meta-tools (all actions are read-only)
            "sonar", "spring", "build"
        )

        /** Source-code mutation tools blocked during plan mode.
         *  When plan mode is active, these are removed from the LLM's tool schema
         *  so it cannot attempt file modifications — only read/analyze/plan. */
        val PLAN_MODE_BLOCKED_TOOLS = setOf(
            "edit_file", "create_file", "format_code",
            "optimize_imports", "refactor_rename", "rollback_changes"
        )

        /** Filter a tool map to remove plan-mode-blocked tools. */
        fun filterToolsForPlanMode(tools: Map<String, AgentTool>): Map<String, AgentTool> {
            return tools.filterKeys { it !in PLAN_MODE_BLOCKED_TOOLS }
        }

        /** Filter tool definitions to remove plan-mode-blocked tools. */
        fun filterToolDefsForPlanMode(toolDefs: List<ToolDefinition>): List<ToolDefinition> {
            return toolDefs.filter { it.function.name !in PLAN_MODE_BLOCKED_TOOLS }
        }

        /** Check if a tool is blocked by plan mode (plan mode must be active AND tool in blocked set). */
        fun isPlanModeBlocked(toolName: String): Boolean {
            return AgentService.planModeActive.get() && toolName in PLAN_MODE_BLOCKED_TOOLS
        }

        /** Meta-tools that contain mixed read/write actions. */
        private val META_TOOLS_WITH_WRITE_ACTIONS = setOf("jira", "bamboo_builds", "bamboo_plans", "bitbucket_pr", "bitbucket_review", "bitbucket_repo", "git")

        /** Meta-tool actions blocked during plan mode. The meta-tool itself stays
         *  available (so read actions work), but write actions are blocked. */
        val PLAN_MODE_BLOCKED_ACTIONS = setOf(
            // jira write actions
            "transition", "comment", "log_work", "start_work",
            // bamboo write actions
            "trigger_build", "stop_build", "cancel_build", "rerun_failed", "trigger_stage",
            // bitbucket write actions
            "create_pr", "approve_pr", "merge_pr", "decline_pr", "add_comment",
            "add_reviewer", "remove_reviewer", "update_pr",
            // git write actions
            "shelve"
        )

        /** Tools exempt from the ApprovalGate — plan mode transitions and internal orchestration
         *  should be frictionless, no user approval prompt needed. */
        private val APPROVAL_GATE_EXEMPT_TOOLS = setOf(
            "attempt_completion",
            "enable_plan_mode",
            "create_plan",
            "update_plan_step",
            "think"
        )

        /**
         * Check if a tool call is a background agent launch. These are safe to
         * parallelize because they just spawn a coroutine and return an ID immediately.
         */
        private fun isBackgroundAgentCall(tc: com.workflow.orchestrator.agent.api.dto.ToolCall): Boolean {
            if (tc.function.name != "agent") return false
            return try {
                val params = kotlinx.serialization.json.Json.parseToJsonElement(tc.function.arguments)
                (params as? kotlinx.serialization.json.JsonObject)
                    ?.get("run_in_background")
                    ?.jsonPrimitive?.booleanOrNull == true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Execute the single-agent ReAct loop.
     *
     * @param task The full task description from the user
     * @param tools Map of tool name to AgentTool (all registered tools)
     * @param toolDefinitions Tool definitions for the LLM
     * @param brain The LLM brain
     * @param bridge Event-sourced context bridge (the only context manager)
     * @param project The IntelliJ project
     * @param systemPrompt The assembled system prompt (from PromptAssembler)
     * @param maxOutputTokens Maximum output tokens for LLM responses
     * @param approvalGate Optional gate for risk-based approval of tool actions
     * @param eventLog Optional structured event log for audit trail
     * @param onProgress Callback for progress updates
     * @param onStreamChunk Callback for streaming LLM output tokens (for real-time UI)
     * @return [SingleAgentResult] — completed or failed
     */
    /**
     * Checkpoint data emitted after each iteration for session-level persistence.
     */
    data class IterationCheckpointData(
        val iteration: Int,
        val tokensUsed: Int,
        val lastToolCall: String?,
        val editedFiles: List<String>,
        val hasPlan: Boolean,
        val lastActivity: String?
    )

    suspend fun execute(
        task: String,
        tools: Map<String, AgentTool>,
        toolDefinitions: List<ToolDefinition>,
        brain: LlmBrain,
        bridge: EventSourcedContextBridge,
        project: Project,
        systemPrompt: String? = null,
        maxOutputTokens: Int? = null,
        approvalGate: ApprovalGate? = null,
        eventLog: AgentEventLog? = null,
        sessionTrace: SessionTrace? = null,
        sessionId: String = "",
        onProgress: (AgentProgress) -> Unit = {},
        onStreamChunk: (String) -> Unit = {},
        onDebugLog: ((String, String, String, Map<String, Any?>?) -> Unit)? = null,
        /** Called after each iteration to persist checkpoint + messages. */
        onCheckpoint: ((IterationCheckpointData) -> Unit)? = null,
        /** Optional steering channel for mid-execution user messages. */
        steeringChannel: SteeringChannel? = null
    ): SingleAgentResult {
        currentTask = task
        LOG.info("SingleAgentSession: starting with ${tools.size} tools for task: ${task.take(100)}")
        eventLog?.log(AgentEventType.SESSION_STARTED, "Task: ${task.take(200)}, tools: ${tools.size}")

        val effectiveBudget = bridge.remainingBudget() + bridge.currentTokens
        val budgetEnforcer = BudgetEnforcer(bridge, effectiveBudget)

        sessionTrace?.sessionStarted(task, tools.size, effectiveBudget - bridge.remainingBudget(), effectiveBudget)
        agentFileLogger?.logSessionStart(sessionId, task, tools.size)

        val sessionStartMs = System.currentTimeMillis()

        // Only add system prompt if explicitly provided (first message).
        // On multi-turn, systemPrompt is null — already in context from session.initialize().
        if (systemPrompt != null) {
            bridge.addSystemPrompt(systemPrompt)
        }
        bridge.addUserMessage(task)

        // Initialize LoopGuard for loop detection and auto-verification
        val loopGuard = LoopGuard()
        val backpressureGate = BackpressureGate(editThreshold = 3)
        val selfCorrectionGate = SelfCorrectionGate(maxRetriesPerFile = 3)

        // Initialize CompletionGatekeeper (option b: created here where gates are available)
        val planManager = try {
            com.workflow.orchestrator.agent.AgentService.getInstance(project).currentPlanManager
        } catch (_: Exception) { null }
        consecutiveNoToolResponses = 0
        completionGatekeeper = CompletionGatekeeper(
            planManager = planManager,
            selfCorrectionGate = selfCorrectionGate,
            loopGuard = loopGuard
        )

        // Add attempt_completion tool to the active tool set
        val attemptCompletionTool = com.workflow.orchestrator.agent.tools.builtin.AttemptCompletionTool(completionGatekeeper!!)
        var activeToolDefs = toolDefinitions.toMutableList().apply { add(attemptCompletionTool.toToolDefinition()) }.toList()
        var activeTools = tools.toMutableMap().apply { put(attemptCompletionTool.name, attemptCompletionTool) }.toMap()

        // Initialize FactsStore for compression-proof knowledge retention
        if (bridge.factsStore == null) {
            bridge.factsStore = FactsStore(maxFacts = 50)
        }

        var totalTokensUsed = 0
        val allArtifacts = mutableListOf<String>()
        val editedFiles = mutableListOf<String>()
        var compressionDone = false  // Re-arms if utilization drops below 80% and rises again


        var verificationPending = false
        forceTextOnly = false
        consecutiveMalformedRetries = 0

        var iteration = 0
        while (true) {
            iteration++
            // Mid-loop cancellation check
            if (cancelled.get()) {
                LOG.info("SingleAgentSession: cancelled at iteration $iteration")
                return SingleAgentResult.Completed(
                    content = "Task cancelled by user after $iteration iterations.",
                    summary = "Cancelled",
                    tokensUsed = totalTokensUsed,
                    artifacts = allArtifacts,
                    scorecard = buildScorecard(sessionId, "cancelled", selfCorrectionGate, System.currentTimeMillis() - sessionStartMs, project)
                )
            }

            // Drain messages from child workers (findings, status updates, file conflicts)
            try {
                val bus = AgentService.getInstance(project).workerMessageBus
                if (bus != null) {
                    val childMessages = bus.drain(WorkerMessageBus.ORCHESTRATOR_ID)
                    if (childMessages.isNotEmpty()) {
                        val formatted = childMessages.joinToString("\n") { msg ->
                            "<worker_message from=\"${msg.from}\" type=\"${msg.type.name.lowercase()}\">\n" +
                            "${msg.content}\n" +
                            "</worker_message>"
                        }
                        bridge.addSystemMessage(formatted)
                        LOG.info("SingleAgentSession: injected ${childMessages.size} worker messages at iteration $iteration")
                    }
                }
            } catch (_: Exception) { /* service not available in tests */ }

            // Drain steering messages from user (mid-execution redirections)
            if (steeringChannel != null) {
                val steeringMessages = steeringChannel.drain()
                if (steeringMessages.isNotEmpty()) {
                    for (msg in steeringMessages) {
                        bridge.addSteeringMessage(msg.content)
                        eventLog?.log(AgentEventType.STEERING_RECEIVED, "User steering: ${msg.content.take(200)}")
                    }
                    val drainedIds = steeringMessages.joinToString(",") { it.id }
                    LOG.info("SingleAgentSession: injected ${steeringMessages.size} steering message(s) at iteration $iteration")
                    onProgress(AgentProgress(
                        step = "Received steering from user:$drainedIds",
                        tokensUsed = bridge.currentTokens
                    ))
                }
            }

            LOG.info("SingleAgentSession: iteration $iteration")
            val iterationStartMs = System.currentTimeMillis()
            metrics.turnCount = iteration

            // Track current iteration for tools that need it (e.g. list_changes)
            try {
                com.workflow.orchestrator.agent.AgentService.getInstance(project).currentIteration = iteration
            } catch (_: Exception) { /* service not available in tests */ }
            sessionTrace?.iterationStarted(iteration, bridge.currentTokens, budgetEnforcer.utilizationPercent())
            onProgress(AgentProgress(
                step = "Thinking... (iteration $iteration)",
                tokensUsed = bridge.currentTokens
            ))

            // Check for pending tool activations from request_tools
            try {
                val agentService = com.workflow.orchestrator.agent.AgentService.getInstance(project)
                val pending = mutableSetOf<String>()
                while (agentService.pendingToolActivations.isNotEmpty()) {
                    agentService.pendingToolActivations.poll()?.let { pending.add(it) }
                }
                if (pending.isNotEmpty()) {
                    // Expand active tools with the requested ones
                    val allRegisteredTools = agentService.toolRegistry.allTools().associateBy { it.name }
                    for (name in pending) {
                        if (name !in activeTools) {
                            val tool = allRegisteredTools[name]
                            if (tool != null) {
                                activeTools = activeTools + (name to tool)
                                activeToolDefs = activeToolDefs + tool.toToolDefinition()
                            }
                        }
                    }
                    LOG.info("SingleAgentSession: expanded tool set with ${pending.size} tools from request_tools: $pending")
                }
            } catch (e: Exception) { LOG.warn("Failed to expand pending tool activations", e) }

            // Budget check before each LLM call.
            // Use the bridge's API-reconciled token utilization as the authoritative TERMINATE
            // signal (>0.97) so rotation triggers on accurate counts.
            val bridgeUtilization = bridge.tokenUtilization
            val budgetStatus = if (bridgeUtilization >= 0.97) {
                BudgetEnforcer.BudgetStatus.TERMINATE
            } else {
                budgetEnforcer.check()
            }
            when (budgetStatus) {
                BudgetEnforcer.BudgetStatus.TERMINATE -> {
                    LOG.warn("SingleAgentSession: budget exhausted at iteration $iteration (${budgetEnforcer.utilizationPercent()}%)")

                    // If the LLM was trying to complete, accept it despite gates
                    if (consecutiveNoToolResponses > 0) {
                        val lastContent = bridge.getMessages().lastOrNull { it.role == "assistant" }?.content ?: "Task completed (budget exhausted)"
                        LOG.warn("SingleAgentSession: budget TERMINATE — accepting last response as completion")
                        metrics.forcedCompletionCount++
                        agentFileLogger?.logSessionEnd(sessionId, iteration, totalTokensUsed, System.currentTimeMillis() - sessionStartMs)
                        loopGuard.guardrailStore?.save()
                        return SingleAgentResult.Completed(
                            content = lastContent,
                            summary = "Completed (budget exhausted, gates bypassed)",
                            tokensUsed = totalTokensUsed,
                            artifacts = allArtifacts,
                            scorecard = buildScorecard(sessionId, "completed_forced", selfCorrectionGate, System.currentTimeMillis() - sessionStartMs, project)
                        )
                    }

                    eventLog?.log(AgentEventType.SESSION_FAILED, "Budget terminated at ${budgetEnforcer.utilizationPercent()}%")
                    sessionTrace?.sessionFailed("Budget terminated at ${budgetEnforcer.utilizationPercent()}%", totalTokensUsed, iteration)

                    // Attempt context rotation if we have structured state to hand off
                    val planManager = try {
                        com.workflow.orchestrator.agent.AgentService.getInstance(project).currentPlanManager
                    } catch (_: Exception) { null }
                    val currentPlan = planManager?.currentPlan
                    val sessionDir = try {
                        com.workflow.orchestrator.agent.AgentService.getInstance(project).currentSessionDir
                    } catch (_: Exception) { null }

                    if (currentPlan != null && sessionDir != null) {
                        val accomplishments = currentPlan.steps
                            .filter { it.status == "done" }
                            .joinToString("; ") { it.title }
                        val remaining = currentPlan.steps
                            .filter { it.status != "done" }
                            .joinToString("; ") { it.title }
                        val guardrails = loopGuard.guardrailStore?.toContextString()?.lines()
                            ?.filter { it.startsWith("- ") }
                            ?.map { it.removePrefix("- ") }
                            ?: emptyList()
                        val facts = bridge.factsStore?.toContextString()?.lines()
                            ?.filter { it.startsWith("- ") }
                            ?.map { it.removePrefix("- ") }
                            ?: emptyList()

                        val rotationState = RotationState(
                            goal = currentPlan.goal,
                            accomplishments = accomplishments.ifBlank { "In progress" },
                            remainingWork = remaining.ifBlank { "Unknown — check plan" },
                            modifiedFiles = editedFiles.toList(),
                            guardrails = guardrails,
                            factsSnapshot = facts
                        )
                        RotationState.save(rotationState, sessionDir)

                        val summary = "Context full (${budgetEnforcer.utilizationPercent()}%). " +
                            "Accomplished: $accomplishments. Remaining: $remaining."

                        loopGuard.guardrailStore?.save()
                        agentFileLogger?.logSessionEnd(sessionId, iteration, totalTokensUsed, System.currentTimeMillis() - sessionStartMs, error = "Context rotated")

                        return SingleAgentResult.ContextRotated(
                            summary = summary,
                            rotationStatePath = java.io.File(sessionDir, "rotation-state.json").absolutePath,
                            tokensUsed = totalTokensUsed,
                            scorecard = buildScorecard(sessionId, "rotated", selfCorrectionGate, System.currentTimeMillis() - sessionStartMs, project)
                        )
                    }

                    // No plan — fall back to hard failure
                    val budgetTerminateError = "Context budget exhausted at ${budgetEnforcer.utilizationPercent()}%. Please start a new conversation for remaining work."
                    agentFileLogger?.logSessionEnd(sessionId, iteration, totalTokensUsed, System.currentTimeMillis() - sessionStartMs, error = budgetTerminateError)
                    loopGuard.guardrailStore?.save()
                    return SingleAgentResult.Failed(
                        error = budgetTerminateError,
                        tokensUsed = totalTokensUsed,
                        scorecard = buildScorecard(sessionId, "failed", selfCorrectionGate, System.currentTimeMillis() - sessionStartMs, project)
                    )
                }
                BudgetEnforcer.BudgetStatus.COMPRESS -> {
                    if (!compressionDone) {
                        compressionDone = true
                        LOG.info("SingleAgentSession: triggering sliding window compression at iteration $iteration (${budgetEnforcer.utilizationPercent()}%)")
                        eventLog?.log(AgentEventType.COMPRESSION_TRIGGERED, "At iteration $iteration, ${budgetEnforcer.utilizationPercent()}% used")
                        val tokensBefore = bridge.currentTokens
                        val messagesBefore = bridge.messageCount
                        bridge.requestCondensation()
                        metrics.compressionCount++
                        loopGuard.clearAllFileReads()
                        val tokensAfter = bridge.currentTokens
                        sessionTrace?.compressionTriggered("budget_enforcer", tokensBefore, tokensAfter, messagesBefore - bridge.messageCount)
                        agentFileLogger?.logCompression(sessionId, "budget_enforcer", tokensBefore, tokensAfter)
                        onDebugLog?.invoke("warn", "compression", "$tokensBefore → $tokensAfter tokens", null)
                    }
                }
                BudgetEnforcer.BudgetStatus.OK -> {
                    // Re-arm compression if utilization dropped below threshold after previous compression
                    compressionDone = false
                }
            }

            // Use the condenser pipeline + ConversationMemory path for LLM calls.
            val messages: List<ChatMessage>
            when (val outcome = bridge.getMessagesViaCondenser()) {
                is CondenserOutcome.NeedsCondensation -> {
                    // Condenser determined condensation must happen before the next LLM call.
                    // Record the condensation action in the event store and re-step.
                    bridge.eventStore.add(outcome.action, EventSource.SYSTEM)
                        continue
                    }
                    is CondenserOutcome.Messages -> {
                    messages = outcome.messages
                }
            }
            // Plan mode: remove source mutation tools from schema so LLM can't see them
            val planMode = AgentService.planModeActive.get()
            val effectiveToolDefs = if (planMode) filterToolDefsForPlanMode(activeToolDefs) else activeToolDefs
            val effectiveTools = if (planMode) filterToolsForPlanMode(activeTools) else activeTools
            val toolDefsForCall = if (forceTextOnly) null else if (effectiveTools.isNotEmpty()) effectiveToolDefs else null

            // LLM call with retry logic for rate limits and context length exceeded
            val result = callLlmWithRetry(
                brain, messages, toolDefsForCall, maxOutputTokens,
                onStreamChunk, effectiveToolDefs, effectiveTools, eventLog, onDebugLog, iteration
            )

            // Handle tool reduction if context exceeded
            if (result is LlmCallResult.ContextExceededRetry) {
                activeToolDefs = result.reducedToolDefs
                activeTools = result.reducedTools
                eventLog?.log(AgentEventType.CONTEXT_EXCEEDED_RETRY, "Reduced to ${activeTools.size} core tools")
                LOG.info("SingleAgentSession: context exceeded — requesting condensation")
                bridge.eventStore.add(CondensationRequestAction(), EventSource.SYSTEM)
                val tokensBefore = bridge.currentTokens
                val messagesBefore = bridge.messageCount
                bridge.requestCondensation()
                metrics.compressionCount++
                loopGuard.clearAllFileReads()
                compressionDone = true
                val tokensAfter = bridge.currentTokens
                sessionTrace?.compressionTriggered("context_overflow", tokensBefore, tokensAfter, messagesBefore - bridge.messageCount)
                agentFileLogger?.logCompression(sessionId, "context_overflow", tokensBefore, tokensAfter)
                onDebugLog?.invoke("warn", "compression", "$tokensBefore → $tokensAfter tokens (context overflow)", null)
                // Re-fetch messages after compression
                val compressedMessages = when (val condenserOutcome = bridge.getMessagesViaCondenser()) {
                    is CondenserOutcome.Messages -> condenserOutcome.messages
                    is CondenserOutcome.NeedsCondensation -> {
                        bridge.eventStore.add(condenserOutcome.action, EventSource.SYSTEM)
                        bridge.getMessages() // fallback if still needs condensation
                    }
                }
                // Retry with reduced tools and compressed context
                val retryResult = callLlmWithRetry(
                    brain, compressedMessages, activeToolDefs, maxOutputTokens,
                    onStreamChunk, activeToolDefs, activeTools, eventLog, onDebugLog, iteration
                )
                if (retryResult !is LlmCallResult.Success) {
                    val errorMsg = when (retryResult) {
                        is LlmCallResult.Failed -> retryResult.error
                        else -> "Context length exceeded even after tool reduction"
                    }
                    eventLog?.log(AgentEventType.SESSION_FAILED, errorMsg)
                    agentFileLogger?.logSessionEnd(sessionId, iteration, totalTokensUsed, System.currentTimeMillis() - sessionStartMs, error = errorMsg)
                    return SingleAgentResult.Failed(error = errorMsg, tokensUsed = totalTokensUsed,
                        scorecard = buildScorecard(sessionId, "failed", selfCorrectionGate, System.currentTimeMillis() - sessionStartMs, project))
                }
                // Process the successful retry below
                return processLlmSuccess(
                    retryResult as LlmCallResult.Success, iteration, totalTokensUsed, allArtifacts,
                    editedFiles, activeTools, bridge, project, approvalGate, loopGuard,
                    backpressureGate, selfCorrectionGate, budgetEnforcer, brain, activeToolDefs, maxOutputTokens, onProgress,
                    onStreamChunk, eventLog, sessionTrace,
                    sessionId, sessionStartMs, iterationStartMs, onDebugLog, onCheckpoint
                ) ?: continue
            }

            when (result) {
                is LlmCallResult.Success -> {
                    val sessionResult = processLlmSuccess(
                        result, iteration, totalTokensUsed, allArtifacts,
                        editedFiles, activeTools, bridge, project, approvalGate, loopGuard,
                        backpressureGate, selfCorrectionGate, budgetEnforcer, brain, activeToolDefs, maxOutputTokens, onProgress,
                        onStreamChunk, eventLog, sessionTrace,
                        sessionId, sessionStartMs, iterationStartMs, onDebugLog, onCheckpoint
                    )
                    if (sessionResult != null) return sessionResult
                    totalTokensUsed = result.totalTokensSoFar
                }
                is LlmCallResult.Failed -> {
                    // Cancelled by user — exit cleanly as Completed (not Failed)
                    if (cancelled.get()) {
                        LOG.info("SingleAgentSession: cancelled during LLM call at iteration $iteration")
                        return SingleAgentResult.Completed(
                            content = "Task cancelled by user after $iteration iterations.",
                            summary = "Cancelled",
                            tokensUsed = totalTokensUsed,
                            artifacts = allArtifacts,
                            scorecard = buildScorecard(sessionId, "cancelled", selfCorrectionGate, System.currentTimeMillis() - sessionStartMs, project)
                        )
                    }
                    eventLog?.log(AgentEventType.SESSION_FAILED, result.error)
                    sessionTrace?.dumpConversationState(bridge.getMessages(), "llm_call_failed: ${result.error}")
                    sessionTrace?.sessionFailed(result.error, totalTokensUsed, iteration)
                    val llmFailedError = "LLM call failed: ${result.error}"
                    agentFileLogger?.logSessionEnd(sessionId, iteration, totalTokensUsed, System.currentTimeMillis() - sessionStartMs, error = llmFailedError)
                    onDebugLog?.invoke("error", "error", llmFailedError, null)
                    return SingleAgentResult.Failed(
                        error = llmFailedError,
                        tokensUsed = totalTokensUsed,
                        scorecard = buildScorecard(sessionId, "failed", selfCorrectionGate, System.currentTimeMillis() - sessionStartMs, project)
                    )
                }
                is LlmCallResult.ContextExceededRetry -> {
                    // Already handled above
                }
            }
        }
    }

    /**
     * Process a successful LLM response — handle tool calls, LoopGuard, and final response.
     * Returns a SingleAgentResult if the session should end, or null to continue the loop.
     */
    private suspend fun processLlmSuccess(
        result: LlmCallResult.Success,
        iteration: Int,
        totalTokensUsedBefore: Int,
        allArtifacts: MutableList<String>,
        editedFiles: MutableList<String>,
        tools: Map<String, AgentTool>,
        bridge: EventSourcedContextBridge,
        project: Project,
        approvalGate: ApprovalGate?,
        loopGuard: LoopGuard,
        backpressureGate: BackpressureGate,
        selfCorrectionGate: SelfCorrectionGate,
        budgetEnforcer: BudgetEnforcer,
        brain: LlmBrain,
        toolDefinitions: List<ToolDefinition>,
        maxOutputTokens: Int?,
        onProgress: (AgentProgress) -> Unit,
        onStreamChunk: (String) -> Unit,
        eventLog: AgentEventLog?,
        sessionTrace: SessionTrace?,
        sessionId: String = "",
        sessionStartMs: Long = 0L,
        iterationStartMs: Long = 0L,
        onDebugLog: ((String, String, String, Map<String, Any?>?) -> Unit)? = null,
        onCheckpoint: ((IterationCheckpointData) -> Unit)? = null
    ): SingleAgentResult? {
        val response = result.response
        val usage = response.usage
        var totalTokensUsed = totalTokensUsedBefore
        if (usage != null && usage.promptTokens > 0) {
            totalTokensUsed += usage.totalTokens
            sessionInputTokens += usage.promptTokens.toLong()
            sessionOutputTokens += usage.completionTokens.toLong()
            // Reconcile token count with actual API-reported count.
            // The API's prompt_tokens is authoritative — calibrate to it.
            bridge.updateTokensFromUsage(usage.promptTokens)
        } else {
            // Streaming response returned null/zero usage — estimate heuristically
            // so budget tracking doesn't drift. Not authoritative, but better than nothing.
            val estimatedPrompt = TokenEstimator.estimate(bridge.getMessages())
            val estimatedCompletion = TokenEstimator.estimate(response.choices.firstOrNull()?.message?.content ?: "")
            totalTokensUsed += estimatedPrompt + estimatedCompletion
            sessionInputTokens += estimatedPrompt.toLong()
            sessionOutputTokens += estimatedCompletion.toLong()
        }
        // Store for caller
        (result as LlmCallResult.Success).totalTokensSoFar = totalTokensUsed

        val choice = response.choices.firstOrNull() ?: return SingleAgentResult.Failed(
            error = "Empty response from LLM", tokensUsed = totalTokensUsed,
            scorecard = buildScorecard(sessionId, "failed", selfCorrectionGate, System.currentTimeMillis() - sessionStartMs, project)
        )
        val message = choice.message

        // Filter out tool calls with empty/blank names (Sourcegraph streaming can produce these)
        val validToolCalls = message.toolCalls?.filter { it.function.name.isNotBlank() }?.ifEmpty { null }
        val cleanMessage = if (validToolCalls != message.toolCalls) {
            message.copy(toolCalls = validToolCalls)
        } else message
        val toolCalls = cleanMessage.toolCalls

        // Only add assistant message to context if it has content or valid tool calls
        if (!cleanMessage.content.isNullOrBlank() || !toolCalls.isNullOrEmpty()) {
            if (!toolCalls.isNullOrEmpty()) {
                bridge.addAssistantToolCalls(cleanMessage)
            } else {
                bridge.addAssistantMessage(cleanMessage)
            }
        } else {
            LOG.info("SingleAgentSession: skipping empty assistant message (no content, no valid tool calls)")
        }

        // Show assistant's text content in UI. If the response was streamed,
        // tokens were already pushed via onStreamChunk during the LLM call.
        // Only push here for non-streamed responses (fallback path).
        if (!cleanMessage.content.isNullOrBlank() && !result.wasStreamed) {
            onStreamChunk(cleanMessage.content!!)
        }

        // Flush the stream buffer after each LLM response so that text-only
        // responses (no tool calls) become discrete chat messages instead of
        // concatenating with the next iteration's text output.
        if (toolCalls.isNullOrEmpty() && !cleanMessage.content.isNullOrBlank()) {
            onProgress(AgentProgress(step = "__flush_stream__", tokensUsed = bridge.currentTokens))
        }

        // Handle truncated responses (output token limit hit).
        // finishReason="length" means the model hit max_tokens before completing.
        // Two cases: (1) truncated text response, (2) truncated tool call (invalid JSON).
        if (choice.finishReason == "length") {
            if (toolCalls.isNullOrEmpty()) {
                // Case 1: Text response truncated — ask to continue or summarize
                LOG.info("SingleAgentSession: text response truncated (finishReason=length), requesting continuation")
                val truncatedTextContent = "Your response was truncated due to the output token limit. " +
                    "If you were about to use a tool, please do so now. " +
                    "If you were providing a final answer, please provide a concise summary instead."
                bridge.addUserMessage(truncatedTextContent)
                return null // continue loop
            } else {
                // Case 2: Tool call likely truncated (JSON may be invalid).
                // Don't try to parse — the arguments are probably incomplete.
                // Ask the LLM to retry with a smaller operation.
                LOG.warn("SingleAgentSession: tool call truncated (finishReason=length with ${toolCalls.size} tool calls)")
                eventLog?.log(AgentEventType.TOOL_FAILED, "Tool call truncated at output limit")

                // Check if the tool call JSON is actually valid before giving up
                val firstCall = toolCalls.firstOrNull()
                val jsonValid = if (firstCall != null) {
                    try { json.decodeFromString<kotlinx.serialization.json.JsonObject>(firstCall.function.arguments); true }
                    catch (_: Exception) { false }
                } else false

                if (jsonValid) {
                    // JSON is valid despite length finish — proceed normally (model finished the tool call just in time)
                    LOG.info("SingleAgentSession: tool call JSON is valid despite finishReason=length, proceeding")
                } else {
                    // JSON is truncated — ask LLM to retry with smaller scope
                    LOG.warn("SingleAgentSession: tool call JSON is invalid/truncated, requesting smaller operation")
                    val truncatedToolName = firstCall?.function?.name ?: "unknown"
                    val truncatedToolContent = "Your previous tool call to '$truncatedToolName' was truncated because " +
                        "it exceeded the output token limit. The arguments were incomplete and could not be parsed. " +
                        "Please retry with a SMALLER operation — for example, edit one function at a time " +
                        "instead of an entire file, or break the task into multiple tool calls."
                    bridge.addUserMessage(truncatedToolContent)
                    return null // continue loop with retry guidance
                }
            }
        }

        if (toolCalls.isNullOrEmpty()) {
            // Check if the LLM intended to make tool calls but they were malformed/filtered out.
            // finishReason="tool_calls" with empty toolCalls means the LLM tried to call tools
            // but the arguments were invalid (e.g., concatenated JSON objects).
            if (choice.finishReason == "tool_calls") {
                consecutiveMalformedRetries++
                val rawArgs = message.toolCalls?.firstOrNull()?.function?.arguments?.take(200) ?: "(null)"
                val rawName = message.toolCalls?.firstOrNull()?.function?.name ?: "(null)"
                val rawCount = message.toolCalls?.size ?: 0
                LOG.warn("SingleAgentSession: finishReason=tool_calls but no valid tool calls (attempt $consecutiveMalformedRetries/$MAX_MALFORMED_RETRIES) — name=$rawName, args=$rawArgs, count=$rawCount")
                agentFileLogger?.logRetry(sessionId, "finishReason=tool_calls but no valid tool calls (attempt $consecutiveMalformedRetries)", iteration)
                agentFileLogger?.logMalformedToolCall(sessionId, rawName, rawArgs, "finishReason=tool_calls but all tool calls filtered as malformed")
                onDebugLog?.invoke("error", "malformed_tc", "0 valid tool calls (retry $consecutiveMalformedRetries/$MAX_MALFORMED_RETRIES) — name=$rawName args=${rawArgs.take(100)}", mapOf("iteration" to iteration, "rawCount" to rawCount))

                if (consecutiveMalformedRetries >= MAX_MALFORMED_RETRIES) {
                    LOG.warn("SingleAgentSession: $MAX_MALFORMED_RETRIES consecutive malformed tool calls — forcing text-only response")
                    onDebugLog?.invoke("error", "force_text", "Forcing text-only after $MAX_MALFORMED_RETRIES failed tool call attempts", null)
                    onProgress(AgentProgress(step = "Tool calls failed $MAX_MALFORMED_RETRIES times — switching to text response", tokensUsed = bridge.currentTokens))
                    val forceTextContent = "IMPORTANT: Your last $MAX_MALFORMED_RETRIES attempts to call tools all failed because the tool call " +
                        "arguments were empty or malformed. The streaming API is not delivering your tool call arguments correctly. " +
                        "DO NOT attempt any more tool calls. Instead, respond with a TEXT message explaining what you were " +
                        "trying to do, and I will help you accomplish it another way."
                    bridge.addUserMessage(forceTextContent)
                    forceTextOnly = true
                } else {
                    onProgress(AgentProgress(step = "Tool call failed (malformed args, retry $consecutiveMalformedRetries/$MAX_MALFORMED_RETRIES)...", tokensUsed = bridge.currentTokens))
                    val malformedRetryContent = "Your previous response indicated tool calls (finish_reason=tool_calls) but the tool call " +
                        "arguments were empty or malformed and could not be parsed. Please retry — call ONE tool at a time with valid JSON arguments."
                    bridge.addUserMessage(malformedRetryContent)
                }
                return null // continue loop with retry guidance
            }

            // No tool calls — about to return final response
            val content = message.content ?: ""

            // No-tool-call nudge / implicit completion tracking
            consecutiveNoToolResponses++

            if (!forceTextOnly) {
                // Normal mode: always require explicit attempt_completion.
                // Implicit completion is NOT allowed — keep nudging with escalating urgency.
                metrics.nudgeCount++
                val nudgeMessage = if (consecutiveNoToolResponses == 1) {
                    "You responded without calling any tools. If you've completed the task, " +
                        "call attempt_completion with a summary. If you have more work to do, " +
                        "make your next tool call now."
                } else if (consecutiveNoToolResponses <= MAX_NO_TOOL_NUDGES) {
                    "You MUST call attempt_completion to finish the task. Do NOT respond with only text. " +
                        "Call attempt_completion now with your result summary, or continue working with tool calls. " +
                        "(Reminder $consecutiveNoToolResponses/$MAX_NO_TOOL_NUDGES)"
                } else {
                    // After MAX_NO_TOOL_NUDGES, switch to forceTextOnly to allow implicit completion
                    LOG.warn("SingleAgentSession: $MAX_NO_TOOL_NUDGES consecutive no-tool responses — switching to forceTextOnly")
                    onDebugLog?.invoke("warn", "force_text", "$MAX_NO_TOOL_NUDGES no-tool responses — allowing implicit completion", null)
                    forceTextOnly = true
                    // Fall through to the forceTextOnly gatekeeper path below
                    null
                }
                if (nudgeMessage != null) {
                    bridge.addUserMessage(nudgeMessage)
                    onDebugLog?.invoke("warn", "nudge", "No tool calls (×$consecutiveNoToolResponses) — nudging to use attempt_completion", null)
                    return null // continue loop
                }
            }

            // forceTextOnly mode: tools are disabled (malformed retries exhausted
            // or MAX_NO_TOOL_NUDGES exceeded). Allow implicit completion via gatekeeper.
            val gateBlock = completionGatekeeper?.checkCompletion()
            if (gateBlock != null) {
                val blockedGate = completionGatekeeper?.lastBlockedGate ?: "unknown"
                metrics.completionGateBlocks[blockedGate] = (metrics.completionGateBlocks[blockedGate] ?: 0) + 1
                bridge.addUserMessage(gateBlock)
                return null // continue loop
            }
            // Check if the gatekeeper force-accepted (max attempts exceeded)
            if (completionGatekeeper?.wasForceAccepted == true) {
                metrics.forcedCompletionCount++
            }

            // All gates passed (or no gatekeeper) — accept implicit completion (forceTextOnly mode only)
            // Validate output for sensitive data and redact if needed
            val securityIssues = OutputValidator.validate(content)
            val sanitizedContent = if (securityIssues.isNotEmpty()) {
                LOG.warn("SingleAgentSession: output validation flagged: ${securityIssues.joinToString()}")
                hallucinationFlags += securityIssues.size
                CredentialRedactor.redact(content)
            } else content

            val summary = if (sanitizedContent.length > 200) sanitizedContent.take(200) + "..." else sanitizedContent
            val scStates = selfCorrectionGate.getFileStates()
            val verifiedCount = scStates.count { it.value.verified }
            val exhaustedCount = selfCorrectionGate.getExhaustedFiles().size
            LOG.info("SingleAgentSession: completed after $iteration iterations, $totalTokensUsed tokens, self-correction: $verifiedCount verified, $exhaustedCount exhausted")

            onProgress(AgentProgress(
                step = "Task completed",
                tokensUsed = bridge.currentTokens
            ))

            eventLog?.log(AgentEventType.SESSION_COMPLETED, "Completed after $iteration iterations, $totalTokensUsed tokens")
            sessionTrace?.iterationCompleted(iteration, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, emptyList(), choice.finishReason)
            sessionTrace?.sessionMetrics(metrics.toJson())
            sessionTrace?.sessionCompleted(totalTokensUsed, iteration, allArtifacts)
            agentFileLogger?.logIteration(sessionId, iteration, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, choice.finishReason, emptyList(), System.currentTimeMillis() - iterationStartMs)
            agentFileLogger?.logSessionEnd(sessionId, iteration, totalTokensUsed, System.currentTimeMillis() - sessionStartMs)

            // Persist any learned guardrails
            loopGuard.guardrailStore?.save()

            return SingleAgentResult.Completed(
                content = sanitizedContent,
                summary = summary,
                tokensUsed = totalTokensUsed,
                artifacts = allArtifacts,
                scorecard = buildScorecard(sessionId, "completed", selfCorrectionGate, System.currentTimeMillis() - sessionStartMs, project)
            )
        }

        // Reset malformed retry counter and no-tool counter — we have valid tool calls
        consecutiveMalformedRetries = 0
        consecutiveNoToolResponses = 0

        // Handle mixed tool calls: attempt_completion + other tools
        val hasAttemptCompletion = toolCalls.any { it.function.name == "attempt_completion" }
        val hasOtherTools = toolCalls.any { it.function.name != "attempt_completion" }
        val effectiveToolCalls = if (hasAttemptCompletion && hasOtherTools) {
            // Discard attempt_completion, execute only other tools
            val mixedCompletionContent = "You called attempt_completion alongside other tools. Complete your tool calls first, " +
                "then call attempt_completion separately when you are truly done."
            bridge.addUserMessage(mixedCompletionContent)
            onDebugLog?.invoke("warn", "mixed_completion", "attempt_completion discarded — mixed with ${toolCalls.size - 1} other tools", null)
            toolCalls.filter { it.function.name != "attempt_completion" }
        } else {
            toolCalls
        }

        // Execute tool calls: read-only tools in parallel, write tools sequentially
        val toolResults = mutableListOf<Pair<String, Boolean>>() // (toolCallId, isError) for LoopGuard

        // Split into parallel-safe and sequential tool calls.
        // Read-only tools are always parallel-safe. Background agent launches are also
        // parallel-safe (they just spawn a coroutine and return an ID immediately).
        val (readOnlyCalls, writeCalls) = effectiveToolCalls.partition { tc ->
            tc.function.name in READ_ONLY_TOOLS || isBackgroundAgentCall(tc)
        }

        // Doom loop detection: check each tool call before execution, skip if detected
        val doomSkipped = mutableSetOf<String>() // toolCallIds skipped due to doom loop
        for (tc in effectiveToolCalls) {
            val doomMessage = loopGuard.checkDoomLoop(tc.function.name, tc.function.arguments)
            if (doomMessage != null) {
                doomLoopTriggers++
                // SKIP execution — don't waste time on a doom loop call
                bridge.addToolError(tc.id, doomMessage, "Doom loop detected — execution skipped", tc.function.name)
                doomSkipped.add(tc.id)
                toolResults.add(tc.id to true)
                onProgress(AgentProgress(
                    step = "Skipped tool: ${tc.function.name} (doom loop detected)",
                    tokensUsed = bridge.currentTokens
                ))
            }
        }

        // Filter out doom-loop-skipped calls from both parallel and sequential batches
        val activeReadOnlyCalls = readOnlyCalls.filter { it.id !in doomSkipped }
        val activeWriteCalls = writeCalls.filter { it.id !in doomSkipped }

        // Execute read-only tools in parallel, collect results first
        if (activeReadOnlyCalls.isNotEmpty()) {
            val parallelResults: List<Triple<ToolCall, ToolResult, Long>> = coroutineScope {
                activeReadOnlyCalls.map { tc ->
                    async {
                        if (cancelled.get()) {
                            Triple(tc, ToolResult("Cancelled by user", "Cancelled", 0, isError = true), 0L)
                        } else {
                            executeSingleToolRaw(tc, tools, project, approvalGate, eventLog, sessionTrace, onProgress)
                        }
                    }
                }.awaitAll()
            }
            // Add results to context sequentially (EventSourcedContextBridge is not thread-safe)
            for (entry in parallelResults) {
                val tc = entry.first
                val tr = entry.second
                val durMs = entry.third
                val toolName = tc.function.name
                // 5C: Inject tool results into LLM context (no redaction — LLM needs raw content)
                if (tr.isError) {
                    bridge.addToolError(tc.id, tr.content, tr.summary, toolName)
                } else {
                    bridge.addToolResult(tc.id, tr.content, tr.summary, toolName)
                }
                recordFactFromToolResult(toolName, tc.function.arguments, tr.content, tr.summary, iteration, bridge, project)
                allArtifacts.addAll(tr.artifacts)
                toolResults.add(tc.id to tr.isError)

                if (tr.isError) {
                    sessionTrace?.toolExecuted(toolName, durMs, tr.tokenEstimate, true, tr.summary)
                } else {
                    sessionTrace?.toolExecuted(toolName, durMs, tr.tokenEstimate, false)
                }

                // Record metrics and check circuit breaker
                metrics.recordToolCall(toolName, durMs, !tr.isError, tr.tokenEstimate.toLong())
                // Acknowledge verification tools in backpressure gate and self-correction gate
                if (toolName in BackpressureGate.VERIFICATION_TOOLS) {
                    if (!tr.isError) {
                        backpressureGate.acknowledgeVerification()
                    }
                    // Self-correction: record verification result per file
                    val verifiedFile = selfCorrectionGate.extractFilePathFromArgs(toolName, tc.function.arguments)
                    selfCorrectionGate.recordVerification(verifiedFile, passed = !tr.isError, errorDetails = if (tr.isError) tr.content.take(1500) else null)
                    // If verification failed on a tracked file, inject reflection prompt
                    if (tr.isError && verifiedFile != null && selfCorrectionGate.isTracked(verifiedFile)) {
                        val reflection = selfCorrectionGate.buildReflectionPrompt(verifiedFile, toolName, tr.content)
                        if (reflection != null) {
                            bridge.addMessage(reflection)
                        }
                    }
                }
                if (metrics.isCircuitBroken(toolName)) {
                    val circuitBreakerContent = "Circuit breaker: '$toolName' has failed ${AgentMetrics.CIRCUIT_BREAKER_THRESHOLD} consecutive times. Try a different approach or tool."
                    bridge.addSystemMessage(circuitBreakerContent)
                    // Auto-record to guardrails
                    loopGuard.guardrailStore?.record(
                        "Tool '$toolName' frequently fails in this project — consider alternative approaches"
                    )
                }

                agentFileLogger?.logToolCall(
                    sessionId = sessionId,
                    toolName = toolName,
                    args = tc.function.arguments.take(500),
                    status = if (tr.isError) "error" else "success",
                    result = tr.summary.take(300),
                    errorMessage = if (tr.isError) tr.content.take(500) else null,
                    durationMs = durMs,
                    tokenEstimate = tr.tokenEstimate
                )

                onProgress(AgentProgress(
                    step = "Used tool: $toolName",
                    tokensUsed = bridge.currentTokens,
                    toolCallInfo = ToolCallInfo(
                        toolName = toolName,
                        args = tc.function.arguments.take(1000),
                        result = tr.summary,
                        durationMs = durMs,
                        isError = tr.isError,
                        output = tr.content.take(5000)
                    )
                ))
                onDebugLog?.invoke(
                    if (tr.isError) "warn" else "info", "tool_call",
                    "$toolName ${if (tr.isError) "ERROR" else "OK"} (${durMs}ms)",
                    mapOf("tool" to toolName, "duration" to durMs, "tokens" to tr.tokenEstimate)
                )
            }
        }

        // Execute write tools sequentially
        for (toolCall in activeWriteCalls) {
            if (cancelled.get()) {
                bridge.addToolError(toolCall.id, "Cancelled by user", "Cancelled", toolCall.function.name)
                break
            }
            val toolName = toolCall.function.name

            // Pre-edit search enforcement: block edit_file if file not read in this session
            if (toolName == "edit_file") {
                val editPathMatch = Regex(""""path"\s*:\s*"([^"]+)"""").find(toolCall.function.arguments)
                val editPath = editPathMatch?.groupValues?.get(1)
                if (editPath != null) {
                    val preEditWarning = loopGuard.checkPreEditRead(editPath)
                    if (preEditWarning != null) {
                        bridge.addToolError(toolCall.id, preEditWarning, "Edit blocked: file not read", toolCall.function.name)
                        toolResults.add(toolCall.id to true)
                        onProgress(AgentProgress(
                            step = "Edit blocked: $editPath not read yet",
                            tokensUsed = bridge.currentTokens
                        ))
                        continue  // Skip to next tool call
                    }
                }
            }

            val (_, toolResult, toolDurationMs) = executeSingleToolRaw(toolCall, tools, project, approvalGate, eventLog, sessionTrace, onProgress)

            // Track attempt_completion invocations and gate blocks
            if (toolName == "attempt_completion") {
                metrics.completionAttemptCount++
                if (toolResult.isError && !toolResult.isCompletion) {
                    // Blocked by a gate — record which gate blocked it
                    val blockedGate = completionGatekeeper?.lastBlockedGate ?: "unknown"
                    metrics.completionGateBlocks[blockedGate] = (metrics.completionGateBlocks[blockedGate] ?: 0) + 1
                }
                if (toolResult.isCompletion && completionGatekeeper?.wasForceAccepted == true) {
                    metrics.forcedCompletionCount++
                }
            }

            // Handle attempt_completion success — exit loop with completion event (not a tool call)
            if (toolResult.isCompletion) {
                val sanitizedContent = if (OutputValidator.validate(toolResult.content).isNotEmpty()) {
                    hallucinationFlags++
                    CredentialRedactor.redact(toolResult.content)
                } else toolResult.content

                // Persist the completion summary in conversation context so follow-up messages retain it
                bridge.addToolResult(toolCall.id, sanitizedContent, toolResult.summary, toolCall.function.name)

                // Emit completion summary as streamed text, not a tool call card
                onProgress(AgentProgress(
                    step = "__completion__",
                    tokensUsed = bridge.currentTokens,
                    toolCallInfo = ToolCallInfo(
                        toolName = "attempt_completion",
                        result = sanitizedContent,
                        durationMs = 0,
                        isError = false,
                        output = toolResult.verifyCommand
                    )
                ))

                LOG.info("SingleAgentSession: attempt_completion accepted after $iteration iterations")
                eventLog?.log(AgentEventType.SESSION_COMPLETED, "attempt_completion accepted after $iteration iterations, $totalTokensUsed tokens")
                sessionTrace?.iterationCompleted(iteration, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, listOf("attempt_completion"), choice.finishReason)
                sessionTrace?.sessionMetrics(metrics.toJson())
                sessionTrace?.sessionCompleted(totalTokensUsed, iteration, allArtifacts)
                agentFileLogger?.logIteration(sessionId, iteration, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, choice.finishReason, listOf("attempt_completion"), System.currentTimeMillis() - iterationStartMs)
                agentFileLogger?.logSessionEnd(sessionId, iteration, totalTokensUsed, System.currentTimeMillis() - sessionStartMs)
                loopGuard.guardrailStore?.save()

                return SingleAgentResult.Completed(
                    content = sanitizedContent,
                    summary = toolResult.summary,
                    tokensUsed = totalTokensUsed,
                    artifacts = allArtifacts,
                    scorecard = buildScorecard(sessionId, "completed", selfCorrectionGate, System.currentTimeMillis() - sessionStartMs, project)
                )
            }

            // 5C: Inject tool results into LLM context (no redaction — LLM needs raw content
            // to work with real files, certs, config values. Redaction only on disk logs.)
            if (toolResult.isError) {
                bridge.addToolError(toolCall.id, toolResult.content, toolResult.summary, toolName)
            } else {
                bridge.addToolResult(toolCall.id, toolResult.content, toolResult.summary, toolName)
            }
            recordFactFromToolResult(toolName, toolCall.function.arguments, toolResult.content, toolResult.summary, iteration, bridge, project)

            allArtifacts.addAll(toolResult.artifacts)
            toolResults.add(toolCall.id to toolResult.isError)

            // Per-edit checkpoints: create a LocalHistory checkpoint and record in ChangeLedger
            if (toolResult.artifacts.isNotEmpty()) {
                val agentService = try { com.workflow.orchestrator.agent.AgentService.getInstance(project) } catch (_: Exception) { null }
                val ledger = agentService?.currentChangeLedger
                val rollback = agentService?.currentRollbackManager

                val checkpointId = rollback?.createCheckpoint(
                    "Iteration $iteration: $toolName"
                ) ?: ""

                // Record checkpoint in ledger
                val iterChanges = ledger?.changesForIteration(iteration) ?: emptyList()
                ledger?.recordCheckpoint(CheckpointMeta(
                    id = checkpointId,
                    description = "Iteration $iteration: $toolName",
                    iteration = iteration,
                    timestamp = System.currentTimeMillis(),
                    filesModified = toolResult.artifacts.map { it.substringAfterLast('/') },
                    totalLinesAdded = iterChanges.sumOf { it.linesAdded },
                    totalLinesRemoved = iterChanges.sumOf { it.linesRemoved }
                ))

                // COMPRESSION: Update anchor so LLM sees cumulative changes
                if (ledger != null) {
                    agentService?.currentContextBridge?.updateChangeLedgerAnchor(ledger)
                }

                toolResult.artifacts.forEach { rollback?.trackFileChange(it) }
            }

            // Track edited files for LoopGuard auto-verification
            if (toolName == "edit_file" && !toolResult.isError) {
                editedFiles.addAll(toolResult.artifacts)
                // Clear file read tracking so agent can re-read after edit
                val editPathMatch = Regex(""""path"\s*:\s*"([^"]+)"""").find(toolCall.function.arguments)
                editPathMatch?.groupValues?.get(1)?.let { loopGuard.clearFileRead(it) }
                if (toolResult.content.contains("Edit rejected: syntax errors")) {
                    eventLog?.log(AgentEventType.EDIT_REJECTED_SYNTAX, toolResult.artifacts.firstOrNull() ?: "unknown")
                } else {
                    eventLog?.log(AgentEventType.EDIT_APPLIED, toolResult.artifacts.firstOrNull() ?: "unknown")
                }
                // Record in backpressure gate and self-correction gate
                toolResult.artifacts.firstOrNull()?.let {
                    backpressureGate.recordEdit(it)
                    selfCorrectionGate.recordEdit(it)
                }
            }

            if (toolResult.isError) {
                sessionTrace?.toolExecuted(toolName, toolDurationMs, toolResult.tokenEstimate, true, toolResult.summary)
            } else {
                sessionTrace?.toolExecuted(toolName, toolDurationMs, toolResult.tokenEstimate, false)
            }

            // Record metrics and check circuit breaker
            metrics.recordToolCall(toolName, toolDurationMs, !toolResult.isError, toolResult.tokenEstimate.toLong())
            // Acknowledge verification tools in backpressure gate and self-correction gate
            if (toolName in BackpressureGate.VERIFICATION_TOOLS) {
                if (!toolResult.isError) {
                    backpressureGate.acknowledgeVerification()
                }
                // Self-correction: record verification result per file
                val verifiedFile = selfCorrectionGate.extractFilePathFromArgs(toolName, toolCall.function.arguments)
                selfCorrectionGate.recordVerification(verifiedFile, passed = !toolResult.isError, errorDetails = if (toolResult.isError) toolResult.content.take(1500) else null)
                // If verification failed on a tracked file, inject reflection prompt
                if (toolResult.isError && verifiedFile != null && selfCorrectionGate.isTracked(verifiedFile)) {
                    val reflection = selfCorrectionGate.buildReflectionPrompt(verifiedFile, toolName, toolResult.content)
                    if (reflection != null) {
                        bridge.addMessage(reflection)
                    }
                }
            }
            // Backpressure error on test/build failures
            if (toolResult.isError && toolName in setOf("run_command", "runtime_config", "runtime_exec")) {
                val bpError = backpressureGate.createBackpressureError(toolName, toolResult.content)
                bridge.addMessage(bpError)
            }
            if (toolName == "agent" && !toolResult.isError) {
                metrics.subagentCount++
            }
            if (metrics.isCircuitBroken(toolName)) {
                val circuitBreakerMsg = "Circuit breaker: '$toolName' has failed ${AgentMetrics.CIRCUIT_BREAKER_THRESHOLD} consecutive times. Try a different approach or tool."
                bridge.addSystemMessage(circuitBreakerMsg)
                // Auto-record to guardrails
                loopGuard.guardrailStore?.record(
                    "Tool '$toolName' frequently fails in this project — consider alternative approaches"
                )
            }

            agentFileLogger?.logToolCall(
                sessionId = sessionId,
                toolName = toolName,
                args = toolCall.function.arguments.take(500),
                status = if (toolResult.isError) "error" else "success",
                result = toolResult.summary.take(300),
                errorMessage = if (toolResult.isError) toolResult.content.take(500) else null,
                durationMs = toolDurationMs,
                tokenEstimate = toolResult.tokenEstimate
            )

            // Build rich tool call info for the UI
            val outputPreview = toolResult.content.take(5000)
            val editInfo = if (toolName == "edit_file" && !toolResult.isError) {
                try {
                    val argsObj = json.decodeFromString<JsonObject>(toolCall.function.arguments)
                    ToolCallInfo(
                        toolName = toolName,
                        args = toolCall.function.arguments.take(1000),
                        result = toolResult.summary,
                        durationMs = toolDurationMs,
                        isError = toolResult.isError,
                        output = outputPreview,
                        editFilePath = argsObj["path"]?.jsonPrimitive?.content,
                        editOldText = argsObj["old_string"]?.jsonPrimitive?.content?.take(500),
                        editNewText = argsObj["new_string"]?.jsonPrimitive?.content?.take(500)
                    )
                } catch (_: Exception) {
                    ToolCallInfo(toolName = toolName, args = toolCall.function.arguments.take(1000), result = toolResult.summary, durationMs = toolDurationMs, isError = toolResult.isError, output = outputPreview)
                }
            } else {
                ToolCallInfo(
                    toolName = toolName,
                    args = toolCall.function.arguments.take(1000),
                    result = toolResult.summary,
                    durationMs = toolDurationMs,
                    isError = toolResult.isError,
                    output = outputPreview
                )
            }

            // Skip UI tool call card for attempt_completion — handled as completion event
            if (toolName != "attempt_completion") {
                onProgress(AgentProgress(
                    step = "Used tool: $toolName",
                    tokensUsed = bridge.currentTokens,
                    toolCallInfo = editInfo
                ))
            }
            onDebugLog?.invoke(
                if (toolResult.isError) "warn" else "info", "tool_call",
                "$toolName ${if (toolResult.isError) "ERROR" else "OK"} (${toolDurationMs}ms)",
                mapOf("tool" to toolName, "duration" to toolDurationMs, "tokens" to toolResult.tokenEstimate)
            )
        }

        // Trace: record iteration completion with tool list
        val toolNames = effectiveToolCalls.map { it.function.name }
        sessionTrace?.iterationCompleted(iteration, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, toolNames, choice.finishReason)
        agentFileLogger?.logIteration(sessionId, iteration, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, choice.finishReason, toolNames, System.currentTimeMillis() - iterationStartMs)
        onDebugLog?.invoke("info", "iteration", "Iter $iteration: ${toolNames.size} tools, ${usage?.totalTokens ?: 0} tokens",
            mapOf("iteration" to iteration, "tokens" to (usage?.totalTokens ?: 0)))

        // LoopGuard: check for loops, error nudges, instruction-fade reminders
        val loopGuardMessages = loopGuard.afterIteration(effectiveToolCalls, toolResults, editedFiles.toList())
        for (msg in loopGuardMessages) {
            bridge.addMessage(msg)
            if (msg.content?.contains("same arguments") == true) {
                eventLog?.log(AgentEventType.LOOP_DETECTED, msg.content ?: "")
            }
        }

        // Backpressure gate: nudge after N edits without verification
        val backpressureNudge = backpressureGate.checkAndGetNudge()
        if (backpressureNudge != null) {
            bridge.addMessage(backpressureNudge)
        }

        // Self-correction gate: demand verification after edits
        val verificationDemand = selfCorrectionGate.getVerificationDemand()
        if (verificationDemand != null) {
            bridge.addMessage(verificationDemand)
        }

        // Flush event store before checkpoint (ensures events survive crashes)
        try { bridge.flushEvents() } catch (_: Exception) {}

        // Emit checkpoint after each iteration for durable execution
        try {
            val lastTool = effectiveToolCalls.lastOrNull()?.function?.name
            onCheckpoint?.invoke(IterationCheckpointData(
                iteration = iteration,
                tokensUsed = totalTokensUsed,
                lastToolCall = lastTool,
                editedFiles = editedFiles.toList(),
                hasPlan = bridge.hasPlanAnchor,
                lastActivity = "Iteration $iteration: ${toolNames.joinToString(", ")}"
            ))
        } catch (_: Exception) { /* checkpoint is best-effort */ }

        return null // continue loop
    }

    /**
     * Record a fact from a tool result into the FactsStore for compression-proof retention.
     * Extracts structured information from tool results based on the tool type.
     */
    private fun recordFactFromToolResult(
        toolName: String,
        toolArgs: String,
        content: String,
        summary: String,
        iteration: Int,
        bridge: EventSourcedContextBridge,
        project: Project
    ) {
        val factsStore = bridge.factsStore ?: return
        val filePath = AgentStringUtils.JSON_FILE_PATH_REGEX.find(toolArgs)?.groupValues?.get(1)
        when (toolName) {
            "read_file" -> if (filePath != null) {
                val lineCount = content.lines().size
                val firstLine = content.lineSequence().firstOrNull()?.take(80) ?: ""
                factsStore.record(Fact(FactType.FILE_READ, filePath, "$lineCount lines. Starts with: $firstLine", iteration))
            }
            "edit_file", "create_file" -> if (filePath != null) {
                // COMPRESSION: Enriched summary survives in factsAnchor. Format includes
                // "+X/-Y lines" so the LLM has line-level awareness even after the full
                // tool result is pruned from context during Phase 1 tiered pruning.
                val ledger = try { com.workflow.orchestrator.agent.AgentService.getInstance(project).currentChangeLedger } catch (_: Exception) { null }
                val latestChange = ledger?.changesForFile(filePath)?.lastOrNull()
                val enrichedSummary = if (latestChange != null) {
                    "iter ${latestChange.iteration}: +${latestChange.linesAdded}/-${latestChange.linesRemoved} lines. ${summary.take(150)}"
                } else {
                    summary.take(200)
                }
                factsStore.record(Fact(FactType.EDIT_MADE, filePath, enrichedSummary, iteration))
            }
            "search_code", "glob_files", "find_references", "find_definition" -> {
                factsStore.record(Fact(FactType.CODE_PATTERN, filePath, summary.take(200), iteration))
            }
            "run_command", "runtime_config", "runtime_exec" -> {
                factsStore.record(Fact(FactType.COMMAND_RESULT, null, summary.take(200), iteration))
            }
            "diagnostics", "run_inspections" -> {
                if (content.contains("error") || content.contains("warning")) {
                    factsStore.record(Fact(FactType.ERROR_FOUND, filePath, summary.take(200), iteration))
                }
            }
            "think" -> {
                if (toolArgs.length > 100) {
                    factsStore.record(Fact(
                        FactType.DISCOVERY, null,
                        "Agent reasoning: ${toolArgs.take(300)}",
                        iteration
                    ))
                }
            }
        }
        bridge.updateFactsAnchor()
    }

    /**
     * Execute a single tool call and return the raw result WITHOUT adding to context.
     * Used by both parallel (read-only) and sequential (write) execution paths.
     * Returns Triple(toolCall, result, durationMs).
     */
    private suspend fun executeSingleToolRaw(
        toolCall: ToolCall,
        tools: Map<String, AgentTool>,
        project: Project,
        approvalGate: ApprovalGate?,
        eventLog: AgentEventLog?,
        sessionTrace: SessionTrace?,
        onProgress: ((AgentProgress) -> Unit)?
    ): Triple<ToolCall, ToolResult, Long> {
        val toolName = toolCall.function.name
        val tool = tools[toolName]

        if (tool == null) {
            eventLog?.log(AgentEventType.TOOL_FAILED, "Tool not found: $toolName")
            return Triple(toolCall, ToolResult(
                content = "Error: Tool '$toolName' not found. Available tools: ${tools.keys.joinToString(", ")}",
                summary = "Tool not found: $toolName",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            ), 0L)
        }

        // Plan mode execution guard — belt-and-suspenders safety net.
        // Tools should already be filtered from the schema, but if one slips through
        // (e.g. cached tool call from before mode switch), block it here.
        if (isPlanModeBlocked(toolName)) {
            val msg = "Tool '$toolName' is blocked in plan mode. Create and get your plan approved first, then plan mode will deactivate and you can use write tools."
            eventLog?.log(AgentEventType.TOOL_FAILED, "$toolName: blocked by plan mode")
            return Triple(toolCall, ToolResult(
                content = msg,
                summary = "Blocked: $toolName (plan mode)",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            ), 0L)
        }

        // Plan mode: block write actions within meta-tools (jira, bamboo, bitbucket, git)
        if (AgentService.planModeActive.get() && toolName in META_TOOLS_WITH_WRITE_ACTIONS) {
            try {
                val metaParams = json.parseToJsonElement(toolCall.function.arguments)
                val action = (metaParams as? kotlinx.serialization.json.JsonObject)?.get("action")
                    ?.jsonPrimitive?.content
                if (action != null && action in PLAN_MODE_BLOCKED_ACTIONS) {
                    val msg = "Action '$action' on '$toolName' is blocked in plan mode. Get your plan approved first."
                    eventLog?.log(AgentEventType.TOOL_FAILED, "$toolName.$action: blocked by plan mode")
                    return Triple(toolCall, ToolResult(
                        content = msg,
                        summary = "Blocked: $toolName.$action (plan mode)",
                        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    ), 0L)
                }
            } catch (_: Exception) { /* parsing failed, allow through */ }
        }

        // Check approval gate before executing risky tools.
        // Skip for: attempt_completion (internal orchestration), plan mode tools (frictionless transitions)
        val skipApproval = toolName in APPROVAL_GATE_EXEMPT_TOOLS
        if (approvalGate != null && !skipApproval) {
            // Convert tool call arguments to map for context-aware risk classification + approval display
            val paramsMap: Map<String, Any?> = try {
                val jsonElement = json.parseToJsonElement(toolCall.function.arguments)
                if (jsonElement is kotlinx.serialization.json.JsonObject) {
                    jsonElement.entries.associate { (k, v) ->
                        k to when (v) {
                            is kotlinx.serialization.json.JsonPrimitive -> v.content
                            else -> v.toString()
                        }
                    }
                } else emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
            eventLog?.log(AgentEventType.APPROVAL_REQUESTED, "$toolName (risk: ${ApprovalGate.classifyRisk(toolName, paramsMap)})")
            val approval = approvalGate.check(toolName, paramsMap)
            metrics.approvalCount++
            when (approval) {
                is ApprovalResult.Rejected -> {
                    eventLog?.log(AgentEventType.APPROVAL_DENIED, toolName)
                    return Triple(toolCall, ToolResult(
                        content = "Tool call rejected by user. The user chose not to allow this action.",
                        summary = "Rejected: $toolName",
                        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    ), 0L)
                }
                is ApprovalResult.Pending -> {
                    return Triple(toolCall, ToolResult(
                        content = "Tool call pending user approval. Waiting for user decision.",
                        summary = "Pending approval: $toolName",
                        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    ), 0L)
                }
                is ApprovalResult.Approved -> {
                    eventLog?.log(AgentEventType.APPROVAL_GRANTED, toolName)
                }
            }
        }

        // Emit pre-execution progress (skip for attempt_completion — handled as completion event)
        if (toolName != "attempt_completion") {
            onProgress?.invoke(AgentProgress(
                step = "Calling tool: $toolName",
                tokensUsed = 0, // Don't access bridge from parallel context
                toolCallInfo = ToolCallInfo(
                    toolName = toolName,
                    args = toolCall.function.arguments.take(1000),
                    isError = false,
                    toolCallId = toolCall.id
                )
            ))
        }

        val toolStartMs = System.currentTimeMillis()
        return try {
            val rawArgs = toolCall.function.arguments.let { if (it.isBlank()) "{}" else it }
            eventLog?.log(AgentEventType.TOOL_CALLED, "$toolName(${rawArgs.take(100)})")
            val params = json.decodeFromString<JsonObject>(rawArgs)

            // 5A: Before executing edit_file, validate the content being written
            if (toolName == "edit_file") {
                val newString = params["new_string"]?.jsonPrimitive?.content
                if (newString != null) {
                    try {
                        OutputValidator.validateOrThrow(newString)
                    } catch (e: SecurityViolationException) {
                        LOG.warn("[Agent:Security] edit_file blocked by OutputValidator: ${e.issues.joinToString("; ")}")
                        eventLog?.log(AgentEventType.TOOL_FAILED, "$toolName: security violation blocked edit")
                        return Triple(toolCall, ToolResult(
                            content = "Security violation blocked this edit: ${e.issues.joinToString("; ")}",
                            summary = "Security violation: edit blocked",
                            tokenEstimate = 50,
                            isError = true
                        ), System.currentTimeMillis() - toolStartMs)
                    }
                }
            }

            // Set tool call ID for streaming output + process kill support
            if (toolName == "run_command" || toolName == "runtime_config" || toolName == "runtime_exec") {
                RunCommandTool.currentToolCallId.set(toolCall.id)
            }
            val toolResult = try {
                tool.execute(params, project)
            } finally {
                RunCommandTool.currentToolCallId.remove()
            }
            val toolDurationMs = System.currentTimeMillis() - toolStartMs

            if (toolResult.isError) {
                eventLog?.log(AgentEventType.TOOL_FAILED, "$toolName: ${toolResult.summary}")
            } else {
                eventLog?.log(AgentEventType.TOOL_SUCCEEDED, toolName)
            }

            Triple(toolCall, toolResult, toolDurationMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Never swallow CancellationException — propagate for structured concurrency
        } catch (e: Exception) {
            val toolDurationMs = System.currentTimeMillis() - toolStartMs
            LOG.warn("SingleAgentSession: tool '$toolName' failed", e)
            eventLog?.log(AgentEventType.TOOL_FAILED, "$toolName: ${e.message}")
            Triple(toolCall, ToolResult(
                content = "Error executing tool '$toolName': ${e.message}",
                summary = "Tool error: $toolName",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            ), toolDurationMs)
        }
    }

    /**
     * Call the LLM with retry logic for rate limits and context length exceeded.
     */
    private suspend fun callLlmWithRetry(
        brain: LlmBrain,
        messages: List<ChatMessage>,
        toolDefs: List<ToolDefinition>?,
        maxOutputTokens: Int?,
        onStreamChunk: (String) -> Unit,
        allToolDefs: List<ToolDefinition>,
        allTools: Map<String, AgentTool>,
        eventLog: AgentEventLog?,
        onDebugLog: ((String, String, String, Map<String, Any?>?) -> Unit)? = null,
        iteration: Int = 0
    ): LlmCallResult {
        var lastError: String? = null

        for (attempt in 1..MAX_RETRIES) {
            // Check cancellation before each attempt (especially important during retries)
            if (cancelled.get()) return LlmCallResult.Failed("Cancelled by user")

            // Use non-streaming to get complete, well-structured tool call arguments.
            // Sourcegraph's streaming SSE can produce empty/malformed tool call args
            // (tool name arrives but arguments are empty strings). Non-streaming
            // returns the full response in one shot with properly formed JSON.
            // Text content is pushed to UI after the response via onStreamChunk.
            val streamed = false
            val result = brain.chat(messages, toolDefs, maxOutputTokens)

            // Check cancellation after LLM call returns (call may have been aborted)
            if (cancelled.get()) return LlmCallResult.Failed("Cancelled by user")

            when (result) {
                is ApiResult.Success -> {
                    return LlmCallResult.Success(result.data, wasStreamed = streamed)
                }
                is ApiResult.Error -> {
                    // If cancelled and we got a network error, that's the abort — exit cleanly
                    if (cancelled.get()) return LlmCallResult.Failed("Cancelled by user")
                    when (result.type) {
                        ErrorType.RATE_LIMITED, ErrorType.SERVER_ERROR -> {
                            lastError = result.message
                            if (attempt < MAX_RETRIES) {
                                // Exponential backoff with random jitter (±50%)
                                val backoff = minOf(BASE_BACKOFF_MS * (1L shl (attempt - 1)), MAX_BACKOFF_MS)
                                val jitter = (backoff * 0.5 * kotlin.random.Random.nextDouble()).toLong()
                                val delayMs = backoff + jitter
                                val reason = if (result.type == ErrorType.RATE_LIMITED) "rate limited" else "server error"
                                LOG.info("SingleAgentSession: retry $attempt/$MAX_RETRIES after ${delayMs}ms ($reason)")
                                eventLog?.log(AgentEventType.RATE_LIMITED_RETRY, "Attempt $attempt, backoff ${delayMs}ms ($reason)")
                                onDebugLog?.invoke("warn", "retry", "$reason — attempt $attempt/$MAX_RETRIES, backoff ${delayMs}ms", mapOf("iteration" to iteration))
                                delay(delayMs) // delay() is cancellable — Job.cancel() will interrupt this
                            }
                            continue // retry with backoff
                        }
                        ErrorType.NETWORK_ERROR -> {
                            // Network error during cancellation = expected (socket closed by cancelActiveRequest)
                            if (cancelled.get()) return LlmCallResult.Failed("Cancelled by user")
                            return LlmCallResult.Failed(result.message)
                        }
                        ErrorType.CONTEXT_LENGTH_EXCEEDED -> {
                            // Reduce tools to core set and retry once
                            val reducedTools = allTools.filterKeys { it in CORE_TOOL_NAMES }
                            val reducedDefs = allToolDefs.filter { it.function.name in CORE_TOOL_NAMES }
                            return LlmCallResult.ContextExceededRetry(reducedDefs, reducedTools)
                        }
                        else -> {
                            // Also detect context_length from error message for APIs that don't use specific error type
                            if (result.message.contains("context_length", ignoreCase = true)) {
                                val reducedTools = allTools.filterKeys { it in CORE_TOOL_NAMES }
                                val reducedDefs = allToolDefs.filter { it.function.name in CORE_TOOL_NAMES }
                                return LlmCallResult.ContextExceededRetry(reducedDefs, reducedTools)
                            }
                            return LlmCallResult.Failed(result.message)
                        }
                    }
                }
            }
        }

        return LlmCallResult.Failed(lastError ?: "Failed after $MAX_RETRIES retries")
    }

    /** Internal result type for LLM calls with retry. */
    private sealed class LlmCallResult {
        class Success(
            val response: com.workflow.orchestrator.agent.api.dto.ChatCompletionResponse,
            var totalTokensSoFar: Int = 0,
            val wasStreamed: Boolean = false
        ) : LlmCallResult()
        data class Failed(val error: String) : LlmCallResult()
        data class ContextExceededRetry(
            val reducedToolDefs: List<ToolDefinition>,
            val reducedTools: Map<String, AgentTool>
        ) : LlmCallResult()
    }


    /**
     * Fallback system prompt when no PromptAssembler prompt is provided.
     * Used for backward compatibility in tests and when orchestrator doesn't provide one.
     */
    private fun buildFallbackSystemPrompt(): String {
        return """
            You are an AI coding assistant for the Workflow Orchestrator IntelliJ plugin.
            You have access to all tools and can analyze code, edit files, review changes,
            and interact with enterprise tools (Jira, Bamboo, SonarQube, Bitbucket).

            <capabilities>
            - Read and analyze code using PSI-based tools (find references, type hierarchy, call graph)
            - Edit files precisely using the edit_file tool
            - Search code across the project
            - Run shell commands when needed
            - Interact with Jira (read tickets, update status, add comments, log time)
            - Check Bamboo build status and trigger builds
            - Query SonarQube for issues and coverage
            - Create Bitbucket pull requests
            </capabilities>

            <rules>
            - Make minimal, focused edits. Don't rewrite entire files.
            - Preserve existing code style (indentation, naming, comments).
            - For IntelliJ plugin code: never block the EDT, use suspend functions for I/O.
            - Handle errors gracefully and report what happened.
            - Never store, log, or output credentials, tokens, or secrets.
            - Confirm destructive actions before executing them.
            - If you call the same tool 3 times with the same arguments, try a different approach.
            </rules>

            <output>
            When you've completed the task, provide a clear summary of what you did,
            what files were changed, and any issues encountered.
            </output>
        """.trimIndent()
    }

    /**
     * Build a [SessionScorecard] from current session state.
     * Called at each session exit point (completed, failed, cancelled, rotated).
     */
    internal fun buildScorecard(
        sessionId: String,
        status: String,
        selfCorrectionGate: SelfCorrectionGate?,
        durationMs: Long,
        project: Project
    ): SessionScorecard {
        val planManager = try {
            com.workflow.orchestrator.agent.AgentService.getInstance(project).currentPlanManager
        } catch (_: Exception) { null }
        val plan = planManager?.currentPlan
        val planStepsTotal = plan?.steps?.size ?: 0
        val planStepsCompleted = plan?.steps?.count { it.status == "completed" || it.status == "done" } ?: 0

        return SessionScorecard.compute(
            sessionId = sessionId,
            taskDescription = currentTask,
            status = status,
            agentMetrics = metrics,
            selfCorrectionGate = selfCorrectionGate,
            planStepsTotal = planStepsTotal,
            planStepsCompleted = planStepsCompleted,
            durationMs = durationMs,
            totalInputTokens = sessionInputTokens,
            totalOutputTokens = sessionOutputTokens,
            hallucinationFlags = hallucinationFlags,
            credentialLeakAttempts = credentialLeakAttempts,
            doomLoopTriggers = doomLoopTriggers,
            guardrailHits = guardrailHits
        )
    }

}
