# :sonar Module — Security & Correctness Audit

**Date:** 2026-05-24
**Auditor:** Claude (max-effort, read-only)
**Scope:** `sonar/src/main/kotlin/`
**Files audited:** 34 Kotlin source files

---

## Findings

### F-1 [P0] [Security / XSS]: Unescaped server-controlled strings inserted into Swing HTML labels
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueDetailPanel.kt:192-193`
**Description**: `issue.message` and `hotspot.message` are interpolated directly into `<html>` label text without HTML escaping. A SonarQube server (or a compromised/attacker-controlled Sonar instance) could return issue messages or rule names containing HTML tags (e.g., `<b>`, `<font>`, `<a href=...>`) that are rendered by Swing's HTML renderer. While JLabel does not execute JavaScript, malformed HTML can break layout, inject misleading text, or cause attribute-injection into font/color tags. The same applies to `rule.name` and `rule.remediation` at line 393.
**Evidence**:
```kotlin
// IssueDetailPanel.kt:192-193
titleLabel.text = "<html><font color='$htmlColor'><b>[${issue.severity}]</b></font> " +
    "$typeStr — ${issue.message}</html>"
// IssueDetailPanel.kt:393
ruleInfoLabel.text = "<html><b>${rule.name}</b>$remediation<br/><i>${cleanDesc}</i></html>"
```
**Impact**: Attacker-controlled Sonar response can inject HTML into IDE panels. The `HtmlEscape.escapeHtml()` utility already exists in `:core` (used by `HtmlReportRenderer`) but is not applied here.
**Fix sketch**: Wrap all server-sourced strings before HTML interpolation with `HtmlEscape.escapeHtml(issue.message)`, `HtmlEscape.escapeHtml(rule.name)`, etc. Also applies to `IssueListPanel.kt:405,461` where `issue.message` and `hotspot.message` appear in HTML cell renderer labels.

---

### F-2 [P0] [Security]: Bearer token forwarded on HTTP redirects (no followRedirects=false)
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt:61-75` / `core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpClientFactory.kt`
**Description**: OkHttp's default `followRedirects(true)` is in effect for the shared `SonarApiClient`. The `AuthInterceptor` adds `Authorization: Bearer <token>` on every request, including redirected ones. OkHttp by default does NOT strip the `Authorization` header on cross-host redirects but DOES resend it on same-host redirects. If the Sonar base URL is configured as HTTP (the settings only warns, does not block) and a redirect is issued to a different host, the token is forwarded. `AuthTestService` in core explicitly uses `followRedirects(false)` for this reason, but `HttpClientFactory.clientFor(ServiceType.SONARQUBE)` does not.
**Evidence**:
```kotlin
// core/http/AuthInterceptor.kt:23,27 — added on every outgoing request including redirects
AuthScheme.BEARER -> "Bearer $token"
.header("Authorization", headerValue)
// core/http/AuthTestService.kt:22 — correct pattern not applied to production client
.followRedirects(false)
// core/settings/ConnectionsConfigurable.kt:176 — warning only, not blocking
warning("Using HTTP is insecure...")
```
**Impact**: Token exfiltration on misconfigured HTTP Sonar URLs with redirect-capable intermediary or attacker-in-the-middle.
**Fix sketch**: Add `.followRedirects(false)` to `HttpClientFactory.clientFor()` for all auth-bearing service types, or at minimum for `ServiceType.SONARQUBE`. Handle 3xx manually where legitimate redirects are needed.

---

### F-3 [P0] [Security / SSRF]: Sonar base URL accepts non-HTTPS schemes without blocking
**File**: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/ConnectionsConfigurable.kt:176`
**Description**: The settings UI only issues a `warning()` when the Sonar URL uses HTTP rather than HTTPS — it does not block saving. `SonarDataService.apiClient` uses the URL directly from settings (`settings.connections.sonarUrl`) without scheme validation. There is no URL allowlist or localhost/private-IP guard at the HTTP-call layer. An enterprise admin who misconfigures the URL to `http://internal-service` can cause the plugin to make credentialed requests to arbitrary internal services (SSRF).
**Evidence**:
```kotlin
// SonarDataService.kt:62-63
val url = settings.connections.sonarUrl.orEmpty().trimEnd('/')
if (url.isBlank()) return null
// ConnectionsConfigurable.kt:176 — warning only
warning("Using HTTP is insecure. Credentials will be sent in plaintext...")
```
**Impact**: SSRF against internal infrastructure; credential leakage to non-Sonar endpoints.
**Fix sketch**: Validate scheme is `https://` (or `http://localhost`) before constructing `SonarApiClient`; reject unknown schemes at the `apiClient` getter level, not just the UI.

