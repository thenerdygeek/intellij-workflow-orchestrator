package com.workflow.orchestrator.agent.runtime

/**
 * Abstraction for UI interactions that tools and the session layer need.
 *
 * Registered on [com.workflow.orchestrator.agent.AgentService.uiCallbacks]
 * (project-scoped) so each project window gets its own callback instance.
 * Tools look up callbacks via `AgentService.getInstance(project).uiCallbacks`
 * instead of static companion fields — this prevents multi-project routing bugs (C7).
 */
interface UiCallbacks {
    /** Show a process input prompt in the chat panel (for ask_user_input tool). */
    fun showProcessInput(processId: String, description: String, prompt: String, command: String)

    /** Stream incremental output from a running command to the chat panel. */
    fun streamCommandOutput(toolCallId: String, chunk: String)

    /** Notify the user that a tool is making progress (e.g., "Downloading artifact..."). */
    fun notifyToolProgress(toolName: String, message: String)
}
