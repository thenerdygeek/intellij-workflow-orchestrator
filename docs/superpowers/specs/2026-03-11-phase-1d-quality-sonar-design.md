# Phase 1D: Quality & Sonar — Design Specification

> **Date:** 2026-03-11
> **Status:** Approved
> **Depends on:** Phase 1A (complete), Phase 1C (EventBus — in progress)
> **Gate:** Gate 4 — "Coverage visible in editor"

---

## 1. Overview

Add SonarQube integration to the IDE via a `:sonar` module, eliminating the need to switch to SonarQube's web UI for checking quality gate status, viewing issues, or inspecting coverage. Provides six UI surfaces: Quality dashboard tab, gutter coverage markers, inline issue annotations, editor notification banner, file tree coverage badges, and quality gate notifications.

**Target user workflow:** Build finishes → plugin refreshes SonarQube data → Quality tab shows gate status + issues → editor shows coverage gutter markers and issue annotations → developer sees exactly which lines need attention without leaving the IDE.

---

## 2. Scope

### In Scope
- SonarQube API client + DTOs (6 endpoints)
- SonarDataService (hybrid refresh: EventBus + configurable poll interval)
- CoverageMapper (SonarQube component paths → local file paths)
- IssueMapper (SonarQube issues → editor positions)
- ProjectKeyDetectionService (auto-detect via `/api/projects/search` + searchable fallback)
- Quality Dashboard tab (sub-tabbed: Overview, Issues, Coverage)
- Gutter coverage markers (`LineMarkerProvider`)
- Inline issue annotations (`ExternalAnnotator`, 3-phase async)
- Editor notification banner (`EditorNotificationProvider`)
- File tree coverage badges (`ProjectViewNodeDecorator`)
- Quality gate change notifications
- WorkflowEvent extensions (QualityGateResult, CoverageUpdated)

### Out of Scope (Deferred)
- Pre-push health check gate → Phase 1F
- "Fix with Cody" / "Ask Cody to fix" actions → Phase 1E
- Coverage threshold configurability → later if requested
- Per-condition branch coverage detail (which specific branch is uncovered) → later if requested
- Historical coverage trends → later if requested

---

## 3. Architecture

### Module Dependency
```
:sonar ──→ :core (EventBus, HttpClientFactory, PluginSettings, Notifications, etc.)
```

No sibling module dependencies. Cross-module communication via EventBus only.

### Build Configuration Changes

**`settings.gradle.kts`** — add `:sonar` module:
```kotlin
include(":core", ":jira", ":git-integration", ":bamboo", ":sonar")
```

**`build.gradle.kts` (root)** — add `:sonar` composition:
```kotlin
implementation(project(":sonar"))
```

**`sonar/build.gradle.kts`** — new submodule:
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

### Data Flow
```
BuildFinished event (from :bamboo via EventBus)
        │
        ▼
SonarDataService ──poll (configurable)──→ SonarApiClient ──→ SonarQube REST API
        │
        ▼
StateFlow<SonarState>  ──→  QualityDashboardPanel (Quality tab)
        │                    CoverageLineMarkerProvider (gutter)
        │                    SonarIssueAnnotator (inline annotations)
        │                    CoverageBannerProvider (editor banner)
        │                    CoverageTreeDecorator (file tree badges)
        │
        ▼
EventBus.emit(QualityGateResult) ──→ Notifications
```

All UI components consume the single `StateFlow<SonarState>` reactively. No UI component makes API calls directly — `SonarDataService` is the single source of truth.

---

## 4. SonarQube API Layer

### DTOs (`sonar/api/dto/`)
`@Serializable` data classes matching SonarQube Web API responses:

| DTO | Fields | Purpose |
|-----|--------|---------:|
| `SonarProjectDto` | key, name, qualifier | Project listing/search |
| `SonarQualityGateDto` | status (OK/ERROR), conditions (list of `ConditionDto`) | Quality gate status |
| `ConditionDto` | metric, comparator, errorThreshold, actualValue, status | Individual gate condition |
| `SonarIssueDto` | key, rule, severity, message, component, textRange, type, effort | Issue with position |
| `TextRangeDto` | startLine, endLine, startOffset, endOffset | Issue location in file |
| `SonarMeasureDto` | component, metric, value | Coverage metric per file |
| `SonarComponentDto` | key, name, qualifier, path | File component |
| `SourceLineDto` | line, code, lineHits, conditions, coveredConditions | Per-line coverage data |
| `SonarPagingDto` | pageIndex, pageSize, total | Pagination wrapper |

