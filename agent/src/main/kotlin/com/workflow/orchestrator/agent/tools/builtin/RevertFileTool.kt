package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.RollbackEntry
import com.workflow.orchestrator.agent.runtime.RollbackScope
import com.workflow.orchestrator.agent.runtime.RollbackSource
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reverts a single file to its pre-edit state using git checkout.
 * Only available to CODER workers.
 *
 * Unlike [RollbackChangesTool] which reverts ALL changes after a checkpoint,
 * this tool surgically reverts only the specified file.
 */
class RevertFileTool : AgentTool {
    override val name = "revert_file"
    override val description = """Revert a single file to its original state before the agent modified it. This is a surgical operation — only the specified file is reverted, all other changes remain intact.

Use this when you made a mistake in one file but want to keep changes in other files. For reverting ALL changes, use rollback_changes instead.

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

        val agentService = try {
            AgentService.getInstance(project)
        } catch (_: Exception) {
            return ToolResult(
                content = "Error: AgentService not available.",
                summary = "Error: no agent service",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val rollbackManager = agentService.currentRollbackManager
            ?: return ToolResult(
                content = "Error: No rollback manager active. Cannot revert file.",
                summary = "Error: no rollback manager",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        // Resolve to absolute path
        val resolvedPath = if (filePath.startsWith("/")) filePath
        else "${project.basePath}/$filePath"

        val result = rollbackManager.rollbackFile(resolvedPath)
        val ledger = agentService.currentChangeLedger

        return if (result.success) {
            // Record rollback entry for this single file
            val fileEntries = ledger?.changesForFile(resolvedPath) ?: emptyList()
            val rollbackEntry = RollbackEntry(
                id = "revert-${System.currentTimeMillis()}",
                timestamp = System.currentTimeMillis(),
                checkpointId = fileEntries.lastOrNull()?.checkpointId ?: "",
                description = description,
                source = RollbackSource.LLM_TOOL,
                mechanism = result.mechanism,
                affectedFiles = result.affectedFiles,
                rolledBackEntryIds = fileEntries.map { it.id },
                scope = RollbackScope.SINGLE_FILE
            )
            ledger?.recordRollback(rollbackEntry)

            if (ledger != null) {
                agentService.currentContextBridge?.updateChangeLedgerAnchor(ledger)
            }

            ToolResult(
                content = "Successfully reverted $filePath. Reason: $description\n\n" +
                    "The file has been restored to its pre-edit state. Other file changes are preserved.",
                summary = "Reverted file $filePath: $description",
                tokenEstimate = 20
            )
        } else {
            ToolResult(
                content = "Error: Failed to revert '$filePath': ${result.error}. " +
                    "Use list_changes to see tracked files.",
                summary = "Revert failed: ${result.error}",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
