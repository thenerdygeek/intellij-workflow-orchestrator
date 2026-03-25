package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
/**
 * Sets a line breakpoint in a file. Supports conditional breakpoints,
 * log breakpoints (non-suspending), and temporary breakpoints.
 *
 * Threading: Breakpoint creation via XBreakpointManager must run on EDT
 * inside a WriteAction.
 */
class AddBreakpointTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "add_breakpoint"
    override val description = "Set a line breakpoint in a file. Supports conditional breakpoints, log breakpoints (non-suspending), and temporary breakpoints."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(
                type = "string",
                description = "File path relative to project or absolute"
            ),
            "line" to ParameterProperty(
                type = "integer",
                description = "1-based line number where the breakpoint should be set"
            ),
            "condition" to ParameterProperty(
                type = "string",
                description = "Optional conditional expression (e.g., user.getId() == 42). Breakpoint only suspends when condition is true."
            ),
            "log_expression" to ParameterProperty(
                type = "string",
                description = "Optional expression to log when hit without stopping execution (log breakpoint)"
            ),
            "temporary" to ParameterProperty(
                type = "boolean",
                description = "If true, breakpoint is automatically removed after first hit"
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
        val condition = params["condition"]?.jsonPrimitive?.content
        val logExpression = params["log_expression"]?.jsonPrimitive?.content
        val temporary = params["temporary"]?.jsonPrimitive?.booleanOrNull ?: false

        if (line < 1) {
            return ToolResult("Line number must be >= 1, got: $line", "Invalid line", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // Resolve and validate file path (prevents path traversal)
        val (absolutePath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
        if (pathError != null) return pathError

        return try {
            withContext(Dispatchers.EDT) {
                WriteAction.compute<ToolResult, Exception> {
                    val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath!!)
                        ?: return@compute ToolResult(
                            "File not found: $absolutePath",
                            "File not found",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )

                    val bpManager = XDebuggerManager.getInstance(project).breakpointManager

                    // Determine breakpoint type based on file extension
                    val fileUrl = vFile.url
                    val bpType = resolveBreakpointType(vFile.name)

                    val zeroBasedLine = line - 1
                    val bp: XLineBreakpoint<*> = bpManager.addLineBreakpoint(
                        bpType,
                        fileUrl,
                        zeroBasedLine,
                        null,
                        temporary
                    ) ?: return@compute ToolResult(
                        "Failed to add breakpoint at ${vFile.name}:$line — line may not be breakpointable",
                        "Add failed",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                    // Configure condition
                    if (condition != null) {
                        bp.conditionExpression = XExpressionImpl.fromText(condition)
                    }

                    // Configure log expression (non-suspending breakpoint)
                    if (logExpression != null) {
                        bp.logExpressionObject = XExpressionImpl.fromText(logExpression)
                        bp.suspendPolicy = SuspendPolicy.NONE
                    }

                    // Track for agent cleanup
                    controller.trackBreakpoint(bp)

                    // Build output
                    val fileName = vFile.name
                    val sb = StringBuilder("Breakpoint added at $fileName:$line")
                    if (condition != null) sb.append("\n  Condition: $condition")
                    if (logExpression != null) sb.append("\n  Log expression: $logExpression")

                    val traits = mutableListOf<String>()
                    if (condition != null) traits.add("conditional")
                    if (logExpression != null) traits.add("log")
                    if (temporary) traits.add("temporary")
                    val suspendType = if (logExpression != null) "non-suspend" else "suspend"
                    traits.add(suspendType)
                    sb.append("\n  Type: ${traits.joinToString(", ")}")

                    val content = sb.toString()
                    ToolResult(content, "Breakpoint at $fileName:$line", TokenEstimator.estimate(content))
                }
            }
        } catch (e: Exception) {
            ToolResult(
                "Error adding breakpoint: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    companion object {
        /**
         * Resolves the breakpoint type based on file extension.
         * Kotlin files use KotlinLineBreakpointType, everything else uses JavaLineBreakpointType.
         */
        fun resolveBreakpointType(fileName: String): com.intellij.xdebugger.breakpoints.XLineBreakpointType<*> {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return if (ext == "kt" || ext == "kts") {
                // Try Kotlin breakpoint type first, fall back to Java
                try {
                    val kotlinType = Class.forName("org.jetbrains.kotlin.idea.debugger.breakpoints.KotlinLineBreakpointType")
                    XDebuggerUtil.getInstance().findBreakpointType(
                        @Suppress("UNCHECKED_CAST")
                        (kotlinType as Class<out com.intellij.xdebugger.breakpoints.XLineBreakpointType<*>>)
                    )
                } catch (_: ClassNotFoundException) {
                    // Kotlin plugin not available, use Java type
                    XDebuggerUtil.getInstance().findBreakpointType(
                        com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType::class.java
                    )
                }
            } else {
                XDebuggerUtil.getInstance().findBreakpointType(
                    com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType::class.java
                )
            }
        }
    }
}
