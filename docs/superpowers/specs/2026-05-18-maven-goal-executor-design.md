# Maven Goal Executor — Design

**Status:** Draft (awaiting user review)
**Date:** 2026-05-18
**Branch:** bugfix

## Summary

Add a `run_maven_goal` action to the existing `java_runtime_exec` meta-tool so the agent can execute arbitrary Maven goals (`clean`, `install`, `package`, custom plugin goals) with multi-module awareness, instead of falling back to raw `run_command "./mvnw …"` invocations. The action wraps the existing `:core/MavenBuildService` and reuses `MavenModuleDetector`'s `-pl … -am` wiring.

## Motivation

The agent today has three ways to interact with Maven:

1. `BuildTool` — six **read-only** introspection actions (`maven_dependencies`, `maven_properties`, `maven_plugins`, `maven_profiles`, `maven_dependency_tree`, `maven_effective_pom`). Cannot execute goals.
2. `project_structure.refresh_external_project` — IDE-level Maven plugin actions (reload, generate sources, download sources/docs) via `MavenProjectsManager` and registered action IDs. Cannot execute arbitrary goals.
3. `run_command` — raw shell. Works, but the LLM has to assemble `./mvnw -pl X -am clean install` itself, deal with executable resolution (mvnw vs. mvn vs. MAVEN_HOME) inconsistently, and parse unstructured output. No multi-module type safety.

`:core/MavenBuildService` already implements goal execution correctly (wrapper detection, env sanitization, suspend signature) — it's just not wrapped as an agent tool. This spec closes that gap.

## Non-goals

- **No new top-level `maven` tool.** Goal execution slots in under the existing `java_runtime_exec` meta-tool. Decided 2026-05-18 brainstorming.
- **No relocation of existing IDE actions.** `project_structure.refresh_external_project` continues to own reload / generate_sources / download_*. No aliases.
- **No native `MavenRunner` integration.** Goal execution shells out via `OSProcessHandler` (existing `MavenBuildService` path) rather than going through `org.jetbrains.idea.maven.execution.MavenRunner`. Launches do not appear in the IDE's Run tool window. Tradeoff accepted: smaller surface, no `MavenRunConfigurationType` reflection, no IDE Maven-home preference. Can be revisited.
- **No automatic Surefire/Failsafe XML parsing.** `:core/SurefireReportParser` exists and could parse test reports when goals include `test`/`verify`/`integration-test`, but the v1 LLM-facing surface is intentionally minimal — exit code + tail-biased output is enough for the LLM to inspect failures via `read_file` if needed.
- **No `timeout_seconds` LLM-facing param.** Hardcoded 1200s default. Promote to `PluginSettings` if usage shows the need.

## Architecture

### Placement

`java_runtime_exec(action="run_maven_goal", ...)` — a 4th action alongside `run_tests`, `compile_module`, `rerun_failed_tests`. Inherits:

- Conditional registration via `ToolRegistrationFilter.shouldRegisterJavaBuildTools` (`hasJavaPlugin`).
- `BuildSystemValidator`-style pre-flight error categories.

**Write-action classification (corrected from initial draft).** `java_runtime_exec` is NOT in `AgentLoop.WRITE_TOOLS` or `ApprovalPolicy.APPROVAL_TOOLS` — its existing actions (`run_tests`, `compile_module`, `rerun_failed_tests`) don't override `isWriteAction()`. Goal execution is different — `mvn install` deploys to local repo, `mvn deploy` to remote, custom plugins can mutate arbitrarily. So:

