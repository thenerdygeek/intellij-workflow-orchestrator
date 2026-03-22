package com.workflow.orchestrator.agent.tools.config

import com.intellij.execution.RunManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deletes a run/debug configuration.
 *
 * Safety constraint: only agent-created configurations (prefixed with [Agent])
 * can be deleted. Attempting to delete a user-created configuration returns an error.
 */
class DeleteRunConfigTool : AgentTool {
    override val name = "delete_run_config"
    override val description = "Delete a run/debug configuration. Only agent-created configurations (prefixed with [Agent]) can be deleted."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "name" to ParameterProperty(
                type = "string",
                description = "Exact name of the configuration to delete"
            )
        ),
        required = listOf("name")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val configName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Missing required parameter: name",
                "Error: missing name",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        // Safety check: only allow deletion of agent-created configs
        if (!configName.startsWith("[Agent]")) {
            return ToolResult(
                "Cannot delete '$configName': only agent-created configurations (containing [Agent] in name) can be deleted. " +
                    "This is a safety constraint to protect user-created configurations.",
                "Error: cannot delete non-agent config",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            val runManager = RunManager.getInstance(project)
            val settings = runManager.findConfigurationByName(configName)
                ?: return ToolResult(
                    "Configuration '$configName' not found. Use get_run_configurations to list available configs.",
                    "Error: config not found",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            // Remove on EDT
            ApplicationManager.getApplication().invokeAndWait {
                runManager.removeConfiguration(settings)
            }

            ToolResult(
                "Deleted run configuration '$configName'",
                "Deleted config '$configName'",
                10
            )
        } catch (e: Exception) {
            ToolResult(
                "Error deleting run configuration: ${e.message}",
                "Error deleting config",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
