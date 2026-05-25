# :bamboo Module Security & Correctness Audit
**Date:** 2026-05-24  
**Auditor:** Claude Sonnet 4.6 (read-only, max-effort)  
**Scope:** `bamboo/src/main/kotlin/` (28 files)

---

## Findings

### F-1 [P0] [Security]: SnakeYAML unsafe deserializer enables remote code execution via malicious bamboo-specs YAML

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooSpecsLocalParser.kt:40`  
**Also**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt:360-361`

**Description**: Both `BambooSpecsLocalParser.extractKey()` and `PlanDetectionService.extractRepoUrls()` instantiate `Yaml()` (SnakeYAML) without a `SafeConstructor`. The default constructor resolves arbitrary Java class tags embedded in YAML (e.g. `!!java.net.URL`, `!!com.sun.rowset.JdbcRowSetImpl`), enabling deserialization gadget chains. An attacker who controls a `bamboo-specs/bamboo.yml` in a repo the developer opens (e.g., a malicious PR or a compromised upstream) can achieve RCE inside the IDE JVM.

**Evidence**:
```kotlin
// BambooSpecsLocalParser.kt:40
val data = Yaml().load<Any?>(reader) ?: return null

// PlanDetectionService.kt:360-361
val yaml = org.yaml.snakeyaml.Yaml()
val data = yaml.load<Any>(specsYaml)
```

**Impact**: Full RCE in the developer's IDE process. Triggered by opening a project containing a crafted `bamboo-specs/*.yml`, or by the Tier 4 deep-scan fetching a crafted remote YAML from Bamboo.

**Fix sketch**: Replace `Yaml()` with `Yaml(SafeConstructor(LoaderOptions()))` in both call sites. This disables custom class tags and restricts loading to safe scalar/map/list types only.

---

### F-2 [P0] [Security]: Build variable values printed in plaintext to console UI — password variables exposed

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildRunState.kt:81`

**Description**: `BambooBuildProcessHandler.runBuild()` iterates `effectiveVariables` and calls `printOutput("  $k = $v\n")` for every variable, including those sourced from `BambooBuildRunConfiguration.getBuildVariables()`. There is no `isPassword` check here — any password-typed variable the user configures (e.g., a deploy token or database password) is printed verbatim to the Run console, which persists in the IDE's Run tool-window history and is searchable.

**Evidence**:
```kotlin
// BambooBuildRunState.kt:79-81
if (effectiveVariables.isNotEmpty()) {
    printOutput("Variables:\n")
    effectiveVariables.forEach { (k, v) -> printOutput("  $k = $v\n") }
}
```

**Impact**: Any secret passed as a build variable via the run configuration is leaked to the IDE's Run console, visible to anyone who can screenshot or share the developer's screen. The `ManualStageDialog.doOKAction()` at line 622-629 correctly redacts password variables before logging — this path does not.

**Fix sketch**: `BambooBuildRunConfiguration` has no `isPassword` metadata. Either suppress value display entirely (`"  $k = <hidden>\n"`) or integrate with `PlanVariableData.isPassword` by fetching plan variables before printing.

---

### F-3 [P0] [Resource Leak]: `BuildFailureBridgeStartupActivity` creates an unmanaged `CoroutineScope` that is never cancelled on project close

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/listeners/BuildFailureBridgeStartupActivity.kt:52-54`

**Description**: `execute()` creates `CoroutineScope(SupervisorJob() + Dispatchers.IO)` locally and launches a long-running `collect` coroutine against the event bus. This scope is stored in a local variable — it is never registered with a `Disposable`, never cancelled on project close, and never cancelled when `execute()` returns. If the project is closed and reopened, a new scope is created without the old one being cleaned up.

**Evidence**:
```kotlin
// BuildFailureBridgeStartupActivity.kt:52-54
val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
scope.launch {
    eventBus.events
        .filterIsInstance<WorkflowEvent.BuildFinished>()
        .collect { event -> ... }
}
```

**Impact**: Memory leak — a dead `SupervisorJob` + coroutine collecting from a hot SharedFlow is retained for the IDE session lifetime. On repeated project open/close (common in enterprise settings), scopes accumulate. The `eventBus.events` SharedFlow keeps the coroutine alive even after the project is disposed.

