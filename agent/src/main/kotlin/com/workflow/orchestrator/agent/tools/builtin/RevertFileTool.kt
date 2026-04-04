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
    override val description = """Use to undo changes made to a file. Reverts a single file to its original state before the agent modified it (via git checkout). This is a surgical operation — only the specified file is reverted, all other changes remain intact. Use this when an edit introduced bugs, broke tests, or went in the wrong direction. This is safer than trying to manually reverse edits with edit_file, as it restores the exact original content."""

    override val parameters = FunctionParameters(
        properties = mapOf(
            "file_path" to ParameterProperty(
                type = "string",
                description = "Path to the file to revert (absolute or relative to the project root). The file must be tracked by git."
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Why you are reverting this file. This is recorded in the audit trail and shown to the user."
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
