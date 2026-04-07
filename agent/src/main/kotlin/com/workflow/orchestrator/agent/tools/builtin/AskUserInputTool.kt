package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class AskUserInputTool : AgentTool {
    override val name = "ask_user_input"
    override val description = "Ask the user to provide input for a running process. Use when a process " +
        "is waiting for a password, token, secret, or any input that the agent cannot determine from context. " +
        "Shows a prompt in the chat UI and sends the user's response to the process's stdin."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "process_id" to ParameterProperty(type = "string", description = "The process ID from the [IDLE] message."),
            "description" to ParameterProperty(type = "string", description = "Human-readable description of what input is needed and why."),
            "prompt" to ParameterProperty(type = "string", description = "Optional prompt text shown to the user (e.g., the actual prompt line from the process output).")
        ),
        required = listOf("process_id", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    companion object {
        private val LOG = Logger.getInstance(AskUserInputTool::class.java)
        private const val USER_INPUT_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
        private const val MONITOR_POLL_MS = 500L
        private const val IDLE_AFTER_INPUT_MS = 10_000L
        private const val MAX_WAIT_AFTER_INPUT_MS = 60_000L
        private const val IDLE_LABEL = "user input"

        var showInputCallback: ((processId: String, description: String, prompt: String, command: String) -> Unit)? = null

        @Volatile
        var pendingInput: CompletableDeferred<String>? = null

        fun resolveInput(input: String) {
            pendingInput?.complete(input)
        }
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val processId = params["process_id"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'process_id' parameter required",
                "Error: missing process_id",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val description = params["description"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'description' parameter required",
                "Error: missing description",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val prompt = params["prompt"]?.jsonPrimitive?.content ?: ""

        // Look up process
        val managed = ProcessRegistry.get(processId)
            ?: return ToolResult(
                "Error: Process '$processId' not found. It may have exited or the ID is incorrect.",
                "Error: process not found",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        // Check if alive
        if (!managed.process.isAlive) {
            val exitCode = try { managed.process.exitValue() } catch (_: Exception) { -1 }
            return ToolResult(
                "Error: Process '$processId' is no longer running (exit code: $exitCode).",
                "Error: process dead",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Create deferred for user input
        val deferred = CompletableDeferred<String>()
        pendingInput = deferred

        // Show input UI in chat
        showInputCallback?.invoke(processId, description, prompt, managed.command)

        // Wait for user input with timeout
        val userInput = withTimeoutOrNull(USER_INPUT_TIMEOUT_MS) { deferred.await() }
        pendingInput = null

        if (userInput == null) {
            // Timeout — kill process
            ProcessRegistry.kill(processId)
            return ToolResult(
                "User did not respond within 5 minutes. Process killed.",
                "Error: user input timeout — process killed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Write user input to process stdin
        val written = ProcessRegistry.writeStdin(processId, userInput)
        if (!written) {
            return ToolResult(
                "Error: Failed to write user input to process '$processId'. The process may have exited.",
                "Error: stdin write failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        LOG.info("[Agent:AskUserInput] Sent ${userInput.length} chars of user input to process $processId")

        // Reset idle signal — we just interacted
        managed.idleSignaledAt.set(0)

        // Snapshot output size before monitoring
        val outputSizeBeforeInput = managed.outputLines.size

        // Monitor loop — same pattern as SendStdinTool
        val inputSentAt = System.currentTimeMillis()
        var lastNewOutputAt = System.currentTimeMillis()
        var lastCheckedSize = outputSizeBeforeInput

        while (true) {
            kotlinx.coroutines.delay(MONITOR_POLL_MS)
            val now = System.currentTimeMillis()

            // Priority 1: process exited
            if (!managed.process.isAlive) {
                val newOutput = ProcessToolHelpers.collectNewOutput(managed, outputSizeBeforeInput)
                val stripped = RunCommandTool.stripAnsi(newOutput)
                val exitCode = try { managed.process.exitValue() } catch (_: Exception) { -1 }
                ProcessRegistry.unregister(processId)
                val content = "Exit code: $exitCode\n$stripped"
                return ToolResult(
                    content = content,
                    summary = "Process exited with code $exitCode after user input",
                    tokenEstimate = TokenEstimator.estimate(content),
                    isError = exitCode != 0
                )
            }

            // Update last-new-output timestamp if we got new lines
            val currentSize = managed.outputLines.size
            if (currentSize > lastCheckedSize) {
                lastNewOutputAt = now
                lastCheckedSize = currentSize
            }

            // Priority 2: max wait after input exceeded (60s)
            if (now - inputSentAt > MAX_WAIT_AFTER_INPUT_MS) {
                val newOutput = ProcessToolHelpers.collectNewOutput(managed, outputSizeBeforeInput)
                val stripped = RunCommandTool.stripAnsi(newOutput)
                managed.idleSignaledAt.set(now)
                val content = ProcessToolHelpers.buildIdleContent(processId, stripped, now - inputSentAt, IDLE_LABEL)
                return ToolResult(
                    content = content,
                    summary = "Process idle after user input — waiting for more input (ID: $processId)",
                    tokenEstimate = TokenEstimator.estimate(content),
                    isError = false
                )
            }

            // Priority 3: new output stopped for 10s after input was sent
            val timeSinceLastOutput = now - lastNewOutputAt
            val timeSinceInput = now - inputSentAt
            if (timeSinceInput > 500 && lastCheckedSize > outputSizeBeforeInput && timeSinceLastOutput >= IDLE_AFTER_INPUT_MS) {
                val newOutput = ProcessToolHelpers.collectNewOutput(managed, outputSizeBeforeInput)
                val stripped = RunCommandTool.stripAnsi(newOutput)
                managed.idleSignaledAt.set(now)
                val content = ProcessToolHelpers.buildIdleContent(processId, stripped, timeSinceLastOutput, IDLE_LABEL)
                return ToolResult(
                    content = content,
                    summary = "Process idle after user input — waiting for more input (ID: $processId)",
                    tokenEstimate = TokenEstimator.estimate(content),
                    isError = false
                )
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable: while(true) always returns")
    }
}
