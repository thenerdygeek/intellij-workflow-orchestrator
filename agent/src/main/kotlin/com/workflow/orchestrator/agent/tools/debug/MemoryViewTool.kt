package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Count live instances of a class in the JVM heap. Use to detect memory leaks
 * (e.g., Connection objects not being closed). Requires debug session to be paused.
 */
class MemoryViewTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "memory_view"
    override val description = "Count live instances of a class in the JVM heap. " +
        "Use to detect memory leaks (e.g., Connection objects not being closed). " +
        "Requires debug session to be paused."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Fully-qualified class name to inspect (e.g., java.sql.Connection)"
            ),
            "max_instances" to ParameterProperty(
                type = "integer",
                description = "Max instances to list details for (0=count only, >0 lists instance details). Default 0."
            )
        ),
        required = listOf("class_name")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Missing required parameter: class_name",
                "Missing param",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val maxInstances = params["max_instances"]?.jsonPrimitive?.intOrNull ?: 0

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (!session.isSuspended) {
            return ToolResult(
                "Session is not suspended. Memory view requires the debug session to be paused.",
                "Not suspended",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            controller.executeOnManagerThread(session) { _, vmProxy ->
                val vm = vmProxy.virtualMachine

                if (!vm.canGetInstanceInfo()) {
                    return@executeOnManagerThread ToolResult(
                        "VM does not support instance info (canGetInstanceInfo=false). " +
                            "This may be a remote or non-HotSpot JVM.",
                        "Not supported",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                val refTypes = vm.classesByName(className)
                if (refTypes.isEmpty()) {
                    return@executeOnManagerThread ToolResult(
                        "Class '$className' is not loaded in the JVM. " +
                            "It may not have been instantiated yet, or the name may be incorrect.",
                        "Class not loaded",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                val counts = vm.instanceCounts(refTypes)
                val totalCount = counts.sum()

                val content = buildString {
                    append("Memory view for: $className\n")
                    append("Total live instances: $totalCount\n")

                    // Show per-type breakdown if multiple reference types (e.g., subclasses)
                    if (refTypes.size > 1) {
                        append("\nBreakdown by type:\n")
                        refTypes.forEachIndexed { i, refType ->
                            append("  ${refType.name()}: ${counts[i]}\n")
                        }
                    }

                    // Optionally list instance details
                    if (maxInstances > 0 && totalCount > 0) {
                        val cappedMax = maxInstances.coerceAtMost(MAX_INSTANCE_DETAILS)
                        append("\nInstance details (first $cappedMax):\n")
                        val instances = refTypes[0].instances(cappedMax.toLong())
                        instances.forEachIndexed { i, instance ->
                            append("  [$i] ${instance.referenceType().name()} @ ${instance.uniqueID()}\n")
                        }
                        if (totalCount > cappedMax) {
                            append("  ... and ${totalCount - cappedMax} more\n")
                        }
                    }

                    append("\nSession: ${sessionId ?: controller.getActiveSessionId() ?: "unknown"}")
                }

                ToolResult(content, "$totalCount instances of $className", TokenEstimator.estimate(content))
            }
        } catch (e: Exception) {
            ToolResult(
                "Error viewing memory: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    companion object {
        /** Cap instance details to prevent massive output */
        const val MAX_INSTANCE_DETAILS = 50
    }
}
