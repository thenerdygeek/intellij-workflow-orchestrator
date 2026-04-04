// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.core.ai.LlmBrain
import java.util.concurrent.atomic.AtomicBoolean

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
    private val tools: Map<String, AgentTool>,
    private val systemPrompt: String,
    private val project: Project,
    private val maxIterations: Int,
    private val planMode: Boolean,
    private val contextBudget: Int
) {
    private val abortRequested = AtomicBoolean(false)

    /**
     * Abort the subagent run. Sets the abort flag and cancels the brain's active request.
     * Safe to call from any thread.
     */
    fun abort() {
        abortRequested.set(true)
        brain.cancelActiveRequest()
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
     * @param onProgress callback for incremental progress updates
     * @return the final result of the subagent run
     */
    suspend fun run(
        prompt: String,
        onProgress: suspend (SubagentProgressUpdate) -> Unit
    ): SubagentRunResult {
        val stats = MutableSubagentStats()

        try {
            // 1. Create fresh context manager with budget
            val contextManager = ContextManager(maxInputTokens = contextBudget)
            contextManager.setSystemPrompt(systemPrompt)

            // 2. Report initial "running" status
            onProgress(SubagentProgressUpdate(status = "running", stats = stats.snapshot()))

            // 3. Check abort before proceeding
            if (abortRequested.get()) {
                return cancelledResult(stats)
            }

            // 4. Build tool definitions from the tools map
            val toolDefinitions = tools.values.map { it.toToolDefinition() }

            // 5. Create AgentLoop with callbacks
            val loop = AgentLoop(
                brain = brain,
                tools = tools,
                toolDefinitions = toolDefinitions,
                contextManager = contextManager,
                project = project,
                maxIterations = maxIterations,
                planMode = planMode,
                onToolCall = { progress ->
                    stats.toolCalls++
                    val preview = formatToolCallPreview(progress.toolName, progress.args)
                    // Fire-and-forget would need a scope; instead we track for the next onProgress
                    stats.latestToolCall = preview
                },
                onTokenUpdate = { inputTokens, outputTokens ->
                    stats.inputTokens = inputTokens
                    stats.outputTokens = outputTokens
                    // Compute context usage from the context manager
                    stats.contextUsagePercentage = contextManager.utilizationPercent()
                    stats.contextTokens = contextManager.tokenEstimate()
                    stats.contextWindow = contextBudget
                }
            )

            // 6. Run the loop
            val loopResult = loop.run(prompt)

            // 7. Check abort after loop finishes
            if (abortRequested.get()) {
                return cancelledResult(stats)
            }

            // 8. Map LoopResult to SubagentRunResult
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

            // 9. Report final status
            val finalStatus = if (result.status == SubagentRunStatus.COMPLETED) "completed" else "failed"
            onProgress(
                SubagentProgressUpdate(
                    status = finalStatus,
                    stats = result.stats,
                    result = result.result,
                    error = result.error
                )
            )

            return result
        } catch (e: Exception) {
            // If aborted, return cancelled result
            if (abortRequested.get()) {
                return cancelledResult(stats).also { cancelResult ->
                    onProgress(
                        SubagentProgressUpdate(
                            status = "failed",
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
                    status = "failed",
                    stats = failedResult.stats,
                    error = errorMsg
                )
            )
            return failedResult
        }
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
