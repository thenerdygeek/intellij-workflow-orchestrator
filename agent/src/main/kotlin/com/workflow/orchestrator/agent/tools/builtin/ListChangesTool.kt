package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Read-only tool that queries the ChangeLedger to show the LLM
 * what files have been modified, with checkpoint IDs for rollback.
 *
 * Supports optional filtering by file path or iteration number.
 */
class ListChangesTool : AgentTool {
    override val name = "list_changes"
    override val description = """List all file changes made in this session. Returns change entries with file paths, line counts, iteration numbers, and checkpoint IDs. Use to review what has been modified before completing a task, or to find a checkpoint ID for rollback_changes.

Optional filters:
- file: filter to changes for a specific file path
- iteration: filter to changes from a specific iteration number"""

    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(
                type = "string",
                description = "Optional file path to filter changes for. Matches against both absolute and relative paths."
            ),
            "iteration" to ParameterProperty(
                type = "integer",
                description = "Optional iteration number to filter changes from."
            )
        ),
        required = emptyList()
    )

    override val allowedWorkers = setOf(
        WorkerType.CODER,
        WorkerType.REVIEWER,
        WorkerType.ANALYZER
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
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

        val ledger = agentService.currentChangeLedger
            ?: return ToolResult(
                content = "No change ledger active. No changes have been recorded in this session.",
                summary = "No changes recorded",
                tokenEstimate = 5
            )

        val fileFilter = params["file"]?.jsonPrimitive?.content
        val iterationFilter = params["iteration"]?.jsonPrimitive?.intOrNull

        // Get filtered entries
        val entries = when {
            fileFilter != null && iterationFilter != null ->
                ledger.changesForFile(fileFilter).filter { it.iteration == iterationFilter }
            fileFilter != null -> ledger.changesForFile(fileFilter)
            iterationFilter != null -> ledger.changesForIteration(iterationFilter)
            else -> ledger.allEntries()
        }

        if (entries.isEmpty()) {
            val filterDesc = buildString {
                if (fileFilter != null) append(" for file '$fileFilter'")
                if (iterationFilter != null) append(" for iteration $iterationFilter")
            }
            return ToolResult(
                content = "No changes found$filterDesc.",
                summary = "No changes found",
                tokenEstimate = 5
            )
        }

        // Format output
        val output = buildString {
            appendLine("Changes in this session (${entries.size} entries):")
            appendLine()

            // Group by iteration for readability
            entries.groupBy { it.iteration }.toSortedMap().forEach { (iter, iterEntries) ->
                appendLine("--- Iteration $iter ---")
                iterEntries.forEach { entry ->
                    val action = entry.action.name
                    val verified = if (entry.verified) " [verified]" else ""
                    val error = if (entry.verificationError != null) " [error: ${entry.verificationError}]" else ""
                    appendLine("  [$action] ${entry.relativePath} +${entry.linesAdded}/-${entry.linesRemoved} (lines ${entry.editLineRange}) via ${entry.toolName}$verified$error")
                    appendLine("    checkpoint: ${entry.checkpointId}")
                }
                appendLine()
            }

            // Summary stats
            val stats = ledger.totalStats()
            appendLine("Totals: ${stats.filesModified} files, +${stats.totalLinesAdded}/-${stats.totalLinesRemoved} lines")

            // List checkpoints
            val checkpoints = ledger.listCheckpoints()
            if (checkpoints.isNotEmpty()) {
                appendLine()
                appendLine("Checkpoints (use with rollback_changes):")
                checkpoints.forEach { cp ->
                    appendLine("  ${cp.id} — ${cp.description} (${cp.filesModified.size} files, +${cp.totalLinesAdded}/-${cp.totalLinesRemoved})")
                }
            }
        }.trimEnd()

        val tokenEstimate = (output.length / 4).coerceIn(10, 2000)

        return ToolResult(
            content = output,
            summary = "Listed ${entries.size} changes across ${ledger.totalStats().filesModified} files",
            tokenEstimate = tokenEstimate
        )
    }
}
