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
 * Session handoff tool -- faithful port of Cline's new_task.
 *
 * The LLM calls this tool to propose a handoff to a fresh session with a
 * structured context summary. The proposal is shown to the user as a preview
 * card; the user chooses whether to fork ("Start fresh session") or stay
 * ("Keep chatting"). The summary preserves essential state so the new session
 * can continue without losing critical information.
 *
 * From Cline (src/core/prompts/system-prompt/tools/new_task.ts):
 * "Request to create a new task with preloaded context covering the
 * conversation with the user up to this point and key information for
 * continuing with the new task."
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/prompts/system-prompt/tools/new_task.ts">Cline source</a>
 */
class NewTaskTool : AgentTool {

    override val name = "new_task"

    // Faithfully ported from Cline's new_task tool description
    override val description = "Request to create a new task with preloaded context covering the conversation " +
        "with the user up to this point and key information for continuing with the new task. With this tool, " +
        "you will create a detailed summary of the conversation so far, paying close attention to the user's " +
        "explicit requests and your previous actions, with a focus on the most relevant information required " +
        "for the new task.\n" +
        "Among other important areas of focus, this summary should be thorough in capturing technical details, " +
        "code patterns, and architectural decisions that would be essential for continuing with the new task. " +
        "The user will be presented with a preview of your generated context and can choose to create a new " +
        "task or keep chatting in the current conversation. The user may choose to start a new task at any point."

