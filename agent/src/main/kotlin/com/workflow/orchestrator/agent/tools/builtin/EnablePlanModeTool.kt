package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Allows the LLM to programmatically switch to PLAN MODE.
 *
 * The system prompt says "You CAN suggest entering PLAN MODE" but previously
 * there was no tool for the LLM to actually do it. Skills like writing-plans
 * reference enable_plan_mode but the tool didn't exist.
 *
 * Flow:
 * 1. LLM recognizes a complex task needing planning
 * 2. LLM calls enable_plan_mode with a reason
 * 3. AgentLoop sees enablePlanMode=true on the ToolResult
 * 4. AgentService.planModeActive is set to true
 * 5. Tool definitions are rebuilt (write tools removed, plan_mode_respond added)
 * 6. LLM can now use plan_mode_respond to present plans
 *
 * Note: Only the user can switch BACK to act mode (via UI approve button).
 */
class EnablePlanModeTool : AgentTool {

    override val name = "enable_plan_mode"

    override val description = "Switch to PLAN MODE for structured planning before implementation. " +
        "Use this when a task is complex and would benefit from creating a detailed plan first. " +
        "After enabling plan mode, use plan_mode_respond to present your plan to the user. " +
        "Only the user can switch back to ACT MODE by approving the plan."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "reason" to ParameterProperty(
                type = "string",
                description = "Brief explanation of why plan mode is needed for this task."
            )
        ),
        required = listOf("reason")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override fun documentation(): ToolDocumentation = toolDoc("enable_plan_mode") {
        summary {
            technical("Programmatically flips `AgentService.planModeActive` (AtomicBoolean) to true; AgentLoop sees `enablePlanMode=true` on the ToolResult, the dynamic tool-definition provider rebuilds the schema (write tools + this tool removed, `plan_mode_respond` added), and the next LLM turn runs under plan-mode constraints. Asymmetric: only the user can flip the bit back via the UI Approve button — there is no `disable_plan_mode` tool.")
            plain("A switch the agent flips from \"do it\" to \"draft a proposal first.\" Like a contractor pausing demolition to walk the homeowner through the plan before swinging a hammer — and only the homeowner can say \"ok, start the work.\"")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without `enable_plan_mode`, the LLM has no way to request a planning checkpoint mid-task. It would either (a) plough ahead and edit/run things the user wanted to review first — defeating the whole point of the act/plan split; or (b) emit a plain-text \"I'd like to plan first\" turn, which trips AgentLoop's no-tool-nudge escalation and still doesn't actually swap the schema, so the LLM remains tempted by visible write tools. The user would have to manually toggle plan mode in the UI on every complex task. Net cost of dropping it: real-world `edit_file` regressions on ambiguous tasks, plus the loss of the \"let me think first\" affordance that the system prompt explicitly invites the LLM to use."
        )
        llmMistake("Enables plan mode for trivial single-file edits where act mode would have been fine. Wastes a round-trip — the LLM now has to call `plan_mode_respond` and wait for user approval before doing the one-line change it could have just made.")
        llmMistake("Calls `enable_plan_mode` and then forgets to follow up with `plan_mode_respond`, instead trying read-only exploration tools or `think`. The user sees an empty plan-mode badge with no plan card, and has to wait or steer the agent into actually presenting something.")
        llmMistake("Assumes the toggle is symmetric — calls `enable_plan_mode` thinking it can call something equivalent to disable it later. There is no such tool; mode-out-of-plan is user-driven only. The execution guard in AgentLoop blocks any write tool the LLM hallucinates calling, but the LLM may waste turns trying to escape plan mode programmatically.")
        llmMistake("Already-in-plan-mode re-call: calls `enable_plan_mode` when plan mode is already active. Schema filtering removes the tool when planMode is true, so this should not be presented to the LLM, but cached tool-call replay or stale schemas can still surface it. The execution guard treats it as a no-op-style toggle.")
        llmMistake("Skips reading/exploring before enabling plan mode and immediately calls `plan_mode_respond` with a plan based on assumptions. The system prompt explicitly says to explore first; rushing produces low-confidence plans the user has to rewrite.")
        params {
            required("reason", "string") {
                llmSeesIt("Brief explanation of why plan mode is needed for this task.")
                humanReadable("A short sentence explaining why the agent wants to plan first — surfaced in the loop trace and in the tool result content so the user can see the motivation when reviewing the session.")
                whenPresent("The reason is appended to the ToolResult content (`\"Switched to PLAN MODE. Reason: \${reason} ...\"`) and to the summary line. AgentLoop reads the result, flips `planModeActive`, rebuilds the tool schema, and the next LLM turn sees the plan-mode tool set.")
                constraint("must be a non-empty string — a missing or null `reason` returns an `isError=true` result with summary `enable_plan_mode failed: missing reason` and the toggle does NOT happen")
                example("Multi-module refactor touching ~20 files; want to outline the dependency-update order before editing.")
                example("User asked to \"clean up the auth flow\" — task is ambiguous enough that I should propose a concrete plan first.")
                example("Need to choose between two database-migration strategies; presenting both with trade-offs.")
            }
        }
        verdict {
            keep(
                "The asymmetric design is the feature, not a bug: making plan-out user-only enforces a real review checkpoint that the LLM cannot skip. If the LLM could disable plan mode, it would do so the moment it ran into a write-tool wall, defeating the safety contract. Letting it enable plan mode (one-way, LLM-callable) gives it the \"let me think first\" affordance that complex tasks genuinely benefit from, while the user-only off-switch keeps the human firmly in the loop on the act/plan boundary. Cheap (~20 token result, schema-filtered out when not relevant) and load-bearing for the entire plan-mode contract.",
                VerdictSeverity.STRONG,
            )
        }
        related(
            "plan_mode_respond",
            Relationship.COMPLEMENT,
            "Once plan mode is enabled, the LLM uses `plan_mode_respond` to actually present the plan card. The two are a strict pair: enable_plan_mode flips the bit, plan_mode_respond delivers the artefact. Calling one without the other is almost always a bug.",
        )
        related(
            "attempt_completion",
            Relationship.SEE_ALSO,
            "Both are session-control primitives the LLM emits to hand off to the user — but `attempt_completion` ends the task with a final answer, whereas `enable_plan_mode` requests more time to think and design before doing.",
        )
        downside("Asymmetric on/off semantics: only the user can return to act mode (via UI Approve button). LLMs that try to programmatically toggle back hit the schema filter (no `disable_plan_mode` tool exists) and may waste turns flailing.")
        downside("Schema filtering removes `enable_plan_mode` itself from the tool set when plan mode is already active, so the LLM cannot \"refresh\" plan mode mid-session. This is correct, but can confuse models that try to re-enter plan mode after partial exploration.")
        downside("The reason string is required but never enforced for length or quality — the LLM can pass a one-word `reason=\"complex\"` and still flip the mode. Audit traces depend on the LLM voluntarily writing useful reasons.")
        downside("Once enabled, every subsequent turn pays the schema-rebuild cost (write tools removed, `plan_mode_respond` added) until the user approves or revises. Cheap per-turn but compounds across long planning sessions.")
        flowchart(
            """
            flowchart TD
                A[LLM calls enable_plan_mode<br/>with reason] --> B{reason present?}
                B -- no --> X1[Return isError ToolResult<br/>'missing reason' — no toggle]
                B -- yes --> C[Return ToolResult.planModeToggle<br/>enablePlanMode=true]
                C --> D[AgentLoop sees enablePlanMode=true<br/>fires planModeCallback]
                D --> E[AgentService.planModeActive.set(true)]
                E --> F[Dynamic schema provider rebuilds:<br/>- tools with isMutating=true removed<br/>- enable_plan_mode removed<br/>- plan_mode_respond added]
                F --> G[Next LLM turn sees plan-mode schema]
                G --> H[LLM calls plan_mode_respond<br/>with plan markdown]
                H --> I[Loop suspends for user review]
                I --> J{User action}
                J -- Approve --> K[planModeActive.set(false)<br/>schema rebuilds for act mode]
                J -- Revise --> H
                K --> L[Loop resumes in act mode]
            """,
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val reason = params["reason"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: reason",
                summary = "enable_plan_mode failed: missing reason",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return ToolResult.planModeToggle(
            content = "Switched to PLAN MODE. Reason: $reason\n\n" +
                "You are now in PLAN MODE. Use plan_mode_respond to present your plan. " +
                "Read and explore relevant files first, then present a concrete plan.",
            summary = "Switched to plan mode: $reason",
            tokenEstimate = 20
        )
    }
}
