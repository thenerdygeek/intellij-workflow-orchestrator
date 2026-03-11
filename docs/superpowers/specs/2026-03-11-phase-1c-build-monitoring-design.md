# Phase 1C: Build Monitoring — Design Specification

> **Date:** 2026-03-11
> **Status:** Approved
> **Depends on:** Phase 1A (complete), Phase 1B (in progress)
> **Gate:** Gate 3 — "CI feedback in IDE"

---

## 1. Overview

Add build monitoring to the IDE via a `:bamboo` module, eliminating the need to switch to Bamboo's web UI for checking build status, viewing logs, or triggering manual stages. Also introduces the event bus infrastructure in `:core` for cross-module communication.

**Target user workflow:** Developer pushes code → Bamboo builds automatically → plugin polls and shows stage-by-stage status in IDE → developer sees failures instantly with log details → developer triggers manual stages (with build variables) from IDE.

---

## 2. Scope

### In Scope
- EventBus in `:core` (SharedFlow-based, sealed event hierarchy)
- Bamboo REST API client + DTOs
- BuildMonitorService (30s polling, branch-aware)
- BuildLogParser (Maven error extraction from logs)
- Build Dashboard tab (master-detail: stage list + log/error detail)
- Manual stage triggering with dynamic variable form
- Plan auto-detection from Git remote + searchable fallback
- Build status bar widget
- Build notifications (success/failure on terminal state transitions)

### Out of Scope (Deferred)
- CveRemediationService → Phase 1F (Pre-Push & Health Check)
- PushListener (auto-trigger) → not needed, Bamboo handles this
- Caching layers L2/L3 → deferred until needed
- Notification level configurability → later if requested

---

## 3. Architecture

### Module Dependency
```
:bamboo ──→ :core (EventBus, HttpClientFactory, CredentialStore, PluginSettings, etc.)
```

No sibling module dependencies. Cross-module communication via EventBus only.

### Build Configuration Changes

**`settings.gradle.kts`** — add `:bamboo` module:
```kotlin
include(":core", ":jira", ":git-integration", ":bamboo")
```

**`build.gradle.kts` (root)** — add `:bamboo` composition:
```kotlin
implementation(project(":bamboo"))
```

**`bamboo/build.gradle.kts`** — new submodule:
```kotlin
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("Git4Idea")
    }
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

tasks.test { useJUnitPlatform() }
```

**`gradle/libs.versions.toml`** — add Turbine dependency:
```toml
[versions]
turbine = "1.1.0"

[libraries]
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
```

### Bamboo CI Model
- Single Bamboo plan per project (configured via plan key, e.g., `PROJ-BUILD`)
- Each plan has branch builds matching Git branches
- Stages within a plan: some run automatically, some are manual with build variables (checkboxes, key-value pairs)

### UI Design Note
The master design spec describes "3 parallel lanes (Artifact, OSS, Sonar)" for the Build tab. This assumed multiple Bamboo plans per project. In practice, the CI uses a single plan with multiple stages. Phase 1C uses a **stage-based master-detail layout** instead — this is more general, handles any number of stages, and accurately represents the actual Bamboo data model. The master spec should be updated to reflect this.

---

## 4. Core Infrastructure: EventBus

New files in `:core/events/`:

### WorkflowEvent.kt
Sealed class hierarchy for type-safe cross-module events:

```kotlin
sealed class WorkflowEvent {
    // From :bamboo (Phase 1C)
    data class BuildFinished(val planKey: String, val status: BuildStatus) : WorkflowEvent()
    data class BuildLogReady(val planKey: String, val log: String) : WorkflowEvent()
    // Future phases add their own subclasses here:
    // Phase 1D: QualityGateResult, CoverageUpdated (from :sonar)
    // Phase 2A: AutomationTriggered, AutomationFinished, QueuePositionChanged (from :automation)
    // Phase 1B retrofit: WorkStarted, TaskCompleted (from :jira)
}
```

