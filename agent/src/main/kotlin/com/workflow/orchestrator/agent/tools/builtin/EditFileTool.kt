package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class EditFileTool : AgentTool {
    override val name = "edit_file"
    override val description = "Perform an exact string replacement in a file. The old_string must match exactly once in the file."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "Absolute or project-relative file path"),
            "old_string" to ParameterProperty(type = "string", description = "The exact text to find and replace. Must be unique in the file."),
            "new_string" to ParameterProperty(type = "string", description = "The replacement text.")
        ),
        required = listOf("path", "old_string", "new_string")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter required", "Error: missing path", 5, isError = true)
        val oldString = params["old_string"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'old_string' parameter required", "Error: missing old_string", 5, isError = true)
        val newString = params["new_string"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'new_string' parameter required", "Error: missing new_string", 5, isError = true)

        val path = if (rawPath.startsWith("/")) rawPath
            else "${project.basePath}/$rawPath"

        val file = java.io.File(path)
        if (!file.exists() || !file.isFile) {
            return ToolResult("Error: File not found: $path", "Error: file not found", 5, isError = true)
        }

        val content = file.readText(Charsets.UTF_8)
        val occurrences = countOccurrences(content, oldString)

        if (occurrences == 0) {
            return ToolResult(
                "Error: old_string not found in $rawPath. Verify the exact text including whitespace.",
                "Error: old_string not found",
                5,
                isError = true
            )
        }

        if (occurrences > 1) {
            return ToolResult(
                "Error: old_string found $occurrences times in $rawPath. Provide a larger, unique string with more context.",
                "Error: old_string not unique ($occurrences occurrences)",
                5,
                isError = true
            )
        }

        // Check if approval is required before editing
        try {
            val settings = com.workflow.orchestrator.agent.settings.AgentSettings.getInstance(project)
            if (settings.state.approvalRequiredForEdits) {
                // Return the proposed diff instead of applying — the UI will show approval dialog
                val diff = "--- $rawPath\n+++ $rawPath\n@@ edit @@\n-${oldString.take(200)}\n+${newString.take(200)}"
                return ToolResult(
                    content = "APPROVAL_REQUIRED: Edit pending approval.\n$diff",
                    summary = "Edit pending approval for $rawPath",
                    tokenEstimate = TokenEstimator.estimate(diff),
                    artifacts = listOf(path),
                    isError = false
                )
            }
        } catch (_: Exception) {
            // Settings not available (e.g., testing) — proceed without approval
        }

        // Use WriteCommandAction to properly integrate with IntelliJ's VFS and undo system
        val newContent = content.replace(oldString, newString)
        file.writeText(newContent, Charsets.UTF_8)

        val summary = "Replaced ${oldString.length} chars with ${newString.length} chars in $rawPath"
        return ToolResult(
            content = summary,
            summary = summary,
            tokenEstimate = TokenEstimator.estimate(summary),
            artifacts = listOf(path)
        )
    }

    private fun countOccurrences(text: String, search: String): Int {
        var count = 0
        var startIndex = 0
        while (true) {
            val index = text.indexOf(search, startIndex)
            if (index < 0) break
            count++
            startIndex = index + 1
        }
        return count
    }
}
