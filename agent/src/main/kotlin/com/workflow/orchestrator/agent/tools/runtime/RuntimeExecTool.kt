package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.workflow.orchestrator.agent.tools.debug.DebugInvocation
import com.workflow.orchestrator.core.vfs.PostMutationRefresh
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.TestConsoleUtils
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.agent.util.ReflectionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Runtime observation — universal process and test-result observation for any JetBrains IDE.
 *
 * Split from the original RuntimeExecTool: the Java-specific `run_tests` / `compile_module`
 * actions moved to [JavaRuntimeExecTool], and pytest-specific equivalents live in
 * [PythonRuntimeExecTool]. This tool keeps only the three universal actions that work
 * against the already-running IntelliJ execution / test framework (any RunProfile, any
 * language).
 */
class RuntimeExecTool : AgentTool {

    override val name = "runtime_exec"
    // run_config and get_test_results manage their own readiness/process timeouts (up to 600 s).
    override val timeoutMs: Long get() = Long.MAX_VALUE

    override val description = """
Runtime observation and launch — read console output and structured test results from running or recently finished run configurations, and launch/stop existing run configurations.

Actions and their parameters:
- get_running_processes() → List active run/debug sessions with status and PID
- get_run_output(config_name, last_n_lines?, filter?) → Read process console output (last_n_lines default 200, max 1000; filter: regex pattern). When multiple sessions share the same config name (e.g. a terminated Run tab and a live Debug session), the live one is selected; the result's Note line lists the other matches. The Launch Mode line shows whether the selected session is Run or Debug.
- get_test_results(config_name, status_filter?) → Get structured test results (status_filter: FAILED|ERROR|PASSED|SKIPPED)
- run_config(config_name, mode?, wait_for_ready?, wait_for_pause?, readiness_strategy?, ready_pattern?, ready_timeout_seconds?, wait_for_finish?, timeout_seconds?, tail_lines?, discover_ports?) → Launches fresh. If an instance of the same configuration is already running, it is stopped first (graceful then force). Returns READY with port info or DEBUG with session info.
- stop_run_config(config_name, graceful_timeout_seconds?, force_on_timeout?) → Stop a running process gracefully (and force-kill if timeout exceeded).

To run tests or compile: use java_runtime_exec (on IntelliJ with Java plugin) or python_runtime_exec (on PyCharm).
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_running_processes", "get_run_output", "get_test_results",
                    "run_config", "stop_run_config"
                )
            ),
            "config_name" to ParameterProperty(
                type = "string",
                description = "Name of the run configuration"
            ),
            "last_n_lines" to ParameterProperty(
                type = "integer",
                description = "Number of lines to return from the end (default: 200, max: 1000) — for get_run_output"
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "Regex pattern to filter output lines — for get_run_output"
            ),
            "status_filter" to ParameterProperty(
                type = "string",
                description = "Filter by test status — for get_test_results",
                enumValues = listOf("FAILED", "ERROR", "PASSED", "SKIPPED")
            ),
            "mode" to ParameterProperty(
                type = "string",
                description = "Launch mode — for run_config",
                enumValues = listOf("run", "debug", "coverage", "profile")
            ),
            "wait_for_ready" to ParameterProperty(
                type = "boolean",
                description = "Wait until app signals readiness (default true) — for run_config"
            ),
            "wait_for_pause" to ParameterProperty(
                type = "boolean",
                description = "Wait for first breakpoint pause in debug mode (default false) — for run_config"
            ),
            "readiness_strategy" to ParameterProperty(
                type = "string",
                description = "Override readiness detection strategy — for run_config",
                enumValues = listOf("auto", "process_started", "log_pattern", "idle_stdout", "explicit_pattern", "http_probe")
            ),
            "ready_url" to ParameterProperty(
                type = "string",
                description = "Full URL to probe for readiness (e.g. http://localhost:8080/health). Overrides auto-detection when readiness_strategy=http_probe — for run_config"
            ),
            "ready_pattern" to ParameterProperty(
                type = "string",
                description = "Custom regex to match readiness in stdout (required when readiness_strategy=explicit_pattern) — for run_config"
            ),
            "ready_timeout_seconds" to ParameterProperty(
                type = "integer",
                description = "Timeout for readiness detection in seconds (default 120) — for run_config"
            ),
            "wait_for_finish" to ParameterProperty(
                type = "boolean",
                description = "Block until process exits (default false) — for run_config"
            ),
            "timeout_seconds" to ParameterProperty(
                type = "integer",
                description = "Overall process timeout in seconds (default 600, only when wait_for_finish=true) — for run_config"
            ),
            "tail_lines" to ParameterProperty(
                type = "integer",
                description = "Lines of output to include in result (default 200) — for run_config"
            ),
            "discover_ports" to ParameterProperty(
                type = "boolean",
                description = "Attempt port discovery via log patterns and lsof/ss/netstat (default true) — for run_config"
            ),
            "graceful_timeout_seconds" to ParameterProperty(
                type = "integer",
                description = "Seconds to wait for graceful shutdown before force-killing (default 10) — for stop_run_config"
            ),
            "force_on_timeout" to ParameterProperty(
                type = "boolean",
                description = "Force-kill if graceful timeout exceeded (default true) — for stop_run_config"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER
    )

    override fun documentation(): ToolDocumentation = toolDoc("runtime_exec") {
        summary {
            technical("Single-tool dispatcher for IntelliJ run/debug surfaces: 3 observation actions (get_running_processes, get_run_output, get_test_results) anchored on RunContentManager + ExecutionConsole, and 2 launch actions (run_config, stop_run_config) anchored on ProgramRunnerUtil + ExecutionEnvironment. run_config drives a 5-stage state machine — pre-launch validation, idempotent stop-then-launch, ProgramRunner.Callback launch, readiness pipeline (http_probe / log_pattern / idle_stdout / explicit_pattern / process_started), and detach-on-ready — with 17 distinct error categories and OS-only port discovery (lsof / ss / netstat).")
            plain("The agent's run/debug toolbar buttons, but scriptable. The LLM uses this to launch a Spring Boot app, wait until it's actually serving traffic, learn which port it bound, then keep working — and to read the console of any process the IDE knows about, including ones the user started by clicking the green Run arrow.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.PROCESS_SPAWN)
        counterfactual(
            "Without runtime_exec, every action degrades to run_command. Whole 'launch app, wait until ready, query its endpoints' workflows degrade from 1 tool call into a brittle pipeline of `run_command 'gradle bootRun &'; sleep 30; curl …` that loses readiness detection, port discovery, idempotency, IDE Run-tool-window integration, and before-run tasks. Test-result reading degrades to manually parsing Surefire/Gradle XML. Console reading degrades to tailing log files (if you can find them) and misses sessions the user started via the gutter Run button entirely."
        )
        llmMistake("Confuses `run_config` with `run_command` — uses run_command to launch a server (`gradle bootRun &`), gets no readiness detection, no port info, and no IDE integration. The right move is run_config with the existing run configuration name.")
        llmMistake("Sets `readiness_strategy=http_probe` on a non-Spring app without providing `ready_url` — gets `READINESS_DETECTION_FAILED` because http_probe needs either a Spring config (for actuator paths) or an explicit URL.")
        llmMistake("Expects `readiness_strategy=auto` to work for non-Spring/non-HTTP apps — gets the idle_stdout heuristic, which fires after 2000ms of stdout silence and frequently fires too early on apps that pause output during startup. Fix: pass `readiness_strategy=explicit_pattern` with a known log line.")
        llmMistake("Tries `mode=debug` on a config that wasn't designed as a debug config (e.g. a Maven goal) — this works but gives unexpected results because `wait_for_pause` only fires when an existing breakpoint is hit. Set breakpoints via `debug_breakpoints` first, or just use `mode=run`.")
        llmMistake("Calls `run_config` twice without realizing the first call is idempotent — the first instance is stopped and replaced. Mostly harmless but the LLM is sometimes surprised when the second `READY` payload reports a different PID.")
        llmMistake("Reads a port number out of `Tomcat started on port(s): 8080` in `get_run_output` and assumes it's authoritative — it isn't. The `Listening ports:` field on the run_config result is the only authoritative port source. Run-config overrides via VM options / env vars / profiles can shift the bound port away from the static log-banner number.")
        llmMistake("Treats `STOP_FAILED` from the idempotent pre-launch stop as a retry-able error — it isn't. A force-kill that didn't kill is almost always a kernel- or OS-level problem; escalate to the user.")
        llmMistake("Calls `get_test_results` on a non-test config — gets `No test data` because `findTestRoot` returns null. The action description doesn't gate on config type because the tool can't tell from the descriptor alone whether it's a test run.")
        flowchart("""
            flowchart TD
                A[LLM wants to run/debug/observe] --> B{Already running?}
                B -- check --> C[get_running_processes]
                C --> D{What now?}
                D -- launch fresh --> E[run_config]
                D -- stop one --> F[stop_run_config]
                D -- read console --> G[get_run_output]
                D -- read tests --> H[get_test_results]
                E --> I[Stage 1: validate config_name + check guards]
                I --> J[Stage 2: idempotent stop existing]
                J -- STOP_FAILED --> X[Error to LLM, abort]
                J -- ok --> K[Stage 3: ProgramRunnerUtil.executeConfigurationAsync]
                K --> L[Stage 4: readiness pipeline]
                L -- http_probe --> M[OS port + actuator path → HTTP GET]
                L -- log_pattern --> N[Spring banner regex on stdout]
                L -- idle_stdout --> O[2000ms stdout silence]
                L -- explicit_pattern --> P[user regex on stdout]
                L -- process_started --> Q[return immediately]
                M --> R[Stage 5: detach refs, return READY]
                N --> R
                O --> R
                P --> R
                Q --> R
                R --> S[App keeps running across session resets]
        """)
        actions {
            action("get_running_processes") {
                description {
                    technical("Enumerates active run+debug sessions via ExecutionManager.getRunningProcesses() and XDebuggerManager.debugSessions, returning name, type (Running|Debug), status, and PID where extractable.")
                    plain("'What's alive in the IDE right now?' — like glancing at the Run tool window to see which tabs have green play dots.")
                }
                whenLLMUses("As a cheap state-check before deciding whether to launch a new instance, before calling stop_run_config, or to discover sessions the user started outside the agent (gutter Run button, run-config dropdown).")
                params {}
                rejectsParam("config_name", "Lists all sessions; doesn't filter by name.")
                rejectsParam("mode", "No launch — read-only.")
                rejectsParam("status_filter", "Filters test results, not process list.")
                onSuccess("Returns a multi-line list — one block per session — with name, Type (Running|Debug), Status (Active|Paused at breakpoint), and optional PID. Empty list returns 'No active run/debug sessions.'")
                onFailure("internal exception", "Returns 'Error listing processes: <msg>' with isError=true. Rare — usually means the IDE itself is in a bad state.")
                example("check before launching") {
                    param("action", "get_running_processes")
                    outcome("LLM learns whether 'my-spring-boot-app' is already running before deciding between run_config and get_run_output.")
                }
                verdict {
                    keep("Cheapest action in the tool. Often the difference between one tool call and three (LLM blindly calls run_config which then has to stop-and-relaunch).", VerdictSeverity.NORMAL)
                }
            }
            action("get_run_output") {
                description {
                    technical("Reads the console buffer of a RunContentDescriptor matched by name (exact, then case-insensitive substring). Prefers live descriptors over terminated ones via selectDescriptorByName. Returns last N lines (default 200, max 1000), optionally regex-filtered.")
                    plain("Reads the Console tab of a Run/Debug session — what the user would see if they clicked on that tab. Useful for checking startup logs, error messages, or fishing a port number out of a startup banner.")
                }
                whenLLMUses("After run_config to confirm the app actually started cleanly; after a test run to read uncategorized stdout/stderr; to follow up when readiness detection times out and the LLM needs to see what happened.")
                params {
                    required("config_name", "string") {
                        llmSeesIt("Name of the run configuration")
                        humanReadable("Which Run/Debug tab to read — matches the tab title.")
                        whenPresent("Descriptor with that name (exact or substring) is selected; live descriptors preferred over terminated ones.")
                        constraint("must match a registered or recently-active descriptor")
                        example("MySpringApp")
                    }
                    optional("last_n_lines", "integer") {
                        llmSeesIt("Number of lines to return from the end (default: 200, max: 1000) — for get_run_output")
                        humanReadable("How much of the tail to return.")
                        whenPresent("Returns at most that many lines from the end of the buffer.")
                        whenAbsent("Defaults to 200 lines.")
                        constraint("clamped to [1, 1000]")
                        example("500")
                    }
                    optional("filter", "string") {
                        llmSeesIt("Regex pattern to filter output lines — for get_run_output")
                        humanReadable("Only return lines matching this regex — useful for fishing out errors or specific log lines.")
                        whenPresent("Only matching lines are returned, then last_n_lines is applied to the filtered set.")
                        whenAbsent("All lines from the buffer are considered.")
                        constraint("must compile as a Java regex; invalid patterns return an error")
                        example("ERROR|WARN")
                    }
                }
                rejectsParam("status_filter", "Test-only param.")
                rejectsParam("mode", "No launch.")
                onSuccess("Returns a header (Console Output, Launch Mode, Status, optional Note about other matched sessions, optional Filter) followed by '---' and numbered lines. Output >30K is auto-spilled to disk via ToolOutputSpiller and a preview is returned.")
                onFailure("no descriptor matches name", "Returns 'No run session found matching <name>' with the available session names listed.")
                onFailure("descriptor found but console empty", "Returns '<name> found but console output is empty' (informational).")
                onFailure("invalid regex in filter", "Returns 'Error: invalid regex pattern …'.")
                example("read tail of running app") {
                    param("action", "get_run_output")
                    param("config_name", "MySpringApp")
                    param("last_n_lines", "100")
                    outcome("Returns the last 100 lines of the console — enough to see startup banner + recent activity.")
                }
                example("filter for errors") {
                    param("action", "get_run_output")
                    param("config_name", "MySpringApp")
                    param("filter", "ERROR|FATAL")
                    outcome("Returns only ERROR and FATAL lines — much smaller and more focused.")
                }
                verdict {
                    keep("Only path to console buffers for sessions the user started outside the agent. The tail+filter combination keeps token cost manageable.", VerdictSeverity.STRONG)
                }
            }
            action("get_test_results") {
                description {
                    technical("Reads structured test results from a recently-completed test run via TestConsoleUtils.findTestRoot + interpretTestRoot. Empty suites return NO_TESTS_FOUND (not PASSED). Failed runs propagate isError=true. Waits up to MAX_PROCESS_WAIT_SECONDS (600s) for process termination, TEST_TREE_FINALIZE_TIMEOUT_MS (10s) for the test tree to finalize.")
                    plain("'How did the tests do?' — same data as the test results pane in IntelliJ, structured for the LLM. The LLM picks this over parsing console output because the tree gives it pass/fail/error/skipped counts plus per-test detail.")
                }
                whenLLMUses("After java_runtime_exec.run_tests / python_runtime_exec.run_tests / coverage.run_with_coverage finish, when the LLM needs structured pass/fail counts or wants to filter to FAILED only.")
                params {
                    optional("config_name", "string") {
                        llmSeesIt("Name of the run configuration")
                        humanReadable("Which test run to read.")
                        whenPresent("That descriptor is selected (with the same exact-then-substring matching as get_run_output).")
                        whenAbsent("First descriptor with test results is used; if none, the first running descriptor is tried.")
                        example("MyTestSuite")
                    }
                    optional("status_filter", "string") {
                        llmSeesIt("Filter by test status — for get_test_results")
                        humanReadable("Show only tests with this status.")
                        whenPresent("Output is restricted to tests with this status.")
                        whenAbsent("All tests are shown.")
                        enumValue("FAILED", "ERROR", "PASSED", "SKIPPED")
                        example("FAILED")
                    }
                }
                rejectsParam("last_n_lines", "Console-only param.")
                rejectsParam("filter", "Console-only param.")
                rejectsParam("mode", "No launch.")
                precondition("a test run must exist (typically launched via java_runtime_exec.run_tests, python_runtime_exec.run_tests, or coverage.run_with_coverage)")
                onSuccess("Returns counts (passed/failed/errored/skipped) plus per-test detail when status_filter narrows the set. Empty suites return NO_TESTS_FOUND with isError=true (not PASSED — historical bugfix). Output >30K is auto-spilled to disk.")
                onFailure("process still running after 600s", "Returns 'Process for <name> is still running after 600s (may still be building/compiling). Try again later.' with isError=true.")
                onFailure("descriptor found but no test data", "Returns '<name> found but no test results available. It may not be a test run.'")
                onFailure("no descriptor matches", "Returns 'No test run found matching <name>' or 'No test run results available.'")
                example("get only failures") {
                    param("action", "get_test_results")
                    param("config_name", "MyTestSuite")
                    param("status_filter", "FAILED")
                    outcome("Returns only failing tests — the typical post-run state the LLM cares about.")
                }
                verdict {
                    keep("Empty-suite handling and structured tree access aren't recoverable via run_command parsing. The interpretTestRoot canonicalization is shared with java_runtime_exec and coverage, so the bugfix benefits all test paths.", VerdictSeverity.STRONG)
                }
            }
            action("run_config") {
                description {
                    technical("Launches an existing run configuration with idempotent stop-then-launch (graceful → force kill of any existing instance), full readiness detection (http_probe / log_pattern / idle_stdout / explicit_pattern / process_started, auto-selected by config type), OS-only port discovery (lsof / ss / netstat), and detach-on-ready so the process survives across agent sessions. Drives a 5-stage state machine and emits results from a 17-category error taxonomy.")
                    plain("Press the green Run arrow on a saved run configuration, but with a brain on top: it stops any duplicate instance first, waits until the app is actually serving traffic (not just 'JVM started'), tells you which port it bound, and lets the app keep running after the conversation ends. The single most useful action in the tool.")
                }
                whenLLMUses("To launch a Spring Boot app or other long-running service the user has a saved run configuration for, then immediately query its endpoints. Also for `mode=debug` to drive a debug session via debug_step / debug_inspect.")
                params {
                    required("config_name", "string") {
                        llmSeesIt("Name of the run configuration")
                        humanReadable("Which saved run configuration to launch.")
                        whenPresent("Resolved against RunManager — exact name first, then case-sensitive unique substring.")
                        constraint("must match exactly one config (substring matches that match >1 return AMBIGUOUS_MATCH)")
                        constraint("must NOT be a Remote, JUnit, or TestNG config — those have dedicated tools")
                        example("MySpringApp")
                    }
                    optional("mode", "string") {
                        llmSeesIt("Launch mode — for run_config")
                        humanReadable("Run normally, or under the debugger? `coverage` and `profile` are reserved but currently rejected (use coverage.run_with_coverage instead).")
                        whenPresent("`run` uses DefaultRunExecutor; `debug` uses DefaultDebugExecutor and observes XDebuggerManager.TOPIC for session attach.")
                        whenAbsent("Defaults to `run`.")
                        enumValue("run", "debug", "coverage", "profile")
                        example("debug")
                    }
                    optional("wait_for_ready", "boolean") {
                        llmSeesIt("Wait until app signals readiness (default true) — for run_config")
                        humanReadable("Should the tool block until the app is actually serving traffic, or return as soon as it's launched?")
                        whenPresent("If true, the tool waits for the readiness pipeline before returning READY/DEBUG; on success, refs are nulled to detach the process.")
                        whenAbsent("Defaults to true — the LLM almost always wants to know the app is ready.")
                        example("true")
                    }
                    optional("wait_for_pause", "boolean") {
                        llmSeesIt("Wait for first breakpoint pause in debug mode (default false) — for run_config")
                        humanReadable("In debug mode only: should the tool also wait for the first breakpoint hit before returning?")
                        whenPresent("After service-readiness, the tool subscribes to XDebugSessionListener.sessionPaused and waits up to ready_timeout_seconds for the first pause.")
                        whenAbsent("Defaults to false — the tool returns once the debug session is established.")
                        example("true")
                    }
                    optional("readiness_strategy", "string") {
                        llmSeesIt("Override readiness detection strategy — for run_config")
                        humanReadable("Pick how the tool decides 'ready'. `auto` does the right thing for Spring Boot and most JVM apps; the others are escape hatches.")
                        whenPresent("Forces the corresponding strategy regardless of config type.")
                        whenAbsent("`auto` — http_probe for Spring Boot configs, idle_stdout otherwise.")
                        enumValue("auto", "process_started", "log_pattern", "idle_stdout", "explicit_pattern", "http_probe")
                        example("explicit_pattern")
                    }
                    optional("ready_url", "string") {
                        llmSeesIt("Full URL to probe for readiness (e.g. http://localhost:8080/health). Overrides auto-detection when readiness_strategy=http_probe — for run_config")
                        humanReadable("Full URL to GET for readiness. The user owns correctness — no path-construction or port-discovery wrapping.")
                        whenPresent("Used verbatim for the HTTP probe, bypassing OS port discovery and Spring actuator-path resolution.")
                        whenAbsent("If http_probe is in effect, URL is composed from OS-discovered port + Spring actuator paths.")
                        example("http://localhost:9090/actuator/health/readiness")
                    }
                    optional("ready_pattern", "string") {
                        llmSeesIt("Custom regex to match readiness in stdout (required when readiness_strategy=explicit_pattern) — for run_config")
                        humanReadable("Regex matched against the stdout buffer. The first match flips readiness to true.")
                        whenPresent("Used as the readiness signal when readiness_strategy=explicit_pattern.")
                        whenAbsent("Required when readiness_strategy=explicit_pattern; ignored otherwise.")
                        constraint("must compile as a Java regex")
                        example("Ready to accept connections")
                    }
                    optional("ready_timeout_seconds", "integer") {
                        llmSeesIt("Timeout for readiness detection in seconds (default 120) — for run_config")
                        humanReadable("How long to wait before giving up on readiness.")
                        whenPresent("Caps the readiness wait at this many seconds; on timeout, returns TIMEOUT_WAITING_FOR_READY with the last lines of output.")
                        whenAbsent("Defaults to 120s.")
                        constraint("clamped to [1, 600]")
                        example("180")
                    }
                    optional("wait_for_finish", "boolean") {
                        llmSeesIt("Block until process exits (default false) — for run_config")
                        humanReadable("Set true for short-lived processes (CLI tools, batch jobs) that should run to completion. Mutually exclusive in spirit with wait_for_ready, though both can be set (wait_for_ready is checked first).")
                        whenPresent("After launch, the tool blocks up to timeout_seconds for the process to terminate.")
                        whenAbsent("Defaults to false — fire-and-forget unless wait_for_ready is also set.")
                        example("true")
                    }
                    optional("timeout_seconds", "integer") {
                        llmSeesIt("Overall process timeout in seconds (default 600, only when wait_for_finish=true) — for run_config")
                        humanReadable("Cap on how long to wait for the process to exit when wait_for_finish=true.")
                        whenPresent("On timeout, returns TIMEOUT_WAITING_FOR_PROCESS with last output lines.")
                        whenAbsent("Defaults to 600s.")
                        constraint("clamped to [1, 3600]")
                        example("900")
                    }
                    optional("tail_lines", "integer") {
                        llmSeesIt("Lines of output to include in result (default 200) — for run_config")
                        humanReadable("How many tail lines to include in TIMEOUT and finish payloads.")
                        whenPresent("Cap on lines included in error/finish output.")
                        whenAbsent("Defaults to 200 lines.")
                        constraint("clamped to [1, 1000]")
                        example("500")
                    }
                    optional("discover_ports", "boolean") {
                        llmSeesIt("Attempt port discovery via log patterns and lsof/ss/netstat (default true) — for run_config")
                        humanReadable("Try to learn which TCP ports the process bound. OS-only — never derived from static config.")
                        whenPresent("Runs lsof / ss / netstat by PID after readiness; `Listening TCP port(s):` is added to the result on success.")
                        whenAbsent("Defaults to true; set false if you don't care or the OS commands cost more than they're worth.")
                        example("false")
                    }
                }
                rejectsParam("status_filter", "Test-only param.")
                rejectsParam("last_n_lines", "Read-only console param.")
                rejectsParam("filter", "Read-only console param.")
                precondition("indexing must be complete (smart mode) — DUMB_MODE error if not, after waiting up to 60s")
                precondition("config must pass checkConfiguration() — RuntimeConfigurationError → INVALID_CONFIGURATION; warnings pass")
                precondition("a ProgramRunner must be registered for executor + config-type — else NO_RUNNER_REGISTERED")
                precondition("config must not be Remote, JUnit, or TestNG — those have dedicated tools")
                onSuccess("Returns a multi-line block beginning with READY (run mode) or DEBUG (debug mode), with Config name, Type, Mode, PID, optional Listening ports, optional Ready signal, optional Status, and optional pre-launch note when an existing instance was stopped before relaunch. The process is detached from the agent session — it survives session resets.")
                onFailure("idempotent stop fails", "Returns STOP_FAILED with the PIDs that didn't die. Do NOT retry — escalate.")
                onFailure("indexing didn't finish in 60s", "Returns DUMB_MODE — wait and retry shortly.")
                onFailure("checkConfiguration error", "Returns INVALID_CONFIGURATION with the underlying message.")
                onFailure("no ProgramRunner registered", "Returns NO_RUNNER_REGISTERED — usually means the required plugin isn't installed.")
                onFailure("before-run Build task fails", "Returns BEFORE_RUN_FAILED with per-file compile errors (path:line:col — message), via formatCompileErrors.")
                onFailure("process exits before readiness", "Returns EXITED_BEFORE_READY with exit code. Inspect tail before retrying.")
                onFailure("readiness signal never arrives", "Returns TIMEOUT_WAITING_FOR_READY with the last tail_lines of output.")
                onFailure("http_probe selected without Spring config or ready_url", "Returns READINESS_DETECTION_FAILED — provide ready_url or pick another strategy.")
                onFailure("mode=coverage", "Returns INVALID_CONFIGURATION pointing at coverage.run_with_coverage.")
                onFailure("mode=profile", "Returns INVALID_CONFIGURATION — profile mode not yet implemented.")
                onFailure("uncategorized exception", "Returns UNEXPECTED_ERROR with class name + message.")
                example("launch Spring Boot app and wait for ready") {
                    param("action", "run_config")
                    param("config_name", "MySpringApp")
                    outcome("Returns READY with PID, Listening ports (e.g. {8080, 9090}), and 'HTTP probe 200 OK: http://localhost:8080/actuator/health'. App keeps running after this tool returns; LLM can now curl endpoints.")
                }
                example("launch in debug mode and wait for first pause") {
                    param("action", "run_config")
                    param("config_name", "MySpringApp")
                    param("mode", "debug")
                    param("wait_for_pause", "true")
                    outcome("Returns DEBUG with session name, PID, ports, ready signal, and paused_at: <file>:<line>. LLM can now drive the session via debug_step / debug_inspect.")
                }
                example("launch CLI tool and block until exit") {
                    param("action", "run_config")
                    param("config_name", "RunMigration")
                    param("wait_for_ready", "false")
                    param("wait_for_finish", "true")
                    param("timeout_seconds", "300")
                    outcome("Process runs to completion; returns 'Process RunMigration finished. Exit code: 0' with output tail.")
                }
                example("override readiness for a non-Spring app") {
                    param("action", "run_config")
                    param("config_name", "MyKafkaConsumer")
                    param("readiness_strategy", "explicit_pattern")
                    param("ready_pattern", "Subscribed to topic")
                    outcome("Tool waits until the consumer logs 'Subscribed to topic …' before returning READY.")
                }
                verdict {
                    keep("Irreplaceable. Every alternative loses readiness, ports, idempotency, or all three. The 5-stage state machine and 17-category error taxonomy together represent the most useful concentrated capability in the agent.", VerdictSeverity.STRONG)
                }
            }
            action("stop_run_config") {
                description {
                    technical("Stops processes whose RunContentDescriptor.displayName matches config_name (case-insensitive substring or handler.toString contains). Graceful destroyProcess → poll → force destroyProcess → poll. Reuses the same StopOutcome state machine that run_config uses for its idempotent pre-launch stop.")
                    plain("'Stop the running app cleanly, force-kill if it doesn't respond.' Lets the LLM tear down what it (or the user) launched, without the user having to click the IDE Stop button.")
                }
                whenLLMUses("After done with a server it launched via run_config; before relaunching when the LLM wants explicit ordering rather than the idempotent path; when get_running_processes shows a stale process the LLM doesn't need anymore.")
                params {
                    required("config_name", "string") {
                        llmSeesIt("Name of the run configuration")
                        humanReadable("Which session to stop — matched by descriptor display name (case-insensitive substring).")
                        whenPresent("All matching running processes are stopped.")
                        constraint("must match a running descriptor or process handler — else PROCESS_NOT_RUNNING")
                        example("MySpringApp")
                    }
                    optional("graceful_timeout_seconds", "integer") {
                        llmSeesIt("Seconds to wait for graceful shutdown before force-killing (default 10) — for stop_run_config")
                        humanReadable("How long to give the process to shut down cleanly before force-killing.")
                        whenPresent("Caps the graceful-destroy poll at this many seconds.")
                        whenAbsent("Defaults to 10s.")
                        constraint("clamped to [1, 300]")
                        example("30")
                    }
                    optional("force_on_timeout", "boolean") {
                        llmSeesIt("Force-kill if graceful timeout exceeded (default true) — for stop_run_config")
                        humanReadable("If the graceful shutdown timeout passes, should the tool force-kill?")
                        whenPresent("If false and graceful times out, returns STOP_FAILED instead of force-killing.")
                        whenAbsent("Defaults to true — force-kill is the friendlier default for the agent loop.")
                        example("false")
                    }
                }
                rejectsParam("mode", "No launch.")
                rejectsParam("wait_for_ready", "No launch.")
                rejectsParam("readiness_strategy", "No launch.")
                rejectsParam("ready_pattern", "No launch.")
                rejectsParam("last_n_lines", "Read-only console param.")
                rejectsParam("filter", "Read-only console param.")
                onSuccess("Returns 'Stopped <N> process(es) matching <name>.' Process handler refs and descriptor are cleaned up via the per-launch RunInvocation.")
                onFailure("no matching running process", "Returns PROCESS_NOT_RUNNING — treat as no-op.")
                onFailure("graceful timeout and force_on_timeout=false", "Returns STOP_FAILED.")
                onFailure("graceful + force kill both fail", "Returns STOP_FAILED with the count of processes still running. Kernel-level problem; escalate.")
                example("clean stop") {
                    param("action", "stop_run_config")
                    param("config_name", "MySpringApp")
                    outcome("Sends SIGTERM, waits 10s, force-kills if needed; returns 'Stopped 1 process(es) matching MySpringApp.'")
                }
                example("stop with longer graceful window") {
                    param("action", "stop_run_config")
                    param("config_name", "MyDatabaseApp")
                    param("graceful_timeout_seconds", "60")
                    outcome("Gives 60s for the DB to flush before force-killing — useful for stateful processes.")
                }
                verdict {
                    keep("Stopping a launched server cleanly is the natural pair to run_config. The shared StopOutcome state machine eliminates duplication and keeps idempotency contract honest.", VerdictSeverity.STRONG)
                }
            }
        }
        verdict {
            keep("5 actions consolidated into one tool keeps the schema lean while exposing the full IntelliJ run/debug surface area. run_config's readiness pipeline and idempotent stop-then-launch are irreplaceable; the observation actions are the only path to descriptor-anchored data without re-implementing IntelliJ's RunContentManager. Action enum + targeted state machine make this the highest-leverage tool in the runtime category.", VerdictSeverity.STRONG)
        }
        mergeOpportunity("`stop_run_config` could in principle be merged into `run_config` as a separate `mode=stop` — but the parameter shapes diverge sharply (graceful_timeout_seconds, force_on_timeout vs the readiness/launch cluster), so the merge would clutter run_config's parameter list. Keeping them separate preserves param locality.")
        mergeOpportunity("`get_run_output` and `get_test_results` both pivot on selectDescriptorByName. They could plausibly be merged into a single `get_session_output(format=console|test_tree)` — net effect: -1 action, +1 enum param. Marginal at best; current form is clearer.")
        observation("The launch / observe split mirrors IntelliJ's underlying API split (RunContentManager + ExecutionConsole vs ProgramRunnerUtil + ExecutionEnvironment). The actions can't be split into separate tools without duplicating the descriptor-resolution code in five places.")
        observation("`mode=coverage` and `mode=profile` exist in the schema enum but currently return INVALID_CONFIGURATION pointing the LLM elsewhere. Acceptable surface area for the schema; could be removed once a profiler tool exists.")
        observation("Port discovery is the most consequential design decision. Static port parsing was deliberately removed in favor of OS-only discovery — the tool reports nothing rather than reporting a wrong port. The Spring log banners are kept ONLY as readiness signals; the matched port number is intentionally discarded.")
        observation("Detach-on-ready is what makes 'launch a server, then test it' workflows actually work. Without it, every server dies on session reset.")
        related("run_command", Relationship.ALTERNATIVE, "Use run_command for ad-hoc shell invocations or commands not bound to a saved IntelliJ run configuration. Loses readiness, ports, idempotency, IDE integration.")
        related("java_runtime_exec", Relationship.COMPLEMENT, "Java/Kotlin test running and module compilation. Use java_runtime_exec.run_tests instead of trying to run a JUnit/TestNG config via run_config (which is rejected with INVALID_CONFIGURATION).")
        related("python_runtime_exec", Relationship.COMPLEMENT, "Python pytest running. Same rationale as java_runtime_exec — pytest configs go through python_runtime_exec.")
        related("coverage", Relationship.COMPOSE_WITH, "Coverage uses the same runner machinery. mode=coverage on run_config currently redirects to coverage.run_with_coverage.")
        related("debug_step", Relationship.COMPOSE_WITH, "After mode=debug returns DEBUG, debug_step navigates the session (step_over/into/out, run_to_cursor, resume, pause, stop).")
        related("debug_inspect", Relationship.COMPOSE_WITH, "After mode=debug returns DEBUG and the session is paused, debug_inspect reads variables, evaluates expressions, lists frames.")
        related("debug_breakpoints", Relationship.COMPOSE_WITH, "Set breakpoints first via debug_breakpoints, then run_config(mode=debug, wait_for_pause=true) to launch and stop at the first hit.")
        related("runtime_config", Relationship.COMPLEMENT, "Use runtime_config to create/modify run configurations the LLM doesn't have yet, then run them via run_config.")
        downside("Readiness detection has language and framework limits. http_probe wants a Spring config or explicit ready_url; log_pattern wants Spring banners or explicit_pattern; idle_stdout fires on 2000ms silence and can fire too early on apps that pause output during startup. Non-Spring HTTP services need explicit ready_url.")
        downside("Port discovery requires lsof / ss / netstat to be available. In stripped containers (Alpine without lsof and ss) port info is silently absent — result has no Listening ports field.")
        downside("The 17 error categories are documented but not enumerated in the schema. The LLM has to read the message prefix and remember to branch on it — easy to miss DUMB_MODE vs PROCESS_START_FAILED.")
        downside("`get_test_results` waits up to 600s for a still-running process before giving up. For long-running test suites that's correct, but for the LLM iterating quickly it can stall an iteration boundary.")
        downside("mode=coverage and mode=profile are enum values that don't actually work — they redirect via INVALID_CONFIGURATION. The LLM occasionally picks them and then has to re-pick.")
        downside("Detach-on-ready transfers ownership to the IDE, but the IDE doesn't surface 'this was started by the agent' — the user can't tell which Run tabs are agent-started without context. Mostly cosmetic.")
        narrative("runtime_exec")
    }

    /** Resolve stream callback for live output. */
    private fun resolveStreamCallback(@Suppress("UNUSED_PARAMETER") project: Project): ((String, String) -> Unit)? {
        return RunCommandTool.streamCallback
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "get_running_processes" -> executeGetRunningProcesses(params, project)
            "get_run_output" -> executeGetRunOutput(params, project)
            "get_test_results" -> executeGetTestResults(params, project)
            "run_config" -> executeRunConfig(params, project)
            "stop_run_config" -> executeStopRunConfig(params, project)
            "run_tests", "compile_module" -> ToolResult(
                content = "Action '$action' is handled by java_runtime_exec (on IntelliJ with the Java plugin) or python_runtime_exec (on PyCharm). This tool only provides process observation — use tool_search to load the IDE-specific variant.",
                summary = "Action '$action' moved to java_runtime_exec / python_runtime_exec",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: get_running_processes, get_run_output, get_test_results, run_config, stop_run_config",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_running_processes
    // ══════════════════════════════════════════════════════════════════════

    private fun executeGetRunningProcesses(params: JsonObject, project: Project): ToolResult {
        return try {
            val entries = mutableListOf<ProcessEntry>()

            val runningProcesses = ExecutionManager.getInstance(project).getRunningProcesses()
            for (handler in runningProcesses) {
                val processName = extractProcessName(handler)
                val isDestroyed = handler.isProcessTerminated || handler.isProcessTerminating
                if (!isDestroyed) {
                    entries.add(ProcessEntry(
                        name = processName, type = "Running", status = "Active", pid = extractPid(handler)
                    ))
                }
            }

            val debugSessions = XDebuggerManager.getInstance(project).debugSessions
            for (session in debugSessions) {
                val sessionName = session.sessionName
                val isStopped = session.isStopped
                if (!isStopped && entries.none { it.name == sessionName && it.type == "Debug" }) {
                    val isPaused = session.isPaused
                    val status = when {
                        isPaused -> "Paused (at breakpoint)"
                        else -> "Active"
                    }
                    entries.add(ProcessEntry(name = sessionName, type = "Debug", status = status, pid = null))
                }
            }

            if (entries.isEmpty()) {
                return ToolResult("No active run/debug sessions.", "No processes", 10)
            }

            val sb = StringBuilder()
            sb.appendLine("Active Sessions (${entries.size}):")
            sb.appendLine()

            for (entry in entries) {
                sb.appendLine(entry.name)
                sb.appendLine("  Type: ${entry.type}")
                sb.appendLine("  Status: ${entry.status}")
                entry.pid?.let { sb.appendLine("  PID: $it") }
                sb.appendLine()
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "${entries.size} active sessions", content.length / 4)
        } catch (e: Exception) {
            ToolResult("Error listing processes: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun extractProcessName(handler: ProcessHandler): String {
        return try { handler.toString() } catch (_: Exception) { "Unknown process" }
    }

    private fun extractPid(handler: ProcessHandler): Long? {
        return try {
            val method = handler.javaClass.methods.find { it.name == "getProcess" }
            val process = method?.invoke(handler) as? Process
            process?.pid()
        } catch (_: Exception) { null }
    }

    private data class ProcessEntry(val name: String, val type: String, val status: String, val pid: Long?)

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_run_output
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeGetRunOutput(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'config_name' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val lastNLines = (params["last_n_lines"]?.jsonPrimitive?.intOrNull ?: RUN_OUTPUT_DEFAULT_LINES)
            .coerceIn(1, RUN_OUTPUT_MAX_LINES)

        val filterPattern = params["filter"]?.jsonPrimitive?.content?.let {
            try { Regex(it) }
            catch (e: Exception) {
                return ToolResult("Error: invalid regex pattern '${it}': ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }
        }

        return try {
            val contentManager = RunContentManager.getInstance(project)
            val allDescriptors = contentManager.allDescriptors

            val match = selectDescriptorByName(allDescriptors, configName)

            if (match == null) {
                val available = allDescriptors.mapNotNull { it.displayName }
                val availableMsg = if (available.isNotEmpty()) {
                    "\nAvailable sessions: ${available.joinToString(", ")}"
                } else {
                    "\nNo run sessions available."
                }
                return ToolResult("No run session found matching '$configName'.$availableMsg", "Not found", 30, isError = true)
            }

            val descriptor = match.descriptor
            val consoleText = extractConsoleText(descriptor)

            if (consoleText == null || consoleText.isBlank()) {
                return ToolResult("Run session '${descriptor.displayName}' found but console output is empty.", "Empty output", 10)
            }

            var lines = consoleText.lines()
            if (filterPattern != null) {
                lines = lines.filter { filterPattern.containsMatchIn(it) }
            }

            val totalLines = lines.size
            lines = lines.takeLast(lastNLines)

            val sb = StringBuilder()
            sb.appendLine("Console Output: ${descriptor.displayName}")
            sb.appendLine("Launch Mode: ${describeLaunchMode(descriptor, project)}")
            sb.appendLine("Status: ${describeProcessStatus(descriptor)}")
            if (match.others.isNotEmpty()) {
                val othersDesc = match.others.joinToString(", ") { d ->
                    "${d.displayName} [${describeLaunchMode(d, project)} / ${describeProcessStatus(d)}]"
                }
                sb.appendLine("Note: ${match.others.size + 1} sessions matched '$configName'. " +
                    "Selected the ${if (match.pickedLive) "live" else "most recent"} one. Other matches: $othersDesc")
            }
            if (filterPattern != null) {
                sb.appendLine("Filter: ${filterPattern.pattern}")
            }
            if (totalLines > lastNLines) {
                sb.appendLine("Showing last $lastNLines of $totalLines lines")
            }
            sb.appendLine("---")

            val startLineNum = (totalLines - lines.size) + 1
            for ((index, line) in lines.withIndex()) {
                sb.appendLine("${startLineNum + index}: $line")
            }

            val content = sb.toString().trimEnd()
            val spilled = spillOrFormat(content, project)

            ToolResult(
                content = spilled.preview,
                summary = "${lines.size} lines from ${descriptor.displayName}",
                tokenEstimate = spilled.preview.length / 4,
                spillPath = spilled.spilledToFile,
            )
        } catch (e: Exception) {
            ToolResult("Error getting run output: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private suspend fun extractConsoleText(descriptor: RunContentDescriptor): String? {
        val console = descriptor.executionConsole ?: return null

        val unwrapped = unwrapToConsoleView(console)
        if (unwrapped != null) {
            val text = readConsoleViewText(unwrapped)
            if (!text.isNullOrBlank()) return text
        }

        if (console is com.intellij.execution.impl.ConsoleViewImpl) {
            val text = readConsoleViewText(console)
            if (!text.isNullOrBlank()) return text
        }

        if (console is com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView) {
            val innerConsole = console.console
            if (innerConsole is com.intellij.execution.impl.ConsoleViewImpl) {
                val text = readConsoleViewText(innerConsole)
                if (!text.isNullOrBlank()) return text
            }
            val text = readViaEditor(innerConsole)
            if (!text.isNullOrBlank()) return text
        }

        try {
            val getConsole = console.javaClass.getMethod("getConsole")
            val innerConsole = getConsole.invoke(console)
            if (innerConsole is com.intellij.execution.impl.ConsoleViewImpl) {
                val text = readConsoleViewText(innerConsole)
                if (!text.isNullOrBlank()) return text
            }
            if (innerConsole != null) {
                val text = readViaEditor(innerConsole)
                if (!text.isNullOrBlank()) return text
            }
        } catch (_: Exception) {}

        return readViaEditor(console)
    }

    private fun unwrapToConsoleView(console: Any): com.intellij.execution.impl.ConsoleViewImpl? {
        var current: Any? = console
        repeat(MAX_UNWRAP_DEPTH) {
            if (current is com.intellij.execution.impl.ConsoleViewImpl) return current

            val delegate = ReflectionUtils.tryInvoke(current, "getDelegate")
            if (delegate is com.intellij.execution.impl.ConsoleViewImpl) return delegate
            if (delegate != null && delegate !== current) {
                current = delegate
                return@repeat
            }

            val inner = ReflectionUtils.tryInvoke(current, "getConsole")
            if (inner is com.intellij.execution.impl.ConsoleViewImpl) return inner
            if (inner != null && inner !== current) {
                current = inner
                return@repeat
            }

            return null
        }
        return current as? com.intellij.execution.impl.ConsoleViewImpl
    }

    private suspend fun readConsoleViewText(console: com.intellij.execution.impl.ConsoleViewImpl): String? {
        return try {
            withContext(Dispatchers.EDT) {
                console.component
                console.flushDeferredText()
                console.editor?.document?.text
            }
        } catch (_: Exception) { null }
    }

    private suspend fun readViaEditor(console: Any): String? {
        return try {
            withContext(Dispatchers.EDT) {
                try { console.javaClass.getMethod("getComponent").invoke(console) } catch (_: Exception) {}
                val editorMethod = console.javaClass.getMethod("getEditor")
                val editor = editorMethod.invoke(console) as? com.intellij.openapi.editor.Editor
                editor?.document?.text
            }
        } catch (_: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_test_results
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeGetTestResults(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content
        val statusFilter = params["status_filter"]?.jsonPrimitive?.content?.uppercase()

        return try {
            val contentManager = RunContentManager.getInstance(project)
            val allDescriptors = contentManager.allDescriptors

            val descriptor = if (configName != null) {
                selectDescriptorByName(allDescriptors, configName)?.descriptor
            } else {
                allDescriptors.firstOrNull { desc -> hasTestResults(desc) }
                    ?: allDescriptors.firstOrNull { desc ->
                        desc.processHandler?.let { !it.isProcessTerminated } == true
                    }
            }

            if (descriptor == null) {
                val msg = if (configName != null) "No test run found matching '$configName'." else "No test run results available."
                return ToolResult(msg, "No test results", 10, isError = true)
            }

            val handler = descriptor.processHandler
            if (handler != null && !handler.isProcessTerminated) {
                val processTerminated = awaitProcessTermination(handler, MAX_PROCESS_WAIT_SECONDS * 1000L, project)
                if (!processTerminated) {
                    return ToolResult(
                        "Process for '${descriptor.displayName}' is still running after ${MAX_PROCESS_WAIT_SECONDS}s " +
                            "(may still be building/compiling). Try again later.",
                        "Process still running", 20, isError = true
                    )
                }
            }

            val testConsole = TestConsoleUtils.unwrapToTestConsole(descriptor.executionConsole)
            if (testConsole != null) {
                awaitTestingFinished(testConsole.resultsViewer, TEST_TREE_FINALIZE_TIMEOUT_MS)
            } else {
                // No test console — retry until the test tree is populated
                for (attempt in 1..TEST_TREE_RETRY_ATTEMPTS) {
                    if (TestConsoleUtils.findTestRoot(descriptor)?.children?.isNotEmpty() == true) break
                    delay(TEST_TREE_RETRY_INTERVAL_MS)
                }
            }

            val testRoot = TestConsoleUtils.findTestRoot(descriptor)
            if (testRoot == null) {
                return ToolResult(
                    "Run session '${descriptor.displayName}' found but no test results available. It may not be a test run.",
                    "No test data", 15, isError = true
                )
            }

            // Route through the canonical interpreter — correctly handles empty-suite /
            // terminated / defect cases (fixes the "0/0/0/0 → PASSED" bug where the old
            // inline classifier mapped an empty suite to PASSED). formatStructuredResults
            // routes through spillOrFormat so full output lands on disk.
            interpretTestRoot(testRoot, descriptor.displayName ?: "unknown", this, project, statusFilter)
        } catch (e: Exception) {
            ToolResult("Error getting test results: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private suspend fun awaitProcessTermination(handler: ProcessHandler, timeoutMs: Long, project: Project? = null): Boolean {
        if (handler.isProcessTerminated) return true

        val toolCallId = RunCommandTool.currentToolCallId.get()
        val streamCallback = if (project != null) resolveStreamCallback(project) else RunCommandTool.streamCallback

        val terminated = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val progressJob = if (toolCallId != null && streamCallback != null) {
                    launch {
                        var elapsed = 0L
                        while (true) {
                            delay(PROGRESS_INTERVAL_MS)
                            elapsed += PROGRESS_INTERVAL_MS
                            streamCallback.invoke(toolCallId, "[waiting for process... ${elapsed / 1000}s elapsed]\n")
                        }
                    }
                } else null

                try {
                    suspendCancellableCoroutine { continuation ->
                        val listener = object : ProcessAdapter() {
                            override fun processTerminated(event: ProcessEvent) {
                                if (continuation.isActive) continuation.resume(true)
                            }
                        }
                        handler.addProcessListener(listener)
                        continuation.invokeOnCancellation {
                            handler.removeProcessListener(listener)
                        }
                        if (handler.isProcessTerminated && continuation.isActive) {
                            continuation.resume(true)
                        }
                    }
                } finally {
                    progressJob?.cancel()
                }
            }
        }

        if (terminated == null && toolCallId != null) {
            streamCallback?.invoke(toolCallId, "[process still running after ${timeoutMs / 1000}s]\n")
        }

        return terminated ?: false
    }

    private suspend fun awaitTestingFinished(resultsViewer: TestResultsViewer, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                // M1 fix: tie the listener lifecycle to a child Disposable registered on
                // resultsViewer so it is released when the viewer is disposed. Also wire
                // invokeOnCancellation so coroutine cancel/timeout disposes the child
                // immediately — preventing a listener from outliving the coroutine.
                // TestResultsViewer has no removeEventsListener API; Disposer is the only
                // documented release path (mirrors JavaRuntimeExecTool.handleDescriptorReady).
                val child = Disposer.newDisposable(resultsViewer, "agent-awaitTestingFinished")
                val listener = object : TestResultsViewer.EventsListener {
                    override fun onTestingFinished(sender: TestResultsViewer) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                resultsViewer.addEventsListener(listener)
                continuation.invokeOnCancellation {
                    Disposer.dispose(child)
                }
            }
        }
    }

    private fun hasTestResults(descriptor: RunContentDescriptor): Boolean {
        val root = TestConsoleUtils.findTestRoot(descriptor) ?: return false
        return root.children.isNotEmpty()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: run_config — unified launcher (run + debug modes)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Launch an existing run configuration in run or debug mode. Waits for readiness
     * (log-pattern / idle-stdout / XDebugSession attached) then detaches — app stays alive.
     * Returns READY (run) or DEBUG (debug mode) payload with PID, ports, and ready signal.
     */
    private suspend fun executeRunConfig(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "CONFIGURATION_NOT_FOUND: Missing required parameter 'config_name'.",
                "Missing config_name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        val mode = params["mode"]?.jsonPrimitive?.content ?: "run"
        val waitForReady = params["wait_for_ready"]?.jsonPrimitive?.booleanOrNull ?: true
        val waitForPause = params["wait_for_pause"]?.jsonPrimitive?.booleanOrNull ?: false
        val readinessStrategy = params["readiness_strategy"]?.jsonPrimitive?.content ?: "auto"
        val readyPattern = params["ready_pattern"]?.jsonPrimitive?.content
        val readyUrl = params["ready_url"]?.jsonPrimitive?.content
        val readyTimeoutSec = (params["ready_timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 120).coerceIn(1, 600)
        val waitForFinish = params["wait_for_finish"]?.jsonPrimitive?.booleanOrNull ?: false
        val timeoutSec = (params["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 600).coerceIn(1, 3600)
        val tailLines = (params["tail_lines"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(1, 1000)
        val discoverPorts = params["discover_ports"]?.jsonPrimitive?.booleanOrNull ?: true

        // Guard unsupported modes early
        if (mode == "coverage") return ToolResult(
            "INVALID_CONFIGURATION: mode=coverage is not yet supported by run_config. " +
                "Use coverage.run_with_coverage instead.",
            "mode=coverage not supported", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )
        if (mode == "profile") return ToolResult(
            "INVALID_CONFIGURATION: mode=profile is not yet supported by run_config. " +
                "A dedicated profiler tool is planned for a future release.",
            "mode=profile not supported", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )
        if (mode != "run" && mode != "debug") return ToolResult(
            "INVALID_CONFIGURATION: Unknown mode '$mode'. Supported: run, debug.",
            "Unknown mode", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )

        // Bug 4 — Layer C: upgrade the existing isDumb hard-error to a wait-then-fail.
        // A recent run_command may have triggered a wide VFS refresh that is currently
        // reindexing; suspending until smart mode is the friendlier behaviour.
        if (!com.workflow.orchestrator.core.vfs.waitForSmartModeOrTimeout(project)) {
            return ToolResult(
                "DUMB_MODE: indexing did not complete within 60s. A recent file mutation " +
                    "triggered reindexing. Retry shortly.",
                "DUMB_MODE: timeout waiting for indexing",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        }

        // Name resolution: exact match → unique substring → error
        // M3 fix: RunManager.getInstance + allSettings access must run inside a read action
        // (same pattern as JavaRuntimeExecTool.detectTestFramework / findModuleForClass and
        // DebugBreakpointsTool.executeMethodBreakpoint which all use a read action).
        val runManager = RunManager.getInstance(project)
        val allSettings = readAction {
            runManager.allSettings
        }
        val settings = readAction {
            runManager.findConfigurationByName(configName)
        } ?: run {
            val matches = allSettings.filter { it.name.contains(configName, ignoreCase = false) }
            when {
                matches.size == 1 -> matches[0]
                matches.size > 1 -> return ToolResult(
                    "AMBIGUOUS_MATCH: '$configName' matches multiple configurations: " +
                        matches.joinToString(", ") { it.name } +
                        ". Use the exact name.",
                    "AMBIGUOUS_MATCH", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
                else -> return ToolResult(
                    "CONFIGURATION_NOT_FOUND: No run configuration named '$configName'. " +
                        "Available: [${allSettings.joinToString(", ") { it.name }}]",
                    "CONFIGURATION_NOT_FOUND", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
            }
        }

        // Config-type guardrails
        val config = settings.configuration
        val configTypeId = config.type.id
        if (configTypeId == "Remote") return ToolResult(
            "INVALID_CONFIGURATION: '$configName' is a remote debug configuration. " +
                "Use debug_breakpoints.attach_to_process instead.",
            "INVALID_CONFIGURATION: remote", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )
        if (configTypeId == "JUnit" || configTypeId == "TestNG") return ToolResult(
            "INVALID_CONFIGURATION: '$configName' is a test configuration. " +
                "Use java_runtime_exec.run_tests instead.",
            "INVALID_CONFIGURATION: junit", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )

        // checkConfiguration — errors block, warnings pass.
        // Must run inside a smart read action: Spring Boot's checkConfiguration() resolves the
        // main class via JavaPsiFacade.findClass(), which triggers FileBasedIndex.ensureUpToDate()
        // and asserts read access. We're on a Dispatchers.IO worker here, so wrap in smartReadAction.
        try {
            smartReadAction(project) {
                config.checkConfiguration()
            }
        } catch (e: RuntimeConfigurationError) {
            return ToolResult(
                "INVALID_CONFIGURATION: ${e.message}",
                "INVALID_CONFIGURATION", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } catch (_: RuntimeConfigurationWarning) {
            // Warnings are non-fatal — proceed with launch
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Other exceptions from checkConfiguration — proceed
        }

        // Idempotent stop: if an instance of this config is already running, stop it first.
        // Uses the resolved settings.name so substring-matched configs target the specific name.
        val preLaunchNote: String? = when (val stopOutcome = stopProcessesForConfig(project, settings.name)) {
            is StopOutcome.NotRunning -> null
            is StopOutcome.StoppedGracefully ->
                "Stopped existing '${settings.name}' (PID ${stopOutcome.pids}, graceful) before relaunch."
            is StopOutcome.StoppedForced ->
                "Stopped existing '${settings.name}' (PID ${stopOutcome.pids}, forced) before relaunch."
            is StopOutcome.FailedToStop ->
                return ToolResult(
                    "STOP_FAILED: Could not stop existing instance of '${settings.name}' " +
                        "(PID ${stopOutcome.pids}): ${stopOutcome.message}",
                    "STOP_FAILED", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
        }

        // Executor selection
        val executor = when (mode) {
            "debug" -> DefaultDebugExecutor.getDebugExecutorInstance()
            else -> DefaultRunExecutor.getRunExecutorInstance()
        }

        // Build ExecutionEnvironment
        val envBuilder = ExecutionEnvironmentBuilder.createOrNull(executor, settings)
            ?: return ToolResult(
                "NO_RUNNER_REGISTERED: No ProgramRunner registered for executor '${executor.id}' " +
                    "on configuration type '$configTypeId'. Check that the required plugin is installed.",
                "NO_RUNNER_REGISTERED", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        val invocation = project.service<AgentService>().newRunInvocation("run-config-${config.name}")
        try {
            val env = envBuilder.build()
            val launchEnv = AtomicReference<ExecutionEnvironment?>(env)

            // Readiness state
            val processStartFailed = AtomicBoolean(false)
            val processStartFailedMsg = AtomicReference<String?>(null)
            val descriptorRef = AtomicReference<RunContentDescriptor?>(null)
            val processHandlerRef = AtomicReference<ProcessHandler?>(null)

            // Follow-up A: Subscribe to CompilerTopics.COMPILATION_STATUS BEFORE launching
            // so we can capture per-file compile errors from the implicit before-run Build task.
            // The AtomicReference pattern is identical to JavaRuntimeExecTool's explicit build
            // phase (JavaRuntimeExecTool.kt:340-362) — stores CompileContext for use in the
            // processNotStarted callback below when discriminating BEFORE_RUN_FAILED vs
            // PROCESS_START_FAILED (research doc Section A4-A5).
            val compileContextRef = AtomicReference<CompileContext?>(null)
            val compileConnection = project.messageBus.connect()
            invocation.subscribeTopic(compileConnection)
            compileConnection.subscribe(
                CompilerTopics.COMPILATION_STATUS,
                object : CompilationStatusListener {
                    override fun compilationFinished(
                        aborted: Boolean,
                        errors: Int,
                        warnings: Int,
                        compileContext: CompileContext,
                    ) {
                        if (aborted || errors > 0) {
                            compileContextRef.set(compileContext)
                        }
                    }
                }
            )

            // Subscribe to EXECUTION_TOPIC before launching (Section 5 correlation).
            // processNotStarted: discriminate BEFORE_RUN_FAILED (compile errors captured
            // in compileContextRef) vs PROCESS_START_FAILED (generic launch failure).
            val runConnection = project.messageBus.connect()
            invocation.subscribeTopic(runConnection)
            runConnection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processNotStarted(executorId: String, e: ExecutionEnvironment) {
                    if (e === launchEnv.get()) {
                        val compileCtx = compileContextRef.get()
                        if (compileCtx != null) {
                            // Before-run Build task failed: surface per-file compile errors
                            // (research doc Section A5, checklist D1f).
                            val compileResult = formatCompileErrors(
                                context = compileCtx,
                                target = config.name,
                                leadingLine = "BEFORE_RUN_FAILED: Build task failed before launch of '${config.name}':"
                            )
                            processStartFailed.set(true)
                            processStartFailedMsg.set(compileResult.content)
                        } else {
                            processStartFailed.set(true)
                            processStartFailedMsg.set("PROCESS_START_FAILED: Process failed to start.")
                        }
                    }
                }
                override fun processStarted(
                    executorId: String,
                    e: ExecutionEnvironment,
                    handler: ProcessHandler,
                ) {
                    // Observation only; handler stored via ProgramRunner.Callback
                }
            })

            // Accumulated log output and readiness signal
            val logBuffer = StringBuilder()
            val readinessAchieved = AtomicBoolean(false)
            val readySignal = AtomicReference<String?>(null)
            val discoveredPorts = mutableSetOf<Int>()

            // Spring Boot readiness patterns — used ONLY as readiness signals (app has finished
            // bootstrapping). The matched port number in groups[1] is intentionally NOT stored as
            // the port value reported to the LLM: run configurations can override the port via VM
            // options, env vars, active profiles, programmatic setDefaultProperties, cloud config,
            // or random port mode (server.port=0). Only OS PID discovery (lsof/ss/netstat) is the
            // authoritative port source. See discoverListeningPorts() and the design note in
            // SpringBootConfigParser.kt.
            val springReadinessPatterns = listOf(
                Regex("""^.*Tomcat started on port\(?s?\)?:?\s*(\d+)""", RegexOption.MULTILINE),
                Regex("""^.*Netty started on port\(?s?\)?:?\s*(\d+)""", RegexOption.MULTILINE),
                Regex("""^.*Jetty started on port\(?s?\)?:?\s*(\d+)""", RegexOption.MULTILINE),
                Regex("""^.*Undertow started on port\(?s?\)?:?\s*(\d+)""", RegexOption.MULTILINE),
                Regex("""^.*Started\s+[A-Za-z0-9._-]+\s+in\s+[0-9.]+s""", RegexOption.MULTILINE),
            )

            // Launch callback
            val callbackSlot = AtomicReference<ProgramRunner.Callback?>(null)
            val processListenerAttached = AtomicBoolean(false)

            val callback = object : ProgramRunner.Callback {
                override fun processStarted(descriptor: RunContentDescriptor?) {
                    if (descriptor == null) return
                    descriptorRef.set(descriptor)
                    val handler = descriptor.processHandler ?: return
                    processHandlerRef.set(handler)
                    invocation.descriptorRef.set(descriptor)
                    invocation.processHandlerRef.set(handler)

                    // Register cleanup (source-text anchor for leak test A3).
                    // Only remove descriptor from RunContentManager when NOT detached.
                    // Detach path (wait_for_ready=true, wait_for_finish=false) sets
                    // invocation.descriptorRef to null before Disposer.dispose() so the
                    // process keeps running; removeRunContent must be skipped in that case.
                    invocation.onDispose {
                        try {
                            if (invocation.descriptorRef.get() != null) {
                                RunContentManager.getInstance(project).removeRunContent(executor, descriptor)
                            }
                        } catch (_: Exception) {}
                    }

                    // Attach readiness listener for both Run and Debug modes. The listener is
                    // a plain ProcessAdapter and works identically on either handler. Gating it on
                    // mode=="run" caused debug launches to return "DEBUG session active" immediately
                    // after the debugger attached (pre-main), before the service was actually up.
                    if (waitForReady) {
                        val readinessListener = object : ProcessAdapter() {
                            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                                if (readinessAchieved.get()) return
                                val text = event.text ?: return
                                logBuffer.append(text)

                                // Check explicit_pattern first
                                if (readinessStrategy == "explicit_pattern" && readyPattern != null) {
                                    if (Regex(readyPattern).containsMatchIn(text)) {
                                        readinessAchieved.set(true)
                                        readySignal.set(text.trim())
                                    }
                                    return
                                }

                                // Check log patterns (Spring Boot banners) — readiness signal only.
                                // The matched port number in groups[1] is intentionally discarded:
                                // run-config overrides make static log-scraped ports unreliable.
                                // Authoritative port comes from discoverListeningPorts() via OS PID.
                                for (pattern in springReadinessPatterns) {
                                    val match = pattern.find(logBuffer.toString())
                                    if (match != null) {
                                        readinessAchieved.set(true)
                                        readySignal.set(text.trim())
                                        // Port from log banner intentionally not stored here —
                                        // OS PID discovery is the only authoritative port source.
                                        break
                                    }
                                }
                            }

                            override fun processTerminated(event: ProcessEvent) {
                                if (!readinessAchieved.get()) {
                                    // Process exited before ready — mark as failed
                                    processStartFailed.set(true)
                                    processStartFailedMsg.set(
                                        "EXITED_BEFORE_READY: Process exited with code ${event.exitCode} " +
                                            "before reaching ready state."
                                    )
                                }
                            }
                        }
                        invocation.attachProcessListener(handler, readinessListener)
                        processListenerAttached.set(true)
                    }
                }
            }
            callbackSlot.set(callback)

            // Launch: ProgramRunnerUtil.executeConfigurationAsync handles its own EDT dispatch
            // internally (2025.1+ implementation posts to EDT via getApplication().invokeLaterIfEdtRequired).
            // We do NOT wrap in withContext(Dispatchers.EDT) here because that requires an initialized
            // IntelliJ Application context and would fail in unit tests that mock the static APIs.
            // The callback and ExecutionListener wired above receive results asynchronously regardless
            // of which thread calls executeConfigurationAsync.
            //
            // Drop JPS's in-memory build snapshot so the run config's "Build before launch" task
            // re-stats sources from disk — matches the same pattern in run_tests / coverage to
            // avoid silent no-op builds against the stale dependency graph.
            try { PostMutationRefresh.clearJpsCache(project) } catch (_: Exception) {}
            try {
                ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
            } catch (_: NoSuchMethodError) {
                env.callback = callback
                ProgramRunnerUtil.executeConfiguration(env, false, true)
            }

            // Check for immediate launch failure
            if (processStartFailed.get()) {
                val baseMsg = processStartFailedMsg.get() ?: "PROCESS_START_FAILED: Unknown launch failure."
                // A process that exits before ready often has its crash cause in the tail
                // (e.g. "connection refused"); surface it like TIMEOUT_WAITING_FOR_READY does.
                val msg = withTailOutput(baseMsg, logBuffer.toString(), tailLines)
                return ToolResult(msg, "Launch failed", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            // Service-readiness wait, shared between Run and Debug modes. Returns null on success
            // (readiness achieved; httpProbeSignal populated if an HTTP probe hit), or a ToolResult
            // on failure (validation error, exited-before-ready, or timeout).
            suspend fun awaitServiceReadiness(httpProbeSignal: AtomicReference<String?>): ToolResult? {
                val isSpring = configTypeId.contains("SpringBoot", ignoreCase = true) ||
                    configTypeId.contains("spring", ignoreCase = true) ||
                    SpringBootConfigParser.isSpringBootConfig(settings)

                // Validate http_probe without Spring config or explicit ready_url
                if (readinessStrategy == "http_probe" && !isSpring && readyUrl == null) {
                    return ToolResult(
                        "READINESS_DETECTION_FAILED: http_probe requires a Spring Boot config or an explicit ready_url parameter.",
                        "READINESS_DETECTION_FAILED", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }

                // Determine effective strategy
                val useHttpProbe = readyUrl != null ||
                    readinessStrategy == "http_probe" ||
                    (readinessStrategy == "auto" && isSpring)

                val readyResult: Boolean?

                if (useHttpProbe) {
                    // Resolve probe URL.
                    // If the user supplied a ready_url, use it verbatim — the user owns correctness.
                    // Otherwise, we MUST have an OS-discovered port before probing: static config
                    // parsing is intentionally skipped because run configurations override the port
                    // via VM options, env vars, active profiles, programmatic setDefaultProperties,
                    // cloud config, or random-port mode (server.port=0) that static parsing cannot see.
                    val probeUrl = if (readyUrl != null) {
                        readyUrl
                    } else {
                        // Wait briefly for the process to start binding, then attempt OS port discovery.
                        // We poll up to HTTP_PROBE_GRACE_MS (5 s) before giving up.
                        val pid = processHandlerRef.get()?.let { extractPid(it) }
                        var osPort: Int? = null
                        if (pid != null) {
                            val probeStartMs = System.currentTimeMillis()
                            while (osPort == null && (System.currentTimeMillis() - probeStartMs) < HTTP_PROBE_GRACE_MS) {
                                coroutineContext.ensureActive()
                                val discovered = discoverListeningPorts(pid)
                                if (discovered.isNotEmpty()) {
                                    osPort = discovered.min()
                                    discoveredPorts.addAll(discovered)
                                } else {
                                    delay(OS_PROBE_POLL_MS)
                                }
                            }
                        }
                        if (osPort == null) {
                            // No port observed via OS commands — skip HTTP probe and fall through
                            // to log-pattern + idle-stdout strategies. Per "no info > wrong info"
                            // principle, we do NOT fall back to static config parsing.
                            null
                        } else {
                            val paths = SpringBootConfigParser.parseActuatorPaths(settings, project)
                            "http://localhost:$osPort${paths.actuatorBasePath}${paths.healthPath}"
                        }
                    }

                    if (probeUrl != null) {
                        // Race HTTP probe against log-pattern (first wins).
                        readyResult = withTimeoutOrNull(readyTimeoutSec * 1000L) {
                            coroutineScope {
                                val httpJob = async {
                                    val probe = HttpReadinessProbe()
                                    val result = probe.poll(
                                        url = probeUrl,
                                        timeoutMs = readyTimeoutSec * 1000L,
                                        gracePeriodMs = HTTP_PROBE_GRACE_MS,
                                    )
                                    result is HttpReadinessProbe.ProbeResult.Success
                                }
                                val logJob = async {
                                    while (!readinessAchieved.get() && !processStartFailed.get()) {
                                        coroutineContext.ensureActive()
                                        delay(LOG_PATTERN_POLL_MS)
                                    }
                                    readinessAchieved.get()
                                }

                                var result = false
                                while (!httpJob.isCompleted && !logJob.isCompleted && !processStartFailed.get()) {
                                    coroutineContext.ensureActive()
                                    delay(READY_RACE_POLL_MS)
                                }
                                if (httpJob.isCompleted && httpJob.await()) {
                                    httpProbeSignal.set("HTTP probe 200 OK: $probeUrl")
                                    result = true
                                } else if (logJob.isCompleted && logJob.await()) {
                                    result = true
                                } else if (!processStartFailed.get()) {
                                    val httpOk = httpJob.await()
                                    val logOk = logJob.await()
                                    result = httpOk || logOk
                                    if (httpOk) httpProbeSignal.set("HTTP probe 200 OK: $probeUrl")
                                }
                                httpJob.cancel()
                                logJob.cancel()
                                result
                            }
                        }
                    } else {
                        readyResult = withTimeoutOrNull(readyTimeoutSec * 1000L) {
                            while (!readinessAchieved.get() && !processStartFailed.get()) {
                                coroutineContext.ensureActive()
                                delay(LOG_PATTERN_POLL_MS)
                            }
                            readinessAchieved.get()
                        }
                    }
                } else {
                    readyResult = withTimeoutOrNull(readyTimeoutSec * 1000L) {
                        when {
                            readinessStrategy == "process_started" -> true
                            readinessStrategy == "explicit_pattern" || readinessStrategy == "log_pattern" || isSpring -> {
                                while (!readinessAchieved.get() && !processStartFailed.get()) {
                                    coroutineContext.ensureActive()
                                    delay(LOG_PATTERN_POLL_MS)
                                }
                                readinessAchieved.get()
                            }
                            else -> {
                                var lastLogLength = logBuffer.length
                                delay(IDLE_STDOUT_INITIAL_WAIT_MS)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    if (processStartFailed.get()) return@withTimeoutOrNull false
                                    val currentLength = logBuffer.length
                                    if (currentLength == lastLogLength) break
                                    lastLogLength = currentLength
                                    delay(IDLE_STDOUT_POLL_MS)
                                }
                                !processStartFailed.get()
                            }
                        }
                    }
                }

                if (processStartFailed.get()) {
                    val baseMsg = processStartFailedMsg.get() ?: "EXITED_BEFORE_READY"
                    // Surface the process tail output — the crash cause (e.g. "connection
                    // refused", "failed to bind port") is almost always in the last lines.
                    // Mirrors the TIMEOUT_WAITING_FOR_READY branch below so the LLM can
                    // diagnose without a second get_run_output call.
                    val msg = withTailOutput(baseMsg, logBuffer.toString(), tailLines)
                    return ToolResult(msg, "Process failed before ready", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                }

                if (readyResult != true) {
                    val lastLines = logBuffer.toString().lines()
                        .takeLast(tailLines)
                        .joinToString("\n")
                    return ToolResult(
                        "TIMEOUT_WAITING_FOR_READY: Application '${config.name}' did not reach ready state within " +
                            "${readyTimeoutSec}s.\nLast output:\n$lastLines",
                        "TIMEOUT_WAITING_FOR_READY", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }

                // Port discovery: lsof/ss/netstat after readiness
                if (discoverPorts) {
                    val pid = processHandlerRef.get()?.let { extractPid(it) }
                    if (pid != null) {
                        val osPorts = discoverListeningPorts(pid)
                        discoveredPorts.addAll(osPorts)
                    }
                }

                return null
            }

            // Debug mode path: wait for XDebugSession
            if (mode == "debug") {
                val debugSessionName = AtomicReference<String?>(null)
                val debugPid = AtomicReference<Long?>(null)
                val debugPausedAt = AtomicReference<String?>(null)
                val debugSessionReady = AtomicBoolean(false)

                // Subscribe to XDebuggerManager.TOPIC for debug session detection
                val debugConnection = project.messageBus.connect()
                invocation.subscribeTopic(debugConnection)
                debugConnection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                    override fun processStarted(debugProcess: XDebugProcess) {
                        val session = debugProcess.session
                        if (!session.isStopped) {
                            debugSessionName.set(session.sessionName)
                            debugPid.set(extractPid(debugProcess.processHandler ?: return))
                            debugSessionReady.set(true)
                            if (waitForPause && session.isPaused) {
                                val pos = session.currentPosition
                                if (pos != null) {
                                    debugPausedAt.set("${pos.file.path}:${pos.line + 1}")
                                }
                            }
                        }
                    }
                })

                val debugReady = withTimeoutOrNull(readyTimeoutSec * 1000L) {
                    while (!debugSessionReady.get() && !processStartFailed.get()) {
                        coroutineContext.ensureActive()
                        delay(100)
                    }
                    debugSessionReady.get()
                }

                if (processStartFailed.get()) {
                    val msg = processStartFailedMsg.get() ?: "PROCESS_START_FAILED: Debug process failed to start."
                    return ToolResult(msg, "Debug launch failed", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                }

                if (debugReady != true) {
                    return ToolResult(
                        "TIMEOUT_WAITING_FOR_READY: Debug session was not established within ${readyTimeoutSec}s. " +
                            "Check run configuration and build errors.",
                        "TIMEOUT_WAITING_FOR_READY", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }

                // Debug-session attach only confirms the JVM booted with the debug agent, not that
                // the application is serving traffic. Run the same readiness pipeline used by Run
                // mode (HTTP probe / Spring log banner / idle stdout) so the tool returns when the
                // service is actually up.
                val debugHttpProbeSignal = AtomicReference<String?>(null)
                if (waitForReady) {
                    val readinessError = awaitServiceReadiness(debugHttpProbeSignal)
                    if (readinessError != null) return readinessError
                }

                // S5 fix: replace poll-on-isPaused with XDebugSessionListener.sessionPaused()
                // via DebugInvocation.attachListener (2-arg addSessionListener form — auto-removes
                // via Disposer). This matches the pattern used by AgentDebugController.registerSession.
                if (waitForPause) {
                    val sessions = XDebuggerManager.getInstance(project).debugSessions
                    val sessionName = debugSessionName.get()
                    val session = sessions.firstOrNull { it.sessionName == sessionName }
                    if (session != null && !session.isPaused && !session.isStopped) {
                        val debugInvocation = project.service<AgentService>().newDebugInvocation(
                            "run-config-wait-pause-${config.name}"
                        )
                        try {
                            withTimeoutOrNull(readyTimeoutSec * 1000L) {
                                suspendCancellableCoroutine { cont ->
                                    debugInvocation.attachListener(session, object : XDebugSessionListener {
                                        override fun sessionPaused() {
                                            if (cont.isActive) cont.resume(Unit)
                                        }
                                        override fun sessionStopped() {
                                            if (cont.isActive) cont.resume(Unit)
                                        }
                                    })
                                    cont.invokeOnCancellation { Disposer.dispose(debugInvocation) }
                                }
                            }
                        } finally {
                            Disposer.dispose(debugInvocation)
                        }
                    }
                    if (session != null && session.isPaused) {
                        val pos = session.currentPosition
                        if (pos != null) {
                            debugPausedAt.set("${pos.file.path}:${pos.line + 1}")
                        }
                    }
                }

                // Detach: null refs before dispose so process survives
                invocation.descriptorRef.set(null)
                invocation.processHandlerRef.set(null)

                val sb = StringBuilder()
                sb.appendLine("DEBUG")
                sb.appendLine("Config: ${config.name}")
                sb.appendLine("Session: ${debugSessionName.get() ?: "unknown"}")
                debugPid.get()?.let { sb.appendLine("PID: $it") }
                if (discoveredPorts.isNotEmpty()) {
                    // Label disambiguates port vs PID — feedback.md §4 reported confusion
                    // when an ephemeral port (32636) was read as a process id. Adding "TCP"
                    // and the unit ("port(s)") makes the line self-describing.
                    sb.appendLine("Listening TCP port(s): ${discoveredPorts.toSortedSet()}")
                }
                val debugReadySignal = debugHttpProbeSignal.get() ?: readySignal.get()
                debugReadySignal?.let { sb.appendLine("Ready signal: $it") }
                if (waitForPause) {
                    val paused = debugPausedAt.get()
                    if (paused != null) sb.appendLine("paused_at: $paused")
                    else sb.appendLine("Status: running (no breakpoint hit within ${readyTimeoutSec}s)")
                } else if (waitForReady) {
                    sb.appendLine("Status: DEBUG session active — service READY")
                } else {
                    sb.appendLine("Status: DEBUG session active")
                }
                val content = sb.toString().trimEnd()
                val spilled = spillOrFormat(content, project)
                return ToolResult(
                    content = spilled.preview,
                    summary = "DEBUG: ${debugSessionName.get() ?: config.name}",
                    tokenEstimate = spilled.preview.length / 4,
                    spillPath = spilled.spilledToFile
                )
            }

            // Run mode: wait_for_ready path
            if (waitForReady) {
                val httpProbeSignal = AtomicReference<String?>(null)
                val readinessError = awaitServiceReadiness(httpProbeSignal)
                if (readinessError != null) return readinessError

                // Detach: null refs so dispose() doesn't kill the process
                invocation.descriptorRef.set(null)
                invocation.processHandlerRef.set(null)

                val pid = processHandlerRef.get()?.let { extractPid(it) }
                val sb = StringBuilder()
                sb.appendLine("READY")
                sb.appendLine("Config: ${config.name}")
                sb.appendLine("Type: $configTypeId")
                sb.appendLine("Mode: run")
                pid?.let { sb.appendLine("PID: $it") }
                if (discoveredPorts.isNotEmpty()) {
                    // Label disambiguates port vs PID — feedback.md §4 reported confusion
                    // when an ephemeral port (32636) was read as a process id. Adding "TCP"
                    // and the unit ("port(s)") makes the line self-describing.
                    sb.appendLine("Listening TCP port(s): ${discoveredPorts.toSortedSet()}")
                }
                val effectiveSignal = httpProbeSignal.get() ?: readySignal.get()
                effectiveSignal?.let { sb.appendLine("Ready signal: $it") }
                sb.appendLine("Status: READY to serve traffic")
                preLaunchNote?.let { sb.appendLine(it) }
                val content = sb.toString().trimEnd()
                val spilled = spillOrFormat(content, project)
                return ToolResult(
                    content = spilled.preview,
                    summary = "READY: ${config.name}${if (discoveredPorts.isNotEmpty()) " on ports $discoveredPorts" else ""}",
                    tokenEstimate = spilled.preview.length / 4,
                    spillPath = spilled.spilledToFile
                )
            }

            // wait_for_finish path
            if (waitForFinish) {
                val handler = processHandlerRef.get()
                val finishResult: Boolean? = if (handler != null) {
                    withTimeoutOrNull(timeoutSec * 1000L) {
                        awaitProcessTermination(handler, timeoutSec * 1000L, project)
                    }
                } else null

                if (finishResult == null || finishResult == false) {
                    val lastLines = logBuffer.toString().lines()
                        .takeLast(tailLines)
                        .joinToString("\n")
                    return ToolResult(
                        "TIMEOUT_WAITING_FOR_PROCESS: Process '${config.name}' is still running after ${timeoutSec}s. " +
                            "Last output:\n$lastLines",
                        "TIMEOUT_WAITING_FOR_PROCESS: still running after timeout",
                        ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }

                val lastLines = logBuffer.toString().lines()
                    .takeLast(tailLines)
                    .joinToString("\n")
                val content = "Process '${config.name}' finished.\nExit code: 0\nOutput tail:\n$lastLines".trimEnd()
                val spilled = spillOrFormat(content, project)
                return ToolResult(
                    content = spilled.preview,
                    summary = "Process ${config.name} finished (exit 0)",
                    tokenEstimate = spilled.preview.length / 4,
                    spillPath = spilled.spilledToFile
                )
            }

            // Fire-and-forget: launched, not waiting
            if (processStartFailed.get()) {
                val msg = processStartFailedMsg.get() ?: "PROCESS_START_FAILED: Process did not start."
                return ToolResult(msg, "Launch failed", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }
            val ffBase = "Config '${config.name}' launched in run mode (fire-and-forget)."
            val content = if (preLaunchNote != null) "$ffBase\n$preLaunchNote" else ffBase
            return ToolResult(content, "Launched ${config.name}", content.length / 4)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult(
                "UNEXPECTED_ERROR: ${e.javaClass.simpleName}: ${e.message}",
                "UNEXPECTED_ERROR", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } finally {
            Disposer.dispose(invocation)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Shared stop helper — used by stop_run_config and run_config (idempotent)
    // ══════════════════════════════════════════════════════════════════════

    private sealed class StopOutcome {
        object NotRunning : StopOutcome()
        data class StoppedGracefully(val pids: List<Long>) : StopOutcome()
        data class StoppedForced(val pids: List<Long>) : StopOutcome()
        data class FailedToStop(val pids: List<Long>, val message: String) : StopOutcome()
    }

    /**
     * Stop all running processes matching [configName] (exact display-name match against
     * RunContentDescriptor.displayName). Performs graceful destroy → poll → force destroy → poll.
     * Handles coroutine cancellation by re-throwing [CancellationException].
     */
    private suspend fun stopProcessesForConfig(
        project: Project,
        configName: String,
        gracefulMs: Long = 10_000L,
        forceMs: Long = 5_000L,
    ): StopOutcome {
        val handlers = ExecutionManager.getInstance(project).getRunningProcesses()
            .filter { h ->
                val displayName = try {
                    val mgr = RunContentManager.getInstance(project)
                    mgr.allDescriptors.firstOrNull { d -> d.processHandler === h }?.displayName
                } catch (_: Exception) { null }
                displayName == configName || h.toString().contains(configName, ignoreCase = true)
            }
            .filter { !it.isProcessTerminated }

        if (handlers.isEmpty()) return StopOutcome.NotRunning

        val pids = handlers.mapNotNull { extractPid(it) }

        // Graceful destroy
        for (handler in handlers) {
            handler.destroyProcess()
        }

        // Poll until graceful timeout
        val allGraceful = withTimeoutOrNull(gracefulMs) {
            var remaining = handlers.filter { !it.isProcessTerminated }
            while (remaining.isNotEmpty()) {
                coroutineContext.ensureActive()
                delay(STOP_POLL_INTERVAL_MS)
                remaining = remaining.filter { !it.isProcessTerminated }
            }
            true
        } ?: false

        if (allGraceful) return StopOutcome.StoppedGracefully(pids)

        // Force kill
        for (handler in handlers.filter { !it.isProcessTerminated }) {
            try {
                @Suppress("DEPRECATION")
                handler.destroyProcess()
            } catch (_: Exception) {}
        }

        val allForced = withTimeoutOrNull(forceMs) {
            var remaining = handlers.filter { !it.isProcessTerminated }
            while (remaining.isNotEmpty()) {
                coroutineContext.ensureActive()
                delay(STOP_POLL_INTERVAL_MS)
                remaining = remaining.filter { !it.isProcessTerminated }
            }
            true
        } ?: false

        val stillAlive = handlers.filter { !it.isProcessTerminated }
        return if (allForced || stillAlive.isEmpty()) {
            StopOutcome.StoppedForced(pids)
        } else {
            StopOutcome.FailedToStop(
                pids,
                "Process '${configName}' could not be force-killed. ${stillAlive.size} process(es) still running."
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: stop_run_config — graceful (+ force) shutdown
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Stop a running process by config name. Graceful SIGTERM then optional force-kill.
     * Returns STOPPED with process count, or STOP_FAILED / PROCESS_NOT_RUNNING.
     * Delegates to [stopProcessesForConfig] for the stop state machine.
     */
    private suspend fun executeStopRunConfig(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Missing required parameter 'config_name'.",
                "Missing config_name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        val gracefulTimeoutSec = (params["graceful_timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 300)
        val forceOnTimeout = params["force_on_timeout"]?.jsonPrimitive?.booleanOrNull ?: true

        val handlers = ExecutionManager.getInstance(project).getRunningProcesses()
            .filter { h ->
                // Match by descriptor display name if available
                val displayName = try {
                    val mgr = RunContentManager.getInstance(project)
                    mgr.allDescriptors.firstOrNull { d -> d.processHandler === h }?.displayName
                } catch (_: Exception) { null }
                displayName?.contains(configName, ignoreCase = true) == true ||
                    h.toString().contains(configName, ignoreCase = true)
            }
            .filter { !it.isProcessTerminated }

        if (handlers.isEmpty()) {
            return ToolResult(
                "PROCESS_NOT_RUNNING: No running process matching '$configName'.",
                "PROCESS_NOT_RUNNING", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val invocation = project.service<AgentService>().newRunInvocation("stop-$configName")
        try {
            // Graceful destroy
            for (handler in handlers) {
                handler.destroyProcess()
            }

            // Poll until terminated or timeout
            val gracefulMs = gracefulTimeoutSec * 1000L
            val allTerminated = withTimeoutOrNull(gracefulMs) {
                var remaining = handlers.filter { !it.isProcessTerminated }
                while (remaining.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    delay(STOP_POLL_INTERVAL_MS)
                    remaining = remaining.filter { !it.isProcessTerminated }
                }
                true
            } ?: false

            if (!allTerminated) {
                if (!forceOnTimeout) {
                    return ToolResult(
                        "STOP_FAILED: Process '${configName}' is still running after ${gracefulTimeoutSec}s " +
                            "(force_on_timeout=false).",
                        "STOP_FAILED", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }

                // Force kill
                for (handler in handlers.filter { !it.isProcessTerminated }) {
                    try {
                        @Suppress("DEPRECATION")
                        handler.destroyProcess()
                    } catch (_: Exception) {}
                }

                withTimeoutOrNull(FORCE_KILL_TIMEOUT_MS) {
                    var remaining = handlers.filter { !it.isProcessTerminated }
                    while (remaining.isNotEmpty()) {
                        coroutineContext.ensureActive()
                        delay(STOP_POLL_INTERVAL_MS)
                        remaining = remaining.filter { !it.isProcessTerminated }
                    }
                    true
                }

                val stillAlive = handlers.filter { !it.isProcessTerminated }
                if (stillAlive.isNotEmpty()) {
                    return ToolResult(
                        "STOP_FAILED: Process '${configName}' could not be force-killed after ${gracefulTimeoutSec}s. " +
                            "${stillAlive.size} process(es) still running.",
                        "STOP_FAILED", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }
            }

            val count = handlers.size
            val content = "Stopped $count process(es) matching '$configName'."
            return ToolResult(content, "Stopped $count process(es)", content.length / 4)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult(
                "UNEXPECTED_ERROR stopping '$configName': ${e.message}",
                "UNEXPECTED_ERROR", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } finally {
            Disposer.dispose(invocation)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Port discovery helper
    // ══════════════════════════════════════════════════════════════════════

    // M2 fix: all Runtime.exec() + readLines() calls are blocking I/O — must run on
    // Dispatchers.IO. Function made suspend; callers are already in a suspend context.
    // S2 fix: on Linux, fall back to `ss -tlnp` when lsof returns nothing (Alpine-based
    // containers lack lsof). macOS does not have `ss`, so the fallback is Linux-only.
    private suspend fun discoverListeningPorts(pid: Long): Set<Int> = withContext(Dispatchers.IO) {
        val ports = mutableSetOf<Int>()
        val os = System.getProperty("os.name", "").lowercase()
        try {
            when {
                os.contains("mac") -> {
                    // macOS: lsof only (ss unavailable)
                    val lines = Runtime.getRuntime()
                        .exec(arrayOf("lsof", "-iTCP", "-sTCP:LISTEN", "-P", "-n", "-p", pid.toString()))
                        .inputStream.bufferedReader().readLines()
                    parseLsofPorts(lines, ports)
                }
                os.contains("linux") -> {
                    // Linux: try lsof first; fall back to ss -tlnp when lsof is absent (Alpine)
                    val lsofLines = try {
                        Runtime.getRuntime()
                            .exec(arrayOf("lsof", "-iTCP", "-sTCP:LISTEN", "-P", "-n", "-p", pid.toString()))
                            .inputStream.bufferedReader().readLines()
                    } catch (_: Exception) { emptyList() }

                    if (lsofLines.size > 1) {
                        parseLsofPorts(lsofLines, ports)
                    } else {
                        // S2: lsof absent or returned no rows — try ss -tlnp and grep by pid.
                        // ss output format: LISTEN 0 128 *:8080 *:*  users:(("java",pid=12345,fd=7))
                        // We filter lines that contain "pid=<pid>" so we only pick up ports
                        // owned by the target process.
                        val ssLines = try {
                            Runtime.getRuntime()
                                .exec(arrayOf("ss", "-tlnp"))
                                .inputStream.bufferedReader().readLines()
                        } catch (_: Exception) { emptyList() }
                        for (line in ssLines) {
                            if (!line.contains("pid=$pid")) continue
                            // Column 4 (0-based index 3) holds the local address: *:PORT or ADDR:PORT
                            val cols = line.trim().split(Regex("""\s+"""))
                            val localAddr = cols.getOrNull(3) ?: continue
                            val portStr = localAddr.substringAfterLast(':')
                            val port = portStr.toIntOrNull() ?: continue
                            if (port in 1..65535) ports.add(port)
                        }
                    }
                }
                os.contains("win") -> {
                    val lines = Runtime.getRuntime()
                        .exec(arrayOf("cmd", "/c", "netstat -ano | findstr LISTENING | findstr $pid"))
                        .inputStream.bufferedReader().readLines()
                    for (line in lines) {
                        val cols = line.trim().split(Regex("""\s+"""))
                        // netstat output columns: Protocol LocalAddr ForeignAddr State PID
                        // lastOrNull() was returning the PID (last column) instead of the port.
                        // Column 1 (LocalAddr) contains the bound address: "0.0.0.0:8080".
                        val localAddr = cols.getOrNull(1) ?: continue
                        val portStr = localAddr.substringAfterLast(':')
                        val port = portStr.toIntOrNull() ?: continue
                        if (port in 1..65535) ports.add(port)
                    }
                }
            }
        } catch (_: Exception) {}
        ports
    }

    /**
     * Parse lsof TCP-LISTEN output (skip header row, extract port from name column).
     *
     * lsof output: `COMMAND PID USER FD TYPE DEVICE SIZE NODE NAME`
     * where NAME is `TCP *:8080 (LISTEN)` or `TCP 127.0.0.1:8080 (LISTEN)`.
     * Using lastOrNull() is wrong — it returns `"(LISTEN)"`, not the port token.
     * Instead, find the first whitespace token that contains a colon (skipping the
     * `(LISTEN)` state token which starts with `(`), then extract the port after the
     * last colon.  Tokens like PID (`1234`) have no colon so they are skipped safely.
     */
    private fun parseLsofPorts(lines: List<String>, ports: MutableSet<Int>) {
        for (line in lines.drop(1)) {
            val tokens = line.trim().split(Regex("""\s+"""))
            for (token in tokens) {
                if (token.startsWith("(")) continue   // skip "(LISTEN)" state indicator
                if (!token.contains(':')) continue     // port tokens always have a colon
                val portStr = token.substringAfterLast(':')
                val port = portStr.toIntOrNull() ?: continue
                if (port in 1..65535) {
                    ports.add(port)
                    break   // one port entry per lsof line
                }
            }
        }
    }

    internal data class DescriptorSelection(
        val descriptor: RunContentDescriptor,
        val others: List<RunContentDescriptor>,
        val pickedLive: Boolean,
    )

    /**
     * Resolve a run/debug descriptor by name, preferring a live one over a terminated one.
     *
     * `RunContentManager.allDescriptors` retains closed tabs (terminated processes stay as
     * inert descriptors until the user closes the tab). A plain `find` returns the first
     * match in registration order, which is almost always an older terminated run — not the
     * live debug session the caller actually wants. This helper:
     *   1. collects all descriptors whose display name contains [configName] (case-insensitive);
     *   2. prefers the most recently registered *live* descriptor (not terminated / terminating);
     *   3. falls back to the most recently registered descriptor if none are live.
     *
     * The "others" list in the result lets callers surface disambiguation context to the LLM.
     */
    internal fun selectDescriptorByName(
        allDescriptors: List<RunContentDescriptor>,
        configName: String,
    ): DescriptorSelection? {
        val matches = allDescriptors.filter { desc ->
            desc.displayName?.contains(configName, ignoreCase = true) == true
        }
        if (matches.isEmpty()) return null

        val live = matches.lastOrNull { desc ->
            desc.processHandler?.let { !it.isProcessTerminated && !it.isProcessTerminating } == true
        }
        val chosen = live ?: matches.last()
        return DescriptorSelection(
            descriptor = chosen,
            others = matches.filter { it !== chosen },
            pickedLive = live != null,
        )
    }

    private fun describeProcessStatus(descriptor: RunContentDescriptor): String = when {
        descriptor.processHandler?.isProcessTerminated == true -> "Terminated"
        descriptor.processHandler?.isProcessTerminating == true -> "Terminating"
        else -> "Running"
    }

    /**
     * Best-effort detection of whether the descriptor was launched in Debug mode.
     *
     * A descriptor doesn't carry its executor directly; instead we check whether any active
     * `XDebugSession` has the same `RunContentDescriptor` reference. If so, it's Debug;
     * otherwise it's treated as Run. (Coverage uses the Run executor, so it'll show as "Run".)
     */
    private fun describeLaunchMode(descriptor: RunContentDescriptor, project: Project): String {
        return try {
            val isDebug = XDebuggerManager.getInstance(project).debugSessions.any { session ->
                session.runContentDescriptor === descriptor
            }
            if (isDebug) "Debug" else "Run"
        } catch (_: Exception) {
            "Run"
        }
    }

    companion object {
        /**
         * Append the process tail output to a launch-/readiness-failure message so the
         * crash cause (e.g. "connection refused", "failed to bind port") rides along with
         * the error category. Mirrors the TIMEOUT_WAITING_FOR_READY "Last output:" framing.
         * Pure + internal for unit testing.
         */
        internal fun withTailOutput(baseMsg: String, logOutput: String, tailLines: Int): String {
            val tail = logOutput.lines().takeLast(tailLines).joinToString("\n").trim()
            return if (tail.isEmpty()) baseMsg else "$baseMsg\nLast output:\n$tail"
        }

        // get_run_output constants
        private const val RUN_OUTPUT_DEFAULT_LINES = 200
        private const val RUN_OUTPUT_MAX_LINES = 1000
        // Console unwrap depth
        private const val MAX_UNWRAP_DEPTH = 5

        // get_test_results constants
        private const val MAX_PROCESS_WAIT_SECONDS = 600
        private const val TEST_TREE_FINALIZE_TIMEOUT_MS = 10_000L
        private const val PROGRESS_INTERVAL_MS = 10_000L

        // run_config readiness detection constants
        private const val IDLE_STDOUT_INITIAL_WAIT_MS = 300L
        private const val IDLE_STDOUT_POLL_MS = 200L

        // http_probe constants
        private const val HTTP_PROBE_GRACE_MS = 5000L
        private const val LOG_PATTERN_POLL_MS = 50L
        private const val READY_RACE_POLL_MS = 50L

        // OS port discovery poll interval when waiting for port to bind before HTTP probe
        private const val OS_PROBE_POLL_MS = 200L

        // stop_run_config / stopProcessesForConfig constants
        private const val STOP_POLL_INTERVAL_MS = 500L
        private const val FORCE_KILL_TIMEOUT_MS = 5_000L
    }
}
