package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

class RunCommandTool : AgentTool {
    override val name = "run_command"
    override val description = "Execute a shell command in the project directory. Has a 60-second timeout and 4000-character output limit. Dangerous commands are blocked."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "command" to ParameterProperty(type = "string", description = "The shell command to execute"),
            "working_dir" to ParameterProperty(type = "string", description = "Working directory. Optional, defaults to project root.")
        ),
        required = listOf("command")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    companion object {
        private const val TIMEOUT_SECONDS = 60L
        private const val MAX_OUTPUT_CHARS = 4000

        private val BLOCKED_PATTERNS = listOf(
            Regex("""rm\s+-rf\s+/"""),
            Regex("""rm\s+-rf\s+~"""),
            Regex("""rm\s+-rf\s+\*"""),
            Regex("""rm\s+-rf\s+\.\s"""),
            Regex("""^\s*sudo\s"""),
            Regex("""mkfs\."""),
            Regex("""dd\s+if="""),
            Regex(""":>\s*/"""),
            Regex(""">\s*/dev/sd"""),
            Regex("""chmod\s+-R\s+777\s+/"""),
            Regex("""chown\s+-R\s+.*\s+/"""),
            Regex("""curl\s+.*\|\s*sh"""),
            Regex("""curl\s+.*\|\s*bash"""),
            Regex("""wget\s+.*\|\s*sh"""),
            Regex("""wget\s+.*\|\s*bash"""),
            Regex("""fork\s*bomb""", RegexOption.IGNORE_CASE),
            Regex(""":\(\)\s*\{"""),
        )

        fun isBlocked(command: String): Boolean {
            return BLOCKED_PATTERNS.any { it.containsMatchIn(command) }
        }
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val command = params["command"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'command' parameter required", "Error: missing command", 5, isError = true)

        if (isBlocked(command)) {
            return ToolResult(
                "Error: Command blocked for safety: $command",
                "Error: blocked command",
                5,
                isError = true
            )
        }

        val basePath = project.basePath ?: "."
        val workingDir = params["working_dir"]?.jsonPrimitive?.content?.let { dir ->
            if (dir.startsWith("/")) dir else "$basePath/$dir"
        } ?: basePath

        val workDir = File(workingDir)
        if (!workDir.exists() || !workDir.isDirectory) {
            return ToolResult(
                "Error: Working directory not found: $workingDir",
                "Error: working dir not found",
                5,
                isError = true
            )
        }

        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val processBuilder = if (isWindows) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("sh", "-c", command)
            }

            processBuilder.directory(workDir)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()
            val completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val output = process.inputStream.bufferedReader().readText()
            val truncatedOutput = if (output.length > MAX_OUTPUT_CHARS) {
                output.take(MAX_OUTPUT_CHARS) + "\n... (output truncated, ${output.length - MAX_OUTPUT_CHARS} chars omitted)"
            } else {
                output
            }

            if (!completed) {
                process.destroyForcibly()
                return ToolResult(
                    "Error: Command timed out after ${TIMEOUT_SECONDS}s.\nPartial output:\n$truncatedOutput",
                    "Error: command timed out",
                    TokenEstimator.estimate(truncatedOutput),
                    isError = true
                )
            }

            val exitCode = process.exitValue()
            val summary = "Command exited with code $exitCode: ${command.take(80)}"
            ToolResult(
                content = "Exit code: $exitCode\n$truncatedOutput",
                summary = summary,
                tokenEstimate = TokenEstimator.estimate(truncatedOutput),
                isError = exitCode != 0
            )
        } catch (e: Exception) {
            ToolResult(
                "Error executing command: ${e.message}",
                "Error: ${e.message}",
                5,
                isError = true
            )
        }
    }
}
