package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reverts a single file to its pre-edit state using git checkout.
 * Only available to CODER workers.
 */
class RevertFileTool : AgentTool {
    override val name = "revert_file"
    override val description = """Revert a single file to its original state before the agent modified it. This is a surgical operation — only the specified file is reverted, all other changes remain intact.

Parameters:
- file_path: Absolute or relative path to the file to revert
- description: Why you are reverting this file (for audit trail)"""

    override val parameters = FunctionParameters(
        properties = mapOf(
            "file_path" to ParameterProperty(
                type = "string",
                description = "Path to the file to revert (absolute or relative to project root)."
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Reason for reverting this file (recorded in audit trail)."
            )
        ),
        required = listOf("file_path", "description")
    )

    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file_path"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'file_path' parameter is required.",
                summary = "Error: missing file_path",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val description = params["description"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'description' parameter is required.",
                summary = "Error: missing description",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val resolvedPath = java.io.File(
            if (filePath.startsWith("/")) filePath
            else "${project.basePath}/$filePath"
        ).canonicalPath

        // Use git checkout to revert
        return try {
            val process = ProcessBuilder("git", "checkout", "--", resolvedPath)
                .directory(java.io.File(project.basePath ?: "."))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                ToolResult(
                    content = "Successfully reverted $filePath. Reason: $description\n\n" +
                        "The file has been restored to its pre-edit state. Other file changes are preserved.",
                    summary = "Reverted file $filePath: $description",
                    tokenEstimate = 20
                )
            } else {
                ToolResult(
                    content = "Error: Failed to revert '$filePath': $output",
                    summary = "Revert failed: exit code $exitCode",
                    tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }
        } catch (e: Exception) {
            ToolResult(
                content = "Error: Failed to revert '$filePath': ${e.message}",
                summary = "Revert failed: ${e.message}",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
