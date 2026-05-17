// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element identifying the sub-agent that owns the current scope.
 * When present, the approval gate (and any other UI surface) can attribute the
 * request to the sub-agent rather than the main session.
 *
 * Mental model: like a thread-local that the parent reads to know "this approval
 * is bubbling up from a sub-agent". Stays attached for the lifetime of one
 * SubagentRunner.run() call.
 */
data class SubagentOriginContext(
    val agentId: String,
    val label: String,
) : AbstractCoroutineContextElement(SubagentOriginContext) {
    companion object Key : CoroutineContext.Key<SubagentOriginContext>
}

/**
 * Helper that runs [block] with the given origin context installed.
 *
 * Use at the boundary where a sub-agent run begins (typically inside
 * [SubagentRunner.run]). Inside the block, callers can read
 * `coroutineContext[SubagentOriginContext.Key]` to retrieve the origin.
 */
suspend inline fun <T> withSubagentOrigin(
    agentId: String,
    label: String,
    crossinline block: suspend CoroutineScope.() -> T,
): T = withContext(SubagentOriginContext(agentId, label)) { block() }
