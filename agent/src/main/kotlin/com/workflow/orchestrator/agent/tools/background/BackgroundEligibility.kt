package com.workflow.orchestrator.agent.tools.background

/**
 * Decides which tools may run in the background. All *work* tools are eligible; *control-flow* and
 * *interactive* tools (which steer the loop or need an inline user answer) are not. Mirrors the
 * plan-mode WRITE_TOOLS denylist shape in AgentLoop.
 */
object BackgroundEligibility {
    /** Reserved loop-level attribute the parser must keep on any tool call, then the loop strips. */
    const val RUN_IN_BACKGROUND_PARAM = "run_in_background"

    /**
     * Augment a tool's parameter-name allowlist with the reserved background tag so the XML parser keeps
     * it on any tool call. Single source for the augmentation — used at every brain/param-provider site
     * (orchestrator + sub-agent) so a future reserved attribute is added in exactly one place.
     */
    fun withReservedParams(base: Set<String>): Set<String> = base + RUN_IN_BACKGROUND_PARAM

    /** Tools that must always run inline (steer the loop / need inline user input / mutate loop state). */
    val CONTROL_FLOW_DENYLIST: Set<String> = setOf(
        "attempt_completion",   // ends the task
        "task_report",          // sub-agent completion
        "plan_mode_respond",    // plan-mode control
        "enable_plan_mode",     // plan-mode control
        "ask_followup_question",// inline question
        "ask_questions",        // inline question (wizard)
        "ask_user_input",       // inline question
        "new_task",             // session-handoff control
        "use_skill",            // mutates loop tool/skill state
        "tool_search",          // mutates the active-deferred tool set
        "think",                // no-op scratchpad; backgrounding is pointless
    )

    fun isBackgroundable(toolName: String): Boolean = toolName !in CONTROL_FLOW_DENYLIST
}