Note: Only the bamboo event variants are implemented in Phase 1C. The sealed class lives in `:core` so all modules can reference the type hierarchy. The `EventSubscriber.kt` DSL from the master spec is deferred — raw `SharedFlow` collection is sufficient for Phase 1C's needs.

### EventBus.kt
Project-level service wrapping `MutableSharedFlow`:

```kotlin
@Service(Service.Level.PROJECT)
class EventBus {
    private val _events = MutableSharedFlow<WorkflowEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<WorkflowEvent> = _events.asSharedFlow()
    suspend fun emit(event: WorkflowEvent) { _events.emit(event) }
}
```

Key decisions:
- `replay = 0` — events are ephemeral notifications, not state
- `extraBufferCapacity = 64` — emitters don't suspend waiting for slow collectors
- Project-scoped — automatically disposed on project close

---

## 5. Bamboo API Layer

### DTOs (`bamboo/api/dto/`)
`@Serializable` data classes matching Bamboo REST API responses:

| DTO | Fields | Purpose |
|-----|--------|---------|
| `BambooPlanDto` | key, name, shortName, enabled | Plan listing/search |
| `BambooBranchDto` | key, name, enabled | Branch listing |
| `BambooResultDto` | buildNumber, state, lifecycleState, stages | Build result with stages |
| `BambooStageDto` | name, state, manual, durationInSeconds | Individual stage status |
| `BambooPlanVariableDto` | name, value | Variables for manual run form |
| `BambooSearchResultDto` | searchResults wrapping plans | Fuzzy search response |

### BambooApiClient
Project-level service with suspend functions returning `ApiResult<T>`:

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `getPlans()` | `GET /rest/api/latest/plan` | List all plans |
| `searchPlans(query)` | `GET /rest/api/latest/search/plans` | Fuzzy search for plan dropdown |
| `getPlanSpecs(planKey)` | `GET /rest/api/latest/plan/{key}/specs` | Auto-detect via repo URL match |
| `getBranches(planKey)` | `GET /rest/api/latest/plan/{key}/branch` | List branches for a plan |
| `getLatestResult(planKey, branch)` | `GET /rest/api/latest/result/{key}/branch/{branch}/latest` | Latest build with stages |
| `getBuildLog(resultKey)` | `GET /rest/api/latest/result/{key}/log` | Full build log text |
| `getVariables(planKey)` | `GET /rest/api/latest/plan/{key}/variable` | Variables for manual run form |
| `triggerBuild(planKey, variables)` | `POST /rest/api/latest/queue/{key}` | Trigger build with variables |

Uses `HttpClientFactory.createClient(ServiceType.BAMBOO, ...)` for OkHttp client with existing `AuthInterceptor` (Bearer) and `RetryInterceptor`. This is the go-forward pattern for API clients; the existing `JiraApiClient` uses direct construction but should be aligned in a future cleanup.

**Manual stage triggering:** The `POST /rest/api/latest/queue/{key}` endpoint triggers the full plan. For triggering a specific manual stage, Bamboo uses `POST /rest/api/latest/queue/{planKey}?stage={stageName}&executeAllStages=false`. The `triggerBuild` method accepts an optional `stageName` parameter for this.

---

## 6. Services

### BuildMonitorService
Project-level service. Core of Phase 1C.

- Launches polling coroutine on `Dispatchers.IO` with `SupervisorJob`
- Polls `getLatestResult()` every 30s (configurable via `PluginSettings.buildPollIntervalSeconds`)
- Tracks current Git branch via `GitRepositoryManager`, polls matching Bamboo branch
- On status change: emits `BuildFinished` event, sends notification
- Exposes `StateFlow<BuildState>` for reactive UI updates
- Starts on project open if `bambooUrl` is configured, stops on project close
- Subscribes to `BranchChangeListener` topic via `project.messageBus.connect().subscribe(BranchChangeListener.VCS_BRANCH_CHANGED, ...)` to switch polling when branch changes
- Implements `Disposable` — cancels `CoroutineScope` in `dispose()` for clean project-close lifecycle

