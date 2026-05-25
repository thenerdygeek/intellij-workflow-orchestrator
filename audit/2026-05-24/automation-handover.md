# Security & Correctness Audit: :automation + :handover
**Date:** 2026-05-24  
**Auditor:** Claude Sonnet 4.6 (max-effort, read-only)  
**Scope:** automation/src/main/kotlin/ + handover/src/main/kotlin/

---

## Executive Summary

Both modules are largely well-structured. No `runBlocking` violations, no AI pre-review leftovers, no SSRF in active code paths. The most critical issues are:
1. **P0:** Unguarded single JDBC `Connection` shared across multiple coroutines on `Dispatchers.IO` — data corruption / `SQLITE_BUSY` crash under concurrent load.
2. **P0:** `ResultSet` not closed in `getActiveQueueEntries` — connection-level resource leak that accumulates across IDE sessions.
3. **P0:** `coroutineScope { cache.computeIfAbsent(key) { async { … } } }` — `async` is launched in `coroutineScope`'s scope but stored in a `ConcurrentHashMap`; if the calling coroutine is cancelled before `.await()`, the `Deferred` remains in the map orphaned and its parent `coroutineScope` block cancels propagating outward.
4. **P1:** Docker tag payload logged at DEBUG level — sensitive service→tag mappings written to `idea.log` on debug builds.
5. **P1:** `DOCKER_TAG_REGEX` applied to potentially large build logs without a line-length cap — linear but O(N) scan on megabyte-scale logs every 15 s poll tick.

---

## Findings

### F-1 [P0] [Resource Leak]: ResultSet not closed in `getActiveQueueEntries` [AUTOMATION]
**File**: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt:161`  
**Description**: The `PreparedStatement` is closed via `.use{}` but the `ResultSet` `rs` obtained from `stmt.executeQuery()` is never closed explicitly. In SQLite JDBC, closing the Statement also closes its ResultSet, so the immediate risk is low — however if any exception is thrown inside the `while (rs.next())` loop (e.g., `json.decodeFromString` throws on corrupt `variables_json`), the lambda exits without returning, and the `PreparedStatement.use{}` block propagates the exception — the ResultSet is still implicitly closed. But the `integrityCheck()` method at line 216 has the same pattern with no `.use{}` on the `Statement` either; the `ResultSet` from `createStatement().use { stmt -> stmt.executeQuery(...) }` is never closed.  
**Evidence**:
```kotlin
// TagHistoryService.kt:214-218
fun integrityCheck(): Boolean {
    return connection.createStatement().use { stmt ->
        val rs = stmt.executeQuery("PRAGMA quick_check")
        rs.next() && rs.getString(1) == "ok"
    }
}
```
**Impact**: `rs` is not closed; SQLite JDBC keeps the cursor open until GC collects it. Under repeated calls (e.g., startup activity + periodic triggers), this accumulates open cursors against a single `Connection` that is not thread-safe (see F-2).  
**Fix sketch**: Wrap `rs` in `.use{}` in both `integrityCheck` and `getActiveQueueEntries`: `stmt.executeQuery().use { rs -> … }`.

---

### F-2 [P0] [Threading]: SQLite `Connection` shared across multiple `Dispatchers.IO` threads with no synchronization [AUTOMATION]
**File**: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt:36-55`  
**Description**: `TagHistoryService` holds a single `connection: Connection` initialized by `lazy {}`. `QueueService.pollOnce()` calls `tagHistoryService.updateQueueEntryStatus()` for every entry on `Dispatchers.IO`, while `restoreFromPersistence()` calls `getActiveQueueEntries()` also on IO. SQLite JDBC connections are not thread-safe by default; concurrent reads/writes to the same `Connection` object from different threads produce `SQLITE_BUSY`, data corruption, or NPE in the driver.  
**Evidence**:
```kotlin
// TagHistoryService.kt:36-37
private val connection: Connection by lazy {
    // no synchronization guard
    ...
    DriverManager.getConnection("jdbc:sqlite:$dbPath")
```
```kotlin
// QueueService.kt:353-377 (pollOnce)
// Called from Dispatchers.IO, iterates entries and calls:
tagHistoryService.updateQueueEntryStatus(entry.id, ...)  // concurrent writes
```
**Impact**: Two simultaneous poll tick calls can race on `connection.prepareStatement()`. SQLITE WAL mode reduces file-level contention but does not make the JDBC `Connection` object thread-safe. Worst case: silent data corruption in the `queue_entries` table, lost queue entries after IDE restart.  
**Fix sketch**: Either (a) add `@Synchronized` to every `TagHistoryService` method, or (b) wrap all DB ops in a `Mutex.withLock {}` coroutine guard, or (c) use a `Dispatchers.IO.limitedParallelism(1)` single-thread executor for all DB calls.

