package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.security.DefaultCommandFilter
import com.workflow.orchestrator.agent.security.FilterResult
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.process.CommandMutationClassifier
import com.workflow.orchestrator.agent.tools.process.ManagedProcess
import com.workflow.orchestrator.agent.tools.process.OutputCollector
import com.workflow.orchestrator.agent.tools.process.ProcessEnvironment
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.agent.tools.process.ShellResolver
import com.workflow.orchestrator.agent.tools.process.ShellUnavailableException
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolOutputConfig
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
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
    override val description = "Execute a CLI command — for system operations with no IDE-tool equivalent (deploy, Docker, custom scripts, git). Runs in the project directory by default; you MUST set `shell` to one of your environment's available shells. DO NOT pre-wrap the command in a shell yourself (e.g. 'cmd /c …', 'bash -c …') — the tool wraps it in the selected shell, and pre-wrapping double-nests and breaks quoting on Windows. For interactive prompts use background=true + send_stdin (or ask_user_input for passwords); idle output is auto-classified (PASSWORD_PROMPT / STDIN_PROMPT / GENERIC_IDLE) and surfaced — set on_idle=wait to disable. Dangerous commands are blocked."
    override val parameters: FunctionParameters = buildShellParameters(allowedShells)
    override val allowedWorkers = setOf(WorkerType.CODER)
    override val timeoutMs: Long get() = AgentTool.LONG_TOOL_TIMEOUT_MS
    override val outputConfig = ToolOutputConfig.COMMAND

    override fun documentation(): ToolDocumentation = toolDoc("run_command") {
        summary {
            technical(
                "Spawn a shell command via ShellResolver-selected shell, gated by DefaultCommandFilter (hard-block) " +
                    "and an always-per-invocation approval. Output is tail-biased truncated by OutputCollector " +
                    "(build/test failure tails are what the LLM needs); env is sterilised by ProcessEnvironment " +
                    "(35+ secrets stripped, 25 LLM-supplied vars blocked, 15 anti-interactive overrides applied). " +
                    "Foreground default (10-min ceiling) plus optional background mode (no wall-clock cap, monitored " +
                    "via background_process)."
            )
            plain(
                "Like a terminal the agent can type commands into — but the user has to approve every single command, " +
                    "every time. The tool picks the right shell for the OS, blocks obviously dangerous patterns " +
                    "(rm -rf /, sudo, fork bombs) before the command even runs, sanitises the environment so the " +
                    "agent can't leak the user's API tokens or hijack their PATH, and keeps the tail of long output " +
                    "(where errors live) instead of the head."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.PROCESS_SPAWN)
        counterfactual(
            "Without run_command, the agent loses access to the long tail of CLI work that has no dedicated tool: " +
                "git, gh, docker, kubectl, terraform, jq, find, du, ls, cat. The dedicated runtime tools " +
                "(runtime_exec(action=run_config), java_runtime_exec, python_runtime_exec, coverage, build) cover " +
                "the named execution paths — roughly 60% of the workflow. The other 40% disappears, and we'd be " +
                "forced to either build dozens more dedicated tools or accept an agent that can't ship code."
        )
        llmMistake(
            "Forgets to set working_dir and runs from the project root when a sub-module was needed — `mvn test` " +
                "from the parent runs the wrong reactor or fails because the parent isn't a maven module. Should " +
                "pass working_dir='services/foo' instead of prepending `cd services/foo && …`."
        )
        llmMistake(
            "Uses `cd somedir && cmd` instead of the working_dir parameter. Works on Unix but leaks an inner shell " +
                "layer on Windows that breaks quoting; also defeats CommandMutationClassifier's working-dir scoping " +
                "for the post-mutation VFS refresh."
        )
        llmMistake(
            "Pre-wraps the command itself: `cmd /c gradlew test`, `bash -c './script.sh'`, `powershell -Command Get-Date`. " +
                "The tool already wraps via ShellResolver, so the LLM's wrapper creates double-shell nesting that " +
                "mangles quoting on Windows. CommandPrefixStripper detects and strips the redundant prefix and prepends " +
                "a [NOTE] warning, but the LLM should pass the bare command."
        )
        llmMistake(
            "Runs interactive commands that wait for input — `vim`, `less` (when PAGER override is bypassed), " +
                "`ssh user@host` (without -T), `python` (no script), `gh auth login`. These hang and trip the idle " +
                "classifier into LIKELY_STDIN_PROMPT, which kills the process after two idle windows. Should use " +
                "background=true + send_stdin for genuinely interactive flows, or pick a non-interactive variant."
        )
        llmMistake(
            "Pipes a multi-command chain (`a | b | c`) with separate_stderr=false and gets confused when stderr " +
                "from earlier stages interleaves with stdout. Either set separate_stderr=true or test one stage at a time."
        )
        llmMistake(
            "Tries to inline a secret in the command string (`API_TOKEN=abc curl …` or `mysql -p<password> …`). " +
                "The command and its output are logged and may end up in the conversation history. Pass secrets via " +
                "the env parameter, where ProcessEnvironment routes them into the spawned process without echoing " +
                "into logs."
        )
        params {
            required("command", "string") {
                llmSeesIt(
                    "The CLI command to execute. This should be valid for the current operating system and the " +
                        "specified shell. Ensure the command is properly formatted and does not contain any harmful " +
                        "instructions."
                )
                humanReadable(
                    "The actual command line — like what you'd type at a terminal prompt. The tool wraps this in " +
                        "the right shell for the OS, so don't add `cmd /c`, `bash -c`, or `powershell -Command` yourself."
                )
                whenPresent(
                    "Stripped of any redundant shell prefix (cmd /c / bash -c / powershell -Command), checked " +
                        "against DefaultCommandFilter for hard-blocked patterns, then wrapped in the resolved shell."
                )
                constraint(
                    "Hard-blocked patterns are rejected pre-spawn regardless of approval: rm -rf /, rm -rf ~, sudo, " +
                        "mkfs.*, dd if=, : > /, chmod -R 777 /, chown -R … /, fork bombs, curl … | sh, wget … | sh."
                )
                constraint("Quoted strings inside the command (e.g. `grep \"rm -rf\" file.txt`) are exempt from the filter.")
                example("./gradlew :agent:test")
                example("git status --porcelain")
                example("docker ps --format '{{.Names}}'")
            }
            optional("shell", "string") {
                llmSeesIt(
                    "Shell to execute the command in. Use ONLY shells listed as available in your environment. " +
                        "bash = Unix/Git Bash syntax (ls, grep, cat, &&). cmd = Windows cmd.exe syntax (dir, type, " +
                        "findstr). powershell = PowerShell syntax (Get-ChildItem, Select-String)."
                )
                humanReadable(
                    "Which shell to interpret the command. Omitted from the schema entirely on systems with one " +
                        "shell (Unix typically); required on Windows where Git Bash, cmd, and PowerShell are all live."
                )
                whenPresent(
                    "Resolved to a concrete ShellConfig (executable + args) by ShellResolver. Unknown values throw a " +
                        "ShellUnavailableException that surfaces as a tool error listing the actual options."
                )
                whenAbsent(
                    "Falls back to the platform default — bash on Unix, the first available of " +
                        "(Git Bash → PowerShell → cmd) on Windows."
                )
                enumValue("bash", "cmd", "powershell")
                example("bash")
                example("powershell")
            }
            optional("working_dir", "string") {
                llmSeesIt(
                    "Working directory (absolute or relative to project root). Optional, defaults to project root. " +
                        "Example: 'src/main/kotlin'"
                )
                humanReadable(
                    "Where the command runs from. Prefer this over typing `cd somedir && cmd` — the tool handles the " +
                        "directory cleanly without a redundant shell layer, and the post-mutation VFS refresh scopes " +
                        "to it correctly."
                )
                whenPresent(
                    "Path is canonicalised and validated against the project root by PathValidator. Must exist and " +
                        "be a directory."
                )
                whenAbsent("Defaults to project.basePath (the open project's root).")
                example("services/orders")
                example("/Users/me/projects/myrepo")
            }
            required("description", "string") {
                llmSeesIt("A clear explanation of what the command does and why (shown to user in approval dialog).")
                humanReadable(
                    "A short sentence shown to the user when they're asked to approve the command. The user sees " +
                        "this — it's not LLM-internal — so it should be honest about side effects. " +
                        "Note: this value is read by AgentLoop's pre-dispatch approval gate, not by RunCommandTool.execute()."
                )
                whenPresent("Rendered in the approval dialog and prepended to the result summary.")
                example("Run the agent module's unit tests")
                example("List currently running Docker containers")
            }
            optional("timeout", "integer") {
                llmSeesIt(
                    "Timeout in seconds. Default: 300. The hard upper bound is configurable in plugin settings " +
                        "(Process Tools → Run-command max timeout); default ceiling is 600 (10 min). Values exceeding " +
                        "the configured ceiling are clamped."
                )
                humanReadable(
                    "How long the command can run before being killed. Defaults to five minutes; the user-configurable " +
                        "ceiling is ten minutes. For longer-running things use background=true."
                )
                whenPresent("Clamped into [1, AgentSettings.runCommandMaxTimeoutMinutes * 60]. Process is force-killed at the limit and partial output is returned with a [TIMEOUT] marker.")
                whenAbsent("Defaults to 300s.")
                constraint("must be >= 1; values above the configured ceiling clamp silently")
                example("300")
            }
            optional("idle_timeout", "integer") {
                llmSeesIt(
                    "Idle detection threshold in seconds. Default: 15 (60 for build commands). When on_idle=notify " +
                        "(default), an inline classification note is emitted after this many seconds of no output."
                )
                humanReadable(
                    "How long the tool will tolerate silence before classifying the tail (looking for password " +
                        "prompts, stdin prompts, or just generic quiet). Build commands like `gradle test` get a " +
                        "longer default because they legitimately go quiet during compilation."
                )
                whenPresent("Used as the silence threshold for PromptHeuristics.classify; reset every time output appears.")
                whenAbsent(
                    "Defaults from AgentSettings: 15s for normal commands, 60s for ShellResolver.isLikelyBuildCommand " +
                        "matches (gradle, mvn, npm, docker build, pytest, …)."
                )
                constraint("must be >= 1")
                example("30")
                example("120")
            }
            optional("env", "object") {
                llmSeesIt(
                    "Custom environment variables to set for the command. Keys are variable names, values are strings. " +
                        "System/path variables (PATH, HOME, LD_PRELOAD, etc.) are blocked for safety."
                )
                humanReadable(
                    "Extra environment variables for this one command. Use this for secrets the command needs " +
                        "(API tokens, passwords from ask_user_input) — they don't show up in logs the way an inline " +
                        "command-line secret would."
                )
                whenPresent(
                    "Filtered through ProcessEnvironment.filterUserEnv: ~25 vars (PATH, HOME, LD_PRELOAD, " +
                        "DYLD_INSERT_LIBRARIES, JAVA_TOOL_OPTIONS, CLASSPATH, PYTHONPATH, MAVEN_HOME, etc.) are " +
                        "rejected and logged. The rest are merged with the inherited environment plus anti-interactive " +
                        "overrides."
                )
                whenAbsent("No extra vars. The spawn still gets the full sterilised inherited env + anti-interactive overrides.")
                constraint("PATH, HOME, LD_PRELOAD, DYLD_*, JAVA_TOOL_OPTIONS, CLASSPATH, PYTHONPATH, MAVEN_HOME, GRADLE_HOME, etc. are silently dropped if supplied")
                example("{\"GITHUB_TOKEN\": \"ghp_...\"}")
                example("{\"NODE_ENV\": \"production\", \"DEBUG\": \"app:*\"}")
            }
            optional("separate_stderr", "boolean") {
                llmSeesIt(
                    "When true, capture stderr separately and append as [STDERR] section. Default: false (stderr " +
                        "merged with stdout)."
                )
                humanReadable(
                    "Most of the time you want stderr inline with stdout (the natural terminal experience). Turn " +
                        "this on when you specifically need to see error output without it interleaving with progress " +
                        "messages — useful for compilers and CI tools that emit structured output to stdout."
                )
                whenPresent("A separate stderr reader thread captures stderr; the result includes a `[STDERR]` section after the main content.")
                whenAbsent("Defaults to false; stderr is redirected into stdout via withRedirectErrorStream(true).")
                example("true")
            }
            optional("background", "boolean") {
                llmSeesIt(
                    "When true, the command starts in the background and returns immediately with a bgId. Use " +
                        "background_process to monitor, read output, attach, send stdin, or kill. When false (default), " +
                        "the command runs synchronously."
                )
                humanReadable(
                    "Set to true for long-lived processes (dev server, watch mode, slow integration test) that you " +
                        "don't want to block on. The tool returns a bgId after a 500ms grace period; you keep working " +
                        "and check on it later via background_process."
                )
                whenPresent(
                    "Spawns the process, registers it in BackgroundPool[sessionId] (max-concurrent enforced), and " +
                        "returns the bgId + first 500ms of output. No wall-clock timeout in this mode."
                )
                whenAbsent("Defaults to false; the tool blocks until exit, timeout, or idle-prompt-stuck termination.")
                example("true")
            }
            optional("on_idle", "string") {
                llmSeesIt(
                    "Foreground-only (ignored when background=true). What to do when the process produces no output " +
                        "for idle_timeout seconds. 'notify' (default) emits an inline idle signal with classification " +
                        "and keeps waiting. 'wait' ignores idle entirely and blocks until exit or total timeout."
                )
                humanReadable(
                    "How aggressive the idle-handling should be. Default 'notify' classifies the tail (password prompt? " +
                        "stdin prompt? generic quiet?) and acts on it. 'wait' is the escape hatch when you know " +
                        "silence is expected (long sleeps, slow integration tests)."
                )
                whenPresent("Drives the idle branch in the monitor loop: notify keeps the password/stdin/generic classification path active; wait disables it entirely.")
                whenAbsent("Defaults to notify.")
                enumValue("notify", "wait")
                example("wait")
            }
            optional("grep_pattern", "string") {
                llmSeesIt(
                    "Regex pattern to filter output lines. Only lines matching this pattern are returned. Use when " +
                        "you only need specific information from a potentially large output."
                )
                humanReadable(
                    "Auto-injected by AgentLoop because run_command is in OUTPUT_FILTERABLE_TOOLS. Like piping the " +
                        "command's output through `grep <pattern>` before it reaches the LLM. Cheaper than reading the " +
                        "full output and asking the model to find the line."
                )
                whenPresent("Applied to the formatted result lines after spill/truncation; only matching lines are kept.")
                whenAbsent("Full result is returned, subject to tail-biased truncation and optional disk spill.")
                example("(FAILED|ERROR|BUILD SUCCESSFUL)")
                example("^Tests run:")
            }
            optional("output_file", "boolean") {
                llmSeesIt(
                    "If true, save full output to a file and return a preview with the file path. Use for large " +
                        "outputs you may need to search later. Read the file with read_file or search_code."
                )
                humanReadable(
                    "Auto-injected by AgentLoop. When true, the full command output spills to disk under the session " +
                        "directory and the LLM gets just a head/tail preview plus the file path — saves context " +
                        "tokens for outputs the LLM can search later instead of memorising upfront."
                )
                whenPresent("Triggers ToolOutputSpiller; the result is the standard preview (head 20 + tail 10) plus the saved file path.")
                whenAbsent("Output flows through tail-biased OutputCollector with the standard 100K-char COMMAND cap.")
                example("true")
            }
        }
        verdict {
            keep(
                "Architecturally complex (four components — ShellResolver, DefaultCommandFilter, OutputCollector, " +
                    "ProcessEnvironment) but irreplaceable. Without it, the agent loses access to the entire long " +
                    "tail of shell-driven workflow (git, docker, kubectl, terraform, gh, jq, find, …). Dedicated " +
                    "execution tools cover named paths but never the long tail. The complexity is honest — each " +
                    "concern is owned by a small, testable component, and the per-invocation approval gate keeps " +
                    "the blast radius bounded.",
                VerdictSeverity.STRONG,
            )
        }
        related(
            "background_process",
            Relationship.ALTERNATIVE,
            "Use the background path (background=true returns a bgId) when the command is long-lived (dev server, " +
                "watch mode); then poll status / read output / send stdin via background_process.",
        )
        related(
            "send_stdin",
            Relationship.COMPOSE_WITH,
            "Pair with background=true for interactive flows — kick off with run_command, then send_stdin to feed " +
                "the prompts as the LLM observes the output.",
        )
        related(
            "runtime_exec",
            Relationship.ALTERNATIVE,
            "Prefer runtime_exec(action=run_config) when an existing IDE run configuration covers the task — it " +
                "gets readiness detection, port discovery, and the IDE's full process-management UI for free.",
        )
        related(
            "java_runtime_exec",
            Relationship.ALTERNATIVE,
            "For JUnit/TestNG runs prefer java_runtime_exec.run_tests — it routes through the IDE's native test runner " +
                "(real test tree, rerun_failed_tests support, BuildSystemValidator pre-flight) instead of parsing " +
                "raw stdout.",
        )
        related(
            "python_runtime_exec",
            Relationship.ALTERNATIVE,
            "For pytest runs prefer python_runtime_exec.run_tests — uses pytest's native runner with proper -k filtering, " +
                "fallback to shell only when the Python plugin's runner is unavailable.",
        )
        related(
            "build",
            Relationship.ALTERNATIVE,
            "For Gradle / Maven introspection (dependencies, tasks, modules) prefer the build meta-tool — it goes " +
                "through ProjectTaskManager / Maven model APIs and produces structured output instead of parsing " +
                "human-formatted stdout.",
        )
        related(
            "ask_user_input",
            Relationship.COMPOSE_WITH,
            "When run_command terminates with PASSWORD_PROMPT, the recovery path is ask_user_input to obtain the " +
                "secret, then re-run with the secret in the env parameter (never inline in command).",
        )
        observation(
            "DefaultCommandFilter (hard-block, in agent/security/) and CommandSafetyAnalyzer (risk classification " +
                "for the approval gate, also in agent/security/) share a package but execute in different layers — " +
                "the filter inside RunCommandTool.execute, the analyser inside AgentLoop's approval-gate dispatch. " +
                "RunCommandTool never references CommandSafetyAnalyzer. The split is intentional (defence-in-depth " +
                "vs UX) but easy to mistake for redundancy."
        )
        observation(
            "Approval policy is hardcoded ALWAYS_PER_INVOCATION (no allow-for-session option). The other write tools " +
                "(edit_file, create_file, revert_file) can be allow-listed for the session; run_command cannot. The " +
                "rationale lives in ApprovalPolicy.kt — a one-time approval for run_command would silently authorise " +
                "every future command."
        )
        observation(
            "OutputCollector exposes both processOutput (middle-truncated, default for most tools) and " +
                "processOutputTailBiased (used here). The tail-biased variant exists specifically because run_command " +
                "output has its load-bearing summary at the end (build failures, test counts, exit code), and the " +
                "default 60/40 middle-truncation reliably loses it."
        )
        observation(
            "ProcessEnvironment reconciles three concerns in one place: SENSITIVE_ENV_VARS (35+ stripped from inherit), " +
                "BLOCKED_ENV_VARS (25 rejected from LLM env), antiInteractiveEnv (15 forced overrides). Adding a new " +
                "secret requires editing only SENSITIVE_ENV_VARS; adding a new injection vector requires only " +
                "BLOCKED_ENV_VARS. Easy to keep in sync."
        )
        downside(
            "Per-invocation approval is heavy on common workflows — running tests after every edit prompts approval " +
                "each time. The friction is intentional (the alternative is silently delegating the whole machine " +
                "to the LLM) but it's real."
        )
        downside(
            "10-minute foreground ceiling is too short for some legitimate workloads (cold docker pulls, large mvn " +
                "package + assembly runs, pathological integration test suites). Workaround is background=true, but " +
                "the LLM doesn't always reach for it on the first try."
        )
        downside(
            "Output cap is 100K chars even with tail-biasing — extremely chatty commands (verbose Gradle output with " +
                "hundreds of subprojects, deeply nested test logs) lose information. Mitigated by output_file=true " +
                "(spill to disk) but the LLM has to remember to set it."
        )
        downside(
            "Idle-prompt classification is regex-based heuristics over the last 40 lines. False positives kill " +
                "long-running tasks that happen to print prompt-shaped strings (e.g., a JSON value ending in `>`). " +
                "Set on_idle=wait or background=true to opt out."
        )
        downside(
            "Background-mode processes are session-scoped — a new chat kills them. Surprising for users who expect " +
                "long-running dev servers to survive. The runtime_exec detach-on-ready path is the survival route " +
                "for those, but only when there's a named run config to launch."
        )
        downside(
            "ProcessEnvironment's BLOCKED_ENV_VARS are silently dropped (with a log line) rather than surfaced as a " +
                "tool error. The LLM may set DYLD_INSERT_LIBRARIES, see no error, and assume the override took effect."
        )
        flowchart(
            """
            flowchart TD
                A[LLM calls run_command] --> B[CommandPrefixStripper strips redundant cmd /c, bash -c, powershell -Command]
                B --> C{ShellResolver.resolve}
                C -- ShellUnavailableException --> X1[Return shell-unavailable error]
                C -- ShellConfig --> D[DefaultCommandFilter.check]
                D -- Reject --> X2[Return blocked-command error<br/>NEVER spawned]
                D -- Allow --> E[CommandSafetyAnalyzer<br/>called by AgentLoop, NOT by this tool]
                E --> F[Approval gate<br/>ALWAYS_PER_INVOCATION — every call]
                F -- denied --> X3[Tool result: user-rejected]
                F -- approved --> G[ProcessEnvironment.filterUserEnv<br/>strip 25 blocked vars from LLM env]
                G --> H[ProcessEnvironment.applyToEnvironment<br/>strip 35 sensitive + add 15 anti-interactive]
                H --> I{background=true?}
                I -- yes --> J[Spawn → register in BackgroundPool<br/>500ms grace → return bgId]
                I -- no --> K[Spawn process + reader threads]
                K --> L[Monitor loop @ 500ms<br/>- exited?<br/>- timeout?<br/>- idle? → PromptHeuristics.classify]
                L -- PASSWORD_PROMPT --> X4[Kill → recovery: ask_user_input + env]
                L -- STDIN_PROMPT x2 --> X5[Kill → recovery: background+send_stdin]
                L -- exit --> M[applyPostMutationRefresh<br/>VFS refresh + JPS clear]
                L -- timeout --> N[Kill + buildTimeoutResult]
                M --> O[OutputCollector.processOutputTailBiased<br/>strip ANSI → sanitize Unicode → tail-bias trim → optional disk spill]
                O --> P[ToolResult with exit code, summary, content]
            """
        )
        narrative("run_command")
    }

    companion object {
        private val LOG = Logger.getInstance(RunCommandTool::class.java)
        const val DEFAULT_TIMEOUT_SECONDS = 300L

        /**
         * Resolve the effective per-call timeout (in seconds) for a run_command
         * invocation: caller-supplied `timeout` param, falling back to
         * [DEFAULT_TIMEOUT_SECONDS], clamped to the user's
         * `runCommandMaxTimeoutMinutes` setting. Single source of truth for
         * both the in-tool monitor loop (which kills the process at the
         * limit) and the chat-card timeout label rendered in the webview;
         * keep both call sites going through this helper so the displayed
         * cap and the actual cap never drift again.
         */
        fun resolveTimeoutSeconds(params: JsonObject, project: Project): Long {
            val maxTimeoutSeconds = AgentSettings.getInstance(project).state
                .runCommandMaxTimeoutMinutes
                .coerceAtLeast(1)
                .toLong() * 60L
            return (params["timeout"]?.jsonPrimitive?.intOrNull?.toLong() ?: DEFAULT_TIMEOUT_SECONDS)
                .coerceIn(1, maxTimeoutSeconds)
        }
        // Max is configurable in settings (Process Tools → "Run-command max
        // timeout"). Default: 10 minutes. Read at execution time so changes
        // apply to subsequent tool calls without restarting the session.
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
                    description = "Timeout in seconds. Default: 300. The hard upper bound is configurable in plugin settings (Process Tools → Run-command max timeout); default ceiling is 600 (10 min). Values exceeding the configured ceiling are clamped."
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
                    enumValues = listOf("notify", "wait")
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
        val rawCommand = params["command"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'command' parameter required", "Error: missing command", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        // Defense-in-depth: strip a redundant leading shell prefix (cmd /c, bash -c,
        // powershell -Command). The tool already wraps in a ShellResolver-selected
        // shell, so an inner shell layer creates double-wrapping that mangles
        // quoting on Windows (cmd-on-cmd) and adds no value on Unix.
        val stripped = com.workflow.orchestrator.agent.tools.process.CommandPrefixStripper.strip(rawCommand)
        val command = stripped.command
        val prefixWarning: String? = stripped.strippedPrefix?.let { prefix ->
            "[NOTE] Stripped redundant shell prefix '$prefix' — run_command already wraps your command in a shell. " +
                "Pass the bare command next time."
        }

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

            val timeoutSeconds = resolveTimeoutSeconds(params, project)
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
            // Tracks consecutive LIKELY_STDIN_PROMPT classifications across idle
            // windows so a transient prompt-shaped tail (e.g. JSON closing `>`)
            // gets one notify before we kill — but a sustained interactive prompt
            // (cmd.exe banner, REPL waiting for input) terminates fast instead of
            // burning the full timeout.
            var consecutiveStdinPrompts = 0
            var outputSizeAtLastClassification = 0
            while (true) {
                coroutineContext.ensureActive()
                delay(500)
                val now = System.currentTimeMillis()

                // Priority 1: process exited
                if (!process.isAlive) {
                    readerThread.join(IO_DRAIN_TIMEOUT_MS)
                    stderrReaderThread?.join(IO_DRAIN_TIMEOUT_MS)
                    ProcessRegistry.unregister(toolCallId)
                    val mutation = applyPostMutationRefresh(project, command, workingDir)
                    return buildExitResult(managed, command, params, stderrLines, prefixWarning, mutation)
                }

                // Priority 2: total timeout
                if (now - managed.startedAt > timeoutMs) {
                    ProcessRegistry.kill(toolCallId)
                    readerThread.join(IO_DRAIN_TIMEOUT_MS)
                    stderrReaderThread?.join(IO_DRAIN_TIMEOUT_MS)
                    val mutation = applyPostMutationRefresh(project, command, workingDir)
                    return buildTimeoutResult(managed, timeoutSeconds, prefixWarning, mutation)
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

                    // Reset the consecutive-prompt counter if real output appeared
                    // since the previous classification — the prompt was transient.
                    val currentSize = managed.outputLines.size
                    if (currentSize != outputSizeAtLastClassification) {
                        consecutiveStdinPrompts = 0
                    }
                    outputSizeAtLastClassification = currentSize

                    val note = buildIdleNote(classification, idleThresholdMs / 1000)
                    activeStreamCallback?.invoke(toolCallId, note)

                    when (classification) {
                        is com.workflow.orchestrator.agent.tools.process.IdleClassification.LikelyPasswordPrompt -> {
                            // No recovery path without user input — terminate now and route
                            // the LLM to ask_user_input.
                            ProcessRegistry.kill(toolCallId)
                            readerThread.join(IO_DRAIN_TIMEOUT_MS)
                            stderrReaderThread?.join(IO_DRAIN_TIMEOUT_MS)
                            return buildPromptStuckResult(
                                managed,
                                "PASSWORD_PROMPT",
                                classification.promptText,
                                "Process was waiting for a password. Use ask_user_input to obtain credentials, " +
                                    "then re-run with the secret in the env parameter (never inline in command).",
                                prefixWarning,
                            )
                        }
                        is com.workflow.orchestrator.agent.tools.process.IdleClassification.LikelyStdinPrompt -> {
                            consecutiveStdinPrompts++
                            if (consecutiveStdinPrompts >= 2) {
                                // Two consecutive idle windows ending in a prompt-shaped
                                // tail with no output between them — process is genuinely
                                // stuck waiting on stdin. Kill instead of burning timeout.
                                ProcessRegistry.kill(toolCallId)
                                readerThread.join(IO_DRAIN_TIMEOUT_MS)
                                stderrReaderThread?.join(IO_DRAIN_TIMEOUT_MS)
                                return buildPromptStuckResult(
                                    managed,
                                    "STDIN_PROMPT",
                                    classification.promptText,
                                    "Process appeared stuck at an interactive prompt for two idle windows. " +
                                        "Use background=true with send_stdin/background_process for interactive flows, " +
                                        "or set on_idle=wait if the prompt-shape is expected.",
                                    prefixWarning,
                                )
                            }
                        }
                        is com.workflow.orchestrator.agent.tools.process.IdleClassification.GenericIdle -> {
                            consecutiveStdinPrompts = 0
                        }
                    }
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
        stderrLines: List<String>?,
        prefixWarning: String? = null,
        mutation: CommandMutationClassifier.Mutation = CommandMutationClassifier.Mutation.Generic,
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
        if (prefixWarning != null) contentBuilder.append(prefixWarning).append("\n\n")
        vfsHintFor(mutation)?.let { contentBuilder.append(it).append("\n\n") }
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

        // B2: Submodule hint when git fails with "fatal: not a git repository".
        // The error lands in combined stdout/stderr (rawOutput) when separate_stderr=false,
        // or in the separate stderr block when separate_stderr=true.
        val combinedForHint = rawOutput + (stderrLines?.joinToString("") ?: "")
        if (
            command.trim().startsWith("git ") &&
            combinedForHint.contains("fatal: not a git repository")
        ) {
            val effectiveWorkingDir = params["working_dir"]?.jsonPrimitive?.content
                ?: "project root"
            contentBuilder.append(
                "\n\nHint: this command ran from $effectiveWorkingDir. " +
                "If the target path is inside a git submodule, set working_dir to that " +
                "submodule's root (e.g., working_dir=\"sample-common-core\")."
            )
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

    private fun buildTimeoutResult(
        managed: ManagedProcess,
        timeoutSeconds: Long,
        prefixWarning: String? = null,
        mutation: CommandMutationClassifier.Mutation = CommandMutationClassifier.Mutation.Generic,
    ): ToolResult {
        val rawOutput = collectOutput(managed)
        val processed = OutputCollector.processOutputTailBiased(
            rawOutput = rawOutput,
            maxResultChars = outputConfig.maxChars,
            spillDir = null,
            toolCallId = currentToolCallId.get()
        )
        val prefixSection = if (prefixWarning != null) "$prefixWarning\n\n" else ""
        val vfsSection = vfsHintFor(mutation)?.let { "$it\n\n" } ?: ""
        val content = "${prefixSection}${vfsSection}[TIMEOUT] Command timed out after ${timeoutSeconds}s.\nPartial output:\n${processed.content}"
        return ToolResult(
            content = content,
            summary = "Error: command timed out",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = true
        )
    }

    /**
     * Bug 4 — Layers A, B, D: classify the command, refresh VFS at the right scope,
     * drop JPS state for build-cleans. Always best-effort: failures are logged, never thrown.
     *
     * Returns the classification so callers ([buildExitResult] / [buildTimeoutResult])
     * can include the matching `[VFS NOTE]` LLM-facing hint.
     */
    private fun applyPostMutationRefresh(
        project: Project,
        command: String,
        workingDir: String,
    ): CommandMutationClassifier.Mutation {
        val mutation = try {
            CommandMutationClassifier.classify(command)
        } catch (e: Exception) {
            LOG.warn("CommandMutationClassifier.classify failed (non-fatal): ${e.message}")
            return CommandMutationClassifier.Mutation.Generic
        }
        try {
            val workDirPath = java.nio.file.Path.of(workingDir)
            val scope: com.workflow.orchestrator.core.vfs.PostMutationRefresh.Scope =
                when (mutation) {
                    CommandMutationClassifier.Mutation.GitMutator ->
                        com.workflow.orchestrator.core.vfs.PostMutationRefresh.Scope.WholeProject
                    else ->
                        com.workflow.orchestrator.core.vfs.PostMutationRefresh.Scope.WorkingDir(workDirPath)
                }
            com.workflow.orchestrator.core.vfs.PostMutationRefresh.refresh(project, scope, async = true)
            if (mutation == CommandMutationClassifier.Mutation.BuildClean) {
                com.workflow.orchestrator.core.vfs.PostMutationRefresh.clearJpsCache(project)
            }
        } catch (e: Exception) {
            LOG.warn("PostMutationRefresh wiring failed (non-fatal): ${e.message}")
        }
        return mutation
    }

    /**
     * Bug 4 — Layer D: produce a `[VFS NOTE]` block prepended to the tool result so the LLM
     * knows that earlier `read_file` outputs in the conversation may now be stale. Only fires
     * for git mutators (which can revert content) and build cleans (which destroy outputs).
     */
    private fun vfsHintFor(mutation: CommandMutationClassifier.Mutation): String? = when (mutation) {
        CommandMutationClassifier.Mutation.GitMutator ->
            "[VFS NOTE] This command may have changed files on disk. Earlier read_file outputs " +
                "in this conversation may now be stale. Re-read any file you intend to edit before applying changes."
        CommandMutationClassifier.Mutation.BuildClean ->
            "[VFS NOTE] Build outputs were cleaned. Re-build before launching tests."
        else -> null
    }

    private fun buildPromptStuckResult(
        managed: ManagedProcess,
        category: String,
        promptText: String,
        recoveryHint: String,
        prefixWarning: String? = null,
    ): ToolResult {
        val rawOutput = collectOutput(managed)
        val processed = OutputCollector.processOutputTailBiased(
            rawOutput = rawOutput,
            maxResultChars = outputConfig.maxChars,
            spillDir = null,
            toolCallId = currentToolCallId.get()
        )
        val prefixSection = if (prefixWarning != null) "$prefixWarning\n\n" else ""
        val content = buildString {
            append(prefixSection)
            append("[$category] Process killed: it appeared to be waiting for interactive input.\n")
            append("Detected prompt: \"$promptText\"\n")
            append(recoveryHint)
            append("\n\nPartial output:\n")
            append(processed.content)
        }
        return ToolResult(
            content = content,
            summary = "Error: $category — process killed waiting on input",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = true,
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
