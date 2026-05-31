# Six-Tab Audit — Findings (Pass 1, COMPLETE)

**Date:** 2026-05-31  **Run:** `wf_17e3c644-60c`  **Status:** ✅ COMPLETE (all 6 modules)

## Summary
- Raw → Confirmed → Rejected: **205 → 193 → 12**
- By severity: {"Medium": 54, "Low": 119, "High": 20}
- By category: {"bug": 40, "cleanup": 48, "architecture": 30, "coverage": 75}
- By module: {"jira": 33, "pullrequest": 30, "bamboo": 37, "sonar": 32, "automation": 26, "handover": 35}

Verification: each finding adversarially refuted (Sonnet, default-reject when unconfirmable); Critical findings would escalate to a 3-skeptic Opus panel (none found).

## `:automation` (26 confirmed)

### AUTOMATION-ARC-1 — Direct cross-module dependency on :bamboo UI package
- **Severity:** High  **Category:** architecture  **Lens:** architecture
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt`
- **Problem:** AutomationPanel imports and directly instantiates `ManualStageDialog` and uses `TriggerMode` from `com.workflow.orchestrator.bamboo.ui` — a sibling feature module. The project convention is that feature modules depend ONLY on `:core`; cross-module interaction must go through `:core` interfaces or EventBus. The `:automation` build.gradle.kts already lists `implementation(project(":bamboo"))`, making the compile-level coupling explicit and creating a directed dependency cycle risk if `:bamboo` ever needs `:automation` types.
- **Evidence:** `import com.workflow.orchestrator.bamboo.ui.ManualStageDialog
import com.workflow.orchestrator.bamboo.ui.TriggerMode
...
val dialog = ManualStageDialog(project = project, planKey = currentSuitePlanKey, scope = scope, triggerMode = TriggerMode.CUSTOM_STAGES, ...)`
- **Fix:** Move `ManualStageDialog` and `TriggerMode` to `:core` (e.g. `core/ui/bamboo/` or `core/model/`), or define a `StagePickerLauncher` extension point in `:core` (similar to `createPrLauncher` / `openPrLister`) that `:bamboo` registers and `:automation` calls through the EP. Either approach severs the direct `:automation → :bamboo` compile dependency and lets the build.gradle.kts drop `implementation(project(":bamboo"))` entirely.

### AUTOMATION-COR-1 — cancel() silently skips Bamboo stop for RUNNING builds — build keeps running after user cancels
- **Severity:** High  **Category:** bug  **Lens:** correctness
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt`
- **Problem:** QueueService.cancel() only fires bambooService.cancelBuild() when the entry status is QUEUED_ON_BAMBOO. RUNNING entries (which have already started executing on Bamboo) are silently transitioned to CANCELLED locally without any Bamboo API call. cancelBuild maps to DELETE /queue/{key} (removes a queued-but-not-started build); the separate stopBuild maps to PUT /result/{key}/stop (terminates an in-progress build). Neither is called for RUNNING entries. The result: the user clicks Cancel on a running build, the Monitor list clears the row and shows CANCELLED, but the Bamboo agent continues executing the job until completion. This wastes CI resources and misleads the user about the build's actual state.
- **Evidence:** `// QueueService.kt lines 212-216
val resultKey = entry.bambooResultKey
if (resultKey != null && entry.status == QueueEntryStatus.QUEUED_ON_BAMBOO) {
    log.info("[Automation:Queue] Cancelling Bamboo build $resultKey")
    bambooService.cancelBuild(resultKey)
}`
- **Fix:** In the cancel() mutex block, extend the status guard to also call bambooService.stopBuild(resultKey) for RUNNING entries: `if (resultKey != null) { when (entry.status) { QueueEntryStatus.QUEUED_ON_BAMBOO -> bambooService.cancelBuild(resultKey); QueueEntryStatus.RUNNING -> bambooService.stopBuild(resultKey); else -> {} } }`. Both calls should log and silently continue on error (the local CANCELLED transition is still correct — if the build finishes milliseconds before the stop reaches Bamboo, the poll loop will detect the terminal state on the next tick). Add a test: enqueue an entry, advance it to RUNNING status, call cancel(), and verify stopBuild was called exactly once.

