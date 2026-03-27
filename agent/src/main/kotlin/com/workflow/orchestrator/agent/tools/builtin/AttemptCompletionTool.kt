package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.CompletionGatekeeper
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class AttemptCompletionTool(
    private val gatekeeper: CompletionGatekeeper
) : AgentTool {

    override val name = "attempt_completion"

    override val description = "Declare that you have finished the user's request. Call this " +
        "ONLY when the entire task is fully resolved — not when completing individual plan " +
        "steps (use update_plan_step for that). Your completion may be blocked if there is " +
        "unfinished work. The result is displayed prominently to the user as a completion " +
        "summary — make it informative and well-structured."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "result" to ParameterProperty(
                type = "string",
                description = "A short, concise summary of only the important things done. " +
                    "Keep it brief — bullet points of key changes and outcomes. " +
                    "Skip implementation details the user doesn't need to know. " +
                    "This is displayed as the final completion card."
            ),
            "command" to ParameterProperty(
                type = "string",
                description = "Optional command for the user to verify the result (e.g., './gradlew test')"
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

        val block = gatekeeper.checkCompletion()
        if (block != null) {
            return ToolResult(
                content = block,
                summary = "Completion blocked by gate",
                tokenEstimate = block.length / 4,
                isError = true
            )
        }

        return ToolResult(
            content = result,
            summary = "Task completed: ${result.take(200)}",
            tokenEstimate = result.length / 4,
            isCompletion = true,
            verifyCommand = command
        )
    }
}
