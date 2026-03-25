package com.workflow.orchestrator.agent.tools.debug

import com.intellij.debugger.ui.breakpoints.JavaExceptionBreakpointType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.java.debugger.breakpoints.properties.JavaExceptionBreakpointProperties

/**
 * Sets a breakpoint that triggers when a specific exception type is thrown.
 * Much faster than finding the throw site — breaks wherever the exception occurs.
 *
 * Threading: Breakpoint creation via XBreakpointManager must run on EDT
 * inside a WriteAction.
 */
class ExceptionBreakpointTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "exception_breakpoint"
    override val description = "Set a breakpoint that triggers when a specific exception type is thrown. Much faster than finding the throw site — breaks wherever the exception occurs. Specify caught/uncaught filters."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "exception_class" to ParameterProperty(
                type = "string",
                description = "Fully qualified exception class name (e.g., java.lang.NullPointerException)"
            ),
            "caught" to ParameterProperty(
                type = "boolean",
                description = "Break on caught exceptions (default: true)"
            ),
            "uncaught" to ParameterProperty(
                type = "boolean",
                description = "Break on uncaught exceptions (default: true)"
            ),
            "condition" to ParameterProperty(
                type = "string",
                description = "Optional conditional expression (e.g., getMessage().contains(\"timeout\")). Breakpoint only triggers when condition is true."
            )
        ),
        required = listOf("exception_class")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val exceptionClass = params["exception_class"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Missing required parameter: exception_class",
                "Missing param",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val caught = params["caught"]?.jsonPrimitive?.booleanOrNull ?: true
        val uncaught = params["uncaught"]?.jsonPrimitive?.booleanOrNull ?: true
        val condition = params["condition"]?.jsonPrimitive?.content

        if (exceptionClass.isBlank()) {
            return ToolResult(
                "exception_class cannot be blank",
                "Invalid param",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            withContext(Dispatchers.EDT) {
                WriteAction.compute<ToolResult, Exception> {
                    val bpManager = XDebuggerManager.getInstance(project).breakpointManager

                    // Find the Java exception breakpoint type
                    val bpType = XDebuggerUtil.getInstance()
                        .findBreakpointType(JavaExceptionBreakpointType::class.java)
                        ?: return@compute ToolResult(
                            "Java exception breakpoint type not available — Java debugger plugin may not be installed",
                            "Type not found",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )

                    // Create exception breakpoint properties
                    val props = JavaExceptionBreakpointProperties(exceptionClass)
                    props.NOTIFY_CAUGHT = caught
                    props.NOTIFY_UNCAUGHT = uncaught

                    // Add the breakpoint
                    val bp = bpManager.addBreakpoint(bpType, props)
                        ?: return@compute ToolResult(
                            "Failed to create exception breakpoint for $exceptionClass",
                            "Creation failed",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )

                    // Configure condition if provided
                    if (condition != null) {
                        bp.conditionExpression = XExpressionImpl.fromText(condition)
                    }

                    // Track for agent cleanup
                    controller.trackGeneralBreakpoint(bp)

                    // Build output
                    val simpleName = exceptionClass.substringAfterLast('.')
                    val sb = StringBuilder("Exception breakpoint set for $exceptionClass")
                    sb.append("\n  Caught: $caught")
                    sb.append("\n  Uncaught: $uncaught")
                    if (condition != null) sb.append("\n  Condition: $condition")
                    sb.append("\n  Note: No validation that '$exceptionClass' exists in the classpath — verify the class name is correct")

                    val content = sb.toString()
                    ToolResult(
                        content,
                        "Exception breakpoint on $simpleName",
                        TokenEstimator.estimate(content)
                    )
                }
            }
        } catch (e: Exception) {
            ToolResult(
                "Error setting exception breakpoint: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