---

### F-4 [P0] [Resource Leak / Threading]: `SonarDataService.apiClient` getter has an unsynchronized check-then-act race
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt:61-75`
**Description**: `cachedApiClient` and `cachedSonarUrl` are `@Volatile` but the getter reads `cachedSonarUrl`, compares, then writes both — this is a non-atomic check-then-act. Two coroutines can both enter the `if` branch simultaneously, both create a new `SonarApiClient` (and its underlying OkHttp connection pool), and both write to the volatile. The stale client is abandoned with its `OkHttpClient` connection pool never closed, leaking file descriptors on every URL change.
**Evidence**:
```kotlin
// SonarDataService.kt:61-74
private val apiClient: SonarApiClient? get() {
    val url = settings.connections.sonarUrl.orEmpty().trimEnd('/')
    if (url.isBlank()) return null
    if (url != cachedSonarUrl || cachedApiClient == null) {  // NOT atomic
        cachedSonarUrl = url
        cachedApiClient = SonarApiClient(...)               // second writer wins; first client leaked
    }
    return cachedApiClient
}
```
**Impact**: Connection pool leak (file descriptors, threads) on each URL change; potential inconsistency between `cachedSonarUrl` and `cachedApiClient` under race.
**Fix sketch**: Use `@Synchronized` or a `Mutex`-guarded `suspend` initializer; or make `apiClient` a `lazy` computed once and invalidated explicitly on URL change.

---

### F-5 [P0] [Correctness]: `refreshWith()` launches child coroutines on the service `scope` instead of `coroutineScope {}`
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt:269-299`
**Description**: `refreshWith` is a `suspend` function that calls `scope.async { ... }` (service-level scope) rather than `coroutineScope { async { ... } }`. This means: (1) if the calling coroutine is cancelled (e.g., the refresh debounce job is cancelled because a new refresh starts), the in-flight `async` children are NOT automatically cancelled — they run to completion and write stale data to `_stateFlow`; (2) if a child `async` throws an uncaught exception, `SupervisorJob` absorbs it silently instead of propagating to `refreshWith`'s caller.
**Evidence**:
```kotlin
// SonarDataService.kt:269-270
val overallIssuesDeferred = scope.async { client.getIssuesWithPaging(projectKey, branch) }
val newCodeIssuesDeferred = scope.async { client.getIssuesWithPaging(projectKey, branch, inNewCodePeriod = true) }
```
**Impact**: Stale data written to state after cancellation; silent swallowing of unexpected errors from child coroutines.
**Fix sketch**: Replace `scope.async { }` with `coroutineScope { val d = async { }; ... d.await() }` inside `refreshWith` so children are structurally scoped to the suspend call and cancelled when the caller is cancelled.

---

