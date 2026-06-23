package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.loop.TaskStatus
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class TaskUpdateTool(
    private val storeProvider: () -> TaskStore?,
) : AgentTool {

    override val name = "task_update"
    override val isHookExempt = true

    override val description =
        "Update a task's status, content, ownership, or dependencies. One status transition per call. " +
            "Read the current task via task_get before updating to avoid stale overwrites. " +
            "Mark a task `deleted` when it is no longer relevant — stale tasks pollute the context " +
            "and confuse progress tracking. Mark `completed` only when the work is actually finished " +
            "(tests passing, changes verified); for in-progress work keep it as `in_progress`."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "taskId" to ParameterProperty(type = "string", description = "ID of the task to update."),
            "status" to ParameterProperty(
                type = "string",
                description = "New status — one of: pending, in_progress, completed, deleted.",
                enumValues = listOf("pending", "in_progress", "completed", "deleted")
            ),
            "subject" to ParameterProperty(type = "string", description = "New subject (imperative form)."),
            "description" to ParameterProperty(type = "string", description = "New description."),
            "activeForm" to ParameterProperty(type = "string", description = "New present-continuous form."),
            "owner" to ParameterProperty(type = "string", description = "New owner (agent name)."),
            "addBlocks" to ParameterProperty(
                type = "array",
                description = "Task IDs that should be blocked by this task.",
                items = ParameterProperty(type = "string", description = "Task ID"),
            ),
            "addBlockedBy" to ParameterProperty(
                type = "array",
                description = "Task IDs that must complete before this task can start.",
                items = ParameterProperty(type = "string", description = "Task ID"),
            ),
        ),
        required = listOf("taskId"),
    )

    override val allowedWorkers = WorkerType.entries.toSet()

    override fun documentation(): ToolDocumentation = toolDoc("task_update") {
        summary {
            technical("Mutates one existing task in the session-scoped TaskStore: status, subject, description, activeForm, owner, or additive `blocks`/`blockedBy` edges. Status string is uppercased and parsed against TaskStatus (pending/in_progress/completed/deleted). Every mutation runs through TaskStore's DFS cycle check on both edge directions; on success a TaskChanged event is best-effort emitted to the webview.")
            plain("Like editing a sticky note on the agent's to-do board — change its status, retitle it, add a 'this blocks that' link. The board itself prevents you from drawing arrows in a circle, and refuses if you try to update a sticky that doesn't exist.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without task_update, the LLM has no way to mark a task as done, in_progress, or deleted. The fallbacks are both bad: re-create the task with a new id (DAG churn, broken `blockedBy` references, the original task lingers as `pending` forever) or just write 'I finished step 2' as prose (invisible after compaction since the task list rendered into Section 2 of the system prompt is the only progress signal that survives). Multi-step work degrades into stale lists the LLM can't clean up."
        )
        llmMistake("Marks status=completed before verification runs (tests not yet green, changes not yet built). The tool happily accepts it; the description begs the LLM not to, but it routinely does anyway. Symptom: a `completed` task that the next iteration immediately reverts to `in_progress` after discovering a failing test.")
        llmMistake("Tries to introduce a cycle via `addBlockedBy` (e.g. task 3 already blocks task 7, then update task 7 with addBlockedBy=[\"3\"]). TaskStore.CycleException fires mid-flight, the tool returns an error, and the LLM has to figure out the existing edges via task_get before retrying.")
        llmMistake("Calls task_update with a stale or hallucinated id after compaction. Returns 'Task not found' plus a list of every non-deleted task — but only because the recovery hint is hand-built; without it the LLM strands on a bare not-found error.")
        llmMistake("Forgets that `addBlocks`/`addBlockedBy` are ADDITIVE — passes an empty array expecting it to clear the edges, or passes the full desired set expecting replacement. Source uses `(t.blocks + addBlocks).distinct()`; there is no remove or replace API.")
        llmMistake("Sends status=DONE or status=TODO (Claude Code naming) — TaskStatus.valueOf() rejects them and returns 'Invalid status'. Hit when the LLM's pretraining outweighs the in-tool description.")
        params {
            required("taskId", "string") {
                llmSeesIt("ID of the task to update.")
                humanReadable("Which task on the board to edit. Always a short numeric string (\"1\", \"2\", …) minted by task_create — not a UUID. Look it up via task_list if you've forgotten.")
                whenPresent("That task is loaded by id; if absent, the tool returns an error AND lists every non-deleted task in the session so the LLM can self-correct on retry.")
                constraint("must match an existing task id (case-sensitive string compare)")
                example("1")
                example("7")
            }
            optional("status", "string") {
                llmSeesIt("New status — one of: pending, in_progress, completed, deleted.")
                humanReadable("Where this task sits in the workflow: not started, working on it, finished, or no-longer-relevant. Mark `deleted` (don't leave stale items lying around) when the task is obsolete; mark `completed` only when the work is actually verified.")
                whenPresent("String is uppercased and looked up in TaskStatus; on match, the task's status is replaced. On miss (e.g. \"DONE\", \"TODO\", \"BLOCKED\") the call fails with 'Invalid status' and lists the four legal values.")
                whenAbsent("Status is left unchanged — useful when you only want to retitle or add a dependency without claiming progress.")
                constraint("must be one of: pending, in_progress, completed, deleted (case-insensitive)")
                enumValue("pending", "in_progress", "completed", "deleted")
                example("in_progress")
                example("completed")
            }
            optional("subject", "string") {
                llmSeesIt("New subject (imperative form).")
                humanReadable("Replace the task's headline. Keep it imperative and outcome-focused (\"Fix auth bug\", not \"Working on bug\") — the system prompt's Task Management section renders this verbatim, so sloppy subjects pollute every subsequent prompt.")
                whenPresent("Subject field is overwritten with the new string.")
                whenAbsent("Subject is left unchanged.")
                example("Fix OAuth2 token refresh race")
            }
            optional("description", "string") {
                llmSeesIt("New description.")
                humanReadable("Replace the long-form details — acceptance criteria, context the LLM accumulated by the time it scoped the task properly. Useful when an investigation reveals the original framing was wrong.")
                whenPresent("Description field is overwritten with the new string.")
                whenAbsent("Description is left unchanged.")
                example("Token refresh fails when two requests race; need a mutex around the refresh call.")
            }
            optional("activeForm", "string") {
                llmSeesIt("New present-continuous form.")
                humanReadable("The 'doing it now' phrasing shown in the UI while the task is in_progress (e.g. \"Implementing OAuth2 flow\"). Useful when the subject changed and the active form should match.")
                whenPresent("activeForm field is overwritten.")
                whenAbsent("activeForm is left unchanged.")
                example("Refactoring the token refresh to use a mutex")
            }
            optional("owner", "string") {
                llmSeesIt("New owner (agent name).")
                humanReadable("Which agent persona owns this task — typically the sub-agent name when the orchestrator hands work off (e.g. \"code-reviewer\", \"spring-boot-engineer\"). Free-form string; not enforced against the persona registry.")
                whenPresent("owner field is overwritten.")
                whenAbsent("owner is left unchanged.")
                example("spring-boot-engineer")
            }
            optional("addBlocks", "array") {
                llmSeesIt("Task IDs that should be blocked by this task.")
                humanReadable("Add 'this task blocks those' edges — `addBlocks=[\"3\",\"4\"]` means tasks 3 and 4 cannot start until this one is completed. ADDITIVE: the new ids are appended to the existing list and deduped; there is no replace or remove.")
                whenPresent("Each id in the array is appended to the task's `blocks` list (deduped via .distinct()). The combined edge set is then DFS-checked for cycles; on cycle the whole update is rejected and nothing is persisted.")
                whenAbsent("Outgoing block edges are left unchanged.")
                constraint("array of task id strings")
                constraint("additive only — there is no API to clear or replace block edges")
                example("[\"3\"]")
                example("[\"3\", \"4\"]")
            }
            optional("addBlockedBy", "array") {
                llmSeesIt("Task IDs that must complete before this task can start.")
                humanReadable("Add 'those tasks must finish before this one' edges — the inverse direction of addBlocks. Same additive semantics: ids are appended, never replaced.")
                whenPresent("Each id is appended to the task's `blockedBy` list (deduped). Cycle check runs over both edge directions; rejection rolls back the whole update.")
                whenAbsent("Incoming block edges are left unchanged.")
                constraint("array of task id strings")
                constraint("additive only — there is no API to clear or replace blockedBy edges")
                example("[\"1\", \"2\"]")
            }
        }
        verdict {
            keep(
                "Without task_update, multi-step plans can't move past the 'pending' state — the LLM either re-creates tasks (DAG churn, dangling blockedBy refs) or stops tracking progress entirely. Task state is the only progress signal that survives ContextManager compaction (rendered into system prompt Section 2 every rebuild), so losing it costs visibility into long agent runs.",
                VerdictSeverity.STRONG,
            )
        }
        related("task_create", Relationship.COMPOSE_WITH, "Create a task first, then update it as work progresses. Same store, same id space.")
        related("task_get", Relationship.COMPLEMENT, "Read current state before updating — avoids stale overwrites of subject/description that another sub-agent edited. The tool description explicitly nudges this.")
        related("task_list", Relationship.COMPLEMENT, "Cheap way to recover a forgotten id after compaction; result is the same id-table the not-found error builds.")
        downside("Cycle rejection is a hard error mid-flight — the LLM must call task_get on the affected ids and reason about existing edges before retrying. There is no 'preview' or 'force' mode.")
        downside("`addBlocks` / `addBlockedBy` are additive only. There is no remove or replace API: to undo a dependency the LLM must delete the task (status=deleted) or live with the edge.")
        downside("Status changes do not propagate — marking task A as completed does not auto-update tasks that listed A in their `blockedBy`. Dependent tasks remain blocked from the LLM's perspective until it manually flips their status.")
        downside("There is no BLOCKED status in this system (despite the docs/research mentioning one) — the legal values are pending, in_progress, completed, deleted. Blockage is expressed structurally via the edge graph, not via a status flag.")
        downside("EventBus emission is best-effort and silently swallowed if the service is missing (e.g. unit tests). The store is the source of truth; the webview just won't refresh in those environments.")
        downside("No history / no audit trail. updatedAt is bumped, but the previous status / subject / description is overwritten in place and never recoverable from this tool.")
        flowchart("""
            flowchart TD
                A[LLM calls task_update] --> B{taskId provided?}
                B -- no --> X1[Return missing-param error]
                B -- yes --> C{status provided?}
                C -- yes --> D{Parses to TaskStatus?}
                D -- no --> X2[Return invalid-status error]
                D -- yes --> E[Build patch lambda]
                C -- no --> E
                E --> F[TaskStore.updateTask under mutex]
                F --> G{Task exists?}
                G -- no --> X3[Return not-found + list non-deleted tasks]
                G -- yes --> H[Apply patch; bump updatedAt]
                H --> I{Cycle DFS clean?}
                I -- no --> X4[Return cycle error; rollback]
                I -- yes --> J[Persist tasks.json atomically]
                J --> K[Best-effort emit TaskChanged event]
                K --> L[Return success summary]
        """)
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val store = storeProvider()
            ?: return ToolResult.error(
                "Task store is not available in this session.",
                "task_update failed: Task store is not available in this session.",
            )

        val taskId = params["taskId"]?.jsonPrimitive?.content
            ?: return ToolResult.error(
                "Missing required parameter: taskId",
                "task_update failed: Missing required parameter: taskId",
            )

        val statusArg = params["status"]?.jsonPrimitive?.content?.uppercase()
        val parsedStatus: TaskStatus? = statusArg?.let {
            runCatching { TaskStatus.valueOf(it) }.getOrNull()
                ?: return ToolResult.error(
                    "Invalid status '$statusArg'. Expected: pending, in_progress, completed, deleted.",
                    "task_update failed: Invalid status '$statusArg'. Expected: pending, in_progress, completed, deleted.",
                )
        }

        val addBlocks = params["addBlocks"]?.asStringList().orEmpty()
        val addBlockedBy = params["addBlockedBy"]?.asStringList().orEmpty()
        val newSubject = params["subject"]?.jsonPrimitive?.content
        val newDescription = params["description"]?.jsonPrimitive?.content
        val newActiveForm = params["activeForm"]?.jsonPrimitive?.content
        val newOwner = params["owner"]?.jsonPrimitive?.content

        val updated = try {
            store.updateTask(taskId) { t ->
                t.copy(
                    status = parsedStatus ?: t.status,
                    subject = newSubject ?: t.subject,
                    description = newDescription ?: t.description,
                    activeForm = newActiveForm ?: t.activeForm,
                    owner = newOwner ?: t.owner,
                    blocks = (t.blocks + addBlocks).distinct(),
                    blockedBy = (t.blockedBy + addBlockedBy).distinct(),
                )
            }
        } catch (e: TaskStore.CycleException) {
            return ToolResult.error(
                "Update rejected (cycle): ${e.message}",
                "task_update failed: Update rejected (cycle): ${e.message}",
            )
        }

        if (updated == null) {
            // Build a recovery hint: list existing tasks (id → subject) so the LLM
            // can pick the correct id on the next attempt instead of guessing.
            // Ported from Claude Code's TodoWrite miss behavior — bare "not found"
            // routinely strands the model, while a short ID table lets it self-correct.
            val available = store.listTasks().filter { it.status != TaskStatus.DELETED }
            val hint = if (available.isEmpty()) {
                "No tasks exist in this session. Use task_create to add one before calling task_update."
            } else {
                "Available tasks:\n" + available.joinToString("\n") { t ->
                    "  id=\"${t.id}\" status=${t.status.name.lowercase()} — ${t.subject}"
                }
            }
            val message = "Task not found: \"$taskId\".\n$hint"
            return ToolResult.error(message, "task_update failed: task id \"$taskId\" not found")
        }

        // Emit TaskChanged so AgentController can push the updated task to the webview.
        // Best-effort: if the event bus is unavailable (e.g. in unit tests without a Project
        // service container), silently swallow — the store update itself succeeded.
        runCatching {
            project.getService(EventBus::class.java)
                ?.emit(WorkflowEvent.TaskChanged(taskId, isCreate = false))
        }

        return ToolResult(
            content = "Updated task $taskId" + (parsedStatus?.let { " to status=${it.name.lowercase()}" } ?: ""),
            summary = "Updated task: ${updated.subject}",
            tokenEstimate = 20,
        )
    }

    private fun kotlinx.serialization.json.JsonElement.asStringList(): List<String>? =
        runCatching { this.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
}
