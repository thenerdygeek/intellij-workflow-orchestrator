# Maven Goal Executor — Design

**Status:** Draft (awaiting user review)
**Date:** 2026-05-18
**Branch:** bugfix

## Summary

Add a `run_maven_goal` action to the existing `java_runtime_exec` meta-tool so the agent can execute arbitrary Maven goals (`clean`, `install`, `package`, custom plugin goals) with multi-module awareness. Execution goes through IntelliJ's Maven plugin runner machinery (`org.jetbrains.idea.maven.execution.MavenRunConfigurationType` + `ProgramRunnerUtil.executeConfiguration`) — the same path as IntelliJ's built-in "Execute Maven Goal" dialog — so launches appear in the **Run tool window**, honor IDE-configured Maven home / JRE / `settings.xml` / VM options / environment variables, and can be killed via the Run window's stop button. The agent action mirrors the established `java_runtime_exec.run_tests` pattern (transient config, attached process listener, `RunInvocation` disposal) — Maven-specific config population is the only new code.

## Motivation

The agent today has three ways to interact with Maven:

1. `BuildTool` — six **read-only** introspection actions (`maven_dependencies`, `maven_properties`, `maven_plugins`, `maven_profiles`, `maven_dependency_tree`, `maven_effective_pom`). Cannot execute goals.
2. `project_structure.refresh_external_project` — IDE-level Maven plugin actions (reload, generate sources, download sources/docs) via `MavenProjectsManager` and registered action IDs. Cannot execute arbitrary goals.
3. `run_command` — raw shell subprocess. The LLM has to assemble `./mvnw -pl X -am clean install` itself, deal with executable resolution inconsistently, parse unstructured output, and miss IDE settings entirely.

A new tool that just wraps `OSProcessHandler` would be `run_command` with shorter args. The point of a Maven-specific tool is to use the IntelliJ Maven plugin's execution path so behavior matches what the user gets from the IDE itself. `:core/MavenBuildService` (the subprocess-based path used by pre-commit health checks) is **not** what this spec wires up — that service stays untouched for its current callers.

## Non-goals

- **No new top-level `maven` tool.** Goal execution slots in under the existing `java_runtime_exec` meta-tool. Decided 2026-05-18 brainstorming.
- **No relocation of existing IDE actions.** `project_structure.refresh_external_project` continues to own reload / generate_sources / download_*. No aliases.
- **No changes to `:core/MavenBuildService`.** That subprocess-based path (used by `MavenCompileCheck`, `MavenTestCheck`, `BuildDashboardPanel`) is deliberately separate — those callers want a deterministic, non-UI-coupled execution and shouldn't be affected by this work.
- **No automatic Surefire/Failsafe XML parsing.** `:core/SurefireReportParser` exists and could parse test reports when goals include `test`/`verify`/`integration-test`, but the v1 LLM-facing surface is intentionally minimal — exit code + tail-biased output is enough for the LLM to inspect failures via `read_file` if needed.
- **No `timeout_seconds` LLM-facing param.** Hardcoded 1200s default. Promote to `PluginSettings` if usage shows the need.

## Architecture

### Placement

`java_runtime_exec(action="run_maven_goal", ...)` — a 4th action alongside `run_tests`, `compile_module`, `rerun_failed_tests`. Inherits:

- Conditional registration via `ToolRegistrationFilter.shouldRegisterJavaBuildTools` (`hasJavaPlugin`).
- The `RunInvocation` disposal contract (the same `Disposable` pattern that `run_tests` uses to guarantee process+listener teardown).

**Write-action classification.** `java_runtime_exec` is NOT in `AgentLoop.WRITE_TOOLS` or `ApprovalPolicy.APPROVAL_TOOLS` — its existing actions (`run_tests`, `compile_module`, `rerun_failed_tests`) don't override `isWriteAction()`. Goal execution is different — `mvn install` deploys to local repo, `mvn deploy` to remote, custom plugins can mutate arbitrarily. So:

