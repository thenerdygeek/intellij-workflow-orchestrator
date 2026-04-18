package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.loop.Task
import com.workflow.orchestrator.agent.session.TaskStore
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

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
            id = UUID.randomUUID().toString(),
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
            content = "Created task ${task.id}: $subject",
            summary = "Created task: $subject",
            tokenEstimate = 20,
        )
    }
}
