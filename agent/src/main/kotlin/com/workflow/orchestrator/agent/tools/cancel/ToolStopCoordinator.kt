package com.workflow.orchestrator.agent.tools.cancel

import com.workflow.orchestrator.agent.tools.process.ProcessRegistry

/**
 * Single entry point for "stop this tool call", invoked from the UI Stop button via
 * AgentController. Two-layer precedence:
 *   1. If a process is registered for this toolCallId (run_command / background_process),
 *      hard-kill it — preserves the tool's existing partial-output behavior.
 *   2. Otherwise cancel the per-call coroutine job (cooperative cancel for all other tools).
 *
 * Sub-agents are intentionally NOT handled here: the `agent` tool's Stop button is
 * suppressed in the webview, and sub-agents are stopped via the separate agentId-keyed
 * path (SpawnAgentTool.cancelAgent). See design spec §6.
 *
 * The two lambdas are seams defaulting to the real registries; production callers use
 * the single-arg form.
 */
object ToolStopCoordinator {
    fun requestStop(
        toolCallId: String,
        killProcess: (String) -> Boolean = ProcessRegistry::kill,
        cancelCoroutine: (String) -> Boolean = ToolCancellationRegistry::cancel,
    ): Boolean {
        if (killProcess(toolCallId)) return true
        return cancelCoroutine(toolCallId)
    }
}
