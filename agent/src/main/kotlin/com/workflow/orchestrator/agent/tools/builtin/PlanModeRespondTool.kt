package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.diagnostic.Logger
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/**
 * Plan mode response tool — faithful port of Cline's plan_mode_respond.
 *
 * Cline source: src/core/prompts/system-prompt/tools/plan_mode_respond.ts
 *
 * This tool is only available in PLAN MODE. The LLM uses it to present a
 * concrete plan after exploring relevant files. The plan is returned to the
 * user for review. If needs_more_exploration is true, the loop continues
 * to let the LLM explore more before presenting a final plan.
 *
 * Flow:
 * 1. LLM explores code (reads, searches) in plan mode
 * 2. LLM calls plan_mode_respond with its plan
 * 3. If needs_more_exploration=true, loop continues for more exploration
 * 4. If needs_more_exploration=false (default), loop pauses for user review
 * 5. User reviews, gives feedback or approves
 * 6. On approval, switches to act mode to implement
 */
class PlanModeRespondTool : AgentTool {

    private val LOG = Logger.getInstance(PlanModeRespondTool::class.java)

    override val name = "plan_mode_respond"

    override val description = "Call this ONLY when presenting a new or materially revised implementation plan. " +
        "For conversational replies (answering questions, acknowledging feedback, discussing whether to plan) " +
        "reply with plain text — do not call this tool. If a previously presented plan has become invalid and " +
        "you do not have a replacement ready, call discard_plan to clear it.\n\n" +
        "Present a concrete implementation plan to the user for review and approval. " +
        "This tool should ONLY be used when you have already explored the relevant files and are ready to present " +
        "a plan. DO NOT use this tool to announce what files you're going to read — just read them first. " +
        "This tool is only available in PLAN MODE.\n\n" +
        "Your plan is a single `response` field: a full markdown document with headings, code blocks, tables, " +
        "and file paths. This is rendered in the plan document viewer where the user can add inline comments.\n\n" +
        "Plan format guidelines:\n" +
        "- Use `## Phase N: Title` or `### Task N: Title` headings to structure the response markdown.\n" +
        "- Under each heading, list the files to create/modify, the steps to take, and include actual code blocks.\n\n" +
        "Callouts (admonitions): the plan viewer renders GitHub-style alert blockquotes as colored " +
        "callout boxes. Use them as a SINGLE \"things the user should glance at before approving\" zone at " +
        "the very TOP of the plan — before the first `### Task` header, in the summary area. Do NOT sprinkle " +
        "callouts through individual tasks; that's just visual noise. The whole point is that the user can " +
        "scan one block at the top and immediately see whether their input is needed.\n\n" +
        "Syntax:\n" +
        "  > [!LABEL]\n" +
        "  > One or more lines of body text. Inline `code`, **bold**, [links](url) and lists are fine.\n\n" +
        "Recommended labels (each maps to a distinct callout color):\n" +
        "- `[!REVIEW REQUIRED]` — the user must verify or decide something before you can continue " +
        "(e.g. \"confirm column type\", \"choose between Option A and B\"). This is the most important label.\n" +
        "- `[!ASSUMPTION]` — something you assumed; flag it so the user can correct it cheaply before approval.\n" +
        "- `[!RISK]` — a known trade-off or sharp edge in the chosen approach.\n" +
        "- `[!IMPORTANT]` — must-read info that affects correctness.\n" +
        "- `[!WARNING]` — proceed-with-care detail; behaviour changes if missed.\n" +
        "- `[!CAUTION]` — risk of data loss, breaking change, security impact.\n" +
        "- `[!NOTE]` — neutral context the reader should know.\n" +
        "- `[!TIP]` — a helpful suggestion or shortcut.\n" +
        "Custom labels are accepted (e.g. `[!ROLLBACK PLAN]`) and render with a generic style.\n\n" +
        "Rules of thumb: zero callouts is fine — use them only when the user's attention is genuinely " +
        "needed. Two or three at most for a typical plan. If you have nothing the user must review, " +
        "skip callouts entirely — an empty top-zone is cleaner than a fake `[!NOTE]` filler.\n\n" +
        "If while writing your response you realize you need more exploration, set needs_more_exploration=true.\n\n" +
        "If your previous plan call was cut short by the output length limit, call this tool again with " +
        "append=true and continue EXACTLY where the previous content was cut off — the system has saved " +
        "the prefix that was already emitted and will stitch it together with your continuation. " +
        "Do not repeat earlier sections. Do not set append=true for a fresh plan or a revised plan; " +
        "only in response to a system truncation nudge."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "response" to ParameterProperty(
                type = "string",
                description = "The full implementation plan as a markdown document. Use ## and ### headings for " +
                    "phases/tasks, include file paths, code blocks, tables, and step-by-step instructions. " +
                    "This is rendered in the plan document viewer where the user can add inline comments on " +
                    "specific lines. Do not use tools in this parameter — it is a markdown document only."
            ),
            "needs_more_exploration" to ParameterProperty(
                type = "boolean",
                description = "Set to true if while formulating your response you found you need to do more " +
                    "exploration with tools, for example reading files. (Remember, you can explore the project " +
                    "with tools like read_file in PLAN MODE without the user having to toggle to ACT MODE.) " +
                    "Defaults to false if not specified."
            ),
            "append" to ParameterProperty(
                type = "boolean",
                description = "Set to true when continuing after a truncation nudge OR after a successful " +
                    "call with needs_more_exploration=true. The new response is appended to the existing " +
                    "plan content in the editor — start exactly where the previous content was cut off and " +
                    "do not repeat earlier sections. Defaults to false (replaces the current plan)."
            ),
        ),
        required = listOf("response")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override fun documentation(): ToolDocumentation = toolDoc("plan_mode_respond") {
        summary {
            technical("Plan-mode communication primitive: emits a markdown plan as a PlanResponse tool result; when needs_more_exploration=false, AgentLoop suspends on userInputChannel.receive() until the user types feedback or clicks Approve, at which point planModeActive flips off and tool definitions are rebuilt for act mode.")
            plain("Like a draft proposal a manager submits before doing the work — the agent writes up what it intends to do, hands it to you, and waits at the door until you say go (or scribble in the margins). The agent cannot resume on its own.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without plan_mode_respond, plan mode is unenforceable: the LLM has no primitive that both communicates a plan AND yields control to the user. It would either dump the plan as plain assistant text (no rendered plan card, no inline-comment surface, no loop suspension — the agent just keeps iterating) or attempt to write the plan to a file via edit_file, which is blocked by both the schema filter and the AgentLoop write-tool guard while in plan mode. Net cost: plan mode collapses into a styling convention with no teeth."
        )
        llmMistake("Sets needs_more_exploration=true when the plan is actually complete — the loop continues iterating instead of pausing for user review, burning tokens on redundant exploration before eventually re-emitting the same plan. Defaults to false for a reason.")
        llmMistake("Presents the plan as plain assistant text outside this tool — gets nudged by the act-mode/plan-mode separation but the bare text is not rendered as a plan card, has no inline-comment surface, and the loop does not suspend. Should always wrap plan content in this tool when in plan mode.")
        llmMistake("Calls plan_mode_respond for conversational replies (answering a question, acknowledging feedback) — the tool is for materially new or revised plans only; ordinary chat should be plain text. The description hardens this but the LLM still over-reaches, especially after compaction.")
        llmMistake("Tries to use edit_file or create_file to write the plan to disk while in plan mode — blocked twice (schema filter strips write tools; AgentLoop's WRITE_TOOLS guard rejects cached calls). The LLM should put the plan in the `response` parameter, not try to land it as a file.")
        llmMistake("Sprinkles `> [!REVIEW REQUIRED]` callouts throughout individual tasks instead of consolidating into a single top-of-plan zone — visual noise, defeats the scan-once UX. The description spells this out; failures here suggest the LLM weighted other plan-format guidance higher.")
        llmMistake("Includes nested tool invocations or tool-use XML inside the `response` markdown — the parameter is a markdown document only, no tools execute from inside it.")
        params {
            required("response", "string") {
                llmSeesIt("The full implementation plan as a markdown document. Use ## and ### headings for phases/tasks, include file paths, code blocks, tables, and step-by-step instructions. This is rendered in the plan document viewer where the user can add inline comments on specific lines. Do not use tools in this parameter — it is a markdown document only.")
                humanReadable("The actual proposal — a markdown document the user reads in the plan viewer. Headings, code blocks, file paths, callouts at the top for things that need user input. The whole plan goes in this one field.")
                whenPresent("Captured as the PlanResponse tool result content; persisted as a PLAN_UPDATE UI message so it survives session resume; rendered in the plan document viewer for inline commenting.")
                constraint("must be a markdown document — no embedded tool-use XML, no nested tool calls; the plan viewer renders it as plain markdown with GitHub-style alert callouts (`> [!REVIEW REQUIRED]`, `> [!ASSUMPTION]`, etc.) at the top only")
                example("## Phase 1: Refactor AuthService\n\n### Task 1: Extract token validation\n- File: `core/src/.../AuthService.kt`\n- Move `validateToken(...)` into a new `TokenValidator` class.\n```kotlin\nclass TokenValidator { ... }\n```")
                example("> [!REVIEW REQUIRED]\n> Confirm the column type before I proceed: should `created_at` be `TIMESTAMP` or `TIMESTAMPTZ`?\n\n## Phase 1: Schema migration\n...")
            }
            optional("needs_more_exploration", "boolean") {
                llmSeesIt("Set to true if while formulating your response you found you need to do more exploration with tools, for example reading files. (Remember, you can explore the project with tools like read_file in PLAN MODE without the user having to toggle to ACT MODE.) Defaults to false if not specified.")
                humanReadable("Escape hatch: 'I started writing the plan but realized I need to read more files first.' When true, the loop keeps running instead of pausing for your review.")
                whenPresent("If true: AgentLoop emits the plan to the UI but does NOT suspend — the loop continues immediately so the LLM can read more files, search, etc. The plan rendered is treated as a draft.")
                whenAbsent("Defaults to false — AgentLoop calls userInputChannel.receive() and suspends until the user sends a message (typed feedback, inline step comments, or Approve click). This is the load-bearing default; flipping it accidentally turns plan mode into an unbounded auto-loop.")
                constraint("accepts JSON boolean (true/false) OR string `\"true\"`/`\"false\"` (some models emit booleans as strings — handled by a try/catch in execute)")
                example("false")
                example("true")
            }
            optional("append", "boolean") {
                llmSeesIt("Set to true when continuing after a truncation nudge OR after a successful call with needs_more_exploration=true. The new response is appended to the existing plan content in the editor — start exactly where the previous content was cut off and do not repeat earlier sections. Defaults to false (replaces the current plan).")
                humanReadable("Stitch mode: the new content is glued onto whatever plan text was already saved. Use only in response to a system truncation nudge, or when deliberately adding sections after a needs_more_exploration=true draft.")
                whenPresent("AgentController prepends accumulatedPlanText to the new planText before writing to disk and rendering the plan card — the result is the full combined plan, not just the continuation.")
                whenAbsent("Defaults to false — the new response replaces the current plan entirely. This is correct for fresh plans and revised plans.")
                constraint("Only set to true in response to an output-length truncation nudge or when following up on a needs_more_exploration=true call; never for first-call or wholesale revision")
                example("false")
                example("true")
            }
        }
        verdict {
            keep(
                "Load-bearing for plan mode. Nothing else combines (a) emitting a structured plan as a tool-result the loop can dispatch on, (b) routing through AgentLoop's PlanResponse branch which suspends on userInputChannel.receive(), and (c) persisting as a PLAN_UPDATE UI message so the plan card survives session resume. Removing this would force a redesign of the entire plan-mode handshake — schema filter, write-tool guard, plan-card UI, resume flow, and the dashboard approve button all assume this tool exists.",
                VerdictSeverity.STRONG,
            )
        }
        related("enable_plan_mode", Relationship.COMPLEMENT, "Inbound counterpart — switches the agent INTO plan mode. plan_mode_respond is the outbound communication primitive once you're there.")
        related("attempt_completion", Relationship.SEE_ALSO, "Contrast: attempt_completion ends a session with a finished result; plan_mode_respond proposes work and waits for approval before any work happens.")
        related("task_create", Relationship.COMPLEMENT, "After a plan is approved and act mode resumes, the LLM often spawns task_create entries for each Phase/Task heading in the plan to track progress.")
        related("ask_followup_question", Relationship.ALTERNATIVE, "Use instead when the agent needs ONE specific decision from the user (single question or wizard) rather than presenting a multi-step implementation plan.")
        related("discard_plan", Relationship.COMPLEMENT, "If a previously-presented plan becomes invalid and the LLM has no replacement ready, discard_plan clears it; plan_mode_respond is for the replacement plan.")
        downside("Loop suspension is one-way — only the user can transition out (typed message into userInputChannel, inline comment, or Approve click). If the user goes idle, the loop sits forever; there is no programmatic timeout or self-rescue path for the LLM.")
        downside("The plan is a single markdown blob in `response`; structured fields (phases, tasks, file lists) are convention-only — the LLM has to manually hold the `## Phase N` / `### Task N` shape, and a malformed plan still renders but loses the inline-comment alignment.")
        downside("Available only in plan mode (schema filter strips it from tool definitions in act mode). The LLM occasionally tries to call it after the user approves and act mode resumes — the call fails the schema check and the iteration is wasted.")
        downside("Allowed only for the orchestrator (`allowedWorkers = {ORCHESTRATOR}`). Sub-agents cannot present plans — they must use task_report instead. Worth knowing before composing plans inside a delegated worker.")
        downside("Boolean-coercion fallback for `needs_more_exploration` is intentional but masks a class of LLM bugs: passing `\"True\"` (capital T from some models) is silently coerced; passing `\"yes\"` is silently treated as false. No warning surfaced.")
        flowchart("""
            flowchart TD
                A[LLM in plan mode] --> B{Plan ready?}
                B -- "no, need more exploration" --> C[plan_mode_respond<br/>needs_more_exploration=true]
                C --> D[AgentLoop emits PlanResponse<br/>onPlanResponse fires]
                D --> E[Loop continues immediately<br/>LLM reads more files]
                E --> B
                B -- "yes, plan complete" --> F[plan_mode_respond<br/>needs_more_exploration=false]
                F --> G[AgentLoop emits PlanResponse<br/>onPlanResponse fires<br/>PLAN_UPDATE UI message persisted]
                G --> H[userInputChannel.receive<br/>LOOP SUSPENDED]
                H --> I{User action?}
                I -- "type feedback" --> J[Channel resumes with feedback<br/>still in plan mode]
                J --> B
                I -- "click Approve" --> K[planModeActive flips to false<br/>tool definitions rebuilt for act mode]
                K --> L[Loop resumes — LLM implements plan]
                I -- "user idle indefinitely" --> M[No timeout —<br/>loop sits forever]
        """)
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val response = params["response"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: response",
                summary = "plan_mode_respond failed: missing response",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val needsMoreExploration = try {
            params["needs_more_exploration"]?.jsonPrimitive?.boolean ?: false
        } catch (_: Exception) {
            // Handle string "true"/"false" since some models send booleans as strings
            params["needs_more_exploration"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) ?: false
        }

        val append = try {
            params["append"]?.jsonPrimitive?.boolean ?: false
        } catch (_: Exception) {
            params["append"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) ?: false
        }

        return ToolResult.planResponse(
            content = response,
            summary = if (needsMoreExploration) {
                "Plan draft (needs more exploration): ${response.take(200)}"
            } else {
                "Plan presented: ${response.take(200)}"
            },
            tokenEstimate = response.length / 4,
            needsMoreExploration = needsMoreExploration,
            append = append,
        )
    }
}
