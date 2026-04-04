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

    override val description = "After each tool use, the user will respond with the result of that tool use, i.e. if it succeeded or failed, along with any reasons for failure. Once you've received the results of tool uses and can confirm that the task is complete, use this tool to present the result of your work to the user. Optionally you may provide a CLI command to showcase the result of your work. The user may respond with feedback if they are not satisfied with the result, which you can use to make improvements and try again. IMPORTANT: This tool CANNOT be used until you've confirmed from the user that any previous tool uses were successful. Failure to do so will result in code corruption and system failure. Before using this tool, you must verify that all previous tool uses completed successfully."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "result" to ParameterProperty(
                type = "string",
                description = "The result of the task. This should be a clear, specific description of what was accomplished. Formulate this result in a way that is final and does not require further input from the user. Don't end your result with questions or offers for further assistance."
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
