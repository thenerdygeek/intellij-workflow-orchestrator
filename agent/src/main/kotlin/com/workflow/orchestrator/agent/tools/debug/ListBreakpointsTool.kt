package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Lists all breakpoints in the project, optionally filtered by file.
 * Returns a formatted summary of each breakpoint's location, status, and properties.
 */
class ListBreakpointsTool : AgentTool {
    override val name = "list_breakpoints"
    override val description = "List all breakpoints in the project, optionally filtered by file"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(
                type = "string",
                description = "Optional file path to filter breakpoints. If not provided, lists all breakpoints."
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filterFile = params["file"]?.jsonPrimitive?.content

        return try {
            val bpManager = XDebuggerManager.getInstance(project).breakpointManager

            // Resolve filter file URL if provided
            val filterFileUrl = if (filterFile != null) {
                val absolutePath = if (File(filterFile).isAbsolute) {
                    filterFile
                } else {
                    val basePath = project.basePath
                        ?: return ToolResult("Cannot resolve relative path: project basePath is null", "Path error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                    File(basePath, filterFile).canonicalPath
                }
                LocalFileSystem.getInstance().findFileByPath(absolutePath)?.url
            } else {
                null
            }

            val lineBreakpoints = bpManager.allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .filter { bp ->
                    filterFileUrl == null || bp.fileUrl == filterFileUrl
                }

            if (lineBreakpoints.isEmpty()) {
                val qualifier = if (filterFile != null) " in $filterFile" else ""
                return ToolResult(
                    "No breakpoints found$qualifier.",
                    "No breakpoints",
                    ToolResult.ERROR_TOKEN_ESTIMATE
                )
            }

            val sb = StringBuilder()
            sb.appendLine("Breakpoints (${lineBreakpoints.size}):")
            sb.appendLine()

            for (bp in lineBreakpoints) {
                val fileName = bp.fileUrl.substringAfterLast('/')
                val oneBased = bp.line + 1

                val traits = mutableListOf<String>()
                traits.add(if (bp.isEnabled) "enabled" else "disabled")

                val condition = bp.conditionExpression?.expression
                if (!condition.isNullOrBlank()) {
                    traits.add("conditional: $condition")
                }

                val logExpr = bp.logExpressionObject?.expression
                if (!logExpr.isNullOrBlank()) {
                    traits.add("log: $logExpr")
                }

                if (bp.isTemporary) {
                    traits.add("temporary")
                }

                if (bp.suspendPolicy == SuspendPolicy.NONE) {
                    traits.add("non-suspend")
                }

                sb.appendLine("$fileName:$oneBased [${traits.joinToString(", ")}]")
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "${lineBreakpoints.size} breakpoints", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult(
                "Error listing breakpoints: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
