package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.CompletionData
import com.workflow.orchestrator.agent.tools.CompletionKind
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class AttemptCompletionTool : AgentTool {

    override val name = "attempt_completion"

    override val description = """
        Call this tool to stop the current task. This is the ONLY way to end a task — text-only responses without a tool call are NOT valid exits.

        Choose the kind that describes what kind of completion this is:
        - "done": Work is complete. No user action needed. Example result: "Refactored AuthService to use constructor injection. All 14 tests pass."
        - "review": The output needs the user to inspect, validate, or decide something before they can be confident. Put the verify-by instruction in verify_how. Example result: "Added the feature flag. Please check the admin panel to confirm the toggle is visible."
        - "heads_up": You discovered something important that the user should know — a hidden risk, a scope gap, a notable finding — even though the immediate task is complete. Put the finding in discovery. Example result: "Completed the migration. Discovery: the old schema still has 3 orphaned tables that were not in the migration spec."

        Use ask_followup_question instead if you are blocked and user input can unblock you.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "kind" to ParameterProperty(
                type = "string",
                description = "Classification of this completion. Must be 'done', 'review', or 'heads_up'. See tool description for criteria.",
                enumValues = listOf("done", "review", "heads_up")
            ),
            "result" to ParameterProperty(
                type = "string",
                description = "Short summary card shown to the user. Must be concise — your detailed explanation should be in the streamed text BEFORE this tool call, not here."
            ),
            "verify_how" to ParameterProperty(
                type = "string",
                description = "Optional: a CLI command, URL, or instruction the user can follow to verify the result. Valid on all kinds. When kind=review, this is the primary CTA and should clearly describe what to check."
            ),
            "discovery" to ParameterProperty(
                type = "string",
                description = "Required when kind=heads_up: the surprising finding, hidden risk, or scope gap the user needs to know about. Must be omitted or null for done and review."
            ),
            "next_step" to ParameterProperty(
                type = "string",
                description = "Optional. A short, plausible next message the user is likely to send (e.g. \"run the tests\", \"open the failing log\", \"commit this\"). Rendered as faded ghost-text in the chat input; the user accepts it with Right Arrow. Keep it under ~12 words and phrase it as the user would (imperative, first-person). Omit if no next step is obvious."
            )
        ),
        required = listOf("kind", "result")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override fun documentation(): ToolDocumentation = toolDoc("attempt_completion") {
        summary {
            technical("Orchestrator-only completion signal. Returns a Completion-typed ToolResult that drives loop termination via ToolResultType.Completion and renders a CompletionCard in the chat with a kind-tagged result, optional verify-how CTA, optional surprising discovery, and optional ghost-text next-step suggestion.")
            plain("How the agent says 'I'm done' to you. Like the chef tapping the bell at a restaurant — the meal stops cooking, the plate goes out, and a card lands in chat saying what was made and whether you should taste-test it before paying.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without attempt_completion the ReAct loop has no clean exit. The LLM cannot signal 'task done' except by stopping tool calls — which trips the TEXT_ONLY_NUDGE escalation: nudge after each text-only turn, then after maxConsecutiveMistakes=3 the loop either pauses asking the user to type guidance or (in sub-agents) fails with NO_TOOLS_USED. Removing the tool replaces every successful task ending with three rounds of '[ERROR] You did not use a tool' nudges and a manual user intervention. There is no acceptable substitute — task_report fills the same role for sub-agents but is gated to non-orchestrator workers."
        )
        llmMistake("Calls attempt_completion in the same turn as other tool calls (e.g. read_file + attempt_completion). The summary would be a guess based on tools whose results haven't been observed yet — AgentLoop's batch guard strips the completion from the multi-tool batch and nudges the LLM to re-issue it on its own next turn after seeing the results.")
        llmMistake("Picks `kind=done` when the work needs human verification (browser check, deploy validation, schema review). The user gets a 'no action needed' card and only later realises something's wrong. Correct choice: `kind=review` with a concrete verify_how.")
        llmMistake("Picks `kind=heads_up` and forgets to set `discovery`, or sets a blank string — execute() rejects with 'heads_up requires a non-empty discovery' and the LLM has to retry. Mitigation lives in the description but the omission is a recurring pattern.")
        llmMistake("Stuffs the full detailed explanation into `result` instead of streaming the explanation as text BEFORE the tool call. The CompletionCard truncates the summary at 200 chars in the cross-session log, so verbose results get clipped where the user reads them later.")
        llmMistake("Calls attempt_completion mid-task before the user's actual request is satisfied — typically after partially exploring code or finishing a sub-step. There is no automatic guard for this; only LoopDetector's repeat-call detection catches it indirectly.")
        params {
            required("kind", "string") {
                llmSeesIt("Classification of this completion. Must be 'done', 'review', or 'heads_up'. See tool description for criteria.")
                humanReadable("What flavour of completion this is — like the difference between 'I shipped it' (done), 'please double-check before I ship it' (review), and 'shipped, but watch out for this' (heads_up). Drives the icon, colour, and call-to-action on the completion card the user sees.")
                whenPresent("Maps to the CompletionKind enum (DONE / REVIEW / HEADS_UP) and tags the rendered CompletionCard. The card UI changes accent colour and CTA wording per kind.")
                constraint("must be exactly one of: done, review, heads_up — case-sensitive; any other value returns 'Invalid value for kind' before any persistence")
                enumValue("done", "review", "heads_up")
                example("done")
                example("review")
                example("heads_up")
            }
            required("result", "string") {
                llmSeesIt("Short summary card shown to the user. Must be concise — your detailed explanation should be in the streamed text BEFORE this tool call, not here.")
                humanReadable("The one-or-two-sentence headline shown on the completion card. Anything longer belongs in the streaming chat above the card — the long explanation the model just wrote, not duplicated here.")
                whenPresent("Becomes the card's primary text and is truncated to the first 200 chars in the cross-session sessions index summary.")
                constraint("must be non-empty — missing parameter rejected before execution")
                example("Refactored AuthService to use constructor injection. All 14 tests pass.")
                example("Added the feature flag. Please check the admin panel to confirm the toggle is visible.")
            }
            optional("verify_how", "string") {
                llmSeesIt("Optional: a CLI command, URL, or instruction the user can follow to verify the result. Valid on all kinds. When kind=review, this is the primary CTA and should clearly describe what to check.")
                humanReadable("A copy-pasteable command, URL, or one-line instruction telling the user how to confirm the work landed correctly. The 'how do I check this?' answer pre-baked.")
                whenPresent("Rendered as a CTA / verify-button on the CompletionCard. For kind=review it is the primary action; for kind=done/heads_up it appears as secondary detail.")
                whenAbsent("Card renders without a verification CTA. Acceptable for purely-internal refactors where the LLM has already proven correctness via tests.")
                example("./gradlew :agent:test --tests '*AuthServiceTest*'")
                example("Open http://localhost:8080/admin and check the 'New flag' toggle is visible.")
            }
            optional("discovery", "string") {
                llmSeesIt("Required when kind=heads_up: the surprising finding, hidden risk, or scope gap the user needs to know about. Must be omitted or null for done and review.")
                humanReadable("The 'oh and by the way…' note — something the user didn't ask about but ought to know before moving on. Hidden risk, scope gap, dead code stumbled on, etc.")
                whenPresent("Required when kind=heads_up — execute() rejects a heads_up call without it. Rendered as a distinct discovery callout on the card so it doesn't get lost in the result line.")
                whenAbsent("Required for kind=heads_up (otherwise the call fails with 'heads_up requires a non-empty discovery'). Optional and typically omitted for kind=done and kind=review.")
                example("The old schema still has 3 orphaned tables that were not in the migration spec.")
            }
            optional("next_step", "string") {
                llmSeesIt("Optional. A short, plausible next message the user is likely to send (e.g. \"run the tests\", \"open the failing log\", \"commit this\"). Rendered as faded ghost-text in the chat input; the user accepts it with Right Arrow. Keep it under ~12 words and phrase it as the user would (imperative, first-person). Omit if no next step is obvious.")
                humanReadable("A guess at what the user will type next — shown as faded autocomplete in the chat input box, accepted with Right Arrow. Saves the user typing 'run the tests' if that's the obvious follow-up. Phrased as the user would say it, not as a system suggestion.")
                whenPresent("Pre-fills the chat input with faded ghost-text on the next prompt. User taps Right Arrow to accept; any other typing dismisses it. Blank values are stripped so an empty `next_step: \"\"` does not pollute the input.")
                whenAbsent("Chat input opens empty as normal. No regression — the feature is purely additive.")
                constraint("aim for under ~12 words; phrase as imperative first-person ('run the tests'), not third-person ('the user could run the tests')")
                example("run the tests")
                example("commit this")
                example("open the failing log")
            }
        }
        verdict {
            keep(
                "Architecturally load-bearing — the ReAct loop's only clean exit. Without it, every successful task degrades into the TEXT_ONLY_NUDGE escalation: 3 rounds of '[ERROR] You did not use a tool' before either user-feedback fallback (orchestrator) or hard NO_TOOLS_USED failure (sub-agent). The structured kind/discovery/verify_how/next_step fields also drive UI affordances (ghost-text autocomplete, verify CTA) that are impossible to reconstruct from raw model text.",
                VerdictSeverity.STRONG,
            )
        }
        related("task_report", Relationship.ALTERNATIVE, "Sub-agent counterpart. Sub-agents are gated away from attempt_completion (allowedWorkers=ORCHESTRATOR only) and must call task_report instead — its fields flow directly into the parent LLM's tool result, where attempt_completion targets the user UI.")
        related("ask_followup_question", Relationship.ALTERNATIVE, "Use instead when blocked and user input can unblock the work. attempt_completion ends the task; ask_followup_question pauses it pending an answer.")
        related("plan_mode_respond", Relationship.SEE_ALSO, "Plan-mode equivalent: presents a plan and suspends the loop pending Approve. Different UI, different exit semantics — plan_mode_respond doesn't terminate the session, just gates act-mode entry.")
        downside("Orchestrator-only — `allowedWorkers = {ORCHESTRATOR}` means sub-agents calling this get a worker-type rejection at schema time. Sub-agents must use task_report. Easy to forget when porting persona prompts.")
        downside("Batch-stripped: if the LLM emits attempt_completion alongside other tool calls in the same turn, AgentLoop's batch guard removes it and nudges the model to re-issue after observing the other results. This is intentional but can confuse the LLM if the prompt didn't warn it.")
        downside("`result` is shown verbatim as the card headline AND truncated to 200 chars for the global sessions index — verbose multi-paragraph results get clipped where the user reads them between sessions. Streaming the long-form text BEFORE the tool call is the workaround, but the LLM doesn't always do it.")
        downside("`kind=heads_up` enforces non-empty discovery only at execute() time — schema-level enforcement is impossible because JSON Schema can't express 'discovery required iff kind==heads_up'. The LLM occasionally trips on this and burns a retry.")
        downside("Despite the name, this tool does not 'attempt' anything — it terminates the loop unconditionally. The legacy name is a Cline carry-over; renaming would break every persona prompt that mentions it.")
        observation("`next_step` is unique among completion params in that it leaks state INTO the next session (pre-fills the chat input). All other fields are read-only artefacts of the just-finished task.")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val kindStr = params["kind"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult.error(
                message = "Missing required parameter: kind",
                summary = "attempt_completion failed: missing kind"
            )

        val kind = when (kindStr) {
            "done" -> CompletionKind.DONE
            "review" -> CompletionKind.REVIEW
            "heads_up" -> CompletionKind.HEADS_UP
            else -> return ToolResult.error(
                message = "Invalid value for 'kind': '$kindStr'. Must be one of: done, review, heads_up",
                summary = "attempt_completion failed: invalid kind '$kindStr'"
            )
        }

        val result = params["result"]?.jsonPrimitive?.content
            ?: return ToolResult.error(
                message = "Missing required parameter: result",
                summary = "attempt_completion failed: missing result"
            )

        val verifyHow = params["verify_how"]?.jsonPrimitive?.content
        val discovery = params["discovery"]?.jsonPrimitive?.content
        val nextStep = params["next_step"]?.jsonPrimitive?.content?.takeUnless { it.isBlank() }

        if (kind == CompletionKind.HEADS_UP && discovery.isNullOrBlank()) {
            return ToolResult.error(
                message = "kind=heads_up requires a non-empty 'discovery' field describing the finding",
                summary = "attempt_completion failed: heads_up requires discovery"
            )
        }

        return ToolResult.completion(
            content = result,
            summary = "Task $kindStr: ${result.take(200)}",
            tokenEstimate = result.length / 4,
            completionData = CompletionData(
                kind = kind,
                result = result,
                verifyHow = verifyHow,
                discovery = discovery,
                nextStep = nextStep,
            )
        )
    }
}
