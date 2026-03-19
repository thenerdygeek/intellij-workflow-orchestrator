package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraTransitionTool : AgentTool {
    override val name = "jira_transition"
    override val description = "Transition a Jira ticket to a new status. Validates the ticket exists, checks the transition is available from the current state, and reports required fields."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "key" to ParameterProperty(type = "string", description = "Jira ticket key (e.g., PROJ-123)"),
            "transition_id" to ParameterProperty(type = "string", description = "Transition ID. Use jira_get_ticket first to see available transitions."),
            "comment" to ParameterProperty(type = "string", description = "Optional comment to add with the transition")
        ),
        required = listOf("key", "transition_id")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'key' parameter required", "Error: missing key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val transitionId = params["transition_id"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'transition_id' parameter required", "Error: missing transition_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val comment = params["comment"]?.jsonPrimitive?.content

        // 1. Validate key format
        ToolValidation.validateJiraKey(key)?.let { return it }

        val service = ServiceLookup.jira(project)
            ?: return ServiceLookup.notConfigured("Jira")

        // 2. Pre-flight: verify ticket exists and get current state
        val ticketResult = service.getTicket(key)
        if (ticketResult.isError) {
            return ToolResult(
                content = "Cannot transition: ticket $key not found or not accessible.\n${ticketResult.summary}",
                summary = "Ticket $key not found",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val currentStatus = ticketResult.data.status

        // 3. Pre-flight: verify transition is available from current state
        val transitionsResult = service.getTransitions(key)
        if (!transitionsResult.isError) {
            val available = transitionsResult.data
            val match = available.find { it.id == transitionId }

            if (match == null) {
                val availableList = available.joinToString("\n") { "  ID ${it.id}: ${it.name} → ${it.toStatus}" }
                val content = "Cannot transition $key: transition ID '$transitionId' is not available from current status '$currentStatus'.\n\nAvailable transitions:\n$availableList"
                return ToolResult(
                    content = content,
                    summary = "Invalid transition ID '$transitionId' from status '$currentStatus'",
                    tokenEstimate = TokenEstimator.estimate(content),
                    isError = true
                )
            }
        }

        // 4. Execute transition
        val result = service.transition(key, transitionId, comment = comment)
        return if (result.isError) {
            result.toAgentToolResult()
        } else {
            ToolResult(
                content = "Transitioned $key from '$currentStatus'. ${result.summary}",
                summary = "Transitioned $key",
                tokenEstimate = TokenEstimator.estimate(result.summary)
            )
        }
    }
}
