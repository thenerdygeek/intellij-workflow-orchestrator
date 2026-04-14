package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.security.DefaultCommandFilter
import com.workflow.orchestrator.agent.security.FilterResult
import com.workflow.orchestrator.agent.tools.process.ManagedProcess
import com.workflow.orchestrator.agent.tools.process.OutputCollector
import com.workflow.orchestrator.agent.tools.process.ProcessEnvironment
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.agent.tools.process.ShellResolver
import com.workflow.orchestrator.agent.tools.process.ShellType
import com.workflow.orchestrator.agent.tools.process.ShellUnavailableException
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolOutputConfig
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class RunCommandTool : AgentTool {
    override val name = "run_command"
    override val description = "Execute a CLI command on the system. Use this when you need to perform system operations or run specific commands to accomplish any step in the user's task. You must tailor your command to the user's system and provide a clear explanation of what the command does via the description parameter. For command chaining, use the appropriate chaining syntax for the shell (e.g., '&&' for bash). Prefer to execute complex CLI commands over creating executable scripts, as they are more flexible and easier to run. Commands will be executed in the project directory by default. You MUST specify the shell type matching the available shells listed in your environment. If the process goes idle (no output for the idle timeout period), returns [IDLE] with the process ID — use send_stdin, ask_user_input, or kill_process to interact with it. Dangerous commands are blocked by the safety analyzer."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "command" to ParameterProperty(type = "string", description = "The CLI command to execute. This should be valid for the current operating system and the specified shell. Ensure the command is properly formatted and does not contain any harmful instructions."),
            "shell" to ParameterProperty(
                type = "string",
                description = "Shell to execute the command in. Use ONLY shells listed as available in your environment. bash = Unix/Git Bash syntax (ls, grep, cat, &&). cmd = Windows cmd.exe syntax (dir, type, findstr). powershell = PowerShell syntax (Get-ChildItem, Select-String).",
                enumValues = listOf("bash", "cmd", "powershell")
            ),
            "working_dir" to ParameterProperty(type = "string", description = "Working directory (absolute or relative to project root). Optional, defaults to project root. Example: 'src/main/kotlin'"),
            "description" to ParameterProperty(type = "string", description = "A clear explanation of what the command does and why (shown to user in approval dialog)."),
            "timeout" to ParameterProperty(type = "integer", description = "Timeout in seconds. Default: 120, max: 600."),
            "idle_timeout" to ParameterProperty(type = "integer", description = "Idle detection threshold in seconds. Default: 15 (60 for build commands). Process returns [IDLE] if no output for this many seconds."),
            "env" to ParameterProperty(type = "object", description = "Custom environment variables to set for the command. Keys are variable names, values are strings. System/path variables (PATH, HOME, LD_PRELOAD, etc.) are blocked for safety."),
            "separate_stderr" to ParameterProperty(type = "boolean", description = "When true, capture stderr separately and append as [STDERR] section. Default: false (stderr merged with stdout).")
        ),
        required = listOf("command", "shell", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)
    override val timeoutMs: Long get() = AgentTool.LONG_TOOL_TIMEOUT_MS
    override val outputConfig = ToolOutputConfig.COMMAND

    companion object {
        private val LOG = Logger.getInstance(RunCommandTool::class.java)
        private const val DEFAULT_TIMEOUT_SECONDS = 120L
        private const val MAX_TIMEOUT_SECONDS = 600L
        private const val MAX_OUTPUT_CHARS = 30_000
        // Idle thresholds are read from AgentSettings.commandIdleThresholdSeconds and
        // AgentSettings.buildCommandIdleThresholdSeconds at execution time. Defaults
        // are 15s / 60s respectively (set in AgentSettings.State).
        private const val IO_DRAIN_TIMEOUT_MS = 2000L
        private val processIdCounter = AtomicLong(0)

        private val commandFilter = DefaultCommandFilter()

        /**
         * Stream callback for real-time output delivery to the UI.
         * Set by the session/controller before tool execution.
         * Receives (toolCallId, chunk) pairs as output lines arrive.
         */
        var streamCallback: ((toolCallId: String, chunk: String) -> Unit)? = null

        /**
         * Current tool call ID, set by the execution layer before calling execute().
         * Used to register processes in ProcessRegistry and route stream callbacks.
         */
        var currentToolCallId: ThreadLocal<String?> = ThreadLocal.withInitial { null }

        // ── Backward-compat shims ──────────────────

        @Deprecated("Use DefaultCommandFilter().check() instead")
        fun isBlocked(command: String): Boolean =
            DefaultCommandFilter().check(command, ShellType.BASH) is FilterResult.Reject

        @Deprecated("Use DefaultCommandFilter().check() instead")
        fun isHardBlocked(command: String): Boolean = isBlocked(command)

        @Deprecated("Use ShellResolver.isLikelyBuildCommand() instead")
        fun isLikelyBuildCommand(command: String): Boolean = ShellResolver.isLikelyBuildCommand(command)

        @Deprecated("Use OutputCollector.stripAnsi() instead")
        fun stripAnsi(text: String): String = OutputCollector.stripAnsi(text)

        @Deprecated("Use ShellResolver.isLikelyPasswordPrompt() instead")
        fun isLikelyPasswordPrompt(lastOutput: String): Boolean = ShellResolver.isLikelyPasswordPrompt(lastOutput)

        @Deprecated("Use ShellResolver.detectAvailableShells() instead")
        fun detectAvailableShells(project: Project? = null): List<String> =
            ShellResolver.detectAvailableShells(project).map { it.shellType.name.lowercase() }
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // 1. Parse params
        val command = params["command"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'command' parameter required", "Error: missing command", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val shell = params["shell"]?.jsonPrimitive?.content?.lowercase()
        val separateStderr = params["separate_stderr"]?.jsonPrimitive?.boolean ?: false
        val userEnv: Map<String, String> = parseEnvParam(params)

        // 2. Resolve shell
        val shellConfig = try {
            ShellResolver.resolve(shell, project)
        } catch (e: ShellUnavailableException) {
            return ToolResult(
                "Error: ${e.message}",
                "Error: shell unavailable",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // 3. Filter command
        val filterResult = commandFilter.check(command, shellConfig.shellType)
        if (filterResult is FilterResult.Reject) {
            LOG.warn("[Agent:RunCommand] BLOCKED command: ${command.take(100)}")
            return ToolResult(
                "Error: Command blocked for safety: ${filterResult.reason}",
                "Error: blocked command",
                5,
                isError = true
            )
        }

        // 4. Parse env, filter blocked vars
        val (safeEnv, rejectedEnv) = ProcessEnvironment.filterUserEnv(userEnv)
        if (rejectedEnv.isNotEmpty()) {
            LOG.info("[Agent:RunCommand] Rejected env vars: ${rejectedEnv.joinToString(", ")}")
        }

        // 5. Validate working directory
        val workingDir = params["working_dir"]?.jsonPrimitive?.content?.let { dir ->
            val (validated, error) = PathValidator.resolveAndValidate(dir, project.basePath)
            if (error != null) return error
            validated!!
        } ?: (project.basePath ?: ".")

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

            // 6. Build command line from ShellConfig
            val commandLine = GeneralCommandLine(
                shellConfig.executable, *shellConfig.args.toTypedArray(), command
            )

            val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.int?.toLong() ?: DEFAULT_TIMEOUT_SECONDS)
                .coerceIn(1, MAX_TIMEOUT_SECONDS)
            val timeoutMs = timeoutSeconds * 1000

            commandLine.workDirectory = workDir

            // 7. Configure stderr
            if (!separateStderr) {
                commandLine.withRedirectErrorStream(true)
            }

            // 8. Apply environment
            val env = commandLine.environment
            ProcessEnvironment.applyToEnvironment(env, isWindows, safeEnv)

            // 9. Spawn process
            val process = commandLine.createProcess()

            // Determine tool call ID for ProcessRegistry and streaming
            val toolCallId = currentToolCallId.get()
                ?: "run-cmd-${processIdCounter.incrementAndGet()}"

            // Register in ProcessRegistry for kill/killAll/stdin support
            val managed = ProcessRegistry.register(toolCallId, process, command)

            // Determine idle threshold — caller-supplied param wins, otherwise read from
            // AgentSettings (defaults: 15s / 60s for build commands).
            val agentSettings = com.workflow.orchestrator.agent.settings.AgentSettings.getInstance(project).state
            val idleThresholdMs = params["idle_timeout"]?.jsonPrimitive?.int?.let { it * 1000L }
                ?: if (ShellResolver.isLikelyBuildCommand(command))
                    agentSettings.buildCommandIdleThresholdSeconds * 1000L
                else
                    agentSettings.commandIdleThresholdSeconds * 1000L

            // 10. Start reader thread(s)
            val activeStreamCallback: ((String, String) -> Unit)? = streamCallback

            // Stdout reader thread
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(4096)
                        var bytesRead = reader.read(buffer)
                        while (bytesRead != -1) {
                            val chunk = String(buffer, 0, bytesRead)
                            managed.outputLines.add(chunk)
                            managed.lastOutputAt.set(System.currentTimeMillis())
                            activeStreamCallback?.invoke(toolCallId, chunk)
                            bytesRead = reader.read(buffer)
                        }
                    }
                } catch (_: Exception) {
                    // Process killed or stream closed — expected during timeout/cancel
                } finally {
                    managed.readerDone.countDown()
                }
            }.apply {
                isDaemon = true
                name = "RunCommand-Output-$toolCallId"
                start()
            }

            // Stderr reader thread (only when separate_stderr is true)
            val stderrLines = if (separateStderr) java.util.concurrent.CopyOnWriteArrayList<String>() else null
            val stderrReaderThread = if (separateStderr) {
                Thread {
                    try {
                        process.errorStream.bufferedReader().use { reader ->
                            val buffer = CharArray(4096)
                            var bytesRead = reader.read(buffer)
                            while (bytesRead != -1) {
                                stderrLines!!.add(String(buffer, 0, bytesRead))
                                bytesRead = reader.read(buffer)
                            }
                        }
                    } catch (_: Exception) {
                        // Expected on kill/cancel
                    }
                }.apply {
                    isDaemon = true
                    name = "RunCommand-Stderr-$toolCallId"
                    start()
                }
            } else null

            // 11. Monitor loop
            while (true) {
                coroutineContext.ensureActive()
                delay(500)
                val now = System.currentTimeMillis()

                // Priority 1: process exited
                if (!process.isAlive) {
                    readerThread.join(IO_DRAIN_TIMEOUT_MS)
                    stderrReaderThread?.join(IO_DRAIN_TIMEOUT_MS)
                    ProcessRegistry.unregister(toolCallId)
                    return buildExitResult(managed, command, params, stderrLines)
                }

                // Priority 2: total timeout
                if (now - managed.startedAt > timeoutMs) {
                    ProcessRegistry.kill(toolCallId)
                    readerThread.join(IO_DRAIN_TIMEOUT_MS)
                    stderrReaderThread?.join(IO_DRAIN_TIMEOUT_MS)
                    return buildTimeoutResult(managed, timeoutSeconds)
                }

                // Priority 3: idle detection (only after first output)
                val lastOutput = managed.lastOutputAt.get()
                if (lastOutput > 0 && now - lastOutput >= idleThresholdMs) {
                    managed.idleSignaledAt.set(now)
                    return buildIdleResult(managed, idleThresholdMs / 1000)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable: while(true) always returns")
        } catch (e: CancellationException) {
            throw e // Propagate for structured concurrency
        } catch (e: Exception) {
            ToolResult(
                "Error executing command: ${e.message}",
                "Error: ${e.message}",
                5,
                isError = true
            )
        }
    }

    // ── Private helpers ──────────────────

    private fun parseEnvParam(params: JsonObject): Map<String, String> {
        val envJson = params["env"] ?: return emptyMap()
        return try {
            val obj = envJson.jsonObject
            obj.mapValues { it.value.jsonPrimitive.content }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun collectOutput(managed: ManagedProcess): String {
        return managed.outputLines.joinToString("")
    }

    private fun lastOutputLines(managed: ManagedProcess, lineCount: Int = 10): String {
        val allOutput = collectOutput(managed)
        val lines = allOutput.lines()
        return lines.takeLast(lineCount).joinToString("\n")
    }

    private fun buildExitResult(
        managed: ManagedProcess,
        command: String,
        params: JsonObject,
        stderrLines: List<String>?
    ): ToolResult {
        val rawOutput = collectOutput(managed)
        val processed = OutputCollector.processOutput(
            rawOutput = rawOutput,
            maxResultChars = MAX_OUTPUT_CHARS,
            spillDir = null,
            toolCallId = currentToolCallId.get()
        )

        val exitCode = managed.process.exitValue()

        val contentBuilder = StringBuilder()
        contentBuilder.append("Exit code: $exitCode\n")
        contentBuilder.append(processed.content)

        // Append separate stderr if captured
        if (stderrLines != null && stderrLines.isNotEmpty()) {
            val rawStderr = stderrLines.joinToString("")
            val stderrProcessed = OutputCollector.processOutput(
                rawOutput = rawStderr,
                maxResultChars = MAX_OUTPUT_CHARS / 2,
                spillDir = null,
                toolCallId = null
            )
            contentBuilder.append("\n\n[STDERR]\n")
            contentBuilder.append(stderrProcessed.content)
        }

        val content = contentBuilder.toString()
        val description = params["description"]?.jsonPrimitive?.content
        val summary = if (description != null) {
            "$description — exit code $exitCode"
        } else {
            "Command exited with code $exitCode: ${command.take(80)}"
        }
        return ToolResult(
            content = content,
            summary = summary,
            tokenEstimate = TokenEstimator.estimate(content),
            isError = exitCode != 0
        )
    }

    private fun buildTimeoutResult(managed: ManagedProcess, timeoutSeconds: Long): ToolResult {
        val rawOutput = collectOutput(managed)
        val processed = OutputCollector.processOutput(
            rawOutput = rawOutput,
            maxResultChars = MAX_OUTPUT_CHARS,
            spillDir = null,
            toolCallId = currentToolCallId.get()
        )
        val content = "[TIMEOUT] Command timed out after ${timeoutSeconds}s.\nPartial output:\n${processed.content}"
        return ToolResult(
            content = content,
            summary = "Error: command timed out",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = true
        )
    }

    private fun buildIdleResult(managed: ManagedProcess, idleSeconds: Long): ToolResult {
        val processId = managed.toolCallId
        val command = managed.command
        val lastLines = OutputCollector.stripAnsi(lastOutputLines(managed, 10))
        val indentedLines = lastLines.lines().joinToString("\n") { "  $it" }

        val passwordWarning = if (ShellResolver.isLikelyPasswordPrompt(lastLines)) {
            "\nWARNING: Last output appears to be a password/credential prompt. Use ask_user_input, not send_stdin.\n"
        } else {
            ""
        }

        val content = buildString {
            appendLine("[IDLE] Process idle for ${idleSeconds}s — no output since last line.")
            appendLine("Process still running (ID: $processId, command: $command).")
            appendLine()
            appendLine("Last output:")
            appendLine(indentedLines)
            append(passwordWarning)
            appendLine()
            appendLine("Options:")
            appendLine("- send_stdin(process_id=\"$processId\", input=\"<your input>\\n\") to provide input")
            appendLine("- ask_user_input(process_id=\"$processId\", description=\"...\", prompt=\"...\") for user input")
            appendLine("- kill_process(process_id=\"$processId\") to abort")
        }

        return ToolResult(
            content = content,
            summary = "Process idle — waiting for input (ID: $processId)",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = false
        )
    }
}
