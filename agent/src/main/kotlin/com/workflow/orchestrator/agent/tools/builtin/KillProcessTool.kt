package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class KillProcessTool : AgentTool {
    override val name = "kill_process"
    override val description = "Kill a running process. Use when a process is stuck, unresponsive, or no longer needed."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "process_id" to ParameterProperty(type = "string", description = "The process ID to kill.")
        ),
        required = listOf("process_id")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val processId = params["process_id"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'process_id' parameter required",
                summary = "Error: missing process_id",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val managed = ProcessRegistry.get(processId)
            ?: return ToolResult(
                content = "Error: Process '$processId' not found or already exited.",
                summary = "Error: process not found",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val command = managed.command

        // Collect partial output before killing
        val rawOutput = RunCommandTool.stripAnsi(managed.outputLines.joinToString(""))

        val readerLatch = managed.readerDone
        ProcessRegistry.kill(processId)

        // Wait for reader thread to finish draining output (max 2s)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            readerLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        }

        // Truncate to last 5000 chars if longer
        val output = if (rawOutput.length > 5000) rawOutput.takeLast(5000) else rawOutput

        val content = "[KILLED] Process terminated (ID: $processId, command: $command).\n\nPartial output:\n$output"

        return ToolResult(
            content = content,
            summary = "Process killed: $command",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = false
        )
    }
}
