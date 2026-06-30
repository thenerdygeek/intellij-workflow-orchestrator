// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.ide.IdeContext
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.prompt.IntegrationFlags
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.background.BackgroundEligibility
import com.workflow.orchestrator.agent.tools.builtin.ToolSearchTool
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.OpenAiCompatBrain
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Wraps [AgentLoop] with per-subagent stats tracking, progress callbacks, and cancellation.
 *
 * Ported from Cline's SubagentRunner.ts: Cline's version manages its own stream handler
 * and tool execution loop. We simplify by delegating the actual loop to [AgentLoop] but
 * wrapping it with:
 * - Per-subagent [MutableSubagentStats] accumulator
 * - Progress callback ([onProgress]) fired on status changes, tool calls, token updates
 * - Cancellation via [AtomicBoolean] + [LlmBrain.cancelActiveRequest]
 * - Context budget passed to [ContextManager]
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/subagent/SubagentRunner.ts">Cline source</a>
 */
class SubagentRunner(
    private val brain: LlmBrain,
    private val coreTools: Map<String, AgentTool>,
    private val deferredTools: Map<String, Pair<AgentTool, String>> = emptyMap(),
    private val systemPrompt: String,
    private val project: Project,
    private val maxIterations: Int,
    private val planMode: Boolean,
    private val contextBudget: Int,
    private val maxOutputTokens: Int? = null,
    private val apiDebugDir: File? = null,
    toolExecutionMode: String = "accumulate",
    /**
     * Optional approval gate forwarded from the parent session. When set, write-tool
     * executions inside the sub-agent suspend waiting for user approval, just like
     * they do for the main agent. Null = no approval (tests, read-only sub-agents).
     */
    private val approvalGate: (suspend (toolName: String, args: String, riskLevel: String, allowSessionApproval: Boolean) -> com.workflow.orchestrator.agent.loop.ApprovalResult)? = null,
    /**
     * Optional session-approval store shared with the parent session. When set, sub-agent
     * write tools honor "Allow for session" decisions the user has already made in this
     * conversation, and approvals granted inside the sub-agent propagate back to the
     * parent's later turns (shared reference, not a copy). Null = fresh empty store
     * (tests, disconnected sub-agents).
     */
    private val sessionApprovalStore: com.workflow.orchestrator.agent.loop.SessionApprovalStore? = null,
    /**
     * Optional session command-prefix allowlist shared with the parent session (Task 8).
     * When set, sub-agent run_commands honor "Approve all <prefix> this session" decisions
     * the user has already made — shared reference, not a copy, so prefixes approved inside
     * a sub-agent also propagate back to the parent's subsequent turns.
     * Null = fresh empty allowlist (tests, disconnected sub-agents).
     */
    private val sessionCommandAllowlist: com.workflow.orchestrator.agent.loop.SessionCommandAllowlist? = null,
    /**
     * Parent-session "auto-approve safe read-only commands" toggle (Task 8). When true,
     * the sub-agent's AgentLoop auto-approves commands that CommandSafetyAnalyzer classifies
     * as SAFE, mirroring the main agent's behavior. Null/false = no auto-approval.
     */
    private val autoApproveSafeCommands: Boolean = false,
    /**
     * Optional hook manager forwarded from the parent session. When set, PRE_TOOL_USE /
     * POST_TOOL_USE / etc. fire for sub-agent tool calls with the same semantics as the
     * main agent.
     */
    private val hookManager: com.workflow.orchestrator.agent.hooks.HookManager? = null,
    /**
     * Optional session metrics accumulator from the parent session. Sub-agent tool
     * durations / API latencies flow into the parent scorecard.
     */
    private val sessionMetrics: com.workflow.orchestrator.agent.observability.SessionMetrics? = null,
    /**
     * Optional file logger from the parent session. Sub-agent lifecycle events land in
     * the same JSONL stream as the parent's.
     */
    private val fileLogger: com.workflow.orchestrator.agent.observability.AgentFileLogger? = null,
    /**
     * Optional debug log callback forwarded from the parent session. Routes sub-agent
     * warnings/events to the JCEF debug panel.
     */
    private val onDebugLog: ((level: String, event: String, detail: String, meta: Map<String, Any?>?) -> Unit)? = null,
    /**
     * Test hook: called with the initial composed system prompt immediately after it is built.
     * Null in production. Allows tests to capture the composed prompt without running a full loop.
     */
    internal val onSystemPromptBuilt: ((String) -> Unit)? = null,
    /**
     * Optional IDE context forwarded from [com.workflow.orchestrator.agent.AgentService].
     * When non-null, passed into [SubagentSystemPromptBuilder.build] so the sub-agent's
     * system prompt adapts to the running IDE (PyCharm vs IntelliJ IDEA vs WebStorm).
     * Null produces IntelliJ-flavored defaults (backward compatible).
     */
    private val ideContext: IdeContext? = null,
    /**
     * Optional resolved [AgentConfig] for this sub-agent (forwarded from [SpawnAgentTool]).
     * When non-null, passed into [SubagentSystemPromptBuilder.build]. Accepted and threaded
     * through; Task 4 will consume agentConfig.promptSections when the YAML schema lands.
     */
    private val agentConfig: AgentConfig? = null,
    /**
     * Optional message-state handler scoped to the sub-agent's own session directory.
     * When set, every assistant chunk, tool result, and user message is persisted to
     * sessions/{parentId}/subagents/{agentId}/{api_conversation_history.json,ui_messages.json}.
     * Null = ephemeral run (default, tests, legacy callers).
     */
    private val messageStateHandler: com.workflow.orchestrator.agent.session.MessageStateHandler? = null,
    /**
     * Optional output spiller forwarded from the parent session. When set, sub-agent
     * tool outputs above the spill threshold are written to disk instead of inflating
     * the sub-agent's context window. Null = no spill (legacy behavior, tests).
     */
    private val outputSpiller: com.workflow.orchestrator.agent.tools.ToolOutputSpiller? = null,
    /**
     * Optional provider for the session-scoped [AttachmentStore]. Forwarded so
     * sub-agent tools that read user-pasted images (or tool-produced images) hit
     * the SAME store the parent's BrainRouter uses. Default returns null —
     * sub-agent runs without a parent session see no attachments.
     */
    private val attachmentStoreProvider: () -> com.workflow.orchestrator.agent.session.AttachmentStore? = { null },
    /**
     * Optional factory that produces a fresh LlmBrain for a given model ID. Used by
     * same-tier brain recycling and L2 tier escalation. Null = no recycling.
     */
    private val brainFactory: (suspend (modelId: String, reason: String?) -> com.workflow.orchestrator.core.ai.LlmBrain)? = null,
    /**
     * Optional fallback chain used by L2 tier escalation when same-tier recycles are
     * exhausted. Mirrors main agent's `cachedFallbackChain`.
     */
    private val cachedFallbackChain: List<String>? = null,
    /**
     * Optional callback when the loop switches models (fallback or escalation). Lets the
     * sub-agent card update its model badge.
     */
    private val onModelSwitch: ((fromModel: String, toModel: String, reason: String) -> Unit)? = null,
    /**
     * Optional model catalog service. When set with image-bearing turns, the loop filters
     * the fallback chain to vision-capable models. Mirrors main agent's `modelCatalogService`.
     */
    private val modelCatalogService: com.workflow.orchestrator.core.ai.ModelCatalogService? = null,
    /**
     * Phase 4a Task 11 (C1) — the sub-agent's tool-calling paradigm. Defaults to [XmlToolProtocol]
     * so every existing (non-Phase-4a) caller compiles unchanged. [SpawnAgentTool] passes the
     * provider-selected protocol so a sub-agent on the native Anthropic brain presents tools the same
     * way the orchestrator does: `presentTools` returns null and the §6c XML tool-doc block is OMITTED
     * (tools live only in the wire `tools:[]` field) — preventing the double-presentation dialect drift.
     */
    private val toolProtocol: com.workflow.orchestrator.core.ai.protocol.ToolProtocol =
        com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol(),
) {
    private val abortRequested = AtomicBoolean(false)

    /**
     * Job of the child [coroutineScope] that wraps the sub-agent's inner [AgentLoop.run]
     * (set in [runInternal]). Cancelling this job — done by [abort] — tears down the inner
     * loop AND its in-flight tool coroutine (the tool runs inside a `coroutineScope` that is
     * a descendant of this job, so cancellation propagates down to it) without cancelling
     * [runInternal]'s own coroutine, so the parent `agent` tool-call returns a normal
     * "sub-agent aborted" result and the orchestrator loop continues. Null when no loop is
     * currently running (before start / after the scope completes — cleared in a `finally`).
     */
    @Volatile
    private var abortableRunJob: Job? = null

    /**
     * Effective tool execution mode for this sub-agent. Always `"stream_interrupt"`
     * regardless of what the caller passed, because sub-agents must ReAct one
     * tool at a time. The constructor arg is retained for API compatibility but
     * intentionally ignored — sub-agents should not accumulate because batched
     * `attempt_completion` speculates past tool results.
     */
    @Suppress("unused")
    private val callerRequestedToolExecutionMode: String = toolExecutionMode
    internal val effectiveToolExecutionMode: String = "stream_interrupt"

    /**
     * Abort the subagent run. Sets the abort flag, cancels the brain's active request (SSE
     * stream), AND cancels the child scope that wraps the inner [AgentLoop.run] so the
     * sub-agent's IN-FLIGHT tool coroutine is cancelled too (not just the LLM stream) — a
     * mid-`run_command` tool's coroutine is cancelled, which (combined with the tool's own
     * `finally` cleanup) kills the grandchild OS process instead of letting it run until the
     * next iteration boundary. Order matters: [abortRequested] is set BEFORE the cancel so
     * that when the resulting [CancellationException] reaches [runInternal]'s
     * `catch (CancellationException)` block the flag is already `true` → it returns the
     * cancelled result rather than re-throwing. Cancelling the CHILD job leaves
     * [runInternal]'s own coroutine alive, so the parent `agent` tool-call returns normally
     * and the orchestrator loop continues. Safe to call from any thread (`Job.cancel` and
     * `AtomicBoolean.set` are thread-safe; `abort()` fires from the EDT/JCEF bridge).
     */
    fun abort() {
        abortRequested.set(true)
        brain.cancelActiveRequest()
        abortableRunJob?.cancel(CancellationException("Sub-agent aborted by user"))
    }

    /**
     * Run the subagent loop with the given prompt.
     *
     * Creates a fresh [ContextManager] with the configured [contextBudget],
     * sets the system prompt, creates an [AgentLoop], and runs it. Progress
     * updates are emitted via [onProgress] for tool calls, token updates,
     * and status changes.
     *
     * @param prompt the task prompt for the subagent
     * @param agentId stable identifier for this sub-agent instance (used by the approval gate
     *   and UI to attribute requests to the correct sub-agent card)
     * @param label human-readable label shown in the approval modal, e.g. "Find auth flow (explorer)"
     * @param onProgress callback for incremental progress updates
     * @return the final result of the subagent run
     */
    suspend fun run(
        prompt: String,
        agentId: String,
        label: String,
        onProgress: suspend (SubagentProgressUpdate) -> Unit
    ): SubagentRunResult {
        // Capture the pre-wrapper context so that runInternal's free-floating
        // CoroutineScope(callerContext) inherits the SAME job as the caller rather than the
        // withSubagentOrigin child job. Without this, withContext's child job waits for
        // scope.launch children to complete, altering callback ordering relative to direct
        // onProgress calls (and breaking tests using UnconfinedTestDispatcher).
        //
        // We also merge SubagentOriginContext into the context passed to runInternal so that
        // scope.launch children — and any tools they invoke — can read the sub-agent origin via
        // coroutineContext[SubagentOriginContext.Key]. Without the merge, callerContext would be
        // missing the origin element (it was captured before withSubagentOrigin installed it),
        // and any future origin-aware progress callback would see a silent null. See P3 review notes.
        val callerContext = coroutineContext + SubagentOriginContext(agentId, label)
        return withSubagentOrigin(agentId, label) {
            runInternal(prompt, onProgress, callerContext)
        }
    }

    private suspend fun runInternal(
        prompt: String,
        onProgress: suspend (SubagentProgressUpdate) -> Unit,
        callerContext: kotlin.coroutines.CoroutineContext,
    ): SubagentRunResult {
        val stats = MutableSubagentStats()

        // Per-run stream pipeline (mirrors AgentController main-agent path).
        // Splitter strips <thinking>...</thinking>; batcher coalesces text to ~16ms
        // frames so the JCEF bridge doesn't fire per SSE byte.
        val thinkingSplitter = com.workflow.orchestrator.agent.ui.ThinkingTagSplitter()
        var textBatcher: com.workflow.orchestrator.agent.ui.StreamBatcher? = null

        try {
            // 0. Wire API debug dumps on the brain (separate subdir from main agent).
            //    Detach any parent-session shared counter so this sub-agent's dumps
            //    number from 001 inside its own debug dir — otherwise the shared
            //    counter would interleave numbering between parent and sub-agent.
            if (brain is OpenAiCompatBrain) {
                brain.detachSharedApiCallCounter()
                if (apiDebugDir != null) {
                    brain.setApiDebugDir(apiDebugDir)
                    brain.resetApiCallCounter()
                }
            }

            // 1. Build per-sub-agent ToolRegistry
            val subagentRegistry = ToolRegistry()

            // Register core tools (schemas in system prompt from turn 1)
            coreTools.forEach { (_, tool) -> subagentRegistry.registerCore(tool) }

            // Register deferred tools (names in catalog, schemas loaded via tool_search)
            deferredTools.forEach { (_, pair) ->
                val (tool, category) = pair
                subagentRegistry.registerDeferred(tool, category)
            }

            // Always inject a fresh ToolSearchTool backed by THIS sub-agent's registry.
            subagentRegistry.registerCore(ToolSearchTool(subagentRegistry))

            // 2. Build initial composed system prompt: body + core schemas + deferred catalog
            val initialPrompt = buildComposedSystemPrompt(subagentRegistry)
            onSystemPromptBuilt?.invoke(initialPrompt)

            // 3. Scope the brain's XML parser to ALL tools (core + deferred)
            brain.toolNameSet = subagentRegistry.allToolNames()
            brain.paramNameSet = BackgroundEligibility.withReservedParams(subagentRegistry.allParamNames())

            // 4. Create fresh context manager with budget
            val contextManager = ContextManager(maxInputTokens = contextBudget)
            contextManager.setSystemPrompt(initialPrompt)
            contextManager.setToolDefinitionTokens(
                TokenEstimator.estimateToolDefinitions(subagentRegistry.getActiveDefinitions())
            )

            // 5. Report initial "running" status
            onProgress(SubagentProgressUpdate(status = SubagentExecutionStatus.RUNNING, stats = stats.snapshot()))

            // 6. Check abort before proceeding
            if (abortRequested.get()) {
                return cancelledResult(stats)
            }

            // 7. Create AgentLoop with callbacks
            // Capture coroutine scope to bridge non-suspend AgentLoop callbacks
            // to suspend onProgress. Port of Cline's per-tool-call progress reporting.
            // scope is parented at the caller's Job (fire-and-forget child semantics) and
            // carries SubagentOriginContext so any progress-callback child can read the
            // sub-agent origin via coroutineContext[SubagentOriginContext.Key].
            val scope = CoroutineScope(callerContext)

            // Allocate per-run stream pipeline after scope is ready (onFlush launches
            // coroutines into it). StreamBatcher coalesces text at 16ms frames;
            // ThinkingTagSplitter instance (thinkingSplitter) is declared above.
            textBatcher = com.workflow.orchestrator.agent.ui.StreamBatcher(
                onFlush = { batched ->
                    scope.launch {
                        onProgress(SubagentProgressUpdate(streamDelta = batched, stats = stats.snapshot()))
                    }
                }
            )

            // networkProbe / llmProbeUrl are intentionally omitted here: sub-agents do NOT get
            // offline fail-fast. The parent orchestrator already fails fast with FailureReason.OFFLINE,
            // and sub-agents are iteration-bounded, so a dead-tunnel retry burn is short-lived. Do not
            // "fix" by wiring the probe in — that would surface offline failures at the wrong (sub-agent) layer.
            val loop = AgentLoop(
                brain = brain,
                tools = subagentRegistry.getActiveTools(),
                toolDefinitions = subagentRegistry.getActiveDefinitions(),
                contextManager = contextManager,
                project = project,
                toolProtocol = toolProtocol,
                maxIterations = maxIterations,
                maxOutputTokens = maxOutputTokens,
                planMode = planMode,
                toolDefinitionProvider = { subagentRegistry.getActiveDefinitions() },
                toolResolver = { name -> subagentRegistry.get(name) },
                systemPromptProvider = { buildComposedSystemPrompt(subagentRegistry) },
                toolNameProvider = { subagentRegistry.allToolNames() },
                paramNameProvider = { BackgroundEligibility.withReservedParams(subagentRegistry.allParamNames()) },
                onToolCall = { progress ->
                    // AgentLoop fires onToolCall twice: once at tool start (empty result, durationMs=0)
                    // and once at tool completion (populated result, durationMs>0). We must propagate
                    // both so the UI can transition the sub-agent's tool chip from RUNNING to COMPLETED.
                    val isStarting = progress.result.isEmpty() && progress.durationMs == 0L
                    if (isStarting) {
                        stats.toolCalls++
                        val preview = formatToolCallPreview(progress.toolName, progress.args)
                        stats.latestToolCall = preview
                        scope.launch {
                            onProgress(SubagentProgressUpdate(
                                latestToolCall = preview,
                                toolStartName = progress.toolName,
                                toolStartArgs = progress.args,
                                toolCallId = progress.toolCallId,
                                stats = stats.snapshot()
                            ))
                        }
                    } else {
                        scope.launch {
                            onProgress(SubagentProgressUpdate(
                                toolCompleteName = progress.toolName,
                                toolCompleteResult = progress.result,
                                toolCompleteOutput = progress.output,
                                toolCompleteDiff = progress.editDiff,
                                toolCompleteDurationMs = progress.durationMs,
                                toolCompleteIsError = progress.isError,
                                // Multimodal-agent Phase 6 — propagate tool-produced
                                // image metadata so the sub-agent UI mirrors the
                                // main agent's badge behaviour.
                                toolCompleteImageRefs = progress.imageRefs,
                                toolCallId = progress.toolCallId,
                                stats = stats.snapshot()
                            ))
                        }
                    }
                },
                onTokenUpdate = { inputTokens, outputTokens ->
                    stats.inputTokens = inputTokens
                    stats.outputTokens = outputTokens
                    stats.contextUsagePercentage = contextManager.utilizationPercent()
                    stats.contextTokens = contextManager.tokenEstimate()
                    stats.contextWindow = contextBudget
                    // Fire progress with updated stats (Cline: onProgress({ stats: { ...stats } }))
                    scope.launch {
                        onProgress(SubagentProgressUpdate(stats = stats.snapshot()))
                    }
                },
                toolExecutionMode = effectiveToolExecutionMode,
                approvalGate = approvalGate,
                sessionApprovalStore = sessionApprovalStore ?: com.workflow.orchestrator.agent.loop.SessionApprovalStore(),
                sessionCommandAllowlist = sessionCommandAllowlist ?: com.workflow.orchestrator.agent.loop.SessionCommandAllowlist(),
                autoApproveSafeCommands = autoApproveSafeCommands,
                hookManager = hookManager,
                sessionMetrics = sessionMetrics,
                fileLogger = fileLogger,
                onDebugLog = onDebugLog,
                onStreamChunk = { chunk ->
                    // Route through ThinkingTagSplitter so <thinking>...</thinking> blocks
                    // are separated from prose. Text parts are coalesced by textBatcher;
                    // ThinkingDelta and ThinkingEnd are emitted immediately (live render).
                    for (part in thinkingSplitter.consume(chunk)) {
                        when (part) {
                            is com.workflow.orchestrator.agent.ui.ThinkingTagSplitter.Part.Text ->
                                textBatcher?.append(part.text)
                            is com.workflow.orchestrator.agent.ui.ThinkingTagSplitter.Part.ThinkingDelta ->
                                scope.launch {
                                    onProgress(SubagentProgressUpdate(thinkingDelta = part.text, stats = stats.snapshot()))
                                }
                            com.workflow.orchestrator.agent.ui.ThinkingTagSplitter.Part.ThinkingEnd ->
                                scope.launch {
                                    onProgress(SubagentProgressUpdate(thinkingEnd = true, stats = stats.snapshot()))
                                }
                        }
                    }
                },
                messageStateHandler = messageStateHandler,
                outputSpiller = outputSpiller,
                attachmentStoreProvider = attachmentStoreProvider,
                // Route the sub-agent loop's compaction/retry status to the SUB-AGENT CARD
                // (via onProgress), NOT the orchestrator's main chat. Forwarding the main-chat
                // callbacks here was the leak that painted subagent retries/compactions in the
                // main chat. statusNoteSet=true marks the note as authoritative (null = clear).
                onCompactionState = { active, phase ->
                    val note = if (active) {
                        if (phase.isNotBlank()) "Compacting context… ($phase)" else "Compacting context…"
                    } else {
                        null
                    }
                    scope.launch {
                        onProgress(
                            SubagentProgressUpdate(
                                statusNote = note,
                                statusNoteSet = true,
                                stats = stats.snapshot(),
                            ),
                        )
                    }
                },
                brainFactory = brainFactory,
                cachedFallbackChain = cachedFallbackChain,
                onRetry = { attempt, maxAttempts, reason, delayMs ->
                    val secs = (delayMs / 1000).coerceAtLeast(1)
                    scope.launch {
                        onProgress(
                            SubagentProgressUpdate(
                                statusNote = "$reason — retrying ($attempt/$maxAttempts) in ${secs}s…",
                                statusNoteSet = true,
                                stats = stats.snapshot(),
                            ),
                        )
                    }
                },
                onModelSwitch = onModelSwitch,
                modelCatalogService = modelCatalogService,
            )

            // 8. Run the loop (inner try/finally ensures the StreamBatcher is flushed
            // and disposed regardless of normal completion, abort, or exception so
            // any tail prose bytes reach the UI before the final status event).
            try {
                // Wrap loop.run in its OWN child coroutineScope and expose that scope's Job
                // via abortableRunJob, mirroring the main funnel's "cancel a CHILD scope,
                // parent survives" pattern (AgentLoop.executeToolCalls). abort() cancels this
                // job → the inner loop AND its in-flight tool coroutine (which runs inside a
                // coroutineScope that is a descendant of this job) are cancelled, but
                // runInternal's own coroutine is NOT, so this function still returns the
                // cancelled SubagentRunResult normally and the parent agent tool-call (and the
                // orchestrator loop) continue. The enclosing catch (CancellationException)
                // below still wraps this — when abortRequested is true it returns the cancelled
                // result; a genuine structured-concurrency teardown (flag false) re-throws.
                // Cleared in finally so a stale job can never be cancelled across runs.
                val loopResult = try {
                    coroutineScope {
                        abortableRunJob = coroutineContext[Job]
                        loop.run(prompt)
                    }
                } finally {
                    abortableRunJob = null
                }

                // 9. Check abort after loop finishes
                if (abortRequested.get()) {
                    return cancelledResult(stats)
                }

                // 10. Map LoopResult to SubagentRunResult
                val result = when (loopResult) {
                    is LoopResult.Completed -> {
                        stats.inputTokens = loopResult.inputTokens
                        stats.outputTokens = loopResult.outputTokens
                        SubagentRunResult(
                            status = SubagentRunStatus.COMPLETED,
                            result = loopResult.summary,
                            stats = stats.snapshot()
                        )
                    }
                    is LoopResult.Failed -> {
                        stats.inputTokens = loopResult.inputTokens
                        stats.outputTokens = loopResult.outputTokens
                        SubagentRunResult(
                            status = SubagentRunStatus.FAILED,
                            error = loopResult.error,
                            stats = stats.snapshot()
                        )
                    }
                    is LoopResult.Cancelled -> {
                        stats.inputTokens = loopResult.inputTokens
                        stats.outputTokens = loopResult.outputTokens
                        SubagentRunResult(
                            status = SubagentRunStatus.FAILED,
                            error = "Subagent cancelled",
                            stats = stats.snapshot()
                        )
                    }
                    is LoopResult.SessionHandoff -> {
                        stats.inputTokens = loopResult.inputTokens
                        stats.outputTokens = loopResult.outputTokens
                        SubagentRunResult(
                            status = SubagentRunStatus.COMPLETED,
                            result = loopResult.context,
                            stats = stats.snapshot()
                        )
                    }
                }

                // Flush batcher so tail prose reaches the UI before the final event.
                textBatcher?.flush()

                // 11. Report final status
                val finalStatus = if (result.status == SubagentRunStatus.COMPLETED) SubagentExecutionStatus.COMPLETED else SubagentExecutionStatus.FAILED
                onProgress(
                    SubagentProgressUpdate(
                        status = finalStatus,
                        stats = result.stats,
                        result = result.result,
                        error = result.error
                    )
                )

                return result
            } finally {
                textBatcher?.flush()
                textBatcher?.dispose()
            }
        } catch (e: CancellationException) {
            // Never swallow a genuine cancellation. When abortRequested is FALSE, this is a
            // structured-concurrency teardown (parent scope cancelled) and must re-throw so
            // callers unwind correctly. When abortRequested is TRUE, a user-driven abort set
            // the flag and also cancelled the brain's active request, which caused this
            // CancellationException — return the cancelled result here in THIS block rather
            // than re-throwing. Non-cancellation failures are handled in the separate
            // catch (e: Exception) block below.
            // No textBatcher?.flush() here — deliberate. An aborted sub-agent's
            // batched tail prose is intentionally dropped; flushing would deliver
            // a partial/misleading trailing chunk to the UI after the abort card.
            textBatcher?.dispose()
            if (!abortRequested.get()) throw e
            // abortRequested is true: fall through to return the cancelled result below.
            return cancelledResult(stats).also { cancelResult ->
                onProgress(
                    SubagentProgressUpdate(
                        status = SubagentExecutionStatus.FAILED,
                        stats = cancelResult.stats,
                        error = cancelResult.error
                    )
                )
            }
        } catch (e: Exception) {
            // Defense in depth: if an exception fires AFTER textBatcher allocation but
            // BEFORE the inner try { loop.run() } / finally { dispose() }, the inner
            // finally never runs and the batcher's javax.swing.Timer would leak.
            // Idempotent: dispose() is safe to call twice (the batcher's `disposed`
            // AtomicBoolean prevents double-cleanup).
            textBatcher?.dispose()
            // If aborted, return cancelled result
            if (abortRequested.get()) {
                return cancelledResult(stats).also { cancelResult ->
                    onProgress(
                        SubagentProgressUpdate(
                            status = SubagentExecutionStatus.FAILED,
                            stats = cancelResult.stats,
                            error = cancelResult.error
                        )
                    )
                }
            }

            // Otherwise return failed result
            val errorMsg = e.message ?: "Unknown error"
            LOG.warn("[SubagentRunner] Failed: $errorMsg", e)
            val failedResult = SubagentRunResult(
                status = SubagentRunStatus.FAILED,
                error = errorMsg,
                stats = stats.snapshot()
            )
            onProgress(
                SubagentProgressUpdate(
                    status = SubagentExecutionStatus.FAILED,
                    stats = failedResult.stats,
                    error = errorMsg
                )
            )
            return failedResult
        }
    }

    /**
     * Composes the sub-agent system prompt by delegating to [SubagentSystemPromptBuilder],
     * which calls the shared [com.workflow.orchestrator.agent.prompt.SystemPrompt.build]
     * with sub-agent-scoped opt-in flags. Called both for the initial prompt (step 2 in
     * [run]) and for dynamic rebuilds wired into [AgentLoop.systemPromptProvider].
     * IDE-aware sections (role, capabilities, rules, system info) automatically adapt
     * to the runtime IDE via [ideContext].
     */
    private fun buildComposedSystemPrompt(registry: ToolRegistry): String =
        buildUnifiedSystemPrompt(registry)

    private fun buildUnifiedSystemPrompt(registry: ToolRegistry): String {
        val coreDefinitions = registry.getActiveDefinitions()
        val toolDefinitionsMarkdown = toolProtocol.presentTools(coreDefinitions)
        val deferredToolCatalog = registry.getDeferredCatalogGroupedWithDescriptions()
            .takeIf { it.isNotEmpty() }

        // Consume the one-shot dialect-drift flag here — buildUnifiedSystemPrompt is wired as
        // AgentLoop.systemPromptProvider (called before each LLM call), so this mirrors the
        // orchestrator's AgentService.systemPromptBuilder consuming it per turn. When the
        // sub-agent's previous assistant turn used an incompatible tool-call dialect,
        // MessageStateHandler.addToApiConversationHistory raised the flag; consuming it here
        // injects the corrective <system-reminder> on the immediately-next call and resets.
        // The initial buildComposedSystemPrompt() call also lands here, but the flag is false
        // at session start, so no reminder is emitted then (same as the orchestrator).
        // WA-1 structural no-op: consumeDialectDriftFlag() is the chokepoint — under NativeProtocol
        // it short-circuits to false without getAndSet, so no explicit guard needed here.
        val dialectDriftDetected = messageStateHandler?.consumeDialectDriftFlag() ?: false

        // Gate integrations on the sub-agent's OWN registry (persona tool allowlist), not on
        // ConnectionSettings. A persona without the jira tool must not get jira prose for a tool
        // it can't call — even in a jira-configured install. Integration tools are only in any
        // registry when their URL is configured, so registry-derived is never broader than
        // ConnectionSettings-derived. Mirrors the sibling hasWebTools resolution below.
        val integrations = IntegrationFlags(
            jira = registry.has("jira"),
            bamboo = registry.has("bamboo_builds") || registry.has("bamboo_plans"),
            sonar = registry.has("sonar"),
            bitbucket = registry.has("bitbucket_pr") || registry.has("bitbucket_repo") ||
                registry.has("bitbucket_review"),
        )

        return SubagentSystemPromptBuilder.build(
            personaRole = systemPrompt,
            agentConfig = agentConfig,
            ideContext = ideContext,
            projectName = project.name,
            projectPath = project.basePath ?: "",
            toolDefinitionsMarkdown = toolDefinitionsMarkdown,
            deferredToolCatalog = deferredToolCatalog,
            toolNames = registry.allToolNames(),
            // Sub-agents inherit the orchestrator's registry; if web tools are unregistered
            // there, they are also absent from the sub-agent's tool set.
            hasWebTools = registry.has("web_fetch") || registry.has("web_search"),
            integrations = integrations,
            completingYourTaskSection = COMPLETING_YOUR_TASK_SECTION,
            dialectDriftDetected = dialectDriftDetected,
        )
    }

    private fun cancelledResult(stats: MutableSubagentStats): SubagentRunResult =
        SubagentRunResult(
            status = SubagentRunStatus.FAILED,
            error = "Subagent cancelled",
            stats = stats.snapshot()
        )

    companion object {
        private val LOG = Logger.getInstance(SubagentRunner::class.java)
        private const val TOOL_CALL_PREVIEW_MAX_LENGTH = 80

        /**
         * Injected at the end of every sub-agent's composed system prompt so that all
         * current and future personas know to use [TaskReportTool] (not `attempt_completion`).
         * Option B from the design: one injection point beats editing every persona file.
         */
        internal const val COMPLETING_YOUR_TASK_SECTION = """COMPLETING YOUR TASK

When you have completed your task, call `task_report` with:
- summary: one paragraph — what was done, the overall conclusion, and whether the task succeeded
- findings: detailed analysis (markdown OK, inline code snippets with file:line welcome)
- files: newline-separated paths you examined or modified
- next_steps: what the parent agent should do next (concrete and actionable)
- issues: blockers or unresolved questions (omit if none)

Your conversation, tool calls, and streamed text are NOT visible to the parent agent.
Only the task_report fields are. Be comprehensive, not terse."""

        /**
         * Format a tool call for display in progress updates.
         * Truncates arguments to [TOOL_CALL_PREVIEW_MAX_LENGTH] characters.
         */
        fun formatToolCallPreview(toolName: String, args: String): String {
            val truncatedArgs = if (args.length > TOOL_CALL_PREVIEW_MAX_LENGTH) {
                args.take(TOOL_CALL_PREVIEW_MAX_LENGTH) + "..."
            } else {
                args
            }
            return "$toolName($truncatedArgs)"
        }
    }

    /**
     * Mutable stats accumulator for tracking subagent execution metrics.
     * Thread-safe access is not needed since stats are only updated from the
     * coroutine running the agent loop (single writer).
     */
    internal class MutableSubagentStats {
        var toolCalls: Int = 0
        var inputTokens: Int = 0
        var outputTokens: Int = 0
        var cacheWriteTokens: Int = 0
        var cacheReadTokens: Int = 0
        var totalCost: Double = 0.0
        var contextTokens: Int = 0
        var contextWindow: Int = 0
        var contextUsagePercentage: Double = 0.0
        var latestToolCall: String? = null

        /** Create an immutable snapshot of the current stats. */
        fun snapshot(): SubagentRunStats = SubagentRunStats(
            toolCalls = toolCalls,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheWriteTokens = cacheWriteTokens,
            cacheReadTokens = cacheReadTokens,
            totalCost = totalCost,
            contextTokens = contextTokens,
            contextWindow = contextWindow,
            contextUsagePercentage = contextUsagePercentage
        )
    }
}