---

### F-3 [P0] [Resource Leak / Correctness]: `coroutineScope { computeIfAbsent { async {} } }` — orphaned `Deferred` on cancellation [HANDOVER]
**File**: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverAiSummaryCache.kt:154-171`  
**Description**: `getOrCompute` wraps `cache.computeIfAbsent(key) { async { … } }` inside `coroutineScope { … }`. The `async {}` block is launched as a child of the `coroutineScope` block's Job. When `computeIfAbsent` finds a cached entry and returns it, the `async` lambda is never run — but when the key is absent, the new `Deferred` is parented to the transient `coroutineScope` job, not to the service's injected `cs`. If the calling coroutine (e.g., a chip `refresh()` call) is cancelled before `deferred.await()` returns, the `coroutineScope` is cancelled, which cancels the child `async` — but the `Deferred` object is already stored in the `ConcurrentHashMap`. The next caller for the same key finds a cancelled `Deferred` in the map and gets `CancellationException` from `.await()`, rather than a fresh computation. The cache entry is permanently poisoned until `invalidate()` is called.  
**Evidence**:
```kotlin
// HandoverAiSummaryCache.kt:154-172
val deferred: Deferred<HandoverPlaceholderValue> = coroutineScope {
    cache.computeIfAbsent(key) {
        async {   // parented to transient coroutineScope job
            runCatching { gen.generate(prompt) ... }
        }
    }
}
return deferred.await()
```
**Impact**: After any coroutine cancellation (tab close, project close, chip panel hide), the next chip refresh for `ai.changeSummary` / `ai.ticketSummary` permanently shows "unavailable" until the user switches ticket — the cached `Deferred` is cancelled but never evicted.  
**Fix sketch**: Launch `async` on `cs` directly (the injected service scope), not inside `coroutineScope`. Remove the `coroutineScope` wrapper; use `cs.async { … }` so the `Deferred` outlives the caller's lifecycle. Add a check before returning a cached `Deferred`: `if (cached.isCancelled) { cache.remove(key); recompute }`.

---

### F-4 [P1] [Security / Information Disclosure]: Docker tag payload written to DEBUG log [AUTOMATION]
**File**: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt:280`  
**Description**: `buildJsonPayload` logs a 200-character preview of the full `dockerTagsPayload` JSON at DEBUG level. This payload contains all service→image-tag mappings which may be considered sensitive infrastructure data (internal image repository paths, version fingerprints). When a developer sets IDEA log level to DEBUG for troubleshooting, these are written to `idea.log` in plaintext.  
**Evidence**:
```kotlin
// TagBuilderService.kt:279-280
log.info("[Automation:Tags] Built JSON payload with ${entries.size} services, length=${payload.length}")
log.debug("[Automation:Tags] JSON preview: ${payload.take(200)}${if (payload.length > 200) "..." else ""}")
```
**Impact**: `idea.log` is a plaintext file accessible to anyone with filesystem access to the developer's machine. In shared CI environments or if log files are shipped to a logging aggregator, this exposes internal Docker registry paths and version tags.  
**Fix sketch**: Remove the `log.debug` payload preview line entirely, or gate it behind an explicit `DEBUG_PAYLOAD` system property that is off by default.

---