### SonarApiClient
Non-service class (direct instantiation with `baseUrl` + `tokenProvider`), matching `JiraApiClient`/`BambooApiClient` pattern. All methods return `ApiResult<T>`.

Constructs its own `OkHttpClient` internally with `AuthInterceptor` (Bearer) and `RetryInterceptor`, following the same direct-construction pattern used by `JiraApiClient` and `BambooApiClient`. `SonarDataService` instantiates `SonarApiClient` using credentials from `PluginSettings` and `CredentialStore`.

| Method | Endpoint | Returns | Purpose |
|--------|----------|---------|---------|
| `validateConnection()` | `GET /api/authentication/validate` | `ApiResult<Boolean>` | Test connection |
| `searchProjects(query)` | `GET /api/projects/search?q={query}` | `ApiResult<List<SonarProjectDto>>` | Auto-detect / searchable dropdown |
| `getQualityGateStatus(projectKey)` | `GET /api/qualitygates/project_status?projectKey={key}` | `ApiResult<SonarQualityGateDto>` | Quality gate pass/fail + conditions |
| `getIssues(projectKey, branch?, filePath?)` | `GET /api/issues/search?componentKeys={key}&resolved=false&ps=500` | `ApiResult<List<SonarIssueDto>>` | Open issues with positions |
| `getMeasures(projectKey, metrics)` | `GET /api/measures/component_tree?component={key}&metricKeys=...&qualifiers=FIL&ps=500` | `ApiResult<List<SonarMeasureDto>>` | Per-file aggregate coverage data |
| `getSourceLines(componentKey, from?, to?)` | `GET /api/sources/lines?key={componentKey}&from={from}&to={to}` | `ApiResult<List<SourceLineDto>>` | Per-line coverage data (line hits, conditions, covered conditions) |

**Per-line coverage:** The `/api/measures/component_tree` endpoint returns aggregate metrics per file (line_coverage %, branch_coverage %). For gutter markers (`CoverageLineMarkerProvider`) which need per-line COVERED/UNCOVERED/PARTIAL status, the `/api/sources/lines` endpoint is required — it returns `lineHits`, `conditions`, and `coveredConditions` per line. `SonarDataService` calls `getSourceLines()` lazily: only when a file is opened in the editor, not for all files upfront. This keeps API usage bounded.

**Additional DTO for per-line data:**
- `SourceLineDto` — line (number), code, scmAuthor, scmDate, lineHits (Int?, null = not executable), conditions (Int?), coveredConditions (Int?)

**Pagination:** SonarQube APIs default to `ps=100` (page size). For `getIssues` and `getMeasures`, request `ps=500` to reduce round-trips. If `total > 500`, paginate. Most projects will fit in a single page.

**Branch parameter:** SonarQube branch analysis uses `&branch={branchName}` query parameter. Omit for default branch analysis. `SonarDataService` passes the current Git branch name.

---

## 5. Services

### SonarDataService
Project-level service. Core orchestrator for Phase 1D.

- Launches polling coroutine on `Dispatchers.IO` with `SupervisorJob`
- **Hybrid refresh strategy:**
  - Subscribes to `EventBus` for `BuildFinished` events → triggers immediate refresh (debounced 5s to let SonarQube analysis complete)
  - Safety-net poll at interval from `PluginSettings.sonarPollIntervalSeconds` (default 60s) for external analysis runs
- Tracks current Git branch via `GitRepositoryManager`, queries matching SonarQube branch
- Exposes `StateFlow<SonarState>` for reactive UI updates
- On quality gate status change: emits `QualityGateResult` event, sends notification via `WorkflowNotificationService`
- Starts on project open if `sonarUrl` is configured, stops on project close
- Subscribes to `BranchChangeListener` via `project.messageBus.connect(this).subscribe(BranchChangeListener.VCS_BRANCH_CHANGED, ...)` to re-query when branch changes (same mechanism as `BuildMonitorService` in Phase 1C)
- Implements `Disposable` — cancels `CoroutineScope` in `dispose()` for clean project-close lifecycle

