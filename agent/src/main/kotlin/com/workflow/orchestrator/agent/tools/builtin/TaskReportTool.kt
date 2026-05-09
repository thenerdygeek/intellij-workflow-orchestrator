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
 * Sub-agent completion tool that replaces [AttemptCompletionTool] for sub-agents.
 *
 * Unlike [AttemptCompletionTool] (which writes a short UI-facing summary card for the
 * orchestrator), this tool forces the sub-agent to produce a comprehensive report that
 * flows directly into the parent LLM's tool result. The parent cannot see the sub-agent's
 * conversation, streamed text, or internal tool calls — only the fields written here.
 *
 * Returning [ToolResult.completion] triggers loop termination via [ToolResultType.Completion],
 * the same type-based exit path used by [AttemptCompletionTool].
 *
 * [allowedWorkers] deliberately excludes [WorkerType.ORCHESTRATOR] so the orchestrator
 * continues to use [AttemptCompletionTool] (the user-facing signal). The [ToolRegistry]
 * worker-type filter enforces this at schema time.
 */
class TaskReportTool : AgentTool {

    override val name = "task_report"

    override val description = """
        Signal task completion and report findings to the parent agent. This is how a sub-agent
        hands back its result. Everything you write here flows DIRECTLY into the parent LLM's
        tool result — the parent cannot see your conversation, your tool calls, or your streamed
        text. Put your comprehensive findings in these fields, not a short summary.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "summary" to ParameterProperty(
                type = "string",
                description = "One paragraph: what was done, the overall conclusion, and whether the task succeeded."
            ),
            "findings" to ParameterProperty(
                type = "string",
                description = "Detailed findings with analysis. Markdown allowed. Include inline code snippets (with file:line) where they support the analysis."
            ),
            "files" to ParameterProperty(
                type = "string",
                description = "Newline-separated list of file paths examined or modified. One path per line. The parent uses this to know what has already been read."
            ),
            "next_steps" to ParameterProperty(
                type = "string",
                description = "What the parent agent should do next based on these findings. Be concrete and actionable."
            ),
            "issues" to ParameterProperty(
                type = "string",
                description = "Blockers, errors, or unresolved questions encountered. Empty if none."
            )
        ),
        required = listOf("summary")
    )

    // Notably excludes ORCHESTRATOR — orchestrator uses attempt_completion.
    // ToolRegistry worker-type filter gates this at schema time.
    override val allowedWorkers = setOf(
        WorkerType.CODER, WorkerType.REVIEWER,
        WorkerType.ANALYZER, WorkerType.TOOLER
    )

    override fun documentation(): ToolDocumentation = toolDoc("task_report") {
        summary {
            technical("Sub-agent-only completion signal. Returns a Completion-typed ToolResult whose content is a structured markdown report (Summary / Findings / Files / Next Steps / Issues) that flows verbatim into the parent LLM's tool result. Loop termination via ToolResultType.Completion, identical exit path to attempt_completion. Auto-injected into every sub-agent's core tool set by SpawnAgentTool.resolveConfigToolsTiered.")
            plain("How a sub-agent hands its work back to the parent. Like a sub-contractor giving the project lead a structured handoff form — five labelled sections — instead of a chatty voicemail. The parent agent never hears the sub-contractor's internal phone calls or saw their notebook; this form is the only thing it gets.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without task_report, sub-agents have no clean exit and no structured handoff. The parent LLM would see only the sub-agent's last free-form assistant text, which mixes streamed thinking, partial tool reasoning, and the actual conclusion. The parent then has to parse prose to extract files-touched, blockers, and next-steps — and frequently misses one. Spawning a sub-agent already costs an iteration and a budget allocation; losing the structured return on top makes the round-trip net-negative versus just doing the work in the orchestrator. There is no acceptable substitute — attempt_completion targets the user UI (CompletionCard) and is gated to ORCHESTRATOR only, so a sub-agent calling it gets a worker-type rejection at schema time."
        )
        llmMistake("Persona prompt copied from the orchestrator instructs the sub-agent to call `attempt_completion` to finish the task. The schema-time worker-type filter rejects the call and the sub-agent burns iterations retrying. Mitigated by SpawnAgentTool.resolveConfigToolsTiered silently dropping `attempt_completion` from `config.tools` and injecting `task_report` instead — but the persona's *prompt body* is unchanged, so the model still names the wrong tool.")
        llmMistake("Reports `summary: \"DONE\"` (or similar one-word) and omits `findings`, `files`, `next_steps`, `issues` entirely. The parent LLM gets a near-empty tool result and has to re-spawn the sub-agent or re-do the analysis itself. There is no schema-level enforcement — only `summary` is required; the structural fields are all optional.")
        llmMistake("Lists blockers in `issues` but forgets to include the file paths in `files` or actionable instructions in `next_steps`. Parent agent reads 'tests are failing' with no breadcrumb back to the failing test file, ends up re-running search_code from scratch.")
        llmMistake("Stuffs streamed thinking and tool-call narration into `findings` instead of distilling. The parent LLM hits this as a wall of low-signal text and burns context on the sub-agent's process rather than its conclusions. Symmetric to the attempt_completion 'put long-form in streamed text BEFORE the call' guidance — except here, NO upstream text reaches the parent, so distillation matters more.")
        llmMistake("Calls task_report mid-task before the sub-agent's own brief is satisfied. Unlike the orchestrator (where the user can immediately retry), the parent LLM treats the report as authoritative and may build wrong follow-up steps on top of an incomplete answer. No automatic guard.")
        params {
            required("summary", "string") {
                llmSeesIt("One paragraph: what was done, the overall conclusion, and whether the task succeeded.")
                humanReadable("The TL;DR the parent agent reads first. One paragraph — like the abstract of a paper. State whether the work succeeded, what was actually delivered, and the headline conclusion.")
                whenPresent("Becomes the `## Summary` block at the top of the report markdown and is also used (truncated to 200 chars and prefixed `Task report:`) as the ToolResult.summary that appears in the cross-session log.")
                constraint("must be non-empty — missing parameter rejected before execution with a clear error")
                example("Reviewed AuthService for thread-safety. Found two race conditions in token refresh and one missing volatile field; all are reproducible. Did not patch — fix scope was not in brief.")
                example("Implemented connection pooling for JiraClient. All 12 unit tests pass; integration smoke test against staging returns 200 in <100ms.")
            }
            optional("findings", "string") {
                llmSeesIt("Detailed findings with analysis. Markdown allowed. Include inline code snippets (with file:line) where they support the analysis.")
                humanReadable("The body of the report — the actual analysis, with code excerpts and file:line citations. This is where root causes, evidence, and reasoning go. Markdown is fine and encouraged; the parent LLM renders it inline.")
                whenPresent("Rendered as a `## Findings` block beneath the summary. The parent LLM reads this directly — no other channel exposes the sub-agent's reasoning.")
                whenAbsent("Block omitted entirely. Acceptable for trivial sub-tasks (e.g. \"Files X, Y, Z exist as expected\"), but the parent loses all reasoning and the round-trip's value collapses to a pass/fail bit. STRONGLY recommended for review/analysis sub-agents.")
                example("**Race 1** — `AuthService.kt:142`: `cachedToken` is read outside the synchronized block, so two callers can both see `null` and trigger duplicate refreshes.\\n```\\nif (cachedToken == null) {\\n    synchronized(this) { /* refresh */ }\\n}\\n```\\nFix: hoist the read inside the synchronized block, or mark cachedToken @Volatile.")
            }
            optional("files", "string") {
                llmSeesIt("Newline-separated list of file paths examined or modified. One path per line. The parent uses this to know what has already been read.")
                humanReadable("The breadcrumb trail — every file the sub-agent looked at or changed. Newline-separated, one path per line. The parent agent uses this to skip redundant reads (\"sub-agent already read AuthService.kt — no need to re-open\").")
                whenPresent("Rendered as a `## Files` block. The parent LLM treats this as the authoritative \"already-examined\" list and adjusts its own read plan accordingly.")
                whenAbsent("Block omitted. Parent must guess from the findings text which files were touched — works for small sub-tasks but breaks down at >5 files.")
                example("agent/src/main/kotlin/com/workflow/orchestrator/agent/auth/AuthService.kt\\nagent/src/test/kotlin/com/workflow/orchestrator/agent/auth/AuthServiceTest.kt\\nagent/src/main/kotlin/com/workflow/orchestrator/agent/auth/TokenStore.kt")
            }
            optional("next_steps", "string") {
                llmSeesIt("What the parent agent should do next based on these findings. Be concrete and actionable.")
                humanReadable("The handoff instruction — what should the parent agent do with this report? Concrete and actionable. \"Apply fix X to file Y\" is good; \"consider improving the architecture\" is not.")
                whenPresent("Rendered as a `## Next Steps` block. The parent LLM treats this as the recommended action and often follows it directly. Particularly load-bearing for analyzer/reviewer sub-agents whose whole purpose is to direct the orchestrator's next move.")
                whenAbsent("Block omitted. Parent has to derive next steps from summary + findings — usually fine when the work is fully done, but for reviewer/analyzer roles it forces the parent to re-read the entire findings to plan its next call.")
                example("Apply the @Volatile annotation to cachedToken at AuthService.kt:38 and re-run AuthServiceConcurrencyTest. If it still flakes, also wrap the refresh callback in synchronized(this).")
            }
            optional("issues", "string") {
                llmSeesIt("Blockers, errors, or unresolved questions encountered. Empty if none.")
                humanReadable("Anything that stopped the sub-agent from completing the task — a missing dependency, an ambiguous requirement, a permission error. Empty if everything went smoothly.")
                whenPresent("Rendered as an `## Issues` block. Signals the parent that the report is conditional or partial; the parent should consider re-spawning, asking the user, or adjusting scope.")
                whenAbsent("Block omitted. Default — most successful runs have no issues.")
                example("Could not run integration tests — JIRA_TOKEN env var missing in sub-agent's process. Need parent to either set the var or grant the sub-agent access via shared session env.")
            }
        }
        verdict {
            keep(
                "Architecturally load-bearing for the orchestrator/sub-agent boundary. Without it, sub-agents would terminate with free-form text the parent has to parse, structured findings (especially `files` and `next_steps`) would be lost, and the parent would make worse decisions on next steps — turning every sub-agent round-trip into a net-negative iteration. The deliberate symmetry with attempt_completion (orchestrator-side completion) keeps the system prompt, the loop, and the persona prompts coherent: every agent has exactly one completion tool, gated by allowedWorkers.",
                VerdictSeverity.STRONG,
            )
        }
        related("attempt_completion", Relationship.ALTERNATIVE, "Orchestrator counterpart. attempt_completion ends the user-visible session and drives a CompletionCard in the chat (UI affordances: kind, verify_how, discovery, next_step ghost-text). task_report ends a sub-agent's run and feeds the parent LLM a structured tool result. Mutually exclusive by allowedWorkers — orchestrator can ONLY call attempt_completion; sub-agents can ONLY call task_report.")
        related("agent", Relationship.COMPOSE_WITH, "The agent (SpawnAgentTool) creates the sub-agent that calls task_report. The report's content becomes the agent tool's return value to the orchestrator — task_report is what makes spawn-and-await meaningful.")
        related("task_create", Relationship.SEE_ALSO, "If the sub-agent's `next_steps` describe a multi-step follow-up, the orchestrator can convert each item into a typed task via task_create. Not automatic — task_report's next_steps is plain prose, not a structured task list.")
        related("task_update", Relationship.SEE_ALSO, "When the orchestrator spawns a sub-agent for a specific in-flight task, task_update is the canonical way to mark that task DONE/BLOCKED based on the sub-agent's report.")
        related("ask_followup_question", Relationship.ALTERNATIVE, "Sub-agents cannot pause for user input — they have no UI surface. If a sub-agent is blocked on user judgement, it should populate `issues` with the question and let the orchestrator decide whether to call ask_followup_question.")
        downside("Sub-agent-only — `allowedWorkers = {CODER, REVIEWER, ANALYZER, TOOLER}` means the orchestrator calling this gets a worker-type rejection at schema time. Symmetric to attempt_completion's orchestrator-only restriction.")
        downside("Only `summary` is required at the schema level — `findings`, `files`, `next_steps`, `issues` are all optional. A sub-agent can submit a near-empty report (just `summary: \"done\"`) and still terminate cleanly. The structured contract is intent, not enforcement.")
        downside("Forces structure even on trivial sub-tasks. A 1-iteration explorer that just answers \"does file X exist?\" still has to fill out at minimum a Summary section. Low-cost in tokens, but feels heavy in the persona prompts.")
        downside("Auto-replacement is silent — if a persona config lists `attempt_completion` in its `tools` field, SpawnAgentTool.resolveConfigToolsTiered drops it and injects `task_report` with no warning. Authors only discover the swap when reading the persona's actual tool schema. Consider a build-time lint to flag persona configs that name attempt_completion.")
        downside("Markdown rendered into the parent's tool result is opaque to downstream context-management. ContextManager Stage 1 dedup doesn't recognise these as structured payloads, so multi-sub-agent runs with overlapping `files` lists waste a few hundred tokens on duplicated path mentions.")
        observation("Schema-level enforcement of \"at least one of findings/files/next_steps/issues populated\" would catch the most common sub-agent failure mode (the summary-only return) without changing API surface. Currently relies on persona prompt discipline — see SubagentRunner.COMPLETING_YOUR_TASK_SECTION footer for the in-prompt nudge.")
        observation("`attempt_completion` in a persona's `config.tools:` is silently filtered and replaced by `task_report` (see SpawnAgentTool.resolveConfigToolsTiered). No log warning, no error — the substitution is invisible to authors who don't read the resolution code. The intentional symmetry between the two completion tools is also a hidden contract: dropping attempt_completion from the registry would break sub-agent completion, even though sub-agents never call it directly.")
        flowchart("""
            flowchart TD
                A[Orchestrator calls agent tool] --> B[SpawnAgentTool.resolveConfigToolsTiered]
                B --> B1[Filter attempt_completion from tools]
                B --> B2[Auto-inject task_report]
                B --> C[SubagentRunner spawns sub-agent loop]
                C --> D{Sub-agent works...}
                D --> E[Sub-agent calls task_report]
                E --> F{summary present?}
                F -- no --> X1[ToolResult.error 'missing summary']
                F -- yes --> G[Build markdown report]
                G --> G1[## Summary always]
                G --> G2[## Findings if non-blank]
                G --> G3[## Files if non-blank]
                G --> G4[## Next Steps if non-blank]
                G --> G5[## Issues if non-blank]
                G --> H[ToolResult.completion]
                H --> I[ToolResultType.Completion -> sub-agent loop terminates]
                I --> J[Report markdown returned to orchestrator as tool result]
                J --> K[Orchestrator LLM sees structured report]
        """)
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val summary = params["summary"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing required parameter: summary", "task_report failed: missing summary")

        val findings = params["findings"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val files = params["files"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val nextSteps = params["next_steps"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val issues = params["issues"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val content = buildString {
            appendLine("## Summary")
            appendLine(summary)
            if (findings != null) {
                appendLine()
                appendLine("## Findings")
                appendLine(findings)
            }
            if (files != null) {
                appendLine()
                appendLine("## Files")
                appendLine(files)
            }
            if (nextSteps != null) {
                appendLine()
                appendLine("## Next Steps")
                appendLine(nextSteps)
            }
            if (issues != null) {
                appendLine()
                appendLine("## Issues")
                appendLine(issues)
            }
        }.trimEnd()

        return ToolResult.completion(
            content = content,
            summary = "Task report: ${summary.take(200)}",
            tokenEstimate = content.length / 4
        )
    }
}