**Fix sketch**: Use a platform-scoped coroutine context. Either accept `cs: CoroutineScope` via DI (per `:core` "Service & threading conventions"), or cancel the scope by registering `scope.launch { … }.invokeOnCompletion { }` + `project.coroutineScope` from `ProjectActivity`.

---

### F-4 [P0] [Security / Correctness]: No SSRF guard on Bamboo base URL — arbitrary internal host reachable

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt:59-61`  
**Also**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/listeners/BuildFailureBridgeStartupActivity.kt:141-150`

**Description**: The Bamboo base URL is read directly from `PluginSettings` and passed verbatim to `BambooApiClient` with no validation beyond `isBlank()`. `ConnectionsConfigurable.kt:176` shows a UI warning for non-`https://` URLs, but the warning is advisory only — the setting is saved regardless. A developer whose settings XML is tampered with (shared settings commit, workspace poisoning) or who imports a project with pre-configured settings could have requests directed to `http://localhost:8080`, `file://`, or any internal CIDR host. OkHttp will happily follow redirects to `file://` paths on some JVM versions.

**Evidence**:
```kotlin
// BambooServiceImpl.kt:59-61
val url = settings.connections.bambooUrl.orEmpty().trimEnd('/')
if (url.isBlank()) return null
// url used verbatim — no scheme check, no localhost block
cachedClient = BambooApiClient(baseUrl = url, ...)
```

**Impact**: SSRF — an attacker who can influence the settings (e.g., via a shared `.idea/` commit) can direct Bamboo HTTP calls to cloud metadata endpoints (`http://169.254.169.254`), internal services, or `file://` URIs. The Bearer token is sent to whichever host is configured.

**Fix sketch**: Validate the URL on save AND on first use: require `https://` scheme, reject private IP ranges (RFC 1918 + loopback), reject `file://`. Throw a `ToolResult.error` before constructing the client when validation fails.

---

### F-5 [P0] [Security]: ReDoS via attacker-controlled method/class names in `BambooTestResultConverter.extractTestError`

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooTestResultConverter.kt:149-163`

**Description**: `extractTestError()` constructs three regexes at runtime using `Regex.escape(methodName)` and `Regex.escape(fullClassName)` from Bamboo's API response, then runs them against the full build log. The patterns use `[\s\S]*?` (lazy any-char including newlines) with MULTILINE anchors and lookahead alternation `(?=\n\S|\n\n|\Z)`. On a crafted build log containing thousands of partial matches before a non-matching terminal, each backtrack compounds. A 29 KB log with adversarially placed test output lines can trigger catastrophic backtracking in the third pattern (Pattern 3, which does not anchor to the class name at all).

**Evidence**:
```kotlin
// BambooTestResultConverter.kt:149-163
val pattern1 = Regex(
    """${Regex.escape(methodName)}\(${Regex.escape(fullClassName)}\).*<<<\s*(FAILURE|ERROR)!\s*\n([\s\S]*?)(?=\n\S|\n\n|\Z)""",
    RegexOption.MULTILINE
)
// pattern3 has no class anchor:
val pattern3 = Regex(
    """${Regex.escape(methodName)}.*<<<\s*(FAILURE|ERROR)!\s*\n([\s\S]*?)(?=\n[^\s]|\n\n|\Z)""",
    RegexOption.MULTILINE
)
```

**Impact**: A Bamboo build log containing a test named with a method name that appears many times in build output (e.g., common words like `test`, `run`, `build`) can freeze the EDT for seconds to minutes when log parsing executes. Since `extractErrorsFromLog()` is called from `toTeamCityMessages()` which is called from `BuildDashboardPanel` on the background coroutine, this freezes log rendering.

**Fix sketch**: Replace `[\s\S]*?` with line-count-bounded matching (collect N lines after the marker, stop at first blank line). Apply a `withTimeoutOrNull(200)` guard around each pattern match. Consider dropping Pattern 3 entirely since it matches any occurrence of the method name in the log.

---

### F-6 [P1] [Correctness / Security]: Build variable values leaked to log via `getPlanVariables` fallback path (Strategy C)

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt:316-320`

