# `runtime_exec` — extended notes

## Why this is a meta-tool

Five distinct verbs share one schema entry: three observation actions
(`get_running_processes`, `get_run_output`, `get_test_results`) and two launch
actions (`run_config`, `stop_run_config`). They are bundled because every action
operates on the same anchor point — IntelliJ's `RunContentManager` and
`ExecutionManager` — and shares the same descriptor-resolution logic
(`selectDescriptorByName`, exact-then-substring match against
`RunContentDescriptor.displayName`). Splitting them into five sibling tools
would duplicate the descriptor-resolution code in five places and add ~750
schema tokens *every iteration* even when the LLM is reading code rather than
running it.

The action-enum keeps the cost flat and makes the LLM see "process control" as
one capability surface — the natural mental model.

## The launch / observe split is not arbitrary

IntelliJ has two production APIs that this tool federates:

| Surface | What it owns | Actions |
|---|---|---|
| `RunContentManager` / `ExecutionConsole` | Descriptors, console buffers, descriptor lifecycle | `get_running_processes`, `get_run_output`, `get_test_results` |
| `ProgramRunnerUtil` / `ExecutionEnvironment` / `ProgramRunner.Callback` | Launch, before-run tasks, executor selection | `run_config`, `stop_run_config` |

The observation actions are technically read-only against IntelliJ state, but
the tool aggregates side-effecting actions, so the blast-radius classification
is `PROCESS_SPAWN`. (The Compare Tools view filters by the broadest tag, by
design — splitting the chip per-action would lie about the worst case.)

## `run_config` is the heaviest action in the agent

It is a five-stage state machine that absolutely must be understood in pieces.
The implementation runs ~700 lines and is the single most error-prone code path
in `:agent`. The stages, in order, are:

### Stage 1 — Pre-launch validation

1. Resolve `config_name` against `RunManager.allSettings` — exact name first,
   then unique substring. Multiple substring matches return `AMBIGUOUS_MATCH`.
2. Reject by config-type guardrails:
   - `Remote` configs → tell the LLM to use `debug_breakpoints.attach_to_process`.
   - `JUnit` / `TestNG` configs → tell the LLM to use `java_runtime_exec.run_tests`.
3. `mode=coverage` → redirect to `coverage.run_with_coverage`.
4. `mode=profile` → not yet implemented.
5. Wait for smart mode (up to 60s). If indexing isn't done, return `DUMB_MODE`
   rather than launching against a half-built index.
6. Run `config.checkConfiguration()` inside a `smartReadAction`. Errors block
   (`INVALID_CONFIGURATION`); warnings pass.

### Stage 2 — Idempotent stop-then-launch

If an instance with the resolved `settings.name` is already running, stop it
*before* launching a new one. The state machine is implemented in the private
`stopProcessesForConfig` helper:

```
NotRunning → proceed silently
StoppedGracefully(pids) → append note, proceed
StoppedForced(pids) → append note, proceed
FailedToStop(pids, msg) → return STOP_FAILED, do NOT launch
```

This is the contract the LLM relies on: calling `run_config` twice in a row is
safe — the first instance is stopped before the second launches. Without this,
the LLM accumulates zombie processes when it iterates on a buggy launch.

`STOP_FAILED` is the one error category that surfaces from the idempotent stop
path. When the LLM sees it, the right move is to escalate to the user, not to
retry — a force-kill that didn't kill is almost always a kernel- or OS-level
problem.

### Stage 3 — Process launch via `ProgramRunner.Callback`

`ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)` posts
to the EDT internally. The tool subscribes to two message-bus topics *before*
launching:

- `CompilerTopics.COMPILATION_STATUS` — captures `CompileContext` if the
  before-run Build task fails. When `processNotStarted` later fires, the
  presence of this captured context discriminates `BEFORE_RUN_FAILED` (with
  per-file compile errors via `formatCompileErrors`) from
  `PROCESS_START_FAILED` (generic launch failure with no compiler diagnostics).
- `ExecutionManager.EXECUTION_TOPIC` — `processNotStarted` and `processStarted`
  ride this. The discriminator above runs in `processNotStarted`.

When `processStarted` fires inside `ProgramRunner.Callback`, the descriptor
and process handler are stored in atomic refs and parented to the per-launch
`RunInvocation` for cleanup-on-all-paths.

### Stage 4 — Readiness state machine

The readiness pipeline is the part of `run_config` that most often surprises
the LLM. There are five strategies, auto-selected unless the LLM overrides:

| Strategy | Auto-selected when | Mechanism |
|---|---|---|
| `http_probe` | Spring Boot config detected, OR `ready_url` provided | OS-discovered port + actuator paths → HTTP GET. Backoff: 200ms → 1.5× → 2000ms cap. 5s grace. |
| `log_pattern` | Spring Boot but no http_probe | Tomcat/Netty/Jetty/Undertow startup banner regex. Concurrent fallback when `http_probe` is active. |
| `idle_stdout` | `auto` for non-Spring/non-http apps | No new stdout for 2000ms after process start, with process not terminated. |
| `explicit_pattern` | LLM passes `ready_pattern` | User-supplied regex against stdout. |
| `process_started` | LLM passes `process_started` | OS process spawned — return immediately, no readiness wait. |