### F-6 [P1] [Correctness / Security]: `SonarCoverageAnnotator.getFileCoverageInformationString` uses wrong map key
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/coverage/SonarCoverageAnnotator.kt:40`
**Description**: `SonarState.fileCoverage` is a `Map<String, FileCoverageData>` keyed by the Sonar **relative path** (e.g., `src/main/kotlin/Foo.kt`) returned by `CoverageMapper.mapMeasures` (`comp.path`). However, `SonarCoverageAnnotator.getFileCoverageInformationString` looks up by `vFile.path`, which is an **absolute path** (e.g., `/Users/dev/project/src/main/kotlin/Foo.kt`). The lookup always returns `null`, so the annotator never shows coverage strings in the Coverage tool-window for any file.
**Evidence**:
```kotlin
// SonarCoverageAnnotator.kt:40
val fileCoverage = sonarService.stateFlow.value.fileCoverage[vFile.path]  // absolute path
// CoverageMapper.kt:17,23 — keyed by comp.path (Sonar relative path)
val path = comp.path!!
path to FileCoverageData(filePath = path, ...)
```
**Impact**: Coverage tool-window column ("% lines", "% branches") is always blank — silent feature regression.
**Fix sketch**: Resolve `vFile.path` to a repo-relative path using `SonarPathResolver.resolveContext(project, vFile).relativePath` before the lookup, matching the key format used by `CoverageMapper`.

---

### F-7 [P1] [Correctness]: Issues API not paginated — silent truncation at 500
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt:134-141`
**Description**: `buildIssuesSearchPath` hard-codes `ps=500`. SonarQube's `/api/issues/search` caps response at 500 regardless of `ps`. For projects with more than 500 open issues, only the first 500 are fetched; the remaining issues are invisible to annotations, the fix intention, and the agent. The `paging.total` is tracked and a warning shown in the UI, but annotations in the editor (`SonarIssueAnnotator`) only see the truncated `state.activeIssues` list and silently miss issues beyond position 500.
**Evidence**:
```kotlin
// SonarApiClient.kt:138
append("&resolved=false&ps=500")
// No pagination loop unlike getMeasures which loops up to MAX_MEASURES_PAGES=10
```
**Impact**: False-negative annotations and fix intentions for issues beyond position 500; agent triage misses high-severity issues that sort late.
**Fix sketch**: Add a pagination loop analogous to `getMeasures` (capped at e.g. 10 pages × 500 = 5000); or at minimum document the truncation in `SonarAnnotationInput` and annotator to prevent users believing the file is clean.

---

### F-8 [P1] [Correctness]: Security hotspots are truncated at 500 with no pagination
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt:288-300`
**Description**: `getSecurityHotspots` fetches a single page of at most 500 hotspots and returns without checking `paging.total`. Unlike `getMeasures` there is no pagination loop. Projects with >500 hotspots silently drop the remainder. No truncation warning is shown in the security hotspots section of the dashboard.
**Evidence**:
```kotlin
// SonarApiClient.kt:294-296
append("/api/hotspots/search?project=")
append(URLEncoder.encode(projectKey, "UTF-8"))
append("&ps=500")  // no loop, no paging.total check
```
**Impact**: Silent data loss for large projects; security posture underreported to users and agent.
**Fix sketch**: Add a pagination loop matching the pattern in `getMeasures`; check `paging.total` and continue fetching until all hotspots are retrieved (or cap at a reasonable max with a visible warning).

---

### F-9 [P1] [Threading]: `QualityDashboardPanel` uses `Dispatchers.EDT` scope but double-dispatches via `invokeLater`
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt:54, 316-321`
**Description**: The panel creates `scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)`. Its `stateFlow.collect` coroutine runs on EDT, then calls `invokeLater { updateUI(state) }` — scheduling a second EDT dispatch inside an already-EDT coroutine. This double-dispatch is redundant and can cause out-of-order execution if the `invokeLater` queue and the EDT coroutine dispatcher disagree on ordering. Additionally, using a self-created `CoroutineScope(SupervisorJob() + ...)` is against the project's "Service-injected scope" convention (per core CLAUDE.md) — though panels are not `@Service`, the cancellation in `dispose()` must be verified to fire.
**Evidence**:
```kotlin
// QualityDashboardPanel.kt:54
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
// QualityDashboardPanel.kt:316-321
scope.launch {
    dataService.stateFlow.debounce(300).collect { state ->
        invokeLater { updateUI(state) }  // redundant: already on EDT
    }
}
```
**Impact**: UI update ordering is non-deterministic; theoretical stale render if invokeLater fires after a subsequent direct EDT update.
**Fix sketch**: Remove the inner `invokeLater`; since the coroutine already runs on `Dispatchers.EDT`, `updateUI(state)` can be called directly inside `collect`.

---