### F-5 [P1] [Correctness]: `DOCKER_TAG_REGEX` applied to full multi-megabyte build logs on every poll tick [AUTOMATION]
**File**: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt:360-363`  
**Description**: `extractDockerTagFromLog` runs `DOCKER_TAG_REGEX.find(logText)` on the full `logText` string. Bamboo build logs can be multiple megabytes (29KB documented for job-level logs; integration suites commonly exceed 1MB). This is called once per `BuildLogReady` event and once per `TagHistoryService.detectDockerTag` REST fallback call — i.e., up to twice per 15-second poll tick per monitored entry. The regex itself `Regex("Unique Docker Tag\\s*:\\s*(.+)")` is linear and safe from ReDoS, but the O(N) scan on large strings on `Dispatchers.IO` during poll ticks adds meaningful latency.  
**Evidence**:
```kotlin
// TagBuilderService.kt:359-363
fun extractDockerTagFromLog(logText: String): String? {
    val match = DOCKER_TAG_REGEX.find(logText) ?: return null
    return match.groupValues[1].trim()
        .replace(ANSI_ESCAPE_REGEX, "")
        .takeIf { it.isNotBlank() }
}
```
**Impact**: At 1MB log × 15s poll × N monitored runs, this is measurable CPU time on IO threads. More critically, if a build log contains a Bamboo step that prints "Unique Docker Tag : <very long line>" with many ANSI escape sequences, the `ANSI_ESCAPE_REGEX.replace()` pass iterates the same captured text again.  
**Fix sketch**: Pre-process `logText` by splitting to lines and scanning only the first occurrence matching the prefix "Unique Docker Tag" rather than scanning the full string. Cap the scan to the first 500 lines (tags are always in the early build output).

---

### F-6 [P1] [Correctness]: `getActiveQueueEntries` ResultSet iteration not exception-safe; corrupt DB row silently aborts restore [AUTOMATION]
**File**: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt:162-178`  
**Description**: Inside `getActiveQueueEntries`, if `json.decodeFromString(rs.getString("variables_json"))` throws (e.g., a row with corrupt JSON written by an older plugin version), the exception propagates out of the `.use{}` block, closing the statement but losing all previously collected `results`. `restoreFromPersistence` in `QueueService` catches exceptions at the `launchWithErrorSurface` level and shows a balloon — the user sees "Restore from persistence failed" with no indication which row is corrupt or how many were recovered before the failure.  
**Evidence**:
```kotlin
// TagHistoryService.kt:169
variables = json.decodeFromString(rs.getString("variables_json")),
// No try/catch per row — one bad row aborts the entire restore
```
**Impact**: A single corrupt `variables_json` row from an older plugin version causes all other non-terminal queue entries to be silently dropped on IDE restart. The user's pending automation queue is lost.  
**Fix sketch**: Wrap each `results.add(QueueEntry(...))` in a `try { … } catch (e: Exception) { log.warn(...); continue }` so corrupt rows are skipped and logged without aborting the restore.

---

### F-7 [P1] [Correctness]: `HandoverStateService` suite row match on `buildResultKey` may miss if `AutomationFinished` arrives before `AutomationTriggered` [HANDOVER]
**File**: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt:264-282`  
**Description**: `AutomationFinished` handler matches on `event.buildResultKey` against `current.suiteResults`. If the `AutomationFinished` event arrives before the corresponding `AutomationTriggered` (e.g., after an IDE restart where the in-memory `_stateFlow` is reset but a previously-started Bamboo build finishes), the `before == null` branch logs a warning but the state is not updated. The handover checklist never marks that suite as passed/failed. The CLAUDE.md documents this as a "known limitation" but it is still a correctness bug from the user's perspective.  
**Evidence**:
```kotlin
// HandoverStateService.kt:265-270
val before = current.suiteResults.firstOrNull { it.buildResultKey == event.buildResultKey }
val updated = current.suiteResults.map { suite ->
    if (suite.buildResultKey == event.buildResultKey) { ... } else suite
}
if (before == null) {
    log.warn("[Handover:State] AutomationFinished resultKey=... not found ...")
}
```
**Impact**: Suite status dots in `ChecksTab` show "—" indefinitely for runs that completed while the IDE was restarting. User must manually verify the suite and proceed past a misleading checklist.  
**Fix sketch**: When `before == null`, create a synthetic `SuiteResult` from the `AutomationFinished` event fields (pass `buildResultKey`, `suitePlanKey`, `passed`, `durationMs`) and append it to `suiteResults`. This is safe because the event carries all necessary fields.

---

### F-8 [P1] [Correctness]: Copyright year consolidation ignores file modification state — marks unchanged files `YEAR_OUTDATED` [HANDOVER]
**File**: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/CopyrightFixService.kt:112-142`  
**Description**: `analyzeFile` is called for every file in `ChangeListManager.allChanges`. If a file appears in the changelist because of a minor unrelated edit (e.g., a whitespace change), but its copyright header still reads `2019` and the current year is `2026`, it is flagged `YEAR_OUTDATED`. The copyright rule says "earliest-currentYear for changed/new files" — this is correct. However, `analyzeFile` is also called with the full `content` that includes lines beyond line 15, but the header analysis only looks at lines 1-15. If the copyright line is on line 16 (valid for some template files with a shebang + blank line), it is silently classified as `MISSING_HEADER`.  
**Evidence**:
```kotlin
// CopyrightFixService.kt:99-102
fun hasCopyrightHeader(content: String): Boolean {
    val headerRegion = content.lines().take(15).joinToString("\n").lowercase()
    return headerRegion.contains("copyright")
}
```
**Impact**: Files with copyright on line 16+ are flagged `MISSING_HEADER` and "fixed" by prepending a new header — resulting in a duplicate copyright block. This is a data corruption issue for script files with shebangs and long license preambles.  
**Fix sketch**: Increase the scan region from 15 lines to 25 lines (or make it configurable), consistent with industry standard copyright scanner heuristics.

