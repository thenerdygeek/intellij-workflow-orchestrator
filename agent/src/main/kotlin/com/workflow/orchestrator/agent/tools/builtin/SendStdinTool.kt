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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_STDIN_PER_PROCESS = 10
private const val MONITOR_POLL_MS = 500L
private const val IDLE_AFTER_STDIN_MS = 10_000L
private const val MAX_WAIT_AFTER_STDIN_MS = 60_000L
private const val IDLE_LABEL = "stdin"

class SendStdinTool : AgentTool {
    override val name = "send_stdin"
    override val description = "Send input to a running process's stdin. Use when a command is waiting for " +
        "input that you can determine from context (e.g., confirmation prompts, menu selections). " +
        "NEVER use for passwords, tokens, or secrets — use ask_user_input instead. Max 10 sends per process."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "process_id" to ParameterProperty(type = "string", description = "The process ID from the [IDLE] message."),
            "input" to ParameterProperty(type = "string", description = "Text to send to stdin. Include \\n for Enter key.")
        ),
        required = listOf("process_id", "input")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    companion object {
        private val LOG = Logger.getInstance(SendStdinTool::class.java)
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val processId = params["process_id"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'process_id' parameter required",
                "Error: missing process_id",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val input = params["input"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'input' parameter required",
                "Error: missing input",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        // Step 2: Look up process
        val managed = ProcessRegistry.get(processId)
            ?: return ToolResult(
                "Error: Process '$processId' not found. It may have exited or the ID is incorrect.",
                "Error: process not found",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        // Step 3: Check if alive
        if (!managed.process.isAlive) {
            val exitCode = try { managed.process.exitValue() } catch (_: Exception) { -1 }
            return ToolResult(
                "Error: Process '$processId' is no longer running (exit code: $exitCode).",
                "Error: process dead",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Step 4: Rate limit check
        if (managed.stdinCount.get() >= MAX_STDIN_PER_PROCESS) {
            return ToolResult(
                "Error: Stdin limit ($MAX_STDIN_PER_PROCESS) exceeded for process '$processId'. " +
                    "Kill the process with kill_process and rerun using a non-interactive command instead.",
                "Error: stdin limit exceeded",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Step 5: Password detection — check last output
        val lastOutput = managed.outputLines.toList().joinToString("")
        if (RunCommandTool.isLikelyPasswordPrompt(lastOutput)) {
            return ToolResult(
                "Error: The process appears to be waiting for a password, token, or secret. " +
                    "Use ask_user_input instead of send_stdin for credential prompts.",
                "Error: password prompt detected — use ask_user_input",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Step 6: Write stdin
        val written = ProcessRegistry.writeStdin(processId, input)
        if (!written) {
            return ToolResult(
                "Error: Failed to write to stdin of process '$processId'. The process may have exited.",
                "Error: stdin write failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        LOG.info("[Agent:SendStdin] Sent ${input.length} chars to process $processId")

        // Step 7: Reset idle signal — we just interacted
        managed.idleSignaledAt.set(0)

        // Step 8: Snapshot output size before monitoring
        val outputSizeBeforeStdin = managed.outputLines.size

        // Step 9: Monitor loop — same pattern as RunCommandTool
        val stdinSentAt = System.currentTimeMillis()
        var lastNewOutputAt = System.currentTimeMillis()
        var lastCheckedSize = outputSizeBeforeStdin

        while (true) {
            kotlinx.coroutines.delay(MONITOR_POLL_MS)
            val now = System.currentTimeMillis()

            // Priority 1: process exited
            if (!managed.process.isAlive) {
                val newOutput = ProcessToolHelpers.collectNewOutput(managed, outputSizeBeforeStdin)
                val stripped = RunCommandTool.stripAnsi(newOutput)
                val exitCode = try { managed.process.exitValue() } catch (_: Exception) { -1 }
                ProcessRegistry.unregister(processId)
                val content = "Exit code: $exitCode\n$stripped"
                return ToolResult(
                    content = content,
                    summary = "Process exited with code $exitCode after stdin",
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

            // Priority 2: max wait after stdin exceeded (60s)
            if (now - stdinSentAt > MAX_WAIT_AFTER_STDIN_MS) {
                val newOutput = ProcessToolHelpers.collectNewOutput(managed, outputSizeBeforeStdin)
                val stripped = RunCommandTool.stripAnsi(newOutput)
                managed.idleSignaledAt.set(now)
                val content = ProcessToolHelpers.buildIdleContent(processId, stripped, now - stdinSentAt, IDLE_LABEL)
                return ToolResult(
                    content = content,
                    summary = "Process idle after stdin — waiting for more input (ID: $processId)",
                    tokenEstimate = TokenEstimator.estimate(content),
                    isError = false
                )
            }

            // Priority 3: new output stopped for 10s after stdin was sent
            val timeSinceLastOutput = now - lastNewOutputAt
            val timeSinceStdin = now - stdinSentAt
            // Only apply idle-after-output check once we've seen some output post-stdin
            // and output has stopped for IDLE_AFTER_STDIN_MS
            if (timeSinceStdin > 500 && lastCheckedSize > outputSizeBeforeStdin && timeSinceLastOutput >= IDLE_AFTER_STDIN_MS) {
                val newOutput = ProcessToolHelpers.collectNewOutput(managed, outputSizeBeforeStdin)
                val stripped = RunCommandTool.stripAnsi(newOutput)
                managed.idleSignaledAt.set(now)
                val content = ProcessToolHelpers.buildIdleContent(processId, stripped, timeSinceLastOutput, IDLE_LABEL)
                return ToolResult(
                    content = content,
                    summary = "Process idle after stdin — waiting for more input (ID: $processId)",
                    tokenEstimate = TokenEstimator.estimate(content),
                    isError = false
                )
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable: while(true) always returns")
    }
}