### F-10 [P1] [Correctness]: `SonarDataService.scope.async` children escape debounce cancellation
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt:147-154, 269`
**Description**: `refreshForBranch` cancels the previous `refreshDebounceJob` but the new `refreshDebounceJob` eventually calls `refreshWith`, which launches children on `scope.async`. If `refreshForBranch` is called again before `refreshWith` completes, the debounce job is cancelled — but the `scope.async` children from the prior `refreshWith` invocation continue to run and will write their (stale, wrong-branch) data to `_stateFlow` when they complete. This is a stale-write race.
**Evidence**:
```kotlin
// SonarDataService.kt:149-154
fun refreshForBranch(branch: String, projectKey: String) {
    refreshDebounceJob?.cancel()  // cancels the debounce delay, NOT the async children
    refreshDebounceJob = scope.launch {
        delay(500)
        val client = apiClient ?: return@launch
        refreshWith(client, projectKey, branch)  // scope.async inside, not cancellable
    }
}
```
**Impact**: Stale Sonar data from a previous branch/project can overwrite fresh data if the user switches scopes quickly (e.g., switches PR focus rapidly).
**Fix sketch**: Track an in-flight `refreshJob: Job?` and cancel it in `refreshForBranch` before launching the new one; use `coroutineScope { }` inside `refreshWith` so children are tied to the job's lifecycle.

---

### F-11 [P1] [Correctness / XSS]: Rule description HTML strip regex is incomplete
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueDetailPanel.kt:391`
**Description**: `rule.description` is sanitized with `desc.replace(Regex("<[^>]*>"), "")` before insertion into a `<html>` label. This regex fails on: (1) attributes containing `>` (e.g., `<div onclick="a>b">`), (2) multi-line tags, (3) `&lt;`/`&gt;` HTML entities which survive and render as angle brackets. After stripping, the cleaned string is still inserted without HTML-encoding special characters (`&`, `<`, `>`), so residual entity sequences or `&` in description text can cause Swing HTML parser errors or character corruption.
**Evidence**:
```kotlin
// IssueDetailPanel.kt:387-393
val desc = rule.description.take(300)...
val cleanDesc = desc.replace(Regex("<[^>]*>"), "")  // incomplete strip
val remediation = rule.remediation?.let { " • Remediation: $it" } ?: ""
ruleInfoLabel.text = "<html><b>${rule.name}</b>$remediation<br/><i>${cleanDesc}</i></html>"
```
**Impact**: Rule name/remediation can inject unescaped HTML; description sanitization can be bypassed by attribute-embedded `>`.
**Fix sketch**: Use `HtmlEscape.escapeHtml(rule.name)` and `HtmlEscape.escapeHtml(cleanDesc)` after stripping tags; alternatively use `StringUtil.escapeXmlEntities` from the IJ platform.

---

### F-12 [P1] [Correctness]: `IssueDetailPanel.buildCodeSnippet` reads local file with `project.basePath` ignoring multi-repo
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueDetailPanel.kt:308-322`
**Description**: `buildCodeSnippet` derives `basePath` from `owningRepo?.localVcsRootPath` falling back to `project.basePath`. However, for hotspot items, `renderHotspot` calls `loadCodeSnippet(filePath, hotspot.line, hotspotProjectKey)` where `filePath = hotspot.component.substringAfterLast(':')`. If the owning repo lookup by `projectKey` fails (no repo configured with that Sonar key), the fallback to `project.basePath` produces an incorrect absolute path in multi-repo projects, returning "File not found" instead of the actual file.
**Evidence**:
```kotlin
// IssueDetailPanel.kt:309-317
val owningRepo = settings.getRepos().firstOrNull { it.sonarProjectKey == projectKey }
val basePath = owningRepo?.localVcsRootPath?.takeIf { it.isNotBlank() }
    ?: project.basePath                          // fallback incorrect for multi-repo
    ?: return "Project base path not available"
