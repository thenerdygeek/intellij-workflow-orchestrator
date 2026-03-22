package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.swing.JComponent

class GetRunOutputTool : AgentTool {
    override val name = "get_run_output"
    override val description = "Get console output from an active or recently completed run session"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "config_name" to ParameterProperty(
                type = "string",
                description = "Name of the run configuration to get output from"
            ),
            "last_n_lines" to ParameterProperty(
                type = "integer",
                description = "Number of lines to return from the end (default: 200, max: 1000)"
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "Regex pattern to filter output lines"
            )
        ),
        required = listOf("config_name")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'config_name' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val lastNLines = (params["last_n_lines"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LINES)
            .coerceIn(1, MAX_LINES)

        val filterPattern = params["filter"]?.jsonPrimitive?.content?.let {
            try {
                Regex(it)
            } catch (e: Exception) {
                return ToolResult("Error: invalid regex pattern '${it}': ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }
        }

        return try {
            // Find the matching run content descriptor
            val contentManager = RunContentManager.getInstance(project)
            val allDescriptors = contentManager.allDescriptors

            val descriptor = allDescriptors.find { desc ->
                desc.displayName?.contains(configName, ignoreCase = true) == true
            }

            if (descriptor == null) {
                // List available sessions to help the user
                val available = allDescriptors.mapNotNull { it.displayName }
                val availableMsg = if (available.isNotEmpty()) {
                    "\nAvailable sessions: ${available.joinToString(", ")}"
                } else {
                    "\nNo run sessions available."
                }
                return ToolResult(
                    "No run session found matching '$configName'.$availableMsg",
                    "Not found",
                    30,
                    isError = true
                )
            }

            // Try to get console content
            val consoleText = extractConsoleText(descriptor)

            if (consoleText == null || consoleText.isBlank()) {
                return ToolResult(
                    "Run session '${descriptor.displayName}' found but console output is empty.",
                    "Empty output",
                    10
                )
            }

            var lines = consoleText.lines()

            // Apply regex filter if provided
            if (filterPattern != null) {
                lines = lines.filter { filterPattern.containsMatchIn(it) }
            }

            // Take last N lines
            val totalLines = lines.size
            lines = lines.takeLast(lastNLines)

            // Build output with line numbers
            val sb = StringBuilder()
            sb.appendLine("Console Output: ${descriptor.displayName}")
            val processStatus = when {
                descriptor.processHandler?.isProcessTerminated == true -> "Terminated"
                descriptor.processHandler?.isProcessTerminating == true -> "Terminating"
                else -> "Running"
            }
            sb.appendLine("Status: $processStatus")
            if (filterPattern != null) {
                sb.appendLine("Filter: ${filterPattern.pattern}")
            }
            if (totalLines > lastNLines) {
                sb.appendLine("Showing last $lastNLines of $totalLines lines")
            }
            sb.appendLine("---")

            val startLineNum = (totalLines - lines.size) + 1
            for ((index, line) in lines.withIndex()) {
                sb.appendLine("${startLineNum + index}: $line")
            }

            val content = sb.toString().trimEnd()
            // Cap at ~3000 tokens
            val capped = if (content.length > TOKEN_CAP_CHARS) {
                content.take(TOKEN_CAP_CHARS) + "\n... (output truncated)"
            } else {
                content
            }

            ToolResult(capped, "${lines.size} lines from ${descriptor.displayName}", capped.length / 4)
        } catch (e: Exception) {
            ToolResult("Error getting run output: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun extractConsoleText(descriptor: RunContentDescriptor): String? {
        return try {
            // Try to get text from the ExecutionConsole
            val console = descriptor.executionConsole
            if (console is ConsoleView) {
                // ConsoleView doesn't have a direct getText — try component-based extraction
                val component = console.component
                return extractTextFromComponent(component)
            }
            // Fallback: try to get from the console's component tree
            val component = console?.component
            if (component != null) {
                return extractTextFromComponent(component)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractTextFromComponent(component: JComponent): String? {
        return try {
            // Try to find a JTextArea or EditorComponent in the hierarchy
            val method = component.javaClass.methods.find {
                it.name == "getText" && it.parameterCount == 0
            }
            method?.invoke(component) as? String
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val DEFAULT_LINES = 200
        private const val MAX_LINES = 1000
        private const val TOKEN_CAP_CHARS = 12000 // ~3000 tokens
    }
}
