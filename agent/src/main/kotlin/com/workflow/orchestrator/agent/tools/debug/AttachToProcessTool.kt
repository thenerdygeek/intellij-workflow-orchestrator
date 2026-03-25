package com.workflow.orchestrator.agent.tools.debug

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.remote.RemoteConfiguration
import com.intellij.execution.remote.RemoteConfigurationType
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * Attach the debugger to an already running JVM. The target JVM must be started
 * with: -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
 */
class AttachToProcessTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "attach_to_process"
    override val description = "Attach the debugger to an already running JVM. " +
        "The target JVM must be started with: " +
        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "host" to ParameterProperty(
                type = "string",
                description = "Host to connect to (default: localhost)"
            ),
            "port" to ParameterProperty(
                type = "integer",
                description = "Debug port to connect to (e.g., 5005)"
            ),
            "name" to ParameterProperty(
                type = "string",
                description = "Display name for the debug configuration (optional)"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog)"
            )
        ),
        required = listOf("port", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val host = params["host"]?.jsonPrimitive?.content ?: "localhost"
        val port = params["port"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult(
                "Missing or invalid required parameter: port",
                "Missing param",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val displayName = params["name"]?.jsonPrimitive?.content
            ?: "[Agent] Remote Debug $host:$port"

        if (port < 1 || port > 65535) {
            return ToolResult(
                "Port must be between 1 and 65535, got $port",
                "Invalid port",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            // Create remote debug configuration and launch on EDT
            val sessionId = withContext(Dispatchers.EDT) {
                val runManager = RunManager.getInstance(project)
                val remoteConfigType = RemoteConfigurationType.getInstance()
                val settings = runManager.createConfiguration(displayName, remoteConfigType.factory)
                val remoteConfig = settings.configuration as RemoteConfiguration
                remoteConfig.HOST = host
                remoteConfig.PORT = port.toString()
                remoteConfig.SERVER_MODE = false
                remoteConfig.USE_SOCKET_TRANSPORT = true

                // Build execution environment for debug
                val executor = DefaultDebugExecutor.getDebugExecutorInstance()
                val env = ExecutionEnvironmentBuilder.create(project, executor, remoteConfig).build()

                // Listen for session start (30s timeout)
                withTimeoutOrNull(30_000L) {
                    suspendCancellableCoroutine<String> { cont ->
                        val connection = project.messageBus.connect()
                        cont.invokeOnCancellation { connection.disconnect() }
                        connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                            override fun processStarted(debugProcess: XDebugProcess) {
                                val session = debugProcess.session
                                val id = controller.registerSession(session)
                                connection.disconnect()
                                cont.resume(id)
                            }
                        })

                        // Launch the debug session
                        ProgramRunnerUtil.executeConfiguration(env, true, true)
                    }
                }
            }

            if (sessionId == null) {
                return ToolResult(
                    "Failed to attach to $host:$port within 30 seconds. " +
                        "Verify the target JVM is running with JDWP agent enabled on port $port.",
                    "Attach timeout",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            val content = buildString {
                append("Attached to remote JVM: $host:$port\n")
                append("Session: $sessionId\n")
                append("Configuration: $displayName\n")
                append("Status: connected")
            }
            ToolResult(content, "Attached to $host:$port as $sessionId", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult(
                "Error attaching to process at $host:$port: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
