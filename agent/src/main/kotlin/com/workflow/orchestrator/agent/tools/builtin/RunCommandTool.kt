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

class RunCommandTool(
    allowedShells: List<String> = listOf("bash", "cmd", "powershell")
) : AgentTool {
    override val name = "run_command"
    override val description = "Execute a CLI command on the system. Use this when you need to perform system operations or run specific commands to accomplish any step in the user's task. You must tailor your command to the user's system and provide a clear explanation of what the command does via the description parameter. For command chaining, use the appropriate chaining syntax for the shell (e.g., '&&' for bash). Prefer to execute complex CLI commands over creating executable scripts, as they are more flexible and easier to run. Commands will be executed in the project directory by default. You MUST specify the shell type matching the available shells listed in your environment. If the process goes idle (no output for the idle timeout period), an inline classification note is emitted and the tool keeps waiting (on_idle=notify, default) — or idle detection is skipped entirely (on_idle=wait). Dangerous commands are blocked by the safety analyzer."
    override val parameters: FunctionParameters = buildShellParameters(allowedShells)
    override val allowedWorkers = setOf(WorkerType.CODER)
    override val timeoutMs: Long get() = AgentTool.LONG_TOOL_TIMEOUT_MS
    override val outputConfig = ToolOutputConfig.COMMAND

    companion object {
        private val LOG = Logger.getInstance(RunCommandTool::class.java)
        private const val DEFAULT_TIMEOUT_SECONDS = 120L
        private const val MAX_TIMEOUT_SECONDS = 600L
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
        @Volatile
        var streamCallback: ((toolCallId: String, chunk: String) -> Unit)? = null

        /**
         * Current tool call ID, set by the execution layer before calling execute().
         * Used to register processes in ProcessRegistry and route stream callbacks.
         */
        var currentToolCallId: ThreadLocal<String?> = ThreadLocal.withInitial { null }

        /**
         * Set by AgentLoop before tool execution. Used to scope background processes
         * to the owning session (background: true registers into BackgroundPool[sessionId]).
         * ThreadLocal<String?>; value is null when not running inside a session-scoped
         * tool call (e.g. most test harnesses).
         */
        var currentSessionId: ThreadLocal<String?> = ThreadLocal.withInitial { null }

        // streamCallback and currentToolCallId are still read by RuntimeExecTool
        // and SonarTool. Remove once those tools are migrated to explicit parameters.

        /**
         * Builds the FunctionParameters for run_command based on which shells are available.
         *
         * When [allowedShells] has exactly one entry, the `shell` parameter is omitted entirely —
         * the LLM never sees it and will not send it. execute() handles a missing `shell` param
         * by falling back to ShellResolver.resolve(null, project) which picks the platform default.
         *
         * When multiple shells are available, `shell` is included as a required enum param with
         * a description that only names the available shells.
         *
         * Internal visibility allows tests to call this directly without constructing the full tool.
         */
        internal fun buildShellParameters(allowedShells: List<String>): FunctionParameters {
            // Guard: empty list means detection found nothing — fall back to bash so the
            // schema is always valid. This prevents an empty enumValues in the LLM schema.
            val effective = allowedShells.ifEmpty { listOf("bash") }
            val singleShell = effective.size == 1

            // Base properties excluding shell (preserves command-first ordering)
            val baseProps = linkedMapOf(
                "command" to ParameterProperty(
                    type = "string",
                    description = "The CLI command to execute. This should be valid for the current operating system and the specified shell. Ensure the command is properly formatted and does not contain any harmful instructions."
                ),
                "working_dir" to ParameterProperty(
                    type = "string",
                    description = "Working directory (absolute or relative to project root). Optional, defaults to project root. Example: 'src/main/kotlin'"
                ),
                "description" to ParameterProperty(
                    type = "string",
                    description = "A clear explanation of what the command does and why (shown to user in approval dialog)."
                ),
                "timeout" to ParameterProperty(
                    type = "integer",
                    description = "Timeout in seconds. Default: 120, max: 600."
                ),
                "idle_timeout" to ParameterProperty(
                    type = "integer",
                    description = "Idle detection threshold in seconds. Default: 15 (60 for build commands). When on_idle=notify (default), an inline classification note is emitted after this many seconds of no output."
                ),
                "env" to ParameterProperty(
                    type = "object",
                    description = "Custom environment variables to set for the command. Keys are variable names, values are strings. System/path variables (PATH, HOME, LD_PRELOAD, etc.) are blocked for safety."
                ),
                "separate_stderr" to ParameterProperty(
                    type = "boolean",
                    description = "When true, capture stderr separately and append as [STDERR] section. Default: false (stderr merged with stdout)."
                ),
                "background" to ParameterProperty(
                    type = "boolean",
                    description = "When true, the command starts in the background and returns immediately with a bgId. Use background_process to monitor, read output, attach, send stdin, or kill. When false (default), the command runs synchronously."
                ),
                "on_idle" to ParameterProperty(
                    type = "string",
                    description = "Foreground-only (ignored when background=true). What to do when the process produces no output for idle_timeout seconds. 'notify' (default) emits an inline idle signal with classification and keeps waiting. 'wait' ignores idle entirely and blocks until exit or total timeout.",
                    enumValues = listOf("wait", "notify")
                )
            )

            if (singleShell) {
                // Only one shell — omit the shell param entirely
                return FunctionParameters(
                    properties = baseProps,
                    required = listOf("command", "description")
                )
            }

            // Multiple shells — build description that only names available ones
            val shellDesc = buildString {
                append("Shell to execute the command in. Use ONLY shells listed as available in your environment.")
                if ("bash" in effective) append(" bash = Unix/Git Bash syntax (ls, grep, cat, &&).")
                if ("cmd" in effective) append(" cmd = Windows cmd.exe syntax (dir, type, findstr).")
                if ("powershell" in effective) append(" powershell = PowerShell syntax (Get-ChildItem, Select-String).")
            }

            // Insert shell between command and working_dir (preserving original param order)
            val propsWithShell = linkedMapOf("command" to baseProps["command"]!!)
            propsWithShell["shell"] = ParameterProperty(
                type = "string",
                description = shellDesc,
                enumValues = effective
            )
            propsWithShell.putAll(baseProps.filterKeys { it != "command" })

            return FunctionParameters(
                properties = propsWithShell,
                required = listOf("command", "shell", "description")
            )
        }
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

            // ── Background path ────────────────
            val isBackground = params["background"]?.jsonPrimitive?.boolean ?: false
            if (isBackground) {
                return launchBackgroundAndReturn(
                    commandLine = commandLine,
                    command = command,
                    project = project,
                )
            }
            // ── Foreground path continues below ────────────────

            // 9. Spawn process
            val process = commandLine.createProcess()

            // Determine tool call ID for ProcessRegistry and streaming
            val rawId = currentToolCallId.get()
            val toolCallId = rawId ?: "run-cmd-${processIdCounter.incrementAndGet()}"
            if (rawId == null) {
                LOG.warn(
                    "run_command[$toolCallId]: ThreadLocal toolCallId not set — falling back to synthetic id. " +
                    "Streaming output will NOT appear in the terminal UI. " +
                    "Root cause: AgentLoop must call RunCommandTool.currentToolCallId.set(id) before execute()."
                )
            }

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
            if (activeStreamCallback == null) {
                LOG.warn(
                    "run_command[$toolCallId]: streamCallback is null — stdout chunks will be silently dropped. " +
                    "Root cause: AgentController.wireCallbacks() was not called or streamCallback was cleared after init."
                )
            }
            LOG.info("run_command[$toolCallId]: process started (streamCallback=${activeStreamCallback != null})")

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
                } catch (e: Exception) {
                    LOG.warn("RunCommand stdout reader for $toolCallId terminated abnormally: ${e.message}", e)
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
                    } catch (e: Exception) {
                        LOG.warn("RunCommand stderr reader for $toolCallId terminated abnormally: ${e.message}", e)
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

                // Priority 3: idle classification per on_idle policy
                val onIdle = params["on_idle"]?.jsonPrimitive?.content?.lowercase() ?: "notify"
                val lastOutput = managed.lastOutputAt.get()
                // Use lastOutputAt if there has been output, otherwise fall back to startedAt so
                // processes that produce zero output (e.g. `sleep N`) still fire idle notes.
                val idleReferenceTime = if (lastOutput > 0) lastOutput else managed.startedAt
                if (onIdle != "wait" && now - idleReferenceTime >= idleThresholdMs) {
                    // Reset timer so we only fire once per idle stretch.
                    managed.lastOutputAt.set(now)
                    val tail = managed.outputLines.toList().joinToString("")
                        .let { com.workflow.orchestrator.agent.tools.process.OutputCollector.stripAnsi(it) }
                        .lines().takeLast(40).joinToString("\n")
                    val classification = com.workflow.orchestrator.agent.tools.process
                        .PromptHeuristics.classify(tail)
                    val note = buildIdleNote(classification, idleThresholdMs / 1000)
                    activeStreamCallback?.invoke(toolCallId, note)
                    // NOTE: on_idle=notify keeps the loop going — do NOT return here.
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

    private suspend fun launchBackgroundAndReturn(
        commandLine: com.intellij.execution.configurations.GeneralCommandLine,
        command: String,
        project: com.intellij.openapi.project.Project,
    ): ToolResult {
        val sessionId = currentSessionId.get()
            ?: return ToolResult(
                "Error: background launch requires sessionId context (not set by AgentLoop).",
                "Error: missing sessionId",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )

        val process = try {
            commandLine.createProcess()
        } catch (e: Exception) {
            return ToolResult(
                "Error launching background process: ${e.message}",
                "Error: background launch failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        }

        val bgId = "bg_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        val managed = ProcessRegistry.register(bgId, process, command)

        // Reader thread — same pattern as foreground run_command
        val callback = streamCallback
        Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    var n = reader.read(buffer)
                    while (n != -1) {
                        val chunk = String(buffer, 0, n)
                        managed.outputLines.add(chunk)
                        managed.lastOutputAt.set(System.currentTimeMillis())
                        callback?.invoke(bgId, chunk)
                        n = reader.read(buffer)
                    }
                }
            } catch (e: Exception) {
                LOG.warn("RunCommand background reader for $bgId terminated abnormally: ${e.message}", e)
            } finally {
                managed.readerDone.countDown()
            }
        }.apply {
            isDaemon = true; name = "RunCommand-BgOutput-$bgId"
        }.start()

        // Register in BackgroundPool
        val handle = com.workflow.orchestrator.agent.tools.background.RunCommandBackgroundHandle(
            bgId = bgId, sessionId = sessionId, managed = managed, label = command.take(120)
        )
        try {
            com.workflow.orchestrator.agent.tools.background.BackgroundPool
                .getInstance(project).register(sessionId, handle)
        } catch (e: com.workflow.orchestrator.agent.tools.background.BackgroundPool.MaxConcurrentReached) {
            process.destroyForcibly()
            ProcessRegistry.unregister(bgId)
            return ToolResult(
                "Error: ${e.message}",
                "Error: MAX_CONCURRENT_REACHED",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        }

        // 500ms initial-output grace period.
        delay(500)
        val initial = managed.outputLines.joinToString("").lines().takeLast(20).joinToString("\n")
        val preview = if (initial.isBlank()) "(no output yet)" else initial

        val content = buildString {
            appendLine("Started in background: $bgId (state: RUNNING)")
            appendLine("Command: $command")
            appendLine("Initial output (first 500ms):")
            preview.lines().forEach { appendLine("  $it") }
            appendLine()
            appendLine("Use background_process to check status, read output, attach, send stdin, or kill.")
            appendLine("On completion you will automatically receive a system message.")
        }
        return ToolResult(
            content = content,
            summary = "Background launched: $bgId",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = false,
        )
    }

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
        val processed = OutputCollector.processOutputTailBiased(
            rawOutput = rawOutput,
            maxResultChars = outputConfig.maxChars,
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
            val stderrProcessed = OutputCollector.processOutputTailBiased(
                rawOutput = rawStderr,
                maxResultChars = outputConfig.maxChars / 2,
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
        val processed = OutputCollector.processOutputTailBiased(
            rawOutput = rawOutput,
            maxResultChars = outputConfig.maxChars,
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

    private fun buildIdleNote(
        classification: com.workflow.orchestrator.agent.tools.process.IdleClassification,
        idleSeconds: Long,
    ): String {
        val label = when (classification) {
            is com.workflow.orchestrator.agent.tools.process.IdleClassification.LikelyPasswordPrompt ->
                "LIKELY_PASSWORD_PROMPT: \"${classification.promptText}\" (use ask_user_input)"
            is com.workflow.orchestrator.agent.tools.process.IdleClassification.LikelyStdinPrompt ->
                "LIKELY_STDIN_PROMPT: \"${classification.promptText}\""
            com.workflow.orchestrator.agent.tools.process.IdleClassification.GenericIdle ->
                "GENERIC_IDLE (cause unknown — may be waiting for stdin, slow, or stuck)"
        }
        return "⏳ Process idle for ${idleSeconds}s — $label (source: regex). Still waiting.\n"
    }
}