val file = File(basePath, relativePath)
if (!file.exists()) return "File not found: $relativePath"
```
**Impact**: Code snippet shows "File not found" for hotspots/issues in secondary repos in multi-repo projects.
**Fix sketch**: Use `SonarPathResolver` + `RepoContextResolver` to resolve the file path the same way `CoverageLineMarkerProvider` does.

---

### F-13 [P1] [Correctness]: `IssueListPanel.navigateToIssue` and `IssueDetailPanel.navigateToItem` use `project.basePath` for hotspot navigation
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueDetailPanel.kt:401-412` / `IssueListPanel.kt:304-313`
**Description**: `navigateToItem` for hotspots uses `project.basePath` directly to resolve the file path: `File(basePath, filePath).path`. For multi-repo projects this is always the aggregator root, not the owning repo root. Files in secondary repos will not be found and navigation silently does nothing.
**Evidence**:
```kotlin
// IssueDetailPanel.kt:405-408
val basePath = project.basePath ?: return
val filePath = item.hotspot.component.substringAfterLast(':')
val vf = LocalFileSystem.getInstance().findFileByPath(File(basePath, filePath).path) ?: return
```
**Impact**: "Open in Editor" and double-click navigation fail silently for hotspots in non-primary repos.
**Fix sketch**: Resolve via `RepoContextResolver` using the hotspot's `projectKey` (extracted from `component.substringBeforeLast(':')`) — same as `IssueDetailPanel.buildCodeSnippet` attempts but incompletely.

---

### F-14 [P2] [Quality]: `SonarDataService` uses `CoroutineScope(SupervisorJob() + ...)` instead of platform-injected scope
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt:37`
**Description**: Per the core `CLAUDE.md` "Service-injected scope" convention, `@Service` constructors should take `cs: CoroutineScope` (IJ 2024.1+ pattern) so the platform manages lifecycle. `SonarDataService` allocates its own `CoroutineScope(SupervisorJob() + Dispatchers.IO)` and cancels it in `dispose()`. While the `dispose()` is correctly wired (the tool-window `setDisposer` cascade reaches it), this pattern bypasses platform lifecycle management and is inconsistent with the established project convention.
**Evidence**:
```kotlin
// SonarDataService.kt:37
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```
**Impact**: Pattern divergence from convention; risk of scope outliving disposal if the dispose chain is ever broken.
**Fix sketch**: Migrate to `@Service constructor(project: Project, cs: CoroutineScope)` and use `cs` directly, as done by `HealthCheckService` and `DefaultBranchResolver`.

---

### F-15 [P2] [Quality]: `getIssuesPaged` in `SonarServiceImpl` fetches ALL issues then paginates client-side
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt:592-613`
**Description**: `getIssuesPaged` calls `getIssuesWithPaging` (which fetches up to 500 issues from server), then applies client-side pagination with `drop(startIndex).take(pageSize)`. For page 2+, this fetches 500 issues from the server and discards most of them. The `total` from `paging.total` is correctly passed through, but server-side paging (`&p=2`) is never used — so pages beyond the first 500 always return empty.
**Evidence**:
```kotlin
// SonarServiceImpl.kt:592
val result = api.getIssuesWithPaging(projectKey, branch = branch, inNewCodePeriod = inNewCodePeriod)
// ...
val startIndex = (page - 1) * pageSize
val pagedIssues = allIssues.drop(startIndex).take(pageSize)  // client-side paging on server-fetched 500
```
**Impact**: Agent's `getIssuesPaged` tool returns empty on any page > (500/pageSize) even though the server has more; wasted network on every page-2+ call.
**Fix sketch**: Pass `&p=$page&ps=$pageSize` as server-side params to the Sonar API rather than emulating paging client-side.

---

