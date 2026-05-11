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
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.agent.tools.process.OutputCollector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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

    override fun documentation(): ToolDocumentation = toolDoc("ask_user_input") {
        summary {
            technical(
                "Process-interactive input primitive: suspends the agent loop, shows a chat-UI prompt, " +
                    "collects sensitive user input (password, token, secret) via a CompletableDeferred, writes " +
                    "it to a live process's stdin via ProcessRegistry.writeStdin(), then enters the same " +
                    "MONITOR_POLL_MS / IDLE_AFTER_INPUT_MS / MAX_WAIT_AFTER_INPUT_MS loop as SendStdinTool " +
                    "to collect subsequent process output. Timeout is configurable per AgentSettings." +
                    "askUserInputTimeoutMinutes (default 5 min); on timeout the process is killed."
            )
            plain(
                "Like a terminal password prompt that pauses your script until you type a secret — the agent " +
                    "stops what it is doing, shows a text box in the chat asking for your password or token, " +
                    "waits for you to type it, sends it silently into the running program, then watches the " +
                    "program for new output before reporting back. If you take longer than the configured " +
                    "timeout the program is killed to prevent an indefinite hang."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without ask_user_input, the LLM has no way to interactively satisfy a process that is waiting " +
                "for a password, PAT, or 2-FA token on stdin. The process stalls in the IDLE state " +
                "indefinitely (or until its own timeout kills it). The only alternative is to pre-bake the " +
                "secret into the command string — which exposes it in plain text in the conversation history " +
                "and the agent log, defeating the entire purpose of interactive prompting. Without this tool " +
                "the agent cannot run password-protected CLI tools, git credential helpers, SSH key passphrases, " +
                "sudo calls, or any interactive setup wizard that reads from stdin."
        )
        llmMistake(
            "Calls ask_user_input when the process is not actually waiting for stdin — e.g. it just " +
                "printed a status message and is continuing on its own. Check the [IDLE] message from " +
                "background_process or send_stdin first; only use this tool when the process output " +
                "contains an actual prompt (e.g. 'Password:', 'Enter token:')."
        )
        llmMistake(
            "Passes an incorrect or stale process_id. The ID must come verbatim from the [IDLE] message " +
                "emitted by run_command (background mode), background_process, or send_stdin. A wrong ID " +
                "returns an error immediately; a stale ID (process already exited) also returns an error."
        )
        llmMistake(
            "Uses ask_user_input for a non-sensitive clarification question — e.g. 'Which branch should " +
                "I use?' The tool shows a plain text-input field with no options; for general questions with " +
                "enumerable answers, ask_followup_question's simple mode + options chips is the right choice."
        )
        llmMistake(
            "Omits the 'description' parameter or writes a vague one ('input needed'). The description is " +
                "the only user-facing text explaining why the input is needed and what they should type. A " +
                "missing or unclear description causes the user to guess, increasing the risk of a wrong " +
                "entry that crashes the process."
        )
        llmMistake(
            "Forgets to set 'prompt' to the actual prompt line visible in the process output (e.g. " +
                "'[sudo] password for ubuntu:'). The prompt param is optional but showing the literal process " +
                "prompt in the chat UI removes ambiguity; skipping it makes the input box feel disconnected " +
                "from what the process is asking."
        )
        params {
            required("process_id", "string") {
                llmSeesIt("The process ID from the [IDLE] message.")
                humanReadable(
                    "The opaque handle identifying the running process — visible in the [IDLE] block " +
                        "that background_process, run_command (background mode), or send_stdin returns " +
                        "when a process is waiting for stdin."
                )
                whenPresent(
                    "Looked up in ProcessRegistry. If not found, returns an error. If found but the " +
                        "process has already exited, returns an error with the exit code."
                )
                constraint("Must match a live entry in ProcessRegistry; case-sensitive.")
                example("bg-1234567890")
            }
            required("description", "string") {
                llmSeesIt("Human-readable description of what input is needed and why.")
                humanReadable(
                    "The explanation shown to the user above the input field — one to two sentences " +
                        "describing what they are being asked to provide and why the process needs it."
                )
                whenPresent(
                    "Passed to showInputCallback and rendered in the chat UI as the label above the " +
                        "text-input field. The user reads this to understand what to type."
                )
                constraint("Non-empty; purely human-facing — not sent to the process.")
                example("The SSH client is asking for the passphrase that protects your private key (~/.ssh/id_ed25519).")
                example("gradle publish requires your Nexus username and password to upload the artifact.")
            }
            optional("prompt", "string") {
                llmSeesIt("Optional prompt text shown to the user (e.g., the actual prompt line from the process output).")
                humanReadable(
                    "The verbatim prompt line that the process printed to stdout (e.g., 'Password:' or " +
                        "'Enter GitHub PAT:'). Shown in the input dialog so the user can see exactly what " +
                        "the process is asking."
                )
                whenPresent(
                    "Passed alongside description to showInputCallback. Rendered in the chat UI as a " +
                        "secondary label or sub-heading beneath the description."
                )
                whenAbsent("Input dialog shows only the description; no secondary prompt line is shown.")
                example("Password:")
                example("[sudo] password for ubuntu:")
                example("Enter your GitHub personal access token:")
            }
        }
        verdict {
            keep(
                "Uniquely handles the interactive-stdin use case: it is the only tool that (a) collects " +
                    "sensitive input from the user without echoing it into the conversation log, (b) writes " +
                    "that input to a live process stdin, and (c) monitors the process for subsequent output " +
                    "before returning. No combination of ask_followup_question + send_stdin can replicate " +
                    "this safely because ask_followup_question logs its answer to the conversation history.",
                VerdictSeverity.STRONG,
            )
        }
        observation(
            "pendingInput is a single @Volatile CompletableDeferred<String> on the companion object. " +
                "If two ask_user_input calls are in flight simultaneously (impossible with single-threaded " +
                "agent loop but theoretically possible with parallel read-only tool execution) the second " +
                "call overwrites the first and the first user input is lost. Mirrors the same structural " +
                "risk in AskQuestionsTool.pendingQuestions — both are safe under today's serialization " +
                "invariant but worth noting if the loop ever parallelizes input tools."
        )
        observation(
            "The post-input monitor loop is a direct copy of SendStdinTool's monitor loop " +
                "(MONITOR_POLL_MS=500, IDLE_AFTER_INPUT_MS=10s, MAX_WAIT_AFTER_INPUT_MS=60s). " +
                "Both tools inline the same logic — only the lower-level helpers " +
                "`ProcessToolHelpers.collectNewOutput` and `buildIdleContent` are shared. Extracting a " +
                "`ProcessToolHelpers.monitorAfterWrite(...)` helper would pin the invariants in one place."
        )
        observation(
            "On timeout, the process is killed (ProcessRegistry.kill). This is the right default for " +
                "password prompts — an unanswered credential prompt should not leave a zombie process " +
                "holding a file lock or network socket — but it means a user who is simply slow loses " +
                "all prior process output. The configurable timeoutMinutes setting mitigates this for " +
                "users who need more time."
        )
        related(
            "ask_followup_question", Relationship.SEE_ALSO,
            "Contrast: ask_followup_question gathers clarifying information from the user and logs it " +
                "in the conversation (question + answer appear in history). ask_user_input collects " +
                "sensitive runtime input (passwords, tokens) that must NOT be logged and pipes it " +
                "directly into a process stdin — the actual value is never stored in the conversation. " +
                "Use ask_followup_question for task decisions; use ask_user_input when a running process " +
                "is blocked on a credential prompt. NOT interchangeable."
        )
        related(
            "send_stdin", Relationship.SEE_ALSO,
            "send_stdin pipes a value the agent already knows into a process stdin without asking the " +
                "user. Use send_stdin when the input is derivable from context (e.g., confirming a " +
                "prompt with 'y'). Use ask_user_input when the agent cannot know the value (passwords, " +
                "PATs, 2-FA codes). Both tools share the same post-write monitor loop."
        )
        related(
            "plan_mode_respond", Relationship.SEE_ALSO,
            "plan_mode_respond also suspends the loop on a CompletableDeferred, but it is waiting for " +
                "plan approval, not for a process credential. Different suspension purpose; same pattern."
        )
        downside(
            "The user-response timeout is a global AgentSettings field (askUserInputTimeoutMinutes). " +
                "There is no per-call override — a single slow remote connection needing a 10-minute " +
                "window forces raising the global timeout for all tools."
        )
        downside(
            "On timeout, the process is killed unconditionally with no grace period. Any partial " +
                "progress by the process (e.g., a partially written file, a started but incomplete " +
                "database migration) is abandoned without rollback."
        )
        downside(
            "showInputCallback is a @Volatile companion-object function pointer wired at UI init time. " +
                "If the chat panel is not yet mounted (e.g., agent started via API without a UI session) " +
                "the callback is null and the tool fires into a void — the deferred never resolves, " +
                "the process never gets input, and eventually the timeout kills it. There is no null-check " +
                "guard that returns an early error, unlike AskQuestionsTool which checks for callback " +
                "availability before suspending."
        )
        downside(
            "The user's typed input is passed verbatim to ProcessRegistry.writeStdin — no masking, " +
                "no truncation, no encoding. A user who accidentally pastes a multi-MB string (e.g., " +
                "a base64-encoded certificate) into the prompt will block the stdin pipe until the " +
                "process consumes it."
        )
        flowchart("""
            flowchart TD
                A[LLM calls ask_user_input] --> B{process_id in params?}
                B -- "no" --> X1[Return error: missing process_id]
                B -- "yes" --> C{description in params?}
                C -- "no" --> X2[Return error: missing description]
                C -- "yes" --> D[Look up ProcessRegistry.get(process_id)]
                D -- "not found" --> X3[Return error: process not found]
                D -- "found but dead" --> X4[Return error: process dead + exit code]
                D -- "alive" --> E[Create CompletableDeferred; set pendingInput]
                E --> F[Invoke showInputCallback — renders input prompt in chat UI]
                F --> G[withTimeoutOrNull(askUserInputTimeoutMinutes)]
                G -- "user types input + submits" --> H[ProcessRegistry.writeStdin(processId, userInput)]
                G -- "timeout elapses" --> X5[Kill process; return timeout error]
                H -- "write fails (process exited)" --> X6[Return error: stdin write failed]
                H -- "write ok" --> I[Reset idleSignaledAt; snapshot outputSizeBeforeInput]
                I --> J[Monitor loop: poll every 500ms]
                J --> K{process exited?}
                K -- "yes" --> L[Collect new output; unregister; return exit result]
                K -- "no" --> M{new output since last check?}
                M -- "yes" --> N[Update lastNewOutputAt]
                N --> O{MAX_WAIT_AFTER_INPUT elapsed?}
                O -- "yes" --> P[Return IDLE result with new output]
                O -- "no" --> Q{IDLE_AFTER_INPUT since last output?}
                Q -- "yes (output settled)" --> P
                Q -- "no" --> J
                M -- "no" --> O
        """)
    }

    companion object {
        private val LOG = Logger.getInstance(AskUserInputTool::class.java)
        // User-response wait timeout is read from AgentSettings.askUserInputTimeoutMinutes
        // at execution time. Default is 5 minutes (set in AgentSettings.State).
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

        // Wait for user input with timeout (configurable via AgentSettings.askUserInputTimeoutMinutes)
        val timeoutMinutes = com.workflow.orchestrator.agent.settings.AgentSettings
            .getInstance(project).state.askUserInputTimeoutMinutes
        val userInputTimeoutMs = timeoutMinutes * 60_000L
        val userInput = withTimeoutOrNull(userInputTimeoutMs) { deferred.await() }
        pendingInput = null

        if (userInput == null) {
            // Timeout — kill process
            ProcessRegistry.kill(processId)
            return ToolResult(
                "User did not respond within $timeoutMinutes minute(s). Process killed.",
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
            delay(MONITOR_POLL_MS)
            val now = System.currentTimeMillis()

            // Priority 1: process exited
            if (!managed.process.isAlive) {
                val newOutput = ProcessToolHelpers.collectNewOutput(managed, outputSizeBeforeInput)
                val stripped = OutputCollector.stripAnsi(newOutput)
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
                val stripped = OutputCollector.stripAnsi(newOutput)
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
                val stripped = OutputCollector.stripAnsi(newOutput)
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
