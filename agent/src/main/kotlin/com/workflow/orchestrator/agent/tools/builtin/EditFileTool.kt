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
            ?: return ToolResult("Error: 'path' parameter required", "Error: missing path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val oldString = params["old_string"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'old_string' parameter required", "Error: missing old_string", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val newString = params["new_string"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'new_string' parameter required", "Error: missing new_string", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        val file = java.io.File(path!!)
        if (!file.exists() || !file.isFile) {
            return ToolResult("Error: File not found: $path", "Error: file not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
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

        // Apply the edit
        // NOTE: Approval is handled by ApprovalGate in SingleAgentSession BEFORE
        // this tool executes. No need for tool-level approval check.
        val newContent = content.replace(oldString, newString)

        // Syntax validation gate: reject edits that introduce syntax errors
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension in setOf("kt", "java")) {
            try {
                val errors = SyntaxValidator.validate(project, path, newContent)
                if (errors.isNotEmpty()) {
                    // WARN: apply the edit but warn about syntax errors
                    // Multi-step refactors require intermediate invalid states
                    val errorDetails = errors.joinToString("\n") { "  Line ${it.line}:${it.column}: ${it.message}" }
                    // Still write the file (don't block)
                    file.writeText(newContent, Charsets.UTF_8)
                    val summary = "Replaced ${oldString.length} chars with ${newString.length} chars in $rawPath"
                    return ToolResult(
                        content = "$summary\nWARNING: This edit introduced ${errors.size} syntax error(s). You should fix these:\n$errorDetails",
                        summary = "$summary (${errors.size} syntax warnings)",
                        tokenEstimate = TokenEstimator.estimate(summary + errorDetails),
                        artifacts = listOf(path),
                        isError = false // NOT an error — edit was applied
                    )
                }
            } catch (_: Exception) {
                // Syntax validation unavailable (e.g., no PSI in test) — proceed without gate
            }
        }

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