```kotlin
data class BuildState(
    val planKey: String,
    val branch: String,
    val stages: List<StageState>,
    val overallStatus: BuildStatus,
    val lastUpdated: Instant
)

data class StageState(
    val name: String,
    val status: BuildStatus,  // SUCCESS, FAILED, IN_PROGRESS, PENDING
    val manual: Boolean,
    val durationMs: Long?
)

enum class BuildStatus { SUCCESS, FAILED, IN_PROGRESS, PENDING, UNKNOWN }
```

### BuildLogParser
Pure function (no service registration). Takes raw log text, returns structured errors.

- Extracts Maven `[ERROR]` lines with file path + line number
- Groups consecutive error lines into logical blocks
- Returns `List<BuildError>` with severity, message, file location

```kotlin
data class BuildError(
    val severity: ErrorSeverity,  // ERROR, WARNING
    val message: String,
    val filePath: String?,
    val lineNumber: Int?
)
```

### PlanDetectionService
Handles auto-detect + search flow for plan key configuration.

- `autoDetect()`: fetches plans → checks specs YAML for repo URL match against Git remote
- `search(query)`: delegates to `BambooApiClient.searchPlans()`
- Caches detected plan key in `PluginSettings.State.bambooPlanKey`
- Runs once on first configuration, result is persisted
- User can always override in Settings

**Auto-detection strategy:**
1. Get all plans via `getPlans()` (paginated — fetch up to 100 plans max to bound API calls)
2. For each plan, fetch specs via `getPlanSpecs()` and extract repo URL using regex (`url:\s+(.+)`) — avoids needing a YAML parser dependency
3. Compare against current Git remote URL (normalized: strip `.git` suffix, SSH↔HTTPS equivalence)
4. If exactly one match → pre-fill. Multiple matches or zero → fall back to searchable dropdown.

**Performance note:** Auto-detection makes N+1 API calls (1 for plan list + N for specs). Capped at 100 plans. Runs once on first configuration, result is persisted. For large Bamboo instances, the searchable dropdown (`/search/plans`) is the primary fallback — it's a single API call with server-side fuzzy matching.

---

## 7. UI Components

### BuildDashboardPanel (Build Tab)
Implements `WorkflowTabProvider` for the "Build" tab. Master-detail layout with `JBSplitter`.

```
┌─ Build Tab ──────────────────────────────────────────┐
│ Plan: PROJ-BUILD / feature/PROJ-123  [↻ Refresh]     │
│┌───────────────────┬────────────────────────────────┐│
││ Stages            │ Stage Detail                    ││
││                   │                                 ││
││ ✓ Compile    2m34s│ [Log] [Errors]                  ││
││ ✓ Unit Test  5m12s│                                 ││
││ ⟳ OSS Scan   --   │ > mvn clean install...          ││
││ ○ Deploy  [Run]   │ > [ERROR] Failed to execute...  ││
││ ○ Sonar   [Run]   │ > ...                           ││
│└───────────────────┴────────────────────────────────┘│
└──────────────────────────────────────────────────────┘
```

**Visual polish:**
- `AllIcons.RunConfigurations.TestPassed/TestFailed` for status icons
- `AsyncProcessIcon` (animated spinner) for in-progress stages
- `JBColor`-based row highlighting (subtle tint for success/failure)
- `JBUI.Borders.empty()` for consistent padding
- `EditorTextField` with monospace font for log viewer
- Error lines highlighted in red foreground
- Human-readable durations ("2m 34s")

### ManualStageDialog
`DialogWrapper` shown when clicking "Run" on a manual stage.

- Fetches variables via `getVariables()`
- Renders dynamic form: `JBTextField` for string vars, `JBCheckBox` for booleans
- Pre-fills with default values
- On OK: calls `triggerBuild()` with the variable map

### BuildStatusBarWidget
`StatusBarWidgetFactory` implementation.

- Text format: `PROJ-BUILD: ✓` or `PROJ-BUILD: ✗` or `PROJ-BUILD: ⟳`
- Click opens popup with stage summary (name + status per row)
- "Open Build Tab" link at bottom
- Updates reactively from `BuildMonitorService.stateFlow`

