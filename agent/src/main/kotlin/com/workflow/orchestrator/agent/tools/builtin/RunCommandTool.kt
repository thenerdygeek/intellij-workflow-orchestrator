package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.context.ToolOutputStore
import com.workflow.orchestrator.agent.runtime.ProcessRegistry
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.security.CommandRisk
import com.workflow.orchestrator.agent.security.CommandSafetyAnalyzer
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RunCommandTool : AgentTool {
    override val name = "run_command"
    override val description = "Execute a shell command in the project directory. Has a 120-second default timeout (max 600s) and 30000-character output limit. Dangerous commands are blocked."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "command" to ParameterProperty(type = "string", description = "The shell command to execute. Examples: 'ls -la src/', 'grep -r TODO .', 'mvn test -pl core'"),
            "working_dir" to ParameterProperty(type = "string", description = "Working directory (absolute or relative to project root). Optional, defaults to project root. Example: 'src/main/kotlin'"),
            "description" to ParameterProperty(type = "string", description = "Brief description of what this command does (5-10 words, for logging/UI)"),
            "timeout" to ParameterProperty(type = "integer", description = "Timeout in seconds. Default: 120, max: 600.")
        ),
        required = listOf("command", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    companion object {
        private val LOG = Logger.getInstance(RunCommandTool::class.java)
        private const val DEFAULT_TIMEOUT_SECONDS = 120L
        private const val MAX_TIMEOUT_SECONDS = 600L
        private const val MAX_OUTPUT_CHARS = 30_000
        private val processIdCounter = AtomicLong(0)

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

        /** Commands that are always safe to run (read-only or build tools). */
        private val ALLOWED_PREFIXES = listOf(
            // Build tools
            "mvn", "gradle", "./gradlew", "gradlew", "npm", "yarn", "pnpm",
            // Version control (read-only)
            "git status", "git log", "git diff", "git branch", "git show", "git blame",
            // File inspection
            "ls", "find", "cat", "head", "tail", "wc", "file", "stat",
            "grep", "rg", "ag",
            // Java/Kotlin
            "java", "javac", "kotlin", "kotlinc", "jar",
            // Docker (read-only)
            "docker ps", "docker images", "docker logs", "docker inspect",
            // System info
            "uname", "whoami", "hostname", "pwd", "env", "echo", "date", "which",
            // Testing
            "pytest", "jest", "cargo test", "go test",
        )

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
                return "Error: 'git $subCommand' is blocked for safety. Allowed read-only git commands: ${SAFE_GIT_SUBCOMMANDS.joinToString(", ")}. For write operations, use the appropriate IDE tools."
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
                    return "Error: Flag '$flag' is blocked for safety in git commands."
                }
            }

            return null // Safe to execute
        }

        /**
         * Check if a command is on the allowlist (safe to run without approval).
         */
        fun isAllowed(command: String): Boolean {
            val trimmed = command.trim()
            return ALLOWED_PREFIXES.any { prefix ->
                trimmed.startsWith(prefix) || trimmed.startsWith("./$prefix")
            }
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

        // Non-allowlisted commands still execute (ApprovalGate handles HIGH-risk approval dialog)
        // but we note it for observability
        if (!isAllowed(command)) {
            // Command will proceed — the existing ApprovalGate in SingleAgentSession
            // already shows a confirmation dialog for run_command (classified as HIGH risk)
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
            val processBuilder = if (isWindows) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("sh", "-c", command)
            }

            val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.int?.toLong() ?: DEFAULT_TIMEOUT_SECONDS)
                .coerceIn(1, MAX_TIMEOUT_SECONDS)

            processBuilder.directory(workDir)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()

            // Determine tool call ID for ProcessRegistry and streaming
            val toolCallId = currentToolCallId.get()
                ?: "run-cmd-${processIdCounter.incrementAndGet()}"

            // Register in ProcessRegistry for kill/killAll support
            ProcessRegistry.register(toolCallId, process, command)

            // Stream output line-by-line in a daemon thread
            val outputBuilder = StringBuilder()
            val activeStreamCallback = streamCallback
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            outputBuilder.appendLine(line)
                            activeStreamCallback?.invoke(toolCallId, line + "\n")
                            line = reader.readLine()
                        }
                    }
                } catch (_: Exception) {
                    // Process killed or stream closed — expected during timeout/cancel
                }
            }.apply {
                isDaemon = true
                name = "RunCommand-Output-$toolCallId"
                start()
            }

            // Wait for process completion
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                ProcessRegistry.kill(toolCallId)
                readerThread.join(1000)
                val rawOutput = outputBuilder.toString()
                val truncatedOutput = if (rawOutput.length > MAX_OUTPUT_CHARS) {
                    ToolOutputStore.middleTruncate(rawOutput, MAX_OUTPUT_CHARS) +
                        "\n\n[Total output: ${rawOutput.length} chars. Use a more targeted command to see specific sections.]"
                } else {
                    rawOutput
                }
                return ToolResult(
                    "Error: Command timed out after ${timeoutSeconds}s.\nPartial output:\n$truncatedOutput",
                    "Error: command timed out",
                    TokenEstimator.estimate(truncatedOutput),
                    isError = true
                )
            }

            // Wait for reader thread to drain remaining output
            readerThread.join(2000)
            ProcessRegistry.unregister(toolCallId)

            val rawOutput = outputBuilder.toString()
            val truncatedOutput = if (rawOutput.length > MAX_OUTPUT_CHARS) {
                ToolOutputStore.middleTruncate(rawOutput, MAX_OUTPUT_CHARS) +
                    "\n\n[Total output: ${rawOutput.length} chars. Use a more targeted command to see specific sections.]"
            } else {
                rawOutput
            }

            val exitCode = process.exitValue()
            val description = params["description"]?.jsonPrimitive?.content
            val summary = if (description != null) {
                "$description — exit code $exitCode"
            } else {
                "Command exited with code $exitCode: ${command.take(80)}"
            }
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
