package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject

/**
 * Allows the LLM to discard a previously presented plan without replacing it.
 *
 * This tool is only available in PLAN MODE. Use it when:
 * - The user pushes back on the current plan and the LLM has no replacement ready.
 * - Exploration revealed the plan approach was wrong.
 * - The scope or requirements changed materially after the plan was presented.
 *
 * After calling this tool, continue the conversation with plain text or further
 * exploration. To present a new plan, call plan_mode_respond when ready.
 *
 * Contrast with plan_mode_respond (which overwrites the plan card) — this tool
 * clears the card without presenting a replacement.
 */
class DiscardPlanTool : AgentTool {

    override val name = "discard_plan"

    override val description = "Discard the currently presented plan without presenting a replacement. " +
        "Use this in PLAN MODE when the prior plan is no longer valid — the user pushed back, " +
        "exploration revealed the approach is wrong, or scope changed — and you do not have a new plan ready. " +
        "After calling this tool, continue the conversation with plain text or further exploration. " +
        "To present a new plan, use plan_mode_respond when you are ready."

    override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override fun documentation(): ToolDocumentation = toolDoc("discard_plan") {
        summary {
            technical(
                "Emits a PlanDiscarded ToolResult; AgentLoop dispatches to onPlanDiscarded, which rewrites " +
                "the most-recent plan_mode_respond tool result in api_conversation_history to " +
                "\"[Plan discarded — do not reference]\", then fires the UI callback to clear the plan card. " +
                "No mode transition — the session stays in plan mode. Zero parameters."
            )
            plain(
                "A 'never mind, scratch that' button for the agent. The agent said 'here is my plan,' the " +
                "conversation moved on, and now it needs to formally retire that proposal before writing a new one. " +
                "Like crossing out a whiteboard draft — the board is clear, but the meeting is still going."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without discard_plan, the LLM has no way to programmatically invalidate a stale plan card. " +
            "It would have to either (a) immediately call plan_mode_respond with a replacement — even when it " +
            "hasn't finished exploring and has nothing concrete to propose yet — producing a low-confidence draft " +
            "the user sees as premature; or (b) add a plain-text disclaimer ('ignore the plan above') that the " +
            "user sees but that does not clear the plan card, does not rewrite history, and leaves the discarded " +
            "plan polluting the LLM's own context on future turns (likely causing it to reference or partially " +
            "adopt the invalidated approach). Net cost: forced premature plans or persistent plan-card ghost state."
        )
        llmMistake(
            "Calls discard_plan when it actually has a replacement ready — wastes a round-trip. " +
            "The correct path is to call plan_mode_respond directly, which overwrites the card in one call."
        )
        llmMistake(
            "Uses plain text ('I am discarding the previous plan') instead of calling this tool. " +
            "The plain-text message does not rewrite history or clear the plan card — the old plan remains " +
            "visible to the user and in the LLM's context on the next turn."
        )
        llmMistake(
            "Calls discard_plan when no plan has been presented yet (no prior plan_mode_respond in this session). " +
            "The rewriteMostRecentToolResult call finds no matching entry and is a no-op — harmless, but confusing."
        )
        llmMistake(
            "Treats discard_plan as a way to exit plan mode. It is not — mode stays plan after the call. " +
            "Only the user can switch back to act mode via the UI Approve button (or by typing, which the loop " +
            "resumes on)."
        )
        verdict {
            keep(
                "Fills an otherwise unaddressable gap in the plan-mode state machine: gives the LLM a " +
                "clean way to retire a stale plan and continue exploring without either (a) being forced into " +
                "a premature plan_mode_respond or (b) leaving ghost state in the plan card and conversation " +
                "history. Zero parameters, cheap result (~20 tokens), and it is schema-filtered out of act mode " +
                "so it costs nothing when not relevant.",
                VerdictSeverity.NORMAL,
            )
        }
        related(
            "plan_mode_respond",
            Relationship.ALTERNATIVE,
            "CONTRAST: use plan_mode_respond instead when you have a replacement plan ready — it overwrites " +
            "the card in a single call. discard_plan is only for 'I need to clear the board but am not ready " +
            "to propose anything yet.'"
        )
        related(
            "enable_plan_mode",
            Relationship.COMPLEMENT,
            "CONTRAST: enable_plan_mode is the entry point to plan mode (LLM-callable, one-way). " +
            "discard_plan operates within plan mode — it clears the current plan artefact but does not " +
            "change the mode. The two tools occupy different phases of the plan-mode lifecycle."
        )
        downside(
            "History rewrite is best-effort: rewriteMostRecentToolResult searches backwards through " +
            "api_conversation_history for the last plan_mode_respond tool result and overwrites its content. " +
            "If that entry has already been compacted away (Stage 2 truncation), the rewrite silently finds " +
            "nothing and the discarded plan may still influence the LLM's context."
        )
        downside(
            "No UI acknowledgement of its own — the plan card is cleared, but the agent must follow up with " +
            "plain text or further exploration to explain the discard to the user. Calling discard_plan with no " +
            "follow-up message leaves the user with a blank plan panel and no context for why."
        )
        downside(
            "Schema-filtered out of act mode (alongside plan_mode_respond). Cached tool-call replay from a " +
            "plan-mode turn can surface it in act mode — the execution guard in AgentLoop dispatches it " +
            "normally (it does not declare `isMutating = true`), so the history rewrite still fires; the net effect is " +
            "usually benign but unexpected."
        )
        observation(
            "discard_plan has zero parameters. If a future version wants to capture a discard reason for " +
            "the audit log or UI, an optional 'reason' string (like enable_plan_mode) would be the natural " +
            "extension — matches the existing pattern and costs nothing when omitted."
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return ToolResult.planDiscarded(
            content = "Plan discarded. Continue the conversation with plain text or explore further before presenting a new plan.",
            summary = "Plan discarded by LLM"
        )
    }
}