```kotlin
data class SonarState(
    val projectKey: String,
    val branch: String,
    val qualityGate: QualityGateState,
    val issues: List<MappedIssue>,
    val fileCoverage: Map<String, FileCoverageData>,
    val overallCoverage: CoverageMetrics,
    val lastUpdated: Instant
)

data class QualityGateState(
    val status: QualityGateStatus,  // PASSED, FAILED, NONE
    val conditions: List<GateCondition>
)

data class GateCondition(
    val metric: String,
    val comparator: String,
    val threshold: String,
    val actualValue: String,
    val passed: Boolean
)

enum class QualityGateStatus { PASSED, FAILED, NONE }

data class MappedIssue(
    val key: String,
    val type: IssueType,        // BUG, VULNERABILITY, CODE_SMELL, SECURITY_HOTSPOT
    val severity: IssueSeverity, // BLOCKER, CRITICAL, MAJOR, MINOR, INFO
    val message: String,
    val rule: String,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val startOffset: Int,
    val endOffset: Int,
    val effort: String?
)

enum class IssueType { BUG, VULNERABILITY, CODE_SMELL, SECURITY_HOTSPOT }
enum class IssueSeverity { BLOCKER, CRITICAL, MAJOR, MINOR, INFO }

data class FileCoverageData(
    val filePath: String,
    val lineCoverage: Double,
    val branchCoverage: Double,
    val uncoveredLines: Int,
    val uncoveredConditions: Int,
    val lineStatuses: Map<Int, LineCoverageStatus>  // line number → status
)

enum class LineCoverageStatus { COVERED, UNCOVERED, PARTIAL }

data class CoverageMetrics(
    val lineCoverage: Double,
    val branchCoverage: Double
)
```

### CoverageMapper
Pure function (no service registration). Takes SonarQube measure response, returns local file coverage map.

- SonarQube returns component keys like `com.myapp:core:src/main/kotlin/com/myapp/UserService.kt`
- Strips the project key prefix, resolves remaining path against project base directory
- Handles multi-module projects: matches module prefix against Gradle subproject paths
- Returns `Map<String, FileCoverageData>` keyed by relative file path

### IssueMapper
Pure function. Takes SonarQube issue DTOs, returns mapped issues with local file paths.

- Resolves `component` → relative file path (same stripping logic as CoverageMapper)
- Maps `textRange` (1-based line, 0-based offset) to `MappedIssue`
- Filters out issues for files not present locally (deleted files, generated code)
- Groups by file for efficient lookup

### ProjectKeyDetectionService
Handles auto-detect + search for SonarQube project key.

- `autoDetect()`: extracts repo name from Git remote URL → calls `searchProjects(repoName)` → if exactly one match, pre-fill; multiple → show dropdown
- `search(query)`: delegates to `SonarApiClient.searchProjects()`
- Single API call — SonarQube's `/api/projects/search` does server-side fuzzy matching
- Caches detected key in `PluginSettings.State.sonarProjectKey`
- Runs once on first configuration, result is persisted
- User can always override in Settings

---

## 6. UI Components

### QualityDashboardPanel (Quality Tab)
Implements `WorkflowTabProvider` via `QualityTabProvider` for the "Quality" tab. Sub-tabbed layout using `JBTabbedPane`.

```
┌─ Quality Tab ──────────────────────────────────────────────┐
│ my-app  [PASSED]  Coverage: 87.3%  Issues: 12  [↻ Refresh] │
│┌──────────┬──────────┬──────────┐                           │
││ Overview  │ Issues   │ Coverage │                           │
│├──────────┴──────────┴──────────┤                           │
││                                                            │
││ ┌──────────┐ ┌──────────┐ ┌──────────┐                    │
││ │ QUALITY  │ │ COVERAGE │ │  ISSUES  │                    │
││ │  GATE    │ │          │ │          │                    │
││ │ ✓ PASSED │ │  87.3%   │ │   12     │                    │
││ │ 3 conds  │ │ Br: 72%  │ │ 2B 1V 8S │                    │
││ └──────────┘ └──────────┘ └──────────┘                    │
││                                                            │
││ RECENT ISSUES                                              │
││ ● BUG CRITICAL NullPointer — UserService.kt:42            │
││ ● BUG CRITICAL SQL injection — QueryBuilder.kt:18         │
││ ● VULN MAJOR Weak crypto — AuthUtil.kt:67                 │
│└────────────────────────────────────────────────────────────│
└──────────────────────────────────────────────────────────────┘
```

**Sub-tabs:**
- **Overview:** Summary cards (quality gate, coverage, issue counts) + recent critical issues
- **Issues:** Filterable/sortable issue list (by type, severity, file). Click navigates to file:line in editor.
- **Coverage:** Per-file coverage table (file path, line %, branch %, uncovered lines). Click opens file.

