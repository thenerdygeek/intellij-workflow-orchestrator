package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

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

    /**
     * Extract console text from a RunContentDescriptor.
     *
     * For test consoles: executionConsole is SMTRunnerConsoleView (extends BaseTestsOutputConsoleView).
     * The actual console output is in the INNER console: BaseTestsOutputConsoleView.getConsole() → ConsoleViewImpl.
     *
     * For regular consoles: executionConsole is directly a ConsoleViewImpl.
     *
     * Follows the delegation chain (getDelegate → getConsole) to handle IntelliJ Ultimate
     * wrappers like JavaConsoleWithProfilerWidget.
     *
     * Forces component initialization via getComponent() to ensure the lazy editor is created
     * before reading — without this, deferred text has nowhere to flush and the document is null.
     */
    private suspend fun extractConsoleText(descriptor: RunContentDescriptor): String? {
        val console = descriptor.executionConsole ?: return null

        // Follow the delegation chain to find the innermost ConsoleViewImpl.
        // IntelliJ Ultimate wraps consoles: e.g. JavaConsoleWithProfilerWidget → BaseTestsOutputConsoleView → ConsoleViewImpl.
        val unwrapped = unwrapToConsoleView(console)
        if (unwrapped != null) {
            val text = readConsoleViewText(unwrapped)
            if (!text.isNullOrBlank()) return text
        }

        // Direct check if the console itself is a ConsoleViewImpl (regular run configurations)
        if (console is com.intellij.execution.impl.ConsoleViewImpl) {
            val text = readConsoleViewText(console)
            if (!text.isNullOrBlank()) return text
        }

        // BaseTestsOutputConsoleView (test runs) — use public getConsole()
        if (console is com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView) {
            val innerConsole = console.console
            if (innerConsole is com.intellij.execution.impl.ConsoleViewImpl) {
                val text = readConsoleViewText(innerConsole)
                if (!text.isNullOrBlank()) return text
            }
            val text = readViaEditor(innerConsole)
            if (!text.isNullOrBlank()) return text
        }

        // Reflection fallback: try getConsole() on unknown wrapper types
        try {
            val getConsole = console.javaClass.getMethod("getConsole")
            val innerConsole = getConsole.invoke(console)
            if (innerConsole is com.intellij.execution.impl.ConsoleViewImpl) {
                val text = readConsoleViewText(innerConsole)
                if (!text.isNullOrBlank()) return text
            }
            if (innerConsole != null) {
                val text = readViaEditor(innerConsole)
                if (!text.isNullOrBlank()) return text
            }
        } catch (_: Exception) {}

        // Last resort: try editor directly on the original console
        return readViaEditor(console)
    }

    /**
     * Follow the delegation chain (max 5 levels) to find the innermost ConsoleViewImpl.
     * Handles: JavaConsoleWithProfilerWidget (getDelegate) → BaseTestsOutputConsoleView (getConsole) → ConsoleViewImpl.
     */
    private fun unwrapToConsoleView(console: Any): com.intellij.execution.impl.ConsoleViewImpl? {
        var current: Any? = console
        repeat(MAX_UNWRAP_DEPTH) {
            if (current is com.intellij.execution.impl.ConsoleViewImpl) return current

            // Try getDelegate() — ConsoleViewWithDelegate (IntelliJ Ultimate profiler wrapper)
            val delegate = tryReflectiveCall(current, "getDelegate")
            if (delegate is com.intellij.execution.impl.ConsoleViewImpl) return delegate
            if (delegate != null && delegate !== current) {
                current = delegate
                return@repeat
            }

            // Try getConsole() — BaseTestsOutputConsoleView and similar wrappers
            val inner = tryReflectiveCall(current, "getConsole")
            if (inner is com.intellij.execution.impl.ConsoleViewImpl) return inner
            if (inner != null && inner !== current) {
                current = inner
                return@repeat
            }

            return null // no more wrappers to unwrap
        }
        return current as? com.intellij.execution.impl.ConsoleViewImpl
    }

    private fun tryReflectiveCall(target: Any?, methodName: String): Any? {
        return try {
            target?.javaClass?.getMethod(methodName)?.invoke(target)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Read text from a ConsoleViewImpl, ensuring deferred text is flushed first.
     * Forces component initialization to create the lazy editor — without this,
     * the editor can be null even when text has been printed to the console.
     */
    private suspend fun readConsoleViewText(console: com.intellij.execution.impl.ConsoleViewImpl): String? {
        return try {
            withContext(Dispatchers.EDT) {
                // Force lazy editor creation — ConsoleViewImpl creates its editor
                // in getComponent(). Without this, editor is null and flushDeferredText
                // has nowhere to write, causing "empty output" even when text is visible.
                console.component
                console.flushDeferredText()
                console.editor?.document?.text
            }
        } catch (_: Exception) { null }
    }

    /** Fallback: try to get text via getEditor().document.text — must run on EDT for document access. */
    private suspend fun readViaEditor(console: Any): String? {
        return try {
            withContext(Dispatchers.EDT) {
                // Force component initialization if possible
                try { console.javaClass.getMethod("getComponent").invoke(console) } catch (_: Exception) {}
                val editorMethod = console.javaClass.getMethod("getEditor")
                val editor = editorMethod.invoke(console) as? com.intellij.openapi.editor.Editor
                editor?.document?.text
            }
        } catch (_: Exception) { null }
    }

    companion object {
        private const val DEFAULT_LINES = 200
        private const val MAX_LINES = 1000
        private const val TOKEN_CAP_CHARS = 12000 // ~3000 tokens
        private const val MAX_UNWRAP_DEPTH = 5
    }
}