When `http_probe` is active and `ready_url` is *not* provided, the tool waits
up to `HTTP_PROBE_GRACE_MS` (5s) for OS port discovery to find the bound port,
then composes `http://localhost:<discovered-port><actuator-base><health-path>`.
If OS discovery doesn't observe a bound port, the HTTP probe is *skipped* and
log-pattern readiness is used as fallback. Static port parsing is intentionally
not used as a fallback — see "Port discovery contract" below.

When the strategy is `auto` for non-Spring apps, the idle-stdout heuristic is
the only signal. This works well for CLI tools and batch jobs but is fragile
for long-bootstrapping apps that pause output for >2000ms during startup. In
those cases, the LLM should explicitly set `readiness_strategy=explicit_pattern`
with a log line that uniquely signals readiness.

### Stage 5 — Detach-on-ready

When `wait_for_ready=true` succeeds, the tool clears `descriptorRef` and
`processHandlerRef` on the `RunInvocation` *before* `Disposer.dispose(invocation)`
runs. This breaks the cleanup chain — the descriptor and process handler are no
longer parented to the agent session, so when the user starts a new chat (which
calls `SessionDisposableHolder.resetSession()`), the running app is *not*
cascade-stopped.

Ownership transfers cleanly to `ExecutionManagerImpl` and the IDE's Run tool
window. The user can stop the app via the IDE Stop button or the LLM can stop
it via `runtime_exec(action=stop_run_config)`, but a session reset will not.

This is the key contract that makes `run_config` useful for "launch the server,
then have the agent test it" workflows. Without detach-on-ready, every server
launched by the agent dies the moment the conversation ends.

## Port discovery contract: OS-only

The single most consequential design decision in this tool is that port
discovery uses *only* OS commands by PID:

| OS | Command |
|---|---|
| macOS | `lsof -iTCP -sTCP:LISTEN -P -n -p <pid>` |
| Linux | `lsof` first; fallback to `ss -tlnp \| grep "pid=<pid>"` (Alpine) |
| Windows | `netstat -ano \| findstr LISTENING \| findstr <pid>` |

Static config parsing — the obvious-seeming alternative — is *not* used as a
fallback. The tool used to retain `serverPort` and `managementPort` fields on
`SpringBootConfigParser`; both were removed on 2026-04-21 because static
parsing cannot see:

- VM options like `-Dserver.port=9090`
- Environment variables like `SERVER_PORT=9090`
- Active profiles (`application-dev.yml` overrides)
- Programmatic `setDefaultProperties` in code
- Spring Cloud Config / Consul / etc.
- Random-port mode (`server.port=0`)

