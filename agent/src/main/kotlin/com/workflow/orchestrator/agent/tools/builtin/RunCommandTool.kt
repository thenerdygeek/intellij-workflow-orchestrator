package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.truncateOutput
import com.workflow.orchestrator.agent.tools.process.ManagedProcess
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.security.CommandRisk
import com.workflow.orchestrator.agent.security.CommandSafetyAnalyzer
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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
            "idle_timeout" to ParameterProperty(type = "integer", description = "Idle detection threshold in seconds. Default: 15 (60 for build commands). Process returns [IDLE] if no output for this many seconds.")
        ),
        required = listOf("command", "shell", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

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

        private val BUILD_COMMAND_PREFIXES = listOf(
            "gradle", "./gradlew", "gradlew", "mvn", "./mvnw", "mvnw",
            "npm", "yarn", "pnpm", "docker build", "cargo build", "go build",
            "dotnet build", "make", "cmake"
        )

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

        /** Commands that are ALWAYS blocked (destructive, no approval possible). */
        private val HARD_BLOCKED = listOf(
            Regex("""rm\s+-rf\s+/"""),
            Regex("""rm\s+-rf\s+~"""),
            Regex("""^\s*sudo\s"""),
            Regex(""":\(\)\s*\{"""),     // fork bomb
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
        )

        /** Safe read-only git sub-commands allowed via run_command. */
        private val SAFE_GIT_SUBCOMMANDS = setOf(
            "log", "diff", "show", "status", "blame", "shortlog",
            "rev-parse", "config", "branch", "tag", "stash list",
            "ls-files", "cat-file", "rev-list", "merge-base",
            "name-rev", "describe", "reflog", "for-each-ref",
            "check-ignore", "ls-tree", "worktree list", "version"
        )

        /** Dangerous flags blocked in ANY git command. */
        private val DANGEROUS_GIT_FLAGS = listOf(
            "--force", "-f", "--hard", "--no-verify",
            "--delete", "-D", "--set-upstream"
        )

        /**
         * Check if a command is likely a build command that should use a longer idle threshold.
         */
        fun isLikelyBuildCommand(command: String): Boolean {
            val trimmed = command.trim()
            return BUILD_COMMAND_PREFIXES.any { trimmed.startsWith(it) }
        }

        /**
         * Find Git Bash on Windows. Checks standard installation paths.
         * Returns the absolute path to bash.exe, or null if not found.
         */
        fun findGitBash(): String? {
            val candidates = listOf(
                System.getenv("PROGRAMFILES")?.let { "$it\\Git\\bin\\bash.exe" },
                System.getenv("PROGRAMFILES(X86)")?.let { "$it\\Git\\bin\\bash.exe" },
                System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\Git\\bin\\bash.exe" },
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe"
            )
            return candidates.filterNotNull().firstOrNull { File(it).exists() }
        }

        /**
         * Find PowerShell on Windows. Prefers pwsh (PowerShell 7+) over powershell.exe (5.1).
         * Returns the executable path, or null if not found.
         */
        fun findPowerShell(): String? {
            val candidates = listOf(
                // PowerShell 7+ (cross-platform, preferred)
                System.getenv("PROGRAMFILES")?.let { "$it\\PowerShell\\7\\pwsh.exe" },
                "C:\\Program Files\\PowerShell\\7\\pwsh.exe",
                // Windows PowerShell 5.1 (built-in on Windows 10+)
                System.getenv("SYSTEMROOT")?.let { "$it\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" },
                "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
            )
            return candidates.filterNotNull().firstOrNull { File(it).exists() }
        }

        /**
         * Detect available shells on the current platform.
         * Returns a list of shell names (bash, cmd, powershell) that can be used.
         * Respects the powershellEnabled setting — when disabled, powershell is excluded.
         */
        fun detectAvailableShells(project: Project? = null): List<String> {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            if (!isWindows) return listOf("bash")
            val shells = mutableListOf<String>()
            if (findGitBash() != null) shells.add("bash")
            shells.add("cmd") // always available on Windows
            val powershellAllowed = project?.let {
                try { com.workflow.orchestrator.agent.settings.AgentSettings.getInstance(it).state.powershellEnabled } catch (_: Exception) { true }
            } ?: true
            if (powershellAllowed && findPowerShell() != null) shells.add("powershell")
            return shells
        }

        private val ANSI_REGEX = Regex("\u001B\\[[;\\d]*[A-Za-z]")

        /**
         * Strip ANSI escape codes from text.
         */
        fun stripAnsi(text: String): String = text.replace(ANSI_REGEX, "")

        private val PASSWORD_PATTERNS = listOf(
            Regex("""(?i)password\s*:"""),
            Regex("""(?i)passphrase\s*:"""),
            Regex("""(?i)enter\s+.*token"""),
            Regex("""(?i)secret\s*:"""),
            Regex("""(?i)credentials?\s*:"""),
            Regex("""(?i)api.?key\s*:"""),
        )

        /**
         * Environment overrides that prevent interactive programs from blocking the process.
         * Adopted from Cline's StandaloneTerminalProcess pattern.
         */
        private val ANTI_INTERACTIVE_ENV = mapOf(
            "PAGER" to "cat",
            "GIT_PAGER" to "cat",
            "MANPAGER" to "cat",
            "SYSTEMD_PAGER" to "",
            "EDITOR" to "cat",
            "VISUAL" to "cat",
            "GIT_EDITOR" to "cat",
            "LESS" to "-FRX",   // quit-if-one-screen, raw-control-chars, no-init
        )

        /** Environment variables stripped before spawning processes to prevent credential leaks. */
        private val SENSITIVE_ENV_VARS = listOf(
            "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "SOURCEGRAPH_TOKEN",
            "GITHUB_TOKEN", "GH_TOKEN", "GITLAB_TOKEN",
            "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "AZURE_CLIENT_SECRET", "GOOGLE_APPLICATION_CREDENTIALS",
            "NPM_TOKEN", "NUGET_API_KEY", "DOCKER_PASSWORD",
            "SONAR_TOKEN", "JIRA_TOKEN", "BAMBOO_TOKEN", "BITBUCKET_TOKEN",
        )

        /**
         * Check if the last output looks like a password/credential prompt.
         */
        fun isLikelyPasswordPrompt(lastOutput: String): Boolean =
            PASSWORD_PATTERNS.any { it.containsMatchIn(lastOutput.takeLast(300)) }

        /**
         * Check if a git command is safe to execute.
         * Returns null if safe, or an error message if blocked.
         */
        fun checkGitCommand(command: String): String? {
            val trimmed = command.trim()
            if (!trimmed.startsWith("git ") && !trimmed.startsWith("git\t")) return null // Not a git command

            // Extract the git sub-command (e.g., "log" from "git log --oneline")
            val parts = trimmed.removePrefix("git").trim().split("\\s+".toRegex(), limit = 2)
            val subCommand = parts.firstOrNull() ?: return "Error: empty git command"

            // Check if sub-command is in the safe list
            val isSafe = SAFE_GIT_SUBCOMMANDS.any { safe ->
                subCommand == safe || (safe.contains(" ") && trimmed.removePrefix("git").trim().startsWith(safe))
            }

            if (!isSafe) {
                return "Error: 'git $subCommand' is blocked for safety. To revert file changes, use the revert_file tool instead. Allowed read-only git commands: ${SAFE_GIT_SUBCOMMANDS.joinToString(", ")}."
            }

            // Block remote refs only in write git commands; allow in read-only commands
            val readOnlyWithRemoteRefs = setOf("log", "diff", "show", "rev-list", "merge-base", "rev-parse")
            if (trimmed.contains("origin/") || trimmed.contains("upstream/")) {
                val allowsRemoteRefs = readOnlyWithRemoteRefs.any { cmd ->
                    subCommand == cmd || (cmd.contains(" ") && trimmed.removePrefix("git").trim().startsWith(cmd))
                }
                if (!allowsRemoteRefs) {
                    return "Error: Remote refs (origin/, upstream/) are only allowed in read-only git commands (${readOnlyWithRemoteRefs.joinToString(", ")}). Use the appropriate IDE tools for write operations."
                }
            }

            // Check for dangerous flags even in safe sub-commands
            for (flag in DANGEROUS_GIT_FLAGS) {
                if (trimmed.contains(" $flag") || trimmed.contains("=$flag")) {
                    return "Error: Flag '$flag' is blocked for safety in git commands. To revert changes, use the revert_file tool instead."
                }
            }

            return null // Safe to execute
        }

        /**
         * Check if a command is hard-blocked (never run, even with approval).
         */
        fun isHardBlocked(command: String): Boolean {
            return HARD_BLOCKED.any { it.containsMatchIn(command) }
        }

        /**
         * Legacy compatibility — returns true if the command is hard-blocked.
         */
        fun isBlocked(command: String): Boolean = isHardBlocked(command)
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val command = params["command"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'command' parameter required", "Error: missing command", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val shell = params["shell"]?.jsonPrimitive?.content?.lowercase() ?: "bash"
        if (shell !in listOf("bash", "cmd", "powershell")) {
            return ToolResult(
                "Error: Invalid shell '$shell'. Must be one of: bash, cmd, powershell.",
                "Error: invalid shell",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // 5B: CommandSafetyAnalyzer — block DANGEROUS commands before any other processing
        val risk = CommandSafetyAnalyzer.classify(command)
        if (risk == CommandRisk.DANGEROUS) {
            LOG.warn("[Agent:RunCommand] BLOCKED dangerous command: ${command.take(100)}")
            return ToolResult(
                content = "Command blocked by safety analyzer: classified as DANGEROUS. This command could cause data loss or system damage.",
                summary = "Error: dangerous command blocked",
                tokenEstimate = 30,
                isError = true
            )
        }
        // Log the execution for audit
        LOG.info("[Agent:RunCommand] risk=$risk command=${command.take(80)}")

        // Smart git command filter — allowlist approach
        val gitBlockReason = checkGitCommand(command)
        if (gitBlockReason != null) {
            return ToolResult(
                gitBlockReason,
                "Error: git command blocked",
                5,
                isError = true
            )
        }

        // Hard-blocked commands are never allowed — destructive with no recovery
        if (isHardBlocked(command)) {
            return ToolResult(
                "Error: Command blocked for safety: $command. This command is destructive and cannot be executed.",
                "Error: blocked command",
                5,
                isError = true
            )
        }

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

            // Resolve the shell executable based on the LLM's requested shell type
            val commandLine = when (shell) {
                "bash" -> {
                    if (isWindows) {
                        val gitBash = findGitBash()
                        if (gitBash != null) {
                            GeneralCommandLine(gitBash, "-c", command)
                        } else {
                            return ToolResult(
                                "Error: shell='bash' requested but Git Bash is not installed. Available shells on this system: cmd, powershell. Use one of those instead.",
                                "Error: bash not available",
                                ToolResult.ERROR_TOKEN_ESTIMATE,
                                isError = true
                            )
                        }
                    } else {
                        GeneralCommandLine("sh", "-c", command)
                    }
                }
                "cmd" -> {
                    if (isWindows) {
                        GeneralCommandLine("cmd.exe", "/c", command)
                    } else {
                        return ToolResult(
                            "Error: shell='cmd' is only available on Windows. Use shell='bash' instead.",
                            "Error: cmd not available",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )
                    }
                }
                "powershell" -> {
                    // Check if PowerShell is enabled in settings
                    val powershellAllowed = try {
                        com.workflow.orchestrator.agent.settings.AgentSettings.getInstance(project).state.powershellEnabled
                    } catch (_: Exception) { true }
                    if (!powershellAllowed) {
                        return ToolResult(
                            "Error: PowerShell is disabled in agent settings. Use shell='cmd' or shell='bash' instead.",
                            "Error: powershell disabled",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )
                    }
                    if (isWindows) {
                        // Try pwsh (PowerShell 7+) first, then fall back to powershell.exe (Windows PowerShell 5.1)
                        val pwsh = findPowerShell()
                        if (pwsh != null) {
                            GeneralCommandLine(pwsh, "-NoProfile", "-NonInteractive", "-Command", command)
                        } else {
                            return ToolResult(
                                "Error: shell='powershell' requested but PowerShell is not found. Available shells: cmd${if (findGitBash() != null) ", bash" else ""}.",
                                "Error: powershell not available",
                                ToolResult.ERROR_TOKEN_ESTIMATE,
                                isError = true
                            )
                        }
                    } else {
                        return ToolResult(
                            "Error: shell='powershell' is only available on Windows. Use shell='bash' instead.",
                            "Error: powershell not available",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )
                    }
                }
                else -> error("unreachable: shell validated above")
            }

            val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.int?.toLong() ?: DEFAULT_TIMEOUT_SECONDS)
                .coerceIn(1, MAX_TIMEOUT_SECONDS)
            val timeoutMs = timeoutSeconds * 1000

            commandLine.workDirectory = workDir
            commandLine.withRedirectErrorStream(true)

            // Sanitize environment: strip sensitive vars that could leak credentials
            val env = commandLine.environment
            SENSITIVE_ENV_VARS.forEach { env.remove(it) }

            // Prevent interactive pagers/editors from hanging the process.
            // Without this, commands like `git log`, `git diff`, `man`, or
            // `git commit` (without -m) open interactive programs that block forever.
            ANTI_INTERACTIVE_ENV.forEach { (k, v) -> env[k] = v }

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
                ?: if (isLikelyBuildCommand(command))
                    agentSettings.buildCommandIdleThresholdSeconds * 1000L
                else
                    agentSettings.commandIdleThresholdSeconds * 1000L

            // Buffer-based reader thread (handles binary output)
            val activeStreamCallback: ((String, String) -> Unit)? = streamCallback
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

            // Event-driven coroutine monitor loop
            while (true) {
                // Coroutine cancellation check — allows instant exit when user presses Stop.
                // delay() is also cancellable, but ensureActive() gives an immediate check
                // before waiting 500ms.
                coroutineContext.ensureActive()
                delay(500)
                val now = System.currentTimeMillis()

                // Priority 1: process exited — always check first (race condition fix)
                if (!process.isAlive) {
                    readerThread.join(IO_DRAIN_TIMEOUT_MS) // drain remaining output
                    ProcessRegistry.unregister(toolCallId)
                    return buildExitResult(managed, command, params)
                }

                // Priority 2: total timeout
                if (now - managed.startedAt > timeoutMs) {
                    ProcessRegistry.kill(toolCallId)
                    readerThread.join(IO_DRAIN_TIMEOUT_MS) // drain remaining output after kill
                    return buildTimeoutResult(managed, timeoutSeconds)
                }

                // Priority 3: idle detection (only after first output — grace period)
                val lastOutput = managed.lastOutputAt.get()
                if (lastOutput > 0 && now - lastOutput >= idleThresholdMs) {
                    managed.idleSignaledAt.set(now)
                    return buildIdleResult(managed, idleThresholdMs / 1000)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable: while(true) always returns")
        } catch (e: CancellationException) {
            // Coroutine cancelled (user pressed Stop) — ProcessRegistry.killAll() handles cleanup
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

    private fun collectOutput(managed: ManagedProcess): String {
        return managed.outputLines.joinToString("")
    }

    private fun lastOutputLines(managed: ManagedProcess, lineCount: Int = 10): String {
        val allOutput = collectOutput(managed)
        val lines = allOutput.lines()
        return lines.takeLast(lineCount).joinToString("\n")
    }

    private fun buildExitResult(managed: ManagedProcess, command: String, params: JsonObject): ToolResult {
        val rawOutput = collectOutput(managed)
        // Strip ANSI for LLM context (saves tokens, LLM doesn't need color codes).
        // The UI already received raw output with ANSI via streamCallback.
        val cleanOutput = stripAnsi(rawOutput)
        val truncatedOutput = if (cleanOutput.length > MAX_OUTPUT_CHARS) {
            truncateOutput(cleanOutput, MAX_OUTPUT_CHARS) +
                "\n\n[Total output: ${cleanOutput.length} chars. Use a more targeted command to see specific sections.]"
        } else {
            cleanOutput
        }

        val exitCode = managed.process.exitValue()
        val description = params["description"]?.jsonPrimitive?.content
        val summary = if (description != null) {
            "$description — exit code $exitCode"
        } else {
            "Command exited with code $exitCode: ${command.take(80)}"
        }
        return ToolResult(
            content = "Exit code: $exitCode\n$truncatedOutput",
            summary = summary,
            tokenEstimate = TokenEstimator.estimate(truncatedOutput),
            isError = exitCode != 0
        )
    }

    private fun buildTimeoutResult(managed: ManagedProcess, timeoutSeconds: Long): ToolResult {
        val rawOutput = collectOutput(managed)
        val cleanOutput = stripAnsi(rawOutput)
        val truncatedOutput = if (cleanOutput.length > MAX_OUTPUT_CHARS) {
            truncateOutput(cleanOutput, MAX_OUTPUT_CHARS) +
                "\n\n[Total output: ${cleanOutput.length} chars. Use a more targeted command to see specific sections.]"
        } else {
            cleanOutput
        }
        return ToolResult(
            content = "[TIMEOUT] Command timed out after ${timeoutSeconds}s.\nPartial output:\n$truncatedOutput",
            summary = "Error: command timed out",
            tokenEstimate = TokenEstimator.estimate(truncatedOutput),
            isError = true
        )
    }

    private fun buildIdleResult(managed: ManagedProcess, idleSeconds: Long): ToolResult {
        val processId = managed.toolCallId
        val command = managed.command
        val lastLines = stripAnsi(lastOutputLines(managed, 10))
        val indentedLines = lastLines.lines().joinToString("\n") { "  $it" }

        val passwordWarning = if (isLikelyPasswordPrompt(lastLines)) {
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
