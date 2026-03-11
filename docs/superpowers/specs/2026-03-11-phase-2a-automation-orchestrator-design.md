# Phase 2A: Automation Orchestrator — Design Specification

> **Date:** 2026-03-11
> **Status:** Draft
> **Gate 7 Milestone:** Queue management — end-to-end automation suite control from IDE
> **Prerequisites:** Phase 1C (Bamboo build monitoring), Phase 1A (settings & connectivity)

---

## 1. Scope

Eliminates the most painful manual workflow in the development cycle: constructing Docker tag JSON payloads, navigating Bamboo's "Run Customized" UI, and manually coordinating queue access to shared automation suites.

| # | Feature | Surface |
|---|---------|---------|
| 43 | Smart `dockerTagsAsJson` payload builder | Tag staging table in Automation tab |
| 44 | Baseline run auto-picker (best of last N) | Baseline dropdown in toolbar |
| 45 | Current-repo tag auto-replacement | Auto-detected on tab open |
| 46 | Per-service inline tag editing | Editable cells in staging table |
| 47 | Docker Registry tag validation | Registry status column + Validate All button |
| 48 | Configuration drift detector | Drift column + Update All to Latest button |
| 49 | Smart conflict detector | Alert bar with running build overlap warnings |
| 50 | Suite variable & stage configuration | Variables/Stages panel (per-suite, persistent) |
| 51 | Queue management with auto-trigger | Queue Run button + background polling + auto-fire |
| 52 | Queue visibility & cancellation | Live status bar + queue position + Cancel button |
| 53 | Run history & config recall | History button + last 5 saved configurations |
| 54 | IDE restart & sleep recovery | SQLite persistence + Bamboo reconciliation on startup |

**Configurability principle:** Each suite is independently configurable. Suite configurations (variables, stages) persist at the IDE application level — set once, available across all projects and IDE restarts. Tag history persists per-project in SQLite.