---

### F-9 [P1] [Security]: `bambooLink` URL in `SuiteResult` is assembled from unvalidated `event.buildResultKey` [HANDOVER]
**File**: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt:244-252`  
**Description**: The Bamboo link is constructed as `"$bambooUrl/browse/${event.buildResultKey}"`. `event.buildResultKey` comes from `WorkflowEvent.AutomationTriggered.buildResultKey` which originates from `BambooService.triggerBuild`. Bamboo's API returns the `buildKey` from the server so this is not user-supplied. However the link is rendered in `JiraClosureService.buildClosureComment` as a Jira wiki `[View Results|${suite.bambooLink}]` without escaping the URL for wiki markup. A malformed `buildResultKey` containing `|` or `]` would break the wiki link syntax.  
**Evidence**:
```kotlin
// HandoverStateService.kt:251-252
bambooLink = "$bambooUrl/browse/${event.buildResultKey}"
// JiraClosureService.kt:46
appendLine("| ${escapeWikiMarkup(suite.suitePlanKey)} | $statusIcon | [View Results|${suite.bambooLink}] |")
```
**Impact**: `escapeWikiMarkup` is applied to `suitePlanKey` but not to `bambooLink`. A `buildResultKey` containing `]` (unlikely from real Bamboo but theoretically possible from a malformed EventBus payload) breaks the wiki table row.  
**Fix sketch**: Apply `escapeWikiMarkup` to `suite.bambooLink` in `JiraClosureService.buildClosureComment`, or URL-encode the link component.

---

### F-10 [P2] [Quality]: `ChecksTab` suite rows matched by hardcoded substring patterns — tight coupling to plan key naming convention [HANDOVER]
**File**: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/tabs/ChecksTab.kt:176-191`  
**Description**: The three suite status rows (API Smoke, API Integration, Web E2E) are populated by string-matching `suitePlanKey` against hardcoded patterns like `"API-SMOKE"`, `"APIINT"`, `"E2E"`. Any suite whose plan key doesn't match these exact patterns is silently ignored — the row stays as "—" even when the suite ran successfully. Teams using plan keys like `"PROJ-REGRESSION"` or `"FULL-E2E"` will see a permanently grey checklist.  
**Evidence**:
```kotlin
// ChecksTab.kt:177-191
applyOneSuite(ROW_SUITE_API_SMOKE, suites.find { s ->
    s.suitePlanKey.contains("API-SMOKE", ignoreCase = true) ||
        s.suitePlanKey.endsWith("APISMOKE", ignoreCase = true)
})
```
**Impact**: P2 quality — incorrect checklist status for non-standard plan keys leads to false "not done" indication. No security or data loss impact.  
**Fix sketch**: Move suite-row matching patterns to `PluginSettings` (configurable per-project), or match on `AutomationSettingsService.getAllSuites()` categories rather than raw plan key substrings.

---

