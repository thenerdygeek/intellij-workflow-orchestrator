package com.workflow.orchestrator.agent.tools.debug

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.invokeLater
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * Launches a run configuration in debug mode. Returns a session ID for
 * subsequent debug operations (step, resume, evaluate, etc.).
 *
 * Threading: Configuration lookup on IO, launch on EDT via invokeLater, callback wait off-EDT.
 */
class StartDebugSessionTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "start_debug_session"
    override val description = "Launch a run configuration in debug mode. Returns session ID for subsequent debug operations."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "config_name" to ParameterProperty(
                type = "string",
                description = "Name of the run configuration to launch in debug mode"
            ),
            "wait_for_pause" to ParameterProperty(
                type = "integer",
                description = "Seconds to wait for first breakpoint hit (default 0 = don't wait)"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog)"
            )
        ),
        required = listOf("config_name", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Missing required parameter: config_name",
                "Missing param",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val waitForPause = params["wait_for_pause"]?.jsonPrimitive?.intOrNull ?: 0

        return try {
            // Find configuration
            val runManager = RunManager.getInstance(project)
            val settings = runManager.findConfigurationByName(configName)
                ?: return ToolResult(
                    "Run configuration not found: '$configName'. Use get_run_configurations to list available configurations.",
                    "Config not found",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            // Subscribe to debug session start off-EDT, launch on EDT, wait off-EDT
            val sessionId = withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine<String> { cont ->
                    val connection = project.messageBus.connect()
                    cont.invokeOnCancellation { connection.disconnect() }
                    connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                        override fun processStarted(debugProcess: XDebugProcess) {
                            val session = debugProcess.session
                            val id = controller.registerSession(session)
                            connection.disconnect()
                            if (cont.isActive) {
                                cont.resume(id)
                            }
                        }
                    })

                    // Only the launch call needs EDT
                    invokeLater {
                        try {
                            val executor = DefaultDebugExecutor.getDebugExecutorInstance()
                            val env = ExecutionEnvironmentBuilder.create(project, executor, settings.configuration).build()
                            ProgramRunnerUtil.executeConfiguration(env, true, true)
                        } catch (e: Exception) {
                            connection.disconnect()
                            if (cont.isActive) {
                                cont.resume("")
                            }
                        }
                    }
                }
            }

            if (sessionId == null || sessionId.isEmpty()) {
                return ToolResult(
                    "Debug session failed to start within 30 seconds. Check run configuration, build errors, or port conflicts.",
                    "Debug session timeout",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            // Optionally wait for first breakpoint hit
            val pauseEvent = if (waitForPause > 0) {
                controller.waitForPause(sessionId, waitForPause * 1000L)
            } else {
                null
            }

            // Build output
            val sb = StringBuilder("Debug session started: $sessionId\n")
            sb.append("Configuration: $configName\n")
            if (pauseEvent != null) {
                sb.append("Status: paused\n")
                sb.append("Location: ${pauseEvent.file ?: "unknown"}:${pauseEvent.line ?: "?"}\n")
                sb.append("Reason: ${pauseEvent.reason}")
            } else if (waitForPause > 0) {
                sb.append("Status: running (no breakpoint hit within ${waitForPause}s)")
            } else {
                sb.append("Status: running")
            }

            val content = sb.toString()
            ToolResult(content, "Debug session $sessionId started", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult(
                "Error starting debug session: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
