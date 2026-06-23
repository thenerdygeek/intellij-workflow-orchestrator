package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.session.TaskStore
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

class TaskGetTool(
    private val storeProvider: () -> TaskStore?,
) : AgentTool {

    override val name = "task_get"
    override val isHookExempt = true

    override val description =
        "Retrieve the full details of a single task — subject, description, status, activeForm, owner, " +
            "blocks, blockedBy, timestamps. Use when you need context beyond what task_list provides. " +
            "Verify `blockedBy` is empty before starting work on a pending task."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "taskId" to ParameterProperty(type = "string", description = "ID of the task to retrieve.")
        ),
        required = listOf("taskId"),
    )

    override val allowedWorkers = WorkerType.entries.toSet()

    override fun documentation(): ToolDocumentation = toolDoc("task_get") {
        summary {
            technical(
                "Single-task fetch from the session's TaskStore by id. Returns the full record — subject, " +
                    "description body, status, activeForm, owner, blocks/blockedBy edge ids, and timestamps. " +
                    "Dirty-read (no mutex) — safe because the agent loop is single-coroutine. Hook-exempt: bypasses " +
                    "PreToolUse/PostToolUse like the other task_* tools."
            )
            plain(
                "Like opening a single Kanban card to see its full content — vs glancing at the column where " +
                    "you only see the headline. The task_list view (and the per-turn `# Tasks` summary the agent " +
                    "already gets) shows you the title and status; task_get is how you actually read the description, " +
                    "see what blocks what, and know when the task was created or last updated."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without task_get, the LLM has only two reduced views of any task: (a) the always-on " +
                "EnvironmentDetailsBuilder.appendTasks block, which renders ONLY `[id] [status] subject` and drops " +
                "description/activeForm/owner/blocks/blockedBy/timestamps entirely, and (b) task_list, which adds " +
                "owner and blockedBy ids but still has no description body, no `blocks` (only blockedBy), and no " +
                "timestamps. Crucially, the full task body lives only in task_get — there is no other path. The LLM " +
                "would therefore lose access to the description it wrote at task_create time the moment ContextManager " +
                "compacts the original tool call result, leading to the same mid-session amnesia task_create exists " +
                "to prevent."
        )
        llmMistake(
            "Calls task_get when the per-turn `# Tasks` block in environment_details is already enough — only " +
                "id+status+subject was needed. Costs an iteration to fetch a description the LLM never reads. " +
                "Pattern: ask whether you need the description body or the dependency graph BEFORE calling; " +
                "subject + status alone are already in front of you."
        )
        llmMistake(
            "Passes a stale id from before a task was deleted (or before a different session's snapshot was " +
                "loaded). Returns `Task not found: <id>` and burns an iteration. The task list in environment_details " +
                "is the source of truth for current ids; copy from there, not from older assistant turns."
        )
        llmMistake(
            "Expects task_get to return the resolved BLOCKED status of dependents — it does not. The output is " +
                "the raw edge graph (`blockedBy: 1,2`), not whether tasks 1 and 2 are themselves still pending. To " +
                "answer 'is this task ready to start?' the LLM must task_get each id in `blockedBy` (or read the " +
                "environment_details `# Tasks` block, which already shows their status)."
        )
        llmMistake(
            "Calls task_get in a loop over every task to 'survey progress', recreating what task_list and the " +
                "environment_details summary already provide for free. Per-task fetch is for deep reads of one card, " +
                "not for scanning many. If you find yourself calling task_get N times in a row, you wanted task_list."
        )
        llmMistake(
            "Confuses `taskId` with task ordinal. Ids are short numeric strings allocated by `TaskStore.nextId()` " +
                "as `max(existing) + 1` — they are stable across deletes (id 3 is never reused as id 3 even if the " +
                "original is removed). Treating them as 'position in the list' breaks after any delete."
        )
        params {
            required("taskId", "string") {
                llmSeesIt("ID of the task to retrieve.")
                humanReadable(
                    "The card's id — a short numeric string like \"3\" allocated when task_create ran. Visible " +
                        "in environment_details `# Tasks` block, in task_list output, and in task_create's return " +
                        "content (which prints `id=\"3\"` quoted to make copy/paste unambiguous)."
                )
                whenPresent(
                    "TaskStore.getTask(id) is called; if the task exists, all fields are formatted into a " +
                        "key-value block and returned. If not, an error 'Task not found: <id>' is returned."
                )
                constraint(
                    "must match an existing, non-deleted task id — TaskStore has no soft-deleted-tombstone view, " +
                        "deleted ids return Task not found"
                )
                constraint(
                    "case-sensitive string match — though ids are numeric in practice, they're stored as String"
                )
                example("1")
                example("17")
                example("42")
            }
        }
        verdict {
            keep(
                "Genuine keep — and unlike task_list, NOT redundant with the system-prompt task render. " +
                    "EnvironmentDetailsBuilder.appendTasks emits only `[id] [status] subject` per task; task_get is " +
                    "the only path to the description body the LLM wrote at task_create time, the `blocks` edge list " +
                    "(task_list only shows `blockedBy`), `activeForm`, and timestamps. Inlining the full description " +
                    "into Section 2 would balloon every prompt linearly with task count and defeat the per-turn " +
                    "render's purpose; an on-demand fetch is the right shape. Drop only if we redesign the task model " +
                    "to elide descriptions entirely.",
                VerdictSeverity.STRONG,
            )
        }
        observation(
            "NOT redundant with EnvironmentDetailsBuilder.appendTasks the way task_list is. The render " +
                "deliberately omits description/blocks/activeForm/timestamps to keep Section 2 cheap on every " +
                "prompt rebuild. task_get carries those fields on demand — the only path to the full task body. " +
                "(Compare with task_list, where merging owner+blockedBy into the render would obviate the tool.)"
        )
        observation(
            "Hook-exempt (bypasses PreToolUse/PostToolUse) per agent/CLAUDE.md \"TaskStore + Task Tools\" — " +
                "internal bookkeeping should never trigger an approval gate. Same status as task_create / " +
                "task_update / task_list."
        )
        observation(
            "Output includes both `blocks` and `blockedBy` edges — task_list only carries `blockedBy`. If you " +
                "need to know what a task BLOCKS (downstream dependents), task_get is the only tool that surfaces it."
        )
        removableParam(
            "Parameter is named `taskId` but the description says \"ID of the task\". Aligning to `id` would match " +
                "task_update's `id` field and the form returned by task_create (`id=\"3\"`). Low-priority; " +
                "renaming would break any hand-coded callers and the LLM is robust to either."
        )
        related(
            "task_create",
            Relationship.COMPLEMENT,
            "task_create returns the new id (and prints it quoted in its content); task_get is how you fetch " +
                "the full record back later when context has compacted away the original tool call."
        )
        related(
            "task_update",
            Relationship.COMPLEMENT,
            "task_get the current task BEFORE task_update to avoid stale-overwrite bugs — especially when " +
                "modifying description or edge lists, where the patch needs to know the existing value."
        )
        related(
            "task_list",
            Relationship.ALTERNATIVE,
            "Use task_list when you only need id+status+subject across all tasks; use task_get when you need " +
                "the description body, the `blocks` edges, or timestamps for ONE task. task_list omits all of those."
        )
        downside(
            "Returns a hard error (`Task not found: <id>`) for unknown ids rather than an empty/null result — " +
                "noisy in tool history when the LLM guesses ids. The cleaner failure mode would be a soft miss " +
                "with a 'did you mean: 1, 2, 3?' hint, but the tool is too low-volume to warrant the change."
        )
        downside(
            "Output format is plain key-value lines (`id: 3\\nsubject: …`), not JSON. Easy for the LLM to read " +
                "but not machine-parseable for downstream tooling. The other task_* tools share this informal format."
        )
        downside(
            "`blocks` and `blockedBy` are rendered as comma-joined id lists (`blocks: 4,5,6`) with no subjects. " +
                "To know what task 4 actually IS, the LLM has to call task_get on each — or read it off the " +
                "environment_details `# Tasks` block, which is the more efficient path. Same lossy-edge-rendering " +
                "downside that task_list has."
        )
        downside(
            "No version/etag — concurrent updates from another coroutine (rare in practice; the agent loop is " +
                "single-coroutine but UI-driven mutations exist) could interleave between a task_get and a follow-up " +
                "task_update, silently overwriting changes. TaskStore's mutex protects the write but not the " +
                "read-then-write pattern across two tool calls."
        )
        flowchart(
            """
            flowchart TD
                A[LLM calls task_get with taskId] --> B{TaskStore attached?}
                B -- no --> X1[Return error: store unavailable]
                B -- yes --> C{taskId param present?}
                C -- no --> X2[Return error: missing taskId]
                C -- yes --> D[store.getTask id dirty-read]
                D --> E{Task found?}
                E -- no --> X3[Return error: Task not found]
                E -- yes --> F[Format key-value block]
                F --> G[Append description body]
                G --> H[Return content + summary 'task_get: subject']
            """
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val store = storeProvider()
            ?: return ToolResult.error(
                "Task store is not available in this session.",
                "task_get failed: store unavailable",
            )

        val taskId = params["taskId"]?.jsonPrimitive?.content
            ?: return ToolResult.error(
                "Missing required parameter: taskId",
                "task_get failed: missing taskId",
            )

        val task = store.getTask(taskId)
            ?: return ToolResult.error(
                "Task not found: $taskId",
                "task_get failed: unknown id",
            )

        val body = buildString {
            appendLine("id: ${task.id}")
            appendLine("subject: ${task.subject}")
            appendLine("status: ${task.status.name.lowercase()}")
            task.activeForm?.let { appendLine("activeForm: $it") }
            task.owner?.let { appendLine("owner: $it") }
            if (task.blocks.isNotEmpty()) appendLine("blocks: ${task.blocks.joinToString(",")}")
            if (task.blockedBy.isNotEmpty()) appendLine("blockedBy: ${task.blockedBy.joinToString(",")}")
            appendLine()
            appendLine("description:")
            appendLine(task.description)
        }

        return ToolResult(
            content = body,
            summary = "task_get: ${task.subject}",
            tokenEstimate = body.length / 4,
            isError = false,
        )
    }
}
