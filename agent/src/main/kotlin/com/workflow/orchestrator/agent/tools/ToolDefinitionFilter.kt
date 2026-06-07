package com.workflow.orchestrator.agent.tools

/**
 * Pure tool-visibility predicate for the LLM-facing tool schema — which registered tools the
 * model is allowed to see on a given iteration. Extracted from `AgentService.executeTask`'s
 * `toolDefinitionProvider` closure (Phase 3 cut B, incision 3) so the rule is unit-testable
 * instead of buried in the god-function.
 *
 * Mirrors Cline's `contextRequirements` plus the plan/act mode split and the act-only delegated
 * session rule:
 *  - `use_skill` is dropped when no skills are available;
 *  - a delegated session is ACT-ONLY (no local human to approve a plan) → both plan tools are
 *    dropped regardless of plan-mode state, mirroring sub-agents' `includePlanModeSection=false`;
 *  - plan mode → drop write tools + `enable_plan_mode` (keep `plan_mode_respond`);
 *  - act mode → drop `plan_mode_respond` + `discard_plan` (keep everything else).
 *
 * Pinned by `ToolDefinitionFilterTest`.
 */
object ToolDefinitionFilter {

    /**
     * Whether [toolName] should be included in the tool definitions sent to the LLM.
     *
     * @param writeToolNames the authoritative write-tool set (`AgentLoop.WRITE_TOOLS`), injected so
     *   this stays a pure function with no dependency on the loop package's mutable state.
     */
    fun shouldInclude(
        toolName: String,
        isPlanMode: Boolean,
        isDelegatedSession: Boolean,
        hasSkills: Boolean,
        writeToolNames: Set<String>,
    ): Boolean = when {
        toolName == "use_skill" && !hasSkills -> false
        isDelegatedSession && (toolName == "enable_plan_mode" || toolName == "plan_mode_respond") -> false
        isPlanMode -> toolName !in writeToolNames && toolName != "enable_plan_mode"
        else -> toolName != "plan_mode_respond" && toolName != "discard_plan"
    }
}
