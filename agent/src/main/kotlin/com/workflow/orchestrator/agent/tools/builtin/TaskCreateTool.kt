package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.loop.Task
import com.workflow.orchestrator.agent.session.TaskStore
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class TaskCreateTool(
    private val storeProvider: () -> TaskStore?,
) : AgentTool {

    override val name = "task_create"

    override val description =
        "Create a new task in the session's task list. Use for work that requires 3+ distinct steps or " +
            "multi-file changes worth user-visible progress tracking. Skip for trivial single-edit work. " +
            "Create ONE task per call — there is no batch API. Prefer concise outcome-focused subjects " +
            "(\"Fix auth bug\", not \"Read file and identify bug and edit line 42 and run tests\"). " +
            "Use the optional activeForm field for the present-continuous string shown while the task is " +
            "in_progress (e.g. subject=\"Implement OAuth2 flow\", activeForm=\"Implementing OAuth2 flow\")."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "subject" to ParameterProperty(
                type = "string",
                description = "Brief imperative title describing the outcome (e.g. \"Fix auth bug in login flow\")."
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Full details — what needs to be done, any acceptance criteria or context."
            ),
            "activeForm" to ParameterProperty(
                type = "string",
                description = "Optional present-continuous form shown in UI while the task is in_progress " +
                    "(e.g. \"Implementing OAuth2 flow\"). Falls back to subject if omitted."
            ),
        ),
        required = listOf("subject", "description"),
    )

    override val allowedWorkers = WorkerType.entries.toSet()

    override fun documentation(): ToolDocumentation = toolDoc("task_create") {
        summary {
            technical("Append a single typed Task to the session's TaskStore (id, subject, description, optional activeForm). Persisted to tasks.json under the session dir, mutex-guarded, atomic write, emits WorkflowEvent.TaskChanged so the webview's task panel updates immediately. Hook-exempt — bypasses PreToolUse/PostToolUse so it never blocks on user approval.")
            plain("Like sticking a new card on a Kanban TODO column. The agent writes down a piece of work it intends to do so it shows up in the task list — and the note survives even when the chat history gets compressed for length.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without task_create, the LLM falls back to tracking multi-step plans in plain-text scratch (numbered lists in its own assistant turns). Those scratch lists evaporate the moment ContextManager compacts — Stage 2 truncation deletes middle turns, Stage 3 LLM-summarisation paraphrases them — and the agent silently forgets which steps remain. TaskStore content is rendered into system-prompt Section 2 on every prompt rebuild, so it survives compaction byte-for-byte. The cost of dropping this tool is mid-session amnesia on any task with 3+ steps, which is most of them."
        )
        llmMistake("Creates a task for trivial single-edit work (\"Fix typo on line 42\"). The description tells it to skip these but models still over-track. Net cost: a tasks.json line, a TaskChanged event, and one extra slot in Section 2 on every subsequent prompt — small but compounding.")
        llmMistake("Batches multiple actions into one subject (\"Read the file, find the bug, fix it, run tests\"). The intended pattern is one task per outcome — a single task should describe one user-visible deliverable, not a sequence. Outcomes also make `activeForm` natural; multi-action subjects don't.")
        llmMistake("Forgets to call task_update after finishing — task stays at status=pending forever, polluting Section 2 of the system prompt and confusing later iterations of the loop. The TaskStore has no auto-completion heuristic.")
        llmMistake("Tries to pass `blocks` or `blockedBy` to task_create — the schema does not accept dependency edges at creation time. Edges are added later via task_update's `addBlocks` / `addBlockedBy` arrays. The tool will silently ignore the unknown fields and create a task with no edges.")
        llmMistake("Calls task_create after every tool result instead of front-loading the plan. The most useful pattern is: think → enumerate the work → task_create N times once → execute. Trickle-creating tasks mid-execution defeats the point of having a plan visible in Section 2.")
        params {
            required("subject", "string") {
                llmSeesIt("Brief imperative title describing the outcome (e.g. \"Fix auth bug in login flow\").")
                humanReadable("The card's headline — what someone reading the task list should see at a glance. Imperative voice (\"Fix X\", not \"Fixing X\" or \"X is broken\"); outcome-focused, not step-focused.")
                whenPresent("Stored as `Task.subject`, displayed in task_list output and rendered into system-prompt Section 2 on every compaction. Becomes the human-readable handle for this task.")
                constraint("must not be blank — whitespace-only values return `Missing required parameter: subject`")
                example("Fix auth bug in login flow")
                example("Implement OAuth2 flow")
                example("Migrate UserService to coroutines")
            }
            required("description", "string") {
                llmSeesIt("Full details — what needs to be done, any acceptance criteria or context.")
                humanReadable("The card's back side — the longer explanation: which files, what acceptance criteria look like, any links or notes. Where the LLM writes down everything it would otherwise forget after compaction.")
                whenPresent("Stored as `Task.description`. Visible via task_get; not rendered into Section 2 (only id/subject/status appear there) so size is not penalised on every prompt.")
                constraint("must not be blank — whitespace-only values return `Missing required parameter: description`")
                example("Add a 401-retry interceptor to AuthClient. Acceptance: integration test confirms a stale token triggers a single refresh + retry. Touch AuthClient.kt and AuthClientTest.kt.")
            }
            optional("activeForm", "string") {
                llmSeesIt("Optional present-continuous form shown in UI while the task is in_progress (e.g. \"Implementing OAuth2 flow\"). Falls back to subject if omitted.")
                humanReadable("How the task reads while it's actively being worked on — the verb in -ing form. Cosmetic but it makes the task panel feel like a status indicator (\"Implementing OAuth2 flow…\") instead of an imperative checklist.")
                whenPresent("Stored as `Task.activeForm` and shown in the webview when status flips to in_progress. The original imperative `subject` continues to drive task_list and Section 2 rendering.")
                whenAbsent("Defaults to null — UI falls back to displaying `subject` verbatim for in_progress tasks.")
                example("Implementing OAuth2 flow")
                example("Migrating UserService to coroutines")
            }
        }
        verdict {
            keep(
                "STRONG keep. The TaskStore survives ContextManager compaction (Stages 1-3 all respect it; tasks render into system-prompt Section 2 on every rebuild) — plain-text TODOs in assistant turns do not. Without task_create, any task longer than one compaction window loses its plan, and we'd be reinventing the same persistent-task pattern that Claude Code's TodoWrite, Cline's task list, and every comparable agent ship out of the box. The cost is tiny: ~150 lines of code, a small tasks.json, and a few dozen tokens of system-prompt overhead per active task. Drop only if we replace it with a richer task system, never with nothing.",
                VerdictSeverity.STRONG,
            )
        }
        related("task_update", Relationship.COMPLEMENT, "Modify status, content, or DAG edges (`addBlocks`/`addBlockedBy`) after creation. The only way to add dependency edges — task_create does not accept them.")
        related("task_list", Relationship.COMPLEMENT, "Cheap read of all tasks (id, subject, status). Use to confirm a create succeeded or to remind the LLM of the current plan.")
        related("task_get", Relationship.COMPLEMENT, "Full detail for a single task including blocks/blockedBy edges and timestamps. Use before task_update to avoid stale-overwrite bugs.")
        related("new_task", Relationship.ALTERNATIVE, "Use instead when the goal is a session-level handoff (start a fresh agent loop with a structured context blob), not lightweight in-session bookkeeping.")
        downside("DAG cycle errors raised by `TaskStore.checkNoCycles` are detected on update, not create — task_create itself has no edges to cycle, so the failure shifts entirely to task_update. Means malformed plans get caught late, not at planning time.")
        downside("Persistence survives compaction, but the LLM's MEMORY of why each task exists does not. After a Stage-3 summarisation pass, tasks may still be sitting at status=pending with descriptions the LLM has effectively forgotten — Section 2 lists ids and subjects but not full descriptions, so context is shallow until task_get is called.")
        downside("Token cost grows linearly with task count: every active (non-deleted) task gets a Section 2 line on every prompt rebuild. A long-running session with 50+ stale tasks bloats every iteration's input.")
        downside("Hook-exempt by design — PreToolUse hooks cannot gate task creation. Any project policy that wants \"no task without ticket id in subject\" has to live elsewhere (server-side validation isn't a thing here).")
        downside("Best-effort EventBus emit: if `project.getService(EventBus::class.java)` returns null (test harness without service container), the webview never learns the task was created. The store update still succeeds — the divergence is silent.")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val store = storeProvider()
            ?: return ToolResult.error(
                "Task store is not available in this session.",
                "task_create failed: store unavailable",
            )

        val subject = params["subject"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error(
                "Missing required parameter: subject",
                "task_create failed: missing subject",
            )

        val description = params["description"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error(
                "Missing required parameter: description",
                "task_create failed: missing description",
            )

        val activeForm = params["activeForm"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val task = Task(
            id = store.nextId(),
            subject = subject,
            description = description,
            activeForm = activeForm,
        )
        store.addTask(task)

        // Emit TaskChanged so AgentController can push the new task to the webview.
        // Best-effort: if the event bus is unavailable (e.g. in unit tests without a Project
        // service container), silently swallow — the store update itself succeeded.
        runCatching {
            project.getService(EventBus::class.java)
                ?.emit(WorkflowEvent.TaskChanged(task.id, isCreate = true))
        }

        return ToolResult(
            // Format surfaces the id in a way the LLM will verbatim-reuse on task_update.
            // Quotes around the id make copy/paste unambiguous and resistant to tokenizer
            // drift (which previously let models reflexively emit "1" after a UUID).
            content = "Created task id=\"${task.id}\" — $subject. " +
                "Use id=\"${task.id}\" on subsequent task_update / task_get calls.",
            summary = "Created task ${task.id}: $subject",
            tokenEstimate = 20,
        )
    }
}