    // Faithfully ported from Cline's parameter description
    override val parameters = FunctionParameters(
        properties = mapOf(
            "context" to ParameterProperty(
                type = "string",
                description = "The context to preload the new task with. If applicable based on the " +
                    "current task, this should include:\n" +
                    "  1. Current Work: Describe in detail what was being worked on prior to this request " +
                    "to create a new task. Pay special attention to the more recent messages / conversation.\n" +
                    "  2. Key Technical Concepts: List all important technical concepts, technologies, " +
                    "coding conventions, and frameworks discussed, which might be relevant for the new task.\n" +
                    "  3. Relevant Files and Code: If applicable, enumerate specific files and code sections " +
                    "examined, modified, or created for the task continuation. Pay special attention to the " +
                    "most recent messages and changes.\n" +
                    "  4. Problem Solving: Document problems solved thus far and any ongoing troubleshooting " +
                    "efforts.\n" +
                    "  5. Pending Tasks and Next Steps: Outline all pending tasks that you have explicitly " +
                    "been asked to work on, as well as list the next steps you will take for all outstanding " +
                    "work, if applicable. Include code snippets where they add clarity. For any next steps, " +
                    "include direct quotes from the most recent conversation showing exactly what task you " +
                    "were working on and where you left off. This should be verbatim to ensure there's no " +
                    "information loss in context between tasks. It's important to be detailed here."
            )
        ),
        required = listOf("context")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override fun documentation(): ToolDocumentation = toolDoc("new_task") {
        summary {
            technical(
                "Session handoff: returns ToolResultType.SessionHandoff(context) which the AgentLoop surfaces as LoopResult.SessionHandoff; AgentController then closes the current ContextManager + sessionApprovalStore and calls AgentService.startHandoffSession(handoffContext) which spawns a NEW session whose first user message is `Continue from the previous session. Here is the preserved context:\\n\\n<context>`. The current session is marked COMPLETED. The LLM is expected to author a 5-section structured summary (Current Work / Key Technical Concepts / Relevant Files / Problem Solving / Pending Tasks) so the fresh session has enough context to continue. Cline-faithful (`new_task.ts`); orchestrator-only via WorkerType.ORCHESTRATOR."
            )
            plain(
                "Like passing a baton to a fresh runner. The current agent has been working for so long that the context window is filling up with old chat — instead of letting it choke, the agent writes a detailed handoff brief, hands it to a fresh agent, and that new agent picks up where the old one left off. The fresh runner remembers nothing except what the brief tells it, so the brief had better be thorough."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without new_task, when the conversation grows past the 88% input-token threshold ContextManager runs its single-stage CC-style compactor: an unconditional dedup pre-pass collapses repeated file reads to placeholders, then an LLM call summarizes the oldest ~70% of messages into a single assistant turn. That keeps the loop alive but the conversation slowly degrades to summary-of-summaries — file reads collapse into placeholders, prior assistant reasoning disappears, and the LLM increasingly works against a fuzzy recollection of its own earlier decisions. new_task lets the LLM voluntarily declare 'this conversation has gotten too long, here is a clean brief, restart' which is sometimes a real improvement over chained summaries — especially after a long debugging-then-implementing arc where the early exploration is no longer useful but the closing tasks are clear. Removing it means accepting the summary-of-summaries decay as the only path past context exhaustion."
        )
        llmMistake(
            "Confuses new_task with task_create. The names sound similar — both contain 'task' — but task_create adds a Kanban-style card to the typed task store INSIDE the current session, while new_task ENDS the current session and starts a fresh one. Calling new_task when the LLM meant task_create silently nukes all in-session memory (file reads, reasoning, active skills) and forces a restart. The description's first sentence ('Request to create a new task with preloaded context') reinforces the confusion."
        )
        llmMistake(
            "Uses new_task for trivial sub-tasks where `agent` (subagent dispatch) would be the right tool. new_task ends the current session — there is no return path. If the goal was 'spawn a worker to do X and report back', the right tool is `agent(description=..., prompt=...)`, which keeps the orchestrator alive and gives it the worker's task_report. new_task is for when the orchestrator itself wants to throw away its own context."
        )
        llmMistake(
            "Writes a vague handoff context that sets the new session up to fail. The 5-section template (Current Work / Key Technical Concepts / Relevant Files / Problem Solving / Pending Tasks) is in the parameter description for a reason — the new session has no memory of the previous one except this string. A 200-word 'we were debugging the auth flow' brief leaves the new session re-discovering everything the old one already knew."
        )
        llmMistake(
            "Calls new_task as a substitute for attempt_completion. Both end something, but new_task starts a fresh session with the handoff context, while attempt_completion hands the result to the user and ends the loop. If the work is actually done, new_task wastes the user's tokens by spinning up a fresh agent that immediately re-discovers the answer is already there."
        )
        llmMistake(
            "Calls new_task too early — before ContextManager's compaction has even fired. Compaction usually buys enough headroom for several more iterations; new_task should be reserved for the case where the LLM judges the *quality* of context, not just its *length*, has degraded past usefulness. Calling it preemptively burns a session-start tax for no benefit."
        )
        params {
            required("context", "string") {
                llmSeesIt(
                    "The context to preload the new task with. If applicable based on the current task, this should include:\n" +
                        "  1. Current Work: Describe in detail what was being worked on prior to this request to create a new task. Pay special attention to the more recent messages / conversation.\n" +
                        "  2. Key Technical Concepts: List all important technical concepts, technologies, coding conventions, and frameworks discussed, which might be relevant for the new task.\n" +
                        "  3. Relevant Files and Code: If applicable, enumerate specific files and code sections examined, modified, or created for the task continuation. Pay special attention to the most recent messages and changes.\n" +
                        "  4. Problem Solving: Document problems solved thus far and any ongoing troubleshooting efforts.\n" +
                        "  5. Pending Tasks and Next Steps: Outline all pending tasks that you have explicitly been asked to work on, as well as list the next steps you will take for all outstanding work, if applicable. Include code snippets where they add clarity. For any next steps, include direct quotes from the most recent conversation showing exactly what task you were working on and where you left off. This should be verbatim to ensure there's no information loss in context between tasks. It's important to be detailed here."
                )
                humanReadable(
                    "The handoff brief itself — every fact the new agent will need to continue. Think of it as the email you'd send to your replacement on your last day: what's the project, what tools/conventions matter, which files are in play, what's been figured out, and what's left to do. The new agent's only memory of the prior session is what's in this string."
                )
                whenPresent(
                    "After validation (must be non-blank), the tool returns ToolResult.sessionHandoff with the context as both the content and the typed handoff payload. AgentLoop sees ToolResultType.SessionHandoff, exits with LoopResult.SessionHandoff, the previous session is finalised as COMPLETED, ContextManager + SessionApprovalStore are cleared, and AgentService.startHandoffSession spawns a fresh session whose first user message is `Continue from the previous session. Here is the preserved context:\\n\\n<context>`."
                )
                constraint("must be non-blank — empty/whitespace context returns an error nudging the LLM to write the 5-section summary")
                constraint("no length cap is enforced server-side, but each char ~= 0.25 input tokens for the new session's first turn — overlong contexts eat the very budget the handoff was supposed to recover")
                example(
                    "## Current Work\nDebugging a flaky auth integration test in :handover module — narrowed to a race between the JWT refresh coroutine and the cleanup hook. Reproduced 3/10 runs by injecting a 50ms delay.\n\n## Key Technical Concepts\nKotlin coroutines, JBR 21, MockK relaxed mocks, JBCefBrowser dispose ordering.\n\n## Relevant Files\n- handover/src/main/kotlin/.../JwtRefreshScheduler.kt (line 88: race window)\n- handover/src/test/kotlin/.../JwtRefreshSchedulerTest.kt\n\n## Problem Solving\nConfirmed root cause is shared SupervisorJob scope. Tried switching to per-test scope — broke 2 unrelated tests.\n\n## Pending Tasks\n- Implement scoped fix without breaking the 2 unrelated tests\n- Re-run :handover:test 20x to verify"
                )
            }
        }
        verdict {
            keep(
                "NORMAL keep, narrowly. There IS a real failure mode that ContextManager's single-stage summarizer can't recover from: long arcs where the early conversation (exploration, false starts, abandoned approaches) is no longer useful but is now dominating the summary chain. A clean restart with a focused brief is sometimes genuinely better than yet another summary-of-summaries pass. Cline ships this for the same reason. The bar for keeping it is low because the implementation is small (~100 lines) and orchestrator-only.",
                VerdictSeverity.NORMAL,
            )
            drop(
                "WEAK drop case worth flagging. In this codebase, ContextManager's LLM summarization (CC-style, single-stage as of 2026-05-17) is more aggressive than Cline's (Cline doesn't do LLM-summary at all), so the 'context degraded past usefulness' threshold is genuinely rarer here. And the typed task store + memory system already cover the 'preserve key info across long sessions' use case more cleanly than a handoff string. There is a non-trivial argument that new_task is a vestige of the Cline port that no longer earns its slot now that compaction is more capable. Drop case is real but not strong — until we have telemetry on how often LLMs actually invoke new_task in practice, keep wins on inertia.",
                VerdictSeverity.WEAK,
            )
        }
        related(
            "task_create",
            Relationship.SEE_ALSO,
            "CONTRAST — same name root, opposite scope. task_create adds a Kanban card to the IN-session task store; new_task ENDS the session and starts a fresh one. The naming collision is the single biggest source of LLM confusion with this tool — see commonLLMMistakes.",
        )
        related(
            "attempt_completion",
            Relationship.SEE_ALSO,
            "CONTRAST — both end the current loop, but attempt_completion hands the result to the user (terminal) while new_task hands a brief to a fresh session (continuation). If the work is done, use attempt_completion; if you're hitting context limits and have more to do, use new_task.",
        )
        related(
            "agent",
            Relationship.ALTERNATIVE,
            "Use `agent` instead when you want to delegate a sub-task without ending the current session — the orchestrator stays alive, gets the worker's task_report, and continues. new_task is for when the orchestrator itself wants to start over.",
        )
        related(
            "read_file",
            Relationship.COMPOSE_WITH,
            "After a handoff, the fresh session typically read_files the key files listed in the 'Relevant Files' section — the brief contains pointers, but the actual code has to be re-read into the new context.",
        )
        related(
            "use_skill",
            Relationship.COMPLEMENT,
            "Skills survive ContextManager compaction (they're re-injected into the system prompt on every rebuild) — they do NOT survive new_task (fresh session, fresh system prompt). The handoff context should explicitly mention any active skill the new session should re-activate.",
        )
        downside(
            "The new session loses ALL in-session memory: every file the previous session read, every prior assistant turn, every active skill, every user steering message, every tool result. The handoff context is the only carryover — anything not written into the brief is gone.",
        )
        downside(
            "Active skills do NOT carry across the handoff. Compaction preserves skills (re-injected into the rebuilt system prompt); handoff does not. If the previous session was operating under tdd / systematic-debugging / etc., the brief must explicitly say so or the new session will operate under default rules.",
        )
        downside(
            "The typed task store (TaskStore + tasks.json) is per-session — tasks created via task_create in the previous session do NOT carry forward. The brief's 'Pending Tasks' section is the only way to surface them, and even then they're prose not structured cards.",
        )
        downside(
            "Session approvals are reset (sessionApprovalStore.clear()): tools the user had granted ALLOW_FOR_SESSION in the previous session must be re-approved in the fresh one. For long autonomous runs this means the user gets re-prompted for the same tools.",
        )
        downside(
            "No undo. Once new_task fires, the previous session is finalised as COMPLETED and the fresh session starts. The user can navigate back to the prior session in History, but the LLM can't 'merge' or 'unwind'. If the brief was bad, the only recovery is another handoff with a better brief.",
        )
        downside(
            "Costs a full system-prompt rebuild for the new session — IDE-aware capabilities, tool definitions, memory injection, skill discovery all re-run. For a long session that has just compacted successfully, this can be MORE expensive than the next compaction would have been.",
        )
        downside(
            "Single context parameter — no metadata for what skill was active, what TaskStore items were open, what files were 'pinned' for re-read. The LLM has to hand-encode all of this in prose.",
        )
        flowchart(
            """
            flowchart TD
                A[LLM calls new_task] --> B{context provided?}
                B -- no --> X1[Error: missing context]
                B -- yes --> C{context blank?}
                C -- yes --> X2[Error: context must include 5-section summary]
                C -- no --> D[ToolResult.sessionHandoff context=context]
                D --> E[AgentLoop: ToolResultType.SessionHandoff]
                E --> F[Loop returns LoopResult.SessionHandoff]
                F --> G[AgentController: previous session marked COMPLETED]
                G --> H[Clear ContextManager + sessionApprovalStore]
                H --> I[AgentService.startHandoffSession]
                I --> J[Fresh session: first user message =\n'Continue from previous session...' + context]
                J --> K[New AgentLoop runs in fresh session]
            """,
        )
        observation(
            "Naming collision with task_create is the single largest source of LLM mistakes with this tool. The Cline-faithful description ('Request to create a new task with preloaded context...') reinforces the confusion. Worth considering a one-sentence disambiguation prefix in the LLM-facing description: 'NOT for adding a TODO item — that is task_create. This tool ENDS the current session and starts a fresh one.'",
        )
        mergeOpportunity(
            "Worth instrumenting how often new_task fires in practice. If the rate is < ~1 per 100 long sessions (where 'long' = compaction has fired at least once), the implementation cost (~100 lines, separate tool name, naming collision with task_create) may not be earning its keep — the niche is narrow. If the rate is meaningful, keep stands.",
        )
        narrative("new_task")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val context = params["context"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: context",
                summary = "new_task failed: missing context",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (context.isBlank()) {
            return ToolResult(
                content = "Error: context parameter must not be empty. Provide a detailed summary " +
                    "including Current Work, Key Technical Concepts, Relevant Files, Problem Solving, " +
                    "and Pending Tasks.",
                summary = "new_task failed: empty context",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return ToolResult.handoffProposed(
            content = context,
            summary = "Session handoff: context preserved (${context.length} chars)",
            tokenEstimate = context.length / 4,
            context = context
        )
    }
}
