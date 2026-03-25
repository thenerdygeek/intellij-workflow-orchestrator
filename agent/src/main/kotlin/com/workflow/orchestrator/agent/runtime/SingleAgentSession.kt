package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.context.Fact
import com.workflow.orchestrator.agent.context.FactType
import com.workflow.orchestrator.agent.context.FactsStore
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.orchestrator.AgentProgress
import com.workflow.orchestrator.agent.orchestrator.ToolCallInfo
import com.workflow.orchestrator.agent.security.CredentialRedactor
import com.workflow.orchestrator.agent.security.OutputValidator
import com.workflow.orchestrator.agent.security.SecurityViolationException
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
        val snapshotRef: String? = null
    ) : SingleAgentResult()

    /** Task failed with an error. */
    data class Failed(
        val error: String,
        val tokensUsed: Int,
        val snapshotRef: String? = null
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
 * - Nudge injection (NUDGE, STRONG_NUDGE) and budget termination instead of escalation
 * - Graceful retry on rate limits (429) and context length exceeded
 * - [OutputValidator] on final content
 * - Progress callbacks for each iteration
 *
 * Max iterations: 50 (higher than WorkerSession's 10, since this handles full tasks).
 */
class SingleAgentSession(
    private val maxIterations: Int = 50,
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

        /** Core tools kept during context reduction (when context_length_exceeded). */
        val CORE_TOOL_NAMES = setOf("read_file", "edit_file", "search_code", "run_command", "diagnostics", "delegate_task", "think")

        /** Patterns that indicate the model intended to use a tool but stopped without calling it. */
        private val TOOL_INTENT_PATTERNS = listOf(
            "let me check", "let me run", "let me read", "let me look",
            "i'll check", "i'll run", "i'll read", "i'll look",
            "i will check", "i will run", "i will read", "i will look",
            "let me search", "let me find", "i'll search", "i'll find",
            "let me examine", "let me inspect", "let me verify",
            "i'll examine", "i'll inspect", "i'll verify",
            "let me see", "i'll see what", "let me get", "i'll get",
        )

        /** Read-only tools safe to execute in parallel (no side effects on project state). */
        private val READ_ONLY_TOOLS = setOf(
            "read_file", "search_code", "glob_files", "file_structure",
            "find_definition", "find_references", "type_hierarchy", "call_hierarchy",
            "diagnostics", "git_status", "git_blame", "git_diff", "git_log",
            "git_branches", "git_show_file", "git_show_commit", "git_stash_list", "git_merge_base", "git_file_history",
            "find_implementations",
            "spring_context", "spring_endpoints", "spring_bean_graph",
            // Jira read-only
            "jira_get_ticket", "jira_get_comments", "jira_get_transitions",
            "jira_get_worklogs", "jira_get_sprints", "jira_get_linked_prs",
            "jira_get_boards", "jira_get_sprint_issues", "jira_get_board_issues",
            "jira_search_issues", "jira_get_dev_branches",
            // Bamboo read-only
            "bamboo_build_status", "bamboo_get_build", "bamboo_get_test_results",
            "bamboo_get_artifacts", "bamboo_recent_builds", "bamboo_get_plans",
            "bamboo_get_project_plans", "bamboo_search_plans", "bamboo_get_plan_branches",
            "bamboo_get_running_builds", "bamboo_get_build_variables", "bamboo_get_plan_variables",
            // Sonar read-only
            "sonar_issues", "sonar_quality_gate", "sonar_coverage",
            "sonar_search_projects", "sonar_analysis_tasks",
            "sonar_branches", "sonar_project_measures", "sonar_source_lines", "sonar_issues_paged",
            // Bitbucket read-only
            "bitbucket_get_pr_commits", "bitbucket_get_file_content", "bitbucket_get_branches",
            "bitbucket_search_users", "bitbucket_get_my_prs", "bitbucket_get_reviewing_prs",
            "bitbucket_get_pr_detail", "bitbucket_get_pr_activities", "bitbucket_get_pr_changes",
            "bitbucket_get_pr_diff", "bitbucket_get_build_statuses", "bitbucket_check_merge_status",
            "bitbucket_list_repos"
        )
    }

    /**
     * Execute the single-agent ReAct loop.
     *
     * @param task The full task description from the user
     * @param tools Map of tool name to AgentTool (all registered tools)
     * @param toolDefinitions Tool definitions for the LLM
     * @param brain The LLM brain
     * @param contextManager Manages conversation history with compression
     * @param project The IntelliJ project
     * @param systemPrompt The assembled system prompt (from PromptAssembler)
     * @param maxOutputTokens Maximum output tokens for LLM responses
     * @param approvalGate Optional gate for risk-based approval of tool actions
     * @param eventLog Optional structured event log for audit trail
     * @param onProgress Callback for progress updates
     * @param onStreamChunk Callback for streaming LLM output tokens (for real-time UI)
     * @return [SingleAgentResult] — completed or failed
     */
    suspend fun execute(
        task: String,
        tools: Map<String, AgentTool>,
        toolDefinitions: List<ToolDefinition>,
        brain: LlmBrain,
        contextManager: ContextManager,
        project: Project,
        systemPrompt: String? = null,
        maxOutputTokens: Int? = null,
        approvalGate: ApprovalGate? = null,
        eventLog: AgentEventLog? = null,
        sessionTrace: SessionTrace? = null,
        sessionId: String = "",
        onProgress: (AgentProgress) -> Unit = {},
        onStreamChunk: (String) -> Unit = {},
        onDebugLog: ((String, String, String, Map<String, Any?>?) -> Unit)? = null
    ): SingleAgentResult {
        LOG.info("SingleAgentSession: starting with ${tools.size} tools for task: ${task.take(100)}")
        eventLog?.log(AgentEventType.SESSION_STARTED, "Task: ${task.take(200)}, tools: ${tools.size}")

        val effectiveBudget = contextManager.remainingBudget() + contextManager.currentTokens
        val budgetEnforcer = BudgetEnforcer(contextManager, effectiveBudget)

        sessionTrace?.sessionStarted(task, tools.size, effectiveBudget - contextManager.remainingBudget(), effectiveBudget)
        agentFileLogger?.logSessionStart(sessionId, task, tools.size)

        val sessionStartMs = System.currentTimeMillis()

        // Only add system prompt if explicitly provided (first message).
        // On multi-turn, systemPrompt is null — already in context from session.initialize().
        if (systemPrompt != null) {
            contextManager.addMessage(ChatMessage(role = "system", content = systemPrompt))
        }
        contextManager.addMessage(ChatMessage(role = "user", content = task))

        // Initialize LoopGuard for loop detection and auto-verification
        val loopGuard = LoopGuard()

        // Initialize FactsStore for compression-proof knowledge retention
        if (contextManager.factsStore == null) {
            contextManager.factsStore = FactsStore(maxFacts = 50)
        }

        var totalTokensUsed = 0
        val allArtifacts = mutableListOf<String>()
        val editedFiles = mutableListOf<String>()
        var nudgeEmitted = false
        var strongNudgeEmitted = false
        var lastWarningPercent = 0

        // Track current tool definitions (may be reduced on context_length_exceeded)
        var activeToolDefs = toolDefinitions
        var activeTools = tools
        var verificationPending = false
        forceTextOnly = false
        consecutiveMalformedRetries = 0

        for (iteration in 1..maxIterations) {
            // Mid-loop cancellation check
            if (cancelled.get()) {
                LOG.info("SingleAgentSession: cancelled at iteration $iteration")
                return SingleAgentResult.Completed(
                    content = "Task cancelled by user after $iteration iterations.",
                    summary = "Cancelled",
                    tokensUsed = totalTokensUsed,
                    artifacts = allArtifacts
                )
            }

            LOG.info("SingleAgentSession: iteration $iteration/$maxIterations")
            val iterationStartMs = System.currentTimeMillis()
            metrics.turnCount = iteration
            sessionTrace?.iterationStarted(iteration, contextManager.currentTokens, budgetEnforcer.utilizationPercent())
            onProgress(AgentProgress(
                step = "Thinking... (iteration $iteration)",
                tokensUsed = contextManager.currentTokens
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

            // Budget check before each LLM call
            val budgetStatus = budgetEnforcer.check()
            when (budgetStatus) {
                BudgetEnforcer.BudgetStatus.TERMINATE -> {
                    LOG.warn("SingleAgentSession: budget exhausted at iteration $iteration (${budgetEnforcer.utilizationPercent()}%)")
                    eventLog?.log(AgentEventType.SESSION_FAILED, "Budget terminated at ${budgetEnforcer.utilizationPercent()}%")
                    sessionTrace?.sessionFailed("Budget terminated at ${budgetEnforcer.utilizationPercent()}%", totalTokensUsed, iteration)
                    val budgetTerminateError = "Context budget exhausted at ${budgetEnforcer.utilizationPercent()}%. Please start a new conversation for remaining work."
                    agentFileLogger?.logSessionEnd(sessionId, iteration, totalTokensUsed, System.currentTimeMillis() - sessionStartMs, error = budgetTerminateError)
                    return SingleAgentResult.Failed(
                        error = budgetTerminateError,
                        tokensUsed = totalTokensUsed
                    )
                }
                BudgetEnforcer.BudgetStatus.STRONG_NUDGE -> {
                    if (!strongNudgeEmitted) {
                        strongNudgeEmitted = true
                        LOG.warn("SingleAgentSession: strong nudge at iteration $iteration (${budgetEnforcer.utilizationPercent()}%)")
                        contextManager.addMessage(ChatMessage(
                            role = "system",
                            content = "WARNING: You have used ${budgetEnforcer.utilizationPercent()}% of your context budget. You MUST use delegate_task for any remaining task touching 2+ files. Single-file edits are still allowed directly."
                        ))
                        // Compress to free space at high utilization
                        val tokensBefore = contextManager.currentTokens
                        contextManager.compressWithLlm(brain)
                        metrics.compressionCount++
                        loopGuard.clearAllFileReads()
                        onDebugLog?.invoke("warn", "compression", "$tokensBefore → ${contextManager.currentTokens} tokens (strong nudge)", null)
                    }
                }
                BudgetEnforcer.BudgetStatus.NUDGE -> {
                    if (!nudgeEmitted) {
                        nudgeEmitted = true
                        LOG.info("SingleAgentSession: nudge at iteration $iteration (${budgetEnforcer.utilizationPercent()}%)")
                        contextManager.addMessage(ChatMessage(
                            role = "system",
                            content = "Context at ${budgetEnforcer.utilizationPercent()}%. Prefer delegate_task for remaining multi-file work — each worker gets a fresh context window."
                        ))
                    }
                }
                BudgetEnforcer.BudgetStatus.COMPRESS -> {
                    LOG.info("SingleAgentSession: triggering LLM compression at iteration $iteration")
                    eventLog?.log(AgentEventType.COMPRESSION_TRIGGERED, "At iteration $iteration, ${budgetEnforcer.utilizationPercent()}% used")
                    val tokensBefore = contextManager.currentTokens
                    val messagesBefore = contextManager.messageCount
                    // Use LLM-powered compression when brain is available
                    contextManager.compressWithLlm(brain)
                    metrics.compressionCount++
                    loopGuard.clearAllFileReads()
                    val tokensAfter = contextManager.currentTokens
                    sessionTrace?.compressionTriggered("budget_enforcer", tokensBefore, tokensAfter, messagesBefore - contextManager.messageCount)
                    agentFileLogger?.logCompression(sessionId, "budget_enforcer", tokensBefore, tokensAfter)
                    onDebugLog?.invoke("warn", "compression", "$tokensBefore → $tokensAfter tokens", null)
                }
                BudgetEnforcer.BudgetStatus.OK -> { /* proceed */ }
            }

            // Ask user to confirm continuing after iteration 25
            if (iteration == 25) {
                LOG.info("SingleAgentSession: reached iteration 25, continuing (budget: ${budgetEnforcer.utilizationPercent()}%)")
                onProgress(AgentProgress(
                    step = "Agent has been working for 25 iterations. Still making progress...",
                    tokensUsed = contextManager.currentTokens
                ))
            }

            // 5a: Inject context budget warning at 10% thresholds past 50% (50%, 60%, 70%, 80%, 90%)
            val maxInputTokens = contextManager.effectiveMaxInputTokens
            val usedPercent = if (maxInputTokens > 0) ((contextManager.currentTokens.toDouble() / maxInputTokens) * 100).toInt() else 0
            if (usedPercent > 50 && usedPercent / 10 > lastWarningPercent / 10) {
                lastWarningPercent = usedPercent
                // Cap system warnings at 2 — remove oldest before adding new one
                while (contextManager.countSystemWarnings() >= 2) {
                    contextManager.removeOldestSystemWarning()
                }
                val remaining = maxInputTokens - contextManager.currentTokens
                contextManager.addMessage(ChatMessage(
                    role = "system",
                    content = "<system_warning>Context usage: ${contextManager.currentTokens}/$maxInputTokens tokens ($usedPercent%). $remaining tokens remaining. Be efficient with remaining context.</system_warning>"
                ))
            }

            // 5b: Graceful degradation at high iterations
            val iterationPercent = (iteration * 100) / maxIterations
            when {
                iterationPercent >= 95 -> {
                    while (contextManager.countSystemWarnings() >= 2) {
                        contextManager.removeOldestSystemWarning()
                    }
                    contextManager.addMessage(ChatMessage(
                        role = "system",
                        content = "<system_warning>CRITICAL: This is your final iteration. Tools are disabled after this response. Provide a complete summary of what you accomplished and what remains.</system_warning>"
                    ))
                    forceTextOnly = true
                }
                iterationPercent >= 80 -> {
                    while (contextManager.countSystemWarnings() >= 2) {
                        contextManager.removeOldestSystemWarning()
                    }
                    contextManager.addMessage(ChatMessage(
                        role = "system",
                        content = "<system_warning>IMPORTANT: You have used $iteration of $maxIterations iterations. Focus on completing the task. Avoid unnecessary exploration.</system_warning>"
                    ))
                }
            }

            val messages = contextManager.getMessages()
            val toolDefsForCall = if (forceTextOnly) null else if (activeTools.isNotEmpty()) activeToolDefs else null

            // LLM call with retry logic for rate limits and context length exceeded
            val result = callLlmWithRetry(
                brain, messages, toolDefsForCall, maxOutputTokens,
                onStreamChunk, activeToolDefs, activeTools, eventLog, onDebugLog, iteration
            )

            // Handle tool reduction if context exceeded
            if (result is LlmCallResult.ContextExceededRetry) {
                activeToolDefs = result.reducedToolDefs
                activeTools = result.reducedTools
                eventLog?.log(AgentEventType.CONTEXT_EXCEEDED_RETRY, "Reduced to ${activeTools.size} core tools")
                LOG.info("SingleAgentSession: context exceeded — pruning old tool results + compressing")
                // Phase 1: Prune old tool results (fast, no LLM)
                contextManager.pruneOldToolResults()
                // Phase 2: Full compression (LLM if brain available, otherwise truncation)
                val tokensBefore = contextManager.currentTokens
                try { contextManager.compressWithLlm(brain) } catch (_: Exception) { contextManager.compress() }
                metrics.compressionCount++
                loopGuard.clearAllFileReads()
                onDebugLog?.invoke("warn", "compression", "$tokensBefore → ${contextManager.currentTokens} tokens (context overflow)", null)
                // Re-fetch messages after compression
                val compressedMessages = contextManager.getMessages()
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
                    return SingleAgentResult.Failed(error = errorMsg, tokensUsed = totalTokensUsed)
                }
                // Process the successful retry below
                return processLlmSuccess(
                    retryResult as LlmCallResult.Success, iteration, totalTokensUsed, allArtifacts,
                    editedFiles, activeTools, contextManager, project, approvalGate, loopGuard,
                    budgetEnforcer, brain, activeToolDefs, maxOutputTokens, onProgress,
                    onStreamChunk, eventLog, sessionTrace, maxIterations,
                    sessionId, sessionStartMs, iterationStartMs, onDebugLog
                ) ?: continue
            }

            when (result) {
                is LlmCallResult.Success -> {
                    val sessionResult = processLlmSuccess(
                        result, iteration, totalTokensUsed, allArtifacts,
                        editedFiles, activeTools, contextManager, project, approvalGate, loopGuard,
                        budgetEnforcer, brain, activeToolDefs, maxOutputTokens, onProgress,
                        onStreamChunk, eventLog, sessionTrace, maxIterations,
                        sessionId, sessionStartMs, iterationStartMs, onDebugLog
                    )
                    if (sessionResult != null) return sessionResult
                    totalTokensUsed = result.totalTokensSoFar
                }
                is LlmCallResult.Failed -> {
                    eventLog?.log(AgentEventType.SESSION_FAILED, result.error)
                    sessionTrace?.dumpConversationState(contextManager.getMessages(), "llm_call_failed: ${result.error}")
                    sessionTrace?.sessionFailed(result.error, totalTokensUsed, iteration)
                    val llmFailedError = "LLM call failed: ${result.error}"
                    agentFileLogger?.logSessionEnd(sessionId, iteration, totalTokensUsed, System.currentTimeMillis() - sessionStartMs, error = llmFailedError)
                    onDebugLog?.invoke("error", "error", llmFailedError, null)
                    return SingleAgentResult.Failed(
                        error = llmFailedError,
                        tokensUsed = totalTokensUsed
                    )
                }
                is LlmCallResult.ContextExceededRetry -> {
                    // Already handled above
                }
            }
        }

        LOG.warn("SingleAgentSession: reached max iterations ($maxIterations)")
        eventLog?.log(AgentEventType.SESSION_FAILED, "Max iterations ($maxIterations) reached")
        sessionTrace?.dumpConversationState(contextManager.getMessages(), "max_iterations_reached")
        sessionTrace?.sessionFailed("Max iterations ($maxIterations) reached", totalTokensUsed, maxIterations)
        val maxIterError = "Reached maximum iterations ($maxIterations) without completing"
        agentFileLogger?.logSessionEnd(sessionId, maxIterations, totalTokensUsed, System.currentTimeMillis() - sessionStartMs, error = maxIterError)
        return SingleAgentResult.Failed(
            error = maxIterError,
            tokensUsed = totalTokensUsed
        )
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
        contextManager: ContextManager,
        project: Project,
        approvalGate: ApprovalGate?,
        loopGuard: LoopGuard,
        budgetEnforcer: BudgetEnforcer,
        brain: LlmBrain,
        toolDefinitions: List<ToolDefinition>,
        maxOutputTokens: Int?,
        onProgress: (AgentProgress) -> Unit,
        onStreamChunk: (String) -> Unit,
        eventLog: AgentEventLog?,
        sessionTrace: SessionTrace?,
        maxIterations: Int,
        sessionId: String = "",
        sessionStartMs: Long = 0L,
        iterationStartMs: Long = 0L,
        onDebugLog: ((String, String, String, Map<String, Any?>?) -> Unit)? = null
    ): SingleAgentResult? {
        val response = result.response
        val usage = response.usage
        var totalTokensUsed = totalTokensUsedBefore
        if (usage != null && usage.promptTokens > 0) {
            totalTokensUsed += usage.totalTokens
            // Reconcile heuristic token count with actual API-reported count.
            // Our character-based estimator (length/3.5) can be 20-40% off.
            // The API's prompt_tokens is authoritative — calibrate to it.
            contextManager.reconcileWithActualTokens(usage.promptTokens)
        } else {
            // Streaming response returned null/zero usage — estimate heuristically
            // so budget tracking doesn't drift. Not authoritative, but better than nothing.
            val estimatedPrompt = TokenEstimator.estimate(contextManager.getMessages())
            val estimatedCompletion = TokenEstimator.estimate(response.choices.firstOrNull()?.message?.content ?: "")
            totalTokensUsed += estimatedPrompt + estimatedCompletion
        }
        // Store for caller
        (result as LlmCallResult.Success).totalTokensSoFar = totalTokensUsed

        val choice = response.choices.firstOrNull() ?: return SingleAgentResult.Failed(
            error = "Empty response from LLM", tokensUsed = totalTokensUsed
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
            contextManager.addAssistantMessage(cleanMessage)
        } else {
            LOG.info("SingleAgentSession: skipping empty assistant message (no content, no valid tool calls)")
        }

        // Show assistant's text content in UI. If the response was streamed,
        // tokens were already pushed via onStreamChunk during the LLM call.
        // Only push here for non-streamed responses (fallback path).
        if (!cleanMessage.content.isNullOrBlank() && !result.wasStreamed) {
            onStreamChunk(cleanMessage.content!!)
        }

        // Handle truncated responses (output token limit hit).
        // finishReason="length" means the model hit max_tokens before completing.
        // Two cases: (1) truncated text response, (2) truncated tool call (invalid JSON).
        if (choice.finishReason == "length") {
            if (toolCalls.isNullOrEmpty()) {
                // Case 1: Text response truncated — ask to continue or summarize
                LOG.info("SingleAgentSession: text response truncated (finishReason=length), requesting continuation")
                contextManager.addMessage(ChatMessage(
                    role = "user",
                    content = "Your response was truncated due to the output token limit. " +
                        "If you were about to use a tool, please do so now. " +
                        "If you were providing a final answer, please provide a concise summary instead."
                ))
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
                    contextManager.addMessage(ChatMessage(
                        role = "user",
                        content = "Your previous tool call to '$truncatedToolName' was truncated because " +
                            "it exceeded the output token limit. The arguments were incomplete and could not be parsed. " +
                            "Please retry with a SMALLER operation — for example, edit one function at a time " +
                            "instead of an entire file, or break the task into multiple tool calls."
                    ))
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
                    onProgress(AgentProgress(step = "Tool calls failed $MAX_MALFORMED_RETRIES times — switching to text response", tokensUsed = contextManager.currentTokens))
                    contextManager.addMessage(ChatMessage(
                        role = "user",
                        content = "IMPORTANT: Your last $MAX_MALFORMED_RETRIES attempts to call tools all failed because the tool call " +
                            "arguments were empty or malformed. The streaming API is not delivering your tool call arguments correctly. " +
                            "DO NOT attempt any more tool calls. Instead, respond with a TEXT message explaining what you were " +
                            "trying to do, and I will help you accomplish it another way."
                    ))
                    forceTextOnly = true
                } else {
                    onProgress(AgentProgress(step = "Tool call failed (malformed args, retry $consecutiveMalformedRetries/$MAX_MALFORMED_RETRIES)...", tokensUsed = contextManager.currentTokens))
                    contextManager.addMessage(ChatMessage(
                        role = "user",
                        content = "Your previous response indicated tool calls (finish_reason=tool_calls) but the tool call " +
                            "arguments were empty or malformed and could not be parsed. Please retry — call ONE tool at a time with valid JSON arguments."
                    ))
                }
                return null // continue loop with retry guidance
            }

            // No tool calls — about to return final response

            // Detect truncated tool intent: the model said it would use a tool but stopped
            // without actually calling it (common when API silently caps output tokens).
            val content = message.content ?: ""
            val lowerContent = content.lowercase()
            val hasUnfulfilledIntent = TOOL_INTENT_PATTERNS.any { lowerContent.contains(it) }
            if (hasUnfulfilledIntent && iteration < maxIterations - 1) {
                LOG.info("SingleAgentSession: detected unfulfilled tool intent in text, nudging model to follow through")
                onDebugLog?.invoke("warn", "nudge", "Detected unfulfilled tool intent — nudging model to act", null)
                contextManager.addMessage(ChatMessage(
                    role = "user",
                    content = "You said you would use a tool but your response ended without making the tool call. " +
                        "Please make the actual tool call now — don't describe what you'll do, just do it."
                ))
                return null // continue loop
            }

            // LoopGuard: check if verification is needed before completing
            val verificationMsg = loopGuard.beforeCompletion()
            if (verificationMsg != null) {
                contextManager.addMessage(verificationMsg)
                return null // continue loop for verification
            }

            // Validate output for sensitive data and redact if needed
            val securityIssues = OutputValidator.validate(content)
            val sanitizedContent = if (securityIssues.isNotEmpty()) {
                LOG.warn("SingleAgentSession: output validation flagged: ${securityIssues.joinToString()}")
                CredentialRedactor.redact(content)
            } else content

            val summary = if (sanitizedContent.length > 200) sanitizedContent.take(200) + "..." else sanitizedContent
            LOG.info("SingleAgentSession: completed after $iteration iterations, $totalTokensUsed tokens")

            onProgress(AgentProgress(
                step = "Task completed",
                tokensUsed = contextManager.currentTokens
            ))

            eventLog?.log(AgentEventType.SESSION_COMPLETED, "Completed after $iteration iterations, $totalTokensUsed tokens")
            sessionTrace?.iterationCompleted(iteration, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, emptyList(), choice.finishReason)
            sessionTrace?.sessionMetrics(metrics.toJson())
            sessionTrace?.sessionCompleted(totalTokensUsed, iteration, allArtifacts)
            agentFileLogger?.logIteration(sessionId, iteration, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, choice.finishReason, emptyList(), System.currentTimeMillis() - iterationStartMs)
            agentFileLogger?.logSessionEnd(sessionId, iteration, totalTokensUsed, System.currentTimeMillis() - sessionStartMs)

            return SingleAgentResult.Completed(
                content = sanitizedContent,
                summary = summary,
                tokensUsed = totalTokensUsed,
                artifacts = allArtifacts
            )
        }

        // Reset malformed retry counter — we have valid tool calls
        consecutiveMalformedRetries = 0

        // Execute tool calls: read-only tools in parallel, write tools sequentially
        val toolResults = mutableListOf<Pair<String, Boolean>>() // (toolCallId, isError) for LoopGuard

        // Split into read-only (parallel-safe) and write (sequential) tool calls
        val (readOnlyCalls, writeCalls) = toolCalls.partition { tc ->
            tc.function.name in READ_ONLY_TOOLS
        }

        // Doom loop detection: check each tool call before execution, skip if detected
        val doomSkipped = mutableSetOf<String>() // toolCallIds skipped due to doom loop
        for (tc in toolCalls) {
            val doomMessage = loopGuard.checkDoomLoop(tc.function.name, tc.function.arguments)
            if (doomMessage != null) {
                // SKIP execution — don't waste time on a doom loop call
                contextManager.addToolResult(tc.id, doomMessage, "Doom loop detected — execution skipped")
                doomSkipped.add(tc.id)
                toolResults.add(tc.id to true)
                onProgress(AgentProgress(
                    step = "Skipped tool: ${tc.function.name} (doom loop detected)",
                    tokensUsed = contextManager.currentTokens
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
            // Add results to context sequentially (ContextManager is not thread-safe)
            for (entry in parallelResults) {
                val tc = entry.first
                val tr = entry.second
                val durMs = entry.third
                val toolName = tc.function.name
                // 5C: Redact credentials before injecting tool results into LLM context
                val redactedContent = CredentialRedactor.redact(tr.content)
                contextManager.addToolResult(
                    toolCallId = tc.id,
                    content = redactedContent,
                    summary = tr.summary
                )
                recordFactFromToolResult(toolName, tc.function.arguments, redactedContent, tr.summary, iteration, contextManager)
                allArtifacts.addAll(tr.artifacts)
                toolResults.add(tc.id to tr.isError)

                if (tr.isError) {
                    sessionTrace?.toolExecuted(toolName, durMs, tr.tokenEstimate, true, tr.summary)
                } else {
                    sessionTrace?.toolExecuted(toolName, durMs, tr.tokenEstimate, false)
                }

                // Record metrics and check circuit breaker
                metrics.recordToolCall(toolName, durMs, !tr.isError, tr.tokenEstimate.toLong())
                if (metrics.isCircuitBroken(toolName)) {
                    contextManager.addSystemMessage(
                        "Circuit breaker: '$toolName' has failed ${AgentMetrics.CIRCUIT_BREAKER_THRESHOLD} consecutive times. Try a different approach or tool."
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
                    tokensUsed = contextManager.currentTokens,
                    toolCallInfo = ToolCallInfo(
                        toolName = toolName,
                        args = tc.function.arguments.take(200),
                        result = tr.summary,
                        durationMs = durMs,
                        isError = tr.isError
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
                contextManager.addToolResult(toolCall.id, "Cancelled by user", "Cancelled")
                break
            }
            val (_, toolResult, toolDurationMs) = executeSingleToolRaw(toolCall, tools, project, approvalGate, eventLog, sessionTrace, onProgress)
            val toolName = toolCall.function.name

            // 5C: Redact credentials before injecting tool results into LLM context
            val redactedWriteContent = CredentialRedactor.redact(toolResult.content)
            contextManager.addToolResult(
                toolCallId = toolCall.id,
                content = redactedWriteContent,
                summary = toolResult.summary
            )
            recordFactFromToolResult(toolName, toolCall.function.arguments, redactedWriteContent, toolResult.summary, iteration, contextManager)

            allArtifacts.addAll(toolResult.artifacts)
            toolResults.add(toolCall.id to toolResult.isError)

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
            }

            if (toolResult.isError) {
                sessionTrace?.toolExecuted(toolName, toolDurationMs, toolResult.tokenEstimate, true, toolResult.summary)
            } else {
                sessionTrace?.toolExecuted(toolName, toolDurationMs, toolResult.tokenEstimate, false)
            }

            // Record metrics and check circuit breaker
            metrics.recordToolCall(toolName, toolDurationMs, !toolResult.isError, toolResult.tokenEstimate.toLong())
            if ((toolName == "agent" || toolName == "delegate_task") && !toolResult.isError) {
                metrics.subagentCount++
            }
            if (metrics.isCircuitBroken(toolName)) {
                contextManager.addSystemMessage(
                    "Circuit breaker: '$toolName' has failed ${AgentMetrics.CIRCUIT_BREAKER_THRESHOLD} consecutive times. Try a different approach or tool."
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
            val editInfo = if (toolName == "edit_file" && !toolResult.isError) {
                try {
                    val argsObj = json.decodeFromString<JsonObject>(toolCall.function.arguments)
                    ToolCallInfo(
                        toolName = toolName,
                        args = toolCall.function.arguments.take(200),
                        result = toolResult.summary,
                        durationMs = toolDurationMs,
                        isError = toolResult.isError,
                        editFilePath = argsObj["path"]?.toString()?.removeSurrounding("\""),
                        editOldText = argsObj["old_string"]?.toString()?.removeSurrounding("\"")?.take(500),
                        editNewText = argsObj["new_string"]?.toString()?.removeSurrounding("\"")?.take(500)
                    )
                } catch (_: Exception) {
                    ToolCallInfo(toolName = toolName, args = toolCall.function.arguments.take(200), result = toolResult.summary, durationMs = toolDurationMs, isError = toolResult.isError)
                }
            } else {
                ToolCallInfo(
                    toolName = toolName,
                    args = toolCall.function.arguments.take(200),
                    result = toolResult.summary,
                    durationMs = toolDurationMs,
                    isError = toolResult.isError
                )
            }

            onProgress(AgentProgress(
                step = "Used tool: $toolName",
                tokensUsed = contextManager.currentTokens,
                toolCallInfo = editInfo
            ))
            onDebugLog?.invoke(
                if (toolResult.isError) "warn" else "info", "tool_call",
                "$toolName ${if (toolResult.isError) "ERROR" else "OK"} (${toolDurationMs}ms)",
                mapOf("tool" to toolName, "duration" to toolDurationMs, "tokens" to toolResult.tokenEstimate)
            )
        }

        // Trace: record iteration completion with tool list
        val toolNames = toolCalls.map { it.function.name }
        sessionTrace?.iterationCompleted(iteration, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, toolNames, choice.finishReason)
        agentFileLogger?.logIteration(sessionId, iteration, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, choice.finishReason, toolNames, System.currentTimeMillis() - iterationStartMs)
        onDebugLog?.invoke("info", "iteration", "Iter $iteration: ${toolNames.size} tools, ${usage?.totalTokens ?: 0} tokens",
            mapOf("iteration" to iteration, "tokens" to (usage?.totalTokens ?: 0)))

        // LoopGuard: check for loops, error nudges, instruction-fade reminders
        val loopGuardMessages = loopGuard.afterIteration(toolCalls, toolResults, editedFiles.toList())
        for (msg in loopGuardMessages) {
            contextManager.addMessage(msg)
            if (msg.content?.contains("same arguments") == true) {
                eventLog?.log(AgentEventType.LOOP_DETECTED, msg.content ?: "")
            }
        }

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
        contextManager: ContextManager
    ) {
        val factsStore = contextManager.factsStore ?: return
        val pathRegex = Regex(""""path"\s*:\s*"([^"]+)"""")
        val filePath = pathRegex.find(toolArgs)?.groupValues?.get(1)
        when (toolName) {
            "read_file" -> if (filePath != null) {
                val lineCount = content.lines().size
                val firstLine = content.lineSequence().firstOrNull()?.take(80) ?: ""
                factsStore.record(Fact(FactType.FILE_READ, filePath, "$lineCount lines. Starts with: $firstLine", iteration))
            }
            "edit_file" -> if (filePath != null) {
                factsStore.record(Fact(FactType.EDIT_MADE, filePath, summary.take(200), iteration))
            }
            "search_code", "glob_files", "find_references", "find_definition" -> {
                factsStore.record(Fact(FactType.CODE_PATTERN, filePath, summary.take(200), iteration))
            }
            "run_command", "run_tests" -> {
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
        contextManager.updateFactsAnchor()
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

        // Check approval gate before executing risky tools
        if (approvalGate != null) {
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

        // Emit pre-execution progress
        onProgress?.invoke(AgentProgress(
            step = "Calling tool: $toolName",
            tokensUsed = 0, // Don't access contextManager from parallel context
            toolCallInfo = ToolCallInfo(
                toolName = toolName,
                args = toolCall.function.arguments.take(200),
                isError = false
            )
        ))

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
            if (toolName == "run_command") {
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
            // Use non-streaming to get complete, well-structured tool call arguments.
            // Sourcegraph's streaming SSE can produce empty/malformed tool call args
            // (tool name arrives but arguments are empty strings). Non-streaming
            // returns the full response in one shot with properly formed JSON.
            // Text content is pushed to UI after the response via onStreamChunk.
            val streamed = false
            val result = brain.chat(messages, toolDefs, maxOutputTokens)

            when (result) {
                is ApiResult.Success -> {
                    return LlmCallResult.Success(result.data, wasStreamed = streamed)
                }
                is ApiResult.Error -> {
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
                                delay(delayMs)
                            }
                            continue // retry with backoff
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

}