---

## 8. Notifications

Uses existing `WorkflowNotificationService` with `GROUP_BUILD`.

| Event | Type | Behavior |
|-------|------|----------|
| Build succeeded | Info | Auto-dismiss after 5s, shows duration |
| Build failed | Error | Sticky, "View Details" action opens Build tab at failed stage |

Only fires on status transitions — tracks previous status to avoid duplicate notifications on each poll cycle.

---

## 9. Settings Additions

New field in `PluginSettings.State`:
- `bambooPlanKey: String` — auto-detected or user-configured

Plan key configuration in existing Connections settings page (alongside `bambooUrl`):
- Searchable dropdown backed by `PlanDetectionService.search()`
- Auto-detect button that runs `PlanDetectionService.autoDetect()`
- Manual text field for direct entry

---

## 10. plugin.xml Additions

All extensions are registered in the **core `plugin.xml`** (`core/src/main/resources/META-INF/plugin.xml`), matching the established pattern from Phase 1A/1B where all service and extension registrations live in the single plugin descriptor.

For the `:bamboo` module:
- `projectService` for `BambooApiClient`, `BuildMonitorService`, `PlanDetectionService`
- `statusBarWidgetFactory` for `BuildStatusBarWidget`
- `WorkflowTabProvider` extension point implementation → `BuildTabProvider`

For `:core`:
- `projectService` for `EventBus`

---

## 11. Testing Strategy

| Layer | What | Tools |
|-------|------|-------|
| DTOs | Serialize/deserialize from fixture JSON | JUnit 5 + kotlinx.serialization |
| BambooApiClient | All 8 endpoints with mock responses | MockWebServer |
| BuildMonitorService | Polling, state transitions, event emission | coroutines-test + MockK |
| BuildLogParser | Error extraction from real log samples | JUnit 5 (pure function) |
| PlanDetectionService | Auto-detect matching, URL normalization | JUnit 5 + MockK |
| EventBus | Emit/subscribe, multiple collectors, buffering | coroutines-test + Turbine |

**Integration test:** Happy-path scenario covering the full flow: poll returns FAILED → `BuildState` updates → `BuildFinished` event emitted → notification sent. Uses coroutines-test `advanceTimeBy()` to simulate polling cycles.

Test fixtures: Real Bamboo API responses captured from actual instances (secrets/PII scrubbed), stored in `bamboo/src/test/resources/fixtures/`.

---

## 12. File Structure

```
core/src/main/kotlin/com/workflow/orchestrator/core/
└── events/
    ├── EventBus.kt
    └── WorkflowEvent.kt

bamboo/
├── build.gradle.kts
├── src/main/kotlin/com/workflow/orchestrator/bamboo/
│   ├── api/
│   │   ├── dto/BambooDtos.kt
│   │   └── BambooApiClient.kt
│   ├── service/
│   │   ├── BuildMonitorService.kt
│   │   ├── BuildLogParser.kt
│   │   └── PlanDetectionService.kt
│   ├── ui/
│   │   ├── BuildTabProvider.kt
│   │   ├── BuildDashboardPanel.kt
│   │   ├── StageListPanel.kt
│   │   ├── StageDetailPanel.kt
│   │   ├── ManualStageDialog.kt
│   │   └── BuildStatusBarWidget.kt
│   └── model/
│       ├── BuildState.kt
│       └── BuildError.kt
└── src/test/
    ├── kotlin/com/workflow/orchestrator/bamboo/
    │   ├── api/BambooApiClientTest.kt
    │   ├── service/
    │   │   ├── BuildMonitorServiceTest.kt
    │   │   ├── BuildLogParserTest.kt
    │   │   └── PlanDetectionServiceTest.kt
    │   └── EventBusTest.kt (in core tests)
    └── resources/fixtures/
        ├── plan-list.json
        ├── build-result.json
        ├── build-log.txt
        ├── plan-variables.json
        └── search-results.json
```