### F-11 [P2] [Quality]: `TRIGGERING` status in `QueueEntryStatus` enum never set by `QueueService` — dead code [AUTOMATION]
**File**: `automation/src/main/kotlin/com/workflow/orchestrator/automation/model/AutomationModels.kt:52`  
**Description**: `QueueEntryStatus.TRIGGERING` is defined in the enum and referenced in `MonitorPanel.buildRunEntry`'s `else` branch, but `QueueService` never sets any entry to `TRIGGERING`. The status is commented "currently unused." It has no `TERMINAL` membership either, so a hypothetical entry in this state would poll forever.  
**Evidence**:
```kotlin
// AutomationModels.kt:52
TRIGGERING,
// MonitorPanel.kt:862
// TRIGGERING currently unused by QueueService — render generically if reintroduced.
```
**Impact**: Dead code that inflates the status space. If a future developer sets an entry to `TRIGGERING` without adding it to `TERMINAL`, it will poll indefinitely.  
**Fix sketch**: Either remove `TRIGGERING` from the enum, or annotate it `@Deprecated` and add it to `TERMINAL` as a safety net.

---

### F-12 [P2] [Quality]: `AutomationStatusBarWidgetFactory` scope not tied to widget disposal [AUTOMATION]
**File**: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationStatusBarWidgetFactory.kt:33`  
**Description**: `AutomationStatusBarWidgetFactory` creates `private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())` at the factory level (not widget level). If the status bar widget is disposed and recreated (which IntelliJ does on layout changes), the old scope continues running. No `dispose()` implementation was observed in the factory that cancels this scope.  
**Evidence**:
```kotlin
// AutomationStatusBarWidgetFactory.kt:33
private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
```
**Impact**: Coroutine leak — on every tool window re-layout or status bar reset, an additional scope accumulates. Over a long IDE session this can produce multiple redundant collectors on `QueueService.stateFlow`.  
**Fix sketch**: Move scope creation into the `StatusBarWidget` instance (not the factory), and cancel it in the widget's `dispose()` method. Alternatively implement `Disposable` on the factory and register it with the project's `Disposer`.

---

### F-13 [P2] [Quality]: `BaselineCacheService.persistToDisk()` called while holding `Mutex` — disk I/O inside lock [AUTOMATION]
**File**: `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/BaselineCacheService.kt:66-69`  
**Description**: Both `put` and `invalidate` call `persistToDisk()` inside `mutex.withLock {}`. `persistToDisk` does `tmp.writeText(...)` and `Files.move(...)` — these are blocking file I/O operations that can take tens of milliseconds on slow storage or under lock contention. Any concurrent `get()` call (e.g., tab open) is blocked for the full write duration.  
**Evidence**:
```kotlin
// BaselineCacheService.kt:66-69
suspend fun put(planKey: String, result: BaselineLoadResult) = mutex.withLock {
    entries[planKey] = CachedSuiteEntry.fromModel(planKey, result)
    persistToDisk()   // blocking file I/O inside lock
}
```
**Impact**: P2 — UI jank is unlikely because the caller is already on `Dispatchers.IO`, but the mutex blocks all concurrent reads during the write.  
**Fix sketch**: Snapshot `entries` under the lock, then release the lock before calling `persistToDisk`. Or use `Dispatchers.IO` explicitly inside `persistToDisk` and ensure it is called outside the lock.

---

### F-14 [P2] [Quality]: `HandoverAiSummaryCache` uses hardcoded `NO_SHA = "no-sha"` as cache key — stale AI summary survives branch changes [HANDOVER]
**File**: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverAiSummaryCache.kt:69,116-117`  
**Description**: The `CacheKey` includes a `sha` field but `changeSummary()` always passes `NO_SHA = "no-sha"`. The class KDoc acknowledges "Diff capture is deferred (T8 follow-up)." In practice, `BranchChanged` events correctly call `invalidate()` so the cache is cleared on branch switch. However between branch switches, two calls for the same `(ticketId, NO_SHA, CHANGE_SUMMARY)` key return the same stale AI summary even if commits were added. The field is API-complete but semantically vacuous.  
**Evidence**:
```kotlin
// HandoverAiSummaryCache.kt:69,116-117
private val NO_SHA = "no-sha"
val key = CacheKey(ticketId, NO_SHA, Kind.CHANGE_SUMMARY)
```
**Impact**: P2 — AI summary is stale within a branch session. Not a regression from current behavior (T8 is pending), but documents the semantic gap.  
**Fix sketch**: When T8 ships, replace `NO_SHA` with the actual HEAD SHA from `GitHistoryUtils` or `WorkflowContextService.state.value.focusPr?.headSha`.