The principle is "no info > wrong info". When OS port discovery returns nothing
(a tool isn't installed, a permission-denied, etc.), the result simply omits
the port. The LLM gets a `READY` payload without `Listening ports:` and can
call `get_run_output` to fish a port out of the logs if it needs one.

The Spring log banners (`Tomcat started on port(s): 8080`) are kept *only* as a
readiness signal — the matched port number is intentionally discarded. This is
the least obvious part of the design and the LLM occasionally suggests "fixes"
that re-introduce log-scraped ports, which would silently break the run-config
override scenarios above.

## Error category taxonomy

`run_config` defines 17 distinct error categories. The first token of every
error message is the category, on purpose: the LLM should branch on the prefix,
not retry blindly.

| Category | Cause | Recovery |
|---|---|---|
| `CONFIGURATION_NOT_FOUND` | No config matches `config_name` | List available configs from the error message; pick exact name. |
| `AMBIGUOUS_MATCH` | Substring matches multiple configs | Use exact name from the error message. |
| `NO_RUNNER_REGISTERED` | No `ProgramRunner` for executor + config-type | Plugin missing — escalate to user. |
| `INVALID_CONFIGURATION` | `checkConfiguration()` errored, or remote/test config rejected | Read message; use the suggested alternative tool, or fix config. |
| `DUMB_MODE` | Indexing didn't complete in 60s | Wait, then retry. Don't retry immediately. |
| `BEFORE_RUN_FAILED` | Build task before launch failed | Per-file compile errors are inline; fix them. |
| `EXECUTION_EXCEPTION` | Generic launch exception | Read the message — usually plugin or config issue. |
| `PROCESS_START_FAILED` | `processNotStarted` with no compile context | OS process failed; check JVM/runtime arguments. |
| `TIMEOUT_WAITING_FOR_READY` | Readiness signal not received in `ready_timeout_seconds` | Inspect `Last output:` block; raise the timeout, change strategy, or fix the app. |
| `TIMEOUT_WAITING_FOR_PROCESS` | `wait_for_finish=true` and process didn't exit in `timeout_seconds` | Process is hanging; raise timeout or kill via `stop_run_config`. |
| `EXITED_BEFORE_READY` | Process exited before readiness | Inspect tail output for crash; do not retry blindly. |
| `READINESS_DETECTION_FAILED` | `http_probe` requested without Spring config or `ready_url` | Provide `ready_url`, or pick another strategy. |
| `PORT_DISCOVERY_FAILED` | OS commands all failed | Result omits ports; LLM should call `get_run_output` and parse logs. |
| `CANCELLED_BY_USER` | Coroutine cancellation | User cancelled the agent run. |
| `UNEXPECTED_ERROR` | Anything not categorized above | Read class name + message. |
| `PROCESS_NOT_RUNNING` | `stop_run_config` called on a config that isn't running | Treat as no-op; possibly check state first via `get_running_processes`. |
| `STOP_FAILED` | Graceful + force kill both failed | Kernel-level problem; escalate to user. Also surfaces from `run_config` idempotent stop. |

## `mode=debug` is the canonical debug launch

There is no separate `start_debug_session` action — that was deleted on
2026-04-26. The canonical path is `runtime_exec(action=run_config, mode=debug,
wait_for_pause=bool)`. Same readiness pipeline, same error taxonomy, same
detach semantics. The XDebugSession lifecycle is observed via
`XDebuggerManager.TOPIC` instead of `ExecutionManager.EXECUTION_TOPIC`, but
everything else is shared.

After `mode=debug` launches and the JVM is up, *both* the JVM-attached signal
*and* the service-readiness pipeline (HTTP probe / log banner / idle stdout)
must complete before the tool returns READY. This catches the failure mode
where the JVM is up but the application hasn't bound its server socket yet — a
common pitfall the previous "JVM attached = ready" implementation suffered.

`debug_step` and `debug_inspect` then drive the running session. The
composition is documented in the `relatedTools` block.

## Observation actions: `get_test_results` quirks

The interesting one is `get_test_results`. It routes through the canonical
`interpretTestRoot` (in `RuntimeExecShared.kt`), which fixes the historical
"empty suite → PASSED" bug:

- Empty suites return `NO_TESTS_FOUND` (not `PASSED`).
- Failed runs propagate `isError=true` so the LLM doesn't continue as if tests
  passed.
- Process-still-running case waits up to `MAX_PROCESS_WAIT_SECONDS` (600s) for
  termination via `awaitProcessTermination`, with periodic streaming progress
  notes (`[waiting for process... 30s elapsed]`).
- Test tree finalization waits up to `TEST_TREE_FINALIZE_TIMEOUT_MS` (10s) for
  `TestResultsViewer.onTestingFinished` before reading. If no test console is
  attached, falls back to retry polling (`TEST_TREE_RETRY_ATTEMPTS=10` ×
  `TEST_TREE_RETRY_INTERVAL_MS=500ms` = 5s max).

The observation actions never spawn processes, but they sit on the same tool
because the descriptor-resolution code is shared.

## Counterfactual: dropping `runtime_exec`

Every action degrades to `run_command` if `runtime_exec` is removed. The
specific costs:

| Action | `run_command` substitute | What's lost |
|---|---|---|
| `get_running_processes` | `ps`, `lsof`, `jps` | IntelliJ's view of run/debug sessions; PIDs of background processes that didn't go through the tool |
| `get_run_output` | `tail -f /var/log/...` (if you can find the log) | Live console buffer from the IDE, including sessions started by gutter Run button |
| `get_test_results` | Parse Surefire/Gradle XML manually | The `interpretTestRoot` empty-suite handling, status filter, native test tree |
| `run_config` | `gradle bootRun`, `mvn spring-boot:run`, etc. | Readiness detection, port discovery, idempotency, IDE Run tool window integration, before-run tasks |
| `stop_run_config` | `kill -TERM <pid>; sleep 10; kill -9 <pid>` | Graceful → force state machine; descriptor cleanup |

The single biggest loss is `run_config`'s readiness pipeline. Without it, every
"launch then test" workflow degrades to `run_command "gradle bootRun &"; sleep
30; curl http://localhost:8080/health || sleep 30; curl ...` — fragile, slow,
and frequently wrong.

## Drop-decision summary

Net verdict: **STRONG keep** for the tool overall.

- `run_config` is irreplaceable. Every alternative loses readiness, ports,
  idempotency, or all three.
- `stop_run_config` is the natural pair and shares the stop state machine with
  `run_config`'s idempotent pre-launch stop.
- `get_test_results` is the only path to structured test results without
  re-implementing Surefire/Gradle XML parsing.
- `get_run_output` is the only console-buffer source for sessions the user
  started outside the agent.
- `get_running_processes` is the cheapest action and the most natural "what's
  alive?" query.

The five actions all anchor on the same descriptor-resolution code, so they
belong in one tool. Splitting them would duplicate the resolution logic and
inflate the schema budget for no design win.