1. `JavaRuntimeExecTool` adds `override fun isWriteAction(action: String?): Boolean = action == "run_maven_goal"`. Plan mode will block `run_maven_goal` (combined with `WRITE_TOOLS` via `AgentLoop`'s `planMode && (toolName in WRITE_TOOLS || tool.isWriteAction(action))` check at `AgentLoop.kt:~955`); the other three actions remain unaffected.
2. The action implementation calls `tool.requestApproval(...)` internally — same pattern as `executeRefreshExternalProject` in `RefreshExternalProjectAction.kt`. The dispatcher passes `this` to the action function: `"run_maven_goal" -> executeRunMavenGoal(params, project, this)`.

### Files

| File | Status | Purpose |
|---|---|---|
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RunMavenGoalAction.kt` | NEW | Action implementation (`executeRunMavenGoal(params, project, tool)`) |
| `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/JavaRuntimeExecTool.kt` | MODIFY | Dispatch `"run_maven_goal"` → `executeRunMavenGoal`. Update tool description block. |
| `core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenBuildService.kt` | MODIFY | Extend `runBuild` signature (extraArgs + timeoutMs); switch blocking `waitFor` to listener-based completion for cancellation propagation. |
| `core/src/test/kotlin/com/workflow/orchestrator/core/maven/MavenBuildServiceTest.kt` | MODIFY | Cover new params + cancellation + timeout invariants |
| `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/RunMavenGoalActionTest.kt` | NEW | Action-level tests (pre-flight gates, param parsing, output spill, approval) |
| `agent/src/test/resources/prompt-snapshots/*.txt` | REGENERATE | 4 of 7 snapshots include `java_runtime_exec` description (null-context, intellij-ultimate, intellij-community, intellij-ultimate-mixed) |

`:agent` continues to depend only on `:core`. No new modules.

## LLM-facing surface

### Parameters

| Param | Type | Required | Default | Notes |
|---|---|---|---|---|
| `action` | string | yes | — | Discriminator: `"run_maven_goal"` |
| `goals` | string | yes | — | Space-separated Maven goals/phases. Examples: `"clean install"`, `"dependency:tree"`, `"spring-boot:run"` |
| `modules` | array&lt;string&gt; | no | `[]` | Module names. When set, prepends `-pl <csv> -am` (existing `MavenModuleDetector.buildMavenArgs` behavior — also-make ON). Omit for whole-project build. |
| `offline` | boolean | no | `false` | Appends `-o` when true. |
| `extra_args` | string | no | `""` | Free-form Maven flags. Tokenized via `ParametersListUtil.parse()`. Examples: `"-DskipTests"`, `"-Pdev,docker"`, `"-Dmaven.test.skip -T 4 -U"` |

### ToolResult shape

```
content:        Header block + captured stdout interleaved with stderr.
                Header lines (always present):
                  $ <executable> <args>
                  Maven detected via: <wrapper | MAVEN_HOME | PATH>
                  Working directory: <project.basePath>
                  Exit code: <int>
                  Duration: <seconds>s
                  ─────────────────────────────────────────
                  <output>

                If captured output > 30KB, spilled via AgentTool.spillOrFormat();
                content becomes head-20 + tail-10 + file path reference.
                Output truncation is tail-biased (matches RunCommandTool) so
                BUILD SUCCESS / BUILD FAILURE markers and Surefire summary
                are preserved.

summary:        "mvn clean install ✓ (45s, exit=0)"
                "mvn clean install ✗ exit=1 — see content"

tokenEstimate:  Computed after truncation.

isError:        true iff exitCode != 0, timedOut, or pre-flight failure.
                (Cancellation is not a result — CancellationException re-thrown
                per agent module convention; AgentLoop owns the cancel UX.)

spillPath:      Set when output spilled (LLM can read_file / search_code on it).

imageRefs:      None.
```

### Action description text

Added to `JavaRuntimeExecTool.description`:

```
- run_maven_goal(goals, modules?, offline?, extra_args?) → Execute Maven goals.
  goals is space-separated (e.g., "clean install", "dependency:tree").
  modules prepends -pl <csv> -am for multi-module scoping.
  extra_args is free-form Maven flags (-DskipTests, -Pdev, -T 4, -U, etc.).
```

## Implementation details

### `MavenBuildService.runBuild` extension

New signature (backward-compatible — existing 3-arg callers continue to compile):

```kotlin
suspend fun runBuild(
    goals: String,
    modules: List<String> = emptyList(),
    offline: Boolean = false,
    extraArgs: List<String> = emptyList(),       // NEW
    timeoutMs: Long? = null                       // NEW — null = PluginSettings.healthCheckTimeoutSeconds * 1000L
): MavenBuildResult
```

Argument order (assembled by `MavenModuleDetector.buildMavenArgs` + this method):

```
<executable> [-pl <modulesCsv> -am] <goalToken>... <extraArg>... [-o]
```

`extraArgs` is appended after the goal list and before the offline flag so that `-DskipTests` and similar property overrides take effect, and so an explicit `-o` in `extra_args` is harmless (no duplicate).

### Cancellation rewrite

Current `handler.waitFor(timeoutMs)` is replaced with listener-based completion:

```kotlin
val completion = CompletableDeferred<Int>()
handler.addProcessListener(object : ProcessAdapter() {
    override fun processTerminated(event: ProcessEvent) {
        completion.complete(event.exitCode)
    }
})
handler.startNotify()

val effectiveTimeoutMs = timeoutMs
    ?: (PluginSettings.getInstance(project).state.healthCheckTimeoutSeconds * 1000L)

val exitCode: Int? = try {
    withTimeoutOrNull(effectiveTimeoutMs) { completion.await() }
} finally {
    if (!handler.isProcessTerminated) handler.destroyProcess()
}
// If we reach here, no CancellationException was thrown.
// exitCode == null means timeout; non-null means natural process exit.

MavenBuildResult(
    success  = exitCode != null && exitCode == 0,
    exitCode = exitCode ?: -1,
    output   = output.toString(),
    errors   = errors.toString(),
    timedOut = exitCode == null
)
```

Coroutine cancellation propagates naturally: if the scope is cancelled, `completion.await()` throws `CancellationException`, which goes through the `finally` block (killing the OS process) and unwinds out of `runBuild`. The agent module convention (`:agent/CLAUDE.md`: "`CancellationException`: Always re-thrown") means no `catch` for it anywhere; `AgentLoop` handles cancellation. Benefits existing callers (`MavenCompileCheck`, `MavenTestCheck`) which today orphan processes when the user closes the project.

### Pre-flight gates (action layer)

Run in order before invoking `MavenBuildService.runBuild`:

1. **INVALID_ARGS — blank goals** — `goals.isBlank()`. Maven CLI accepts empty goal lists and emits `[INFO] BUILD SUCCESS` for a vacuous run; that's misleading to the LLM. Fail fast.
2. **NOT_A_MAVEN_PROJECT** — `MavenUtils.getMavenManager(project) == null`. Return clean error so LLM can pivot to Gradle/run_command.
3. **MAVEN_EXECUTABLE_NOT_FOUND** — `MavenBuildService.detectMavenExecutable()` returns an *absolute* path that fails `File.canExecute()` (e.g., stale `MAVEN_HOME`, mvnw without execute permission). Bare names (`"mvn"`, `"mvn.cmd"`) are not pre-checked — let `OSProcessHandler` surface PATH-resolution failures as `IO_ERROR` with the OS-native message.
4. **INVALID_ARGS — tokenization** — `ParametersListUtil.parse(extra_args)` throws (theoretically can't on valid strings, but guard against future API drift).

### `extra_args` tokenization

`com.intellij.util.execution.ParametersListUtil.parse(rawString)` handles:

- Quoted values: `-Dmessage="hello world"` → single token.
- Backslash escapes: `-Dpath=C:\\foo\\bar` → preserved.
- Multiple spaces: collapsed.

Tokens are appended in order to the `extraArgs` parameter of `runBuild`. The LLM is responsible for getting Maven flag syntax right; the action does not validate flag names (Maven itself will reject unknown flags with a clear error).

**Threat model — `extra_args` injection is in-scope and accepted.** Property-injection attacks via `extra_args` (e.g., `-Dexec.executable=...` triggering the `exec` plugin to run arbitrary binaries; `-Dmaven.ext.class.path=...` loading a malicious extension JAR) are reachable. We do **not** sanitize. Rationale: the agent already exposes `run_command` with full shell access, so `extra_args` does not expand the threat surface — only parity. Any future hardening that scopes the agent's tool set should scope `run_command` and `run_maven_goal` together; do not re-flag `extra_args` as an oversight in isolation.

### Approval gate

Risk classification: `"medium"` (matches `refresh_external_project`). `allowSessionApproval = true` so the LLM doesn't get prompted on every iteration of a fix-build loop.

```kotlin
val approval = tool.requestApproval(
    toolName = "java_runtime_exec.run_maven_goal",
    args = "mvn $goals" + (if (modules.isNotEmpty()) " -pl ${modules.joinToString(",")}" else ""),
    riskLevel = "medium",
    allowSessionApproval = true
)
if (approval == ApprovalResult.DENIED) return ToolResult.error("Approval denied", ...)
```

### Error category taxonomy

Each error result's `content` begins with `<CATEGORY>:` so the LLM can switch on the prefix:

| Category | Trigger | Suggested LLM action |
|---|---|---|
| `INVALID_ARGS` | Pre-flight #1 (blank goals) or #4 (extra_args tokenization) | Fix `goals` / `extra_args` |
| `NOT_A_MAVEN_PROJECT` | Pre-flight #2 | Use `./gradlew` or check project type with `project_modules` |
| `MAVEN_EXECUTABLE_NOT_FOUND` | Pre-flight #3 | Install Maven, add mvnw, or set `MAVEN_HOME` |
| `APPROVAL_DENIED` | User declined approval | No retry; respect user intent |
| `BUILD_FAILURE` | `exitCode != 0` (post-launch) | Inspect tail output for compile/test errors |
| `TIMEOUT` | `MavenBuildResult.timedOut == true` | Narrow scope: smaller `modules`, skip tests, `-T` parallelism |
| `IO_ERROR` | `OSProcessHandler` construction throws (typically `mvn` not on PATH) | Inspect message; usually executable not found or permission |
| `UNEXPECTED_ERROR` | Catch-all | Surface to user, do not blind-retry |

### Output handling

- **Truncation:** tail-biased via existing `OutputCollector.processOutputTailBiased` (same as `RunCommandTool`).
- **Spilling:** `AgentTool.spillOrFormat(rawContent, project)` when content > 30KB. Sets `ToolResult.spillPath` and returns head-20 + tail-10 preview. Matches Phase 7 spill wiring for runtime tools.
- **Cap:** `ToolOutputConfig.COMMAND` (100KB).
- **Header always included** regardless of spill — gives the LLM the executable path, args, exit code, and duration even when the body is spilled.

## Testing

### `:core/MavenBuildService` (extend `MavenBuildServiceTest.kt`)

| Test | Pins |
|---|---|
| `existing 3-arg runBuild still works` | Backward compat |
| `extraArgs appended in expected position` | `<executable> -pl X -am <goals> <extraArgs> [-o]` order |
| `timeoutMs override is honored` | New param takes precedence over health-check default |
| `null timeoutMs falls back to healthCheckTimeoutSeconds` | Default behavior preserved |
| `coroutine cancellation triggers destroyProcess and re-throws` | MockK assert `destroyProcess()` called after scope cancel AND `CancellationException` propagates out of `runBuild` |
| `timedOut flag set when withTimeoutOrNull returns null` | Existing behavior with new wait mechanism |

### `:agent/RunMavenGoalActionTest.kt` (new)

| Test | Pins |
|---|---|
| `missing goals param returns INVALID_ARGS` | Required-param validation |
| `blank goals returns INVALID_ARGS` | Pre-flight #1 (the misleading-success guard) |
| `non-Maven project returns NOT_A_MAVEN_PROJECT` | Pre-flight #2 via `mockkObject(MavenUtils)` |
| `unresolvable executable returns MAVEN_EXECUTABLE_NOT_FOUND` | Pre-flight #3 |
| `JavaRuntimeExecTool.isWriteAction("run_maven_goal") returns true` | Plan-mode gating contract |
| `JavaRuntimeExecTool.isWriteAction("run_tests") still returns false` | No regression on existing actions |
| `extra_args parsed via ParametersListUtil` | `-Dm="hello world"` → single token |
| `BUILD_FAILURE on non-zero exit code` | `isError=true`, `BUILD_FAILURE:` prefix |
| `output > 30KB triggers spillOrFormat` | `spillPath` non-null, content has preview |
| `approval denial returns APPROVAL_DENIED` | Mock `requestApproval` |
| `modules list produces -pl ... -am` | Args wired through correctly |
| `summary follows mvn <goals> ✓/✗ (<sec>s, exit=<int>) format` | UI/notification rendering invariant |

### Snapshot regeneration

Adding `run_maven_goal` changes `java_runtime_exec.description`. Affected snapshots (4 of 7):

- `prompt-snapshots/null-context.txt`
- `prompt-snapshots/intellij-ultimate.txt`
- `prompt-snapshots/intellij-community.txt`
- `prompt-snapshots/intellij-ultimate-mixed.txt`

Workflow per `:agent/CLAUDE.md`:

```bash
./gradlew :agent:test --tests "*SNAPSHOT*"                       # confirm failures on the 4
./gradlew :agent:test --tests "*generate all golden snapshots*"  # regenerate
./gradlew :agent:test --tests "*SNAPSHOT*"                       # confirm green
```

Snapshot diff lands in the same commit as the action.

### Manual integration smoke

Post-`./gradlew runIde`, in a real Maven project (this repo is not Maven; use any external Maven project for verification):

1. `java_runtime_exec(action=run_maven_goal, goals="--version")` — minimal happy path; exits in <1s.
2. `java_runtime_exec(action=run_maven_goal, goals="help:active-profiles", modules=["someModule"])` — proves `-pl` wiring.
3. `java_runtime_exec(action=run_maven_goal, goals="doesnotexist:goal")` — proves `BUILD_FAILURE` path with readable error.

## Open questions / future work

- **IDE Maven-home preference.** `MavenBuildService.detectMavenExecutable()` checks wrapper → `MAVEN_HOME`/`M2_HOME` env → PATH. It does NOT consult IntelliJ's Maven settings (`MavenProjectsManager.generalSettings.mavenHomeType`). A future enhancement could prefer the IDE-configured Maven home so the agent's `mvn` matches what the user gets clicking the IDE's "Execute Maven Goal". Affects existing callers (`MavenCompileCheck`, `MavenTestCheck`) too — separate spec.
- **Surefire/Failsafe auto-parse.** When goals include `test`/`verify`/`integration-test`, the action could optionally parse `target/surefire-reports/*.xml` via `:core/SurefireReportParser` and surface a structured test summary alongside the raw output. Deferred until LLM behavior shows it would help.
- **Native `MavenRunner` integration.** Launching via `MavenRunner` instead of `OSProcessHandler` would put builds in the IDE's Run tool window. Larger surface, more reflection, more failure modes. Revisit if integration with IDE-side run tooling becomes important.
- **`PluginSettings.mavenGoalTimeoutSeconds`.** Promote the hardcoded 1200s default to a settings field if anyone reports hitting it.

## References

- `core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenBuildService.kt` — service to extend
- `core/src/main/kotlin/com/workflow/orchestrator/core/maven/MavenModuleDetector.kt` — multi-module arg construction
- `core/src/main/kotlin/com/workflow/orchestrator/core/util/BuildToolExecutableResolver.kt` — wrapper detection
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/JavaRuntimeExecTool.kt` — meta-tool host
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/MavenUtils.kt` — reflective Maven plugin access
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/project/RefreshExternalProjectAction.kt` — IDE-level Maven actions (already shipped; not modified by this spec)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt` — `spillOrFormat` + `requestApproval` contracts
- `:core/CLAUDE.md` — service architecture conventions
- `:agent/CLAUDE.md` — agent module conventions, snapshot test workflow
