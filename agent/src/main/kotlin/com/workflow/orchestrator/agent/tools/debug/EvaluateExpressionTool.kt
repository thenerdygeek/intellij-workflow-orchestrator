package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Evaluates a Java/Kotlin expression in the context of a paused debug session.
 * WARNING: Expressions can have side effects (method calls, assignments, etc.).
 * Delegates to AgentDebugController.evaluate().
 */
class EvaluateExpressionTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "evaluate_expression"
    override val description = "Evaluate a Java/Kotlin expression in the context of a paused debug session. WARNING: Expressions can have side effects."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "expression" to ParameterProperty(
                type = "string",
                description = "Java/Kotlin expression to evaluate (e.g., 'user.getName()', 'list.size()', 'x + y')"
            ),
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            ),
            "frame_index" to ParameterProperty(
                type = "integer",
                description = "Stack frame index for evaluation context (default: 0 = top frame)"
            )
        ),
        required = listOf("expression")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val expression = params["expression"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Missing required parameter: expression",
                "Missing param",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (expression.isBlank()) {
            return ToolResult(
                "Expression cannot be blank.",
                "Blank expression",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val frameIndex = params["frame_index"]?.jsonPrimitive?.intOrNull ?: 0

        if (frameIndex < 0) {
            return ToolResult(
                "frame_index must be >= 0, got: $frameIndex",
                "Invalid frame_index",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (!session.isSuspended) {
            return ToolResult(
                "Session is not suspended. Cannot evaluate expressions while running.",
                "Not suspended",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            val evalResult = controller.evaluate(session, expression, frameIndex)

            if (evalResult.isError) {
                return ToolResult(
                    "Error: ${evalResult.result}",
                    "Evaluation error",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            val sb = StringBuilder()
            sb.append("Expression: $expression\n")
            sb.append("Result: ${evalResult.result}\n")
            sb.append("Type: ${evalResult.type}")

            val content = sb.toString()
            ToolResult(content, "Evaluated: $expression", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult(
                "Error evaluating expression: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
