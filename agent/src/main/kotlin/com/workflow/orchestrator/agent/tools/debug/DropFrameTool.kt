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
 * Rewind execution to the beginning of a method frame. Variable state is NOT
 * reset. Side effects (file writes, network calls) are NOT undone. Cannot drop
 * frames holding locks or native frames.
 */
class DropFrameTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "drop_frame"
    override val description = "Rewind execution to the beginning of a method frame. Variable state is NOT reset. " +
        "Side effects (file writes, network calls) are NOT undone. Cannot drop frames holding locks or native frames."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            ),
            "frame_index" to ParameterProperty(
                type = "integer",
                description = "Frame index to drop to (0=current method, 1=caller). Default 0."
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val frameIndex = params["frame_index"]?.jsonPrimitive?.intOrNull ?: 0

        if (frameIndex < 0) {
            return ToolResult(
                "frame_index must be >= 0, got $frameIndex",
                "Invalid frame index",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (!session.isSuspended) {
            return ToolResult(
                "Session is not suspended. Cannot drop frame while running.",
                "Not suspended",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            controller.executeOnManagerThread(session) { _, vmProxy ->
                if (!vmProxy.canPopFrames()) {
                    return@executeOnManagerThread ToolResult(
                        "VM does not support frame popping (canPopFrames=false). This may be a remote or non-HotSpot JVM.",
                        "Not supported",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                val suspendContext = session.suspendContext
                    ?: return@executeOnManagerThread ToolResult(
                        "No suspend context available. Session may not be properly paused.",
                        "No suspend context",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                val activeStack = suspendContext.activeExecutionStack
                    ?: return@executeOnManagerThread ToolResult(
                        "No active execution stack. Cannot determine current thread.",
                        "No active stack",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                // Get the JDI thread from the VM proxy
                val vm = vmProxy.virtualMachine
                val threads = vm.allThreads()
                val threadName = activeStack.displayName

                // Find the matching suspended thread
                val thread = threads.firstOrNull { t ->
                    t.name() == threadName && t.isSuspended
                } ?: threads.firstOrNull { it.isSuspended }
                    ?: return@executeOnManagerThread ToolResult(
                        "No suspended thread found matching '$threadName'.",
                        "No suspended thread",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                val frames = thread.frames()
                if (frameIndex >= frames.size) {
                    return@executeOnManagerThread ToolResult(
                        "frame_index $frameIndex is out of range. Stack has ${frames.size} frames (0..${frames.size - 1}).",
                        "Frame out of range",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                val targetFrame = frames[frameIndex]
                val location = targetFrame.location()
                val methodName = "${location.declaringType().name()}.${location.method().name()}"

                thread.popFrames(targetFrame)

                val content = buildString {
                    append("Dropped frame: rewound to beginning of $methodName\n")
                    append("Frame index: $frameIndex\n")
                    append("Thread: ${thread.name()}\n")
                    append("Session: ${sessionId ?: controller.getActiveSessionId() ?: "unknown"}\n")
                    append("\nNote: Variable state is NOT reset. Side effects are NOT undone.")
                }
                ToolResult(content, "Dropped frame to $methodName", TokenEstimator.estimate(content))
            }
        } catch (e: Exception) {
            ToolResult(
                "Error dropping frame: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