---

### F-15 [P2] [Quality]: `JiraClosureService.buildClosureComment` logs a comment preview that may include user-supplied content [HANDOVER]
**File**: `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/JiraClosureService.kt:61`  
**Description**: `log.debug("[Handover:Jira] Comment preview: ${comment.take(200)}")` — this is a `debug` call so it is off by default, and the content (Bamboo URLs, suite pass/fail, Docker tags) is not credentials. However, Docker tag values from `mergeDockerTags` are included verbatim in the comment and thus in the debug log. This is less severe than F-4 since it is gated on `debug` level and the content is already logged elsewhere.  
**Evidence**:
```kotlin
// JiraClosureService.kt:61
log.debug("[Handover:Jira] Comment preview: ${comment.take(200)}")
```
**Impact**: P2 — same class of issue as F-4, lower severity because the comment content does not include auth tokens.  
**Fix sketch**: Remove the comment preview from the log or truncate to the first 80 characters and strip Docker tag values.

---

## AI Pre-Review Check

**Confirmed: No AI pre-review code exists in `:handover`.** A full scan for `AiReview`, `aiReview`, `ai_review`, `pre.review`, `PreReview`, and `pre-review` across all Kotlin files in `handover/src/main/kotlin/` returned zero results. The deletion in `988c261c` is clean. `HandoverAiSummaryCache` relates to AI-generated *handover summaries* (change/ticket summaries for QA clipboard), not the pre-review feature. These are correctly scoped and distinct.

---

## runBlocking Check

**Confirmed: Zero occurrences of `runBlocking` in either module.** All async operations use coroutines with `Dispatchers.IO` or `Dispatchers.EDT` correctly. No violations of the banned-in-production rule.

---

## DockerRegistry Isolation Check

**Accepted: `DriftDetectorService` is a no-op stub.** `isRegistryConfigured()` always returns `false`, `checkDrift()` always returns `emptyList()`, `enrichWithLatestReleaseTags()` is guarded by `isRegistryConfigured()` in `AutomationPanel.reconcileFromBamboo`. The Docker registry isolation concern is moot — no HTTP calls are made. This is architecturally correct per the memory note.

---

## Summary Table

| ID | Severity | Module | Category | File | Status |
|----|----------|--------|----------|------|--------|
| F-1 | P0 | AUTOMATION | Resource Leak | TagHistoryService.kt:216 | Open |
| F-2 | P0 | AUTOMATION | Threading | TagHistoryService.kt:36 | Open |
| F-3 | P0 | HANDOVER | Resource Leak / Correctness | HandoverAiSummaryCache.kt:154 | Open |
| F-4 | P1 | AUTOMATION | Security | TagBuilderService.kt:280 | Open |
| F-5 | P1 | AUTOMATION | Performance | TagBuilderService.kt:360 | Open |
| F-6 | P1 | AUTOMATION | Correctness | TagHistoryService.kt:162 | Open |
| F-7 | P1 | HANDOVER | Correctness | HandoverStateService.kt:264 | Open |
| F-8 | P1 | HANDOVER | Correctness | CopyrightFixService.kt:99 | Open |
| F-9 | P1 | HANDOVER | Security | JiraClosureService.kt:46 | Open |
| F-10 | P2 | HANDOVER | Quality | ChecksTab.kt:176 | Open |
| F-11 | P2 | AUTOMATION | Quality | AutomationModels.kt:52 | Open |
| F-12 | P2 | AUTOMATION | Quality | AutomationStatusBarWidgetFactory.kt:33 | Open |
| F-13 | P2 | AUTOMATION | Quality | BaselineCacheService.kt:66 | Open |
| F-14 | P2 | HANDOVER | Quality | HandoverAiSummaryCache.kt:69 | Open |
| F-15 | P2 | HANDOVER | Quality | JiraClosureService.kt:61 | Open |

### Severity breakdown
| Module | P0 | P1 | P2 | Total |
|--------|----|----|----|----|
| :automation | 2 | 3 | 3 | 8 |
| :handover | 1 | 3 | 3 | 7 |
| **Total** | **3** | **6** | **6** | **15** |

