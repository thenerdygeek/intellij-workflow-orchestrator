package com.workflow.orchestrator.agent.runtime

/**
 * Abstraction for UI interactions that the session layer needs.
 *
 * Decouples the runtime from JCEF/Swing specifics so that
 * [SessionScope] (and anything it feeds) can be unit-tested
 * without a real IDE window.
 */
interface UiCallbacks {
    /** Show a modal input dialog and return the user's answer, or null if cancelled. */
    suspend fun showInputDialog(prompt: String, placeholder: String?): String?

    /** Stream incremental output from a running command to the chat panel. */
    fun streamCommandOutput(toolCallId: String, chunk: String)

    /** Notify the user that a tool is making progress (e.g., "Downloading artifact..."). */
    fun notifyToolProgress(toolName: String, message: String)
}