**Description**: Strategy C (fall back to most recent build's variables) calls `api.getBuildVariables(resultKey)` which returns a `Map<String, String>` from `BambooApiClient.getBuildVariables`. The result is mapped to `PlanVariableData` with `isPassword = false` (default) for every variable — the build-level variable response does not carry `isPassword`. If a password variable has been overridden at build time (common for deploy secrets), its current value is returned here and stored in `PlanVariableData` without masking. The variable's value will then be logged at `log.info("[BambooService] Got ${data.size} variable(s) from last build")` (no values, but the count confirms the secret was fetched) and rendered in `ManualStageDialog` as a plain `JBTextField` instead of `JBPasswordField`, because `isPassword=false`.

**Evidence**:
```kotlin
// BambooServiceImpl.kt:316-320
val data = varsResult.data.entries.map { PlanVariableData(name = it.key, value = it.value) }
// isPassword defaults to false — password classification is lost
log.info("[BambooService] Got ${data.size} variable(s) from last build $resultKey as fallback")
```

**Impact**: Password variables fetched via Strategy C are rendered in plain text in the trigger dialog and could be read by shoulder-surfing.

**Fix sketch**: Mark the Strategy C path as returning unclassified variables — set a flag on `PlanVariableData` indicating `classifiedUnknown=true` and treat all variables from this path as password-obscured in the dialog until the variableContext path succeeds.

---

### F-7 [P1] [Correctness]: Pagination silently truncated — plans capped at 100, repositories at 200, branches at 100/200

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt:41-82`

**Description**: `getPlans()` requests `max-results=100`, `getLinkedRepositories()` requests `max-results=200`, and `getBranches()`/`getPlanBranches()` request `max-results=100`/`200`. None of these endpoints check `startIndex + size < total` (Bamboo's pagination signals) and iterate. For large Bamboo instances (enterprise orgs with hundreds of plans or many branches), results are silently truncated. Tier 3 (`linkedRepositoriesScan`) and Tier 4 (`legacyN1Scan`) plan-detection will miss plans beyond the cap, causing "no plan detected" for users on large instances.

**Evidence**:
```kotlin
// BambooApiClient.kt:41 — hard cap, no pagination loop
return get<BambooPlanListResponse>("/rest/api/latest/plan?expand=plans.plan&max-results=100")
    .map { it.plans.plan }
// BambooApiClient.kt:452 — repository list, same issue
get<BambooLinkedRepositoryListResponse>("/rest/api/latest/repository?max-results=200")
```

**Impact**: On large enterprise Bamboo instances, the auto-detect waterfall may fail for plans listed beyond position 100/200, reporting "no plan found" and forcing users to manually configure plan keys. For the Tier 3 linked-repository scan, the user's repo may not appear in the 200-result window.

**Fix sketch**: Add a pagination loop: check `response.plans.startIndex + response.plans.plan.size < response.plans.size` (or equivalent total field) and iterate with `&start-index=N` until exhausted or a configurable cap (e.g., 2000).

---

### F-8 [P1] [Correctness]: `BambooBuildRunState` polls with hardcoded 15-second interval, ignores SmartPoller and settings

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildRunState.kt:103`

**Description**: `pollBuildStatus()` uses `delay(15_000)` (hardcoded 15 s) in a bare while loop. It does not use `SmartPoller` (which provides 1.5x backoff, visibility gating, jitter), does not read `PluginSettings.state.buildPollIntervalSeconds`, and does not apply the "reset on change" logic that `BuildMonitorService` uses. Additionally the `isProcessTerminating` / `isProcessTerminated` check is performed at loop-top before the `getBuild` call, so if cancellation happens mid-poll the loop iterates one full `delay` before noticing.

**Evidence**:
```kotlin
// BambooBuildRunState.kt:103-129
while (isProcessTerminating.not() && isProcessTerminated.not()) {
    val result = bambooService.getBuild(resultKey)
    ...
    delay(15_000)   // hardcoded, no backoff, no settings awareness
}
```

**Impact**: During very long builds (e.g., integration test suites taking 30+ minutes) the run configuration polls every 15 s regardless of activity, wasting HTTP connections. There is no cancellable interruptibility — a 15-second sleep blocks scope cancellation until the delay expires.

**Fix sketch**: Replace `delay(15_000)` with `delay(settings.buildPollIntervalSeconds * 1000L)` (with a floor of 10 s). Use `withContext(Dispatchers.IO) { delay(...) }` for cancellability.

---

### F-9 [P1] [Correctness]: `getLatestResult` branch-digit heuristic is fragile — branch builds always miss

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt:84-95`

**Description**: `getLatestResult(planKey, branch)` uses `!planKey.last().isDigit()` to decide whether to use the branch-path URL form. If `planKey` already ends in a digit (e.g., a plan key like `PROJ-BUILD2` or a branch plan key like `PROJ-BUILD-523`), the branch parameter is silently ignored and the call is made to the non-branch endpoint. This causes the wrong build to be shown when the master plan key happens to end in a digit.

**Evidence**:
```kotlin
// BambooApiClient.kt:87-93
val path = if (branch != null && !planKey.last().isDigit()) {
    // branch path
} else {
    "/rest/api/latest/result/$planKey/latest?expand=..."
}
```

**Impact**: For projects where the master plan key ends in a digit, branch-specific builds are never fetched. `BuildMonitorService.pollOnce()` will always show the master plan's build, not the current branch's build, causing false-green/false-red states.

**Fix sketch**: Use a more reliable heuristic (e.g., check whether the key matches the branch-plan-key regex `^.+-.+-\d+$` already used in `PlanDetectionService.resolveBranchKey`). If the key already is a branch plan key, skip the branch path; otherwise use it unconditionally.

---

### F-10 [P1] [Resource Leak]: `StageDetailPanel` creates its own `CoroutineScope` without registering in Disposer tree

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt:115`

**Description**: `StageDetailPanel` creates `CoroutineScope(Dispatchers.IO + SupervisorJob())` at `scope` and correctly registers `Disposer.register(parentDisposable, Disposable { scope.cancel() })` in `init`. However, the `scope` is created before the `Disposer.register` call, and the `parentDisposable` is `BuildDashboardPanel` — `panelScope` is cancelled in `BuildDashboardPanel.dispose()`, but `StageDetailPanel.scope` is only cancelled when its own Disposer fires. If `Disposer.register` is called after `parentDisposable` is already disposed (race between panel instantiation and immediate dispose), the scope leaks permanently.

**Evidence**:
```kotlin
// StageDetailPanel.kt:115-116
private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
...
// init block:
Disposer.register(parentDisposable, Disposable { scope.cancel() })
```

**Impact**: Low probability in practice but non-zero: if the tool window is closed immediately after opening (e.g. rapid tab close in testing), the scope fires artifact/log fetches that cannot be cancelled. Minor memory leak.

**Fix sketch**: Accept `cs: CoroutineScope` from the parent (propagating the `panelScope` family), eliminating the second scope entirely.

---

### F-11 [P1] [Correctness]: `defaultRevList` blocks calling thread without timeout — EDT freeze if called on wrong dispatcher

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt:396-404`

**Description**: `defaultRevList()` runs `ProcessBuilder("git", "rev-list", "-n", "10", "HEAD")` and calls `process.waitFor()` with no timeout. If the git repository is on a slow network drive, the process is stuck (corrupted repo, missing HEAD), or the system is under load, `waitFor()` can block indefinitely. This is called from `byChangesetWalk` and `commitWalkPlanKey`, which are called from `autoDetect`, which is called from `resolveBranchPlanAndMonitor` on `panelScope` (IO dispatcher) — safe from EDT, but still blocks the coroutine indefinitely.

**Evidence**:
```kotlin
// PlanDetectionService.kt:399-403
val process = ProcessBuilder("git", "rev-list", "-n", "10", "HEAD")
    .directory(repoRoot.toFile())
    .redirectError(ProcessBuilder.Redirect.DISCARD)
    .start()
val output = process.inputStream.bufferedReader().readText()
process.waitFor()   // no timeout
```

**Impact**: If git is unresponsive (common on VPN-mounted repos or during index operations), the Bamboo plan detection hangs indefinitely. The `panelScope` coroutine is blocked, preventing plan detection from completing or being cancelled.

**Fix sketch**: Add `process.waitFor(10, TimeUnit.SECONDS)` with a `process.destroyForcibly()` fallback on timeout.

---

### F-12 [P1] [Security]: Artifact download writes to caller-supplied `targetFile` with no path validation — partial traversal risk

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt:403-430`  
**Also**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt:493-496`

**Description**: `BambooApiClient.downloadArtifact(artifactUrl, targetFile)` writes the artifact to an arbitrary `java.io.File` supplied by the caller. `StageDetailPanel.downloadArtifact()` constructs `targetFile` as `java.io.File(chosen.path, artifact.name)` where `artifact.name` comes directly from Bamboo's API response. If Bamboo returns an artifact name containing path traversal components (e.g., `../../.bashrc`, `../Library/Application Support/...`), and the user-chosen directory is writable, the artifact is written outside the intended directory. The `FileChooserDescriptor` restricts the user's pick to a directory, but does not canonicalize `artifact.name` before appending.

**Evidence**:
```kotlin
// StageDetailPanel.kt:493-496
val targetFile = java.io.File(chosen.path, artifact.name)
scope.launch {
    val result = bambooService.downloadArtifact(artifact.downloadUrl, targetFile)
```

**Impact**: A maliciously configured Bamboo artifact with name `../../.ssh/authorized_keys` would write the artifact binary to the developer's SSH authorized_keys file. This requires the developer to click "Download" on a crafted artifact from a compromised Bamboo instance.

**Fix sketch**: Canonicalize `artifact.name` before using it as a filename: strip all path separators and directory components (`File(chosen.path, artifact.name.substringAfterLast('/').substringAfterLast('\\')).canonicalFile`, then assert `canonicalFile.parentFile.canonicalPath == chosen.canonicalPath`).

---

### F-13 [P2] [Quality]: `BambooBuildRunState` uses `BambooServiceImpl.getInstance` directly, bypassing `BambooService` interface

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/run/BambooBuildRunState.kt:56`

**Description**: `runBuild()` calls `BambooServiceImpl.getInstance(project)` — the concrete implementation — bypassing the `BambooService` interface that is the canonical cross-module surface. This makes the run-configuration impossible to test without a real `BambooServiceImpl` and couples the run configuration to the implementation class.

**Evidence**:
```kotlin
// BambooBuildRunState.kt:56
val bambooService = BambooServiceImpl.getInstance(project)
```

**Fix sketch**: Inject `BambooService` via `project.getService(BambooService::class.java)` and use only `BambooService` interface methods.

---

### F-14 [P2] [Quality]: Magic numbers — log cap constants differ between `BuildDashboardPanel` and `StageDetailPanel`

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt:1276` (200 KB)  
**Also**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt:163` (50 KB)

**Description**: `BuildDashboardPanel.LOG_RENDER_CAP_BYTES = 200_000` and `StageDetailPanel.MAX_DISPLAY_CHARS = 50_000` are two separate magic-number constants for log truncation, defined in different companion objects with different names, different units (bytes vs chars — which happens to be the same for ASCII-only logs but differs for multi-byte UTF-8), and different values. The two panels render the same log at different truncation points, which is confusing.

**Fix sketch**: Extract a single `BuildLogRenderConfig` object in `:bamboo` with a shared `LOG_DISPLAY_CAP_CHARS` constant and reuse across both panels.

---

### F-15 [P2] [Quality]: `BuildMonitorService` creates a second `BambooApiClient` when `_apiClient` is null — diverges from `BambooServiceImpl`'s cached client

**File**: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt:48-59`

**Description**: `BuildMonitorService.apiClient` lazily constructs its own `BambooApiClient` from `PluginSettings` + `CredentialStore`, independently of `BambooServiceImpl.client`. This means two separate `OkHttpClient` instances (and two separate OkHttp connection pools) exist for the same Bamboo host. If settings change (URL update, timeout change), `BambooServiceImpl.client` updates on next access but `BuildMonitorService._apiClient` is cached forever — diverging timeouts.

**Evidence**:
```kotlin
// BuildMonitorService.kt:48-59
private val apiClient: BambooApiClient get() = _apiClient ?: run {
    BambooApiClient(
        baseUrl = settings.connections.bambooUrl.orEmpty().trimEnd('/'),
        ...
    ).also { _apiClient = it }
}
```

**Fix sketch**: Inject `BambooApiClient` from `BambooServiceImpl.client` via the constructor, or expose `BambooApiClient` from a shared factory/singleton in `:core`'s service layer.

---

## Summary Table

| ID | Severity | Category | File | Title |
|----|----------|----------|------|-------|
| F-1 | P0 | Security | BambooSpecsLocalParser.kt:40 / PlanDetectionService.kt:360 | SnakeYAML unsafe deserializer (RCE via crafted YAML) |
| F-2 | P0 | Security | BambooBuildRunState.kt:81 | Build variable plaintext printed to console — passwords exposed |
| F-3 | P0 | Resource Leak | BuildFailureBridgeStartupActivity.kt:52 | Unmanaged CoroutineScope leaks on project close |
| F-4 | P0 | Security | BambooServiceImpl.kt:59 | No SSRF guard on Bamboo base URL |
| F-5 | P0 | Security | BambooTestResultConverter.kt:149-163 | ReDoS in `[\s\S]*?` regex on build log |
| F-6 | P1 | Security / Correctness | BambooServiceImpl.kt:316 | Strategy C variable fallback loses `isPassword` classification |
| F-7 | P1 | Correctness | BambooApiClient.kt:41-82 | Pagination silently truncated — max 100/200 results, no loop |
| F-8 | P1 | Correctness | BambooBuildRunState.kt:103 | Hardcoded 15s poll interval — ignores SmartPoller and settings |
| F-9 | P1 | Correctness | BambooApiClient.kt:87 | Branch-digit heuristic fragile — wrong builds shown for plan keys ending in digit |
| F-10 | P1 | Resource Leak | StageDetailPanel.kt:115 | Scope created before Disposer registration — race condition |
| F-11 | P1 | Correctness | PlanDetectionService.kt:399 | `defaultRevList` blocks indefinitely — no timeout on `waitFor()` |
| F-12 | P1 | Security | StageDetailPanel.kt:493 | Artifact path traversal via server-controlled `artifact.name` |
| F-13 | P2 | Quality | BambooBuildRunState.kt:56 | Direct `BambooServiceImpl` reference bypasses interface |
| F-14 | P2 | Quality | BuildDashboardPanel.kt:1276 / StageDetailPanel.kt:163 | Divergent log-cap magic numbers (200 KB vs 50 KB) |
| F-15 | P2 | Quality | BuildMonitorService.kt:48 | Second independent `BambooApiClient` — diverges from `BambooServiceImpl` cache |

---

## Top 5 Most Important Findings

1. **F-1** (`BambooSpecsLocalParser.kt:40`, `PlanDetectionService.kt:360`) — **SnakeYAML RCE**. Unsafe `Yaml()` with no `SafeConstructor` enables arbitrary Java class instantiation. Any developer who opens a project with a crafted `bamboo-specs/*.yml` is exploitable. Fix: 2-line change.

2. **F-4** (`BambooServiceImpl.kt:59-61`) — **SSRF / token leakage**. No URL validation before directing Bearer-authenticated HTTP calls. Configuring `http://169.254.169.254` as Bamboo URL exfiltrates the token to cloud metadata or internal services.

3. **F-5** (`BambooTestResultConverter.kt:149-163`) — **ReDoS on build log**. Three runtime-constructed regexes with `[\s\S]*?` lazy-any applied to 29 KB+ build logs. A test named with common words can freeze log rendering.

4. **F-2** (`BambooBuildRunState.kt:81`) — **Secret plaintext in console**. Every variable including deploy tokens/passwords printed unconditionally to the Run console. No `isPassword` guard in this code path.

5. **F-3** (`BuildFailureBridgeStartupActivity.kt:52-54`) — **Coroutine scope leak**. A `CoroutineScope(SupervisorJob())` created in `ProjectActivity.execute()` is never cancelled. On large enterprises with multiple project open/close cycles, scopes accumulate, each holding a live subscription to the event bus.

---

## Files Audited

| File | Lines | Status |
|------|-------|--------|
| `api/BambooApiClient.kt` | 590 | Fully audited |
| `api/dto/BambooDtos.kt` | 453 | Fully audited |
| `listeners/BuildFailureBridgeStartupActivity.kt` | 153 | Fully audited |
| `model/BuildError.kt` | — | Audited |
| `model/BuildState.kt` | — | Audited |
| `run/BambooBuildConfigurationFactory.kt` | — | Audited |
| `run/BambooBuildConfigurationType.kt` | — | Audited |
| `run/BambooBuildRunConfiguration.kt` | 182 | Fully audited |
| `run/BambooBuildRunState.kt` | 155 | Fully audited |
| `service/BambooServiceImpl.kt` | 1044 | Fully audited |
| `service/BambooSpecsLocalParser.kt` | 52 | Fully audited |
| `service/BambooTestResultConverter.kt` | 190 | Fully audited |
| `service/BuildLogParser.kt` | 79 | Fully audited |
| `service/BuildMonitorService.kt` | 403 | Fully audited |
| `service/PlanDetectionService.kt` | 405 | Fully audited |
| `settings/BuildsAndHealthChecksConfigurable.kt` | — | Audited |
| `ui/BuildDashboardActionGate.kt` | — | Audited |
| `ui/BuildDashboardPanel.kt` | 1436 | Fully audited |
| `ui/BuildPlanResolutionPolicy.kt` | — | Audited |
| `ui/BuildTabProvider.kt` | — | Audited |
| `ui/FilePathHyperlinkFilter.kt` | — | Audited |
| `ui/ManualStageDialog.kt` | 663 | Fully audited |
| `ui/PrBar.kt` | — | Audited |
| `ui/StageDetailPanel.kt` | 735 | Fully audited |
| `ui/StageListPanel.kt` | — | Audited |
| `ui/StageRunnabilityPolicy.kt` | — | Audited |
| `workflow/ChainKeyResolverImpl.kt` | — | Audited |
| `workflow/LatestBuildLookupImpl.kt` | — | Audited |
| **core/services/HttpFormPost.kt** | 202 | Fully audited (referenced) |

---

## Items Confirmed Clean (no finding)

- **runBlocking**: Zero occurrences in production sources. Ban is effective.
- **Token in logs**: `Bearer` and token values are never logged. Auth failures log only status codes.
- **X-Atlassian-Token**: Applied correctly to all write operations (POST via `postForm`, PUT, DELETE) via the central `HttpFormPost.kt` helper and inline `.header()` calls.
- **triggerBuild form encoding**: Variables correctly sent as `bamboo.variable.<k>=<v>` form fields via `postForm`, not JSON. The P0 memory item (silently dropped vars) is fixed.
- **Password field masking**: `ManualStageDialog.doOKAction()` correctly redacts password variables before logging (line 622-629). `JBPasswordField` is used for `isPassword=true` variables.
- **BUILD vs BUILD_READ**: Not directly testable from source alone; no evidence of permission conflation in service layer.
- **XSS in JCEF**: Log content is rendered via IntelliJ's `ConsoleView` (native Swing), not via JCEF/WebView. No unescaped HTML interpolation into JCEF panels found in `:bamboo`.
- **Parameter injection in form body**: `postForm` uses OkHttp's `FormBody.Builder().add(k, v)` which URL-encodes key and value independently — no injection risk.

---

## Enterprise-Readiness Verdict

**Not enterprise-ready**: Two critical RCE/SSRF vectors (F-1, F-4), one plaintext secret exposure (F-2), one indefinite coroutine leak (F-3), and a ReDoS risk on build logs (F-5) must be resolved before this module is deployed in a security-sensitive enterprise environment. The write-path correctness (X-Atlassian-Token, form encoding, password masking in dialog) is well-implemented; the gaps are concentrated in input validation, YAML parsing safety, and resource lifecycle.