### F-16 [P2] [Quality]: `calculateOverallCoverage` computes unweighted average of per-file percentages
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt:599-603`
**Description**: When `projectHealth.lineCoverage` is null (project-level measures unavailable), coverage falls back to `fileCoverage.values.map { it.lineCoverage }.average()` — an unweighted mean of per-file percentages. A project with one 10-line file at 100% and one 10,000-line file at 0% would show 50% instead of ~0%. SonarQube's weighted coverage is already fetched via `getProjectMeasures` and preferred; the fallback is only a correctness issue when that call fails.
**Evidence**:
```kotlin
// SonarDataService.kt:601-603
val avgLine = fileCoverage.values.map { it.lineCoverage }.average()
val avgBranch = fileCoverage.values.map { it.branchCoverage }.average()
return CoverageMetrics(avgLine, avgBranch)
```
**Impact**: Misleading coverage percentage displayed when project-level measures fail; not a security issue.
**Fix sketch**: Use `lines_to_cover` and `uncovered_lines` from each file to compute a weighted average: `(sum(linesToCover - uncoveredLines) / sum(linesToCover)) * 100`.

---

### F-17 [P2] [Quality]: Magic strings for metric names throughout codebase
**File**: Multiple files — `SonarApiClient.kt:26-32`, `SonarDataService.kt:586-595`, `CoverageMapper.kt:24-35`
**Description**: Metric key strings (`"sqale_index"`, `"new_coverage"`, `"line_coverage"`, `"reliability_rating"`, etc.) are repeated as raw string literals across `DEFAULT_METRIC_KEYS`, `mapProjectHealth`, `getCoverage`, and `CoverageMapper`. No central `SonarMetrics` enum or constants object exists. A typo in any one site (e.g., `"line_coverage"` vs `"lines_coverage"`) silently returns `null` values.
**Evidence**:
```kotlin
// SonarApiClient.kt:26-32 vs SonarDataService.kt:590 vs CoverageMapper.kt:25
"coverage,line_coverage,branch_coverage..." // in DEFAULT_METRIC_KEYS
byMetric["sqale_index"]  // in mapProjectHealth
measures["line_coverage"]  // in CoverageMapper
```
**Impact**: Maintenance burden; silent data loss on metric key typos.
**Fix sketch**: Extract a `SonarMetricKey` constants object with `const val LINE_COVERAGE = "line_coverage"` etc., used everywhere.

---

### F-18 [P2] [Quality]: `SonarCoverageEngine.createCoverageEnabledConfiguration` throws unconditionally
**File**: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/coverage/SonarCoverageEngine.kt:42-46`
**Description**: `createCoverageEnabledConfiguration` throws `UnsupportedOperationException`. The comment says "should never be called because `isApplicableTo` returns false." However, the platform may call this method reflectively or through extension points without checking `isApplicableTo` first. If called, it produces an unhandled exception that surfaces as an IDE error dialog.
**Evidence**:
```kotlin
// SonarCoverageEngine.kt:44-45
throw UnsupportedOperationException(
    "SonarQube coverage does not support run-configuration-based collection")
```
**Impact**: Potential IDE error dialog if the platform calls this unexpectedly.
**Fix sketch**: Return a no-op `CoverageEnabledConfiguration` or log and return null rather than throwing.

---

## Summary Table

| ID | Severity | Category | File | Key Risk |
|----|----------|----------|------|----------|
| F-1 | P0 | Security/XSS | IssueDetailPanel.kt:192,393 | Server HTML injected into Swing labels |
| F-2 | P0 | Security | HttpClientFactory / AuthInterceptor | Bearer token forwarded on redirects |
| F-3 | P0 | Security/SSRF | ConnectionsConfigurable.kt:176 | HTTP Sonar URL not blocked, SSRF possible |
| F-4 | P0 | Resource Leak | SonarDataService.kt:61-74 | apiClient getter race → OkHttp pool leak |
| F-5 | P0 | Correctness | SonarDataService.kt:269 | scope.async not cancelled on debounce cancel |
| F-6 | P1 | Correctness | SonarCoverageAnnotator.kt:40 | Wrong map key → coverage always blank |
| F-7 | P1 | Correctness | SonarApiClient.kt:138 | Issues truncated at 500, no pagination |
| F-8 | P1 | Correctness | SonarApiClient.kt:296 | Hotspots truncated at 500, no pagination |
| F-9 | P1 | Threading | QualityDashboardPanel.kt:54,320 | Double EDT dispatch in state collector |
| F-10 | P1 | Correctness | SonarDataService.kt:147,269 | Stale data written after debounce cancel |
| F-11 | P1 | Security/XSS | IssueDetailPanel.kt:391,393 | Incomplete HTML strip + unescaped rule.name |
| F-12 | P1 | Correctness | IssueDetailPanel.kt:308-322 | Multi-repo code snippet path incorrect |
| F-13 | P1 | Correctness | IssueDetailPanel.kt:405, IssueListPanel.kt:307 | Hotspot navigation fails in multi-repo |
| F-14 | P2 | Quality | SonarDataService.kt:37 | Self-owned scope vs platform CS pattern |
| F-15 | P2 | Quality | SonarServiceImpl.kt:592-613 | Client-side paging defeats server pagination |
| F-16 | P2 | Quality | SonarDataService.kt:599-603 | Unweighted coverage fallback |
| F-17 | P2 | Quality | Multiple | Magic metric key strings |
| F-18 | P2 | Quality | SonarCoverageEngine.kt:44 | Unconditional throw in engine method |

