package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Removes a line breakpoint from a file at the specified line.
 *
 * Threading: Breakpoint removal via XBreakpointManager must run on EDT
 * inside a WriteAction.
 */
class RemoveBreakpointTool : AgentTool {
    override val name = "remove_breakpoint"
    override val description = "Remove a line breakpoint from a file"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(
                type = "string",
                description = "File path relative to project or absolute"
            ),
            "line" to ParameterProperty(
                type = "integer",
                description = "1-based line number of the breakpoint to remove"
            )
        ),
        required = listOf("file", "line")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing required parameter: file", "Missing param", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val line = params["line"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult("Missing or invalid required parameter: line", "Missing param", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        if (line < 1) {
            return ToolResult("Line number must be >= 1, got: $line", "Invalid line", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // Resolve file path
        val absolutePath = if (File(filePath).isAbsolute) {
            filePath
        } else {
            val basePath = project.basePath
                ?: return ToolResult("Cannot resolve relative path: project basePath is null", "Path error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            File(basePath, filePath).canonicalPath
        }

        return try {
            withContext(Dispatchers.EDT) {
                WriteAction.compute<ToolResult, Exception> {
                    val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
                        ?: return@compute ToolResult(
                            "File not found: $absolutePath",
                            "File not found",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )

                    val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                    val fileUrl = vFile.url
                    val zeroBasedLine = line - 1

                    // Find matching breakpoint
                    val matchingBp = bpManager.allBreakpoints
                        .filterIsInstance<XLineBreakpoint<*>>()
                        .find { bp ->
                            bp.fileUrl == fileUrl && bp.line == zeroBasedLine
                        }

                    if (matchingBp == null) {
                        return@compute ToolResult(
                            "No breakpoint found at ${vFile.name}:$line",
                            "Not found",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )
                    }

                    bpManager.removeBreakpoint(matchingBp)
                    ToolResult(
                        "Breakpoint removed from ${vFile.name}:$line",
                        "Removed ${vFile.name}:$line",
                        ToolResult.ERROR_TOKEN_ESTIMATE
                    )
                }
            }
        } catch (e: Exception) {
            ToolResult(
                "Error removing breakpoint: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