**Visual polish:**
- `AllIcons.General.InspectionsOK/Error` for quality gate status
- Severity icons: `AllIcons.General.Error` (blocker/critical), `AllIcons.General.Warning` (major), `AllIcons.General.Information` (minor/info)
- Coverage progress bars using `JBColor`-based painting
- `JBUI.Borders.empty()` for consistent padding
- Issue type color coding: red (BUG), orange (VULNERABILITY), yellow (CODE_SMELL), grey (HOTSPOT)
- Human-readable effort ("2h 30min")

### CoverageLineMarkerProvider
`LineMarkerProvider` implementation for gutter coverage markers.

- Registered for `language="JAVA"` and `language="kotlin"` (both languages in scope)
- Reads `SonarState.fileCoverage` for the current file
- Returns `LineMarkerInfo` with colored icon per line:
  - Green bar: COVERED
  - Yellow bar: PARTIAL (some branches uncovered)
  - Grey bar: UNCOVERED
- Tooltip on hover shows coverage detail ("2 of 3 branches covered")
- Uses `GutterIconRenderer.Alignment.LEFT`

### SonarIssueAnnotator
`ExternalAnnotator<SonarAnnotationInput, SonarAnnotationResult>` implementation. 3-phase async pattern:

1. **`collectInformation()`** (read action, EDT) — get file path, check if issues exist in `SonarState`
2. **`doAnnotate()`** (background thread) — look up `MappedIssue` list for this file from `SonarState`
3. **`apply()`** (EDT) — create `AnnotationHolder` entries with:
   - `HighlightSeverity.ERROR` for BUG/VULNERABILITY (BLOCKER/CRITICAL)
   - `HighlightSeverity.WARNING` for BUG/VULNERABILITY (MAJOR/MINOR)
   - `HighlightSeverity.WEAK_WARNING` for CODE_SMELL
   - Tooltip: `"[RULE_KEY] message"` with severity and effort
   - Text range from `MappedIssue.startLine/endLine/startOffset/endOffset`

### CoverageBannerProvider
`EditorNotificationProvider` implementation.

- Shows yellow banner at top of editor: "N uncovered branches in this file — Branch coverage: X%"
- Only shown when file has uncovered conditions > 0
- "View in Quality Tab" action link opens the Quality tab focused on this file
- Dismiss button hides for current editor session
- Re-appears on data refresh if conditions still uncovered

### CoverageTreeDecorator
`ProjectViewNodeDecorator` implementation.

- Appends coverage % badge next to file names in the Project tree
- Color-coded by threshold:
  - Green (≥80%): good coverage
  - Yellow (50-79%): needs attention
  - Red (<50%): critical
- Only decorates files that have coverage data (skips test files, resources, configs)
- Badge format: `87%` with colored background

---

## 7. Notifications

Uses existing `WorkflowNotificationService` with `GROUP_QUALITY`.

| Event | Type | Behavior |
|-------|------|----------|
| Quality gate passed | Info | Auto-dismiss after 5s, shows conditions summary |
| Quality gate failed | Error | Sticky, "View Details" action opens Quality tab |

Only fires on status transitions — tracks previous `QualityGateStatus` to avoid duplicate notifications on each poll/refresh cycle.

---

## 8. WorkflowEvent Extensions

New event variants in `WorkflowEvent` sealed class (`:core`):

```kotlin
// Phase 1D additions
data class QualityGateResult(val projectKey: String, val passed: Boolean) : WorkflowEvent()
data class CoverageUpdated(val projectKey: String, val lineCoverage: Double) : WorkflowEvent()
```

`QualityGateResult` is emitted by `SonarDataService` on gate status change.
`CoverageUpdated` is emitted on each successful data refresh for potential future consumers.

**Cross-module compilation note:** Adding new variants to the `WorkflowEvent` sealed class means any exhaustive `when` expressions in other modules (e.g., `:bamboo`) will need an `else` branch. Since Phase 1C's `BuildMonitorService` only subscribes via `filterIsInstance<BuildFinished>()`, this is not expected to be an issue — but verify during integration.

---

## 9. Settings Additions

New field in `PluginSettings.State` (`:core` code change):
- `sonarProjectKey: String` — auto-detected or user-configured, added via `by string("")`

Existing fields already defined in Phase 1A:
- `sonarUrl: String` — SonarQube base URL
- `sonarPollIntervalSeconds: Int` — poll interval (default 60s, configurable)
- `qualityModuleEnabled: Boolean` — feature toggle

