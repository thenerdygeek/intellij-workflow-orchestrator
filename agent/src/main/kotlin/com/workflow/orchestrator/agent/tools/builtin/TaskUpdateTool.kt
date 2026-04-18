package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.loop.TaskStatus
import com.workflow.orchestrator.agent.session.TaskStore
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class TaskUpdateTool(
    private val storeProvider: () -> TaskStore?,
) : AgentTool {

    override val name = "task_update"

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
                description = "New status — one of: pending, in_progress, completed, deleted."
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

        if (updated == null) return ToolResult.error(
            "Task not found: $taskId",
            "task_update failed: Task not found: $taskId",
        )

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
