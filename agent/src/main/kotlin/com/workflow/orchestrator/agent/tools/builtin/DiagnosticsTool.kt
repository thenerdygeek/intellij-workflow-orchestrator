package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Placeholder diagnostics tool. Full PSI-based implementation in Task 5.
 * Returns a message indicating IDE context is required.
 */
class DiagnosticsTool : AgentTool {
    override val name = "diagnostics"
    override val description = "Get IDE diagnostics (errors, warnings) for a file. Requires IDE indexing to be complete."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "Absolute or project-relative file path")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return ToolResult(
            content = "Diagnostics require IDE context. This placeholder will be replaced with PSI-based inspection results in Task 5.",
            summary = "Diagnostics not yet available (placeholder)",
            tokenEstimate = 15,
            isError = false
        )
    }
}
