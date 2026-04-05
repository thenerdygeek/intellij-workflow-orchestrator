package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class AttemptCompletionTool : AgentTool {

    override val name = "attempt_completion"

    override val description = "Once you've confirmed that all tool uses succeeded and the task is complete, use this tool to signal completion. Your detailed explanation should go in the text content BEFORE this tool call (the user reads it in real-time as it streams). The result parameter here is a SHORT summary card — not a repeat of what you already explained. The user may respond with feedback if they are not satisfied, which you can use to make improvements and try again. IMPORTANT: This tool CANNOT be used until you've confirmed from the user that any previous tool uses were successful. Before using this tool, you must verify that all previous tool uses completed successfully."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "result" to ParameterProperty(
                type = "string",
                description = "A short summary of what was done, explored, or found. Do NOT repeat your detailed explanation — the user already read that in your streamed text. Keep it concise."
            ),
            "command" to ParameterProperty(
                type = "string",
                description = "A CLI command to execute to show a live demo of the result to the user. For example, use './gradlew test' to run tests, or 'open localhost:3000' to display a locally running development server. But DO NOT use commands like 'echo' or 'cat' that merely print text. This command should be valid for the current operating system. Ensure the command is properly formatted and does not contain any harmful instructions."
            )
        ),
        required = listOf("result")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val result = params["result"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: result",
                summary = "attempt_completion failed: missing result",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val command = params["command"]?.jsonPrimitive?.content

        return ToolResult(
            content = result,
            summary = "Task completed: ${result.take(200)}",
            tokenEstimate = result.length / 4,
            isCompletion = true,
            verifyCommand = command
        )
    }
}