Project key configuration in existing Connections settings page (alongside `sonarUrl`):
- Searchable dropdown backed by `ProjectKeyDetectionService.search()`
- Auto-detect button that runs `ProjectKeyDetectionService.autoDetect()`
- Manual text field for direct entry

---

## 10. plugin.xml Additions

All extensions registered in `core/src/main/resources/META-INF/plugin.xml`:

For `:sonar` module:
- `projectService` for `SonarDataService`, `ProjectKeyDetectionService`
- `codeInsight.lineMarkerProvider` for `CoverageLineMarkerProvider` (language="JAVA" and language="kotlin")
- `externalAnnotator` for `SonarIssueAnnotator` (language="JAVA" and language="kotlin")
- `editorNotificationProvider` for `CoverageBannerProvider`
- `projectViewNodeDecorator` for `CoverageTreeDecorator`
- `WorkflowTabProvider` extension → `QualityTabProvider`

For `:core`:
- `WorkflowEvent` sealed class additions (code change, no XML)

---

## 11. Testing Strategy

| Layer | What | Tools |
|-------|------|-------|
| DTOs | Serialize/deserialize from fixture JSON | JUnit 5 + kotlinx.serialization |
| SonarApiClient | All 6 endpoints with mock responses | MockWebServer |
| SonarDataService | Hybrid refresh, state transitions, event emission, branch change handling | coroutines-test + MockK + Turbine |
| CoverageMapper | Component path → local path mapping, multi-module, edge cases | JUnit 5 (pure function) |
| IssueMapper | Issue → MappedIssue, file resolution, filtering missing files | JUnit 5 (pure function) |
| ProjectKeyDetectionService | Auto-detect matching, Git remote extraction, zero/multiple matches | JUnit 5 + MockK |
| CoverageLineMarkerProvider | Line status → marker icon mapping, tooltip generation | JUnit 5 (logic tests, no UI rendering) |
| SonarIssueAnnotator | Severity → HighlightSeverity mapping, text range construction | JUnit 5 (logic tests, no UI rendering) |

**Integration test:** Happy-path scenario: `BuildFinished` event → SonarDataService refreshes → SonarState updates → `QualityGateResult` event emitted → notification sent. Uses coroutines-test `advanceTimeBy()` for poll simulation.

Test fixtures: Realistic SonarQube API responses, stored in `sonar/src/test/resources/fixtures/`.

---

## 12. File Structure

```
core/src/main/kotlin/com/workflow/orchestrator/core/
└── events/
    └── WorkflowEvent.kt  (add QualityGateResult, CoverageUpdated)

sonar/
├── build.gradle.kts
├── src/main/kotlin/com/workflow/orchestrator/sonar/
│   ├── api/
│   │   ├── dto/SonarDtos.kt
│   │   └── SonarApiClient.kt
│   ├── service/
│   │   ├── SonarDataService.kt
│   │   ├── CoverageMapper.kt
│   │   ├── IssueMapper.kt
│   │   └── ProjectKeyDetectionService.kt
│   ├── ui/
│   │   ├── QualityTabProvider.kt
│   │   ├── QualityDashboardPanel.kt
│   │   ├── OverviewPanel.kt
│   │   ├── IssueListPanel.kt
│   │   ├── CoverageTablePanel.kt
│   │   ├── CoverageLineMarkerProvider.kt
│   │   ├── SonarIssueAnnotator.kt
│   │   ├── CoverageBannerProvider.kt
│   │   └── CoverageTreeDecorator.kt
│   └── model/
│       ├── SonarState.kt
│       └── SonarModels.kt
└── src/test/
    ├── kotlin/com/workflow/orchestrator/sonar/
    │   ├── api/
    │   │   ├── SonarApiClientTest.kt
    │   │   └── dto/SonarDtoSerializationTest.kt
    │   ├── service/
    │   │   ├── SonarDataServiceTest.kt
    │   │   ├── CoverageMapperTest.kt
    │   │   ├── IssueMapperTest.kt
    │   │   └── ProjectKeyDetectionServiceTest.kt
    │   └── ui/
    │       ├── CoverageLineMarkerLogicTest.kt
    │       └── SonarIssueAnnotatorLogicTest.kt
    └── resources/fixtures/
        ├── auth-validate.json
        ├── projects-search.json
        ├── qualitygate-status-passed.json
        ├── qualitygate-status-failed.json
        ├── issues-search.json
        ├── measures-component-tree.json
        └── source-lines.json
```