---

## Severity Counts

| Severity | Count |
|----------|-------|
| P0 | 5 |
| P1 | 8 |
| P2 | 5 |
| **Total** | **18** |

---

## Top 5 Most Critical

1. **F-1 [P0]** `IssueDetailPanel.kt:192-193, 393` — Unescaped server-controlled strings in Swing HTML labels (XSS via SonarQube issue messages/rule names). `HtmlEscape` utility exists in `:core` and is unused here.

2. **F-5 [P0]** `SonarDataService.kt:269` — `scope.async` in `refreshWith` means async children are NOT cancelled when the debounce job is cancelled → stale data overwrites fresh state.

3. **F-4 [P0]** `SonarDataService.kt:61-74` — `apiClient` getter has an unsynchronized check-then-act race that leaks OkHttp connection pools on each URL change.

4. **F-10 [P1]** `SonarDataService.kt:147-154` — Debounce cancellation only cancels the delay, not in-flight HTTP children from a prior `refreshWith` → stale branch data written to state under rapid PR focus switching.

5. **F-6 [P1]** `SonarCoverageAnnotator.kt:40` — Coverage tool-window always shows blank because the annotator keys by absolute `vFile.path` but the state map is keyed by Sonar relative path — the lookup always returns `null`.

---

## Files Audited

```
sonar/src/main/kotlin/com/workflow/orchestrator/sonar/
├── api/
│   ├── SonarApiClient.kt
│   └── dto/SonarDtos.kt
├── coverage/
│   ├── SonarCoverageAnnotator.kt
│   ├── SonarCoverageEngine.kt
│   ├── SonarCoverageRunner.kt
│   └── SonarCoverageSuite.kt
├── editor/
│   ├── SonarFixIntentionAction.kt
│   └── SonarIssueLookup.kt
├── model/
│   ├── SonarModels.kt
│   └── SonarState.kt
├── service/
│   ├── CoverageMapper.kt
│   ├── GradleSonarKeyDetector.kt
│   ├── IssueMapper.kt
│   ├── SonarDataService.kt
│   ├── SonarKeyDetector.kt
│   ├── SonarProjectPickerLauncherImpl.kt
│   └── SonarServiceImpl.kt
├── settings/
│   └── CodeQualityConfigurable.kt
├── ui/
│   ├── CoverageLineMarkerProvider.kt
│   ├── CoveragePreviewPanel.kt
│   ├── CoverageTablePanel.kt
│   ├── CoverageThresholds.kt
│   ├── GateStatusBanner.kt
│   ├── ImpactRendering.kt
│   ├── IssueDetailPanel.kt
│   ├── IssueListPanel.kt
│   ├── OverviewPanel.kt
│   ├── QualityDashboardPanel.kt
│   ├── QualityListItem.kt
│   ├── QualityTabProvider.kt
│   ├── SonarIssueAnnotator.kt
│   └── SonarProjectPickerDialog.kt
└── util/
    ├── SonarPathResolver.kt
    └── SonarRatingUtils.kt
```

---

## Verdict

The `:sonar` module is **not enterprise-ready** in its current state: it has 5 P0 issues including token-forwarding on redirects (F-2), an HTML injection surface on every issue message rendered in the UI (F-1), a race condition leaking OkHttp connection pools (F-4), and stale-write concurrency bugs in the refresh pipeline (F-5, F-10). The `SonarCoverageAnnotator` key mismatch (F-6) means the platform coverage integration is completely silently broken. Address F-1 through F-5 before any production deployment.