1. `JavaRuntimeExecTool` adds `override fun isWriteAction(action: String?): Boolean = action == "run_maven_goal"`. Plan mode will block `run_maven_goal` (combined with `WRITE_TOOLS` via `AgentLoop`'s `planMode && (toolName in WRITE_TOOLS || tool.isWriteAction(action))` check); the other three actions remain unaffected.
2. The action implementation calls `tool.requestApproval(...)` internally — same pattern as `executeRefreshExternalProject` in `RefreshExternalProjectAction.kt`. The dispatcher passes `this` to the action function: `"run_maven_goal" -> executeRunMavenGoal(params, project, this)`.

### Files

| File | Status | Purpose |
|---|---|---|
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RunMavenGoalAction.kt` | NEW | Whole action: pre-flight, transient `MavenRunConfiguration` construction, launch via `ProgramRunnerUtil.executeConfigurationAsync`, process listener attachment, output capture, `RunInvocation` disposal. |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/JavaRuntimeExecTool.kt` | MODIFY | (a) Add `"run_maven_goal"` to the `enumValues` of the `action` parameter. (b) Update the `description` text. (c) Add `isWriteAction(action)` override. (d) Add dispatch branch `"run_maven_goal" -> executeRunMavenGoal(params, project, this)`. |
| `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/RunMavenGoalActionTest.kt` | NEW | Action-level unit tests (pre-flight gates, param parsing, write-action contract, approval gate). Integration paths tested via the smoke list below since `MavenRunConfiguration` is impractical to fully mock. |
| `agent/src/test/resources/prompt-snapshots/*.txt` | REGENERATE | 4 of 7 snapshots include `java_runtime_exec` description (null-context, intellij-ultimate, intellij-community, intellij-ultimate-mixed) |

**No `:core` changes.** `MavenBuildService` is not touched. `MavenModuleDetector` is not touched (the multi-module arg-list helper isn't used by this path — `MavenRunnerParameters.goals` accepts `-pl`/`-am` tokens directly).

`:agent` continues to depend only on `:core`. `:agent/build.gradle.kts:31` already declares `bundledPlugin("org.jetbrains.idea.maven")` so the `MavenRunConfigurationType` / `MavenRunnerParameters` / `MavenRunnerSettings` classes are compile-time visible — no reflection needed except where `JavaRuntimeExecTool.run_tests` already uses it (e.g., populating private fields on a configuration's persistent data object).

## LLM-facing surface

### Parameters

| Param | Type | Required | Default | Notes |
|---|---|---|---|---|
| `action` | string | yes | — | Discriminator: `"run_maven_goal"` |
| `goals` | string | yes | — | Space-separated Maven goals/phases. Examples: `"clean install"`, `"dependency:tree"`, `"spring-boot:run"` |
| `modules` | array&lt;string&gt; | no | `[]` | Module names. When non-empty, injected as `-pl <csv> -am` tokens at the head of `MavenRunnerParameters.goals`. Omit for whole-project build. |
| `offline` | boolean | no | `false` | Appends `-o` token when true. |
| `extra_args` | string | no | `""` | Free-form Maven flags. Tokenized via `ParametersListUtil.parse()`. Examples: `"-DskipTests"`, `"-Pdev,docker"`, `"-Dmaven.test.skip -T 4 -U"` |

### ToolResult shape

```
content:        Header block + captured stdout (interleaved with stderr by the
                ProcessListener — Maven's own console interleaves them too).
                Header lines (always present):
                  Maven goal: <goals>
                  Modules: <csv or "all">
                  Working directory: <project.basePath>
                  Maven home (IDE-configured): <resolved path>
                  Exit code: <int>
                  Duration: <seconds>s
                  ─────────────────────────────────────────
                  <output>

                Output truncation is tail-biased (matches RunCommandTool) so
                BUILD SUCCESS / BUILD FAILURE markers and the Surefire summary
                are preserved.
                If captured output > 30KB, spilled via AgentTool.spillOrFormat();
                content becomes head-20 + tail-10 + file path reference.

summary:        "mvn clean install ✓ (45s, exit=0)"
                "mvn clean install ✗ exit=1 — see content"

tokenEstimate:  Computed after truncation.

isError:        true iff exitCode != 0, timedOut, processNotStarted, or pre-flight failure.
                (Cancellation is not a result — CancellationException re-thrown
                per agent module convention; AgentLoop owns the cancel UX.)

spillPath:      Set when output spilled (LLM can read_file / search_code on it).

imageRefs:      None.
```

### Action description text

Added to `JavaRuntimeExecTool.description`:

```
- run_maven_goal(goals, modules?, offline?, extra_args?) → Execute Maven goals via
  IntelliJ's Maven plugin runner (appears in Run tool window; honors IDE-configured
  Maven home/JRE/settings.xml/VM options). goals is space-separated (e.g., "clean
  install", "dependency:tree"). modules prepends -pl <csv> -am for multi-module
  scoping. extra_args is free-form Maven flags (-DskipTests, -Pdev, -T 4, -U).
```

## Implementation details

### IntelliJ Maven plugin runner integration

The action mirrors `java_runtime_exec.run_tests` (in `JavaRuntimeExecTool.kt`) step-for-step, with three concrete differences: (a) the configuration type and factory come from `MavenRunConfigurationType` instead of `JUnitConfigurationType`/`TestNGConfigurationType`; (b) configuration population sets `MavenRunnerParameters` instead of `JUnitConfiguration.persistentData`; (c) there is no separate `ProjectTaskManager.build(module)` pre-build phase — Maven's goal list (`clean compile install`, etc.) is itself the build, and adding a Java compile before it would be wrong.

#### Step 1: pre-flight gates

Run in order, before any IntelliJ executor machinery is engaged:

1. **INVALID_ARGS — blank goals** — `goals.isBlank()`. Maven CLI treats an empty goal list as a no-op and prints `[INFO] BUILD SUCCESS` for a vacuous run; that's misleading to the LLM. Fail fast.
2. **NOT_A_MAVEN_PROJECT** — `MavenProjectsManager.getInstance(project).isMavenizedProject == false` (via the existing `MavenUtils.getMavenManager(project)` helper, which returns null in that case). Return clean error so the LLM can pivot to Gradle / `run_command`.
3. **INVALID_ARGS — tokenization** — `ParametersListUtil.parse(extra_args)` throws (theoretically can't on valid strings, but guard against future API drift).

Note: there is no `MAVEN_EXECUTABLE_NOT_FOUND` pre-flight in this path. The Maven plugin's runner resolves the Maven executable from `MavenProjectsManager.getInstance(project).generalSettings.mavenHomeType` (the IDE-configured Maven home); if that's misconfigured, the runner reports it via `ExecutionException` or `processNotStarted`, which map to `EXECUTION_EXCEPTION` / `PROCESS_NOT_STARTED` below.

#### Step 2: approval gate

```kotlin
val approval = tool.requestApproval(
    toolName = "java_runtime_exec.run_maven_goal",
    args = "mvn $goals" + (if (modules.isNotEmpty()) " -pl ${modules.joinToString(",")}" else ""),
    riskLevel = "medium",
    allowSessionApproval = true
)
if (approval == ApprovalResult.DENIED) {
    return ToolResult.error("APPROVAL_DENIED: …", "Approval denied", …)
}
```

Risk classification `"medium"` matches `refresh_external_project`. `allowSessionApproval = true` lets the LLM iterate on a fix-build loop without re-prompting.

#### Step 3: build the goal-and-flag token list

```kotlin
val goalsTokens = goals.trim().split("\\s+".toRegex())
val extraTokens = ParametersListUtil.parse(extraArgs)
val moduleTokens =
    if (modules.isNotEmpty()) listOf("-pl", modules.joinToString(","), "-am") else emptyList()
val offlineToken = if (offline) listOf("-o") else emptyList()

val allGoalTokens: List<String> = moduleTokens + goalsTokens + extraTokens + offlineToken
```

`-pl … -am` comes first because IntelliJ's `MavenRunner` documentation treats flags interchangeably with goals — order within `MavenRunnerParameters.goals` doesn't affect Maven CLI parsing, but placing `-pl` first makes the in-IDE Run window display readable.

#### Step 4: construct the transient `MavenRunConfiguration`

```kotlin
val runManager = RunManager.getInstance(project)
val mavenType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.firstOrNull {
    it.id == "MavenRunConfiguration"
} ?: return ToolResult.error("EXECUTION_EXCEPTION: MavenRunConfigurationType not registered…")
// The `id` is locale-independent; the historical `displayName == "Maven"` fallback
// would have been locale-sensitive (the display name flows through MavenRunnerBundle)
// and is intentionally omitted.
val factory = mavenType.configurationFactories.firstOrNull()
    ?: return ToolResult.error("EXECUTION_EXCEPTION: Maven ConfigurationType has no factories…")

val configName = "[Agent] mvn ${goals.take(40)}"
val settings: RunnerAndConfigurationSettings = runManager.createConfiguration(configName, factory)
val config = settings.configuration   // MavenRunConfiguration

// Populate runner parameters
val params = MavenRunnerParameters(
    /* isPomExecution = */ true,
    /* workingDirPath = */ project.basePath!!,
    /* pomFileName    = */ "pom.xml",
    /* goals          = */ allGoalTokens,
    /* explicitProfiles = */ emptyMap<String, Boolean>()
)
config.runnerParameters = params

settings.isTemporary = true
// Do NOT call runManager.setTemporaryConfiguration(settings) — that steals
// the user's selected run config and causes the same "initialization error
// on next manual run" regression we already documented in run_tests' commit
// 9b164bf3 history.
```

Profiles are explicitly empty: the user's IDE-configured profile activation (`MavenProjectsManager.explicitProfiles`) is consulted by the Maven runner automatically. The LLM passes profile selection via `extra_args="-Pdev,docker"` rather than the typed param to keep the surface minimal.

Note on the property access: `MavenRunConfiguration` is a Java class exposing `getRunnerParameters()` / `setRunnerParameters(MavenRunnerParameters)`. Kotlin's Java-bean property-access synthesis lets us write `config.runnerParameters = params` — this is **not** a native Kotlin property, it's the Kotlin compiler synthesizing the setter call. The synthesis is stable Kotlin behavior, not a bet on Maven plugin internals; direct assignment is the primary path. If a future Maven plugin removes the setter entirely, fall back to `config.javaClass.getMethod("setRunnerParameters", MavenRunnerParameters::class.java).invoke(config, params)` — the same reflective-fallback hardening `run_tests` uses for its persistent-data field accesses.

#### Step 5: launch via `ProgramRunnerUtil` + capture via `ProcessListener`

The structural pattern is `withTimeoutOrNull(N) { suspendCancellableCoroutine { cont -> ... } }` — timeout wraps the suspending wait, never the other way around. **Do not** call `runBlockingCancellable { withTimeoutOrNull { deferredExit.await() } }` inside `suspendCancellableCoroutine`; that nests a blocking call inside a continuation handler and cannot propagate the outer continuation's cancellation correctly. The deferred completes inside `processTerminated`; the continuation resumes from the same callback.

```kotlin
val output = StringBuilder()
val startNs = System.nanoTime()

val result: ToolResult? = withTimeoutOrNull(1200_000L) {
    suspendCancellableCoroutine<ToolResult> { continuation ->
        val invocation = project.service<AgentService>()
            .newRunInvocation("run-maven-${goals.take(20)}")

        continuation.invokeOnCancellation {
            // Coroutine cancellation (agent kill, or withTimeoutOrNull's own
            // cancellation when 1200s elapses) kills the Maven process via
            // RunInvocation disposal — kills the ProcessHandler and removes
            // the Run window content.
            Disposer.dispose(invocation)
        }

        // EDT: the executor framework requires UI thread for env build + launch.
        invokeLater {
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            val env = ExecutionEnvironmentBuilder.createOrNull(executor, settings)?.build()
            if (env == null) {
                if (continuation.isActive) continuation.resume(
                    ToolResult.error("EXECUTION_EXCEPTION: ExecutionEnvironmentBuilder returned null", …)
                )
                return@invokeLater
            }

            val callback = object : ProgramRunner.Callback {
                override fun processStarted(descriptor: RunContentDescriptor?) {
                    if (descriptor == null) {
                        if (continuation.isActive) continuation.resume(
                            ToolResult.error("PROCESS_NOT_STARTED: runner produced no RunContentDescriptor", …)
                        )
                        return
                    }
                    invocation.descriptorRef.set(descriptor)

                    descriptor.processHandler?.addProcessListener(object : ProcessAdapter() {
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            output.append(event.text)
                        }
                        override fun processTerminated(event: ProcessEvent) {
                            // Resume directly from the listener; no separate wait needed.
                            if (continuation.isActive) {
                                val durationSec = (System.nanoTime() - startNs) / 1_000_000_000.0
                                continuation.resume(
                                    buildResult(event.exitCode, output, durationSec, …)
                                )
                            }
                        }
                    })

                    // Belt-and-suspenders descriptor cleanup. Maven's own runner
                    // self-cleans its RunContentDescriptor on normal process
                    // termination via ProcessHandler.destroyProcess — so this is
                    // NOT required the way it is for JUnit (where TestResultsViewer
                    // has no removeEventsListener API). It IS still needed for
                    // abnormal termination and agent-cancel paths where the runner
                    // may not get a chance to clean up. RunContentManager.removeRunContent
                    // is idempotent, so a double-call from the runner + this block is safe.
                    invocation.onDispose {
                        val d = invocation.descriptorRef.get() ?: return@onDispose
                        invokeLater {
                            RunContentManager.getInstance(project).removeRunContent(executor, d)
                        }
                    }
                }
            }

            // Defence-in-depth: ExecutionListener.processNotStarted catches cases
            // the runner refuses (no ProgramRunner registered, executor disabled,
            // JDK lookup failed) that the callback above doesn't surface.
            val conn = project.messageBus.connect()
            invocation.subscribeTopic(conn)
            conn.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processNotStarted(executorId: String, e: ExecutionEnvironment) {
                    if (e == env && continuation.isActive) {
                        continuation.resume(
                            ToolResult.error("PROCESS_NOT_STARTED: execution framework aborted before launch", …)
                        )
                    }
                }
            })

            try {
                ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
            } catch (_: NoSuchMethodError) {
                env.callback = callback
                ProgramRunnerUtil.executeConfiguration(env, false, true)
            }
        }
        // suspendCancellableCoroutine block ends; continuation resolved from
        // processTerminated, callback failure paths, or processNotStarted.
    }
}

// If withTimeoutOrNull returned null, the 1200s wall clock elapsed. Coroutine
// cancellation already disposed the invocation (and thus killed the Maven
// process) via invokeOnCancellation above — we just need to format the result.
result ?: buildTimeoutResult(output, durationSec = 1200.0, …)
```

Key invariants:
- Timeout is **outside** the suspending block, never inside.
- Cancellation flows: outer agent kill → coroutine cancel → `invokeOnCancellation` → `Disposer.dispose(invocation)` → `ProcessHandler` killed → Run window cleared. Same flow whether triggered by user kill, by `withTimeoutOrNull`'s elapsed wall clock, or by the agent's `SessionDisposableHolder` reset.
- The continuation is resumed exactly once per dispatch — guarded by `continuation.isActive` checks at every resume site to avoid `IllegalStateException` from double-resume in race-y failure paths (e.g., `processNotStarted` fires after the callback already resumed with `PROCESS_NOT_STARTED`).

### Error category taxonomy

Each error result's `content` begins with `<CATEGORY>:` so the LLM can switch on the prefix:

| Category | Trigger | Suggested LLM action |
|---|---|---|
| `INVALID_ARGS` | Pre-flight #1 (blank goals) or #3 (extra_args tokenization) | Fix `goals` / `extra_args` |
| `NOT_A_MAVEN_PROJECT` | Pre-flight #2 | Use `./gradlew` or check project type with `project_modules` |
| `APPROVAL_DENIED` | User declined approval | No retry; respect user intent |
| `EXECUTION_EXCEPTION` | `MavenRunConfigurationType` not registered, `ExecutionEnvironmentBuilder.createOrNull` returns null, or `ProgramRunnerUtil.execute…` throws | Check Maven plugin enablement; check IDE-configured Maven home |
| `PROCESS_NOT_STARTED` | `ProgramRunner.Callback.processStarted(null)` OR `ExecutionListener.processNotStarted` fires | Check JDK resolution / executor availability |
| `BUILD_FAILURE` | `exitCode != 0` (post-launch) | Inspect tail output for compile / test / plugin errors |
| `TIMEOUT` | `withTimeoutOrNull(1200_000L)` returned null | Narrow scope: smaller `modules`, skip tests, `-T` parallelism |
| `UNEXPECTED_ERROR` | Catch-all wrapping anything not above | Surface to user; do not blind-retry |

`extra_args` injection threat model is **in-scope and accepted**. Property-injection attacks via `extra_args` (e.g., `-Dexec.executable=…` triggering the `exec` plugin to run arbitrary binaries; `-Dmaven.ext.class.path=…` loading a malicious extension JAR) are reachable. We do **not** sanitize — `run_command` already exposes full shell access, so `extra_args` is parity, not net new surface. Future scope-restriction work should scope `run_command` and `run_maven_goal` together.

### Output handling

- **Capture:** `ProcessAdapter.onTextAvailable` appends `event.text` to a `StringBuilder` regardless of `outputType` (stdout / stderr). Maven's console output already interleaves stdout/stderr, and the Run-window UI shows them merged — matching that here keeps the LLM's view consistent with the IDE's.
- **Truncation:** tail-biased via existing `OutputCollector.processOutputTailBiased` (same as `RunCommandTool`).
- **Spilling:** `AgentTool.spillOrFormat(rawContent, project)` when content > 30KB. Sets `ToolResult.spillPath` and returns head-20 + tail-10 preview. Matches Phase 7 spill wiring for runtime tools.
- **Cap:** `ToolOutputConfig.COMMAND` (100KB).
- **Header always included** regardless of spill — gives the LLM the goals, modules, working dir, Maven home, exit code, and duration even when the body is spilled.

### Cancellation

Coroutine cancellation goes through `suspendCancellableCoroutine.invokeOnCancellation` → `Disposer.dispose(invocation)`. The `RunInvocation` disposal:

1. Removes the `RunContentDescriptor` from `RunContentManager` (clears the IDE Run window tab). The Maven runner itself also self-cleans the descriptor on normal `ProcessHandler.destroyProcess` — so for normal termination this is redundant. For abnormal termination (kill, timeout, crash) and the agent's `SessionDisposableHolder.resetSession()` cascade, the agent-side `onDispose` is the cleanup that fires. `RunContentManager.removeRunContent` is idempotent — a double-call from both sources is safe.
2. Kills the `ProcessHandler` (terminates the `mvn` subprocess that the Maven plugin spawned under the hood).
3. Disconnects the `messageBus` connection and process listener.

No `catch (CancellationException)` is needed; the exception propagates per `:agent/CLAUDE.md` convention. The IDE Run window's red "Stop" button also fires the process handler's terminate, which feeds `processTerminated` and naturally resumes the coroutine with the exit code Maven reported — same control surface in both directions.

## Testing

`MavenRunConfiguration` and `ProgramRunnerUtil.executeConfigurationAsync` are deeply embedded in IntelliJ's execution framework — practical to test only via integration smoke, not unit mocks. So testing splits into a thin unit layer for pre-flights / wiring and a manual integration layer for actual launches.

### `:agent/RunMavenGoalActionTest.kt` (new — unit)

| Test | Pins |
|---|---|
| `missing goals param returns INVALID_ARGS` | Required-param validation |
| `blank goals returns INVALID_ARGS` | Pre-flight #1 (the misleading-success guard) |
| `non-Maven project returns NOT_A_MAVEN_PROJECT` | Pre-flight #2 via `mockkObject(MavenUtils)` |
| `JavaRuntimeExecTool.isWriteAction("run_maven_goal") returns true` | Plan-mode gating contract |
| `JavaRuntimeExecTool.isWriteAction("run_tests") still returns false` | No regression on existing actions |
| `extra_args parsed via ParametersListUtil` | `-Dm="hello world"` → single token (assert against the parsed list, not a launch) |
| `approval denial returns APPROVAL_DENIED` | Mock `requestApproval` |
| `goal-and-flag token assembly: modules + goals + extra + offline` | `-pl X,Y -am clean install -DskipTests -o` order |
| `summary format follows mvn <goals> ✓/✗ (<sec>s, exit=<int>)` | UI/notification rendering invariant via the helper that builds it (no launch) |
| `MavenRunConfigurationType lookup by id="MavenRunConfiguration" succeeds when plugin is present` | Pinpoints the locale-independent id; pre-flight fails cleanly otherwise |

### `:agent` snapshot regeneration

Adding `run_maven_goal` changes `java_runtime_exec.description`. Affected snapshots (4 of 7):

- `prompt-snapshots/null-context.txt`
- `prompt-snapshots/intellij-ultimate.txt`
- `prompt-snapshots/intellij-community.txt`
- `prompt-snapshots/intellij-ultimate-mixed.txt`

(`pycharm-professional.txt`, `pycharm-community.txt`, `webstorm.txt` do not register `java_runtime_exec` — no Java plugin → tool absent.)

Workflow per `:agent/CLAUDE.md`:

```bash
./gradlew :agent:test --tests "*SNAPSHOT*"                       # confirm failures on the 4
./gradlew :agent:test --tests "*generate all golden snapshots*"  # regenerate
./gradlew :agent:test --tests "*SNAPSHOT*"                       # confirm green
```

Snapshot diff lands in the same commit as the action.

### Manual integration smoke

Post-`./gradlew runIde`, in a real Maven project (this repo is Gradle; use any external Maven project for verification — a small one like `spring-projects/spring-petclinic` works well):

1. **Happy path, tiny goal:** `java_runtime_exec(action=run_maven_goal, goals="--version")` — exits in <1s. Verify: Run tool window shows an `[Agent] mvn --version` tab; output captured in `ToolResult.content`; exit code 0.
2. **Multi-module wiring:** `java_runtime_exec(action=run_maven_goal, goals="help:active-profiles", modules=["someModule"])` — verifies `-pl … -am` injection. Run tool window should show the args in its header line.
3. **Build failure category:** `java_runtime_exec(action=run_maven_goal, goals="doesnotexist:goal")` — verifies `BUILD_FAILURE:` prefix and that Maven's error message reaches the captured content.
4. **IDE settings honored:** Set a custom Maven home in Settings → Build → Build Tools → Maven → Maven home path. Run `run_maven_goal` with `goals="--version"`. Verify the `--version` output reports the configured Maven home, proving the IDE setting flowed through.
5. **Run-window kill:** Start `run_maven_goal(goals="install")` (long-running). Click the red "Stop" in the Run window. Verify the tool returns with `BUILD_FAILURE` or `TIMEOUT` and the agent loop continues without hanging.
6. **Plan mode blocks it:** Toggle plan mode in the agent chat. Try `run_maven_goal` — the LLM should not see the action in its tool definitions (schema filtering removes write actions in plan mode). Confirm by inspecting the agent's per-iteration tool definition payload.

These six together cover the launch surface; the unit tests cover the pre-flight / wiring surface; together they replace the heavier mock-based testing that the previous (`MavenBuildService`-based) draft proposed.

## Open questions / future work

- **Surefire/Failsafe auto-parse.** When goals include `test`/`verify`/`integration-test`, the action could optionally parse `target/surefire-reports/*.xml` via `:core/SurefireReportParser` and surface a structured test summary alongside the raw output. Deferred until LLM behavior shows it would help.
- **`PluginSettings.mavenGoalTimeoutSeconds`.** Promote the hardcoded 1200s default to a settings field if anyone reports hitting it.
- **Typed `profiles` param.** Currently profile selection rides on `extra_args="-Pdev,docker"`. If usage shows the LLM frequently mismanages this, promote to a typed `profiles: array<string>` param that flows into `MavenRunnerParameters.explicitProfiles` (the structured Maven plugin API field) instead of the goals token list.
- **Native `JUnitConfiguration`-style test result parsing.** The Maven plugin exposes test result events through `MavenConsoleImpl` and the Maven Run window tree. Hooking into them would give the agent structured test pass/fail counts for `mvn test` without separately parsing Surefire XML. Significantly larger surface; revisit only if Surefire parsing turns out to be insufficient.

## References

- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/JavaRuntimeExecTool.kt` — meta-tool host; the `run_tests` flow (line ~1075 onwards) is the exact pattern this action mirrors
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RunInvocation.kt` — disposal contract used by both `run_tests` and this action
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenUtils.kt` — `getMavenManager(project)` for pre-flight #2
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/project/RefreshExternalProjectAction.kt` — internal-approval pattern reused here
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt` — `spillOrFormat`, `requestApproval`, `isWriteAction` contracts
- `org.jetbrains.idea.maven.execution.MavenRunConfigurationType` — registered via the bundled Maven plugin
- `org.jetbrains.idea.maven.execution.MavenRunnerParameters` — `(isPomExecution, workingDirPath, pomFileName, goals, explicitProfiles)` constructor
- `org.jetbrains.idea.maven.execution.MavenRunnerSettings` — IDE-configured Maven runner settings (JRE / VM options / env / settings.xml)
- `org.jetbrains.idea.maven.project.MavenProjectsManager.generalSettings` — IDE-configured Maven home / local repo
- `:core/MavenBuildService.kt` — **NOT modified by this spec.** Mentioned only to note it stays untouched.
- `:core/CLAUDE.md` — service architecture conventions
- `:agent/CLAUDE.md` — agent module conventions, snapshot test workflow, `runBlockingCancellable` policy, `RunInvocation` pattern
