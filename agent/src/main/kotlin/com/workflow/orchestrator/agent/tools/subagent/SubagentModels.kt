// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import kotlinx.serialization.Serializable

/**
 * Terminal status of a subagent run.
 * Ported from Cline's SubagentRunner.ts result handling.
 */
enum class SubagentRunStatus { COMPLETED, FAILED }

/**
 * Execution lifecycle status for tracking in-progress subagents.
 * Ported from Cline's ExtensionMessage.ts subagent status tracking.
 */
enum class SubagentExecutionStatus { PENDING, RUNNING, COMPLETED, FAILED }

/**
 * Aggregate statistics for a single subagent run.
 * Tracks token usage, cost, and context window utilization.
 */
data class SubagentRunStats(
    val toolCalls: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val totalCost: Double = 0.0,
    val contextTokens: Int = 0,
    val contextWindow: Int = 0,
    val contextUsagePercentage: Double = 0.0,
)

/**
 * Final result of a subagent run, returned when the subagent terminates.
 * On success [result] contains the subagent's output; on failure [error] describes what went wrong.
 */
data class SubagentRunResult(
    val status: SubagentRunStatus,
    val result: String? = null,
    val error: String? = null,
    val stats: SubagentRunStats = SubagentRunStats(),
)

/**
 * Incremental progress update emitted by a running subagent.
 * All fields are optional since any subset may be updated on each tick.
 */
data class SubagentProgressUpdate(
    val stats: SubagentRunStats? = null,
    val latestToolCall: String? = null,
    val status: String? = null,
    val result: String? = null,
    val error: String? = null,
)

/**
 * Status of a single subagent within a parallel group, serializable for the JCEF chat UI.
 * Ported from Cline's ExtensionMessage.ts SubAgentStatus item shape.
 */
@Serializable
data class SubagentStatusItem(
    val index: Int,
    val prompt: String,
    // @Volatile for thread safety: fields mutated concurrently from parallel coroutines.
    // Cline's JS is single-threaded; on JVM we need visibility guarantees.
    @Volatile var status: String = "pending",
    @Volatile var toolCalls: Int = 0,
    @Volatile var inputTokens: Int = 0,
    @Volatile var outputTokens: Int = 0,
    @Volatile var totalCost: Double = 0.0,
    @Volatile var contextTokens: Int = 0,
    @Volatile var contextWindow: Int = 0,
    @Volatile var contextUsagePercentage: Double = 0.0,
    @Volatile var latestToolCall: String? = null,
    @Volatile var result: String? = null,
    @Volatile var error: String? = null,
)

/**
 * Aggregate status of a parallel subagent group, serializable for the JCEF chat UI.
 * Ported from Cline's ExtensionMessage.ts SubAgentStatus shape.
 */
@Serializable
data class SubagentGroupStatus(
    val status: String,
    val total: Int,
    val completed: Int,
    val successes: Int,
    val failures: Int,
    val toolCalls: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val contextWindow: Int,
    val maxContextTokens: Int,
    val maxContextUsagePercentage: Double,
    val items: List<SubagentStatusItem>,
)

/**
 * Token usage info for subagent runs, used for cost tracking and billing aggregation.
 */
@Serializable
data class SubagentUsageInfo(
    val source: String = "subagents",
    val tokensIn: Int,
    val tokensOut: Int,
    val cacheWrites: Int = 0,
    val cacheReads: Int = 0,
    val cost: Double = 0.0,
)
