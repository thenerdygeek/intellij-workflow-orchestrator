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
import com.workflow.orchestrator.agent.tools.docs.AuditKind
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.agent.tools.process.OutputCollector
import com.workflow.orchestrator.agent.tools.process.ShellResolver
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

// Max stdin writes per process is read from AgentSettings.maxStdinPerProcess at execution time.
// Default is 10 (set in AgentSettings.State).
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

    override fun documentation(): ToolDocumentation = toolDoc("send_stdin") {
        summary {
            technical(
                "Writes text to a running process's stdin via ProcessRegistry (the synchronous-then-idle " +
                    "run_command path, non-bg_ ids); guards against password prompts via " +
                    "ShellResolver.isLikelyPasswordPrompt and enforces a per-process write cap from " +
                    "AgentSettings.maxStdinPerProcess (default 10); then enters a 60-second monitor loop " +
                    "polling for process exit or output-idle, returning the new output or an [IDLE] signal."
            )
            plain(
                "Like typing into a terminal that already has a program running — the agent sends a line " +
                    "of text (e.g., 'y' to confirm an 'Are you sure?' prompt), then waits up to a minute " +
                    "to see what the program does next. Has a built-in safety check that refuses to send " +
                    "input if the process looks like it's asking for a password."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.PROCESS_SPAWN)
        counterfactual(
            "Without send_stdin, the LLM falls back to background_process(action=send_stdin) — which " +
                "covers processes in BackgroundPool (bg_ ids) but not the ProcessRegistry idle path, " +
                "lacks password-prompt detection, and lacks the per-process rate limit. " +
                "Alternatively, the LLM might try shell tricks such as `echo y | cmd` (requires " +
                "re-launching the process) or writing to /proc/<pid>/fd/0 (Linux-only, root-gated, " +
                "fragile). Neither alternative is safe or cross-platform."
        )
        llmMistake(
            "Uses this tool with a bgId (bg_... prefix) from run_command(background=true) — those " +
                "ids live in BackgroundPool, not ProcessRegistry. This tool only handles non-bg_ ids " +
                "from the synchronous-then-idle path. Use background_process(action=send_stdin) for " +
                "bg_ ids."
        )
        llmMistake(
            "Sends a password, token, or secret — the description explicitly forbids this. The " +
                "password-prompt guard fires on common prompt patterns (sudo, passphrase, password:) " +
                "but cannot detect all forms; the LLM must also apply judgment and use ask_user_input " +
                "instead."
        )
        llmMistake(
            "Forgets the trailing newline — sends 'y' instead of 'y\\n'. Many interactive prompts " +
                "require Enter to confirm; without \\n the process keeps waiting and the monitor loop " +
                "times out without progress."
        )
        llmMistake(
            "Retries send_stdin after hitting the rate limit (maxStdinPerProcess, default 10) instead " +
                "of killing and rerunning the process with non-interactive flags (e.g., -y, --yes, " +
                "--non-interactive). The error message says exactly this, but the LLM sometimes loops."
        )
        params {
            required("process_id", "string") {
                llmSeesIt("The process ID from the [IDLE] message.")
                humanReadable(
                    "The ID that appeared in the [IDLE] banner when run_command returned with " +
                        "'process is waiting for input'. It is NOT a bg_ prefixed id — those belong " +
                        "to background_process."
                )
                whenPresent("The process is looked up in ProcessRegistry by this id.")
                constraint("must be a non-bg_ id from the run_command synchronous-then-idle path")
                constraint("process must exist and be alive — exits return an error before any write")
                example("proc_a1b2c3d4")
            }
            required("input", "string") {
                llmSeesIt("Text to send to stdin. Include \\n for Enter key.")
                humanReadable(
                    "Exact text to write — escape sequences like \\n are interpreted as real newlines " +
                        "by the JSON layer. Include \\n to press Enter; omitting it leaves the process " +
                        "waiting for line termination."
                )
                whenPresent("Those bytes are written to the process's stdin pipe via ProcessRegistry.writeStdin.")
                constraint("must not be a password, token, or secret — use ask_user_input for credentials")
                constraint("counted against maxStdinPerProcess (default 10) — error returned if limit exceeded")
                example("y\\n")
                example("1\\n")
                example("no\\n")
            }
        }
        verdict {
            keep(
                "Provides password-prompt detection and a per-process write cap that background_process " +
                    "(action=send_stdin) does not have. These guards make it meaningfully safer than " +
                    "its alternative for interactive scripting on the synchronous-then-idle path.",
                VerdictSeverity.NORMAL
            )
            drop(
                "Active MERGE_CANDIDATE: the Batch 10 audit flagged that having two send_stdin surfaces " +
                    "(this standalone tool for ProcessRegistry + background_process action for BackgroundPool) " +
                    "is a persistent source of LLM confusion over id namespaces. The right long-term fix is " +
                    "to migrate the password-prompt guard and rate-limit into BackgroundPool and drop this tool.",
                VerdictSeverity.NORMAL
            )
        }
        mergeOpportunity(
            "STRONG MERGE_OPPORTUNITY with background_process(action=send_stdin). Both tools write text " +
                "to a process's stdin; they differ only in id namespace (ProcessRegistry vs BackgroundPool) " +
                "and guard set (this tool has password-prompt detection + rate limit; the action does not). " +
                "Migration path: absorb the synchronous-then-idle ProcessRegistry path into BackgroundPool, " +
                "migrate ShellResolver.isLikelyPasswordPrompt and maxStdinPerProcess guards into the action, " +
                "then drop this standalone tool. Flagged in Batch 10 audit findings. Until that migration " +
                "lands, keep both but surface the distinction prominently in the description."
        )
        related(
            "background_process",
            Relationship.ALTERNATIVE,
            "Use background_process(action=send_stdin) instead when the process id starts with bg_ — " +
                "those ids belong to BackgroundPool (run_command background=true path). This tool is for " +
                "the non-bg_ synchronous-then-idle path only. Note: background_process send_stdin lacks " +
                "password-prompt detection and rate limiting."
        )
        related(
            "run_command",
            Relationship.COMPOSE_WITH,
            "send_stdin is only meaningful after run_command returned [IDLE] with a process_id — " +
                "that is the only way a non-bg_ id enters ProcessRegistry."
        )
        related(
            "runtime_exec",
            Relationship.SEE_ALSO,
            "For IDE-managed run configurations, runtime_exec(action=run_config) is the canonical launch " +
                "path; those processes live in ExecutionManagerImpl, not ProcessRegistry, and do not " +
                "accept send_stdin."
        )
        downside(
            "The 60-second monitor loop ties up the agent loop iteration for up to a minute if the " +
                "process produces no output after the write. The LLM cannot do other work during this wait."
        )
        downside(
            "Rate limit (maxStdinPerProcess, default 10) may surprise workflows that need many interactive " +
                "exchanges. The only recovery once the limit is hit is to kill and rerun with non-interactive flags."
        )
        downside(
            "Password-prompt detection via ShellResolver.isLikelyPasswordPrompt uses heuristic pattern " +
                "matching and has false positives — any prompt containing words like 'password', 'passphrase', " +
                "or 'secret' will block even if the input is not a credential. The LLM must then use " +
                "ask_user_input, even when that is unnecessary."
        )
        downside(
            "Only covers ProcessRegistry (non-bg_ ids from the synchronous-then-idle path). Does not " +
                "handle processes in BackgroundPool — an easy mistake given both id types are short alphanumeric strings."
        )
        downside(
            "The idle monitor exits after 60s (MAX_WAIT_AFTER_STDIN_MS) or 10s of output silence " +
                "(IDLE_AFTER_STDIN_MS), whichever comes first. Slow processes that take longer than 10s to " +
                "react to input will produce an [IDLE] result that looks like a new prompt even when the " +
                "process is still computing."
        )
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
        val maxStdinPerProcess = com.workflow.orchestrator.agent.settings.AgentSettings
            .getInstance(project).state.maxStdinPerProcess
        if (managed.stdinCount.get() >= maxStdinPerProcess) {
            return ToolResult(
                "Error: Stdin limit ($maxStdinPerProcess) exceeded for process '$processId'. " +
                    "Kill the process with background_process(action=kill) and rerun using a non-interactive command instead.",
                "Error: stdin limit exceeded",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Step 5: Password detection — check last output
        val lastOutput = managed.outputLines.toList().joinToString("")
        if (ShellResolver.isLikelyPasswordPrompt(lastOutput)) {
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
            delay(MONITOR_POLL_MS)
            val now = System.currentTimeMillis()

            // Priority 1: process exited
            if (!managed.process.isAlive) {
                val newOutput = ProcessToolHelpers.collectNewOutput(managed, outputSizeBeforeStdin)
                val stripped = OutputCollector.stripAnsi(newOutput)
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
                val stripped = OutputCollector.stripAnsi(newOutput)
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
                val stripped = OutputCollector.stripAnsi(newOutput)
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