**Out of scope:** Multi-user shared queue server (queue is local per-plugin), Bamboo plan creation/management, Docker image building, CI/CD pipeline editing, Bitbucket PR integration (that's Phase 2B).

---

## 2. Module Architecture

**One new Gradle module: `:automation`**

Phase 2A introduces a dedicated module for automation suite orchestration. Unlike Phase 1F (which added to existing modules), this feature set is a distinct domain with its own API clients, persistence, and UI — warranting a clean module boundary.

```
:automation
├── api/
│   └── DockerRegistryClient.kt          — Docker Registry v2 API client
├── service/
│   ├── TagBuilderService.kt             — Payload construction & smart defaults
│   ├── QueueService.kt                  — Local queue + auto-trigger + polling
│   ├── DriftDetectorService.kt          — Tag staleness detection
│   ├── ConflictDetectorService.kt       — Running build overlap detection
│   ├── AutomationSettingsService.kt     — APP-level suite config persistence
│   └── TagHistoryService.kt             — PROJECT-level SQLite history
├── model/
│   ├── AutomationModels.kt              — Suite, QueuedRun, TagEntry, etc.
│   └── DockerRegistryDtos.kt            — Registry v2 API response DTOs
└── ui/
    ├── AutomationTabProvider.kt         — WorkflowTabProvider implementation
    ├── AutomationPanel.kt               — Main panel orchestrating sub-panels
    ├── TagStagingPanel.kt               — Service table with inline editing
    ├── SuiteConfigPanel.kt              — Variables, stages, suite selector
    └── QueueStatusPanel.kt              — Live status, queue position, actions
```

**Module dependencies:**
```
:automation → :core   (EventBus, PluginSettings, HTTP infra, notifications)
:automation → :bamboo (BambooApiClient — extended with new methods)
```

**Existing module modifications:**

| Module | Change |
|--------|--------|
| `:core` | Add new WorkflowEvent subtypes for automation lifecycle |
| `:bamboo` | Extend `BambooApiClient` with queue-polling and build-variable methods |
| `:core` | Add `dockerRegistryUrl` and `automationModuleEnabled` to `PluginSettings` |

---

## 3. Smart Tag Builder (Features 43-46)

### 3.1 Baseline Auto-Picker

The tag builder eliminates the manual process of finding the "best" previous run to base tags on.

**Algorithm:**
1. Fetch last 10 build results from Bamboo for the selected suite plan
2. Score each run: `score = (releaseTagCount * 10) + (successfulStageCount * 5) - (failedStageCount * 20)`
3. Pre-select the highest-scoring run as the baseline
4. Present last 5 runs in a dropdown with summary: `"★ Best match — Run #847 (14/14 release, 0 errors)"`

```kotlin
data class BaselineRun(
    val buildNumber: Int,
    val resultKey: String,
    val dockerTags: Map<String, String>,     // service → tag
    val releaseTagCount: Int,
    val totalServices: Int,
    val successfulStages: Int,
    val failedStages: Int,
    val triggeredAt: Instant,
    val score: Int
)
```

**Fetching tags from a run:** The `dockerTagsAsJson` variable value is extracted from the build result's variables via `BambooApiClient.getBuildVariables(resultKey)`.

### 3.2 Current-Repo Tag Auto-Replacement

When the Automation tab opens:
1. Detect current repo's service name — derived from project name or a configurable mapping in `AutomationSettingsService`
2. Detect current branch's Docker tag — query Bamboo for the latest build artifact of the current branch using `BambooApiClient.getLatestResult(planKey, branchName)`
3. Replace the baseline tag for that service with the feature branch tag
4. Highlight the row in green with "Your branch" badge

```kotlin
data class CurrentRepoContext(
    val serviceName: String,          // e.g., "service-auth"
    val branchName: String,           // e.g., "feature/PROJ-123"
    val featureBranchTag: String?,    // e.g., "feature/PROJ-123-a1b2c3d"
    val detectedFrom: DetectionSource // PROJECT_NAME, SETTINGS_MAPPING, GIT_BRANCH
)
```

**Service name detection strategy (in order):**
1. Explicit mapping in `AutomationSettingsService` (user configured)
2. Match project directory name against known service names from the baseline run
3. Fuzzy match current Git branch ticket ID (e.g., `PROJ-123`) against Bamboo branch builds

### 3.3 Per-Service Inline Editing

Each tag cell in the staging table is an editable text field. Editing a tag:
- Triggers a background Docker Registry validation for the new value
- Updates the row status (green ✓ / red ✗)
- Marks the row as "modified" (distinguishing user edits from baseline defaults)
- Updates the JSON payload preview in real-time

### 3.4 Tag Entry Model

```kotlin
data class TagEntry(
    val serviceName: String,
    val currentTag: String,
    val latestReleaseTag: String?,       // from Docker Registry
    val source: TagSource,               // BASELINE, USER_EDIT, AUTO_DETECTED
    val registryStatus: RegistryStatus,  // VALID, NOT_FOUND, CHECKING, UNKNOWN
    val isDrift: Boolean,                // currentTag != latestReleaseTag
    val isCurrentRepo: Boolean           // highlighted green
)

enum class TagSource { BASELINE, USER_EDIT, AUTO_DETECTED }
enum class RegistryStatus { VALID, NOT_FOUND, CHECKING, UNKNOWN, ERROR }
```

---

## 4. Docker Registry Integration (Feature 47)

### 4.1 DockerRegistryClient

HTTP client for Docker Registry v2 API. Uses the same OkHttp infrastructure as `BambooApiClient` (via `:core` HTTP module).

**API endpoints used:**

| Operation | Endpoint | Purpose |
|-----------|----------|---------|
| List tags | `GET /v2/{name}/tags/list` | Get all available tags for a service |
| Check tag | `GET /v2/{name}/manifests/{tag}` | Verify tag exists (HEAD request) |

```kotlin
class DockerRegistryClient(
    private val registryUrl: String,
    private val tokenProvider: () -> String?
) {
    // Uses separate OkHttpClient from BambooApiClient to isolate connection pools.
    // Implements Docker Registry V2 token auth handshake:
    // 1. Request returns 401 with WWW-Authenticate header
    // 2. Parse realm/service/scope from header
    // 3. Fetch bearer token from auth endpoint
    // 4. Cache token, refresh at 80% of TTL
    // 5. Retry original request with token

    suspend fun listTags(serviceName: String): ApiResult<List<String>>
    // Follows Link header pagination. Max 50 pages / 5000 tags safety limit.
    // Uses HEAD /v2/{name}/manifests/{tag} for single-tag checks (O(1)).

    suspend fun tagExists(serviceName: String, tag: String): ApiResult<Boolean>
    suspend fun getLatestReleaseTag(serviceName: String): ApiResult<String?>

    // Cached results with 5-minute TTL to reduce registry API load
    private val tagCache: Cache<String, List<String>>
}
```

**`getLatestReleaseTag` logic:**
1. Fetch all tags via `listTags()`
2. Filter to release tags (match pattern: `^\d+\.\d+\.\d+.*$` — semver-like)
3. Sort by semver ordering
4. Return the highest version

### 4.2 Validation Flow

"Validate All" button triggers parallel validation of all tags:
1. For each `TagEntry`, fire `DockerRegistryClient.tagExists(service, tag)` concurrently
2. Update `registryStatus` as results arrive (streaming UI update)
3. Invalid tags shown in red with "✗ Not found" status
4. Summary in alert bar: "2 of 14 tags invalid — fix before triggering"

Validation also runs automatically before `triggerBuild()` — if any tag is `NOT_FOUND`, the trigger is blocked with a notification.

---

## 5. Drift Detection (Feature 48)

### 5.1 DriftDetectorService

Checks whether staged tags are stale compared to the latest release in the Docker Registry.

```kotlin
@Service(Service.Level.PROJECT)
class DriftDetectorService(private val project: Project) {

    suspend fun checkDrift(entries: List<TagEntry>): List<DriftResult>

    data class DriftResult(
        val serviceName: String,
        val currentTag: String,
        val latestReleaseTag: String,
        val isStale: Boolean
    )
}
```

**Flow:**
1. For each non-feature-branch tag entry, call `DockerRegistryClient.getLatestReleaseTag(service)`
2. Compare staged version against latest release
3. If staged < latest → mark as drift (yellow ⚠ in UI)
4. "Update All to Latest" button replaces all stale tags with their latest release versions

**When drift is checked:**
- On tab open (once per session, cached for 5 minutes)
- On "Validate All" click
- Before auto-trigger fires (staleness guard)

---

## 6. Conflict Detection (Feature 49)

### 6.1 ConflictDetectorService

Detects when another team member's automation run targets the same microservices you're about to trigger.

```kotlin
@Service(Service.Level.PROJECT)
class ConflictDetectorService(private val project: Project) {

    suspend fun checkConflicts(stagedTags: Map<String, String>): List<Conflict>

    data class Conflict(
        val serviceName: String,
        val yourTag: String,
        val otherTag: String,
        val triggeredBy: String,
        val buildNumber: Int,
        val isRunning: Boolean     // true = running, false = queued on Bamboo
    )
}
```

**Flow:**
1. Call `BambooApiClient.getRunningAndQueuedBuilds(planKey)` — new method, returns active/queued builds
2. For each running/queued build, extract `dockerTagsAsJson` from its variables via `BambooApiClient.getBuildVariables(resultKey)`
3. Parse the JSON, compare service keys with user's staged tags
4. If overlap found → return `Conflict` with details

**UI presentation:**
- Alert bar: `"⚠ @dev-jones is also running with service-auth in Run #848"`
- Conflict is **warn-only** — user can still trigger (as per user requirement)
- Conflict services highlighted with a distinct indicator in the tag table

### 6.2 New BambooApiClient Methods

```kotlin
// Added to existing BambooApiClient

suspend fun getRunningAndQueuedBuilds(planKey: String): ApiResult<List<BambooBuildStatusDto>>

suspend fun getBuildVariables(resultKey: String): ApiResult<Map<String, String>>

suspend fun cancelBuild(resultKey: String): ApiResult<Unit>
```

**Bamboo REST API endpoints:**

| Method | Endpoint |
|--------|----------|
| `getRunningAndQueuedBuilds` | `GET /rest/api/latest/result/{planKey}?includeAllStates=true&buildstate=Unknown&max-results=5` |
| `getBuildVariables` | `GET /rest/api/latest/result/{resultKey}?expand=variables` |
| `cancelBuild` | `DELETE /rest/api/latest/queue/{resultKey}` |

---

## 7. Suite Configuration (Feature 50)

### 7.1 AutomationSettingsService

Application-level persistent service for suite configurations. Stored in IntelliJ's app config directory — survives across all projects and IDE restarts.

```kotlin
@Service(Service.Level.APP)
@State(
    name = "AutomationSuiteSettings",
    storages = [Storage("workflowAutomationSuites.xml")]
)
class AutomationSettingsService : PersistentStateComponent<AutomationSettingsService.State> {

    data class SuiteConfig(
        val planKey: String,                       // Bamboo plan key
        val displayName: String,                   // User-friendly name
        val variables: Map<String, String>,         // key → value (e.g., "suiteType" → "regression")
        val enabledStages: List<String>,           // checked stage names
        val serviceNameMapping: Map<String, String>?,  // optional: project dir → service name
        val lastModified: Long
    )

    data class State(
        val suites: MutableMap<String, SuiteConfig> = mutableMapOf()
    )

    fun getSuiteConfig(planKey: String): SuiteConfig?
    fun saveSuiteConfig(config: SuiteConfig)
    fun getAllSuites(): List<SuiteConfig>
}
```

**Persistence behavior:**
- Variables and stages auto-save on change (debounced 2 seconds)
- "Saved for E2E-Payments • Last modified 3d ago" indicator in UI
- Configuration is per-suite, per-IDE-installation
- No explicit "save" button needed — changes persist automatically

### 7.2 Variables UI

Variables are fetched from Bamboo plan definition via `BambooApiClient.getVariables(planKey)`:
- Key dropdown populated with available plan variables
- Value is free-text input
- User can add/remove variable overrides
- Only overridden variables are sent with the trigger — others use Bamboo defaults

### 7.3 Stage Selection

Stages are fetched from the latest build result's stage list:
- Presented as checkboxes
- Checked stages are passed to `triggerBuild()` via the `stage` parameter
- Stage selection persists in `SuiteConfig`

---

## 8. Queue Management (Features 51-52)

### 8.1 QueueService

The core queue orchestration service. Manages local queue state, background polling, and auto-trigger.

```kotlin
@Service(Service.Level.PROJECT)
class QueueService(private val project: Project) : Disposable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class QueueEntry(
        val id: String,                    // UUID
        val suiteConfig: SuiteConfig,
        val dockerTagsPayload: String,     // complete JSON
        val variables: Map<String, String>,
        val stages: List<String>,
        val enqueuedAt: Instant,
        val status: QueueEntryStatus,
        val bambooResultKey: String?       // set after trigger
    )

    enum class QueueEntryStatus {
        WAITING_LOCAL,       // waiting for suite to become idle
        TRIGGERING,          // API call in flight
        QUEUED_ON_BAMBOO,    // triggered but queued on Bamboo's side
        RUNNING,             // build is actively running
        COMPLETED,           // build finished (check result)
        FAILED_TO_TRIGGER,   // trigger API call failed
        TAG_INVALID,         // pre-trigger re-validation failed (tag deleted from registry)
        PLAN_UNAVAILABLE,    // Bamboo plan disabled or deleted
        STALE,               // server config changed, needs re-validation
        CANCELLED            // user cancelled
    }

    // --- Public API ---
    fun enqueue(entry: QueueEntry)
    fun cancel(entryId: String)
    fun getActiveEntries(): List<QueueEntry>
    val stateFlow: StateFlow<List<QueueEntry>>    // reactive UI updates
}
```

### 8.2 Polling & Auto-Trigger Flow

```
enqueue() called
    │
    ├─ Save to SQLite (persistence)
    ├─ Emit QueuePositionChanged event
    └─ Start/continue polling job
         │
         ▼
    ┌─────────────────────────┐
    │ Poll every 60 seconds   │◄──────────────────┐
    │ GET build status        │                    │
    └────────┬────────────────┘                    │
             │                                     │
        Suite idle?                                │
        ├─ NO → update UI (progress, ETA) ────────┘
        │
        ├─ YES → Pre-trigger validation
        │        ├─ Re-validate tags exist in registry
        │        ├─ Re-check for conflicts
        │        │
        │        ├─ Validation PASS:
        │        │   ├─ Call triggerBuild(planKey, payload)
        │        │   ├─ Verify build started (immediate poll)
        │        │   │   ├─ Started → status = RUNNING, monitor build
        │        │   │   └─ Claimed by other → status = WAITING_LOCAL, re-queue
        │        │   └─ Emit AutomationTriggered event
        │        │
        │        └─ Validation FAIL:
        │            ├─ Notify user: "Tags invalid, auto-trigger paused"
        │            └─ status = WAITING_LOCAL (user must fix & re-validate)
        │
        └─ Build finishes → Emit AutomationFinished event, notify pass/fail
```

### 8.3 Race Condition Handling

When two plugins trigger simultaneously:
1. Plugin calls `triggerBuild()` → receives `buildResultKey`
2. Immediately polls `getLatestResult()` for that key
3. If build state is `QUEUED` (not `IN_PROGRESS`) and another build is already running:
   - Update entry status to `QUEUED_ON_BAMBOO`
   - Notify: "Your run is queued on Bamboo behind @dev-smith's run"
   - Continue monitoring — Bamboo will run it when the current build finishes
4. If build state is `IN_PROGRESS` → success, monitor normally

### 8.4 Orphan Prevention Rules

**Invariant: every trigger that exists in the system is visible and cancellable.**

| Scenario | Handling |
|----------|----------|
| Local queue entry | Shown in UI with Cancel button → removes from SQLite, stops polling |
| Bamboo-queued build | Shown as "Queued on Bamboo" with Cancel → calls `cancelBuild(resultKey)` |
| Running build | Shown as "Running" with progress → no cancel (Bamboo limitation), but visible |
| IDE restart | On startup: read SQLite → reconcile with Bamboo API → update/remove stale entries |
| Laptop sleep | Coroutine resumes on wake → immediate poll → correct state restored |
| Trigger API failure | Entry marked `FAILED_TO_TRIGGER` → shown with "Retry" button |

### 8.5 Wait Time Estimation

Estimated wait time is calculated from historical data:
1. Query last 10 completed builds for the suite plan
2. Calculate average build duration
3. Subtract elapsed time of current running build
4. Add: `(queuePosition - 1) * averageDuration` for others ahead in queue
5. Display: "Est. wait: ~12 min"

---

## 9. Run History (Feature 53)

### 9.1 TagHistoryService

Persists the last 5 triggered configurations per suite in SQLite.

```kotlin
@Service(Service.Level.PROJECT)
class TagHistoryService(private val project: Project) {

    data class HistoryEntry(
        val id: String,
        val suitePlanKey: String,
        val dockerTagsJson: String,
        val variables: Map<String, String>,
        val stages: List<String>,
        val triggeredAt: Instant,
        val buildResultKey: String?,
        val buildPassed: Boolean?
    )

    fun save(entry: HistoryEntry)
    fun getHistory(suitePlanKey: String, limit: Int = 5): List<HistoryEntry>
    fun loadAsBaseline(entryId: String): Map<String, String>  // returns tag map for staging
}
```

**SQLite schema:**
```sql
-- Schema versioning for safe plugin updates
CREATE TABLE schema_metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
INSERT INTO schema_metadata VALUES ('schema_version', '1');

-- WAL mode for concurrent read/write safety
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;
PRAGMA wal_autocheckpoint = 1000;

-- Queue entries (persisted for IDE restart recovery)
CREATE TABLE queue_entries (
    id TEXT PRIMARY KEY,
    suite_plan_key TEXT NOT NULL,
    docker_tags_json TEXT NOT NULL,
    variables_json TEXT NOT NULL,
    stages_json TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'WAITING_LOCAL',
    bamboo_result_key TEXT,
    enqueued_at INTEGER NOT NULL,
    sequence_order INTEGER NOT NULL,       -- monotonic ordering, not wall-clock
    updated_at INTEGER NOT NULL,
    error_message TEXT
);

CREATE INDEX idx_queue_suite ON queue_entries(suite_plan_key, sequence_order);
CREATE INDEX idx_queue_status ON queue_entries(status);

-- Run history (last 5 per suite)
CREATE TABLE automation_history (
    id TEXT PRIMARY KEY,
    suite_plan_key TEXT NOT NULL,
    docker_tags_json TEXT NOT NULL,
    variables_json TEXT NOT NULL,
    stages_json TEXT NOT NULL,
    triggered_at INTEGER NOT NULL,
    build_result_key TEXT,
    build_passed INTEGER,
    created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now'))
);

CREATE INDEX idx_history_suite ON automation_history(suite_plan_key, triggered_at DESC);
```

**History UI:** "History" button opens a dropdown showing last 5 runs with timestamp, pass/fail status, and "Load" action to populate the staging table.

---

## 10. IDE Restart & Sleep Recovery (Feature 54)

### 10.1 Startup Reconciliation

On IDE startup (via `postStartupActivity`):

1. Read all `QueueEntry` records from SQLite where status is not terminal (`COMPLETED`, `CANCELLED`)
2. For entries with `bambooResultKey`:
   - Query Bamboo for current build status
   - If build finished → update entry, notify user of result
   - If build still running → resume monitoring
   - If build not found (deleted/expired) → mark entry as stale, notify user, remove
3. For entries without `bambooResultKey` (never triggered):
   - Resume local queue polling
4. Show recovered entries in the Automation tab immediately

### 10.2 Sleep Recovery

No special handling needed — Kotlin coroutines with `delay()` naturally resume after JVM wake:
- Polling job fires immediately on wake (delay completes retroactively)
- First poll detects current suite state
- UI updates reactively via `StateFlow`

### 10.3 Polling Design Decisions

**`scheduleWithFixedDelay` semantics:** The polling job uses fixed-delay (not fixed-rate). This means the next poll starts 60s after the previous one *completes*, not after it *started*. Prevents poll overlap when Bamboo is slow.

**Adaptive polling:** When a build is actively running (status = `RUNNING` or `QUEUED_ON_BAMBOO`), reduce poll interval to 15 seconds for faster feedback. Return to 60s when no builds are in-flight.

**Staggered suite intervals:** When polling multiple suites, offset each by `60s / suiteCount` to avoid hitting Bamboo API with parallel requests. E.g., with 4 suites: A at 0s, B at 15s, C at 30s, D at 45s.

**Poll-in-progress guard:** An `AtomicBoolean` prevents overlapping polls if a coroutine resumes unexpectedly (e.g., after long sleep).

---

## 11. Events

New `WorkflowEvent` subtypes added to `:core`:

```kotlin
data class AutomationTriggered(
    val suitePlanKey: String,
    val buildResultKey: String,
    val dockerTagsJson: String,
    val triggeredBy: String        // "auto-queue" or "manual"
) : WorkflowEvent()

data class AutomationFinished(
    val suitePlanKey: String,
    val buildResultKey: String,
    val passed: Boolean,
    val durationMs: Long
) : WorkflowEvent()

data class QueuePositionChanged(
    val suitePlanKey: String,
    val position: Int,             // 0 = next up, -1 = removed
    val estimatedWaitMs: Long?
) : WorkflowEvent()
```

---

## 12. Settings Extensions

### 12.1 PluginSettings Additions (Project-level)

```kotlin
// Added to PluginSettings.State
var dockerRegistryUrl: String?              // Docker Registry v2 base URL
var dockerRegistryCaPath: String?           // Custom CA cert path for self-signed registries
var automationModuleEnabled: Boolean        // master toggle for automation tab
var queuePollingIntervalSeconds: Int        // default 60
var queueActivePollingIntervalSeconds: Int  // default 15 (used when builds are in-flight)
var queueAutoTriggerEnabled: Boolean        // default true
var tagValidationOnTrigger: Boolean         // default true (re-validate before fire)
var queueMaxDepthPerSuite: Int              // default 10
var queueBuildQueuedTimeoutSeconds: Int     // default 720 (Bamboo's own default)
```

### 12.2 AutomationSettingsService (App-level)

See Section 7.1 — separate `PersistentStateComponent` at `Service.Level.APP` for suite configurations that persist across all projects.

---

## 13. UI Architecture

### 13.1 Automation Tab Layout

Single tab in the Workflow tool window using vertical stack layout with collapsible sections:

```
┌─ Toolbar ──────────────────────────────────────────┐
│ [Suite ▾] [Baseline ▾]     [Validate] [Update] [History] │
├─ Live Status Bar ──────────────────────────────────┤
│ ● RUNNING — @dev-smith (12m) ████████░░░ ~8 min    │
├─ Alert Bar (conditional) ──────────────────────────┤
│ ⚠ Drift: service-payments 2.3.1 → 2.4.0 [Update]  │
│ ⚠ Conflict: @dev-jones targeting service-auth       │
├─ Tag Staging Table ────────────────────────────────┤
│ Service | Docker Tag (editable) | Latest | Registry | Status │
│ ● auth  | feature/PROJ-123-a1b | 2.4.0  | ✓       | Your branch │
│ payments| 2.3.1                | 2.4.0↑ | ✓       | ⚠ Drift │
│ user    | 1.9.0                | 1.9.0  | ✓       | ✓ OK    │
│ ...     | (scroll for 10-25 services)               │
├─ Variables & Stages (collapsible) ─────────────────┤
│ [suiteType ▾] [regression]  [featureFlag ▾] [true] │
│ ☑ QA Automation  ☐ Smoke Tests  ☐ Performance      │
├─ JSON Preview (collapsible) ───────────────────────┤
│ { "dockerTagsAsJson": { ... } }                     │
├─ Action Bar ───────────────────────────────────────┤
│ Queue: #2 • Est ~8 min    [Cancel] [Queue] [Trigger Now ▶] │
└────────────────────────────────────────────────────┘
```

### 13.2 Component Responsibilities

| Component | Responsibility |
|-----------|---------------|
| `AutomationTabProvider` | Registers tab with `WorkflowTabProvider` EP, creates `AutomationPanel` |
| `AutomationPanel` | Orchestrates sub-panels, toolbar, action bar. Owns the `TagBuilderService` lifecycle. |
| `TagStagingPanel` | JBTable with custom cell editors/renderers for tag editing, status badges |
| `SuiteConfigPanel` | Variables (key dropdown + value text) + stage checkboxes |
| `QueueStatusPanel` | Live status bar, progress, queue position, Cancel/Queue/Trigger buttons |

### 13.3 Status Bar Widget

A status bar widget (like the existing build and ticket widgets) showing queue status at a glance:

```
[🔄 Auto #2 — ~8 min] or [✓ Suite Idle] or [▶ Running — 60%]
```

Clicking opens the Automation tab.

---

## 14. plugin.xml Registrations

```xml
<!-- Automation Module Services -->
<projectService
    serviceImplementation="com.workflow.orchestrator.automation.service.QueueService"/>
<projectService
    serviceImplementation="com.workflow.orchestrator.automation.service.TagBuilderService"/>
<projectService
    serviceImplementation="com.workflow.orchestrator.automation.service.DriftDetectorService"/>
<projectService
    serviceImplementation="com.workflow.orchestrator.automation.service.ConflictDetectorService"/>
<projectService
    serviceImplementation="com.workflow.orchestrator.automation.service.TagHistoryService"/>

<!-- App-level (cross-project) -->
<applicationService
    serviceImplementation="com.workflow.orchestrator.automation.service.AutomationSettingsService"/>

<!-- Automation Tab -->
<tabProvider implementation="com.workflow.orchestrator.automation.ui.AutomationTabProvider"/>

<!-- Status Bar -->
<statusBarWidgetFactory id="WorkflowAutomationStatusBar"
    implementation="com.workflow.orchestrator.automation.ui.AutomationStatusBarWidgetFactory"/>

<!-- Startup Recovery -->
<postStartupActivity
    implementation="com.workflow.orchestrator.automation.service.QueueRecoveryStartupActivity"/>

<!-- Notification Group -->
<notificationGroup id="workflow.automation.queue" displayType="BALLOON"/>
```

---

## 15. Edge Cases & Error Handling

### 15.1 Network & Connectivity

| Scenario | Handling |
|----------|----------|
| Intermittent connectivity during poll | OkHttp timeouts (15s connect, 30s read). Retry with exponential backoff (1s/2s/4s) + jitter. Never dequeue on transient failure. |
| VPN disconnect/reconnect | Stale pooled connections cause `SocketException`. On network change, call `connectionPool.evictAll()`. Use short keep-alive: `ConnectionPool(5, 60, TimeUnit.SECONDS)`. |
| Proxy IP rotation | Pooled connections to old proxy IP fail. Short connection pool TTL + evict-on-error-and-retry pattern. |
| DNS TTL caching prevents failover | JVM caches DNS aggressively. Set `networkaddress.cache.ttl=30` via plugin startup. |
| TLS certificate rotation | Use system trust store (not pinning). Surface clear error on `SSLHandshakeException` with cert config instructions. |
| HTTP/2 connection coalescing | If Bamboo & Registry share a cert/IP, OkHttp may coalesce. **Use separate `OkHttpClient` instances** for Bamboo and Docker Registry to isolate connection pools. |

### 15.2 Bamboo API

| Scenario | Handling |
|----------|----------|
| Rate limiting (HTTP 429) | Parse `Retry-After` header. Backoff with jitter. **Stagger suite polls** — suite A at 0s, B at 15s, C at 30s, D at 45s within each 60s window. |
| PAT expires mid-session (401) | On any 401: pause ALL polling, mark credentials invalid, surface notification prompting re-auth. Never retry-loop with expired credentials (triggers account lockout). |
| Plan disabled between idle-check and trigger | Inspect response body. Return entry to `WAITING_LOCAL`. After 5 consecutive failures, mark `PLAN_UNAVAILABLE` and notify. |
| Plan deleted entirely | Confirmed 404 → mark all entries for that plan as `FAILED`, notify, do not retry. |
| Build stuck in "Queued" forever | Track time in `QUEUED` state. After configurable timeout (default 720s, matching Bamboo's own default), flag to user with option to cancel. Never auto-cancel without consent. |
| Duplicate trigger (result key collision) | Immediately record `buildResultKey` in SQLite and transition to `TRIGGERED` after successful API call. Skip entries already in `TRIGGERED` state on subsequent polls. |
| Bamboo maintenance mode (503) | Treat as transient. Progressively increase poll interval (60s → 120s → 300s). Resume normal interval on success. |
| Bamboo REST plugin disabled | If ALL requests return 404 (across all plans), detect pattern, surface notification "Bamboo REST API appears disabled", pause polling. |
| Variable name prefix mismatch | Auto-validate and prefix variables before sending. Log exact request for debugging. |
| API schema changes across Bamboo versions | Defensive JSON parsing (`ignoreUnknownKeys = true`). Log unparseable responses at WARN level. |

### 15.3 Docker Registry

| Scenario | Handling |
|----------|----------|
| Token auth handshake (401 → token → retry) | Implement full V2 auth: detect 401, parse `WWW-Authenticate` header for realm/service/scope, fetch bearer token, cache with `expires_in`, retry. Refresh at 80% of TTL. |
| Tag deleted between validation and trigger | **Re-validate tags immediately before firing** (not just at enqueue time). If validation fails, move entry to `TAG_INVALID`, notify user. |
| Pagination of tag listing | Follow `Link: <...>; rel="next"` header until absent. Set `n=100` per page. Max 50 pages (5000 tags) to prevent infinite loops. |
| Registry returns exact `n` results without Link header | Assume more pages exist. Request next page using last tag as `last` parameter. Stop when fewer than `n` results. |
| Registry timeout during tag listing | 30-60s read timeout. On timeout, retry once. **Prefer `HEAD /v2/{name}/manifests/{tag}`** for existence checks (O(1) vs O(n) listing). |
| Self-signed / private CA for internal registry | Allow custom CA certificate path in plugin settings. Surface `SSLHandshakeException` as "untrusted certificate" with instructions. |
| Docker Hub rate limiting (100 pulls/6h anonymous) | Cache tag validation results with 5-minute TTL. Use `HEAD` requests. Detect 429, back off. |

### 15.4 Concurrency & Timing

| Scenario | Handling |
|----------|----------|
| Multiple queue entries for different suites trigger simultaneously | Expected behavior — trigger both, let Bamboo manage agent scheduling. Track each build independently. |
| Same suite queued multiple times with different payloads | Allow FIFO ordering with sequence numbers. Only trigger the oldest pending entry per suite. Advance to next on completion. |
| Rapid enqueue then immediate cancel | Use `Mutex` around the poll-check-trigger sequence. Cancel sets `CANCELLED` flag checked before HTTP call. If already triggered, cancel calls `cancelBuild()`. |
| Poll cycle takes longer than 60 seconds | Use `scheduleWithFixedDelay` (not `scheduleAtFixedRate`). Next poll starts 60s *after* previous finishes. Plus `AtomicBoolean` guard against overlapping polls. |
| System wake after long sleep — multiple missed polls | `scheduleWithFixedDelay` naturally handles this — only one poll fires on wake, not a backlog of missed ones. Detect wall-clock jump, run single poll. |
| Manual Bamboo trigger while auto-trigger pending | On next poll, detect plan is busy (someone else triggered). Keep entry as `WAITING_LOCAL`. When idle again, re-validate before auto-firing (tags may be stale). |
| User clicks "Trigger Now" while auto-trigger pending | "Trigger Now" consumes the queue entry (transitions to `TRIGGERED`). Auto-trigger checks entry state before firing. |

### 15.5 Data Integrity (SQLite)

| Scenario | Handling |
|----------|----------|
| SQLITE_BUSY (concurrent writes) | Enable WAL mode (`PRAGMA journal_mode=WAL`). Set `PRAGMA busy_timeout=5000`. Serialize all writes through a `Mutex`. |
| Database corruption (power loss, force kill) | On startup: run `PRAGMA quick_check`. If corrupt → rename to `.bak`, create fresh DB, notify user queue was reset. |
| Disk full (SQLITE_FULL) | Catch error code 13. Notify "Disk full — queue operations suspended." Pause writes. Periodically check space, resume when available. |
| Clock skew affecting timestamps | Use monotonic time (`System.nanoTime()`) for timeout/duration calculations. Wall-clock timestamps only for display. Use auto-increment integer for ordering. |
| Schema migration on plugin update | Store `schema_version` in metadata table. Compare on startup, run migrations forward. If migration fails, rename DB and start fresh. |
| Database on network filesystem (NFS/SMB) | Detect network filesystem path. Warn user. Offer local storage fallback (`~/.cache/workflow-orchestrator/`). SQLite explicitly warns against NFS. |
| WAL file grows unbounded | Keep read transactions short. `PRAGMA wal_autocheckpoint=1000`. Periodic `PRAGMA wal_checkpoint(TRUNCATE)` during idle. |

### 15.6 IntelliJ Platform Lifecycle

| Scenario | Handling |
|----------|----------|
| Project closing while queue active | `QueueService` implements `Disposable`. In `dispose()`: cancel coroutine scope, persist state to SQLite, clean up OkHttp dispatcher. |
| Plugin disable/re-enable at runtime | In `dispose()`: flush state to SQLite. On re-init: read from SQLite. No static mutable state — all state in service instances. |
| LightEdit mode (no project loaded) | Check `LightEdit.owns(project)` before accessing project services. Gracefully degrade — queue unavailable in LightEdit. |
| Power Save Mode | Listen for `PowerSaveMode.Listener`. When enabled: pause polling (or extend to 5-minute interval). Resume on disable. |
| Memory pressure | Minimal memory footprint — use SQLite, not in-memory caches. Register `LowMemoryWatcher` callback to release any caches. |
| EDT blocking | **Never** perform network I/O or SQLite access on EDT. All I/O on `Dispatchers.IO`. UI updates via `invokeLater` or `withContext(Dispatchers.Main)`. |
| Project switch in single-window mode | Disposal must be fast (cancel HTTP immediately, don't wait). Per-project SQLite paths prevent cross-project contamination. |

### 15.7 User Behavior

| Scenario | Handling |
|----------|----------|
| User edits tags after queuing | **Snapshot payload at enqueue time** in SQLite. Queued entry uses snapshot, not live settings. Display snapshotted payload in queue UI. |
| User switches suite while queue entry pending | Queue is a persistent list across all suites, not tied to current UI selection. Show all pending entries in a unified queue view. Suite switching only affects the config panel. |
| User changes Bamboo URL/credentials while entries queued | On credential change: mark all pending entries as `STALE`. Prompt: "Server config changed. Re-validate queued entries?" |
| User deletes `.idea` directory or SQLite DB | On any SQLite access, catch file-not-found. Recreate DB with current schema. Notify user queue was reset. |
| User expects real-time status but poll is 60s | **Adaptive polling:** after triggering, reduce to 15s for active builds. Return to 60s when no builds in-flight. Show "Last checked: Xs ago" with manual Refresh button. |
| User queues entries faster than processing | Configurable max queue depth (default: 10 per suite, 30 total). Reject beyond limit with clear message. Show estimated wait. |
| Tool window closed while queue active | Polling service is project-level, decoupled from UI. Runs regardless of whether tool window is visible. |

---

## 16. Testing Strategy

### 16.1 Core Service Tests

| Layer | Coverage | Approach |
|-------|----------|----------|
| DockerRegistryClient | Tag listing, existence check, pagination, auth handshake, error codes | MockWebServer fixtures |
| TagBuilderService | Baseline scoring, tag replacement, payload construction | Unit tests with mock API responses |
| QueueService | Enqueue, cancel, state transitions, polling lifecycle, max depth enforcement | Unit tests with mock BambooApiClient |
| DriftDetectorService | Staleness detection, semver comparison | Unit tests with mock registry |
| ConflictDetectorService | Overlap detection, JSON parsing, empty-result handling | Unit tests with fixture data |
| AutomationSettingsService | Persistence, cross-project access, auto-save debounce | Unit tests |
| TagHistoryService | SQLite CRUD, limit enforcement, schema migration | Unit tests with in-memory SQLite |
| BambooApiClient extensions | New endpoints, error handling, variable extraction | MockWebServer tests |
| Event emission | AutomationTriggered, Finished, QueuePositionChanged | Turbine tests |

### 16.2 Edge Case Tests

| Scenario | Test Approach |
|----------|--------------|
| Race condition (concurrent trigger) | Coroutine test: mock Bamboo returning `QUEUED` state, verify re-queue logic |
| Rapid enqueue + cancel | Coroutine test: enqueue then cancel before poll fires, verify no trigger attempt |
| Poll overlap prevention | Launch two concurrent poll coroutines, verify `AtomicBoolean` guard prevents double execution |
| Build stuck in QUEUED | Mock Bamboo returning QUEUED for >720s, verify timeout notification |
| Tag deleted between validate and trigger | Mock registry returning 200 on first call, 404 on pre-trigger re-validation, verify `TAG_INVALID` state |
| Bamboo 401 (expired PAT) | MockWebServer returning 401, verify all polling pauses and notification fires |
| Bamboo 429 (rate limit) | MockWebServer with `Retry-After` header, verify backoff behavior |
| SQLite corruption on startup | Corrupt DB file on disk, verify `quick_check` detects, recreates DB, notifies user |
| Payload snapshot immutability | Enqueue with tag A, change staging to tag B, verify queued entry still has tag A |
| Same suite queued twice | Enqueue two entries for same plan, verify FIFO ordering — only oldest triggers |
| IDE restart recovery | Write entries to SQLite, create fresh QueueService, verify reconciliation with mocked Bamboo |
| Credential change while queued | Change Bamboo URL, verify entries marked `STALE` |
| Docker Registry pagination | MockWebServer returning paginated responses with Link headers, verify all pages followed |
| Registry auth handshake | MockWebServer: first 401 with WWW-Authenticate, then token endpoint, then retry succeeds |

---

## 17. Build Configuration

### 17.1 New Module: `automation/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    id("org.jetbrains.intellij.platform.module")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map {
            it.split(',').map(String::trim).filter(String::isNotEmpty)
        })
    }

    implementation(project(":core"))
    implementation(project(":bamboo"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}

tasks.test { useJUnitPlatform() }
```

### 17.2 settings.gradle.kts Addition

```kotlin
include(":automation")
```

### 17.3 Root build.gradle.kts

Add `:automation` to the `intellijPlatformPluginComposedModule` list alongside existing modules.

---

## 18. Master Spec Divergences

| # | Master Spec | This Design | Justification |
|---|-------------|-------------|---------------|
| 1 | Features 43-54 all in `:automation` | Bamboo API extensions in `:bamboo`, events in `:core`, main features in `:automation` | Clean module boundaries — `:bamboo` owns Bamboo API, `:core` owns events |
| 2 | No persistence tier specified | App-level + Project-level split | Suite configs need cross-project persistence; queue state is project-local |
| 3 | "Nexus" mentioned for registry | Docker Registry v2 API directly | Nexus hosts Docker registries but the API is standard Docker Registry v2 — no Nexus-specific API needed |
| 4 | Single suite assumed | Multi-suite support (3-4 plans) | User confirmed they run 3 out of 4 suite plans — must support suite selection |
