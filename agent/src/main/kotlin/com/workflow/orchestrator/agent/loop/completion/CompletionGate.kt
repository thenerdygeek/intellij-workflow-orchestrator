// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.loop.completion

/**
 * One step in the post-`attempt_completion` chain. When armed, the loop injects
 * [nudge] and continues; the gate is cleared either by a satisfying tool call
 * ([isSatisfiedByTool]) or by the agent re-issuing `attempt_completion` (handled
 * universally by [CompletionGateChain], not here).
 *
 * Orchestrator-only — sub-agents complete via `task_report`, never the Completion branch.
 */
interface CompletionGate {
    /** Stable identifier, e.g. "memory", "feedback". */
    val id: String

    /** Message injected as a nudge when this gate is armed. */
    fun nudge(): String

    /** True if invoking [toolName] satisfies this gate. Default: only re-completion satisfies it. */
    fun isSatisfiedByTool(toolName: String): Boolean = false
}

/**
 * Asks the agent — before the task is marked complete — whether anything it learned
 * this session is worth persisting to file-based memory. Satisfied only by re-issuing
 * `attempt_completion` (memory uses generic create_file/edit_file, so there is no
 * dedicated tool to wait for). Gated by `AgentSettings.proactiveMemoryUpdatesEnabled`.
 */
class MemoryReviewGate(private val memoryDirPath: String) : CompletionGate {
    override val id: String = "memory"

    override fun nudge(): String =
        "Before this task is marked complete, review whether anything you learned this session is " +
        "worth saving to your file-based memory at $memoryDirPath.\n\n" +
        "Worth saving: stable facts about the user (role, preferences), feedback on how you should " +
        "work, durable project state (who is doing what, why, by when), or external references. " +
        "Do NOT save code patterns, architecture, file paths, git history, or fix recipes — those " +
        "are derivable from the project itself.\n\n" +
        "If something is worth saving: use `create_file` for a new `<type>_<topic>.md` memory file " +
        "(with name/description/type frontmatter) or `edit_file` to update an existing one, and add " +
        "or update its one-line entry in MEMORY.md so future sessions can find it. Then call " +
        "`attempt_completion` again to finish.\n\n" +
        "If nothing is worth saving, just call `attempt_completion` again. " +
        "(This is an automated message — do not respond to it conversationally.)"
}

/**
 * Asks the agent to report tool problems via the `feedback` tool after completing a task.
 * Satisfied by the `feedback` tool OR by re-issuing `attempt_completion` (the universal
 * bypass). Gated by `AgentSettings.agentFeedbackEnabled`. Behaviorally identical to the
 * pre-refactor inline feedback nudge.
 */
class FeedbackGate : CompletionGate {
    override val id: String = "feedback"

    override fun isSatisfiedByTool(toolName: String): Boolean = toolName == "feedback"

    override fun nudge(): String = FEEDBACK_NUDGE

    companion object {
        /** Verbatim copy of the pre-refactor nudge (was inline at AgentLoop.kt:2251-2256). */
        const val FEEDBACK_NUDGE: String =
            "Use the `feedback` tool to share any feedback about the tools you used during this task. " +
            "Report tools that did not work as expected, had confusing or contradictory parameters, " +
            "returned incorrect results, or failed unexpectedly. " +
            "If you have no feedback, call it with an empty string."
    }
}
