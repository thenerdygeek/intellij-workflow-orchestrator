package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.RollbackEntry
import com.workflow.orchestrator.agent.runtime.RollbackMechanism
import com.workflow.orchestrator.agent.runtime.RollbackScope
import com.workflow.orchestrator.agent.runtime.RollbackSource
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reverts all file changes back to a specific checkpoint using IntelliJ's
 * LocalHistory API. Only available to CODER workers.
 *
 * The LLM obtains checkpoint IDs from list_changes or from the
 * <change_ledger> context anchor.
 */
class RollbackChangesTool : AgentTool {
    override val name = "rollback_changes"
    override val description = """Revert all file changes back to a specific checkpoint. This undoes all modifications made after the checkpoint was created, using IntelliJ's LocalHistory.

Use list_changes to find available checkpoint IDs. This is a destructive operation — all changes after the checkpoint will be lost.

Parameters:
- checkpoint_id: The checkpoint ID to revert to (from list_changes or <change_ledger>)
- description: Why you are rolling back (for audit trail)"""

    override val parameters = FunctionParameters(
        properties = mapOf(
            "checkpoint_id" to ParameterProperty(
                type = "string",
                description = "The checkpoint ID to revert to. Get this from list_changes or the <change_ledger> context."
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Reason for the rollback (recorded in audit trail)."
            )
        ),
        required = listOf("checkpoint_id", "description")
    )

    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val checkpointId = params["checkpoint_id"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'checkpoint_id' parameter is required.",
                summary = "Error: missing checkpoint_id",
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
                content = "Error: No rollback manager active. Cannot revert changes.",
                summary = "Error: no rollback manager",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val result = rollbackManager.rollbackToCheckpoint(checkpointId)

        return if (result.success) {
            // Record rollback in the change ledger
            val ledger = agentService.currentChangeLedger
            if (ledger != null) {
                val rolledBackEntries = ledger.entriesAfterCheckpoint(checkpointId)
                val rollbackEntry = RollbackEntry(
                    id = "rollback-${System.currentTimeMillis()}",
                    timestamp = System.currentTimeMillis(),
                    checkpointId = checkpointId,
                    description = description,
                    source = RollbackSource.LLM_TOOL,
                    mechanism = result.mechanism,
                    affectedFiles = result.affectedFiles,
                    rolledBackEntryIds = rolledBackEntries.map { it.id },
                    scope = RollbackScope.FULL_CHECKPOINT
                )
                ledger.recordRollback(rollbackEntry)

                // Update the change ledger anchor so LLM sees current state
                agentService.currentContextBridge?.updateChangeLedgerAnchor(ledger)
            }

            val mechanismNote = if (result.mechanism == RollbackMechanism.GIT_FALLBACK) {
                " (used git fallback)"
            } else ""
            val fileCount = result.affectedFiles.size

            ToolResult(
                content = "Successfully rolled back to checkpoint $checkpointId$mechanismNote. " +
                    "$fileCount file(s) reverted. Reason: $description\n\n" +
                    "All file changes after this checkpoint have been reverted. " +
                    "Use list_changes to see the current state.",
                summary = "Rolled back to checkpoint $checkpointId ($fileCount files): $description",
                tokenEstimate = 25
            )
        } else {
            ToolResult(
                content = "Error: Failed to rollback to checkpoint '$checkpointId': ${result.error}. " +
                    "Use list_changes to see available checkpoints.",
                summary = "Rollback failed: ${result.error}",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