### AUTOMATION-COV-1 — QueueService.handleWaitingLocal: Bamboo-busy and getRunningBuilds-error paths untested
- **Severity:** High  **Category:** coverage  **Lens:** coverage
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt`
- **Problem:** Lines 391-392 contain two important branches with no direct tests: (a) `getRunningBuilds` returns an error (network/auth failure) — the entry must stay WAITING_LOCAL and not attempt a trigger; (b) `getRunningBuilds` returns a non-empty list (Bamboo has active builds) — the entry must stay WAITING_LOCAL. Neither scenario is covered. Without these tests, a regression that triggered builds when Bamboo is busy or when the connectivity check failed would not be caught.
- **Evidence:** `val runningResult = bambooService.getRunningBuilds(entry.branchKey ?: entry.suitePlanKey)
if (!runningResult.isError && runningResult.data!!.isEmpty()) {
    val triggerResult = doTrigger(entry)`
- **Fix:** Add two tests in QueueServiceTest driving `pollOnce()` with a WAITING_LOCAL entry: (1) mock `getRunningBuilds` to return `ToolResult(isError=true)` and assert the entry stays WAITING_LOCAL; (2) mock `getRunningBuilds` to return a non-empty list (one running build key) and assert the entry stays WAITING_LOCAL and `triggerBuild` is never called (`coVerify(exactly=0) { bambooService.triggerBuild(any(), any(), any()) }`).

### AUTOMATION-COV-2 — QueueService.cancel(): cancelBuild invocation never verified for QUEUED_ON_BAMBOO entries
- **Severity:** High  **Category:** coverage  **Lens:** coverage
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt`
- **Problem:** Lines 213-215 show that `cancel()` calls `bambooService.cancelBuild(resultKey)` only when the entry is `QUEUED_ON_BAMBOO` and has a non-null `bambooResultKey`. The existing test only cancels a `WAITING_LOCAL` entry. There is no test that (a) seeds an entry already in `QUEUED_ON_BAMBOO` state with a `bambooResultKey` set, cancels it, and verifies `cancelBuild` is invoked; or (b) cancels a `WAITING_LOCAL` entry and verifies `cancelBuild` is NOT invoked.
- **Evidence:** `val resultKey = entry.bambooResultKey
if (resultKey != null && entry.status == QueueEntryStatus.QUEUED_ON_BAMBOO) {
    bambooService.cancelBuild(resultKey)
}`
- **Fix:** Add a test that seeds a `QUEUED_ON_BAMBOO` entry with `bambooResultKey = "PROJ-AUTO-500"` directly in `_stateFlow` (or via `pollOnce` after a successful trigger), calls `service.cancel("q-1")`, and then uses `coVerify(exactly=1) { bambooService.cancelBuild("PROJ-AUTO-500") }`. Add a complementary test for a `WAITING_LOCAL` entry that verifies `coVerify(exactly=0) { bambooService.cancelBuild(any()) }`.

### AUTOMATION-COV-3 — QueueService.restoreFromPersistence() has no test: crash-recovery path entirely uncovered
- **Severity:** High  **Category:** coverage  **Lens:** coverage
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt`
- **Problem:** Lines 501-511 implement `restoreFromPersistence()`, which reads active entries from SQLite and re-populates `_stateFlow`, then starts the poll loop if there is live work. This is the crash-recovery path invoked by `QueueRecoveryStartupActivity` on every IDE start. No test calls this method and verifies that (a) `stateFlow` is repopulated from the persistence layer, or (b) polling starts when non-terminal entries are found. A regression here silently orphans all pre-restart queue entries.
- **Evidence:** `fun restoreFromPersistence() {
    launchWithErrorSurface("Restore from persistence") {
        mutex.withLock {
            val persisted = tagHistoryService.getActiveQueueEntries()
            if (persisted.isNotEmpty()) { _stateFlow.value = persisted; startPollingIfNeeded() }`
- **Fix:** Add a test that pre-seeds the real `TagHistoryService` (with temp DB) with two `WAITING_LOCAL` entries via `saveQueueEntry`, constructs a `QueueService` with `autoTriggerEnabled=false`, calls `service.restoreFromPersistence()`, awaits the state via `awaitState { service.stateFlow.value.size == 2 }`, and asserts both entries are present with `WAITING_LOCAL` status. Also verify that an empty DB leaves `stateFlow` empty and no polling starts.

### AUTOMATION-ARC-3 — dockerTagsJson content partially logged at INFO — information-disclosure risk
- **Severity:** Medium  **Category:** architecture  **Lens:** architecture
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt`
- **Problem:** Line 149 logs `dockerTagsJson.take(100)` at `log.info` level when the parsed JSON is empty. The existing code comment at line 279 (`buildJsonPayload`) explicitly bans logging payload content ('Full service→tag mappings may include internal registry paths and version fingerprints'). The take(100) truncation is insufficient: a single JSON entry like `{"myservice":"repo.internal.corp/svc:1.2.3-build.4567"}` is 57 chars, well within 100, leaking both the internal registry hostname and the Docker image reference into `idea.log` on every build where the parse fails (e.g. the first few builds of a plan before dockerTagsAsJson appears).
- **Evidence:** `val reason = "#${build.buildNumber}: dockerTagsAsJson parsed to empty — ${dockerTagsJson.take(100)}"
log.info("[Automation:Tags]   $reason")
skippedReasons.add(reason)`
- **Fix:** Replace `${dockerTagsJson.take(100)}` with a non-content diagnostic: log the raw string length and a schema hint instead, e.g. `"dockerTagsAsJson was ${dockerTagsJson.length} chars, did not parse as a non-empty JSON object"`. This matches the approach already taken in `buildJsonPayload` (logs `entries.size` and `length`, never the content). The same fix should be applied to the `skippedReasons` string that is later surfaced in the diagnostic banner — the banner text will leak into the UI otherwise.

### AUTOMATION-COR-2 — cancel() result from bambooService silently ignored — cancellation failure is invisible to the user
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt`
- **Problem:** At line 215, bambooService.cancelBuild(resultKey) is called but its ToolResult<Unit> return value is completely discarded. If Bamboo returns an auth failure (403), plan not found (404), or network error, the entry is still transitioned to CANCELLED locally and the EventBus emits QueuePositionChanged(-1). The user has no indication the cancel request failed, and Bamboo's queue still holds the build. The same pattern is present for the not-yet-implemented stopBuild gap (COR-1). In contrast, handleWaitingLocal carefully classifies trigger failures by ErrorType and distinguishes permanent vs transient to decide whether to enter FAILED_TO_TRIGGER.
- **Evidence:** `// QueueService.kt line 215 — return value of suspend fun cancelBuild() is ignored
bambooService.cancelBuild(resultKey)`
- **Fix:** Capture the result and log it: `val cancelResult = bambooService.cancelBuild(resultKey); if (cancelResult.isError) { log.warn("[Automation:Queue] Failed to cancel build $resultKey on Bamboo (continuing with local CANCELLED): ${cancelResult.summary}") }`. For a better UX, surface the Bamboo error in the entry's errorMessage field so the detail panel shows it. The local CANCELLED transition should still proceed regardless (the user's intent is to remove this entry from their view).

### AUTOMATION-COR-3 — enqueue() fast-path holds the mutex across two Bamboo HTTP round-trips, blocking all concurrent user actions
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt`
- **Problem:** Inside mutex.withLock { } in enqueue(), the fast-path (lines 170–191) makes two sequential Bamboo HTTP calls — bambooService.getRunningBuilds() then doTrigger() (which calls bambooService.triggerBuild()) — while holding the coroutine Mutex. Any concurrent cancel(), dismiss(), or pollOnce() call that also tries to acquire the mutex will suspend for the full round-trip latency of both HTTP calls (potentially 2–10+ seconds each on a slow VPN). The pollOnce() was explicitly redesigned (comment at line 345) to avoid exactly this pattern by snapshotting IDs under the lock and re-acquiring per-entry. The enqueue fast-path was not given the same treatment and creates the same 'block everything while HTTP is in-flight' freeze that the pollOnce redesign was meant to fix.
- **Evidence:** `// QueueService.kt lines 136-191 — mutex.withLock spans getRunningBuilds + doTrigger
mutex.withLock {
    ...
    val runningResult = bambooService.getRunningBuilds(entry.branchKey ?: entry.suitePlanKey) // HTTP 1
    if (!runningResult.isError && runningResult.data!!.isEmpty()) {
        val trigge`
- **Fix:** Apply the same snapshot-release-reacquire pattern used in pollOnce: release the mutex after adding the entry to _stateFlow and emitting QueuePositionChanged; then check and trigger outside the lock. Atomicity for the state mutation (adding to _stateFlow) is still correct; the idle-check and trigger can be done without the lock since they only observe Bamboo state and then re-acquire to update the entry's status. Example restructuring: hold the lock only for the state-mutation block (lines 147–165), break out of the lock, then do the idle-check+trigger outside, and re-acquire a new lock to update the entry's bambooResultKey if the trigger succeeds.

### AUTOMATION-COR-4 — onBuildLogReady() reads non-@Volatile field currentSuitePlanKey from IO dispatcher — JMM data race
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt`
- **Problem:** subscribeToBuildEvents() launches a coroutine on scope (Dispatchers.IO) that calls onBuildLogReady() in the collect{} body. onBuildLogReady() reads currentSuitePlanKey at lines 787–788 without any synchronization. currentSuitePlanKey is a plain var (no @Volatile) written on the EDT by onSuiteSelected() (line 450) and the init block (line 381). Under the Java Memory Model, a write on the EDT and a read on an IO thread to an unsynchronized field have no happens-before ordering — the IO thread may see a stale (empty-string) value. In the worst case, the BlankShield at line 787 evaluates isBlank() on the stale empty string and drops a valid BuildLogReady event, leaving the docker-tag banner stuck in the 'Waiting for CI build...' state even after the build completes. By contrast, currentFocusBuild is correctly annotated @Volatile (line 189-190).
- **Evidence:** `// AutomationPanel.kt line 184 — plain var, no @Volatile
private var currentSuitePlanKey: String = ""

// AutomationPanel.kt lines 786-788 — read on IO thread (subscribeToBuildEvents scope)
private fun onBuildLogReady(event: WorkflowEvent.BuildLogReady) {
    if (currentSuitePlanKey.isBlank()) {  //`
- **Fix:** Add @Volatile to currentSuitePlanKey (line 184): `@Volatile private var currentSuitePlanKey: String = ""`. Since it only stores a String (a reference type), @Volatile gives the necessary JMM visibility guarantee with negligible overhead. Alternatively, capture the value on the EDT before launching the IO coroutine and pass it as a parameter, but @Volatile is simpler and consistent with the existing @Volatile currentFocusBuild pattern.

### AUTOMATION-COV-4 — QueueService.triggerNow() error path uncovered — failure result forwarding untested
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt`
- **Problem:** Lines 465-498 show `doTrigger` (called by `triggerNow`) returns a forwarded `ToolResult` with `isError=true` and propagated `payload` when `bambooService.triggerBuild` fails. Only the success path is tested (the single `triggerNow bypasses queue and triggers immediately` test mocks a successful response). The failure path — including that `isError=true`, that `payload` (ErrorType) is forwarded correctly, and that no state mutation occurs — is not tested.
- **Evidence:** `return ToolResult(
    data = "",
    summary = result.summary,
    isError = true,
    payload = result.payload
)`
- **Fix:** Add a test that stubs `bambooService.triggerBuild` to return an error result with `ErrorType.AUTH_FAILED` payload, calls `service.triggerNow(entry)`, asserts `result.isError == true`, `result.data == ""`, and that the payload is `ErrorType.AUTH_FAILED`. Also verify that `_stateFlow.value` is unchanged (no entry mutation when triggerNow is called stand-alone).

### AUTOMATION-COV-5 — TagHistoryService.updateQueueEntryStatus errorMessage round-trip not tested
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt`
- **Problem:** Lines 199-232 show `updateQueueEntryStatus` accepts an `errorMessage: String?` parameter and writes it to the `error_message` column. The only existing test (line 111 in TagHistoryServiceTest) calls this method with `bambooResultKey` set but no `errorMessage`, and only checks `status` and `bambooResultKey`. The path where `errorMessage` is non-null (the `FAILED_TO_TRIGGER` flow writes `triggerResult.summary` as the errorMessage) is never round-tripped through SQLite.
- **Evidence:** `UPDATE queue_entries SET status = ?, updated_at = ?, error_message = ?
WHERE id = ?`
- **Fix:** Add a test that saves a `WAITING_LOCAL` entry, calls `service.updateQueueEntryStatus("q-1", QueueEntryStatus.FAILED_TO_TRIGGER, errorMessage = "Bamboo 401: token expired")`, then reads the DB via `getActiveQueueEntries()` — but since FAILED_TO_TRIGGER is terminal, probe SQLite directly (as the `dismiss` test does) and assert the `error_message` column equals `"Bamboo 401: token expired"`.

### AUTOMATION-COV-7 — QueueService.handleWaitingLocal: second-in-queue entry is not skipped (ordering contract untested)
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt`
- **Problem:** Lines 383-387 implement the oldest-entry-first ordering: only the first `WAITING_LOCAL` entry for a given `suitePlanKey` may trigger; subsequent entries must be skipped (`if (oldestWaiting?.id != entry.id) return entry`). No test verifies this contract. A regression that removes this guard would cause all WAITING_LOCAL entries for the same suite to fire simultaneously against Bamboo.
- **Evidence:** `val oldestWaiting = _stateFlow.value
    .firstOrNull { it.suitePlanKey == planKey && it.status == QueueEntryStatus.WAITING_LOCAL }

if (oldestWaiting?.id != entry.id) return entry`
- **Fix:** Add a test that enqueues two entries for the same `suitePlanKey` (q-1, q-2), mocks `getRunningBuilds` to return empty, mocks `triggerBuild` to succeed, calls `testService.pollOnce()`, and then asserts: only q-1 transitioned to `QUEUED_ON_BAMBOO`; q-2 remains `WAITING_LOCAL`; `coVerify(exactly=1) { bambooService.triggerBuild(any(), any(), any()) }`.

### AUTOMATION-ARC-4 — UI panels own manually-allocated CoroutineScope instead of using platform-injected scope
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt`
- **Problem:** Four UI classes — `AutomationPanel` (line 57), `MonitorPanel` (line 53), `QueueStatusPanel` (line 44), and `AutomationStatusBarWidgetFactory`'s inner widget (line 33) — each allocate their own `CoroutineScope(Dispatchers.IO + SupervisorJob())`. The `:core` CLAUDE.md and phase-4 conventions explicitly deprecate this pattern for `@Service` classes in favour of the platform-injected `cs: CoroutineScope`. UI panels are not `@Service` instances, so the impact is lower, but creating four independent `SupervisorJob` roots means any unhandled exception in one coroutine is silently swallowed by the Job (the `launchWithErrorSurface` wrapper in `QueueService` is absent in the panel scopes), and cancellation ordering is ad-hoc (`scope.cancel()` in `dispose()`). A leak in `dispose()` not being called (e.g. if `Disposer.register` was omitted for a child panel) would permanently prevent GC of the scope's coroutines.
- **Evidence:** `// AutomationPanel.kt:57
private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
// MonitorPanel.kt:53
private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
// QueueStatusPanel.kt:44
private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())`
- **Fix:** For non-`@Service` Disposable UI panels the accepted IntelliJ 2024.1+ pattern is `cs.childScope()` or to obtain the project's `@Service`-injected scope via a thin `@Service`-level bridge (e.g. have a `@Service` own the scope and let the panel call `cs.launch { }`). At minimum, check that every panel that allocates its own scope has a matching `Disposer.register` so `dispose()` is always called, and add a top-level `CoroutineExceptionHandler` so exceptions in fire-and-forget coroutines surface in logs.

### AUTOMATION-ARC-6 — TagBuilderService @Service without platform-injected CoroutineScope takes Project but launches its own suspend work without a scope
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt`
- **Problem:** TagBuilderService is annotated `@Service(Service.Level.PROJECT)` but does not follow the platform-injected `cs: CoroutineScope` pattern mandated by the phase-4 conventions. Its `suspend` entry points (`scoreAndRankRuns`, `detectDockerTag`, `loadBaseline`) are called from caller-owned scopes (panel scopes), which is acceptable, but the IntelliJ DI constructor reads `PluginSettings` and `BuildLogCache` eagerly at construction time without deferring to lazy — in a headless test environment this will throw because `PluginSettings.getInstance(project)` invokes service resolution on the DI container before the plugin's state is initialised. The test constructor (the second one) is the workaround, but it means every test must remember to use the test constructor rather than `project.getService()`.
- **Evidence:** `constructor(project: Project) {
    val settings = PluginSettings.getInstance(project)
    this.bambooService = project.getService(BambooService::class.java)
    this.buildLogCache = BuildLogCache.getInstance(project)
    this.buildVariableName = settings.state.bambooBuildVariableName?.takeIf { it.i`
- **Fix:** Lazy-init `buildVariableName` by reading it at call time from `PluginSettings` rather than caching it at construction, so there is no settings read in the constructor. For `bambooService` and `buildLogCache`, the platform DI constructor already receives `Project` — use lazy delegates (`by lazy { project.getService(...) }`) consistent with the pattern used in `AutomationPanel` and `QueueService`. This removes the eager-resolution fragility without requiring a separate test constructor.

### AUTOMATION-COR-5 — TagHistoryService connection leaked when dispose() races with lazy initialization
- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagHistoryService.kt`
- **Problem:** TagHistoryService.close() guards the connection close with `if (!connectionInitialized) return` at line 335. The `connectionInitialized` flag is set to `true` at line 60, inside the lazy { } block, after the Connection is opened and initSchema is run. If dispose() is called (from the EDT on project close) while a background coroutine is mid-way through the lazy initialization block (connection created, initSchema running, but connectionInitialized not yet set to true), close() sees connectionInitialized=false and returns early. The lazy block then completes, leaves connectionInitialized=true, and the Connection is never closed. The leaking connection holds WAL/SHM file locks on the SQLite DB, causing 'database is locked' errors if the project is immediately reopened in another IDE window. Kotlin's by lazy uses LazyThreadSafetyMode.SYNCHRONIZED (prevents double-init), but the connectionInitialized flag is set at the end of the block, creating a window where the connection object exists but the guard flag is still false.
- **Evidence:** `// TagHistoryService.kt lines 41-62
@Volatile
private var connectionInitialized = false

private val connection: Connection by lazy {
    ...
    connectionInitialized = true  // set at END of block; window before this = leak risk
    conn
}

// TagHistoryService.kt line 335
fun close() {
    if (!c`
- **Fix:** Eliminate the separate connectionInitialized flag and instead use the lazy property's own initialization state. Replace the guard in close() with a null-safe access pattern: declare `private val connectionOrNull: Connection? get() = if (::connection.isInitialized) connection else null` (Kotlin lateinit form), or change to `private val _connection: Connection? by lazy { ... }` returning null on failure. Then close() becomes: `_connection?.let { try { it.close() } catch (_: Exception) {} }`. This removes the TOCTOU window between the Connection being created and the flag being set.

### AUTOMATION-CLE-1 — resolveServiceCiPlanKey() is a private method with zero call sites
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt`
- **Problem:** The private method `resolveServiceCiPlanKey()` at line 715 has no callers anywhere in the codebase (confirmed via `rg` across the entire worktree). The KDoc on line 713 explicitly states "NOTE (Phase B): This method is no longer called from the docker-tag detection path" and promises a Phase D cleanup that has not happened. The method is 13 lines of non-trivial logic that is simply dead.
- **Evidence:** `private fun resolveServiceCiPlanKey(): String {
    val fromDedicated = settings.state.serviceCiPlanKey?.takeIf { it.isNotBlank() }
    if (fromDedicated != null) return fromDedicated
    ...
}
// Only occurrence at line 715 — zero call sites in production or test code`
- **Fix:** Delete `resolveServiceCiPlanKey()` entirely (lines 715-727). The note in the KDoc already calls this a pending cleanup.

### AUTOMATION-CLE-2 — DriftDetectorService and its reconcileFromBamboo guard block are permanently dead
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt`
- **Problem:** `DriftDetectorService.isRegistryConfigured()` unconditionally returns `false` (by design — registry calls removed). The guard in `reconcileFromBamboo` at lines 622–625 therefore can never execute. The three lines inside the `if` block, the `driftDetectorService` lazy field (line 63), and the `DriftDetectorService` class itself are all permanently dead complexity. The service is still registered in `plugin.xml` (line 306), adding pointless DI overhead.
- **Evidence:** `// DriftDetectorService.kt:25
fun isRegistryConfigured(): Boolean = false

// AutomationPanel.kt:622-625
if (tags.isNotEmpty() && driftDetectorService.isRegistryConfigured()) {
    log.info("[Automation:UI] Fetching latest release tags from registry...")
    tags = driftDetectorService.enrichWithLat`
- **Fix:** Delete the `if (tags.isNotEmpty() && driftDetectorService.isRegistryConfigured())` block in `reconcileFromBamboo`. Remove the `driftDetectorService` lazy field from `AutomationPanel`. Delete the entire `DriftDetectorService` class. Remove its `<projectService>` registration from `plugin.xml`.

### AUTOMATION-CLE-3 — DriftResult model and checkDrift() are completely unused dead types
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/model/AutomationModels.kt`
- **Problem:** `DriftResult` (lines 111–116) is a `data class` that appears only in the no-op `DriftDetectorService.checkDrift()` signature and one test that just constructs the object. `checkDrift()` is never called from production code. With `DriftDetectorService` dead (CLE-2), `DriftResult` is unreachable type pollution in the public model namespace.
- **Evidence:** `data class DriftResult(
    val serviceName: String,
    val currentTag: String,
    val latestReleaseTag: String,
    val isStale: Boolean
)
// Only production use: DriftDetectorService.checkDrift() return type — which is never called`
- **Fix:** Delete `DriftResult` from `AutomationModels.kt` and delete the corresponding test case in `AutomationModelsTest.kt` (`DriftResult marks stale when versions differ`).

### AUTOMATION-CLE-4 — TagEntry.isDrift and RegistryStatus.{VALID,NOT_FOUND,CHECKING} are permanently unreachable UI states
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/model/AutomationModels.kt`
- **Problem:** In production, `TagEntry.isDrift` is always `false` and `TagEntry.registryStatus` is always `RegistryStatus.UNKNOWN` (confirmed by searching all assignments in `main/`). The renderer in `TagStagingPanel` has live branches for `isDrift -> WARNING`, `VALID -> SUCCESS`, `NOT_FOUND -> ERROR`, and `CHECKING`, but these branches can never fire at runtime. The values `VALID`, `NOT_FOUND`, and `CHECKING` in the `RegistryStatus` enum are dead. Similarly `TagEntry.latestReleaseTag` is always `null`.
- **Evidence:** `// All production assignments (TagBuilderService.kt, BaselineCacheService.kt, TagStagingPanel.kt):
registryStatus = RegistryStatus.UNKNOWN
isDrift = false
latestReleaseTag = null

// TagStagingPanel.kt:257-259 (dead branches):
entry.isDrift -> "⚠ Drift"
entry.registryStatus == RegistryStatus.VALID -`
- **Fix:** Remove `isDrift`, `latestReleaseTag`, and the renderer branches that depend on them from `TagEntry` and `TagStagingPanel`. Simplify `RegistryStatus` to just `UNKNOWN` and `ERROR` (keep ERROR for future use), or delete the enum entirely if only UNKNOWN remains. Remove the `DriftResult.latestReleaseTag` field if `DriftResult` itself is not deleted via CLE-3.

### AUTOMATION-CLE-5 — loadBaseline() is a legacy delegation stub with no production callers
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt`
- **Problem:** The `loadBaseline()` method at line 254 is marked `/** Legacy method — delegates to [loadBaselineWithDiagnostics]. */` and has zero production call sites (confirmed by `rg` across the full worktree). Its only callers are three test assertions in `TagBuilderServiceTest` that test the delegation rather than the underlying logic.
- **Evidence:** `/** Legacy method — delegates to [loadBaselineWithDiagnostics]. */
suspend fun loadBaseline(suitePlanKey: String): List<TagEntry> =
    loadBaselineWithDiagnostics(suitePlanKey).tags
// Zero production callers; only called from TagBuilderServiceTest lines 68, 156, 169`
- **Fix:** Delete `loadBaseline()`. Update the three test call sites in `TagBuilderServiceTest` to call `loadBaselineWithDiagnostics(...)?.tags` directly so the tests continue exercising the real code path.

### AUTOMATION-CLE-6 — singlePageMode branch in scoreAndRankRuns is redundant dead complexity
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt`
- **Problem:** The `singlePageMode` check at lines 90–99 introduces a conditional that always resolves to the same Bamboo API call. When `singlePageMode = true` (i.e., `targetParseable == maxWalk`), the true-branch calls `getRecentBuilds(suitePlanKey, targetParseable)` and the false-branch calls `getRecentBuilds(suitePlanKey, maxWalk)` — but at that point `targetParseable == maxWalk`, so both calls are identical. The `if/else` can be collapsed to a single unconditional call `getRecentBuilds(suitePlanKey, maxWalk)`.
- **Evidence:** `val singlePageMode = targetParseable == maxWalk
val buildsResult = if (singlePageMode) {
    bambooService.getRecentBuilds(suitePlanKey, targetParseable)  // same as maxWalk
} else {
    bambooService.getRecentBuilds(suitePlanKey, maxWalk)
}`
- **Fix:** Remove the `singlePageMode` variable and the `if/else` block. Replace with a single call: `val buildsResult = bambooService.getRecentBuilds(suitePlanKey, maxWalk)`. Remove the corresponding KDoc paragraph explaining the distinction, which is also no longer accurate.

### AUTOMATION-CLE-8 — CurrentRepoContext.branchName and .detectedFrom are struct fields never read in production
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/model/AutomationModels.kt`
- **Problem:** `CurrentRepoContext` carries two fields — `branchName: String` (line 104) and `detectedFrom: DetectionSource` (line 106) — that are never read by any production consumer. `TagBuilderService.replaceCurrentRepoTag()` only accesses `context.serviceName` and `context.featureBranchTag`. All three call sites in `AutomationPanel` pass `focusBuild.branch` and `DetectionSource.SETTINGS_MAPPING` but these values go nowhere. The `DetectionSource` enum's values `PROJECT_NAME` and `GIT_BRANCH` are never used in production (only `SETTINGS_MAPPING` is passed, and even that is dropped).
- **Evidence:** `// TagBuilderService.kt:257-274 — replaceCurrentRepoTag only reads serviceName and featureBranchTag:
fun replaceCurrentRepoTag(entries: List<TagEntry>, context: CurrentRepoContext): List<TagEntry> {
    if (context.featureBranchTag == null) return entries
    return entries.map { entry ->
        if`
- **Fix:** Remove `branchName` and `detectedFrom` from `CurrentRepoContext`. Simplify the data class to `data class CurrentRepoContext(val serviceName: String, val featureBranchTag: String?)`. Remove `DetectionSource` enum entirely. Update the three `AutomationPanel` call sites and the two test cases that construct `CurrentRepoContext` with 4 arguments.

### AUTOMATION-COV-6 — MonitorPanel.buildRunEntry: bambooUrl construction and filter buckets for most statuses untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/MonitorPanel.kt`
- **Problem:** Lines 800-870 implement `buildRunEntry`, which maps every `QueueEntryStatus` to a `RunEntry` with a computed `bambooUrl`, `filterBucket`, `isTerminal`, and `status` label. Only `FAILED_TO_TRIGGER` and `WAITING_LOCAL` are tested. The following are untested: (a) `bambooUrl = "$bambooUrlBase/browse/$resultKey"` when both are non-blank (line 801-803); (b) `COMPLETED` → `filterBucket=MonitorFilter.COMPLETED`, `isTerminal=true`; (c) `FAILED` → `filterBucket=FAILED`, `isTerminal=true`, `errorMessage` forwarded; (d) `CANCELLED` → `filterBucket=FAILED` (not CANCELLED); (e) `RUNNING` and `QUEUED_ON_BAMBOO` → not terminal.
- **Evidence:** `val bambooUrl = if (resultKey.isNotBlank() && bambooUrlBase.isNotBlank()) {
    "$bambooUrlBase/browse/$resultKey"
} else ""

return when (queueEntry.status) {
    QueueEntryStatus.COMPLETED -> RunEntry(...filterBucket = MonitorFilter.COMPLETED)
    QueueEntryStatus.CANCELLED -> RunEntry(...filterBu`
- **Fix:** Extend `MonitorPanelRunEntryTest` with parametrized or individual tests for each remaining status, using `bambooUrlBase = "https://bamboo.example.com"` and a non-blank `bambooResultKey`. Assert `bambooUrl`, `filterBucket`, `isTerminal`, and `status` label for each. In particular, assert that `CANCELLED` maps to `MonitorFilter.FAILED` (not a `CANCELLED` bucket) and that `COMPLETED` maps to `MonitorFilter.COMPLETED`.

### AUTOMATION-COV-8 — TagBuilderService.replaceCurrentRepoTag with null featureBranchTag returns entries unchanged — not tested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt`
- **Problem:** Line 261 has an early-return guard: `if (context.featureBranchTag == null) return entries`. This path (the service could not detect a feature branch tag for the current repo) returns the baseline list untouched. It is not covered by any test in `TagBuilderServiceTest`. If the guard is accidentally removed or negated, every baseline entry for the current repo service would be overwritten with `null`.
- **Evidence:** `fun replaceCurrentRepoTag(
    entries: List<TagEntry>,
    context: CurrentRepoContext
): List<TagEntry> {
    if (context.featureBranchTag == null) return entries`
- **Fix:** Add a unit test: construct a `CurrentRepoContext` with `featureBranchTag = null`, call `service.replaceCurrentRepoTag(entries, context)`, and assert the returned list is identical to the input list (`assertSame` or deep-equality) with no entry having `isCurrentRepo = true`.

### AUTOMATION-COV-9 — BaselineCacheService.invalidate for non-existent key (no-op early return) untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/BaselineCacheService.kt`
- **Problem:** Line 89 has an early return: `if (entries.remove(planKey) == null) return`. This `return` exits the suspend function before calling `persistSnapshot`, so no disk write occurs when the key did not exist. The scenario is not tested. If this guard is inadvertently removed (e.g. by collapsing the null check), a spurious file-write — overwriting the cache with a missing key still included — would occur on every call to `invalidate` for an unknown key.
- **Evidence:** `suspend fun invalidate(planKey: String) {
    val snapshot = mutex.withLock {
        if (entries.remove(planKey) == null) return
        takeSnapshotLocked()
    }
    persistSnapshot(snapshot)
}`
- **Fix:** Add a test that calls `svc.invalidate("NON-EXISTENT")` on a freshly constructed empty cache, then asserts `svc.get("NON-EXISTENT") == null` (no crash) and that no `baseline-cache.json` file was created in the cache directory (i.e. `!cacheFile.exists()`), confirming the no-op early return fires correctly.

### AUTOMATION-COV-10 — TagBuilderService.scoreAndRankRuns: require() precondition violations (maxWalk < targetParseable, non-positive targetParseable) untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt`
- **Problem:** Lines 81-82 have two `require()` guards: `require(targetParseable > 0)` and `require(maxWalk >= targetParseable)`. No test exercises these guards. A caller passing `targetParseable=0` or `maxWalk < targetParseable` would throw an `IllegalArgumentException` at runtime without any test evidence that the contract is enforced. Callers currently always use defaults, but future call sites (e.g. a configurable baseline depth setting) could violate these preconditions silently.
- **Evidence:** `require(targetParseable > 0) { "targetParseable must be positive" }
require(maxWalk >= targetParseable) { "maxWalk ($maxWalk) must be >= targetParseable ($targetParseable)" }`
- **Fix:** Add two tests using `assertThrows<IllegalArgumentException>` wrapped in `runTest`: one calling `service.scoreAndRankRuns("PROJ", targetParseable = 0)` and asserting the exception message contains "positive"; another calling `service.scoreAndRankRuns("PROJ", targetParseable = 5, maxWalk = 3)` and asserting the exception message contains "maxWalk".

## `:bamboo` (37 confirmed)

### BAMBOO-COR-1 — defaultRevList timeout is ineffective — readText() blocks before waitFor() can fire
- **Severity:** High  **Category:** bug  **Lens:** correctness
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt`
- **Problem:** The REV_LIST_TIMEOUT_SECONDS guard is checked only after `process.inputStream.bufferedReader().readText()` returns. On a slow/hung network-mounted repository or a corrupted git index, `readText()` will block indefinitely reading stdout before `waitFor()` is ever evaluated. The comment claims this guards against slow network drives, but the timeout can never interrupt the blocking `readText()` call — the guard only applies to the time spent waiting for the process to exit AFTER readText() has already drained stdout.
- **Evidence:** `val output = process.inputStream.bufferedReader().readText()
if (!process.waitFor(REV_LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
    process.destroyForcibly()
    return emptyList()
}`
- **Fix:** Read stdout on a separate thread (or with a deadline) so the timeout can fire concurrently. The standard pattern is to use `ProcessBuilder` with `inheritIO()` + a separate thread for stdout, or to use `withTimeoutOrNull` around the entire block inside a `Dispatchers.IO` coroutine. The simplest correct fix: launch a background thread to drain stdout into a StringBuilder, call `process.waitFor(timeout, unit)` on the current thread, then destroy-and-return-empty if the timeout fires, or collect the thread result if it completes in time.

### BAMBOO-COR-2 — Swing component (JBCheckBox) constructed on IO background thread in ManualStageDialog
- **Severity:** High  **Category:** bug  **Lens:** correctness
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/ManualStageDialog.kt`
- **Problem:** Inside the `scope.launch { }` block in the `init` (where `scope` is the caller's `panelScope`, which runs on `Dispatchers.IO`), `JBCheckBox(name, preChecked)` is constructed at line 163 on a background thread. The Swing/AWT threading model requires all Swing component construction and mutation to happen on the Event Dispatch Thread (EDT). Constructing a `JBCheckBox` off-EDT causes an unsynchronized access to Swing's component tree, which can corrupt the component state or produce rendering artefacts.
- **Evidence:** `scope.launch {
    ...
    stageCheckboxes = stageNames.map { name ->
        val cb = JBCheckBox(name, preChecked)
        cb.addActionListener { updateOkButton() }
        name to cb
    }`
- **Fix:** Move all Swing component construction into the `invokeLater` block that already exists at the end of the `scope.launch`. Compute `stageNames` (a plain `List<String>`) on the IO coroutine, then in `invokeLater` create the `JBCheckBox` instances, assign `stageCheckboxes`, and call `refillSlots()` + `updateOkButton()`. This keeps IO work on IO and Swing construction on the EDT.

### BAMBOO-COV-10 — BuildMonitorService.pollOnce(): checkForNewerBuild() is silently swallowed via unregistered mock — newer-build detection path has no positive-case test
- **Severity:** High  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt`
- **Problem:** `pollOnce()` calls `checkForNewerBuild()` (line 223) only on terminal builds. `checkForNewerBuild()` calls `apiClient.getRunningAndQueuedBuilds()`. In `BuildMonitorServiceTest`, `apiClient` is a strict (non-relaxed) mock with no stub for `getRunningAndQueuedBuilds`. The `catch (e: Exception)` in `checkForNewerBuild()` (lines 368-372) swallows the `MockKException` that mockk throws for unstubbed calls, so the tests pass but the newer-build detection is silently skipped in every test that produces a terminal state. No test verifies that `buildState.newerBuild` is populated when a higher-numbered build is running, or that `BuildState.newerBuild` is `null` when no newer builds exist.
- **Evidence:** ````kotlin
private suspend fun checkForNewerBuild(...): NewerBuild? {
    return try {
        val result = apiClient.getRunningAndQueuedBuilds(planKey)
        ...
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null // swallows MockKException from strict mock
  `
- **Fix:** Add `coEvery { apiClient.getRunningAndQueuedBuilds(any()) } returns ApiResult.Success(emptyList())` to `BuildMonitorServiceTest.stubPlanStructure()` (alongside the existing `getPlanStructure` stub). Then add two tests: (1) mock `getRunningAndQueuedBuilds` returning a DTO with `buildNumber = 43` (higher than the current 42) — assert `stateFlow.value?.newerBuild?.buildNumber == 43`; (2) `getRunningAndQueuedBuilds` returns an empty list — assert `stateFlow.value?.newerBuild == null`.

### BAMBOO-COR-3 — ManualStageDialog shared fields written from IO coroutine and read from EDT without @Volatile
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/ManualStageDialog.kt`
- **Problem:** Fields `variables: List<PlanVariableData>`, `stageCheckboxes`, `stageLoadError`, `isLoading`, and `isLoadingStages` are written from within `scope.launch` (IO thread) and then read from the EDT in `populateVariablesEditor`, `populateStageSlot`, `updateOkButton`, and `doOKAction`. None of these fields carry `@Volatile`. Without `@Volatile` or explicit synchronization, the JMM does not guarantee that EDT reads will see the writes made by the IO coroutine, even though the `invokeLater` at the end of the coroutine provides a happens-before for the read paths it dispatches — `doOKAction` reads `variables` and `stageCheckboxes` independently without going through `invokeLater`.
- **Evidence:** `private var variables: List<PlanVariableData> = emptyList()
// ... IO thread writes:
variables = varResult.data!!
// ... EDT reads (doOKAction):
val passwordKeys = variables.filter { it.isPassword }.map { it.name }.toSet()`
- **Fix:** Add `@Volatile` to all fields written from the IO coroutine and read from the EDT: `variables`, `stageCheckboxes`, `stageLoadError`, `isLoading`, `isLoadingStages`. Alternatively, move all writes into the existing `invokeLater` block so they are confined to the EDT throughout, eliminating the cross-thread visibility gap entirely.

### BAMBOO-COR-4 — BambooServiceImpl.client getter has TOCTOU race — @Volatile does not protect compound check-create-write
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt`
- **Problem:** The `client` getter performs a read-check-write compound operation on `cachedBaseUrl` and `cachedClient` that is not atomic. Two coroutines calling `client` concurrently from `Dispatchers.IO` can both observe `url != cachedBaseUrl || cachedClient == null` as true, both construct a new `BambooApiClient` (creating an additional OkHttpClient derived from `sharedPool`), and then both write to `cachedClient`. The second write discards the first client. More critically, `cachedBaseUrl` is written BEFORE `cachedClient` in the same if-block, so in the window between those two writes a third reader sees a stale `cachedBaseUrl` pointing to the new URL but a `cachedClient` still built for the old URL. In `triggerBuild`, `cachedBaseUrl` is then read directly to form the `link` URL — if the URL was just changed this can produce a mismatched link.
- **Evidence:** `if (url != cachedBaseUrl || cachedClient == null) {
    cachedBaseUrl = url
    ...
    cachedClient = BambooApiClient(baseUrl = url, ...)
}
return cachedClient`
- **Fix:** Guard the entire compound operation with `@Synchronized` on the getter, or replace both `@Volatile` fields with a single `@Volatile` holder of a `Pair<String, BambooApiClient>` so the read-check-replace is a single volatile write. The cleanest fix per the project conventions is a coroutine `Mutex` wrapping the check-and-recreate path since service methods are already `suspend`.

### BAMBOO-COR-5 — BuildMonitorService shared mutable state has no synchronization — concurrent pollOnce calls corrupt state
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt`
- **Problem:** The fields `previousBuildNumber`, `previousStatus`, `lastLogFetchedForBuild`, `jobOrderCache`, and `poller` are plain (non-`@Volatile`, non-synchronized) `private var` fields. `pollOnce` is a public `suspend fun` called from (a) the `SmartPoller` action running in `cs` (project scope), and (b) `panelScope.launch` (Dispatchers.IO) when the user clicks Refresh, Stop, or Cancel in `BuildDashboardPanel`. These two scopes can execute `pollOnce` concurrently. A race between them on `previousBuildNumber`/`previousStatus` can cause a `BuildFinished` event to be emitted twice for the same build, or suppressed, and `lastLogFetchedForBuild` can be written by one coroutine before the other has fetched all logs — causing the 'retry on partial fetch' logic to never re-run.
- **Evidence:** `private var previousBuildNumber: Int? = null
private var previousStatus: BuildStatus? = null
private var lastLogFetchedForBuild: Int? = null
// ...
val isFirstPoll = previousBuildNumber == null
val statusChanged = dto.buildNumber != previousBuildNumber || ...
previousBuildNumber = dto.buildNumber`
- **Fix:** Add a `Mutex` (coroutine mutex from `kotlinx.coroutines.sync`) to `BuildMonitorService` and wrap the entire body of `pollOnce` in `mutex.withLock { }`. This ensures only one `pollOnce` executes at a time regardless of which scope calls it, which is the correct semantic since the fields form a consistent unit of state (previousNumber + previousStatus + lastLogFetched must all advance together). Alternatively, mark all fields `@Volatile` and use `compareAndSet` patterns — but the mutex approach is simpler and the lock contention is negligible (30s intervals).

### BAMBOO-COR-7 — getProjects() silently truncates at 100 projects — no pagination
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
- **Problem:** Unlike `getPlans()`, `getBranches()`, `getPlanBranches()`, and `getLinkedRepositories()` — which all use the shared `paginate()` loop — `getProjects()` makes a single GET with `max-results=100` and returns only the first page. Bamboo installations with more than 100 projects will silently return an incomplete list. The `BambooServiceImpl.getProjects()` surface is exposed to the agent (`BambooService.getProjects`) and used for project-key lookups; a user with >100 projects can never find plans belonging to projects beyond the first 100.
- **Evidence:** `suspend fun getProjects(): ApiResult<List<BambooProjectDto>> {
    return get<BambooProjectListResponse>("/rest/api/latest/project?max-results=100")
        .map { it.projects.project }
}`
- **Fix:** Wrap `getProjects()` in the same `paginate()` helper used by `getPlans()`, using `BambooProjectListResponse` and extracting `it.projects.project` as the page items. The paginator already handles both the offset advancement and the short-page termination condition, so the change is a straightforward mechanical replacement matching the pattern of `getPlans()`.

### BAMBOO-COV-7 — BambooApiClient.downloadArtifact(): no tests at API-client level (only SafetyTest covers basename/containment)
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
- **Problem:** `downloadArtifact()` (lines 451-478) has three testable paths: (1) internal URL uses authenticated `httpClient`, external URL uses `sharedPool`; (2) non-2xx response returns `false`; (3) `IOException` returns `false`. `ArtifactDownloadSafetyTest` tests only the `StageDetailPanel.safeArtifactBasename` / `isContainedIn` helpers — it never exercises the actual `BambooApiClient.downloadArtifact()` logic. The cross-origin security boundary (line 457 `isInternal = artifactUrl.startsWith(baseUrl)`) is untested. There is no test that the authenticated client is used for same-origin URLs and the shared pool for cross-origin.
- **Evidence:** ````kotlin
val isInternal = artifactUrl.startsWith(baseUrl)
val client = if (isInternal) httpClient else HttpClientFactory.sharedPool
```
— BambooApiClient.kt lines 457-458. No test in `src/test` calls `downloadArtifact()` directly.`
- **Fix:** Add a `BambooApiClientDownloadArtifactTest`: (1) use MockWebServer as the base URL, enqueue a 200 with binary body, call `downloadArtifact(server.url("/artifact").toString(), tempFile)`, and assert `true` and file content; (2) enqueue a 404, assert returns `false`; (3) for the cross-origin test, enqueue on a separate second MockWebServer and assert the request has no `Authorization` header (proving `sharedPool` was used, not `httpClient`).

### BAMBOO-COV-11 — BuildMonitorService.pollOnce(): first-poll BuildFinished suppression behavior has no direct test
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt`
- **Problem:** `pollOnce()` suppresses `BuildFinished` events on the first poll (`isFirstPoll = previousBuildNumber == null`, lines 233-244) to prevent stale "Build Failed" notifications on IDE startup. While `pollOnce emits BuildLogReady on first terminal poll` documents that `BuildLogReady` IS emitted on first poll, there is no test asserting that `BuildFinished` is NOT emitted on the very first terminal poll. The absence of this test means removing the `!isFirstPoll` guard would go undetected by the test suite, causing spurious startup notifications.
- **Evidence:** ````kotlin
val isFirstPoll = previousBuildNumber == null
...
if (isTerminal && statusChanged && !isFirstPoll) {
    // ... emit BuildFinished
}
```
— BuildMonitorService.kt lines 233-254. The only first-poll test (`pollOnce emits BuildLogReady on first terminal poll`, line 189) asserts `BuildLogReady`
- **Fix:** Add a test: configure a terminal build (Successful/Finished) on the first `pollOnce` call and assert via `eventBus.events.test { expectNoEvents() }` that no `BuildFinished` event is emitted. This directly pins the `!isFirstPoll` guard. Pattern: `service.pollOnce("PROJ-BUILD", "main")` then `eventBus.events.test { expectNoEvents() }` (the current test only checks that `BuildLogReady` IS emitted, not that `BuildFinished` is NOT).

### BAMBOO-COV-13 — BambooTestResultConverter.fromTestResultsData(): no tests at all — skipped/successful test output paths and mixed-status suites untested
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooTestResultConverter.kt`
- **Problem:** `fromTestResultsData()` (lines 94-117) builds a synthetic `BambooTestResultsDto` from the shared domain model and delegates to `toTeamCityMessages()`. It is never called by any test. The existing `BambooTestResultConverterReDoSTest` only calls `toTeamCityMessages()` directly. Additionally, `toTeamCityMessages()` line 78 has a `"skipped"` branch emitting `testIgnored` — no test verifies that a skipped test produces `##teamcity[testIgnored ...]`. Line 33 returns `emptyList()` when `allTests.isEmpty()` — also untested. A test with only passed tests (no failures) is not covered.
- **Evidence:** ````kotlin
fun fromTestResultsData(testResults: TestResultsData, buildLog: String? = null): List<String> {
    val failedDtos = testResults.failedTests.map { ft ->
        BambooTestCaseDto(className = ft.className, methodName = ft.methodName, status = "failed")
    }
```
— BambooTestResultConverter.`
- **Fix:** Create `BambooTestResultConverterTest`: (1) call `fromTestResultsData(TestResultsData(total=3, passed=2, failed=1, skipped=1, failedTests=[...]))` and assert the message list contains `testCount count='3'`, `testFailed`, `testIgnored`, and `testFinished`; (2) call with `TestResultsData(total=0, ...)` and assert `emptyList()`; (3) call `toTeamCityMessages` with a `BambooTestResultsDto` where `failedTests` is empty and `successfulTests` has 2 entries — assert only `testFinished` lines are present (no `testFailed`).

### BAMBOO-ARC-1 — BuildMonitorService creates its own BambooApiClient that becomes stale after Bamboo URL changes
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt`
- **Problem:** BuildMonitorService owns a second BambooApiClient (lines 47–58) that is created once from settings and cached as `_apiClient`. Unlike BambooServiceImpl.client (which invalidates when bambooUrl changes via `cachedBaseUrl` comparison), BuildMonitorService never re-checks the URL. If the user updates the Bamboo URL in settings, the monitor keeps polling the old host until the IDE restarts. This also means there are three independent BambooApiClient instances per project (BambooServiceImpl, BuildMonitorService, BuildFailureBridgeStartupActivity per-call), each with their own OkHttp connection pool, instead of one shared pool.
- **Evidence:** `private val apiClient: BambooApiClient get() = _apiClient ?: run {
    val p = _project!!
    val settings = PluginSettings.getInstance(p)
    ...
    BambooApiClient(
        baseUrl = settings.connections.bambooUrl.orEmpty().trimEnd('/'),
        ...
    ).also { _apiClient = it }   // cached fore`
- **Fix:** Remove BuildMonitorService's private BambooApiClient entirely. Inject BambooServiceImpl (or its internal `client` getter) into BuildMonitorService so all Bamboo HTTP calls in the module share the single URL-change-aware client from BambooServiceImpl. The cleanest form: add an internal `getPollClient(): BambooApiClient?` method on BambooServiceImpl (delegating to its existing `client` getter) and call that from BuildMonitorService instead of constructing its own client.

### BAMBOO-ARC-3 — StageDetailPanel uses raw javax.swing.JScrollPane instead of JBScrollPane for the artifacts list
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt`
- **Problem:** Line 530 constructs a raw `JScrollPane(artifactsList)` inside `showArtifacts()`. The project convention requires JetBrains UI components exclusively; JBScrollPane applies the IDE's scroll speed settings, theming, and rendering improvements. This is the only scroll pane in the file that is not a JBScrollPane (the others at lines 301 and 474 correctly use JBScrollPane).
- **Evidence:** `val scrollPane = JScrollPane(artifactsList)   // line 530, inside showArtifacts()
arifactsPanel.add(scrollPane, BorderLayout.CENTER)`
- **Fix:** Replace `JScrollPane(artifactsList)` with `JBScrollPane(artifactsList).apply { border = JBUI.Borders.empty() }` to match the JBScrollPane usage patterns already present elsewhere in the same file (lines 301, 474).

### BAMBOO-ARC-4 — StageDetailPanel uses raw javax.swing.JButton for search navigation and log/artifact action buttons instead of JetBrains components
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt`
- **Problem:** Lines 91, 96, 147, 648, and 653 create raw `JButton` instances. The JetBrains platform provides themed button components (the `com.intellij.ui.components` package or `ActionButton` for toolbar-style buttons). Raw `JButton` does not adapt to IDE themes on macOS (especially with JetBrains Runtime's custom LAF), potentially producing visual inconsistency in dark mode or with certain platform LAFs.
- **Evidence:** `private val prevMatchButton = JButton("<")       // line 91
private val nextMatchButton = JButton(">")       // line 96
private val openInEditorButton = JButton("Open full log in editor")  // line 147
private val downloadButton = JButton("Download") // line 648
private val openButton = JButton("Open`
- **Fix:** Use IntelliJ platform-recommended buttons. For toolbar-style icon buttons (prev/next match), use `com.intellij.ui.components.ActionLink` or an `ActionButton`. For labelled action buttons (Open full log, Download, Open), use `com.intellij.ui.components.JBButton` if available in the target platform version, or wrap in a `panel { button("...") { } }` DSL row; at minimum import `com.intellij.ui.components` equivalents rather than raw `javax.swing.JButton`.

### BAMBOO-ARC-5 — ManualStageDialog uses raw javax.swing.JTable for the variables preview instead of a JetBrains component
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/ManualStageDialog.kt`
- **Problem:** Line 436 constructs a raw `JTable(tableModel)` for the variables preview section in CUSTOM_STAGES mode. Per project conventions, only JetBrains UI components should be used. The IDE provides `com.intellij.ui.table.JBTable` which participates in IntelliJ's row-height, selection-colors, and accessibility support — raw JTable misses these adaptations.
- **Evidence:** `val table = JTable(tableModel).apply {  // line 436
    setDefaultEditor(Any::class.java, null)
    ...
}`
- **Fix:** Replace `JTable(tableModel)` with `com.intellij.ui.table.JBTable(tableModel)`. JBTable extends JTable, so the rest of the configuration (columnModel, rowHeight, fillsViewportHeight, etc.) is compatible without changes.

### BAMBOO-COR-6 — GitRepository.currentRevision and currentBranchName accessed without ReadAction on IO background thread
- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`
- **Problem:** The methods `readActionLocalHead()` (line 810) and `getCurrentBranch()` (line 807) read `GitRepository.currentRevision` and `GitRepository.currentBranchName` respectively. Despite the name `readActionLocalHead`, neither method wraps the access in `ReadAction.compute {}` or the modern `readAction {}` suspend call. Both are called from within `panelScope.launch` (which runs on `Dispatchers.IO`) — specifically in `resolveBranchPlanAndMonitor` (lines 235, 238) and in the Refresh action's `panelScope.launch` (line 934). The IntelliJ Platform requires VCS model reads to be performed inside a read action when accessed from a background thread, or a write-action interleaving can cause a torn read of the repository state.
- **Evidence:** `private fun readActionLocalHead(): String? = getGitRepo()?.currentRevision
private fun getCurrentBranch(): String? = getGitRepo()?.currentBranchName
// called from panelScope.launch (Dispatchers.IO):
val latestCommit = readActionLocalHead().orEmpty()
// ...
val branch = getCurrentBranch() ?: ...`
- **Fix:** Wrap all VCS model reads in `readAction { }` (the non-deprecated suspend variant from `com.intellij.openapi.application.readAction`). Since the callers are already in a suspend context, convert to: `val latestCommit = readAction { getGitRepo()?.currentRevision }.orEmpty()` and similarly for `getCurrentBranch()`. The `readActionLocalHead` naming implies this was intended; the actual `ReadAction` call is simply missing.

### BAMBOO-CLE-1 — Dead private method: BuildDashboardPanel.startMonitoring() is never called
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`
- **Problem:** The private method `startMonitoring()` (lines 775–790) was deliberately neutered as part of the T-B2/B3-b migration (polling lifecycle moved to BuildMonitorService's focusBuild subscription). The KDoc explicitly says it 'no longer calls BuildMonitorService.startPolling()'. No call site for `startMonitoring()` exists anywhere in the codebase — the three occurrences are all in comments. The method body still launches a coroutine on `panelScope` to resolve a branch and set `headerLabel.text`, which is now redundant because the stateFlow collector already updates `headerLabel.text` on every BuildState change.
- **Evidence:** `775:    private fun startMonitoring() {
776:        val planKey = currentPlanKey()
...
790:    }
// All 3 grep hits for 'startMonitoring' in BuildDashboardPanel.kt are comments, not call sites.`
- **Fix:** Delete the entire `startMonitoring()` method (lines 775–790). It has zero callers and its header-label update is already covered by the stateFlow collector at line 514. No test references it as a call site (BuildDashboardPanelDropdownTest explicitly documents it is NOT called).

### BAMBOO-CLE-2 — Dead public method: BuildMonitorService.switchBranch() has no callers
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt`
- **Problem:** The public `switchBranch(planKey, newBranch, intervalMs)` method (line 203) is referenced only in comments in BuildDashboardPanel.kt (twice, explaining it is no longer used). The T-B2/B3-b migration removed all direct `switchBranch` call sites; the KDoc in BuildDashboardPanelDropdownTest explicitly states 'switchBranch/stopPolling are absent from the new dropdown path'. No caller outside the bamboo module exists either. The method simply calls `_stateFlow.value = null; startPolling(...)` — callers now go through the focusBuild subscription instead.
- **Evidence:** `203:    fun switchBranch(planKey: String, newBranch: String, intervalMs: Long = 30_000) {
204:        log.info("[Bamboo:Monitor] Switching branch...")
205:        _stateFlow.value = null
206:        startPolling(planKey, newBranch, intervalMs)
207:    }
// rg '.switchBranch(' --include='*.kt' src/ y`
- **Fix:** Delete the `switchBranch()` method (lines 203–207). Its only remaining references are comments documenting that it has been removed. `startPolling` is still reachable from the focusBuild subscription path.

### BAMBOO-CLE-3 — Dead parameter: StageDetailPanel.showLog() errors parameter is always ignored
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt`
- **Problem:** The `errors: List<BuildError>` parameter of `showLog()` (line 298) is never read in the method body — the KDoc says 'Parsed errors (unused now — ConsoleView handles highlighting natively)'. All 7 call sites in BuildDashboardPanel.kt pass either `emptyList()` (6 sites) or a live parsed list (1 site, line 1162). Either way the value is discarded. The one site that passes a non-empty list (line 1162) performs `BuildLogParser.parse(logContent)` solely to produce the argument, which is work that has zero effect.
- **Evidence:** `298:    fun showLog(logText: String, errors: List<BuildError>) {
// body never references 'errors'
...
// BuildDashboardPanel.kt line 1161-1162:
    val errors = BuildLogParser.parse(logContent)
    stageDetailPanel.showLog(logContent, errors) // errors dropped immediately`
- **Fix:** Remove the `errors` parameter from `showLog()`. Update the signature to `fun showLog(logText: String)` and remove the corresponding argument at all 7 call sites. Separately delete the `val errors = BuildLogParser.parse(logContent)` local variable at BuildDashboardPanel.kt line 1161 (the `BuildLogParser` object and its tests are still needed for agent-side log parsing; only this local call site is dead).

### BAMBOO-CLE-4 — Dead enum value: TriggerMode.FULL_BUILD is never passed by any external caller
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/ManualStageDialog.kt`
- **Problem:** The `TriggerMode.FULL_BUILD` enum constant (line 48) is only referenced within ManualStageDialog.kt itself (title/button-label string dispatch). Neither BuildDashboardPanel.kt nor AutomationPanel.kt (the only two external callers of ManualStageDialog) pass this value — BuildDashboardPanel uses STAGE and CUSTOM_STAGES; AutomationPanel uses only CUSTOM_STAGES. The KDoc already marks it as a 'Legacy escape hatch'. It is not a reflection/extension-point target. Dead branching code in ManualStageDialog (lines 127, 132, 638) will never execute.
- **Evidence:** `48:    FULL_BUILD,
// ManualStageDialog.kt line 638:
    TriggerMode.FULL_BUILD -> bambooService.triggerBuild(planKey, vars, stages = null)
// No external caller passes TriggerMode.FULL_BUILD; rg confirms only ManualStageDialog.kt references it.`
- **Fix:** Delete the `FULL_BUILD` enum constant and its handling branches in ManualStageDialog (lines 127, 132, 638, and the KDoc references). Both remaining modes (STAGE, CUSTOM_STAGES) are actively used. If a full-build trigger is ever needed again, it is identical to CUSTOM_STAGES with all stages selected.

### BAMBOO-CLE-5 — Dead DTO field: BambooTestResultsDto.quarantined is deserialized but never read
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/dto/BambooDtos.kt`
- **Problem:** The `quarantined: Int = 0` field at line 307 in `BambooTestResultsDto` is deserialized from the Bamboo JSON response but never referenced in any production code. The fields `all`, `successful`, `failed`, `skipped`, `failedTests`, and `successfulTests` are all used; `quarantined` is not. No agent tool, service mapper, or UI panel reads it.
- **Evidence:** `307:    val quarantined: Int = 0,
// rg '\.quarantined\b' --include='*.kt' src/ returns zero results`
- **Fix:** Delete the `quarantined` field from `BambooTestResultsDto`. If Bamboo quarantine data is needed in the future it can be re-added with an explicit consumer.

### BAMBOO-CLE-6 — Dead DTO field: BambooJobResultDto.buildDuration is deserialized but never read
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/dto/BambooDtos.kt`
- **Problem:** The `buildDuration: Long = 0` field at line 139 in `BambooJobResultDto` duplicates `buildDurationInSeconds: Long = 0` (line 138). All mappers in `BambooBuildStructureMapper.toBuildState()` and `BambooServiceImpl.toBuildStageData()` use `buildDurationInSeconds`; `buildDuration` is never referenced.
- **Evidence:** `138:    val buildDurationInSeconds: Long = 0,
139:    val buildDuration: Long = 0,
// rg '\.buildDuration\b' --include='*.kt' src/main/ returns zero results`
- **Fix:** Delete the `buildDuration` field from `BambooJobResultDto`. It is a Bamboo API artifact (the API returns both milliseconds and seconds representations) that the codebase never uses.

### BAMBOO-CLE-7 — Dead DTO field: BambooPlanDto.shortKey is deserialized but never read
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/dto/BambooDtos.kt`
- **Problem:** The `shortKey: String = ""` field at line 51 in `BambooPlanDto` is deserialized but never accessed in any production or test file. The fields actually consumed are `key`, `name`, `shortName`, `enabled`, `type`, and `projectKey`.
- **Evidence:** `51:    val shortKey: String = "",
// rg '\.shortKey\b' --include='*.kt' src/ returns zero results`
- **Fix:** Delete the `shortKey` field from `BambooPlanDto`.

### BAMBOO-CLE-8 — Pervasive dead DTO pagination metadata fields: size/maxResult/startIndex on collection wrappers are never read
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/dto/BambooDtos.kt`
- **Problem:** Every Bamboo paginated collection wrapper DTO declares `size: Int`, `maxResult: Int`, and/or `startIndex: Int` to mirror the Bamboo JSON envelope. None of these fields are ever read by production code — only the inner list field (`.plan`, `.branch`, `.result`, `.variable`, `.stage`, etc.) is accessed. Affected wrappers: BambooProjectCollection (line 15 size), BambooPlanCollection (lines 42-44), BambooBranchCollection (lines 74-76), BambooResultCollection (line 92), BambooStageCollection (line 112), BambooJobResultCollection (line 128), BambooVcsRevisionCollection (line 157), BambooVariableContextCollection (line 194), BambooVariableCollection (line 213), BambooBuildChangeCollection (line 237), BambooSearchResponse (lines 259-261), BambooTestCaseCollection (line 314), BambooChangesetResultList (line 375), BambooLinkedRepositoryListResponse (lines 399-401). The `paginate()` loop in BambooApiClient explicitly documents it does NOT trust the server-echoed `startIndex`/`size` — it uses `items.size` (the Kotlin list size) instead.
- **Evidence:** `42:data class BambooPlanCollection(
43:    val size: Int = 0,
44:    @SerialName("max-result") val maxResult: Int = 25,
45:    @SerialName("start-index") val startIndex: Int = 0,
46:    val plan: List<BambooPlanDto> = emptyList()
// BambooApiClient.paginate() comment: "never from the server-echoed s`
- **Fix:** Delete all `size`, `maxResult`, and `startIndex` fields from the collection wrapper DTOs. The `paginate()` loop uses `items.size` (Kotlin list length) for cursor advancement and termination, not these server-echoed values. The kotlinx.serialization `@SerialName` fields will simply be ignored during deserialization if the JSON contains them — removing the declared field is safe.

### BAMBOO-CLE-9 — Dead DTO fields: BambooProjectDetailResponse.key and .name are never accessed
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/dto/BambooDtos.kt`
- **Problem:** BambooProjectDetailResponse (line 27) declares `key: String = ""` and `name: String = ""` in addition to `plans: BambooPlanCollection`. The only caller `getProjectPlans()` in BambooApiClient.kt maps with `.map { it.plans.plan }`, so `key` and `name` are deserialized but immediately discarded. No other code path reads them.
- **Evidence:** `27:data class BambooProjectDetailResponse(
28:    val key: String = "",
29:    val name: String = "",
30:    val plans: BambooPlanCollection = BambooPlanCollection()
// BambooApiClient.kt line 56-57:
    return get<BambooProjectDetailResponse>(...).map { it.plans.plan }`
- **Fix:** Delete the `key` and `name` fields from `BambooProjectDetailResponse`. If the response envelope's project key or name is needed by a future caller, they can be re-added then.

### BAMBOO-CLE-10 — Duplicate BambooApiClient instantiation in BuildFailureBridgeStartupActivity bypasses the cached project service
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/listeners/BuildFailureBridgeStartupActivity.kt`
- **Problem:** The private `bambooClientOrNull(project)` factory (lines 145–157) duplicates the URL-read + timeout-read + CredentialStore + BambooApiClient construction that already lives in `BambooServiceImpl.client` (which is `internal` and reachable from this file in the same module). Every failed build notification creates a brand-new OkHttp client (wrapped inside BambooApiClient via HttpClientFactory) instead of reusing the cached singleton. This wastes connection-pool and thread-pool resources on every build failure, and the construction logic (URL trimming, timeout clamping) may diverge from BambooServiceImpl over time.
- **Evidence:** `145:    private fun bambooClientOrNull(project: Project): BambooApiClient? {
146:        val settings = PluginSettings.getInstance(project)
147:        val baseUrl = settings.connections.bambooUrl.orEmpty().trimEnd('/')
148:        if (baseUrl.isBlank()) return null
149:        val timeouts = HttpCl`
- **Fix:** Replace `bambooClientOrNull(project)` with `(project.getService(BambooService::class.java) as? BambooServiceImpl)?.client`. This reuses the cached `BambooApiClient` (no new OkHttp pool) and guarantees that URL/timeout configuration stays in sync with BambooServiceImpl. Delete the private `bambooClientOrNull` method and its imports (CredentialStore, ServiceType, HttpClientFactory).

### BAMBOO-COV-1 — BambooApiClient.get(): no test for 429 rate-limit or 5xx server-error HTTP responses
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
- **Problem:** The `get()` private method maps HTTP 429 to `RATE_LIMITED` and any other non-2xx to `SERVER_ERROR` (lines 413-414). No test in any ApiClient test file exercises 429 or 500/503 responses against any of the public endpoints (`getLatestResult`, `getPlans`, `getBranches`, etc.). The `BambooApiClientTest` only tests 401, the XSRF path, and specific 404s, leaving the two most common transient-failure codes completely untested.
- **Evidence:** ````kotlin
429 -> { log.warn("[Bamboo:API] Rate limited (429)"); ApiResult.Error(ErrorType.RATE_LIMITED, "Bamboo rate limit exceeded") }
else -> { log.warn("[Bamboo:API] Server error (${it.code})"); ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned ${it.code}") }
```
— BambooApiClient.kt lines`
- **Fix:** Add to `BambooApiClientTest`: (1) `server.enqueue(MockResponse().setResponseCode(429))` then call `getPlans()` and assert `(result as ApiResult.Error).type == ErrorType.RATE_LIMITED`; (2) `server.enqueue(MockResponse().setResponseCode(503))` and assert `ErrorType.SERVER_ERROR`. Additionally test that `getLatestResult` on a 500 returns `SERVER_ERROR`, since that method is the polling hot path. Use `@ParameterizedTest` with `@ValueSource(ints = [429, 500, 502, 503])` to keep it concise.

### BAMBOO-COV-2 — BambooApiClient.get(): no test for IOException/network-failure propagation to NETWORK_ERROR
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
- **Problem:** The `get()` catch block (line 417-419) wraps `IOException` as `ApiResult.Error(ErrorType.NETWORK_ERROR)`. No existing MockWebServer-based test exercises this path — all tests enqueue valid responses. `BuildMonitorServiceTest.pollOnce handles API error gracefully` mocks the result at the mock layer, not at the HTTP layer, so the IOException branch is never triggered in any test. This path is the most common production failure (firewall drops, VPN disconnects, DNS failures).
- **Evidence:** ````kotlin
} catch (e: IOException) {
    log.warn("[Bamboo:API] Network error: ${e.message}")
    ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Bamboo: ${e.message}", e)
}
```
— BambooApiClient.kt lines 417-419. No test in any test file shuts down the server to exercise this path.`
- **Fix:** In `BambooApiClientTest`, call `server.shutdown()` before making the request (MockWebServer returns a connection-refused IOException) and assert `(result as ApiResult.Error).type == ErrorType.NETWORK_ERROR`. Test this for at least `getLatestResult`, `getPlans`, and `queueBuildWithStageSelection` as they are the highest-traffic endpoints. Pattern: `server.shutdown(); val result = client.getLatestResult("PROJ-BUILD"); assertTrue(result is ApiResult.Error); assertEquals(ErrorType.NETWORK_ERROR, (result as ApiResult.Error).type)`.

### BAMBOO-COV-3 — BambooApiClient.get(): no test for non-JSON 200 response body triggering PARSE_ERROR
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
- **Problem:** Lines 393-399 check for an unexpected `Content-Type` on a 2xx response and return `PARSE_ERROR` (e.g., when a reverse proxy returns `text/html` for a valid endpoint). Lines 402-407 catch JSON parse failures and return `PARSE_ERROR`. Neither branch is tested. The `AUTH_REDIRECT` path (HTML body on `rerunFailedJobs`/`enablePlanBranch`) is covered via `postForm`, but the equivalent GET-path protection (non-JSON Content-Type guard on `get()`) has no test.
- **Evidence:** ````kotlin
if (contentType.isNotBlank() &&
    !contentType.contains("application/json", ignoreCase = true) &&
    !contentType.contains("text/json", ignoreCase = true)) {
    return@withContext ApiResult.Error(
        ErrorType.PARSE_ERROR,
        "Unexpected response Content-Type: $contentType (e`
- **Fix:** Add to `BambooApiClientTest`: (1) `server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>proxy page</html>"))`; call `getPlans()` and assert `ErrorType.PARSE_ERROR`. (2) `server.enqueue(MockResponse().setBody("not-valid-json"))` then call `getLatestResult("PROJ-BUILD")` and assert `ErrorType.PARSE_ERROR`.

### BAMBOO-COV-4 — BambooApiClient.paginate(): no test for error returned on a non-first page
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
- **Problem:** The `paginate()` helper (lines 624-651) short-circuits on any page error via `is ApiResult.Error -> return result`. The existing pagination tests only cover the success path (single-page, two-page, echoed-start-index quirk) and a single first-page 401 failure. No test exercises the case where the first page succeeds but a subsequent page returns an error (e.g., the server goes down or returns 500 mid-pagination). This means the aggregated partial result is silently discarded and the caller sees a network error rather than partial data.
- **Evidence:** ````kotlin
when (val result = fetchPage(startIndex)) {
    is ApiResult.Error -> return result
    is ApiResult.Success -> {
```
— BambooApiClient.kt lines 634-636. `BambooApiClientPaginationTest` only has `getPlans returns error when first page fails` at line 135.`
- **Fix:** Add to `BambooApiClientPaginationTest`: enqueue a full page-1 success (100 items) then enqueue a 500 for page 2. Call `getPlans()` and assert the result is `ApiResult.Error` (not partial success). This documents the current contract that a mid-pagination error discards all collected data, making the behavior explicit and catchable.

### BAMBOO-COV-5 — BambooApiClient.stopBuild(): no error-path tests (only verifies the X-Atlassian-Token header on 200)
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
- **Problem:** The only test for `stopBuild` (`stopBuild sets X-Atlassian-Token header`, `BambooApiClientTest` line 360) verifies the HTTP method and header on a 200 response. There is no test for 401 (`AUTH_FAILED`), 403 (`FORBIDDEN`), 404 (`NOT_FOUND`), the `AUTH_REDIRECT` path (200+HTML), or `NETWORK_ERROR` (IOException). The `put()` method handles all these cases (lines 597-605) but they are untested. The `BambooServiceImpl.stopBuild()` maps each error type to a specific hint string that is equally untested.
- **Evidence:** ````kotlin
@Test
fun `stopBuild sets X-Atlassian-Token header`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200))
    client.stopBuild("PROJ-BUILD-42")
    val recorded = server.takeRequest()
    assertEquals("PUT", recorded.method)
    assertEquals("no-check", recorded.getHeader("`
- **Fix:** Add to `BambooApiClientTest`: (1) `server.enqueue(MockResponse().setResponseCode(401))` → assert `ErrorType.AUTH_FAILED`; (2) 403 → `FORBIDDEN`; (3) 404 → `NOT_FOUND`; (4) 200 with `Content-Type: text/html` → `AUTH_REDIRECT` (mirrors the existing `cancelBuild` test pattern at line 337 and `rerunFailedJobs` at line 387).

### BAMBOO-COV-6 — BambooApiClient.queueBuildWithStageSelection(): no test for parse failure on the queue-response body
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
- **Problem:** Lines 269-275 catch a JSON parse failure on the `BambooQueueResponse` body and return `ErrorType.PARSE_ERROR`. All existing tests for `queueBuildWithStageSelection` supply well-formed JSON responses. No test exercises a successful (200) POST to the queue endpoint followed by a malformed JSON body (e.g., `{"buildResultKey":null}` missing required fields or truncated JSON). This is a real edge case when Bamboo returns a redirect or HTML page with status 200.
- **Evidence:** ````kotlin
try {
    ApiResult.Success(json.decodeFromString<BambooQueueResponse>(raw.data))
} catch (e: Exception) {
    log.warn("[Bamboo:API] queueBuildWithStageSelection parse failed: ${e.message}")
    ApiResult.Error(ErrorType.PARSE_ERROR, "Failed to parse queue response: ${e.message}")
}
```
—`
- **Fix:** Add to `BambooApiClientStageSelectionTest`: `server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))`; call `queueBuildWithStageSelection("PROJ-BUILD", emptyMap(), null)` and assert `(result as ApiResult.Error).type == ErrorType.PARSE_ERROR`.

### BAMBOO-COV-8 — BambooServiceImpl.getTestResults(): no tests at service level for success, error, or not-configured paths
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt`
- **Problem:** `BambooServiceImpl.getTestResults()` (lines 200-239) maps the `BambooJobTestResultDto` into a `TestResultsData` with `FailedTestData` entries, covering three branches: (a) not configured, (b) API error, (c) success with failed/successful test counts. None of these branches has a test. The only existing `BambooTestResultConverter` tests (`BambooTestResultConverterReDoSTest`) target the static converter directly and do not go through the service layer. There is no test that skipped-test counts are correctly propagated, that an empty failed-tests list produces `failedTests = emptyList()`, or that an API error from `getTestResults()` surfaces as `isError = true`.
- **Evidence:** ````kotlin
override suspend fun getTestResults(resultKey: String): ToolResult<TestResultsData> {
    val api = client ?: return ToolResult(
        data = TestResultsData(total = 0, passed = 0, failed = 0, skipped = 0),
        ...
    )
```
— BambooServiceImpl.kt lines 200-240. `grep -r 'getTestResu`
- **Fix:** Create `BambooServiceImplTestResultsTest` with three cases: (1) mock `getTestResults` returning a DTO with 5 passed, 2 failed, 1 skipped — assert `data.total==8`, `data.failed==2`; (2) mock returning `ApiResult.Error(ErrorType.NOT_FOUND, "...")` — assert `isError==true`; (3) set `testClientOverride = null` (or do not set it, leaving `client` null) and assert `isError==true` with the not-configured hint.

### BAMBOO-COV-9 — BambooServiceImpl.getPlanVariables(): no test for all-strategies-fail path returning isError=true
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt`
- **Problem:** `getPlanVariables()` (lines 307-372) has a three-strategy fallback: Strategy A (`variableContext`), then Strategy C (most-recent build's `getBuildVariables`). The existing `BambooServiceImplStrategyCTest` only tests the A-fails→C-succeeds branch. The all-strategies-fail path (lines 361-372) — when variableContext returns empty and Strategy C also fails — returns `isError = true`. This path has zero test coverage. Additionally, the Strategy A returning empty list (line 319 `contextResult.data.isNotEmpty()`) before falling to Strategy C is untested for the case where variableContext succeeds but with zero items.
- **Evidence:** ````kotlin
val errorMsg = when (contextResult) {
    is ApiResult.Error -> contextResult.message
    else -> "variableContext returned empty"
}
return ToolResult(
    data = emptyList(),
    summary = "Error fetching plan variables for $planKey: $errorMsg",
    isError = true,
    hint = "Check Bambo`
- **Fix:** Add to `BambooServiceImplStrategyCTest` or a new file: (1) mock `getPlanVariableContext` returning `ApiResult.Error` and `getRecentResults` also returning `ApiResult.Error` — assert `result.isError == true`; (2) mock `getPlanVariableContext` returning `ApiResult.Success(emptyList())` and `getRecentResults` returning an empty build list — assert `result.isError == true` (no builds to fall back to).

### BAMBOO-COV-12 — BuildLogParser.parse(): no test for empty/blank input string
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildLogParser.kt`
- **Problem:** The `parse()` method (line 26) calls `buildLog.lineSequence()` which works on any string including empty. The tests cover: log with errors, log with warnings, clean log, and generic error. No test exercises `parse("")` or `parse("   \n   ")` (blank/whitespace input). The `.count { it == '\n' }` call on line 28 on an empty string is harmless but untested. Since `getBuildLog()` returns the raw string from the server (and documents that empty body returns `""`), a blank log passed through to the parser should produce an empty list without any indexing failure.
- **Evidence:** ````kotlin
fun parse(buildLog: String): List<BuildError> {
    val lines = buildLog.lineSequence()
    log.info("[Bamboo:Parser] Starting build log parsing (~${buildLog.count { it == '\n' } + 1} lines)")
```
— BuildLogParser.kt lines 26-28. `BuildLogParserTest` has 4 tests, none with blank/empty inpu`
- **Fix:** Add to `BuildLogParserTest`: (1) `BuildLogParser.parse("")` → assert `isEmpty()`; (2) `BuildLogParser.parse("  \n  \n  ")` → assert `isEmpty()`. These are trivial to add and pin the boundary behavior.

### BAMBOO-COV-14 — BambooServiceImpl.client lazy property: URL-change invalidation not tested — stale cached client risk
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt`
- **Problem:** The `client` property (lines 56-72) caches a `BambooApiClient` keyed by `cachedBaseUrl`. If `settings.connections.bambooUrl` changes mid-session (e.g., the user updates the settings page), `url != cachedBaseUrl` triggers a rebuild. No test exercises this state transition: all service tests inject `testClientOverride` and bypass the caching logic entirely. A bug in the invalidation path (e.g., a threading race on `@Volatile` without synchronization when the URL and client are written non-atomically) would go undetected.
- **Evidence:** ````kotlin
if (url != cachedBaseUrl || cachedClient == null) {
    cachedBaseUrl = url
    ...
    cachedClient = BambooApiClient(...)
}
return cachedClient
```
— BambooServiceImpl.kt lines 61-70. No test covers the URL-change branch; all tests set `testClientOverride`.`
- **Fix:** Add a test (without `testClientOverride`) that sets `settings.connections.bambooUrl` to URL-A, calls `service.client`, then changes to URL-B, calls `service.client` again, and asserts the returned client targets URL-B (not URL-A). Since this requires controlling `PluginSettings`, use a mock `Project` that returns a controllable settings instance — already the pattern used in `BambooServiceImplStrategyCTest`. Assert that two different `BambooApiClient` instances are returned, not the same cached one.

### BAMBOO-COV-15 — BuildMonitorService.switchBranch(): stateFlow reset to null before re-poll not tested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt`
- **Problem:** `switchBranch()` (lines 203-207) resets `_stateFlow.value = null` then calls `startPolling()`. This reset is important for the UI — the panel must clear the displayed build state before showing the new branch's build. No test verifies that `stateFlow.value` is null immediately after `switchBranch()` is called and before the poller fires the first result. `BuildMonitorFocusLifecycleTest` covers the polling lifecycle via the focus flow (which calls `startPolling`) but does NOT test the `switchBranch` path or the intermediate null state.
- **Evidence:** ````kotlin
fun switchBranch(planKey: String, newBranch: String, intervalMs: Long = 30_000) {
    log.info("[Bamboo:Monitor] Switching branch to '$newBranch' for planKey=$planKey")
    _stateFlow.value = null
    startPolling(planKey, newBranch, intervalMs)
}
```
— BuildMonitorService.kt lines 203-207`
- **Fix:** Add to `BuildMonitorServiceTest`: call `pollOnce` to populate `stateFlow` with a non-null value, then call `switchBranch("PROJ-BUILD", "other-branch")`, and assert `service.stateFlow.value == null` immediately after (before the next poll). Use turbine to snapshot the flow: `stateFlow.test { ... awaitItem() shouldBe null ... }`.

### BAMBOO-COV-16 — PlanDetectionService.validate(): concurrent-call race on Mutex not tested — two concurrent validators for same key both make API calls
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/PlanDetectionService.kt`
- **Problem:** `validate()` (lines 128-156) releases the `cacheMutex` around the HTTP call (intentionally, to avoid blocking) and re-acquires it to write the result. The comment says "Two concurrent validators racing on the same candidate may both call the API once, but the writes are idempotent". No test exercises concurrent validation of the same key. The `in-memory positive cache avoids repeated API calls within a session` test (line 550 in `PlanDetectionServiceTest`) calls `validate()` serially, not concurrently. A concurrent call could expose a bug if the mutex is incorrectly held across the network call.
- **Evidence:** ````kotlin
// 2. Network call WITHOUT the lock — long-running suspend.
val valid = when (val result = apiClient.validatePlan(candidate)) { ... }
// 3. Write-side: brief lock to record the result.
cacheMutex.withLock {
    if (valid) { positiveCache.add(candidate) } else { ... }
```
— PlanDetectionSer`
- **Fix:** Add a test that launches two coroutines simultaneously both calling `service.validate("PROJ-MAIN")` with the API client mock configured with a small delay. Use `coVerify(atMost = 2) { apiClient.validatePlan("PROJ-MAIN") }` to assert the API is called at most twice (not more, which would indicate the cache read is broken). Use `runTest` with two `launch { validate(...) }` blocks and `joinAll()`.

## `:handover` (35 confirmed)

### HANDOVER-COR-1 — UI mutations on IO thread: onRescan() called from Dispatchers.IO after applyFixes
- **Severity:** High  **Category:** bug  **Lens:** correctness
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/cards/CopyrightFixCard.kt`
- **Problem:** `onFixAll()` launches a coroutine on `Dispatchers.IO` (line 154: `scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`). After the `withContext(Dispatchers.EDT)` block (lines 156–169), execution returns to the IO thread and line 171 calls `onRescan()`. Inside `onRescan()`, lines 113–114 directly mutate Swing components: `rescanButton.isEnabled = false` and `statusLabel.text = "Scanning..."`. These UI mutations execute on the IO thread, violating IntelliJ's threading model and risking visual corruption or JVM-level assertion failures under the Event Dispatch Thread checker.
- **Evidence:** `154:        scope.launch {
171:            onRescan()   // called from IO dispatcher
---
112:    private fun onRescan() {
113:        rescanButton.isEnabled = false  // <-- OFF-EDT
114:        statusLabel.text = "Scanning..." // <-- OFF-EDT`
- **Fix:** Wrap the `onRescan()` call inside a `withContext(Dispatchers.EDT)` block at line 171, or change `onRescan()` itself to not mutate UI state directly when called from a non-EDT context. Specifically: replace line 171 with `withContext(Dispatchers.EDT) { onRescan() }`. Alternatively refactor so the post-fix rescan always starts via `SwingUtilities.invokeLater { onRescan() }`.

### HANDOVER-COR-2 — Copyright fix silently no-ops for headers between lines 15–30 (scan vs fix window mismatch)
- **Severity:** High  **Category:** bug  **Lens:** correctness
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/cards/CopyrightFixCard.kt`
- **Problem:** `CopyrightFixService.analyzeFile` scans the first **30** lines to detect `YEAR_OUTDATED` (lines 111, 141 in `CopyrightFixService.kt`). However, `applyFixes` in `CopyrightFixCard` reads only the first **15** lines as `headerRegion` (line 221) before calling `updateYearInHeader`. For files where the copyright year appears between lines 16–30 (common for Apache/EUPL/GPL headers), `analyzeFile` correctly classifies them `YEAR_OUTDATED`, but `applyFixes` misses the year in the 15-line window, `updateYearInHeader` returns the region unchanged, and no document mutation is written. The UI reports "Fixed N file(s)" but the file is untouched — a silent correctness failure.
- **Evidence:** `CopyrightFixService.kt:141:        val headerRegion = content.lines().take(30).joinToString("\n")
CopyrightFixCard.kt:221:                            val headerRegion = original.lines().take(15).joinToString("\n")`
- **Fix:** Change `CopyrightFixCard.applyFixes` line 221 from `original.lines().take(15)` to `original.lines().take(30)` to match the scan window used in `CopyrightFixService.analyzeFile`. Also update the corresponding line 225 `drop(15)` to `drop(30)`. Extract the magic constant `30` into a shared `const val HEADER_SCAN_LINES = 30` in `CopyrightFixService` and reference it from both the service and the card.

### HANDOVER-ARC-1 — TimeLogCard.setTicket() has zero call sites — Log Work permanently disabled
- **Severity:** High  **Category:** bug  **Lens:** architecture
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/cards/TimeLogCard.kt`
- **Problem:** TimeLogCard declares a public `setTicket(ticketKey: String?)` method at line 140, but it is never called anywhere in the codebase. The field `activeTicketId` initializes to `""` and is never updated in production. `refreshLogButtonState()` checks `ticketOk = activeTicketId.isNotBlank()` which is always false, so the Log Work button is permanently disabled and the card always shows the empty state 'Select a ticket to log time.' HandoverPanel.applyState() (line 101) fans state only to `header.updateState(state)`, `checksTab.updateState(state)`, and `banner.setFailures(...)` — ActionsTab/TimeLogCard receive nothing. The 'todayWorkLogged' checklist dot can never turn green via the UI card.
- **Evidence:** `fun setTicket(ticketKey: String?) {
    activeTicketId = ticketKey.orEmpty()
    if (ticketKey.isNullOrBlank()) {
        cardLayout.show(cardPanel, "empty")
    } else {
        // never reached in production
    }
}`
- **Fix:** In HandoverPanel.applyState() (or by subscribing TimeLogCard to WorkflowContextService.activeTicketFlow), call `timeLog.setTicket(state.ticketId.takeIf { it.isNotBlank() })` after each state emission. Since HandoverPanel does not hold a reference to the inner TimeLogCard, either (a) expose a method on ActionsTab that delegates to TimeLogCard, or (b) wire ActionsTab to collect `WorkflowContextService.activeTicketFlow` directly in its own scope and call timeLog.setTicket() on changes.

### HANDOVER-ARC-2 — CopyrightFixCard.applyFixes reads only 15 header lines while CopyrightFixService.analyzeFile scans 30
- **Severity:** High  **Category:** bug  **Lens:** architecture
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/cards/CopyrightFixCard.kt`
- **Problem:** CopyrightFixService.analyzeFile() (CopyrightFixService.kt line 111) scans the first 30 lines for copyright headers. When it finds a YEAR_OUTDATED entry from lines 16–30, it stores the stale year expression. CopyrightFixCard.applyFixes() at line 221 then reads only `original.lines().take(15)` to build its `headerRegion`. updateYearInHeader() finds no match in the 15-line region, so `rewritten == headerRegion` and the file is left unchanged. The UI reports a failed fix attempt but the status stays YEAR_OUTDATED indefinitely. The scan-window mismatch is the root cause.
- **Evidence:** `// CopyrightFixService.kt line 111:
val headerLines = content.lines().take(30)

// CopyrightFixCard.kt line 221:
val headerRegion = original.lines().take(15).joinToString("\n")`
- **Fix:** Change line 221 in CopyrightFixCard.kt from `original.lines().take(15)` to `original.lines().take(30)` to match the 30-line window used by CopyrightFixService.analyzeFile(). Also update the corresponding reassembly at line 224: change `.drop(15)` to `.drop(30)`. Consider extracting the constant (e.g., `HEADER_SCAN_LINES = 30`) into CopyrightFixService.companion so both paths share it.

### HANDOVER-CLE-5 — CopyrightFixCard.applyFixes uses take(15) but CopyrightFixService.analyzeFile uses take(30) — silent no-op for headers in lines 16-30
- **Severity:** High  **Category:** cleanup  **Lens:** cleanup
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/cards/CopyrightFixCard.kt`
- **Problem:** In `applyFixes()` (line 221), the header region scanned for year replacement is `original.lines().take(15)`. However, `CopyrightFixService.analyzeFile()` (lines 111, 141) was intentionally raised to `take(30)` to detect long Apache/EUPL headers (audit finding handover:F-3, confirmed by test `analyzeFile scans 30 lines for year update`). This mismatch means: `analyzeFile` correctly marks a file `YEAR_OUTDATED` when the copyright year appears on lines 16-30, but `applyFixes` only reads lines 1-15 and calls `service.updateYearInHeader(headerRegion)` on that truncated region — which finds nothing to update, falls through to `if (rewritten == headerRegion) original`, and silently leaves the file unchanged. The Fix All button reports success but the year is not updated.
- **Evidence:** `// CopyrightFixCard.kt:221
val headerRegion = original.lines().take(15).joinToString("\n")
// CopyrightFixService.kt:111, 141
val headerLines = content.lines().take(30)
val headerRegion = content.lines().take(30).joinToString("\n")
// Test comment: "Year is on line 20 — was missed when analyzeFile t`
- **Fix:** Change line 221 of `CopyrightFixCard.kt` from `val headerRegion = original.lines().take(15).joinToString("\n")` to `val headerRegion = original.lines().take(30).joinToString("\n")`. Also change line 227 from `val rest = original.lines().drop(15).joinToString("\n")` to `drop(30)` to reassemble the full file correctly. Add a regression test in `CopyrightFixServiceTest` covering a year on line 20.

### HANDOVER-ARC-3 — WorkflowEvent.JiraCommentPosted has no production emit site — EventBus handler in HandoverStateService is dead code
- **Severity:** Medium  **Category:** architecture  **Lens:** architecture
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt`
- **Problem:** HandoverStateService.handleEvent() at lines 319–321 handles WorkflowEvent.JiraCommentPosted and flips jiraCommentPosted=true. However, no production code ever emits this event: JiraServiceImpl.addComment() (jira module) returns a ToolResult but does not call eventBus.emit(JiraCommentPosted(...)). A global search confirms the only emit sites are in tests. The 'Jira comment posted' checklist dot can only turn green via the direct markJiraCommentPosted() call in ShareTab.handlePostComment(). If a comment is posted via the agent's jira tool (JiraTool action add_comment), the HandoverState is never updated.
- **Evidence:** `// HandoverStateService.kt line 319:
is WorkflowEvent.JiraCommentPosted -> {
    log.info("[Handover:State] jiraCommentPosted=true")
    current.copy(jiraCommentPosted = true)
}
// Zero production emit sites found for WorkflowEvent.JiraCommentPosted`
- **Fix:** Add an EventBus emission in JiraServiceImpl.addComment() on success: `eventBus.emit(WorkflowEvent.JiraCommentPosted(key, commentId))`. Since JiraServiceImpl already has access to EventBus, this is a one-line change. Alternatively, since the agent's JiraTool also calls addComment(), it could emit the event after a successful result. Either path closes the gap between the EventBus contract and the actual state-update path.

### HANDOVER-ARC-4 — PluginSettings.aiSummariesEnabled is surfaced in HandoverConfigurable but never consulted by HandoverAiSummaryCache
- **Severity:** Medium  **Category:** architecture  **Lens:** architecture
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverAiSummaryCache.kt`
- **Problem:** HandoverConfigurable exposes a checkbox 'Compute {ai.changeSummary} and {ai.ticketSummary}' bound to PluginSettings.State.aiSummariesEnabled. The setting is stored correctly. However, HandoverAiSummaryCache.changeSummary() and ticketSummary() never read this setting — they always attempt LLM generation whenever a generator EP is available. The toggle in Settings → Handover → AI summaries is therefore a dead control: disabling it has no effect on runtime behavior.
- **Evidence:** `// HandoverConfigurable.kt line 112:
checkBox("Compute {ai.changeSummary} and {ai.ticketSummary}")
    .bindSelected(
        getter = { state.aiSummariesEnabled },
        setter = { state.aiSummariesEnabled = it }
    )
// HandoverAiSummaryCache: no reference to aiSummariesEnabled anywhere`
- **Fix:** In HandoverAiSummaryCache, inject PluginSettings (add to the DI constructor). At the top of changeSummary() and ticketSummary(), check `if (!settings.state.aiSummariesEnabled) return HandoverPlaceholderValue.unavailable("AI summaries disabled")`. The test constructor already accepts injected collaborators, so adding PluginSettings there is straightforward.

### HANDOVER-ARC-5 — PLACEHOLDER_CATALOG keys build.planKey, build.number, automation.url silently resolve to unavailable — missing resolver branches
- **Severity:** Medium  **Category:** architecture  **Lens:** architecture
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverPlaceholderResolver.kt`
- **Problem:** HandoverConfigurable.PLACEHOLDER_CATALOG (core/src/.../HandoverConfigurable.kt line 185) lists 'build.planKey', 'build.number', and 'automation.url' as selectable chip keys. Users enabling these chips in Settings expect a value. HandoverPlaceholderResolver.resolve() (lines 70–123) only handles 'build.url' (as permanently unavailable) and has no cases for 'build.planKey', 'build.number', or 'automation.url'. All three fall to the else branch returning HandoverPlaceholderValue.unavailable('unknown placeholder'). The values ARE available: build.planKey and build.number come from HandoverState.buildStatus.planKey/buildNumber.
- **Evidence:** `// HandoverConfigurable.kt line 185:
val PLACEHOLDER_CATALOG = listOf(
    ..., "build.url", "build.planKey", "build.number",
    ..., "automation.url", ...
)
// HandoverPlaceholderResolver.resolve(): no case for build.planKey, build.number, or automation.url`
- **Fix:** Add three `when` branches to HandoverPlaceholderResolver.resolve(): `"build.planKey"` → `state.buildStatus?.planKey`; `"build.number"` → `state.buildStatus?.buildNumber?.toString()`; `"automation.url"` → `state.suiteResults.lastOrNull()?.bambooLink`. Return HandoverPlaceholderValue.available(value) when non-null, or unavailable with a descriptive reason. Remove 'build.planKey', 'build.number', 'automation.url' from the catalog if they are genuinely unsupported.

### HANDOVER-COR-3 — Non-atomic read-modify-write on HandoverStateService._stateFlow lost under concurrent events and EDT mark calls
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt`
- **Problem:** `handleEvent` reads `_stateFlow.value` into `current` (line 220), computes `next`, then writes `_stateFlow.value = next` (line 326). Concurrently, `markCopyrightFixed()` (331), `markJiraCommentPosted()` (336), `markJiraTransitioned()` (341–343), and `markWorkLogged()` (349) all do the same non-atomic pattern from the EDT. Since `handleEvent` runs on the coroutine collector thread and the `markX()` methods are called from EDT, concurrent invocations can overwrite each other. Specifically: if `handleEvent` reads `current` (with `copyrightFixed=true`), then `markCopyrightFixed()` writes `copyrightFixed=true` first, then `handleEvent` overwrites with `next` whose `copyrightFixed` reflects the value at the time of the earlier read — the `markCopyrightFixed()` write is silently clobbered, causing the checklist dot to revert to PENDING incorrectly. Note: `resetStatusSlices()` correctly uses `_stateFlow.update { }` (line 113) but the other methods do not.
- **Evidence:** `220:        val current = _stateFlow.value
326:        _stateFlow.value = next
---
331:        _stateFlow.value = _stateFlow.value.copy(copyrightFixed = true)
349:        _stateFlow.value = _stateFlow.value.copy(todayWorkLogged = true)`
- **Fix:** Replace all non-atomic read-modify-write patterns with `_stateFlow.update { current -> current.copy(...) }`. In `handleEvent`, change: `val current = _stateFlow.value; val next = when(event) { ... }; _stateFlow.value = next` to `_stateFlow.update { current -> when(event) { ... } }`. Similarly change all `markX()` methods to use `_stateFlow.update { it.copy(field = value) }`.

### HANDOVER-COR-4 — JiraPreviewPane liveResults collector job is leaked — never cancelled on Disposable.dispose()
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/editor/JiraPreviewPane.kt`
- **Problem:** `JiraPreviewPane.init` creates an isolated root `Job` (`val collectorJob = Job()`, line 90) and launches a `SharedFlow.collect` coroutine on `CoroutineScope(cs.coroutineContext + collectorJob)`. Because `collectorJob` has no parent (it is not a child of `cs`'s Job), it is never cancelled when either `cs` is cancelled or when `dispose()` is called (line 131). Every `JiraPreviewPane` instance that is constructed but later replaced (e.g., when a template is selected, triggering a preview pane re-construction, or when the tool window tab is closed and re-opened) leaves a permanently-running SharedFlow collector that keeps the pane object reachable and continues processing `liveResults` events for stale content.
- **Evidence:** `90:        val collectorJob = Job()
91:        CoroutineScope(cs.coroutineContext + collectorJob).launch {
...
131:    override fun dispose() {
132:        // Nothing to dispose — the cs is owned by the parent
133:    }`
- **Fix:** Store `collectorJob` as a field and cancel it in `dispose()`: `private val collectorJob = Job()` at class level, use it in `init` as before, and add `collectorJob.cancel()` to the `dispose()` method. Alternatively — since the intent is to avoid the collector being a child of `cs` — use `cs.launch` with a `Job()` stored as a field and cancelled on dispose.

### HANDOVER-COR-6 — HandoverAiSummaryCache.getOrCompute uses coroutineScope{} that blocks the caller until the LLM response arrives, defeating Deferred coalescing intent
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverAiSummaryCache.kt`
- **Problem:** `getOrCompute` wraps `cache.computeIfAbsent(key) { async { ... } }` inside `coroutineScope { }` (lines 165–182). `coroutineScope { }` suspends until **all its child coroutines** complete. Since the `async { ... }` block (the LLM call) is launched as a child of this `coroutineScope`, the scope does not return until the LLM call finishes. The returned `Deferred` is already completed at line 183 when `deferred.await()` is called. The class documentation says the Deferred coalescing allows concurrent callers to "share one in-flight compute" without blocking until `await()`, but because each caller's `coroutineScope { }` blocks until the LLM response arrives, the concurrency benefit is lost — every caller to `changeSummary()` / `ticketSummary()` will block at the `coroutineScope { }` boundary for the full LLM latency, even on a cache hit for the second-concurrent caller.
- **Evidence:** `165:        val deferred: Deferred<HandoverPlaceholderValue> = coroutineScope {
166:            cache.computeIfAbsent(key) {
167:                async {
...  LLM call ...
180:                }
181:            }
182:        }
183:        return try {
184:            deferred.await()`
- **Fix:** Replace the `coroutineScope { }` wrapper with the service's own `cs` scope: `val deferred = cache.computeIfAbsent(key) { cs.async { ... } }`. This makes `async { }` a child of the persistent service scope rather than a transient `coroutineScope`, preserving the Deferred in the map across coroutine cancellations of individual callers. The existing `deferred.await()` + `cache.remove(key, deferred)` in the catch block (lines 183–191) then correctly handles cancellation cleanup.

### HANDOVER-COV-5 — HandoverTemplateStore: rename() method has zero test coverage
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverTemplateStore.kt`
- **Problem:** Lines 230-252: `rename(id, newName)` performs a `Files.move()` and calls `rescan()`. None of the 11 tests in `HandoverTemplateStoreTest` exercise this method. The BUNDLED-rejection guard (throws `UnsupportedOperationException`), the PROJECT-origin global-clash guard (`IllegalArgumentException`), the happy-path file rename, and the `NoSuchElementException` from `findOrThrow` on an unknown id are all untested.
- **Evidence:** `suspend fun rename(id: String, newName: String): Unit = withContext(Dispatchers.IO) {
    val template = findOrThrow(id)
    if (template.origin == HandoverTemplateOrigin.BUNDLED) {
        throw UnsupportedOperationException("Cannot rename bundled template")
    }`
- **Fix:** Add three tests to `HandoverTemplateStoreTest`: (1) rename a GLOBAL template — assert old file gone, new file present, `templates.value` updated; (2) rename a BUNDLED template — assert `UnsupportedOperationException`; (3) rename a PROJECT template when GLOBAL with the same newName exists — assert `IllegalArgumentException`.

### HANDOVER-COV-7 — HandoverWikiPreviewRendererService: 403/AUTH failure path and transient-error notification — both untested independently
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverWikiPreviewRenderer.kt`
- **Problem:** Lines 109-128: the auth-failure branch is triggered by `401`, `403`, or `AUTH` in the error summary. Only 401 is covered by the existing test (`live failure flips isLiveAvailable to false and notifies once`). The 403 branch and the `AUTH` substring match are not tested. The transient failure branch (lines 121-127) fires `notifyWarning` with groupId `workflow.handover.wiki.transient` — this notification call is never asserted in any test (the 5xx test only asserts `isLiveAvailable() == true`, not that the transient notification was fired).
- **Evidence:** `val isAuthFailure = summary.contains("401") ||
    summary.contains("403", ignoreCase = true) ||
    summary.contains("AUTH", ignoreCase = true)
if (isAuthFailure) { liveAvailable.set(false) ... }
else {
    // Transient failure (5xx, 429, network) — log but keep live available.
    notifications?.n`
- **Fix:** Add two tests to `HandoverWikiPreviewRendererLiveTest`: (1) 403 response flips `isLiveAvailable` to false and calls `notifyWarning` exactly once with groupId `workflow.handover.wiki`; (2) 5xx response keeps `isLiveAvailable` true and calls `notifyWarning` with groupId `workflow.handover.wiki.transient`.

### HANDOVER-COV-14 — HandoverAiSummaryCache: concurrent changeSummary calls coalesce to one LLM invocation — untested
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverAiSummaryCache.kt`
- **Problem:** Lines 165-181: `cache.computeIfAbsent` is the coalescing mechanism for concurrent callers. The comment at line 144 explicitly documents this property (`concurrent misses for the same key produce exactly one Deferred`). The existing tests only verify single-caller cache hits; no test exercises concurrent `changeSummary()` calls running simultaneously to confirm that the LLM is called exactly once under concurrent load.
- **Evidence:** `val deferred: Deferred<HandoverPlaceholderValue> = coroutineScope {
    cache.computeIfAbsent(key) {
        async {
            runCatching {
                val result = gen.generate(prompt)
                ...
            }
        }
    }
}`
- **Fix:** Add a test using `runTest(UnconfinedTestDispatcher())` that launches 5 concurrent `launch { cache.changeSummary() }` calls, advances until idle, and then asserts `coVerify(exactly = 1) { generator.generate(any()) }` — mirroring the pattern already used in `HandoverWikiPreviewRendererLiveTest` for the same coalescing concern.

### HANDOVER-ARC-6 — CopyrightCellRenderer uses bare JLabel instead of JBLabel
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/cards/CopyrightFixCard.kt`
- **Problem:** CopyrightCellRenderer (lines 283–286) creates `JLabel(rel, icon, ...)` and `JLabel(suffix)` instead of `JBLabel`. The project convention requires JetBrains components for all UI elements. JLabel does not respect IDE HiDPI scaling, font scaling, or theme conventions the way JBLabel does. Both labels are user-visible (file path and status suffix in the copyright file list).
- **Evidence:** `// CopyrightFixCard.kt lines 283-286:
val left = JLabel(rel, icon, SwingConstants.LEFT).apply {
    font = font.deriveFont(Font.PLAIN)
}
val right = JLabel(suffix).apply {`
- **Fix:** Replace `JLabel(rel, icon, SwingConstants.LEFT)` with `JBLabel(rel, icon, SwingConstants.LEFT)` and `JLabel(suffix)` with `JBLabel(suffix)` in CopyrightCellRenderer. Add `import com.intellij.ui.components.JBLabel` and remove `import javax.swing.JLabel`.

### HANDOVER-COR-5 — handleCopyWiki emits COPY_EMAIL action instead of a wiki-specific action, corrupting override audit log
- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/tabs/ShareTab.kt`
- **Problem:** `handleCopyWiki()` (line 159–166) emits `WorkflowEvent.HandoverAction.COPY_EMAIL` at line 160 when the user copies the Jira wiki-format text to clipboard. The correct semantic is that the user performed a "Copy wiki" action (distinct from copying formatted HTML email). `HandoverOverrideTracker` persists every `HandoverOverride` event with its action type; since `COPY_WIKI` does not exist in the `HandoverAction` enum (`POST_JIRA, COPY_EMAIL, FIX_COPYRIGHT, LOG_WORK, COPY_CHIP`), the developer picked the wrong value. This means the override audit log misidentifies wiki copies as email copies, making the 30-day count in Settings unreliable for distinguishing which handover paths the user actually used.
- **Evidence:** `159:    internal suspend fun handleCopyWiki(resolvedMarkup: String) {
160:        emitOverrideIfNeeded(WorkflowEvent.HandoverAction.COPY_EMAIL)

WorkflowEvent.kt:90:        POST_JIRA, COPY_EMAIL, FIX_COPYRIGHT, LOG_WORK, COPY_CHIP`
- **Fix:** Add `COPY_WIKI` to the `HandoverAction` enum in `WorkflowEvent.kt` (next to `COPY_EMAIL`). Then change line 160 in `ShareTab.kt` to `emitOverrideIfNeeded(WorkflowEvent.HandoverAction.COPY_WIKI)`.

### HANDOVER-CLE-1 — HandoverState.currentBranch is a dead field — defined but never read
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/model/HandoverModels.kt`
- **Problem:** The `currentBranch: String = ""` field on `HandoverState` (line 9) is declared but never written to (no `copy(currentBranch = ...)` call anywhere in the module) and never read by any consumer (`ChecksTab`, `HandoverPanel`, `ShareTab`, `HandoverTicketHeader`, `HandoverPlaceholderResolver` — none reference it). It is also never set during `resetForNewTicket` or `resetStatusSlices`. It is a dead data field carrying zero information.
- **Evidence:** `val currentBranch: String = "",  // line 9 — zero grep hits for reads in handover/src/main/`
- **Fix:** Delete `val currentBranch: String = ""` from `HandoverState`. If branch context is needed in future, source it from `WorkflowContextService.state.value.activeBranch` (the canonical source) rather than a stale mirror on `HandoverState`.

### HANDOVER-CLE-2 — JiraClosureService.project field is stored but never consumed
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/JiraClosureService.kt`
- **Problem:** Lines 16-19 declare `private var project: Project? = null` and the DI constructor stores the `project` argument into it. Neither `buildClosureComment()` nor `mergeDockerTags()` — the only methods on the class — ever reference `this.project`. The field is write-only dead storage. The `@TestOnly` no-arg constructor leaves `project` null, confirming the service logic does not need it.
- **Evidence:** `private var project: Project? = null
constructor(project: Project) { this.project = project }
// No reference to `project` or `this.project` in buildClosureComment() or mergeDockerTags()`
- **Fix:** Remove the `private var project: Project? = null` field and the `this.project = project` assignment from the DI constructor. The `Project` parameter in `constructor(project: Project)` and `companion.getInstance(project)` are correct IntelliJ DI boilerplate and must remain; only the stored field is dead.

### HANDOVER-CLE-3 — TimeTrackingService.hoursToSeconds() is dead in production — test-only callers
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/TimeTrackingService.kt`
- **Problem:** The public `fun hoursToSeconds(hours: Double): Int` (line 42) is called only from `TimeTrackingServiceTest` (lines 36-40). No production code in `:handover`, `:agent`, `:core`, or any other module calls it. `TimeLogCard.onLogClicked()` uses `hoursToJiraTimeString()` instead. The method was originally planned as a utility for `JiraService.logWork` but the design shifted to use the string form. A `docs/research/2026-05-07-handover-wireup-plan.md` note documents this transition.
- **Evidence:** `fun hoursToSeconds(hours: Double): Int = (hours * 3600).toInt()  // line 42
// Grep across entire worktree: only callers are TimeTrackingServiceTest lines 36-40`
- **Fix:** Delete `fun hoursToSeconds(hours: Double): Int = (hours * 3600).toInt()` from `TimeTrackingService` and delete the four test cases in `TimeTrackingServiceTest` that exercise it. No production callsite exists.

### HANDOVER-CLE-4 — TimeTrackingService.formatStartedDate() and ISO_FORMAT are dead in production
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/TimeTrackingService.kt`
- **Problem:** `fun formatStartedDate(year, month, day, hour, minute)` (lines 64-70) and the companion `private val ISO_FORMAT` (line 87) are called only from `TimeTrackingServiceTest.formatStartedDate produces ISO 8601` — no production code calls them. `TimeLogCard.onLogClicked()` (line 221) constructs the `OffsetDateTime` directly via `LocalDateTime.of(...).atOffset(ZoneOffset.UTC)` (a comment there even references the former `formatStartedDate(.., 9, 0)` call that was replaced). The three imports `LocalDateTime`, `ZoneOffset`, `DateTimeFormatter` become dead if this method is removed.
- **Evidence:** `fun formatStartedDate(year: Int, month: Int, day: Int, hour: Int, minute: Int): String {
    val formatted = LocalDateTime.of(year, month, day, hour, minute, 0)
        .atOffset(ZoneOffset.UTC)
        .format(ISO_FORMAT)  // line 65-67
// TimeLogCard.kt:220 comment: "9:00 UTC matches the prior `fo`
- **Fix:** Delete `fun formatStartedDate(...)` (lines 64-70), `private val ISO_FORMAT` (line 87), and the three now-unused imports (`LocalDateTime`, `ZoneOffset`, `DateTimeFormatter`). Delete the corresponding test case `formatStartedDate produces ISO 8601`. Note: `JiraServiceImpl.kt:1856` has a code comment `"matches TimeTrackingService.ISO_FORMAT"` — update that comment to reference the local pattern string directly.

### HANDOVER-CLE-6 — HandoverWikiPreviewRenderer.escape() duplicates core HtmlEscape.escapeHtml and is incomplete (misses single-quote)
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverWikiPreviewRenderer.kt`
- **Problem:** Lines 325-326 define a private `escape(s: String)` inside the `HandoverWikiPreviewRenderer` object that replaces `&`, `<`, `>`, `"`. The listed core utility `HtmlEscape.escapeHtml()` (in `core/src/main/kotlin/com/workflow/orchestrator/core/util/HtmlEscape.kt`) performs identical escaping but also handles `'` → `&#39;`. The `:handover` module already depends on `:core` (`implementation(project(":core"))`), so `HtmlEscape` is directly importable. This private reinvention (a) is flagged by the audit rule, (b) silently omits the apostrophe — a template author's name or ticket title containing `'` would render unescaped in the preview HTML.
- **Evidence:** `private fun escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
// Missing: .replace("'", "&#39;") — which HtmlEscape.escapeHtml() includes`
- **Fix:** Delete the private `escape(s: String)` function and replace the three call sites (`escape(it)` in `applyInline`, `escape(it)` in the code block, `escape(it)` in the heading handler) with `HtmlEscape.escapeHtml(it)`, adding `import com.workflow.orchestrator.core.util.HtmlEscape`.

### HANDOVER-CLE-7 — handlePostComment and handleCopyFormatted carry a dead @Suppress(UNUSED_PARAMETER) template param
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/tabs/ShareTab.kt`
- **Problem:** Both `handlePostComment(resolvedMarkup, @Suppress("UNUSED_PARAMETER") template)` (line 141) and `handleCopyFormatted(resolvedMarkup, @Suppress("UNUSED_PARAMETER") template)` (line 168) accept a `HandoverTemplate` second parameter they never use. The `@Suppress` is self-documenting dead weight. The template context is already fully applied before these methods are called (the caller resolves markup via `placeholderResolver`, so passing the raw template adds nothing). These are internal methods wired only from the `create()` lambdas at lines 280 and 294.
- **Evidence:** `internal suspend fun handlePostComment(resolvedMarkup: String, @Suppress("UNUSED_PARAMETER") template: HandoverTemplate) {  // line 141
internal suspend fun handleCopyFormatted(resolvedMarkup: String, @Suppress("UNUSED_PARAMETER") template: HandoverTemplate) {  // line 168`
- **Fix:** Remove the `template: HandoverTemplate` parameter from `handlePostComment` and `handleCopyFormatted`. Update their lambda wrappers at lines 280 and 294 from `{ markup, tmpl -> holder[0]?.handlePostComment(markup, tmpl) }` to `{ markup, _ -> holder[0]?.handlePostComment(markup) }`. Also update the `@TestOnly` methods `testTriggerPostComment` and `testTriggerCopyFormatted` (lines 224, 229) accordingly.

### HANDOVER-CLE-8 — handleCopyWiki emits COPY_EMAIL override action — wrong telemetry for wiki-clipboard action
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/tabs/ShareTab.kt`
- **Problem:** Line 160 of `handleCopyWiki` calls `emitOverrideIfNeeded(WorkflowEvent.HandoverAction.COPY_EMAIL)`. This is the wiki-markup-copy path (copies Jira wiki text to clipboard), not the HTML email path. Both `handleCopyWiki` (line 160) and `handleCopyFormatted` (line 169) emit `COPY_EMAIL`, making them indistinguishable in the `HandoverOverrideTracker` audit log and in `HandoverConfigurable`'s 30-day count. The `WorkflowEvent.HandoverAction` enum (`POST_JIRA, COPY_EMAIL, FIX_COPYRIGHT, LOG_WORK, COPY_CHIP`) has no `COPY_WIKI` variant, so the wiki-copy action is silently mislabeled.
- **Evidence:** `internal suspend fun handleCopyWiki(resolvedMarkup: String) {
    emitOverrideIfNeeded(WorkflowEvent.HandoverAction.COPY_EMAIL)  // line 160 — wrong: this is wiki, not email
...
internal suspend fun handleCopyFormatted(resolvedMarkup: String, ...) {
    emitOverrideIfNeeded(WorkflowEvent.HandoverAct`
- **Fix:** Add `COPY_WIKI` to `WorkflowEvent.HandoverAction` in `core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt`. Update `handleCopyWiki` (line 160) to emit `HandoverAction.COPY_WIKI` instead of `COPY_EMAIL`.

### HANDOVER-CLE-9 — Stale KDoc comment in HandoverWikiPreviewRenderer — T10 was already delivered
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverWikiPreviewRenderer.kt`
- **Problem:** Line 176 says `"T10 will wrap this with a project-scoped service that adds a live-render path."` but T10 is fully delivered — `HandoverWikiPreviewRendererService` (the wrapping service) is defined in the same file (lines 29-167) immediately above the `HandoverWikiPreviewRenderer` object. The comment implies future work that is already done, which is misleading for maintainers.
- **Evidence:** `// line 176:
 * T10 will wrap this with a project-scoped service that adds a live-render path.
// But HandoverWikiPreviewRendererService is defined starting at line 29 of the same file.`
- **Fix:** Replace the KDoc line with: `"Wrapped by [HandoverWikiPreviewRendererService] (above) which adds a live Jira render path."` or remove the sentence.

### HANDOVER-COV-1 — HandoverStateService: no test for null activeTicket emission resetting state to blank strings
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt`
- **Problem:** Line 80: `resetForNewTicket(ticket?.key.orEmpty(), ticket?.summary.orEmpty())` is called when `activeTicketFlow` emits `null` (e.g. Stop Work). This resets `ticketId` and `ticketSummary` to blank strings. No test exercises this path. The missing coverage means a regression that, say, accidentally preserves the old ticketId after Stop Work, would go undetected.
- **Evidence:** `cs.launch {
    workflowService.activeTicketFlow.collect { ticket ->
        resetForNewTicket(ticket?.key.orEmpty(), ticket?.summary.orEmpty())
    }
}`
- **Fix:** Add a test in `HandoverStateServiceTest`: after seeding state with a ticket, emit `null` on `activeTicketFlow` and assert that `stateFlow.value.ticketId == ""` and `stateFlow.value.ticketSummary == ""`, and that all status slices are also cleared.

### HANDOVER-COV-2 — HandoverStateService: startWorkTimestamp from settings not propagated to initial state — no test
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt`
- **Problem:** Line 66: `startWorkTimestamp = settings.state.startWorkTimestamp` seeds the initial `HandoverState` from `PluginSettings`. The existing test mocks `settings.state` but never asserts that a non-zero `startWorkTimestamp` is propagated to `stateFlow.value.startWorkTimestamp`. A regression silently zeroing the timestamp would not be caught.
- **Evidence:** `_stateFlow.value = HandoverState(
    ticketId = initialTicket?.key.orEmpty(),
    ticketSummary = initialTicket?.summary.orEmpty(),
    startWorkTimestamp = settings.state.startWorkTimestamp
)`
- **Fix:** In `HandoverStateServiceTest.setUp()`, set `every { settings.state } returns PluginSettings.State().apply { startWorkTimestamp = 1_700_000_000_000L }`, then assert `service.stateFlow.value.startWorkTimestamp == 1_700_000_000_000L` in a dedicated test.

### HANDOVER-COV-3 — HandoverStateService: PullRequestCreated/JiraCommentPosted scope check when activeTicket is null — no test
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt`
- **Problem:** Lines 171-185: `checkScope` for `PullRequestCreated` compares `ctx.activeTicket?.key` (which is `null` when no ticket is active) with `event.ticketId`. When `activeTicket` is `null`, `null == event.ticketId` is `false`, so the event is correctly dropped — but this specific path has no test. A refactor changing `?.key` to `.key` (NPE) or swapping the comparison would not be detected.
- **Evidence:** `is WorkflowEvent.PullRequestCreated -> {
    val activeKey = ctx.activeTicket?.key
    ScopeDecision(
        inScope = activeKey == event.ticketId,
        reason = ...
    )
}`
- **Fix:** Add a test in `HandoverStateServicePrScopingTest`: clear `contextFlow.value` to `WorkflowContext()` (no active ticket), emit a `PullRequestCreated` event, and assert `expectNoEvents()` and `stateFlow.value.prCreated == false`.

### HANDOVER-COV-4 — HandoverStateService: unrecognised events (TicketChanged, BranchChanged) silently dropped — else->return branch untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverStateService.kt`
- **Problem:** Line 324: `else -> return` in `handleEvent` silently drops `TicketChanged`, `BranchChanged`, and any other event type not explicitly handled. There is no test verifying that emitting one of these events does not mutate state. While the individual happy-path tests cover that the relevant fields are updated by expected events, the no-mutation guarantee for unrecognised events is completely untested.
- **Evidence:** `else -> return // Ignore events we don't care about (TicketChanged is now handled by activeTicketFlow)`
- **Fix:** Add a test emitting `WorkflowEvent.TicketChanged("PROJ-999", "Other")` into the event bus and asserting that `stateFlow.value` remains unchanged (e.g. `expectNoEvents()` via Turbine).

### HANDOVER-COV-6 — HandoverTemplateStore: findOrThrow throws NoSuchElementException for missing id — untested for delete/update/duplicate
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverTemplateStore.kt`
- **Problem:** Lines 395-397: `findOrThrow(id)` throws `NoSuchElementException` when the id is absent from `_templates.value`. This is called by `delete`, `rename`, `duplicate`, and `update`. None of the tests pass a non-existent id to any of these four methods. A caller passing a stale id (race between UI and watcher rescan) would receive an unhandled exception silently propagated inside `withContext(Dispatchers.IO)`.
- **Evidence:** `private fun findOrThrow(id: String): HandoverTemplate =
    _templates.value.find { it.id == id }
        ?: throw NoSuchElementException("Template '\$id' not found")`
- **Fix:** Add a `runTest` for each public mutation method (`delete`, `update`, `duplicate`) with a non-existent id string and assert `assertThrows<NoSuchElementException> { ... }`.

### HANDOVER-COV-8 — HandoverWikiPreviewRendererService: requestLive when jira is null is a silent no-op — untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverWikiPreviewRenderer.kt`
- **Problem:** Line 91: `val jiraService = jira ?: return` — when the production `JiraService` EP is absent, `requestLive` is a no-op and `liveResults` never emits. The `forTest` factory accepts a non-null `JiraService`, so tests cannot exercise this path without creating a `HandoverWikiPreviewRendererService` with a null jira directly.
- **Evidence:** `fun requestLive(resolvedMarkup: String, issueKey: String) {
    if (!liveAvailable.get()) return
    val jiraService = jira ?: return`
- **Fix:** Add a test constructing `HandoverWikiPreviewRendererService(jira = null, notifications = ..., cs = ...)` directly (use the primary constructor, not `forTest`), call `requestLive(...)`, advance until idle, and assert no `liveResults` emission and that `isLiveAvailable()` remains true.

### HANDOVER-COV-9 — HandoverOverrideTracker: count30d silently ignores malformed timestamp entries — behavior untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverOverrideTracker.kt`
- **Problem:** Line 88: `runCatching { !Instant.parse(entry).isBefore(cutoff) }.getOrElse { false }` silently skips entries that are not valid ISO-8601 instants. If a settings migration or manual XML edit introduces garbage into `handoverOverrideLog`, those entries are silently excluded from the count (treated as outside the 30-day window) rather than flagged. The `getOrElse { false }` fallback is never exercised by any test.
- **Evidence:** `log.count { entry ->
    runCatching { !Instant.parse(entry).isBefore(cutoff) }.getOrElse { false }
}`
- **Fix:** Add a test that manually inserts `"not-a-valid-date"` into `state.handoverOverrideLog` alongside a valid recent entry, then asserts `count30d() == 1` (only the valid entry is counted) and no exception is thrown.

### HANDOVER-COV-11 — CopyrightFixService.analyzeFile: OK (already current year) path never directly asserted
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/CopyrightFixService.kt`
- **Problem:** Lines 144-146: `analyzeFile` returns `CopyrightFileEntry(status = CopyrightStatus.OK)` when the header already has the current year. The only `analyzeFile` test in `CopyrightFixServiceTest` asserts `YEAR_OUTDATED`. The `OK` result path — including `oldYear`/`newYear` being null — has no direct test. A regression replacing `== headerRegion` with `!= headerRegion` would invert the OK/OUTDATED classification and go undetected.
- **Evidence:** `return if (updated == headerRegion) {
    log.debug("[Handover:Copyright] Copyright OK: $filePath")
    CopyrightFileEntry(filePath = filePath, status = CopyrightStatus.OK)
} else {`
- **Fix:** Add a test: `service.analyzeFile("Foo.kt", "// Copyright (c) 2026 Corp\npackage foo", currentYear = 2026)` should return a `CopyrightFileEntry` with `status == CopyrightStatus.OK` and `oldYear == null` and `newYear == null`. Also add a test for `MISSING_HEADER` via a file with no copyright keyword.

### HANDOVER-COV-12 — HandoverPlaceholderResolver: docker.tag when dockerTagsJson is a non-object JSON value (array/string/null literal) — untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/HandoverPlaceholderResolver.kt`
- **Problem:** Lines 138-143: `resolveFirstDockerTag` casts the parsed element to `JsonObject?`. If the last suite's `dockerTagsJson` is a JSON array (`"[]"`), a string literal, or `"null"`, the cast returns `null` and the resolver returns `unavailable("docker tags not a JSON object")`. This is handled gracefully but never tested. The automation module could in theory emit a malformed JSON shape from a Bamboo property.
- **Evidence:** `val obj = json.parseToJsonElement(raw) as? JsonObject
    ?: return HandoverPlaceholderValue.unavailable("docker tags not a JSON object")
val (k, v) = obj.entries.firstOrNull()
    ?: return HandoverPlaceholderValue.unavailable("docker tags object is empty")`
- **Fix:** Add three resolver tests: (1) `dockerTagsJson = "[]"` → `docker.tag` is unavailable with reason `"docker tags not a JSON object"`; (2) `dockerTagsJson = "{}"` (empty object) → unavailable with `"docker tags object is empty"`; (3) `dockerTagsJson = "null"` → unavailable. Use an `AutomationTriggered` event with the malformed JSON to seed the state.

### HANDOVER-COV-13 — TimeTrackingService.computeElapsedHours: negative diff (currentTimestamp < startTimestamp / clock going backwards) — untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/TimeTrackingService.kt`
- **Problem:** Lines 78-82: `val diffMs = currentTimestamp - startTimestamp` can be negative if the system clock rolls back (NTP correction, sleep/wake, or user error). The result is a negative elapsed hours value. There is no guard, clamp, or test for this scenario. Callers in the UI that display elapsed hours could show a negative time, and `validateHours` would reject the value (hours <= 0), but the root cause is silent.
- **Evidence:** `fun computeElapsedHours(startTimestamp: Long, currentTimestamp: Long): Double {
    if (startTimestamp == 0L) return 0.0
    val diffMs = currentTimestamp - startTimestamp
    val hours = diffMs.toDouble() / (3600.0 * 1000.0)`
- **Fix:** Add a test: `service.computeElapsedHours(System.currentTimeMillis() + 3_600_000L, System.currentTimeMillis())` (start in the future) returns a negative value. Separately, if the design intent is to never return negative hours, add a `coerceAtLeast(0.0)` guard and assert `computeElapsedHours(future, past) == 0.0`.

### HANDOVER-COV-15 — JiraClosureService.buildClosureComment: conflicting docker tag key across two suites — last-wins behavior untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `handover/src/main/kotlin/com/workflow/orchestrator/handover/service/JiraClosureService.kt`
- **Problem:** Lines 65-76: `mergeDockerTags` iterates suites and puts each key into a `mutableMapOf`, so when two suites report the same service name with different tags, the later suite silently overwrites the earlier one. The existing merge test (`merges docker tags from multiple suites`) uses disjoint keys. The last-wins overwrite with conflicting keys is never tested; it could hide version conflicts in the QA handover comment.
- **Evidence:** `for (suite in suiteResults) {
    try {
        val parsed = json.decodeFromString<JsonObject>(suite.dockerTagsJson)
        parsed.forEach { (key, value) -> mergedTags[key] = value.jsonPrimitive.content }
    } catch (_: Exception) { }
}`
- **Fix:** Add a test with two suites both reporting `my-service` but with different versions (`1.0.0` and `2.0.0`). Assert that the comment contains exactly one `my-service` entry and that it is the value from the second suite (last-wins). This pins the documented behavior and makes any future change to merge semantics visible.

## `:jira` (33 confirmed)

### JIRA-ARC-1 — WorklogSection bypasses JiraService contract via raw JiraApiClient
- **Severity:** Medium  **Category:** architecture  **Lens:** architecture
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/WorklogSection.kt`
- **Problem:** WorklogSection retrieves JiraApiClient directly through JiraServiceImpl.getApiClient() and calls api.getWorklogs() on it, bypassing the JiraService interface entirely. The interface already exposes getWorklogs(issueKey) returning ToolResult<List<WorklogData>>, so this leaks the internal HTTP client into the UI layer and skips ToolResult error handling conventions.
- **Evidence:** `val jiraServiceImpl = JiraServiceImpl.getInstance(project)
val apiClient = jiraServiceImpl.getApiClient()
...
val result = apiClient.getWorklogs(issueKey)`
- **Fix:** Replace the two-step lookup with project.getService(JiraService::class.java).getWorklogs(issueKey). The return type changes from ApiResult<JiraWorklogResponse> to ToolResult<List<WorklogData>>; adjust renderWorklogs() to consume WorklogData instead of the DTO. This removes the only remaining ui/ → api/ direct dependency.

### JIRA-ARC-2 — SprintDashboardPanel.onFilterClicked bypasses JiraService via raw JiraApiClient
- **Severity:** Medium  **Category:** architecture  **Lens:** architecture
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`
- **Problem:** onFilterClicked() retrieves the raw JiraApiClient from JiraServiceImpl.getApiClient() and calls api.searchByJql(jql, 50) directly. JiraService.searchTickets(jql, maxResults) is the correct service-layer entry point and already performs JQL validation (blank, length, control chars). Using the raw client skips those guards and the ToolResult contract.
- **Evidence:** `val api = service.getApiClient()
if (api == null) { ... }
val issuesResult = api.searchByJql(jql, 50)`
- **Fix:** Replace api.searchByJql with service.searchTickets(jql, 50). Convert the returned ToolResult<List<JiraTicketData>> back to List<JiraIssue> only if needed by the cell renderer, or preferably refactor the list model to accept JiraTicketData (the shared core model). Remove the getApiClient() null-guard and the getApiClient() call entirely.

### JIRA-ARC-4 — TicketTransitionServiceImpl secondary constructor has a circular dependency on JiraServiceImpl concrete class
- **Severity:** Medium  **Category:** architecture  **Lens:** architecture
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/TicketTransitionServiceImpl.kt`
- **Problem:** The platform-invoked secondary constructor calls JiraServiceImpl.getInstance(project).getApiClient() to obtain the JiraApiClient. This couples a core-interface implementation (TicketTransitionService) to a sibling concrete class (JiraServiceImpl), forming a within-module dependency chain that makes both services harder to test in isolation and violates the convention that services resolve through interfaces.
- **Evidence:** `constructor(project: Project, cs: CoroutineScope) : this(
    api = JiraServiceImpl.getInstance(project).getApiClient()
        ?: JiraApiClient(baseUrl = "", tokenProvider = { null }),`
- **Fix:** Resolve the JiraApiClient by injecting it from plugin.xml using the <serviceImplementation> constructor parameter or by having the platform inject a JiraApiClient-provider service. Alternatively, have TicketTransitionServiceImpl construct its own JiraApiClient from CredentialStore and PluginSettings directly (same pattern as JiraSearchServiceImpl.createClient()), removing the JiraServiceImpl dependency.

### JIRA-COR-1 — parseJiraErrorMessage crashes with ClassCastException on non-primitive error values
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt`
- **Problem:** Line 473: `obj["errors"]?.jsonObject?.entries?.forEach { (k, v) -> messages += "$k: ${v.jsonPrimitive.content}" }` calls `.jsonPrimitive.content` unconditionally on every value in the `errors` map. Jira's `errors` object can contain non-primitive values (e.g. arrays of constraint violations on custom fields). When `v` is a `JsonArray` or `JsonObject`, calling `.jsonPrimitive` throws `kotlinx.serialization.json.JsonException` at runtime. The outer `catch (_: Exception)` swallows it, but this means the entire error block returns null, so the user sees a generic 'Bad request (400)' instead of the real field-validation message.
- **Evidence:** `obj["errors"]?.jsonObject?.entries?.forEach { (k, v) -> messages += "$k: ${v.jsonPrimitive.content}" }`
- **Fix:** Guard each value: replace `v.jsonPrimitive.content` with `(v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString()`. This is safe for primitives, arrays, and objects without throwing.

### JIRA-CLE-4 — Fully dead service `TicketKeyCache` — registered in plugin.xml but never instantiated
- **Severity:** Medium  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/TicketKeyCache.kt`
- **Problem:** `TicketKeyCache` is a `@Service(Service.Level.PROJECT)` class registered at plugin.xml:237. No production code anywhere calls `TicketKeyCache.getInstance(...)` or any of its six public methods (`extractKeys`, `get`, `isValidated`, `getUnvalidated`, `validateAndCache`, `clear`). The DI registration keeps the class compiling but nothing ever resolves the service. The feature it intended to support (ticket-key hyperlinking in the detail panel description/comments) was never wired up. `JiraApiClient.validateTicketKeys` is also exclusively called from the dead `validateAndCache` (line 76) and has no other production callers.
- **Evidence:** `@Service(Service.Level.PROJECT)
class TicketKeyCache {
    // plugin.xml:237: serviceImplementation="...TicketKeyCache"
    // grep: TicketKeyCache.getInstance — 0 production callers
    // grep: extractKeys/validateAndCache/isValidated/getUnvalidated — 0 callers`
- **Fix:** Delete `TicketKeyCache.kt` and remove the `<projectService serviceImplementation="com.workflow.orchestrator.jira.service.TicketKeyCache"/>` entry from plugin.xml (line 237). Optionally prune `JiraApiClient.validateTicketKeys` and `TicketKeyInfo` if ticket-key hyperlinking is confirmed abandoned; otherwise wire the cache into the description/comment renderers in `TicketDetailPanel`.

### JIRA-COV-1 — SprintService.loadSprintIssues — API error from board discovery not propagated to test coverage
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/SprintService.kt`
- **Problem:** SprintService.discoverBoard (lines 171-209) has a specific branch at line 204 where `getBoards()` returns `ApiResult.Error` — it logs and returns null, which causes `loadSprintIssues` to return `NOT_FOUND`. The SprintServiceTest only covers the 'no boards in success response' case (line 63: `ApiResult.Success(emptyList())`), not the 'API itself failed' case. Similarly, the fallback path `loadScrumBoardIssues` → `loadKanbanBoardIssues` when `getActiveSprints` returns an `ApiResult.Error` (lines 68-73) is exercised by zero tests — the existing fallback test (line 71) only covers the `ApiResult.Success(emptyList())` case.
- **Evidence:** `is ApiResult.Error -> {
    log.error("[Jira:Sprint] Board discovery failed: ${allResult.message}")
    null
}`
- **Fix:** Add to SprintServiceTest: (1) `loadSprintIssues returns error when getBoards itself fails` — stub `getBoards` to return `ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach Jira")`, assert result is `isError`. (2) `loadSprintIssues falls back to board issues when getActiveSprints returns API error` — stub `getActiveSprints(1)` to return `ApiResult.Error(ErrorType.SERVER_ERROR, "500")`, stub `getBoardIssues(1)` to return issues, assert success and `activeSprint == null`. Both use `runTest` + MockK, no platform required.

### JIRA-COV-2 — SprintService.loadAvailableSprints and loadIssuesForSprint — completely untested
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/SprintService.kt`
- **Problem:** SprintService exposes two public methods with no test at all: `loadAvailableSprints(boardId)` (lines 106-115) calls `getActiveSprints` and `fetchRecentClosedSprints`, merges results, and sorts by endDate. `loadIssuesForSprint(sprintId, allUsers)` (lines 160-167) updates `cachedIssues` and returns the sprint issues. Both are called from UI panels and are state-mutating. `fetchRecentClosedSprints` itself runs a pagination loop against `getClosedSprints` and reads/writes `SprintPaginationCache` — that loop has no test coverage whatsoever.
- **Evidence:** `suspend fun loadAvailableSprints(boardId: Int): List<JiraSprint> {
    val activeResult = apiClient.getActiveSprints(boardId)
    val active = (activeResult as? ApiResult.Success)?.data ?: emptyList()
    val recentClosed = fetchRecentClosedSprints(boardId, count = 3)`
- **Fix:** Add SprintServiceTest cases: (1) `loadAvailableSprints returns active sprints followed by sorted recent closed` — stub `getActiveSprints` returning one sprint, stub `getClosedSprints` returning a single `isLast=true` page with two closed sprints. Assert the returned list is active-first then sorted by `endDate` descending. (2) `loadIssuesForSprint returns issues and updates cachedIssues` — stub `getSprintIssues(99, false)`, assert result and `getCachedIssues()`. (3) `loadAvailableSprints handles getClosedSprints API error gracefully` — stub `getClosedSprints` returning an error, assert `loadAvailableSprints` returns just the active sprint without throwing.

### JIRA-COV-5 — AttachmentDownloadService — entirely untested, including error responses, path-traversal guard, and parallel concurrency limit
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/AttachmentDownloadService.kt`
- **Problem:** AttachmentDownloadService has no tests. Key untested paths: (1) `downloadAttachment` with blank content URL returns null (line 51). (2) Non-2xx HTTP response deletes temp dir and returns null (lines 67-70). (3) Path-traversal guard: a filename like `../../etc/passwd` is blocked at line 82-85 (canonicalPath check). (4) Empty response body returns null (lines 73-76). (5) `downloadAll` concurrency limit (Semaphore(3)) with 5+ attachments. (6) `downloadThumbnail` cache hit returns same object without making a network call.
- **Evidence:** `if (!targetFile.canonicalPath.startsWith(dir.canonicalPath)) {
    log.warn("[Jira] Path traversal attempt blocked in attachment: ${attachment.filename}")
    if (isTempDownload) dir.deleteRecursively()
    return@withContext null
}`
- **Fix:** Add `AttachmentDownloadServiceTest` using `MockWebServer` + `@TempDir`: (1) `downloadAttachment returns null for blank URL`. (2) `downloadAttachment returns null on 4xx response`. (3) `downloadAttachment path-traversal filename returns null` — use filename `../../secret`. (4) `downloadAttachment happy path writes bytes to target dir`. (5) `downloadAll with 5 attachments makes at most 3 concurrent requests` — inject a latched mock to observe concurrency. (6) `downloadThumbnail caches result and skips second HTTP request`. Use `runTest` + `Dispatchers.IO` override via `TestDispatcher`.

### JIRA-COV-7 — JiraApiClient.getWorklogs — mid-pagination API error path not tested
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt`
- **Problem:** JiraApiClient.getWorklogs (lines 567-588) uses a `while` loop and short-circuits with `return r` on the first `ApiResult.Error` from any page (line 578). This path — where page 1 succeeds but page 2 returns a 4xx/5xx — is not tested. The existing JiraServiceImplTest tests only cover the 2-page success case and a single-page parse. A mid-pagination auth failure would silently return an error discarding all already-collected entries, which callers may not handle gracefully.
- **Evidence:** `is ApiResult.Error -> return r
is ApiResult.Success -> r.data`
- **Fix:** Add to JiraServiceImplTest (using MockWebServer): `getWorklogs returns error immediately when second page fails` — enqueue a valid first page (total=200, worklogs=[100 items]), then enqueue a 401 response. Assert result is `isError` with `ErrorType.AUTH_FAILED`. Also add: `getWorklogs returns empty list when server returns total=0` — enqueue `{"total":0, "worklogs":[]}`, assert empty `isSuccess`.

### JIRA-COV-8 — JiraTransitionResponseParser.parse — malformed/missing 'to' field and empty transitions body not tested
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraTransitionResponseParser.kt`
- **Problem:** JiraTransitionResponseParser.parse uses `!!` dereferences on `n["to"]!!`, `to["id"]!!`, and `to["name"]!!` (lines 28-30) and on `n["id"]!!` and `n["name"]!!` (lines 38-39). If the Jira server returns a transition without a `to` field (which can happen for internal admin transitions), the parser will throw a `NullPointerException` inside `getTransitions`, crashing the caller. The test suite only covers well-formed transitions; no test verifies behavior when a required field is absent.
- **Evidence:** `private fun parseTransition(n: JsonObject): TransitionMeta {
    val to = n["to"]!!.jsonObject
    val toStatus = StatusRef(
        id = to["id"]!!.jsonPrimitive.content,
        name = to["name"]!!.jsonPrimitive.content,`
- **Fix:** Add to JiraTransitionResponseParserTest: (1) `parse skips or throws on transition missing to field` — body with a transition where `to` is absent; assert either an exception is caught (and document the decision) or that the entry is skipped. If the policy is to throw, wrap the call site in a try/catch and convert to `ApiResult.Error`. (2) `parse returns empty list for empty transitions array` — body `{"transitions":[]}`. (3) `parse returns empty list when transitions key is missing entirely` — body `{}`.

### JIRA-ARC-3 — SprintTabProvider wires raw JiraApiClient into SprintService and BranchingService, creating unregistered service objects
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintTabProvider.kt`
- **Problem:** SprintTabProvider calls JiraServiceImpl.getInstance(project).getApiClient() and passes the raw client into manually-constructed SprintService and BranchingService objects that are not registered as project services. These objects hold long-lived HTTP clients and coroutine scopes tied to the raw API client, meaning lifecycle is ad-hoc. The CLAUDE.md convention requires service-layer objects to be project-level IntelliJ services so the platform manages their lifecycle.
- **Evidence:** `val jiraService = JiraServiceImpl.getInstance(project)
val apiClient = jiraService.getApiClient()
    ?: return EmptyStatePanel(...)
val sprintService = SprintService(apiClient)
val branchingService = BranchingService(project, apiClient, activeTicketService)`
- **Fix:** Register SprintService and BranchingService as @Service(Service.Level.PROJECT) with lazy JiraApiClient resolution (same pattern as JiraServiceImpl.client), or inject them from plugin.xml. SprintTabProvider should retrieve them via project.getService(). This ensures lifecycle is tied to project open/close, not to tool-window panel construction.

### JIRA-ARC-6 — Multiple UI and listener files call JiraServiceImpl.getInstance() (concrete class) instead of project.getService(JiraService::class.java)
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/DevStatusSection.kt`
- **Problem:** DevStatusSection, SprintDashboardPanel (loadFavouriteFilters/onFilterClicked), JiraWorkflowConfigurable (searchBoards, getFields), BranchChangeTicketDetector, PostCommitTransitionHandlerFactory, and TimeTrackingCheckinHandlerFactory all call JiraServiceImpl.getInstance() to obtain the service, creating direct compile-time dependencies on the concrete implementation class from UI/listener layers. The JiraService interface exposes all needed methods (getTicket, getFields, searchBoards, getFavouriteFilters, getFilter, getFullDevStatus). Only invalidateFieldsCache and getApiClient are impl-specific; the former should be added to the interface or moved to a settings-specific seam.
- **Evidence:** `val service = JiraServiceImpl.getInstance(project)  // DevStatusSection.kt:60
val result = JiraServiceImpl.getInstance(project).getTicket(ticketId)  // BranchChangeTicketDetector.kt:79
val result = JiraServiceImpl.getInstance(project).searchBoards(searchText)  // JiraWorkflowConfigurable.kt:176`
- **Fix:** Replace all JiraServiceImpl.getInstance(project) calls outside the service package with project.getService(JiraService::class.java). For invalidateFieldsCache (settings page only), expose it on JiraService interface, or have JiraWorkflowConfigurable inject JiraServiceImpl via the same cast pattern already used in JiraServiceImpl.getInstance() companion (safe within the module). This decouples UI/listener layers from the implementation class.

### JIRA-ARC-7 — TicketDetailPanel and QuickCommentPanel use plain javax.swing.JButton instead of JetBrains platform button
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt`
- **Problem:** addTransitionAndWatchButtons() in TicketDetailPanel instantiates javax.swing.JButton directly for the Transition and Watch buttons (lines 237 and 254). QuickCommentPanel also declares sendButton = JButton(AllIcons.Actions.Execute). The project convention requires JetBrains components only. Plain JButton does not respect the IDE theme (light/dark borders, focus handling, DPI scaling) the way IntelliJ ActionButton or JBButton / platform-themed JButton wrappers do.
- **Evidence:** `val transitionBtn = javax.swing.JButton("${issue.fields.status.name} ▾")
...
val watchBtn = javax.swing.JButton("👁 Watch")
// QuickCommentPanel.kt:42:
private val sendButton = JButton(AllIcons.Actions.Execute)`
- **Fix:** Replace javax.swing.JButton with com.intellij.ui.components.JBButton (or use ActionButton/ActionToolbar for icon-only cases). For the Transition and Watch buttons in TicketDetailPanel, JBButton is the minimal fix. For the icon-only sendButton in QuickCommentPanel, consider ActionButton wrapping an AnAction, which also integrates with the platform's presentation/tooltip system.

### JIRA-ARC-8 — JiraWorkflowConfigurable uses plain javax.swing.JTextField and JComboBox inside the Kotlin UI DSL panel
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/settings/JiraWorkflowConfigurable.kt`
- **Problem:** The settings page uses javax.swing.JTextField for boardSearchField (line 118), ticketKeyRegexField (line 149), epicManualField, and acceptanceManualField (lines 363, 388); it also uses javax.swing.JComboBox for epicCombo and acceptanceCombo (lines 355, 380). These plain Swing components do not inherit IDE theming (placeholder text, border scaling, validation decorators). The Kotlin UI DSL's textField() and comboBox() helpers should be used instead, which already respect the IntelliJ Platform look and feel.
- **Evidence:** `val boardSearchField = javax.swing.JTextField(20)
...
val boardComboBox = javax.swing.JComboBox<JiraWorkflowBoardItem>()
...
val epicCombo = javax.swing.JComboBox<JiraCustomFieldItem>().apply {
    ...
}
val epicManualField = javax.swing.JTextField(20).apply { ... }`
- **Fix:** Use JBTextField for the text fields (com.intellij.ui.components.JBTextField) and ComboBox from the IntelliJ Platform (com.intellij.openapi.ui.ComboBox) for the combo boxes, or bind them using the Kotlin UI DSL row { textField().bindText(...) } / row { comboBox(...).bindItem(...) } pattern already used elsewhere in the same file. This is especially important for the manual field fallbacks (epicManualField, acceptanceManualField) as they already reference the JiraWorkflowConfigurable's scope.

### JIRA-COR-2 — validateTicketKeys throws IllegalArgumentException instead of returning ApiResult.Error, propagating uncaught through TicketKeyCache
- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt`
- **Problem:** Lines 529-532: when a key fails the anchored Jira key pattern, `validateTicketKeys` throws `IllegalArgumentException("Invalid Jira key: $k")` rather than returning `ApiResult.Error`. The only caller in production code is `TicketKeyCache.validateAndCache` (line 76), which is a `suspend fun` that does NOT wrap the call in try/catch. The exception propagates as an unhandled coroutine exception, cancelling the enclosing coroutine scope and potentially crashing the feature silently. The function signature promises `ApiResult<...>` so callers do not expect an exception.
- **Evidence:** `keys.forEach { k ->
    if (!jiraKeyPattern.matches(k)) {
        throw IllegalArgumentException("Invalid Jira key: $k")
    }
}`
- **Fix:** Replace `throw IllegalArgumentException(...)` with `return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Invalid Jira key: $k")`. This keeps the function contract and lets callers handle it uniformly.

### JIRA-COR-3 — BranchingService.startWork / useExistingBranch crashes with NoSuchElementException on repositories with no configured remotes
- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchingService.kt`
- **Problem:** Lines 118 and 214: `git.fetch(repo, repo.remotes.first(), emptyList())` calls `.first()` on `repo.remotes` with no guard. A freshly-cloned repository with no remotes configured, or any offline/disconnected clone where the remote list is transiently empty, throws `NoSuchElementException`. Because both call sites are inside a `try { ... } catch (e: Exception)` block the exception IS caught, but the error message 'Failed to checkout branch: null' (since `e.message` for `NoSuchElementException` is null) is exposed to the user with no actionable guidance.
- **Evidence:** `val fetchResult = git.fetch(repo, repo.remotes.first(), emptyList())`
- **Fix:** Add an explicit guard: `val remote = repo.remotes.firstOrNull() ?: return ApiResult.Error(ErrorType.NOT_FOUND, "No Git remote configured for repository '${repo.root.path}'.")` before the fetch call at both sites.

### JIRA-COR-4 — JiraServiceImpl.client property has an unsynchronized TOCTOU race on URL change under concurrent coroutines
- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraServiceImpl.kt`
- **Problem:** Lines 96-112: `cachedClient` and `cachedBaseUrl` are both `@Volatile` but the check-then-act sequence at lines 104-110 (`if (url != cachedBaseUrl || cachedClient == null) { cachedBaseUrl = url; cachedClient = JiraApiClient(...) }`) is not atomic. Two coroutines on `Dispatchers.IO` can both observe `url != cachedBaseUrl`, both assign `cachedBaseUrl = url`, and both construct a new `JiraApiClient`. The second write wins for `cachedClient`, but the first coroutine proceeds using the `return cachedClient` value which is now the second instance. While this normally results in two identical clients rather than a crash, it means connection-pool resources are leaked and any in-flight request on the discarded client gets a new connection every time settings change rapidly.
- **Evidence:** `@Volatile private var cachedClient: JiraApiClient? = null
@Volatile private var cachedBaseUrl: String? = null
...
if (url != cachedBaseUrl || cachedClient == null) {
    cachedBaseUrl = url
    cachedClient = JiraApiClient(...)
}`
- **Fix:** Use `@Synchronized` on the `client` getter or use a `@GuardedBy("this")` pattern: `@get:Synchronized private val client: JiraApiClient? get() = ...`. Alternatively, store a `Pair<String, JiraApiClient>` and use `@Volatile` on a single atomic reference.

### JIRA-COR-5 — TicketDetailPanel.currentIssueKey written on EDT but read from IO thread without @Volatile — stale visibility
- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt`
- **Problem:** Line 105: `private var currentIssueKey: String? = null` is declared as a plain `var` (no `@Volatile`). It is written at line 126 on the EDT (`showIssue` is called from Swing event handlers) and read at lines 747 and 770 from `lazyScope.launch(Dispatchers.IO)` and `invokeLater` closures respectively. Without `@Volatile` (or confinement to a single thread), the JVM memory model does not guarantee that writes on the EDT are visible to threads on the IO thread pool, meaning a race condition where `currentIssueKey` reads a stale value and the guard check `if (currentIssueKey != issueKey) return@launch` fails to abort a stale load.
- **Evidence:** `private var currentIssueKey: String? = null
...
lazyScope.launch(Dispatchers.IO) {
    ...
    if (currentIssueKey != issueKey) return@launch`
- **Fix:** Declare the field as `@Volatile private var currentIssueKey: String? = null`. Alternatively, use `AtomicReference<String?>` consistent with `currentUserName` which is already `AtomicReference` in the same class.

### JIRA-COR-7 — SprintPaginationCache.getCachedStartAt calls getOrLoadCache() OUTSIDE the synchronized(lock) block, creating a TOCTOU window
- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/SprintPaginationCache.kt`
- **Problem:** Lines 50-56: `getCachedStartAt` calls `getOrLoadCache()` at line 51, then enters `synchronized(lock)` at line 52 to access `data.boards[...]`. `getOrLoadCache()` itself is internally synchronized and returns a `CacheData` reference. However, between the return from `getOrLoadCache()` and entry into the inner `synchronized(lock)` block, `saveCachedStartAt()` on another thread can replace `cache` with a NEW `CacheData` object. The `data` local variable still holds the old object, so `data.boards[boardId.toString()]` reads from stale state and the startAt returned may be one update behind.
- **Evidence:** `fun getCachedStartAt(boardId: Int, pageSize: Int): Int {
    val data = getOrLoadCache()
    synchronized(lock) {
        val entry = data.boards[boardId.toString()] ?: return 0
        return (entry.lastStartAt - pageSize).coerceAtLeast(0)
    }
}`
- **Fix:** Inline the read into a single synchronized block: `synchronized(lock) { val data = getOrLoadCache(); val entry = data.boards[boardId.toString()] ?: return 0; return (entry.lastStartAt - pageSize).coerceAtLeast(0) }`. `getOrLoadCache` is re-entrant-safe because it checks `cache?.let { return it }` at the top.

### JIRA-COR-8 — TimeTrackingCheckinHandler and PostCommitTransitionHandler register a new Disposer callback on every commit, accumulating callbacks for the project's lifetime
- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/TimeTrackingCheckinHandlerFactory.kt`
- **Problem:** Lines 110-111: every call to `checkinSuccessful()` creates a new `CoroutineScope` and registers a new `Disposer.register(project as Disposable) { scope.cancel(...) }` lambda. The project's Disposer tree accumulates one entry per commit for the project's lifetime. After many commits, this is a slow memory leak (lambda references to dead scopes). `PostCommitTransitionHandlerFactory.kt` lines 47-48 has the same pattern. Additionally, `project as Disposable` is an unsafe cast — `Project` does not implement `Disposable` in all IntelliJ platform versions and can throw `ClassCastException` on some versions.
- **Evidence:** `val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
Disposer.register(project as Disposable) { scope.cancel("project disposed") }
scope.launch { ... }`
- **Fix:** Instead of creating a scope per invocation, inject the project-level `CoroutineScope` via the service container or use `project.coroutineScope` (available from platform 2024.1+). If a per-invocation scope is needed, register it against a `Disposable` that itself is already project-scoped, not the project directly. Use `Disposer.newDisposable("time-tracking-commit")` registered under the project.

### JIRA-COR-10 — BranchChangeTicketDetector reads GitRepository.currentBranchName outside readAction, violating platform threading contract
- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/listeners/BranchChangeTicketDetector.kt`
- **Problem:** Line 50: `val changedRepo = gitRepos.find { it.currentBranchName == branchName }` reads `GitRepository.currentBranchName` directly in `branchHasChanged`. The IntelliJ platform requires that VCS model state (GitRepository state including `currentBranchName`) be accessed only under a read lock via `readAction { }` or `runReadAction { }`. Accessing it without a lock on the event dispatch thread can produce stale values; calling it from any non-EDT context (BranchChangeListener callbacks can arrive on various threads in platform 2024+) without a read lock is a potential threading violation that can cause `ConcurrentModificationException` in GitRepositoryImpl's internal mutable state.
- **Evidence:** `val gitRepos = GitRepositoryManager.getInstance(project).repositories
val changedRepo = gitRepos.find { it.currentBranchName == branchName }`
- **Fix:** Wrap the repositories access and branch comparison in a `readAction`: `val changedRepo = readAction { gitRepos.find { it.currentBranchName == branchName } }`. Note `GitRepositoryManager.getInstance(project).repositories` at line 49 should also be inside the same `readAction` block.

### JIRA-CLE-1 — Dead private field `availableSprints` — assigned but never read
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`
- **Problem:** The field `private var availableSprints: List<JiraSprint> = emptyList()` (line 130) is written once at line 610 (`availableSprints = sprints`) but is never read anywhere in the class or its inner classes. The sprint selector (`sprintSelector`) and the load logic both drive off `sprintService.activeSprint` and the local `sprints` local variable. The field accumulates stale list state silently.
- **Evidence:** `private var availableSprints: List<JiraSprint> = emptyList()  // line 130
...
availableSprints = sprints  // line 610 — only write, no reads`
- **Fix:** Delete lines 130 and 610 (`private var availableSprints` declaration and the assignment in `populateSprintSelector`). No callers reference this field.

### JIRA-CLE-2 — Dead local variable `finalBranchClient` — always equal to `branchClient`, assigned but never used differently
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`
- **Problem:** At lines 1150-1154, `finalBranchClient` is set by a conditional whose both branches evaluate to the same value (`branchClient`). The comment even acknowledges 'create a new branch client isn't needed'. The variable is then passed to `branchingService.startWork(branchClient = finalBranchClient, ...)` at line 1188. Using `branchClient` directly is identical in effect; `finalBranchClient` adds zero semantic distinction and the comment is misleading.
- **Evidence:** `val finalBranchClient = if (dialogResult.selectedRepoIndex != detectedIndex) {
    branchClient // same HTTP client, different projectKey/repoSlug passed below
} else branchClient`
- **Fix:** Delete the `finalBranchClient` variable and pass `branchClient` directly at line 1188: `branchClient = branchClient`. The comment and the if-expression can be removed entirely.

### JIRA-CLE-3 — Duplicate `isHeader` helper — identical implementation in two sibling UI classes
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`
- **Problem:** `private fun isHeader(issue: JiraIssue): Boolean = issue.id.startsWith("header-")` appears identically at SprintDashboardPanel.kt:205 and TicketListCellRenderer.kt:55. Any change to the header-sentinel prefix (e.g., changing `"header-"` to `"section-"`) must be duplicated. The two classes are both `ui/` layer and work with the same `JiraIssue` DTO.
- **Evidence:** `// SprintDashboardPanel.kt:205
private fun isHeader(issue: JiraIssue): Boolean = issue.id.startsWith("header-")
// TicketListCellRenderer.kt:55
private fun isHeader(issue: JiraIssue): Boolean = issue.id.startsWith("header-")`
- **Fix:** Extract a package-level top-level function `fun JiraIssue.isSectionHeader(): Boolean = id.startsWith("header-")` in a shared file (e.g., `ui/IssueUiUtils.kt`) and replace both private usages with it. This makes the convention a single source of truth.

### JIRA-CLE-6 — Duplicate private `CacheEntry<T>` data class in `JiraServiceImpl` and `JiraSearchServiceImpl`
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraServiceImpl.kt`
- **Problem:** An identical private generic `data class CacheEntry<T>(val value: T, val expiresAt: Long)` is defined independently inside both `JiraServiceImpl` (line 1282) and `JiraSearchServiceImpl` (line 65). A third, non-generic variant exists in `TicketTransitionServiceImpl` (line 80). The two generic variants are character-for-character identical.
- **Evidence:** `// JiraServiceImpl.kt:1282
private data class CacheEntry<T>(val value: T, val expiresAt: Long)
// JiraSearchServiceImpl.kt:65
private data class CacheEntry<T>(val value: T, val expiresAt: Long)`
- **Fix:** Extract the generic variant to a package-level or `internal` class in the `jira.service` package (e.g., an `internal data class CacheEntry<T>(val value: T, val expiresAt: Long)` in a new `CacheUtils.kt`). Both services reference this one type. `TicketTransitionServiceImpl`'s non-generic variant can be left or migrated as a follow-up.

### JIRA-CLE-7 — Unused `project: Project` constructor parameter in `SavedFiltersSection`
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SavedFiltersSection.kt`
- **Problem:** The `project: Project` parameter at line 33 is annotated `@Suppress("UNUSED_PARAMETER")` and is not referenced anywhere inside the class. The section was designed to be callback-driven (the parent `SprintDashboardPanel` owns the `JiraService` calls and passes results via `update()`), so the `project` parameter was never needed.
- **Evidence:** `class SavedFiltersSection(
    @Suppress("UNUSED_PARAMETER") project: Project,  // line 33
    private val onFilterClicked: (FilterData) -> Unit
) : JPanel(BorderLayout()) { /* project never used */ }`
- **Fix:** Remove the `project: Project` parameter from the `SavedFiltersSection` constructor and update the call site in `SprintDashboardPanel` (line 421) to `SavedFiltersSection { filter -> onFilterClicked(filter) }`. No functionality changes.

### JIRA-CLE-8 — Empty `companion object` in `SprintDashboardPanel` — no-op declaration
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`
- **Problem:** Line 1236 declares `companion object` with no members. An empty `companion object` in Kotlin is unnecessary: it generates a synthetic inner class `Companion` in the bytecode for no benefit. No external code accesses `SprintDashboardPanel.Companion` or `SprintDashboardPanel` via static members.
- **Evidence:** `// SprintDashboardPanel.kt:1236
companion object`
- **Fix:** Delete the empty `companion object` declaration at line 1236.

### JIRA-CLE-9 — `WorklogSection` bypasses `JiraService.getWorklogs` and calls raw `JiraApiClient` directly
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/WorklogSection.kt`
- **Problem:** `WorklogSection.loadWorklogs` calls `jiraServiceImpl.getApiClient()` (line 62) then `apiClient.getWorklogs(issueKey)` (line 71), consuming the raw transport DTO `JiraWorklogResponse`/`JiraWorklog`. The core `JiraService` interface already exposes `getWorklogs(issueKey): ToolResult<List<WorklogData>>` (implemented in `JiraServiceImpl` at line 450 with proper field mapping and `.summary`). The three sibling sections (`DevStatusSection`, `ChangelogSection`, `LinkedDocsSection`) all correctly use `project.getService(JiraService::class.java)`. `WorklogSection` is the sole outlier, duplicating DTO-to-display field mapping and coupling the UI to the raw transport layer.
- **Evidence:** `val jiraServiceImpl = JiraServiceImpl.getInstance(project)  // line 61
val apiClient = jiraServiceImpl.getApiClient()  // line 62
...
val result = apiClient.getWorklogs(issueKey)  // line 71 — raw ApiResult<JiraWorklogResponse>`
- **Fix:** Replace lines 61-89 with `project.getService(JiraService::class.java).getWorklogs(issueKey)`, branch on `ToolResult.isError`, and render from `List<WorklogData>` (mirroring the pattern in `ChangelogSection.loadHistory`). Remove the `import com.workflow.orchestrator.jira.service.JiraServiceImpl` and the raw DTO import `JiraWorklog`.

### JIRA-COV-3 — SprintPaginationCache — entirely untested, including corruption recovery and atomic-move path
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/SprintPaginationCache.kt`
- **Problem:** SprintPaginationCache is a singleton object with file-based persistence, an in-memory lock, LRU-style pagination hints, corrupt-file recovery, and atomic-move writes. None of these paths have a single test. Key missing scenarios: (1) first call returns 0 when no file exists; (2) `saveCachedStartAt` then `getCachedStartAt` round-trips correctly with `pageSize` subtraction; (3) corrupt JSON on disk triggers delete-and-reset instead of crashing; (4) `getCachedStartAt` on a board with `lastStartAt < pageSize` returns 0 (coerceAtLeast guard).
- **Evidence:** `fun getCachedStartAt(boardId: Int, pageSize: Int): Int {
    val data = getOrLoadCache()
    synchronized(lock) {
        val entry = data.boards[boardId.toString()] ?: return 0
        return (entry.lastStartAt - pageSize).coerceAtLeast(0)
    }
}`
- **Fix:** Add a `SprintPaginationCacheTest` using a `@TempDir` to redirect `cacheDir`. Tests: (1) `getCachedStartAt returns 0 on first call` (no file). (2) `save then getCachedStartAt returns lastStartAt minus pageSize, floored at 0`. (3) `getCachedStartAt with lastStartAt < pageSize returns 0`. (4) `corrupt file is deleted and cache returns 0` — write invalid JSON to the file, then call `getCachedStartAt`, assert 0 and file is gone. All are pure Kotlin/JUnit5 with temp dirs — no IntelliJ platform needed.

### JIRA-COV-4 — TicketKeyCache — entirely untested including LRU eviction, regex fallback, and API error path
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/TicketKeyCache.kt`
- **Problem:** TicketKeyCache has zero tests. Critical uncovered paths: (1) `extractKeys` with the default regex against multi-key text, with the MAX_REGEX_MATCHES=200 cap, and with a syntactically invalid custom regex (PatternSyntaxException fallback). (2) `validateAndCache` when `validateTicketKeys` returns `ApiResult.Error` — invalid keys must still not appear in cache, and the method must not throw. (3) `evictIfNeeded` at exactly MAX_SIZE+1 entries — eviction uses `cache.keys().toList()` ordering which is non-deterministic in ConcurrentHashMap, so the eviction count must match regardless of which key is dropped.
- **Evidence:** `} catch (e: java.util.regex.PatternSyntaxException) {
    log.warn("[Jira:KeyCache] Invalid ticket key regex, falling back to default: ${e.message}")
    DEFAULT_REGEX
}`
- **Fix:** Add `TicketKeyCacheTest` (pure Kotlin, no platform): (1) `extractKeys finds all Jira-style keys in text` — text with 3 keys + noise. (2) `extractKeys falls back to default regex on invalid pattern` — inject invalid pattern via ConnectionSettings mock or test constructor. (3) `extractKeys caps at MAX_REGEX_MATCHES` — pass text with 201 keys, assert `size == 200`. (4) `validateAndCache caches valid keys and nulls for invalid ones` — stub `validateTicketKeys` returning only subset, assert missing keys map to `null`. (5) `validateAndCache API error leaves cache empty for that batch`. (6) `evictIfNeeded keeps cache at MAX_SIZE after overfill` — add 501 entries, assert `cache.size == 500`.

### JIRA-COV-6 — JiraApiClient.searchIssues — validation branches (too-long text, control chars, key-pattern JQL switch) have no tests
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt`
- **Problem:** JiraApiClient.searchIssues (lines 104-127) has three defensive branches with zero test coverage: (1) `text.length > 500` returns `VALIDATION_ERROR` immediately without an HTTP call. (2) `text.any { it.code < 32 }` returns `VALIDATION_ERROR` for control characters. (3) `looksLikeKey` regex (line 115) switches the JQL to `text ~ "…" OR key = "…"` form — only the non-key free-text form is implicitly exercised via other tests. The internal `escapeJql` function (lines 993-1001) is also completely untested for its reserved-character escaping logic.
- **Evidence:** `if (text.length > MAX_SEARCH_TEXT_LENGTH) {
    return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Search text exceeds $MAX_SEARCH_TEXT_LENGTH characters")
}
if (text.any { it.code < 32 }) {
    return ApiResult.Error(ErrorType.VALIDATION_ERROR, "Search text contains control characters")
}`
- **Fix:** Add to JiraApiClientTest (using MockWebServer): (1) `searchIssues returns VALIDATION_ERROR and no HTTP call for text over 500 chars`. (2) `searchIssues returns VALIDATION_ERROR and no HTTP call for text with control character`. (3) `searchIssues uses key OR text JQL form when text matches Jira key pattern` — assert the request path contains `key+%3D`. (4) Add a standalone `EscapeJqlTest` (pure Kotlin): verify `+`, `-`, `"`, `(` each escape to `\+`, `\-`, `\"`, `\(`, and that plain alphanumeric text is unchanged.

### JIRA-COV-9 — JiraApiClient.getRawString — 429 RATE_LIMITED and non-JSON content-type (PARSE_ERROR) not tested in any helper path
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt`
- **Problem:** The `checkJsonContentType` guard on `getRawString` (lines 337-362) maps a 200 + non-JSON Content-Type (e.g. `application/xml` or `text/plain`) to `PARSE_ERROR`. This path is tested only via the `text/html` → `AUTH_FAILED` path in JiraApiClientTest (for the typed `get` helper). No test covers `getRawString` receiving a `text/xml` or `application/xml` body, nor does any test exercise the `429 RATE_LIMITED` response code for any method despite it being a distinct branch in all five HTTP helpers.
- **Evidence:** `if (!contentType.contains("application/json", ignoreCase = true) &&
    !contentType.contains("text/json", ignoreCase = true)) {
    return ApiResult.Error(
        ErrorType.PARSE_ERROR,
        "Unexpected response Content-Type: $contentType (expected JSON)"
    )
}`
- **Fix:** Add to JiraApiClientTest: (1) `getRawString 200 with application/xml content-type maps to PARSE_ERROR` — enqueue a 200 + `Content-Type: application/xml` + XML body; assert `ErrorType.PARSE_ERROR`. (2) `getBoards 429 maps to RATE_LIMITED` — enqueue `MockResponse().setResponseCode(429)`; assert `ErrorType.RATE_LIMITED`. The 429 test can use any method since all map via the shared `get` helper.

### JIRA-COV-10 — BranchingService.fetchLinkedBranches — dev-status fallback to Bitbucket search has no test
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchingService.kt`
- **Problem:** BranchingService.fetchLinkedBranches (lines 59-80) has two branches: primary dev-status API (returns non-empty) and fallback Bitbucket branch-name search when dev-status returns empty or an error. Neither branch has a test. The fallback is important for repositories not linked to Jira's dev-status system, and any regression would silently return an empty list to the Start Work dialog's branch picker.
- **Evidence:** `val devResult = apiClient.getDevStatusBranches(issue.id)
if (devResult is ApiResult.Success && devResult.data.isNotEmpty()) {
    return names
}
// Fallback: search Bitbucket branches containing the ticket key`
- **Fix:** Add to BranchingServiceTest: (1) `fetchLinkedBranches returns dev-status branches when available` — stub `getDevStatusBranches(issue.id)` to return two branches, stub `allBranches` as empty; assert the returned list matches the dev-status data. (2) `fetchLinkedBranches falls back to Bitbucket name filter when dev-status returns empty` — stub `getDevStatusBranches` to return empty list, provide `allBranches` with one matching and one non-matching entry; assert only the matching branch is returned. Both are pure MockK + `runTest`, no platform needed.

## `:pullrequest` (30 confirmed)

### PULLREQUEST-ARC-1 — BitbucketServiceImpl.declinePullRequest bypasses PrActionService's 409-retry logic
- **Severity:** High  **Category:** architecture  **Lens:** architecture
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt`
- **Problem:** The agent-facing `declinePullRequest` (lines 741–762) does a single GET then a bare `api.declinePullRequest(…, prDetail.version)` call. `PrActionService.decline()` (service/PrActionService.kt:196–243) has an explicit 409 detect-and-retry path with cache invalidation that was introduced as an audit fix. The agent tool path silently misses that retry, so a concurrent PR update between the GET and the POST will give the agent a spurious error rather than transparently retrying. `BitbucketServiceImpl.updatePrDescription` (lines 764–792) has the same gap: it calls `api.updatePullRequest` directly instead of `api.modifyPullRequest` (which owns the retry). This is inconsistent with the comment at line 291–308 in the same file that explicitly routes `addReviewer` through `PrActionService.addReviewer` (primary-repo case) or through `modifyPullRequestForAgent` (non-primary case) for exactly this reason.
- **Evidence:** `754:        return when (val result = api.declinePullRequest(projectKey, repoSlug, prId, prDetail.version)) {
785:        return when (val result = api.updatePullRequest(projectKey, repoSlug, prId, updateRequest)) {
// compare with addReviewer / removeReviewer which route through modifyPullRequestFo`
- **Fix:** For `declinePullRequest`: mirror the `addReviewer` pattern. For primary-repo case, delegate to `PrActionService.getInstance(project).decline(prId)` and map the result to `ToolResult<Unit>`. For non-primary repo, add a private `declinePullRequestForAgent` helper analogous to `modifyPullRequestForAgent` that uses `BitbucketBranchClient.modifyPullRequest`-style retry. For `updatePrDescription`: route through `api.modifyPullRequest(RepoCoords(…), prId) { current -> updateDescriptionMutator(current, description) }` (the mutator already exists as a top-level function in PrActionService.kt) so the retry fires on 409.

### PULLREQUEST-COR-1 — Merge API result swallowed — UI buttons permanently locked on merge failure
- **Severity:** High  **Category:** bug  **Lens:** correctness
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- **Problem:** At lines 1227–1238, `PrActionService.merge()` returns `ApiResult<Unit>` but the return value is never captured. The `scope.launch` block catches only `Exception` (which `ApiResult.Error` is not), then unconditionally disables all action buttons. When merge fails (network error, 409 stale version, auth failure, merge precondition violation, etc.) the user sees no error notification AND permanently loses the Merge/Approve/Decline/Needs-Work buttons for the current PR view. The only recovery is closing and reopening the PR tab.
- **Evidence:** `PrActionService.getInstance(project).merge(
    prId = prId, ...
)
invokeLater {
    mergeButton.isEnabled = false
    approveButton.isEnabled = false
    needsWorkButton.isEnabled = false
    declineButton.isEnabled = false
}`
- **Fix:** Capture the return value: `val result = PrActionService.getInstance(project).merge(...)`. Inside `invokeLater`, branch on `result`: on `ApiResult.Success` disable buttons (and optionally refresh); on `ApiResult.Error` show a notification via `WorkflowNotificationService.notifyError` and leave buttons enabled so the user can retry. Pattern mirrors the correct `declineButton` handler at lines 1263–1281.

### PULLREQUEST-COR-2 — saveDescription result swallowed — UI shows saved description even on server failure
- **Severity:** High  **Category:** bug  **Lens:** correctness
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- **Problem:** In `DescriptionSubPanel.saveDescription()` (lines 1975–1986), `PrActionService.updateDescription()` returns `ApiResult<Unit>` but the result is discarded. The `invokeLater` block always re-enables the Update button and calls `showDescription(newDescription)`, so the panel displays the unsaved text as though it was persisted. If the PUT returns a 409 stale-version conflict, network error, or auth failure, the user has no indication of failure and the description on the server remains unchanged.
- **Evidence:** `PrActionService.getInstance(project).updateDescription(prId, newDescription)
invokeLater {
    updateButton.isEnabled = true
    currentDescription = newDescription
    showDescription(newDescription)
}`
- **Fix:** Capture the result and branch: on `ApiResult.Success` call `showDescription(newDescription)` as now; on `ApiResult.Error` re-show the original description (`showDescription(currentDescription)`) and surface the error message via `WorkflowNotificationService.notifyError`. Same pattern as `saveTitleEdit` (lines 1563–1578) which correctly reverts and shows a notification on failure.

### PULLREQUEST-COV-1 — AiReviewViewModel.pushFinding — service error path and already-pushed/discarded guard untested
- **Severity:** High  **Category:** coverage  **Lens:** coverage
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/AiReviewViewModel.kt`
- **Problem:** pushFinding has three distinct guard/error branches that have zero test coverage: (1) the early-return guard `if (finding.pushed || finding.discarded) return false` (lines 63-64), (2) the service failure path `if (result.isError) { lastError = …; fire(); return false }` (lines 83-87), and (3) the pushAllKept() iterator (lines 100-109) which delegates to pushFinding but is not tested at all. The happy-path tests cover only the service-call dispatch (addPrComment / addInlineComment), not what the VM does when the push fails at the API layer or when findings are already archived.
- **Fix:** In AiReviewViewModelTest, add: (1) a test that calls pushFinding with a finding where pushed=true and asserts the return value is false and service is never called; (2) a test where service.addPrComment returns ToolResult.error("network error") and asserts vm.lastError is set and the finding is NOT marked pushed; (3) a test for pushAllKept that mixes already-pushed, already-discarded, and fresh findings and verifies only the fresh ones are pushed. All are fully testable via MockK without the IntelliJ platform.

### PULLREQUEST-COV-2 — CommentsViewModel mutation error paths (postGeneralComment, reply, resolve, reopen) have no failure-path tests
- **Severity:** High  **Category:** coverage  **Lens:** coverage
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/CommentsViewModel.kt`
- **Problem:** Four mutation methods — postGeneralComment, reply, resolve, reopen — each have an explicit `if (result.isError) { lastError = …; fire(); return false }` branch (lines 83-86, 99-103, 112-116, 127-131). The CommentsViewModelTest covers the success path for postGeneralComment, reply, and resolve, but not a single failure case for any of the four. The `reopen()` method (lines 123-137) has no test at all (success or failure). These are the paths users hit when the Bitbucket token is expired, the PR is merged, or the server returns 403.
- **Fix:** Add to CommentsViewModelTest: (1) `postGeneralComment returns false and sets lastError when service returns error`; (2) `reply returns false and sets lastError on service error`; (3) `resolve returns false and sets lastError on service error`; (4) `reopen calls service reopenPrComment and refreshes on success`; (5) `reopen returns false and sets lastError on service error`. All are fully testable via MockK coEvery returning ToolResult(isError=true).

### PULLREQUEST-COV-5 — PrListService.fetchDashboardPrs — 100-result cap boundary and per-role API error propagation untested
- **Severity:** High  **Category:** coverage  **Lens:** coverage
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt`
- **Problem:** fetchDashboardPrs (lines 200-232) has two critical untested paths: (1) the `results.size < 100` cap — when exactly 100 results accumulate, pagination stops even if isLastPage=false; there is no test that verifies the capped list is returned instead of continuing to fetch. (2) when getDashboardPullRequests returns ApiResult.Error (line 225), the method silently breaks out of the loop and returns whatever was collected so far (possibly an empty list), with only a log.warn. No test verifies that a mid-pagination API error still returns the partial results collected on prior pages. The only PrListService test in the suite (PrListServiceEdtMutationTest) is a source-text guard for EDT dispatch, not a behaviour test.
- **Fix:** Add a standalone test class PrListServiceFetchDashboardTest at the BitbucketBranchClient seam (using MockWebServer, same pattern as BitbucketServiceImplCommitsPaginationTest): (1) queue 4 pages of 25 PRs (total 100) with isLastPage=false — assert exactly 100 results returned and no further HTTP request made; (2) queue page-1 success + page-2 ApiResult.Error — assert partial results from page-1 are returned and the method does not throw.

### PULLREQUEST-ARC-2 — PrDetailPanel.showCreateForm and submitCreatePr bypass the shared BitbucketBranchClientCache
- **Severity:** Medium  **Category:** architecture  **Lens:** architecture
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- **Problem:** Three sites in `PrDetailPanel` call `BitbucketBranchClient.fromConfiguredSettings()` directly (lines 528, 714, 1706) to construct ad-hoc clients, bypassing the `BitbucketBranchClientCache` that all PR services share. The cache exists specifically to (a) reuse HTTP connections and OkHttp thread pools across the entire PR module, and (b) guarantee that a (url, client) pair is always consistent under concurrent URL changes (see `BitbucketBranchClientCache` lines 29–43). Ad-hoc calls always spin up a new OkHttp client and thread pool on each use, and can produce a client built for an old URL if the settings change between the `ConnectionSettings.getInstance()` read inside `fromConfiguredSettings()` and the client's first real call. A fourth site in `resolveCurrentUsername` (line 1460) does the same. `CreatePrDialog.searchUsers` (line 683) falls back to `fromConfiguredSettings()` when `BitbucketBranchClient.forRepo` returns null, which is acceptable, but the create-PR submit path at line 1035 uses `BitbucketBranchClient.forRepo` correctly.
- **Evidence:** `528:            val client = BitbucketBranchClient.fromConfiguredSettings() ?: return@launch
714:            val client = BitbucketBranchClient.fromConfiguredSettings() ?: return@launch
1460:        val client = BitbucketBranchClient.fromConfiguredSettings() ?: return null`
- **Fix:** Inject a `BitbucketBranchClientCache` instance into `PrDetailPanel` (constructed once in the class body, like every other PR service) and replace the three `fromConfiguredSettings()` call sites with `clientCache.get()`. For `resolveCurrentUsername` (line 1460) this also means the username-resolution client is always consistent with the project/repo used everywhere else in the panel.

### PULLREQUEST-ARC-5 — BitbucketServiceImpl.mergePullRequest (agent path) does not use PrActionService.merge's retry-on-409
- **Severity:** Medium  **Category:** architecture  **Lens:** architecture
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt`
- **Problem:** `BitbucketServiceImpl.mergePullRequest` (lines 709–739) performs a GET to obtain the current `version` then calls `api.mergePullRequest(…, prDetail.version, …)` directly. `PrActionService.merge` (PrActionService.kt:153–183) routes through `client.mergePullRequestWithRetry` which fetches the version internally and retries on a stale-version 409. The agent tool therefore silently misses the retry logic on concurrent merge attempts. The comment in `PrActionService` (lines 143–151) documents this as the deliberately correct path for the UI; the agent should use the same hardened path.
- **Evidence:** `// BitbucketServiceImpl.kt line 720-732:
        val prDetail = when (currentPr) { is ApiResult.Success -> currentPr.data … }
        return when (val result = api.mergePullRequest(
            projectKey, repoSlug, prId, prDetail.version, …
        ))
// vs PrActionService.kt line 165:
        val `
- **Fix:** For the primary-repo case, delegate to `PrActionService.getInstance(project).merge(prId, strategyId=strategy, deleteSourceBranch=deleteSourceBranch, commitMessage=commitMessage)` and convert its `ApiResult` to `ToolResult<Unit>` with the same `.toToolResult(…)` helper already used by `addReviewer`/`updatePrTitle`. For non-primary repos, introduce a `mergePullRequestForAgent` private helper that calls `client.mergePullRequestWithRetry(RepoCoords(projectKey, repoSlug), …)` directly.

### PULLREQUEST-COR-3 — BitbucketServiceImpl.updatePrDescription lacks 409 stale-version retry (agent path)
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt`
- **Problem:** The agent-facing `updatePrDescription` (lines 764–793) performs a naive GET+PUT without 409-retry. The parallel methods `addReviewer` (line 300) and `updatePrTitle` (line 343) both check `isPrimaryRepo` and route to `PrActionService` (which uses `modifyPullRequest` with a built-in 409 retry) or `modifyPullRequestForAgent`. `updatePrDescription` was never updated to follow this routing pattern, so any concurrent edit between the GET and the PUT silently loses the agent's update with a 409 error.
- **Evidence:** `// addReviewer and updatePrTitle both have:
if (!isPrimaryRepo(projectKey, repoSlug)) {
    return modifyPullRequestForAgent(...) { ... }
}
val result = PrActionService.getInstance(project).addReviewer(prId, username)

// updatePrDescription only does:
val currentPr = api.getPullRequestDetail(...)
a`
- **Fix:** Apply the same single-repo/multi-repo routing pattern: for the primary repo, delegate to `PrActionService.updateDescription(prId, description)` which already uses `modifyPullRequest` with retry. For non-primary repos, call `modifyPullRequestForAgent(api, prId, projectKey, repoSlug, "update description") { current -> updateDescriptionMutator(current, description) }`. The `updateDescriptionMutator` pure function already exists in `PrActionService.kt`.

### PULLREQUEST-COR-5 — currentPr read on Dispatchers.IO without @Volatile — stale value visible on ARM
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- **Problem:** `currentPr` (line 100) is a plain `var` written on the EDT by `showPr()`, `showPrDetail()`, and `refreshCurrentPr()`, but read on `Dispatchers.IO` by two `scope.launch {}` blocks: (1) `renderPrHeader`'s coroutine at line 1427 (`val pr = currentPr`) which determines the approve/unapprove toggle label, and (2) `FilesSubPanel.openDiffViewer` at line 2581 (`val pr = currentPr ?: return@launch`) which reads `pr.toRef`/`pr.fromRef`. The JVM memory model does not guarantee visibility of non-volatile writes across threads. On ARM processors (macOS M1/M2/M3, which is the dev/CI environment) a CPU can serve a stale cached value, causing either wrong approve-button state or the diff viewer silently aborting on a false null read. `cachedUsername` at line 1456 correctly uses `@Volatile` but `currentPr` and `currentPrId` do not.
- **Evidence:** `private var currentPr: BitbucketPrDetail? = null  // line 100, no @Volatile
...
scope.launch {  // Dispatchers.IO
    val currentUsername = resolveCurrentUsername()
    val pr = currentPr  // line 1427: cross-thread read without visibility guarantee`
- **Fix:** Add `@Volatile` to `currentPr` and `currentPrId`: `@Volatile private var currentPr: BitbucketPrDetail? = null` and `@Volatile private var currentPrId: Int? = null`. This is the same fix applied to `cachedUsername` (line 1456). No logic changes are needed; `@Volatile` establishes the happens-before edge between the EDT write and the IO read.

### PULLREQUEST-COV-3 — PrReviewSessionRegistry — updateStatus on non-existent key is a silent no-op with no test; all() method also untested
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrReviewSessionRegistry.kt`
- **Problem:** updateStatus (lines 33-38) silently does nothing when the prId is not in the registry (`val existing = current[prId] ?: return@withLock`). This is the expected safety gate, but callers (e.g. AgentLoop completing a review for a session whose registry entry was deleted) see no error and get no indication the status was dropped. There is no test that calls updateStatus on a non-existent prId and asserts the state is unchanged. Additionally, the `all()` method (line 42) that returns all entries for display in the AI Review tab has zero coverage — not tested for empty registry, single entry, or multiple entries.
- **Fix:** Add to PrReviewSessionRegistryTest: (1) `updateStatus on unknown prId is a no-op — all() returns empty` (call updateStatus before any register, assert get returns null); (2) `all() returns all registered entries` (register 3 PRs, call all(), assert size=3 and all entries match); (3) `all() returns empty map on fresh registry`.

### PULLREQUEST-COV-4 — PrReviewSessionRegistry — corrupt/malformed JSON file silently returns emptyMap() with no test
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrReviewSessionRegistry.kt`
- **Problem:** readFile() uses `runCatching { json.decodeFromString<RegistryFile>(...) }.getOrElse { emptyMap() }` (lines 58-60). If the file on disk is corrupt (truncated write, manual edit, schema change from a plugin downgrade), every subsequent register/get/updateStatus silently overwrites history with an empty base — the in-flight write after a corrupt read destroys all existing entries. The `getOrElse { emptyMap() }` recovery is correct behaviour, but there is no test proving it does not crash or throw, and no test that verifies a register() call after a corrupt read produces a single-entry file (rather than a zero-entry one).
- **Fix:** Add to PrReviewSessionRegistryTest: (1) `get() returns null and does not throw when file is corrupt JSON` (write `{ not valid json }` to the registry path, then call get()); (2) `register() after corrupt file creates a fresh single-entry file` (pre-write corrupt JSON, then register a PR, assert get() finds it with correct sessionId and the file is valid JSON).

### PULLREQUEST-COV-9 — PrDescriptionGenerator diff-cap retry ladder — ContextOverflow and non-retryable Other paths untested
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrDescriptionGenerator.kt`
- **Problem:** The generate() method (lines 49-137) has a 4-step DIFF_CAP_LADDER retry loop (lines 76-109). The loop has three distinct branch outcomes: (1) TextGenerationOutcome.Success — returns the text; (2) TextGenerationOutcome.ContextOverflow — continues with the next (halved) cap; (3) TextGenerationOutcome.Other — breaks and falls through to Tier 2. The existing PrDescriptionGeneratorTest covers only the internal `buildFallbackDescription` helper (Tier 3). No test exercises the ContextOverflow path that triggers cap halving, nor the Other path that skips remaining retry slots and falls to Tier 2. The Tier 2 no-diff fallback branch (lines 113-131) is also untested.
- **Fix:** Add PrDescriptionGeneratorLadderTest using a fake TextGenerationService injected via a test seam or by making generate() accept the service as a parameter: (1) `ContextOverflow on first cap retries with halved cap and succeeds`; (2) `ContextOverflow on all four caps falls through to Tier 2`; (3) `Other failure on first cap skips remaining ladder and falls to Tier 2`; (4) `Tier 2 failure (null aiResult) falls to Tier 3 fallback`. Since TextGenerationService.getInstance() is a global singleton, the tests should ideally use the testProvider injection seam pattern already established in TicketChipInputTest.

### PULLREQUEST-ARC-3 — PrListService.getClient() throws instead of returning null, risking uncaught exception in polling
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt`
- **Problem:** `getClient()` (line 96–97) calls `error("Bitbucket URL not configured")` when the URL is blank, throwing `IllegalStateException`. `refresh()` at line 107 calls `getClient()` only after an early-exit check on `bitbucketUrl.isBlank()` (line 102), so the happy path is safe. However, the `SmartPoller` action lambda at line 54 calls `refresh()` directly, and any race where the URL becomes blank between the poller's scheduling and the actual call will propagate the exception up through the poller, potentially cancelling or corrupting the `SupervisorJob` child for that poll cycle. The CLAUDE.md threading contract states polling runs under `CoroutineScope+SupervisorJob` to isolate failures; an uncaught `IllegalStateException` escapes that isolation. All other PR services return `null` from their `getClient()` equivalents.
- **Evidence:** `96:    private fun getClient(): BitbucketBranchClient =
97:        clientCache.get() ?: error("Bitbucket URL not configured")
// vs PrActionService.getClient() which returns null`
- **Fix:** Change `getClient()` to `return clientCache.get()` (nullable), and update `refresh()` to treat a null client as a no-op early-return with a log message (same pattern as `PrDetailService.getDetail`, `PrActionService.approve`, etc.). The `bitbucketUrl.isBlank()` pre-check in `refresh()` can be removed since `clientCache.get()` already returns null when the URL is blank.

### PULLREQUEST-ARC-4 — PersistentStateComponent bitbucketUsername written from IO dispatcher, not EDT
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt`
- **Problem:** The comment at lines 117–119 claims the mutation was moved to `Dispatchers.EDT`. The actual code at lines 119–121 wraps only the `connSettings.bitbucketUsername = result.data` assignment inside `withContext(Dispatchers.EDT)`. However, `connSettings` is a direct reference to the mutable state object obtained at line 100 with `ConnectionSettings.getInstance().state`. The CLAUDE.md threading rule (D2 in the comment) requires that `PersistentStateComponent` state mutations happen on the EDT. While the assignment is dispatched to EDT, the surrounding code still reads `connSettings` from the IO thread (line 111: `connSettings.bitbucketUsername.takeIf {…}`). A concurrent EDT write (e.g., the user saving Settings) could produce a torn read on the IO thread. The fix is narrowly scoped but the underlying issue is that `connSettings` is a mutable state snapshot used across two threads.
- **Evidence:** `100:        val connSettings = ConnectionSettings.getInstance().state
...
119:                    withContext(Dispatchers.EDT) {
120:                        connSettings.bitbucketUsername = result.data
121:                    }`
- **Fix:** Read `ConnectionSettings.getInstance().state.bitbucketUsername` freshly on the EDT inside the `withContext(Dispatchers.EDT)` block rather than holding a reference captured on IO. Better yet, use the canonical pattern: call `ConnectionSettings.getInstance().bitbucketUsername` (via the ApplicationService accessor) on EDT. The outer IO-thread check for a saved username (`connSettings.bitbucketUsername.takeIf { it.isNotBlank() }`) should access the state via a snapshot read: `withContext(Dispatchers.EDT) { ConnectionSettings.getInstance().state.bitbucketUsername }` before the suspend network call.

### PULLREQUEST-ARC-6 — PrDetailPanel's inline Create PR form (CARD_CREATE) is a dead code path — CreatePrLauncher EP is the sole entry point
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- **Problem:** The `CARD_CREATE` card and its entire `buildCreatePanel()` / `submitCreatePr()` / `showCreateForm()` implementation (lines 466–810, ~345 lines) exists alongside the dedicated `CreatePrDialog` + `CreatePrLauncherImpl` EP. The module's `CLAUDE.md` states: 'Single entry point to PR creation: PR tab's "+Create PR" button (via CreatePrLauncher.getInstance()?.launch(…))' and 'Build tab's entry was removed'. The `showCreateForm()` is public but is never called from within the module. The `CreatePrAction` inner class in `PrDashboardPanel` goes through `CreatePrLauncher.getInstance().launch(…)` exclusively. The inline form implements a subset of the dialog's features (no multi-ticket input, no AI description, no module selector, no Jira transition) and creates its own `BitbucketBranchClient` directly (line 714). This dead path adds maintenance burden and breaks the architecture convention of a single EP-driven entry point.
- **Evidence:** `// PrDetailPanel.kt line 466:
    fun showCreateForm(repoConfig: RepoConfig) {
// pullrequest/CLAUDE.md:
// 'Single entry point to PR creation: PR tab's "+Create PR" button'
// 'Build tab's entry was removed'`
- **Fix:** Remove `CARD_CREATE`, `buildCreatePanel()`, `submitCreatePr()`, `showCreateForm()`, and all related fields (`createPanel`, `createSourceBranchLabel`, `createTargetBranchCombo`, `createTitleField`, `createDescriptionArea`, `createReviewersPanel`, `createAddReviewerLink`, `createButton`, `createBackLabel`, `selectedReviewerUsernames`, `selectedReviewerDisplayNames`, `createRepoConfig`). The `CreatePrLauncher` EP (invoked from `PrDashboardPanel.CreatePrAction`) is the correct and complete path.

### PULLREQUEST-ARC-7 — PrListPanel empty-state label text does not follow the project's canonical empty-state convention
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrListPanel.kt`
- **Problem:** The project convention (CLAUDE.md UI section) requires empty states to read "No [entity] [state]." followed by an action link. `PrListPanel`'s empty label reads "No pull requests found. Click Refresh to update." (line 75) — the 'Click Refresh to update' text is a prose instruction embedded in the label rather than a separate clickable action link. No action link component is provided. The same panel also uses this label unconditionally for both 'not yet loaded' and 'loaded but empty' states, meaning the label text is misleading when polling is active but returned an empty result.
- **Evidence:** `75:    private val emptyLabel = JBLabel("No pull requests found. Click Refresh to update.").apply {`
- **Fix:** Split into two states: (1) a 'No pull requests [state].' label (e.g., 'No pull requests open.') matching the convention, plus (2) a separate `JBLabel` with `foreground = LINK_COLOR` and a mouse listener that calls `PrListService.refresh()` as the action link. Pass the current state string into `updatePrs()` so the label reads 'No pull requests open.', 'No pull requests merged.', etc.

### PULLREQUEST-COR-4 — CommentsViewModel.reply: Long comment ID silently truncated to Int without overflow guard
- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/CommentsViewModel.kt`
- **Problem:** At line 95, `reply(parentCommentId: Long, ...)` calls `service.replyToComment(parentCommentId = parentCommentId.toInt(), ...)`. Bitbucket comment IDs are stored as `Long` in the server API and can exceed `Int.MAX_VALUE` (2,147,483,647) on busy projects. The overflow produces a negative Int, which causes the reply to target the wrong comment ID or receive a 404/400 from Bitbucket. `ActivitySubPanel.buildReplyLink()` has an explicit SEC-24 guard for this exact case (lines 2397–2399) but `CommentsViewModel.reply` does not.
- **Evidence:** `suspend fun reply(parentCommentId: Long, text: String): Boolean {
    val result = service.replyToComment(
        ...
        parentCommentId = parentCommentId.toInt(),  // truncates silently`
- **Fix:** Add the same guard as `ActivitySubPanel` before the conversion: `if (parentCommentId < Int.MIN_VALUE || parentCommentId > Int.MAX_VALUE) { lastError = "Comment ID $parentCommentId exceeds Int range"; fire(); return false }`. Alternatively, update `BitbucketService.replyToComment` to accept `Long` throughout and remove the conversion (the deeper architectural fix).

### PULLREQUEST-CLE-1 — Dead inline Create PR form in PrDetailPanel (~300 lines of unreachable code)
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- **Problem:** The entire CARD_CREATE card — `showCreateForm()` (line 466), `buildCreatePanel()` (line 555), `submitCreatePr()` (line 682), `renderCreateReviewers()` (line 760), `showCreateReviewerPopup()` (line 792), and all the fields they depend on (`createSourceBranchLabel`, `createTargetBranchCombo`, `createTitleField`, `createDescriptionArea`, `createReviewersPanel`, `createAddReviewerLink`, `createButton`, `createBackLabel`, `selectedReviewerUsernames`, `selectedReviewerDisplayNames`, `createRepoConfig`) — is dead code. `showCreateForm()` is the only public entry point to this card and it has zero callers outside the file; the CLAUDE.md and code comments confirm the only live Create PR path is `CreatePrLauncherImpl → CreatePrDialog`.
- **Evidence:** `fun showCreateForm(repoConfig: RepoConfig) {  // line 466
// only definition found:
$ grep -rn 'showCreateForm' pullrequest/src/ → only line 466 (definition, no callers)`
- **Fix:** Delete: `showCreateForm`, `buildCreatePanel`, `submitCreatePr`, `renderCreateReviewers`, `showCreateReviewerPopup`, `CARD_CREATE`, and all ~15 associated fields (`createRepoConfig`, `createSourceBranchLabel`, `createTargetBranchCombo`, `createTitleField`, `createDescriptionArea`, `createReviewersPanel`, `createAddReviewerLink`, `createButton`, `createBackLabel`, `selectedReviewerUsernames`, `selectedReviewerDisplayNames`). Remove `add(createPanel, CARD_CREATE)` from `init`. Remove the `import com.workflow.orchestrator.core.settings.RepoConfig` import if it becomes unused.

### PULLREQUEST-CLE-2 — Dead AUTHOR/REVIEWER branches in fetchAllPages and effectively-unused username param
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt`
- **Problem:** `fetchAllPages` is called from exactly one site (line 170) with `role = "ALL"`. The `"AUTHOR"` and `"REVIEWER"` arms of the `when` expression (lines 250–251) are therefore dead — they were the pre-dashboard per-repo loop replaced by `fetchDashboardPrs` but never removed. Consequently, the `username` parameter (line 243) is also never read at any live call site (always passed as `null` and only consumed in the dead arms).
- **Evidence:** `val results = fetchAllPages(client, projectKey, repoSlug, null, "ALL")  // line 170
// inside fetchAllPages:
"AUTHOR" -> client.getMyPullRequests(...)  // line 250, dead
"REVIEWER" -> client.getReviewingPullRequests(...)  // line 251, dead`
- **Fix:** Collapse the `when` to just `client.getRepoPullRequests(projectKey, repoSlug, currentState, start, 25)` (the `else` branch). Remove the `username: String?` parameter from `fetchAllPages`. No behavior changes at any live call site.

### PULLREQUEST-CLE-3 — Unfinished public method resolveDefaultReviewersForBranch never called from outside its file
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/action/CreatePrPrefetch.kt`
- **Problem:** `CreatePrPrefetch.resolveDefaultReviewersForBranch` (line 106) is a `suspend fun` marked public on the companion object. Its KDoc says the dialog "re-runs this on target-change via `reloadDefaultReviewersForBranch`", but no such call exists in `CreatePrDialog` or anywhere else. It has zero callers outside its own file. The `@Suppress("UNUSED_PARAMETER")` on `project` (line 107) is an additional signal that the wiring was never completed.
- **Evidence:** `suspend fun resolveDefaultReviewersForBranch(
    @Suppress("UNUSED_PARAMETER") project: Project, ...
// $ grep -rn 'resolveDefaultReviewersForBranch' pullrequest/src/ → only CreatePrPrefetch.kt`
- **Fix:** Either wire the call from `CreatePrDialog.onModuleChanged` / target-field `DocumentListener` (which the KDoc promises), or delete the method if the re-resolution feature is no longer planned. The method itself is correct; this is purely an unused-export cleanup.

### PULLREQUEST-CLE-4 — Force-dereference !! on ToolResult.data after isError guard in AiReviewTabPanel
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/AiReviewTabPanel.kt`
- **Problem:** Line 85 uses `commits.data!!` after `if (commits.isError) "" else commits.data!!…`. The same pattern was explicitly called out as a bug (`pullrequest:F-6 — forced !! NPE`) and fixed in `PrDetailPanel.kt` (lines 880–890) with a null-check instead. `ToolResult.data` is typed as `T` but is effectively nullable when `isError=true`, and the `isError=false` contract can be violated. The identical defect was already fixed elsewhere in the same module, so this is an oversight.
- **Evidence:** `val commits = service.getPullRequestCommits(prId, repoName = null)
if (commits.isError) "" else commits.data!!.firstOrNull()?.id.orEmpty()  // line 85`
- **Fix:** Replace with `commits.data?.firstOrNull()?.id.orEmpty()`. Remove the `isError` guard entirely, since `?.` handles both the null case and the empty-success case uniformly.

### PULLREQUEST-CLE-5 — Redundant popup.cancel() in showAddReviewerPopup — the callee already cancels before calling back
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- **Problem:** `showUserSearchPopup` calls `popup.cancel()` at line 1727 and then immediately invokes `onUserSelected(selected, popup)`. The `showAddReviewerPopup` lambda (the only caller of `showUserSearchPopup` that calls `popup.cancel()` inside the callback) duplicates this at line 1621, so the popup is cancelled twice. The second `cancel()` on a JBPopup that is already cancelled is benign but misleading.
- **Evidence:** `// In showUserSearchPopup (line 1727):
popup.cancel()
onUserSelected(selected, popup)

// In showAddReviewerPopup lambda (line 1621):
) { user, popup ->
    val prId = currentPrId ?: return@showUserSearchPopup
    popup.cancel()   // ← redundant: already cancelled by the caller`
- **Fix:** Remove the `popup.cancel()` call at line 1621 in `showAddReviewerPopup`. The closure still receives `popup` (needed as the `JBPopup` parameter in the lambda signature), but no action on it is needed.

### PULLREQUEST-CLE-6 — Dead cachedVersion parameter in PrActionService.decline()
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrActionService.kt`
- **Problem:** The `cachedVersion: Int = 0` parameter (line 196) is documented as "retained only for API compatibility" but is never read inside the function body — the implementation always fetches a fresh version via `fetchAndDecline`. The sole caller at `PrDetailPanel.kt:1263` passes no argument (uses the default). The parameter is pure dead weight that contradicts the project's `feedback_no_compat_shims.md` convention.
- **Evidence:** `suspend fun decline(prId: Int, cachedVersion: Int = 0): ApiResult<Unit> {
// 'cachedVersion' is never referenced in the function body
// Sole caller: PrActionService.getInstance(project).decline(prId)  // line 1263 of PrDetailPanel`
- **Fix:** Remove the `cachedVersion` parameter from `decline`. Update the KDoc to remove the outdated "retained only for API compatibility" sentence. The sole caller needs no change as it already passes no argument.

### PULLREQUEST-COV-6 — PrListService.setState — concurrent setState + refresh race: @Volatile currentState read under coroutineScope untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt`
- **Problem:** setState (lines 81-84) writes `currentState` (a @Volatile var) and immediately launches a coroutine on Dispatchers.IO to call refresh(). refresh() reads `currentState` inside an async block (line 210). If setState("MERGED") fires while a prior refresh() coroutine is still mid-flight reading currentState="OPEN", the two coroutines race on a @Volatile field. A @Volatile field prevents torn reads but does not prevent stale reads between setState's write and refresh's coroutineScope launch boundary. The codebase has no test verifying that two rapid setState calls produce a single final consistent state rather than interleaved or stale reads.
- **Fix:** Add a source-text contract test (same pattern as PrListServiceEdtMutationTest) asserting that currentState is accessed only as a @Volatile var (not as a MutableStateFlow or AtomicReference). Additionally add a behavioural note that setState's Dispatchers.IO launch is inherently best-effort (no sequential ordering guarantee). A concrete behaviour test: call setState("OPEN") then setState("MERGED") in rapid succession with a mock client that records which state each refresh sees, and assert the final _myPrs emission was fetched using "MERGED" (not "OPEN").

### PULLREQUEST-COV-7 — BitbucketServiceImpl.listPrComments — onlyOpen and onlyInline post-fetch filters have no test
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt`
- **Problem:** After pagination, listPrComments applies two client-side filters (lines 1020-1022): `if (onlyOpen) mapped = mapped.filter { it.state == PrCommentState.OPEN }` and `if (onlyInline) mapped = mapped.filter { it.anchor != null }`. The pagination tests (BitbucketServiceImplListPrCommentsPaginationTest) operate at the BitbucketBranchClient layer where these filters don't exist. The BitbucketServiceImpl is not unit-testable directly, but the filter logic is a pure transformation on a List<PrComment> that can be extracted and tested independently — or tested via a minimal wrapper that exercises the service layer filter independently of the client layer.
- **Fix:** Extract the two filter predicates as internal/companion functions (similar to how PrActionService exposes internal mutators) so they can be called directly in unit tests. Add tests: (1) `onlyOpen=true filters out RESOLVED comments`; (2) `onlyInline=true filters out comments with null anchor`; (3) `both flags combine via AND (intersection)`; (4) `both flags false returns all comments unfiltered`. Alternatively, add a source-text contract test verifying the exact filter expressions are present in the body of listPrComments.

### PULLREQUEST-COV-8 — BitbucketServiceImpl.toPrComment — enum valueOf fallback for unknown state/severity/lineType strings untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt`
- **Problem:** The private toPrComment extension (lines 1315-1348) uses `runCatching { PrCommentState.valueOf(state) }.getOrDefault(PrCommentState.OPEN)` and identical patterns for severity, lineType, and fileType. If Bitbucket Server returns a new state value (e.g. "PENDING" introduced in a newer DC release) or a lineType the client doesn't recognise, the comment silently maps to OPEN/NORMAL/null. There is no test verifying this fallback for any of the four fields, nor a test that an unrecognised enum value does not throw.
- **Fix:** Extract toPrComment as an internal top-level function (same pattern as the mutators in PrActionService.kt) so it is unit-testable. Add tests: (1) `unknown state string falls back to OPEN without throwing`; (2) `unknown severity string falls back to NORMAL without throwing`; (3) `unknown lineType string falls back to null anchor lineType without throwing`; (4) `known state RESOLVED maps correctly`. These exercise a parsing path that runs on every listPrComments call and silently masks server version mismatches.

### PULLREQUEST-COV-10 — PrActionService.decline — first-attempt fetch failure path (getPullRequestDetail returns Error) untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrActionService.kt`
- **Problem:** fetchAndDecline (lines 249-263) first calls getPullRequestDetail. If this GET fails (404 — PR deleted, 403 — permission revoked, timeout), it returns the error directly (line 258: `is ApiResult.Error -> return r`). The retry path in decline() (lines 215-242) checks for 409 specifically, so a 404 on the pre-fetch produces an error at `first` that is neither a 409 nor a success, and is returned to the caller via the `if (!is409)` path (lines 218-221). The existing PrActionServiceDeclineRetryTest tests the 409 retry cycle and the successful two-step sequence, but not the case where the initial GET itself fails. This is a real user path when a PR is closed (404) between the time the PR list loaded and the user clicks Decline.
- **Fix:** Add to PrActionServiceDeclineRetryTest: `decline returns error when getPullRequestDetail returns 404` (enqueue a 404 for the GET, assert the returned ApiResult is an Error with appropriate type, and assert that no POST to the decline endpoint was made using server.takeRequest()). This exercises the fetchAndDecline early return and confirms the caller receives a meaningful error rather than a silent no-op.

### PULLREQUEST-COV-11 — MarkdownToHtml — unclosed code block, empty input, and adjacent list-then-heading transitions untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/MarkdownToHtml.kt`
- **Problem:** renderBody() (lines 28-96) has three untested edge cases: (1) an unclosed fenced code block (the while-loop ends with `if (inCodeBlock) html.append("</code></pre>")` on line 93 — no test verifies this safety-close fires and produces well-formed HTML rather than an unclosed `<pre>`); (2) empty string input — no test verifies `convert("")` returns `<html><body…></body></html>` without null errors; (3) a list immediately followed by a heading (inList=true when `##` is encountered) — the `if (inList) { html.append("</ul>"); inList = false }` guard on line 63 is not tested for the case where the heading comes right after a list item with no blank line separator.
- **Fix:** Add to MarkdownToHtmlTest: (1) `convert on empty string returns valid html/body wrapper without body content`; (2) `unclosed code block is auto-closed at end of document`; (3) `heading immediately after list item closes the ul and opens h2` — input `"- item\n## Heading"`, assert single `</ul>` before `<h2>` in the output.

### PULLREQUEST-COV-12 — BitbucketServiceImpl.mergePullRequest — getPullRequestDetail failure on version pre-fetch untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt`
- **Problem:** mergePullRequest (lines 709-739) fetches the current PR version before merging (lines 720-726): `val currentPr = api.getPullRequestDetail(...)`. If this GET fails (e.g. 404 because the PR was already merged, or 403), it returns early with an error (line 723-725). This is a user-visible failure path — when a user races another merge from the UI — but there is no test for it. The existing PrActionServiceModifyRetryTest covers the merge retry path via mergePullRequestWithRetry at the client layer, not the BitbucketServiceImpl.mergePullRequest GET-then-POST pattern.
- **Fix:** Add a test at the BitbucketBranchClient seam using MockWebServer: enqueue a 404 for the getPullRequestDetail GET call and assert that mergePullRequest returns a ToolResult with isError=true containing a descriptive error message, and that no POST to the merge endpoint was made.

## `:sonar` (32 confirmed)

### SONAR-COR-1 — SonarApiClient.get() swallows SerializationException — silent refresh failure on malformed/empty body
- **Severity:** High  **Category:** bug  **Lens:** correctness
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt`
- **Problem:** The `get<T>()` method catches only `IOException`. A `kotlinx.serialization.SerializationException` thrown by `json.decodeFromString<T>(bodyStr)` is not caught. This happens when: (a) the response body is null (`it.body?.string() ?: ""` produces `""`), and `""` is invalid JSON for any data class; or (b) the `Content-Type` header is absent/empty (bypassing the guard at lines 526-532) but the body is HTML from a proxy/WAF. The uncaught exception propagates through `paginateSonarItems` / the `async` block in `refreshWith`'s `coroutineScope {}`, which cancels all sibling HTTP calls and propagates to `cs.launch` in `refreshForBranch`. Because `cs` uses `SupervisorJob`, the launch silently fails — `_stateFlow.value` is never updated and the UI stays on loading state indefinitely with no error surfaced.
- **Evidence:** `val bodyStr = it.body?.string() ?: ""
ApiResult.Success(json.decodeFromString<T>(bodyStr))
} catch (e: IOException) { ... }`
- **Fix:** In `get<T>()`, broaden the catch to `catch (e: Exception)` (re-throwing `CancellationException`) or add a separate `catch (e: SerializationException)` block after the `IOException` handler, returning `ApiResult.Error(ErrorType.PARSE_ERROR, "Invalid JSON from SonarQube: ${e.message}")`. Also remove the `?: ""` fallback for null body and instead return `ApiResult.Error(ErrorType.PARSE_ERROR, "Empty response body")` directly.

### SONAR-ARC-3 — SonarServiceImpl.getInstance performs an unchecked downcast from the SonarService interface
- **Severity:** Medium  **Category:** architecture  **Lens:** architecture
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt`
- **Problem:** SonarServiceImpl.getInstance (lines 1480-1481) resolves the service by interface (project.getService(SonarService::class.java)) and then does an unchecked `as SonarServiceImpl` cast. If a test or future code registers an alternative SonarService implementation (e.g., a stub or a mock registered in plugin.xml), this cast will throw ClassCastException at runtime. The method is not called from anywhere outside the sonar module currently, but its public @JvmStatic visibility makes it an implicit contract. The service architecture convention is that callers get the interface, not the concrete class.
- **Evidence:** `// SonarServiceImpl.kt:1479-1481
@JvmStatic
fun getInstance(project: Project): SonarServiceImpl =
    project.getService(SonarService::class.java) as SonarServiceImpl`
- **Fix:** Either (a) change the return type to `SonarService` (the interface) and remove the cast, since no currently reachable caller needs anything beyond the interface methods; or (b) register `SonarServiceImpl` directly as the plugin.xml service key and resolve with `project.getService(SonarServiceImpl::class.java)` to make the concrete type explicit and avoid the cast. The second option matches the SonarDataService pattern (direct class registration and `getService(SonarDataService::class.java)`).

### SONAR-ARC-6 — SonarServiceImpl.getBranchQualityReport makes a sequential API call outside the parallel coroutineScope block (extra latency)
- **Severity:** Medium  **Category:** architecture  **Lens:** architecture
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt`
- **Problem:** getBranchQualityReport (lines 863-1053) fires four parallel requests inside a coroutineScope block (gate, issues, hotspots, fileMeasures). After that block completes, it makes a fifth sequential call to api.getProjectMeasures(projectKey, branch, projectNewCodeMetrics) at line 1003, outside any parallel structure. This extra round trip adds 100-500ms of serial latency after the parallel phase has already finished, while the data it fetches (project-level new-code coverage summary) is independent of the parallel results and could be included in the first parallel wave.
- **Evidence:** `// SonarServiceImpl.kt:896-905 (parallel phase)
coroutineScope {
    val gateDeferred = async { api.getQualityGateStatus(projectKey, branch) }
    val issuesDeferred = async { api.getIssues(projectKey, branch = branch, inNewCodePeriod = true) }
    val hotspotsDeferred = async { api.getSecurityHotsp`
- **Fix:** Move the `api.getProjectMeasures(...)` call inside the first `coroutineScope` block as a fifth `async { ... }` deferred alongside the existing four. Declare `val projectMeasuresDeferred = async { api.getProjectMeasures(projectKey, branch, projectNewCodeMetrics) }` inside the block, and replace the sequential call at line 1003 with `val projectMeasures = projectMeasuresDeferred.await()` after the existing `awaitAll` group. This eliminates the serial latency at the cost of no additional code complexity.

### SONAR-COR-2 — Race on unguarded refreshDebounceJob/activeRefreshJob plain vars accessed from EDT and background thread simultaneously
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`
- **Problem:** `refreshDebounceJob` and `activeRefreshJob` are plain `var` with no `@Volatile` and no mutex. `refreshForBranch()` is called both from the platform-injected `cs` coroutine (default/background thread, via `subscribeToQualityScopeFlow`) and from EDT (via `QualityDashboardPanel`'s scope). A concurrent read-cancel-assign sequence can result in: (1) a freshly-created job being cancelled by the other thread's stale `activeRefreshJob?.cancel()` read, allowing two `refreshWith` invocations to run concurrently; or (2) the cancel of an in-flight job being missed because the other thread saw the old value. Two concurrent `refreshWith` calls then race on `previousGateStatus` (also a plain unguarded `var` at line 47) and can double-fire `QualityGateResult` events or fire them with wrong transition logic.
- **Evidence:** `private var refreshDebounceJob: Job? = null
private var activeRefreshJob: Job? = null
...
fun refreshForBranch(branch: String, projectKey: String) {
    refreshDebounceJob?.cancel()
    activeRefreshJob?.cancel()
    val job = cs.launch { ... }
    refreshDebounceJob = job
    activeRefreshJob = job`
- **Fix:** Mark `refreshDebounceJob` and `activeRefreshJob` as `@Volatile`, or better: make `refreshForBranch` a suspend function and protect the job read-cancel-assign block under the existing `apiClientMutex` (it already guards the analogous check-then-act on `cachedApiClient`). Alternatively, confine all calls to `refreshForBranch` to a single dispatcher by making it `private` and exposing only a suspend wrapper. Also annotate `previousGateStatus` as `@Volatile` or move it inside the mutex.

### SONAR-COR-3 — getLineCoverage fallback branch hardcoded to 'develop' returns wrong coverage for projects not on develop
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`
- **Problem:** When the caller passes `branch = null` (e.g. the owning git repository could not be resolved), `getLineCoverage` silently falls back to `"develop"`. Most projects use `main` or `master` as the default branch. Querying Sonar for branch `develop` on a project whose default branch is `main` returns 404 or empty coverage — all editor gutter markers go dark. The caller `CoverageLineMarkerProvider` sets `branch` from `resolver.findRepositoryForPath(...)?.currentBranchName` which can return null if the file is not in a git repo. There is no log warning that the fallback is being used.
- **Evidence:** `val effectiveBranch = branch?.takeIf { it.isNotBlank() } ?: "develop"`
- **Fix:** Remove the `?: "develop"` fallback. When `branch` is null, return `emptyMap()` early with a debug log (the same approach as `effectiveKey.isBlank()` on line 234). The gutter marker provider already handles `null` lineCoverage gracefully. Alternatively, fallback to the `branches` list in the current `SonarState` to find the main branch name dynamically.

### SONAR-COR-4 — SonarCoverageAnnotator uses project.basePath instead of per-file repo root — coverage lookup always misses in multi-repo projects
- **Severity:** Medium  **Category:** bug  **Lens:** correctness
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/coverage/SonarCoverageAnnotator.kt`
- **Problem:** `getFileCoverageInformationString` calls `SonarPathResolver.computeRelativePath(filePath, vcsRootPath = project.basePath, projectBasePath = project.basePath)`. The `vcsRootPath` and `projectBasePath` are the same value (the IDE project/aggregator root), so `computeRelativePath` computes a project-root-relative path. But `fileCoverage` in `SonarState` is keyed by Sonar's REPO-relative path (computed by `CoverageMapper.mapMeasures` using Sonar's `comp.path` field, which is relative to the individual repo root). In multi-repo setups where secondary repos are not at the project root, the computed path includes the extra directory prefix and the map lookup always returns null. The `CoverageLineMarkerProvider` and `SonarIssueAnnotator` correctly use `RepoContextResolver.resolveFromFile` to get the repo root; this class does not.
- **Evidence:** `val relativePath = SonarPathResolver.computeRelativePath(
    filePath = vFile.path,
    vcsRootPath = project.basePath,
    projectBasePath = project.basePath,
)
val fileCoverage = sonarService.stateFlow.value.fileCoverage[relativePath]`
- **Fix:** Replace the `computeRelativePath(vcsRootPath = project.basePath, ...)` call with `SonarPathResolver.resolveContext(project, vFile)` (which uses `RepoContextResolver.resolveFromFile`) to get both the correct `relativePath` and the `sonarProjectKey`. Then look up `sonarService.stateFlow.value.fileCoverage[context.relativePath]` instead.

### SONAR-ARC-2 — CoverageTablePanel.navigateToFile uses project.basePath instead of per-repo root (multi-repo breakage)
- **Severity:** Medium  **Category:** bug  **Lens:** architecture
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTablePanel.kt`
- **Problem:** CoverageTablePanel.navigateToFile (line 293-296) navigates to a file using project.basePath as the root. The FileCoverageData model carries a projectKey field (line 86 of SonarModels.kt) specifically so multi-repo callers can resolve the owning repo's localVcsRootPath, as fixed in IssueDetailPanel (F-12/F-13). However, the double-click path in CoverageTablePanel (lines 140-142) only passes filePath to navigateToFile, discarding the FileCoverageData.projectKey available at line 155. On a multi-repo project, double-clicking a coverage row for a file in repo_2 will look it up under the aggregator project.basePath and silently fail to navigate (vf is null) or navigate to the wrong file.
- **Evidence:** `// CoverageTablePanel.kt:135-144
table.addMouseListener(object : MouseAdapter() {
    override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount == 2) {
            val row = table.selectedRow
            if (row >= 0) {
                val modelRow = table.convertRowIndexToModel(row)
     `
- **Fix:** Change the double-click handler to fetch FileCoverageData alongside the filePath: `val coverageData = tableModel.getFileCoverageData(modelRow); navigateToFile(filePath, coverageData?.projectKey)`. Update `navigateToFile` to accept a nullable projectKey and use `IssueDetailPanel.resolveRepoRoot(...)` (the same companion method used by IssueListPanel and IssueDetailPanel) to resolve the owning repo's localVcsRootPath before constructing the path. This aligns with the F-12/F-13 fix already applied to the sibling panels.

### SONAR-CLE-5 — SonarCoverageAnnotator passes project.basePath as both vcsRootPath and projectBasePath, making the fallback arm dead
- **Severity:** Medium  **Category:** cleanup  **Lens:** cleanup
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/coverage/SonarCoverageAnnotator.kt`
- **Problem:** Lines 43–47 call `SonarPathResolver.computeRelativePath(filePath = vFile.path, vcsRootPath = project.basePath, projectBasePath = project.basePath)`. Because `vcsRootPath` and `projectBasePath` are the same value, the `projectBasePath` fallback arm inside `computeRelativePath` is structurally dead — if the first strip attempt fails, the second will also fail identically. Additionally this ignores the actual VCS root for multi-repo projects; `SonarPathResolver.resolveContext(project, vFile)` is the canonical path that already uses the correct repo-relative root.
- **Evidence:** `43:         val relativePath = SonarPathResolver.computeRelativePath(
44:             filePath = vFile.path,
45:             vcsRootPath = project.basePath,
46:             projectBasePath = project.basePath,
47:         )`
- **Fix:** Replace lines 34–48 with:
```kotlin
val vFile = psiFile.virtualFile ?: return null
val sonarService = try { SonarDataService.getInstance(project) } catch (_: Exception) { return null }
val pathCtx = SonarPathResolver.resolveContext(project, vFile)
val fileCoverage = sonarService.stateFlow.value.fileCoverage[pathCtx.relativePath] ?: return null
```
This matches how `SonarIssueAnnotator` resolves paths and correctly handles multi-repo projects.

### SONAR-CLE-7 — CoverageTablePanel.navigateToFile uses project.basePath, breaking double-click navigation in multi-repo setups
- **Severity:** Medium  **Category:** cleanup  **Lens:** cleanup
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTablePanel.kt`
- **Problem:** Lines 293–297: `navigateToFile` resolves the file using `project.basePath`. Unlike `CoveragePreviewPanel.showFile` (which correctly reads `owningRepo.localVcsRootPath` from `coverageData.projectKey`) and `IssueDetailPanel.resolveSonarRepoRoot`, this method hardcodes the aggregator basePath. On multi-repo projects a file in a secondary repo will never be found — `LocalFileSystem.findFileByPath` returns null and the double-click silently does nothing.
- **Evidence:** `293:     private fun navigateToFile(filePath: String) {
294:         val basePath = project.basePath ?: return
295:         val vf = LocalFileSystem.getInstance().findFileByPath(java.io.File(basePath, filePath).path) ?: return
296:         OpenFileDescriptor(project, vf, 0, 0).navigate(true)
297:   `
- **Fix:** Use the selected row's `FileCoverageData.projectKey` to resolve the owning repo root (same pattern as `CoveragePreviewPanel.showFile` lines 95–99). Thread `FileCoverageData` into the call site: `tableModel.getFileCoverageData(modelRow)?.let { coverageData -> navigateToFile(filePath, coverageData) }`, then resolve `basePath` via `PluginSettings.getRepos().firstOrNull { it.sonarProjectKey == coverageData.projectKey }?.localVcsRootPath ?: project.basePath`.

### SONAR-COV-1 — SonarApiClient: 429 rate-limit and HTML Content-Type PARSE_ERROR paths are untested
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClientTest.kt`
- **Problem:** The `get()` private method in SonarApiClient handles HTTP 429 (→ ErrorType.RATE_LIMITED) and a non-JSON Content-Type (→ ErrorType.PARSE_ERROR). No test in SonarApiClientTest exercises either branch. A SonarQube instance behind a reverse proxy commonly returns 429 or an HTML error page (e.g. login redirect) on token expiry; without coverage these two error paths are dark.
- **Evidence:** `// SonarApiClient.kt lines 549-557
429 -> {
    log.warn("[Sonar:API] $path -> 429 Rate limited")
    ApiResult.Error(ErrorType.RATE_LIMITED, "SonarQube rate limit exceeded")
}
// lines 526-533
if (contentType.isNotBlank() &&
    !contentType.contains("application/json", ignoreCase = true) &&
    !c`
- **Fix:** Add two tests to SonarApiClientTest using MockWebServer:
1. `server.enqueue(MockResponse().setResponseCode(429))` → assert `result.isError && (result as ApiResult.Error).type == ErrorType.RATE_LIMITED`.
2. `server.enqueue(MockResponse().setBody("<html>Login</html>").addHeader("Content-Type", "text/html"))` → assert `result.isError && (result as ApiResult.Error).type == ErrorType.PARSE_ERROR`.
Both tests should call any endpoint, e.g. `client.validateConnection()`.

### SONAR-COV-2 — SonarApiClient: network IOException (offline) path is untested at the HTTP layer
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClientTest.kt`
- **Problem:** The `get()` method catches `IOException` and maps it to `ApiResult.Error(ErrorType.NETWORK_ERROR, ...)`. No test in SonarApiClientTest exercises this path — the service-layer tests stub with `ApiResult.Error(ErrorType.NETWORK_ERROR, ...)` directly, bypassing the real HTTP stack. MockWebServer supports `SocketPolicy.DISCONNECT_AT_START` to simulate a TCP drop.
- **Evidence:** `// SonarApiClient.kt lines 559-562
} catch (e: IOException) {
    log.error("[Sonar:API] $path -> Network error: ${e.message}", e)
    ApiResult.Error(ErrorType.NETWORK_ERROR, "Cannot reach SonarQube: ${e.message}", e)
}`
- **Fix:** Add a test: `server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))`, call `client.validateConnection()`, assert `result.isError && (result as ApiResult.Error).type == ErrorType.NETWORK_ERROR`. Requires importing `mockwebserver3.SocketPolicy`.

### SONAR-COV-3 — SonarApiClient.getIssuesSinglePage: page/pageSize clamping and single-page behavior are untested
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClientTest.kt`
- **Problem:** `getIssuesSinglePage()` was introduced as the F-15 fix for the agent's paged-issues path (one HTTP request with `&p=&ps=` instead of a full loop). It coerces page values with `coerceAtLeast(1)` and `coerceIn(1, ISSUES_PAGE_SIZE)`. There are zero tests for it: not the happy path, not the page/pageSize boundary clamping (e.g. page=0 → p=1, pageSize=501 → ps=500), and not its 401/404 error responses.
- **Evidence:** `// SonarApiClient.kt lines 210-228
suspend fun getIssuesSinglePage(
    ...page: Int, pageSize: Int...
): ApiResult<SonarIssueSearchResult> {
    val effectivePage = page.coerceAtLeast(1)
    val effectivePageSize = pageSize.coerceIn(1, ISSUES_PAGE_SIZE)
    ...
    return get<SonarIssueSearchResult`
- **Fix:** Add tests to SonarApiClientTest:
1. Happy path: enqueue a single-page response, call `getIssuesSinglePage("proj", 2, 10)`, assert the returned result contains the correct issues and that the request path includes `p=2&ps=10`.
2. Boundary clamping: call with `page=0, pageSize=999`, assert request path has `p=1&ps=500`.
3. Error response: enqueue 404, assert `ApiResult.Error(ErrorType.NOT_FOUND, ...)`.

### SONAR-COV-4 — SonarApiClient.getMeasures: first-page error returns ApiResult.Error — that path is not tested
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClientTest.kt`
- **Problem:** The measures pagination loop distinguishes 'first page error → propagate error' from 'mid-pagination error → return partial success'. The first-page-error path for `getMeasures` is not covered by any test. The equivalent path for `getIssuesWithPaging` (mid-pagination 503 → partial success) is tested, but first-page failure for `getMeasures` is not.
- **Evidence:** `// SonarApiClient.kt lines 339-341
is ApiResult.Error -> {
    // First-page failure → return error. Mid-pagination failure
    return if (page == 1) pageResult else ApiResult.Success(all)
}`
- **Fix:** Add a test: `server.enqueue(MockResponse().setResponseCode(401))`, call `client.getMeasures("proj")`, assert `result.isError && (result as ApiResult.Error).type == ErrorType.AUTH_FAILED`. Also add a mid-pagination 401 test: enqueue a valid page-1 response then a 401 for page-2 (with paging.total > 500 in page-1), assert `result.isSuccess` with partial data.

### SONAR-COV-6 — SonarDataService.getLineCoverage: no test for cache-hit, API error, blank projectKey, or concurrent fetch
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarDataServiceTest.kt`
- **Problem:** The public `getLineCoverage` method has rich logic: it falls back to `settings.state.sonarProjectKey` when `projectKey` is null/blank, returns from `lineCoverageCache` on hit, calls `getSourceLines`, maps results via `CoverageMapper.mapLineStatuses`, and populates the cache. None of these paths have a test — not the cache-hit fast-path, not the `ApiResult.Error` returning `emptyMap()`, and not the blank/null projectKey fallback.
- **Evidence:** `// SonarDataService.kt lines 232-259
suspend fun getLineCoverage(
    relativePath: String,
    projectKey: String? = null,
    branch: String? = null,
): Map<Int, LineCoverageStatus> {
    val effectiveKey = projectKey?.takeIf { it.isNotBlank() }
        ?: settings.state.sonarProjectKey.orEmpty()
`
- **Fix:** Add unit tests using a testable harness with a mocked SonarApiClient:
1. Cache-hit: pre-populate `lineCoverageCache`, call `getLineCoverage`, assert the result matches the cache value and no HTTP call was made.
2. API error: stub `getSourceLines` to return `ApiResult.Error(...)`, assert `getLineCoverage` returns `emptyMap()` and does NOT populate the cache.
3. Blank projectKey: pass `projectKey = ""` with no settings fallback, assert `emptyMap()` is returned immediately.

### SONAR-COV-8 — SonarServiceImpl.getCoverage: coveredLines can silently go negative — no test for uncoveredLines > totalLinesToCover
- **Severity:** Medium  **Category:** coverage  **Lens:** coverage
- **File:** `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarBranchReportGateAndDupTest.kt`
- **Problem:** In `SonarServiceImpl.getCoverage`, `coveredLines = totalLinesToCover - uncoveredLines` is computed without a floor at 0. If SonarQube returns inconsistent data (e.g. uncoveredLines reported from a different analysis epoch than linesToCover), `coveredLines` can be negative. The resulting `CoverageData(coveredLines = -N)` propagates to the agent's summary string. `SonarServiceImpl` has only partial test coverage via the `testClient` seam, and this particular method has no test at all.
- **Evidence:** `// SonarServiceImpl.kt lines 251-259
val totalLinesToCover = measures[SonarMetricKey.LINES_TO_COVER]?.toIntOrNull() ?: 0
val uncoveredLines = measures[SonarMetricKey.UNCOVERED_LINES]?.toIntOrNull() ?: 0
val coveredLines = totalLinesToCover - uncoveredLines  // can be negative
val data = CoverageData`
- **Fix:** 1. Fix: clamp `coveredLines = (totalLinesToCover - uncoveredLines).coerceAtLeast(0)`.
2. Add a test via the `testClient` seam (`SonarServiceImpl(...).also { it.testClient = api }`): stub `getProjectMeasures` with `lines_to_cover=10, uncovered_lines=15` (stale data scenario), call `getCoverage(...)`, assert `coveredLines == 0` (or whatever the clamped value should be).
3. Also add a basic happy-path test for `getCoverage`: this method currently has zero test coverage.

### SONAR-ARC-1 — Dispatchers.Main used for Swing UI mutations instead of Dispatchers.EDT
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueDetailPanel.kt`
- **Problem:** IssueDetailPanel (lines 296, 374, 378, 383) and CoveragePreviewPanel (lines 115, 131) switch to Dispatchers.Main for all Swing UI mutations (codeArea.text, ruleInfoLabel.text). The project convention is Dispatchers.EDT for all UI mutations in IntelliJ plugins. While kotlinx-coroutines-swing maps Dispatchers.Main to the EDT, the codebase explicitly imports and uses com.intellij.openapi.application.EDT everywhere else (e.g., QualityDashboardPanel line 25/53, SonarDataService). This inconsistency violates the stated threading convention and will silently break if the project ever excludes the swing dispatcher artifact from the classpath.
- **Evidence:** `// IssueDetailPanel.kt:296
withContext(Dispatchers.Main) {
    codeArea.text = snippet
    codeArea.caretPosition = 0
}
// CoveragePreviewPanel.kt:131
withContext(Dispatchers.Main) {
    codeArea.text = displayText
    codeArea.caretPosition = 0
    showContentLayout()
}`
- **Fix:** Replace all `withContext(Dispatchers.Main)` in IssueDetailPanel.kt (lines 296, 374, 378, 383) and CoveragePreviewPanel.kt (lines 115, 131) with `withContext(Dispatchers.EDT)`, adding `import com.intellij.openapi.application.EDT` to each file. This matches the explicit convention used by QualityDashboardPanel and the rest of the codebase.

### SONAR-ARC-5 — Non-JB Swing components used for interactive controls (JButton, JCheckBox, JComboBox, JTextArea, JMenuItem/JPopupMenu) in UI panels
- **Severity:** Low  **Category:** architecture  **Lens:** architecture
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt`
- **Problem:** Several sonar UI files use plain Swing interactive components instead of their JetBrains equivalents: QualityDashboardPanel uses javax.swing.JButton (lines 136, 141) and javax.swing.JCheckBox (line 152); IssueDetailPanel uses javax.swing.JButton (lines 80-83) and javax.swing.JTextArea (line 67); IssueListPanel uses javax.swing.JComboBox (lines 39-40), javax.swing.JMenuItem, and javax.swing.JPopupMenu (lines 152-166); CoveragePreviewPanel uses javax.swing.JButton (line 46) and javax.swing.JTextArea (line 35). The project convention mandates JetBrains UI components only. Plain Swing components do not participate in IntelliJ's theme engine, causing visual inconsistencies (incorrect borders, focus rings, and colors) in both light and dark themes.
- **Evidence:** `// QualityDashboardPanel.kt:136,141,152
private val newCodeButton = JButton("New Code")
private val overallButton = JButton("Overall")
val gutterMarkersCheckbox = JCheckBox("Gutter Markers")
// IssueListPanel.kt:39-40
private val filterCombo = JComboBox(arrayOf("All", "Bug", "Vulnerability", "Code S`
- **Fix:** Replace: JButton → com.intellij.ui.components.JBButton (or ActionButton for toolbar-style buttons); JCheckBox → com.intellij.ui.components.JBCheckBox; JComboBox → com.intellij.openapi.ui.ComboBox (already imported/used in QualityDashboardPanel for the repo selector); JTextArea → com.intellij.ui.components.JBTextArea; JMenuItem/JPopupMenu are acceptable as there are no JB equivalents for these. The JTextArea instances in IssueDetailPanel and CoveragePreviewPanel would additionally benefit from being wrapped in a read-only EditorTextField for syntax highlighting support.

### SONAR-COR-5 — buildBranchQualityReportSummary truncation message hardcodes maxFilesDefault constant instead of the actual maxFiles parameter
- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt`
- **Problem:** `buildBranchQualityReportSummary` appends `", showing top $maxFilesDefault"` when `data.truncatedFiles` is true. But `maxFilesDefault` is the companion-object constant (20), not the `maxFiles` parameter actually passed to `getBranchQualityReport`. If a caller passes `maxFiles = 5` or `maxFiles = 50`, the summary message reports the wrong limit. The agent's LLM prompt will be told the wrong number of files were shown.
- **Evidence:** `if (data.truncatedFiles) append(", showing top $maxFilesDefault")
...
private const val maxFilesDefault = 20`
- **Fix:** Pass the actual `maxFiles` parameter into `buildBranchQualityReportSummary` as an argument, or capture it in the closure. Change the function signature to `private fun buildBranchQualityReportSummary(data: BranchQualityReportData, projectKey: String, maxFiles: Int): String` and replace `$maxFilesDefault` with `$maxFiles` at line 1204.

### SONAR-COR-6 — getLineCoverage check-then-act on ConcurrentHashMap allows duplicate in-flight HTTP requests for the same file
- **Severity:** Low  **Category:** bug  **Lens:** correctness
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`
- **Problem:** `getLineCoverage` checks the cache with `lineCoverageCache[key]?.let { return it }` and then issues an HTTP request if the key is absent. Two concurrent callers (e.g. `IssueDetailPanel.buildCodeSnippet` and `CoveragePreviewPanel.showFile`) both calling for the same file can both see a cache miss at the same instant and each fire a separate `getSourceLines` HTTP request. The `CoverageLineMarkerProvider` avoids this with a `putIfAbsent` pending-fetches guard, but `getLineCoverage` itself has no such guard. The duplicate request is wasteful and — under high concurrency — can race between `getLineCoverage` returning `emptyMap()` (error path) from one call while the other succeeds and caches the data.
- **Evidence:** `lineCoverageCache[key]?.let { return it }
val client = resolveApiClient() ?: return emptyMap()
// ... no deduplication guard before HTTP ...
lineCoverageCache[key] = statuses`
- **Fix:** Add a `ConcurrentHashMap<String, Deferred<Map<Int, LineCoverageStatus>>>` in-flight registry inside `SonarDataService`. In `getLineCoverage`, use `computeIfAbsent` to register a `Deferred` for the key before issuing the HTTP request, and `await()` it if already in-flight. Alternatively, use a per-key `Mutex` map. This is the standard single-flight pattern already applied at the `CoverageLineMarkerProvider` level for the gutter-marker path.

### SONAR-CLE-1 — IssueListPanel declares a CoroutineScope that is never launched
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt`
- **Problem:** Line 45 creates `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`. The field is cancelled in `dispose()` at line 318, but there is no `scope.launch { }` call anywhere in the file. The scope object leaks a SupervisorJob + thread until dispose fires, yet does zero work. The class is a pure UI panel with no async work of its own.
- **Evidence:** `45:    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
318:        scope.cancel()
// No scope.launch call anywhere in IssueListPanel.kt`
- **Fix:** Delete the `scope` field and the `scope.cancel()` call in `dispose()`. All async operations (rule fetches, navigation) are done by the injected `IssueDetailPanel` which owns its own scope, or by the platform.

### SONAR-CLE-2 — refreshDebounceJob is a redundant alias for activeRefreshJob — both track the same Job
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`
- **Problem:** Lines 60 and 64 declare two separate `Job?` fields. In `refreshForBranch()` (lines 181–189) both are cancelled and then both are assigned the exact same `job` reference from `cs.launch { }`. Cancelling one already-cancelled Job a second time is a no-op. Both fields are always identical — one is entirely redundant.
- **Evidence:** `181:         refreshDebounceJob?.cancel()
182:         activeRefreshJob?.cancel()
...
188:         refreshDebounceJob = job
189:         activeRefreshJob = job`
- **Fix:** Delete `refreshDebounceJob` (lines 60 and 181, 188). Keep only `activeRefreshJob` which was introduced by F-10 and captures both the debounce delay and in-flight HTTP children. Rename `activeRefreshJob` back to `refreshJob` if clearer.

### SONAR-CLE-3 — applyPreFilter's newCodeMode parameter is accepted but never read
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt`
- **Problem:** The public method `applyPreFilter(type: IssueType?, newCodeMode: Boolean?)` (line 180) accepts `newCodeMode` but never references it in its body (lines 181–190 — only `typeIndex` and `filterCombo.selectedIndex` are set). The caller at `QualityDashboardPanel.kt:227` passes `filter.newCodeMode`, but the value is silently discarded.
- **Evidence:** `180:     fun applyPreFilter(type: IssueType?, newCodeMode: Boolean?) {
181:         val typeIndex = when (type) { ... }
188:         filterCombo.selectedIndex = typeIndex
189:         severityCombo.selectedIndex = 0
190:     }`
- **Fix:** Either (a) remove the `newCodeMode` parameter and update the caller (simplest), or (b) implement the intent: after setting `typeIndex`, if `newCodeMode == true` call `dataService.setNewCodeMode(true)` so the filter and the toggle are in sync. Option (a) is safer unless the toggle sync was the design intent.

### SONAR-CLE-4 — paging.copy(total = firstResult.paging.total) in combineResult lambdas is a no-op
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt`
- **Problem:** In both `getIssuesWithPaging` (line 190) and `getSecurityHotspots` (line 473), the `combineResult` lambda calls `firstResult.paging.copy(total = firstResult.paging.total)` — copying the paging object and assigning its own `total` back to itself. This is identical to `firstResult.paging` and creates a needless allocation.
- **Evidence:** `190:                 firstResult.copy(issues = allItems, paging = firstResult.paging.copy(total = firstResult.paging.total))
473:                 firstResult.copy(hotspots = allItems, paging = firstResult.paging.copy(total = firstResult.paging.total))`
- **Fix:** Simplify both lambdas: `combineResult = { firstResult, allItems -> firstResult.copy(issues = allItems) }` and `combineResult = { firstResult, allItems -> firstResult.copy(hotspots = allItems) }`. The `paging` field of `firstResult` is already the server-authoritative first-page envelope — no copy needed.

### SONAR-CLE-6 — getMeasures has an unnecessary ifBlank fallback on metricKeys — no caller ever passes blank
- **Severity:** Low  **Category:** cleanup  **Lens:** cleanup
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt`
- **Problem:** Line 306: `val metrics = metricKeys.ifBlank { DEFAULT_METRIC_KEYS }`. The parameter already has a default value of `DEFAULT_METRIC_KEYS` (line 303). Every production caller either omits `metricKeys` (getting the default) or passes a non-blank explicit key set. There is no caller that passes an empty or blank string, so the `ifBlank` guard masks mistakes silently instead of failing fast.
- **Evidence:** `300: suspend fun getMeasures(
303:         metricKeys: String = DEFAULT_METRIC_KEYS
...
306:         val metrics = metricKeys.ifBlank { DEFAULT_METRIC_KEYS }`
- **Fix:** Delete line 306 and replace every subsequent reference to `metrics` with `metricKeys`. If guarding against accidental blank is desired, use `require(metricKeys.isNotBlank()) { "metricKeys must not be blank" }` to fail loudly rather than silently falling back.

### SONAR-ARC-4 — IssueListPanel allocates an IO CoroutineScope that is never used
- **Severity:** Low  **Category:** cleanup  **Lens:** architecture
- **File:** `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt`
- **Problem:** IssueListPanel declares `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` at line 45, cancelled in dispose() at line 318. However, a codebase search finds no `scope.launch` or `scope.async` calls within IssueListPanel — the scope is created and cancelled but no work is ever launched on it. This is a dead resource allocation that creates a SupervisorJob and thread-pool overhead for no benefit.
- **Evidence:** `// IssueListPanel.kt:45
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
// IssueListPanel.kt:317-318 (dispose)
override fun dispose() {
    scope.cancel()
}`
- **Fix:** Remove the `scope` field and its `scope.cancel()` in `dispose()`. If future async work is added to this panel, the scope should be reintroduced at that point. Note: IssueDetailPanel (which is registered as a child Disposer and owns its own IO scope) handles all async fetches for the selected issue — IssueListPanel itself does not need a scope.

### SONAR-COV-5 — SonarDataService.refreshWith: CE-tasks FORBIDDEN branch (analysisHistoryForbidden=true) has no test
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarDataServiceTest.kt`
- **Problem:** When `getAnalysisTasks` returns `ErrorType.FORBIDDEN`, `refreshWith` sets `analysisHistoryForbidden = true` in the resulting `SonarState`. No test verifies that this flag is correctly set, meaning the UI hint 'token lacks Administer Project permission' would go dark if the flag assignment were accidentally removed.
- **Evidence:** `// SonarDataService.kt lines 447-449
if (ceTasksResult.type == ErrorType.FORBIDDEN) {
    analysisHistoryForbidden = true
    log.info("[Sonar:CE] /api/ce/activity 403 — token lacks Administer Project permission")
}`
- **Fix:** In a TestSonarDataService harness (or extending the existing one in SonarDataServiceTest), stub `getAnalysisTasks` to return `ApiResult.Error(ErrorType.FORBIDDEN, "403")` and assert `stateFlow.value.analysisHistoryForbidden == true`. Also verify that the state's `recentAnalyses` is empty (not the stale previous value).

### SONAR-COV-7 — SonarDataService.mapProjectHealth: no test for rating-letter mapping, partial metrics, or non-numeric values
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarDataServiceTest.kt`
- **Problem:** The private `mapProjectHealth` method converts SonarQube numeric rating strings ("1.0".."5.0") via `SonarRatingUtils.ratingLetter`, and defaults all fields to 0/empty when the corresponding metric is missing. This method is tested only via the `refreshWith` integration path which uses an empty measures list (so all defaults). No test covers: a full metric set with real rating values, a non-numeric rating value, or missing individual metrics. `SonarRatingUtils.ratingLetter` itself also has zero dedicated tests.
- **Evidence:** `// SonarDataService.kt lines 626-637
maintainabilityRating = SonarRatingUtils.ratingLetter(byMetric[SonarMetricKey.SQALE_RATING]?.value),
reliabilityRating = SonarRatingUtils.ratingLetter(byMetric[SonarMetricKey.RELIABILITY_RATING]?.value),
securityRating = SonarRatingUtils.ratingLetter(byMetric[Son`
- **Fix:** Add a dedicated `SonarRatingUtilsTest` covering: `ratingLetter("1.0") == "A"`, `ratingLetter("5.0") == "E"`, `ratingLetter("6.0") == ""` (out of range), `ratingLetter(null) == ""`, `ratingLetter("abc") == ""`. Also add a `mapProjectHealth` test within the DataService test double: supply all seven metrics with numeric ratings, assert each letter and numeric field is correctly populated.

### SONAR-COV-9 — SonarServiceImpl.compactLineNumbers: private helper has zero tests despite non-trivial logic
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/BranchQualityReportTest.kt`
- **Problem:** `compactLineNumbers` compresses a sorted line list into range strings (e.g. `[1,2,3,5,7,8]` → `"1-3, 5, 7-8"`). It has non-trivial branching: single-element runs, consecutive runs, non-consecutive gaps, and empty input. The method is private but embedded in the agent's LLM summary output, so a bug (e.g. off-by-one in `end + 1`) produces a silently wrong report. No test covers it directly or indirectly through `getBranchQualityReport`.
- **Evidence:** `// SonarServiceImpl.kt lines 1258-1275
private fun compactLineNumbers(lines: List<Int>): String {
    if (lines.isEmpty()) return ""
    val sorted = lines.sorted()
    ...
    for (i in 1 until sorted.size) {
        if (sorted[i] == end + 1) { end = sorted[i] }
        else {
            ranges.ad`
- **Fix:** Extract `compactLineNumbers` to an `internal` function (or a companion object) so it can be tested directly, or test it end-to-end through `getBranchQualityReport` by asserting specific substrings in the returned `summary` field. Required test cases: empty list → `""`, single element → `"42"`, consecutive range → `"1-3"`, single gap → `"1, 3"`, mixed → `"1-3, 5, 7-9"`.

### SONAR-COV-10 — SonarServiceImpl.componentExistsCached: TTL expiry and cache invalidation on URL change are untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarBranchReportGateAndDupTest.kt`
- **Problem:** `componentExistsCached` in `SonarServiceImpl` caches `/api/components/show` results per `(sonarUrl, projectKey)` with a 5-minute TTL. The cache key is `ConnectionSettings.getInstance().state.sonarUrl to projectKey`, meaning a URL change in settings invalidates the key. No tests cover: (a) cache-hit short-circuit avoids a second HTTP call, (b) TTL expiry triggers re-fetch, or (c) stale URL in key means a URL change forces a fresh probe. The `SonarBranchReportGateAndDupTest` uses `testClient` but bypasses the `getIssues` → `componentExistsCached` code path because the test calls `getBranchQualityReport` (which does not call `componentExistsCached`).
- **Evidence:** `// SonarServiceImpl.kt lines 73-84
private val componentExistsCache = java.util.concurrent.ConcurrentHashMap<...>()
private suspend fun componentExistsCached(api: SonarApiClient, projectKey: String): ApiResult<Boolean> {
    val baseUrl = ConnectionSettings.getInstance().state.sonarUrl
    val key =`
- **Fix:** Add tests to a new `SonarServiceImplIssuesTest` using the `testClient` seam:
1. Cache-hit: call `getIssues(...)` twice; stub `componentExists` to be called only once; assert the second call does not invoke `api.componentExists` (verify with `coVerify(exactly = 1)`).
2. Transient error fall-through: stub `componentExists` to return `ApiResult.Error(ErrorType.NETWORK_ERROR, ...)`, assert `getIssues` proceeds to `api.getIssues` anyway (no error returned for a probe failure).
3. 'Not found' response: stub `componentExists` to return `ApiResult.Success(false)`, assert `getIssues` returns `ToolResult` with `isError = true` and a project-not-found message.

### SONAR-COV-11 — SonarDataService.refreshWith: newCodeMode heuristic (branch-isMain detection) is untested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarDataServiceTest.kt`
- **Problem:** The `newCodeMode` resolution in `refreshWith` (lines 513-528) has three priority branches: (1) persisted setting (sonarPreferredCodeMode 1/2), (2) branch-isMain heuristic (new state when projectKey/branch changes: main → false, non-main → true), and (3) in-session toggle preserved. The TestSonarDataService double hard-codes `newCodeMode = false` in its state construction, so none of these branches are exercised.
- **Evidence:** `// SonarDataService.kt lines 512-528
newCodeMode = run {
    val prevWasInitial = _stateFlow.value.projectKey != projectKey
        || _stateFlow.value.branch != branch
    val persisted = when (settings.state.sonarPreferredCodeMode) {
        1 -> false  2 -> true  else -> null
    }
    when {
   `
- **Fix:** Extend the TestSonarDataService harness (or create a focused test class) that replicates the newCodeMode logic. Test cases: (a) `sonarPreferredCodeMode = 1` → `newCodeMode = false` regardless of branch type; (b) `sonarPreferredCodeMode = 2` → `newCodeMode = true`; (c) no setting, main branch → `newCodeMode = false`; (d) no setting, non-main branch → `newCodeMode = true`; (e) same projectKey/branch as previous state → in-session toggle value preserved.

### SONAR-COV-12 — SonarApiClient.paginateSonarItems: MAX_ISSUES_PAGES hard-cap path is not tested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClientTest.kt`
- **Problem:** The `paginateSonarItems` helper includes a post-loop return at line 296 that fires when MAX_ISSUES_PAGES (20) is exhausted before `allItems.size >= total`. This 'truncated result' path has a log.warn but returns `ApiResult.Success` with partial data. No test exercises it for either issues or hotspots — the existing pagination tests all stop before the cap.
- **Evidence:** `// SonarApiClient.kt lines 296-297
log.warn("[Sonar:API] $logTag: hit MAX_ISSUES_PAGES=$MAX_ISSUES_PAGES cap — returning ${allItems.size} items (project may have more)")
return ApiResult.Success(combineResult(firstPageEnvelope!!, allItems))`
- **Fix:** Add a test that sets up `MAX_ISSUES_PAGES + 1` pages worth of data but declares a `total` larger than `MAX_ISSUES_PAGES * ISSUES_PAGE_SIZE`. Because actually enqueueing 21 responses is impractical, override `MAX_ISSUES_PAGES` or use a tiny custom wrapper. Alternatively, test the cap at a reduced page count by creating a `SonarApiClient` with a known-small page constant. Assert: (a) exactly `MAX_ISSUES_PAGES` requests are made, (b) result is `ApiResult.Success` with `MAX_ISSUES_PAGES * pageSize` items.

### SONAR-COV-13 — CoverageMapper.mapLineStatuses: coveredConditions=null with non-null conditions edge case not tested
- **Severity:** Low  **Category:** coverage  **Lens:** coverage
- **File:** `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/CoverageMapperTest.kt`
- **Problem:** In `mapLineStatuses`, the PARTIAL branch triggers when `coveredConditions != null && coveredConditions < conditions`. However, if `conditions` is non-null but `coveredConditions` IS null (a valid API response when the branch count is present but no coverage info is available), the line falls through to the `else -> LineCoverageStatus.COVERED` branch — which is arguably wrong (it should be PARTIAL or UNCOVERED for a line with uncovered branches). There is no test for this combination.
- **Evidence:** `// CoverageMapper.kt lines 47-51
val status = when {
    line.lineHits == 0 -> LineCoverageStatus.UNCOVERED
    line.conditions != null && line.coveredConditions != null
        && line.coveredConditions < line.conditions -> LineCoverageStatus.PARTIAL
    else -> LineCoverageStatus.COVERED  // hit w`
- **Fix:** Add a test to CoverageMapperTest:
```kotlin
val lines = listOf(SonarSourceLineDto(line = 1, lineHits = 1, conditions = 2, coveredConditions = null))
val result = CoverageMapper.mapLineStatuses(lines)
// Document the current behavior — either assert COVERED (current) to pin it,
// or fix to PARTIAL and assert PARTIAL.
```
The fix should likely treat `conditions != null && coveredConditions == null` as PARTIAL since branch data is present but untested.

## Rejected (audit trail)
- **JIRA-COR-6** (`:jira`, Medium) IssueDetailCache.updateComments/updateAttachments acquire the synchronized wrapper's monitor while already holding it implicitly via the wrapper's get() — harmful=True; The finding's core claim is wrong. `synchronized(cache)` at lines 57 and 64 explicitly locks on the `Collections.synchronizedMap` wrapper object for the ENTIRE read-modify-write sequence (`cache.get` 
- **JIRA-COR-9** (`:jira`, Low) QuickCommentPanel.loadedForProjectKey has a TOCTOU race: two concurrent coroutines can both bypass the early-return check and both trigger the API call — harmful=False; The finding's premise is wrong. In QuickCommentPanel.kt the field `loadedForProjectKey` is marked `@Volatile` (line 73). More critically, the assignment `loadedForProjectKey = projectKey` is at line 1
- **JIRA-CLE-5** (`:jira`, Low) Unused public method `isValidBranchName` — no production callers — harmful=True; Confirmed: `isValidBranchName` at line 77 of BranchNameValidator.kt has zero production callers; its only callers are in `BranchNameValidatorTest`. The CLAUDE.md MEMORY.md entry for P2 jira:F-15 expli
- **JIRA-ARC-5** (`:jira`, Critical) JiraTaskRepository uses BaseRepositoryImpl.password field as token provider — credential may be persisted to XML by Tasks framework — harmful=True; Not real as described. Bytecode analysis of `BaseRepository.class` from the bundled `ideaIU-2025.1.7-aarch64` tasks-core.jar (the exact IDE version targeted) shows `loadPassword()` calls `PasswordSafe
- **JIRA-COV-11** (`:jira`, Low) IssueDetailCache.updateComments and updateAttachments — race condition between synchronized(cache) block and the outer Collections.synchronizedMap wrapper not tested — harmful=True; The claimed race condition does not exist. IssueDetailCache (lines 29-68) stores its backing map as Collections.synchronizedMap(LinkedHashMap). The synchronizedMap wrapper uses 'this' (the wrapper obj
- **BAMBOO-ARC-2** (`:bamboo`, Medium) LatestBuildLookupImpl and ChainKeyResolverImpl look up BambooServiceImpl by concrete class, bypassing the service interface — harmful=True; Not a real architecture violation — this is intentional, documented design. `BambooServiceImpl.client` is declared `internal` with KDoc explicitly stating it is 'Exposed as `internal` so that sibling 
- **BAMBOO-ARC-6** (`:bamboo`, Medium) BuildDashboardPanel reads git repository state (currentRevision, remotes) without a readAction guard from a background coroutine — harmful=False; The finding overstates the problem and misidentifies the root cause. `GitRepository.currentRevision` and `repo?.remotes` are NOT IntelliJ PSI data — they are git4idea's own cached state properties bac
- **SONAR-ARC-7** (`:sonar`, Low) CoveragePreviewPanel makes a SonarDataService.getLineCoverage suspend call from a plain Dispatchers.IO scope without injecting the suspend call through a proper IO context — harmful=False; The finding itself concludes 'no structural change needed for the scope itself' and explicitly states the actionable issue is covered by SONAR-ARC-1 (Dispatchers.Main → Dispatchers.EDT). The scope all
- **AUTOMATION-CLE-7** (`:automation`, Low) QueueEntryStatus.TRIGGERING is never set and creates dead branches in QueueStatusPanel — harmful=True; The finding is correct that TRIGGERING is never assigned by QueueService in production and the when-arms at QueueStatusPanel.kt lines 144 and 161 are unreachable at runtime. However, the suggested fix
- **AUTOMATION-ARC-2** (`:automation`, Medium) loadGeneration counter mutated from both EDT and IO dispatcher without @Volatile — harmful=True; The finding claims line 659 reads loadGeneration on Dispatchers.IO, but inspection of the actual code shows line 658 is invokeLater { and line 659 is if (token != loadGeneration) { — the read is insid
- **AUTOMATION-ARC-5** (`:automation`, Low) AutomationTriggered event carries full dockerTagsJson payload on EventBus — harmful=True; The finding's central claim — 'no known subscriber currently uses the raw JSON value from the event' — is factually wrong. HandoverStateService.kt:243-260 explicitly consumes WorkflowEvent.AutomationT
- **HANDOVER-COV-10** (`:handover`, Medium) CopyrightFixService.consolidateYears: inverted year range (end < start) expands to a huge set — untested — harmful=False; The claimed behavioral bug does not exist. Verified by running the actual Kotlin code: consolidateYears("2025-2020", 2026) returns "2020-2026", not "2025-2026" or "2025-currentYear" as the finding imp