---

## Top 5 Most Important

1. **F-2** `TagHistoryService.kt:36` [AUTOMATION] — Single JDBC Connection shared across multiple `Dispatchers.IO` coroutines with zero synchronization. Data corruption risk under load. Fix: add `@Synchronized` to all DB methods or wrap in a `Mutex`.

2. **F-3** `HandoverAiSummaryCache.kt:154` [HANDOVER] — `async` block parented to transient `coroutineScope` and stored in a `ConcurrentHashMap`. Cancelled `Deferred` permanently poisons the cache key, causing all subsequent AI summary requests to fail silently until ticket change.

3. **F-6** `TagHistoryService.kt:162` [AUTOMATION] — Single corrupt `variables_json` row aborts entire `getActiveQueueEntries` restore, dropping all pending queue entries silently on IDE restart.

4. **F-1** `TagHistoryService.kt:216` [AUTOMATION] — `ResultSet` not closed in `integrityCheck`; accumulates open cursors against the already-unsafe shared Connection.

5. **F-8** `CopyrightFixService.kt:99` [HANDOVER] — 15-line header scan misses copyright on line 16+ (e.g., shebang scripts), causing `MISSING_HEADER` false-positive and prepending a duplicate copyright block on fix-all.

---

## Files Audited

**:automation (18 files)**
- `model/AutomationModels.kt`
- `service/AutomationSettingsService.kt`
- `service/BaselineCacheService.kt`
- `service/DriftDetectorService.kt`
- `service/PollingLifecycle.kt`
- `service/QueueRecoveryStartupActivity.kt`
- `service/QueueService.kt`
- `service/TagBuilderService.kt`
- `service/TagHistoryService.kt`
- `service/TriggerDefaultLogic.kt`
- `settings/AutomationConfigurable.kt`
- `ui/AutomationPanel.kt`
- `ui/AutomationStatusBarWidgetFactory.kt`
- `ui/AutomationTabProvider.kt`
- `ui/MonitorPanel.kt`
- `ui/QueueStatusPanel.kt`
- `ui/SuiteConfigPanel.kt`
- `ui/TagStagingPanel.kt`

**:handover (27 files)**
- `model/HandoverModels.kt`
- `model/HandoverPlaceholderValue.kt`
- `model/HandoverTemplate.kt`
- `service/CopyrightFixService.kt`
- `service/HandoverAiSummaryCache.kt`
- `service/HandoverOverrideTracker.kt`
- `service/HandoverOverrideTrackerActivity.kt`
- `service/HandoverPlaceholderResolver.kt`
- `service/HandoverStateService.kt`
- `service/HandoverTemplateStore.kt`
- `service/HandoverWikiPreviewRenderer.kt`
- `service/JiraClosureService.kt`
- `service/TimeTrackingService.kt`
- `ui/cards/CardPanelHeader.kt`
- `ui/cards/CopyrightFixCard.kt`
- `ui/cards/TimeLogCard.kt`
- `ui/chips/QuickValueChipsPanel.kt`
- `ui/editor/EmailPreviewPane.kt`
- `ui/editor/JiraPreviewPane.kt`
- `ui/editor/TemplateEditorCard.kt`
- `ui/editor/TemplatePicker.kt`
- `ui/HandoverOverrideBanner.kt`
- `ui/HandoverPanel.kt`
- `ui/HandoverTabProvider.kt`
- `ui/HandoverTicketHeader.kt`
- `ui/tabs/ActionsTab.kt`
- `ui/tabs/ChecksTab.kt`
- `ui/tabs/ShareTab.kt`

---

## Verdict

**:automation** is NOT enterprise-ready as-is. The unguarded shared JDBC `Connection` (F-2) is a latent data-corruption bug that will surface under concurrent poll activity. The ResultSet and restore-abort issues (F-1, F-6) compound it. These three must be fixed before the module can be considered production-grade.

**:handover** is close to enterprise-ready. The `coroutineScope`+`computeIfAbsent` pattern (F-3) is a correctness trap that silently degrades the AI summary feature, and the copyright scanner's 15-line cap (F-8) is a real data-corruption risk on fix-all for non-standard file structures. Fix these two and the module is solid.
