package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.session.TaskStore
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class TaskGetTool(
    private val storeProvider: () -> TaskStore?,
) : AgentTool {

    override val name = "task_get"

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

    override val allowedWorkers = WorkerType.values().toSet()

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val store = storeProvider()
            ?: return ToolResult(
                content = "Task store is not available in this session.",
                summary = "task_get failed: store unavailable",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )

        val taskId = params["taskId"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: taskId",
                summary = "task_get failed: missing taskId",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )

        val task = store.getTask(taskId)
            ?: return ToolResult(
                content = "Task not found: $taskId",
                summary = "task_get failed: unknown id",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
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
