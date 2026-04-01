package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.MessageType
import com.workflow.orchestrator.agent.runtime.WorkerContext
import com.workflow.orchestrator.agent.runtime.WorkerMessageBus
import com.workflow.orchestrator.agent.runtime.WorkerMessage
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Sends a message from a worker session to the parent orchestrator.
 *
 * Use for critical findings that affect the overall task approach, or status
 * updates on progress. The parent sees the message at its next ReAct loop
 * iteration — delivery is not instant.
 *
 * Available to all [WorkerType]s.
 */
class SendMessageToParentTool : AgentTool {

    override val name = "send_message_to_parent"

    override val description = "Send a message to the parent orchestrator. " +
        "Use for critical findings that affect the overall task, or status updates on progress. " +
        "The parent sees your message at its next iteration — it is not instant.\n\n" +
        "Use 'finding' for discoveries that may change the approach (e.g., circular dependency, " +
        "missing API, breaking change). Use 'status_update' for progress reports."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "type" to ParameterProperty(
                type = "string",
                description = "Message type: 'finding' for discoveries that affect the task, " +
                    "'status_update' for progress reports",
                enumValues = listOf("finding", "status_update")
            ),
            "content" to ParameterProperty(
                type = "string",
                description = "The message content. Be concise and actionable."
            )
        ),
        required = listOf("type", "content")
    )

    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR, WorkerType.ANALYZER, WorkerType.CODER,
        WorkerType.REVIEWER, WorkerType.TOOLER
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val typeStr = params["type"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'type' parameter required", "Error: missing type",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'content' parameter required", "Error: missing content",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val messageType = when (typeStr) {
            "finding" -> MessageType.FINDING
            "status_update" -> MessageType.STATUS_UPDATE
            else -> return ToolResult("Error: type must be 'finding' or 'status_update'",
                "Error: invalid type", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val workerCtx = coroutineContext[WorkerContext]
            ?: return ToolResult("Error: not running in a worker context",
                "Error: no worker context", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val bus = workerCtx.messageBus
            ?: return ToolResult("Error: message bus not available",
                "Error: no message bus", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val agentId = workerCtx.agentId ?: "orchestrator"
        val sent = bus.send(WorkerMessage(
            from = agentId,
            to = WorkerMessageBus.ORCHESTRATOR_ID,
            type = messageType,
            content = content
        ))

        return if (sent) {
            ToolResult(
                content = "Message sent to orchestrator. Continue with your task.",
                summary = "Sent $typeStr to orchestrator",
                tokenEstimate = 15
            )
        } else {
            ToolResult(
                content = "Warning: orchestrator inbox not available. Message not delivered. Continue with your task.",
                summary = "Message delivery failed",
                tokenEstimate = 15,
                isError = true
            )
        }
    }
}
