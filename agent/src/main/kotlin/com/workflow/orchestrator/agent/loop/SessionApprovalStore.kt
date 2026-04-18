package com.workflow.orchestrator.agent.loop

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which tools the user has approved for the current agent session.
 * Lives at the AgentController level (not per-loop-run) so approvals persist
 * across follow-up messages within the same session.
 *
 * Thread-safe via ConcurrentHashMap.
 */
class SessionApprovalStore {
    private val approved = ConcurrentHashMap.newKeySet<String>()

    fun approve(toolName: String) { approved.add(toolName) }
    fun isApproved(toolName: String): Boolean = toolName in approved
    fun clear() { approved.clear() }
    fun approvedTools(): Set<String> = approved.toSet()
}
