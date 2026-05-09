package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ThinkTool : AgentTool {
    override val name = "think"
    override val description = "Use this tool to think about something before acting. It will not obtain new information or change any files, but lets you reason through complex decisions. Use when: analyzing tool output, planning multi-step changes, choosing between approaches, or before making irreversible edits."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "thought" to ParameterProperty(type = "string", description = "Your reasoning or analysis. Think through the problem step by step.")
        ),
        required = listOf("thought")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.TOOLER)

    override fun documentation(): ToolDocumentation = toolDoc("think") {
        summary {
            technical("No-op reasoning scratchpad — accepts a `thought` string, ignores it, and returns a fixed `Thought recorded.` ToolResult (token estimate 2). Exists purely so the LLM can serialize a deliberation step as a tool call instead of a free-text turn, satisfying AgentLoop's tool-call-required cadence and giving the loop a discrete checkpoint between actions.")
            plain("A scratchpad the agent uses to think out loud. Like writing on a whiteboard before making a decision — the words don't do anything, but having a place to write them down means the agent slows down and reasons through the next step instead of guessing.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without `think`, the LLM has two worse options. (1) Emit a text-only assistant turn to reason out loud — but text-only turns trigger AgentLoop's escalating no-tool nudges (up to MAX_NO_TOOL_NUDGES=4) demanding a tool call, so reasoning gets punished. (2) Smuggle deliberation into the args of a real tool (e.g. a verbose `read_file` rationale, or a `<thinking>` block inside `attempt_completion`) — which couples reasoning to side effects and makes loop traces harder to read. Net cost of dropping it: lower-quality reasoning interleaved with real tool calls, plus the extra round-trips to satisfy the no-tool-nudge logic."
        )
        llmMistake("Uses `think` as a substitute for actually doing work — emits a long thought, then immediately calls `attempt_completion` without any read/edit/run actions. Looks productive in the trace but adds zero progress. LoopDetector's identical-call rule does not catch this because the `thought` payload varies.")
        llmMistake("Dumps multi-paragraph plans into `think` instead of using `task_create` / `task_update` — the deliberation is lost on context compaction (Stage 2 truncation drops middle messages, including stale think turns), whereas TaskStore content is re-rendered into the system prompt every iteration and survives.")
        llmMistake("Calls `think` in plan mode when `plan_mode_respond` is the correct surface — `think` keeps reasoning private to the loop, but the user is waiting for a plan card to approve.")
        llmMistake("Chains 3+ `think` calls back-to-back with no intervening action — each call costs a full LLM round-trip for a constant 2-token reply. Cheap individually, expensive in aggregate.")
        params {
            required("thought", "string") {
                llmSeesIt("Your reasoning or analysis. Think through the problem step by step.")
                humanReadable("The agent's free-form deliberation — whatever it wants to say to itself before picking the next action. Goes into the conversation history but never reaches the user UI directly.")
                whenPresent("The string is read off the params, discarded, and the tool returns a fixed `Thought recorded.` ToolResult with token estimate 2. The thought itself only persists because it's part of the `tool_calls` payload that AgentLoop writes into `api_conversation_history.json`.")
                constraint("must be a non-empty string — `thought` is the only required param and a missing key returns an `isError=true` result")
                example("The user wants me to refactor `FooService`. Before editing, I need to read the file, then check who calls it via find_references. Reading first.")
                example("Both options compile. Option A is clearer; Option B is faster. The task is in a hot path, so I'll go with B and add a comment explaining the trade-off.")
            }
        }
        verdict {
            keep(
                "Lightweight, near-zero side effect, and earns its slot by giving the LLM a sanctioned place to reason — without it, deliberation either pollutes other tools' arguments or trips the no-tool-nudge escalation. Cline ships the same primitive for the same reason. The cost (one schema slot, ~2 tokens per call) is far smaller than the cost of removing it (more no-tool nudges, messier traces, deliberation hiding inside `attempt_completion`).",
                VerdictSeverity.STRONG,
            )
        }
        related("plan_mode_respond", Relationship.ALTERNATIVE, "Use instead in plan mode — `plan_mode_respond` renders a user-visible plan card with approve/revise buttons, whereas `think` keeps reasoning private to the loop.")
        related("task_create", Relationship.ALTERNATIVE, "Use instead when the deliberation is really a multi-step plan — `task_create`/`task_update` survive context compaction; `think` does not.")
        related("attempt_completion", Relationship.SEE_ALSO, "Both are AGENT_CONTROL no-ops at the file/process level, but `attempt_completion` ends the session whereas `think` continues it.")
        downside("The thought is discarded by `execute()` — only the prior assistant turn carrying the tool_call args preserves it in history. If that turn gets dropped by context compaction's Stage 2 truncation (middle-message removal), the reasoning is gone.")
        downside("Token estimate is hardcoded to 2, which under-counts the actual cost — the real spend is the assistant turn that contained the `think` tool_call, not the 2-token reply. Token meters reading per-tool estimates will under-report.")
        downside("Available to every worker type (ORCHESTRATOR, CODER, ANALYZER, REVIEWER, TOOLER) and exempt from approval gates. There is no way for the user to disable it for a session if they decide it's being abused as a stalling tactic.")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val thought = params["thought"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'thought' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        return ToolResult(content = "Thought recorded.", summary = "Thought recorded", tokenEstimate = 2)
    }
}
