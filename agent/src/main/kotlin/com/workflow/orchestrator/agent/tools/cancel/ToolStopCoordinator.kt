package com.workflow.orchestrator.agent.tools.cancel

import com.workflow.orchestrator.agent.tools.process.ProcessRegistry

/**
 * Single entry point for "stop this tool call", invoked from the UI Stop button via
 * AgentController. Stop fires BOTH layers, in order:
 *   1. Hard-kill any process registered for this toolCallId (run_command /
 *      background_process) — terminates the OS process tree.
 *   2. Cancel the per-call coroutine job — the cooperative cancel that actually unwinds
 *      the tool's suspend body (and, for run_command, breaks its monitor loop out of
 *      `delay(500)` within ~500ms instead of waiting for the command's natural runtime).
 *
 * Both must fire (BUG-STOP-1 B1). The previous `if (killProcess(id)) return true`
 * short-circuit killed the OS process but never cancelled the coroutine, so a streaming
 * run_command's monitor loop kept spinning (it only exits on process death or the 300s
 * in-tool cap) — Stop appeared to do nothing for ~2 min. Cancelling the registered job
 * throws `UserStopCancellationException` into the loop, so the funnel in AgentLoop
 * returns a "[Stopped by user]" result and the agent loop continues immediately.
 *
 * Sub-agents are intentionally NOT handled here: the `agent` tool's Stop button is
 * suppressed in the webview, and sub-agents are stopped via the separate agentId-keyed
 * path (SpawnAgentTool.cancelAgent). See design spec §6.
 *
 * The two lambdas are seams defaulting to the real registries; production callers use
 * the single-arg form.
 *
 * @return true iff a process was killed OR a coroutine job was cancelled.
 */
object ToolStopCoordinator {
    fun requestStop(
        toolCallId: String,
        killProcess: (String) -> Boolean = ProcessRegistry::kill,
        cancelCoroutine: (String) -> Boolean = ToolCancellationRegistry::cancel,
    ): Boolean {
        val killed = killProcess(toolCallId)
        val cancelled = cancelCoroutine(toolCallId)
        return killed || cancelled
    }
}
