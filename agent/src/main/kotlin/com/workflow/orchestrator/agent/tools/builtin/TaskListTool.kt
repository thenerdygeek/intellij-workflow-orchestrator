package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.session.TaskStore
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject

class TaskListTool(
    private val storeProvider: () -> TaskStore?,
) : AgentTool {

    override val name = "task_list"

    override val description =
        "List all tasks in the session with minimal fields (id, subject, status, owner, blockedBy). " +
            "Does NOT include description or metadata — use task_get with an id for those. Prefer " +
            "working on tasks in id order (lowest first) when multiple are available; earlier tasks " +
            "often set up context for later ones. Tasks with non-empty blockedBy cannot start until " +
            "those dependencies are completed."

    override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())

    override val allowedWorkers = WorkerType.entries.toSet()

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val store = storeProvider()
            ?: return ToolResult.error(
                "Task store is not available in this session.",
                "task_list failed: store unavailable",
            )

        val tasks = store.listTasks()
        if (tasks.isEmpty()) {
            return ToolResult(
                content = "No tasks in this session.",
                summary = "No tasks",
                tokenEstimate = 5,
                isError = false,
            )
        }

        val rendered = tasks.joinToString("\n") { t ->
            val owner = t.owner?.let { " [owner: $it]" }.orEmpty()
            val blockedBy = if (t.blockedBy.isEmpty()) "" else " [blockedBy: ${t.blockedBy.joinToString(",")}]"
            "- ${t.id}  ${t.status.name.lowercase()}  ${t.subject}$owner$blockedBy"
        }

        return ToolResult(
            content = rendered,
            summary = "${tasks.size} tasks",
            tokenEstimate = rendered.length / 4,
            isError = false,
        )
    }
}
