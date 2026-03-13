# IntelliJ Native Integrations: Design Specification

> **Date:** 2026-03-13
> **Status:** Approved
> **Principle:** Surface workflow data where developers already look — don't force them into a custom tool window for everything.

---

## 1. Overview

Extend the Workflow Orchestrator plugin to surface its data through IntelliJ Platform's native UI extension points. Instead of requiring developers to open the "Workflow" tool window for every piece of information, integrate directly into Git Log, Search Everywhere, Editor Tabs, Commit Dialog, Run Configurations, Project Tree, and more.

**14 integration points across 3 tiers:**

| Tier | Integration | Extension Point | Module |
|------|------------|----------------|--------|
| 1 | Jira Task/Issue Tracker | `tasks.repositoryType` | `:jira` |
| 1 | VCS Log Ticket Column | `vcsLogCustomColumn` | `:jira` |
| 1 | Editor Tab Ticket Badge | `editorTabTitleProvider` | `:jira` |
| 1 | Search Everywhere (Jira) | `searchEverywhereContributor` | `:jira` |
| 1 | Commit Dialog Time Tracking | `checkinHandlerFactory` | `:jira` |
| 2 | Surefire Test Runner UI | `SMTRunnerConsoleView` | `:core` |
| 2 | Global Inspection (Sonar) | `globalInspection` | `:sonar` |
| 2 | Coverage Engine Bridge | `coverageRunner` + `coverageEngine` | `:sonar` |
| 2 | Run Configuration (Bamboo) | `configurationType` | `:bamboo` |
| 2 | TODO Pattern (Ticket Refs) | `todoIndexPattern` | `:jira` |
| 3 | Diff Gutter Coverage | `diff.DiffExtension` | `:sonar` |
| 3 | Project View Build Status | `projectViewNodeDecorator` | `:bamboo` |
| 3 | Post-Commit Jira Transition | `checkinHandlerFactory` | `:jira` |
| 3 | Before Run Tag Validation | `stepsBeforeRunProvider` | `:automation` |

---

## 2. Tier 1 — High Impact Integrations

### 2.1 Jira Task/Issue Tracker Integration

**Extension point:** `tasks.repositoryType`

**What it does:** Integrates Jira tickets into IntelliJ's native Tasks system (Tools > Tasks > Open Task). This enables native branch creation, context switching (open files, breakpoints), changelist association, and commit message prefixing — all through IntelliJ's built-in UI.

**Architecture:**

```
JiraTaskRepositoryType (TaskRepositoryType)
  └─ JiraTaskRepository (TaskRepository)
       ├─ findTask(id: String): Task?           → GET /rest/api/2/issue/{id}
       ├─ getIssues(query, offset, limit): Task[] → GET /rest/api/2/search?jql=...
       ├─ createCancellableConnection(): CancellableConnection
       └─ getUrl(): String
  └─ JiraTask (Task)
       ├─ getId() → ticket key (PROJ-123)
       ├─ getSummary() → issue summary
       ├─ getType() → TaskType (BUG, FEATURE, OTHER)
       ├─ getState() → TaskState (OPEN, IN_PROGRESS, RESOLVED)
       ├─ getIcon() → issue type icon
       └─ getIssueUrl() → link to Jira web UI
```

**Key behavior:**
- Reuses existing `JiraApiClient` for all HTTP calls (no duplicate client)
- Maps Jira issue types to `TaskType`: Bug→BUG, Story/Task→FEATURE, else→OTHER
- Maps Jira status categories to `TaskState`: new→OPEN, indeterminate→IN_PROGRESS, done→RESOLVED
- `createCancellableConnection` tests auth via `GET /rest/api/2/myself`
- Search uses JQL: `text ~ "{query}" AND assignee=currentUser() ORDER BY updated DESC`
- When user opens a task, syncs with our `ActiveTicketService` via EventBus

**Registration:**
```xml
<!-- plugin-withGit.xml (requires Git4Idea) -->
<tasks.repositoryType implementation="com.workflow.orchestrator.jira.tasks.JiraTaskRepositoryType"/>
```

**Dependency:** Requires `com.intellij.tasks` bundled plugin. Add `<depends optional="true" config-file="plugin-withTasks.xml">com.intellij.tasks</depends>`.

**Files:**
- `jira/src/main/kotlin/.../tasks/JiraTaskRepositoryType.kt`
- `jira/src/main/kotlin/.../tasks/JiraTaskRepository.kt`
- `jira/src/main/kotlin/.../tasks/JiraTask.kt`
- `src/main/resources/META-INF/plugin-withTasks.xml`

---

### 2.2 VCS Log Ticket Column

**Extension point:** `vcsLogCustomColumn`

**What it does:** Adds a "Jira Ticket" column to the Git Log view that extracts ticket IDs from commit messages and shows the ticket summary + status inline.

**Architecture:**

```
JiraVcsLogColumn (VcsLogCustomColumn<String>)
  ├─ readRow(commit: VcsCommitMetadata): String?    → extract ticket ID from message
  ├─ getStubValue(commit): String?                  → same extraction (cached)
  └─ createTableCellRenderer(): TableCellRenderer   → colored status badge
```

**Key behavior:**
- Extracts ticket ID from commit message using regex: `([A-Z][A-Z0-9]+-\d+)` — first match
- Ticket details fetched lazily and cached in an LRU cache (max 500 entries, 10-minute TTL)
- Column renders: `PROJ-123 | Fix login bug` with status color (green=done, blue=in-progress, grey=open)
- Cache is project-scoped, cleared on settings change
- Async fetch: shows ticket ID immediately, then fills summary when API responds

**Registration:**
```xml
<vcsLogCustomColumn implementation="com.workflow.orchestrator.jira.vcs.JiraVcsLogColumn"/>
```

**Files:**
- `jira/src/main/kotlin/.../vcs/JiraVcsLogColumn.kt`
- `jira/src/main/kotlin/.../vcs/TicketCache.kt` (LRU cache with TTL)

---

### 2.3 Editor Tab Ticket Badge

**Extension point:** `editorTabTitleProvider`

**What it does:** When a file is being edited as part of active ticket work, shows the ticket ID in the editor tab: `UserService.kt [PROJ-123]`.

**Architecture:**

```
TicketEditorTabTitleProvider (EditorTabTitleProvider)
  └─ getEditorTabTitle(project, file): String?
       → if active ticket set AND file is in VCS changelist → "filename [TICKET-ID]"
       → else → null (use default title)
```

**Key behavior:**
- Reads `ActiveTicketService.activeTicketId` — if blank, returns null
- Checks if file is in the current VCS changelist (modified files only) via `ChangeListManager`
- Appends ticket ID suffix only to modified files (not all open tabs)
- Lightweight — no API calls, pure local state check

**Registration:**
```xml
<editorTabTitleProvider implementation="com.workflow.orchestrator.jira.editor.TicketEditorTabTitleProvider"/>
```

**Files:**
- `jira/src/main/kotlin/.../editor/TicketEditorTabTitleProvider.kt`

---

### 2.4 Search Everywhere (Jira Tickets)

**Extension point:** `searchEverywhereContributor`

**What it does:** Adds a "Jira" tab to the Search Everywhere dialog (Shift+Shift) that searches Jira tickets by key, summary, or description.

**Architecture:**

```
JiraSearchContributorFactory (SearchEverywhereContributorFactory)
  └─ JiraSearchContributor (WeightedSearchEverywhereContributor)
       ├─ getSearchProviderId(): String → "JiraWorkflowSearch"
       ├─ getGroupName(): String → "Jira Tickets"
       ├─ getSortWeight(): Int → 50
       ├─ fetchWeightedElements(pattern, progressIndicator, consumer)
       │    → JQL search: text ~ "{pattern}" AND assignee=currentUser()
       │    → consumer.process(element, weight)
       ├─ getDataForItem(element): JiraIssue
       └─ processSelectedItem(element, modifiers, searchText): Boolean
            → opens ticket in Sprint Dashboard detail panel
```

**Key behavior:**
- Minimum 3 characters before triggering search (avoids flooding)
- Debounced: 300ms delay after last keystroke
- Results show: `[PROJ-123] Fix login bug (In Progress)` with type icon
- Enter key on result: sets active ticket + opens in detail panel
- Ctrl+Enter: opens in browser
- Uses `ProgressIndicator.checkCanceled()` for cancellation support
- Max 20 results per search

**Registration:**
```xml
<searchEverywhereContributor
    implementation="com.workflow.orchestrator.jira.search.JiraSearchContributorFactory"/>
```

**Files:**
- `jira/src/main/kotlin/.../search/JiraSearchContributorFactory.kt`
- `jira/src/main/kotlin/.../search/JiraSearchContributor.kt`

---

### 2.5 Commit Dialog Time Tracking Panel

**Extension point:** `checkinHandlerFactory`

**What it does:** Adds a time tracking panel to the commit dialog where the developer can log work time against the active Jira ticket when committing.

**Architecture:**

```
TimeTrackingCheckinHandlerFactory (CheckinHandlerFactory)
  └─ TimeTrackingCheckinHandler (CheckinHandler)
       ├─ getBeforeCheckinConfigurationPanel(): RefreshableOnComponent
       │    → JPanel with: [x] Log time | [2h 30m] spinner | [comment] field
       ├─ beforeCheckin(): ReturnResult
       │    → if checkbox checked, validate time > 0
       └─ checkinSuccessful(changes, commitMessage)
            → POST /rest/api/2/issue/{key}/worklog with time + comment
```

**Key behavior:**
- Panel visible only when `activeTicketId` is set
- Time defaults to elapsed time since "Start Work" (from `startWorkTimestamp` in settings)
- Time spinner: increments by `worklogIncrementHours` (default 0.5h)
- Max time capped at `maxWorklogHours` (default 7h)
- Comment auto-populated with commit message first line
- Worklog POST is async (fires after commit succeeds, doesn't block commit)
- If worklog POST fails, shows warning notification (doesn't revert commit)

**Registration:**
```xml
<vcsCheckinHandlerFactory
    implementation="com.workflow.orchestrator.jira.vcs.TimeTrackingCheckinHandlerFactory"/>
```

**Files:**
- `jira/src/main/kotlin/.../vcs/TimeTrackingCheckinHandlerFactory.kt`
- `jira/src/main/kotlin/.../vcs/TimeTrackingCheckinHandler.kt`

---

## 3. Tier 2 — Medium Impact Integrations

### 3.1 Surefire Test Runner UI

**Extension point:** `programRunner` + `SMTRunnerConsoleView`

**What it does:** Displays Maven Surefire test results in IntelliJ's native test runner tree (green/red checkmarks, timing, failure details) instead of raw console output.

**Architecture:**

```
SurefireTestRunnerConsole
  ├─ After local Maven test build completes:
  │    → Parse TEST-*.xml files (already done by SurefireReportParser)
  │    → Convert to TeamCity service messages
  │    → Feed into SMTRunnerConsoleView
  └─ TeamCity message format:
       ##teamcity[testSuiteStarted name='com.example.FooTest']
       ##teamcity[testStarted name='testAdd']
       ##teamcity[testFinished name='testAdd' duration='100']
       ##teamcity[testFailed name='testFail' message='expected 5 but was 3' details='stack trace']
       ##teamcity[testSuiteFinished name='com.example.FooTest']
```

**Key behavior:**
- Triggered when `runLocalMavenBuild("clean test")` completes
- Converts `TestResultSummary` (from `SurefireReportParser`) to TeamCity service messages
- Displays in a dedicated "Workflow Tests" tool window tab (reuses existing process handler pattern)
- Shows red/green tree, timing per test, click-to-navigate to test source
- Failed test stack traces are clickable (file:line navigation)

**Files:**
- `core/src/main/kotlin/.../maven/SurefireTestConsole.kt`
- `core/src/main/kotlin/.../maven/TeamCityMessageConverter.kt`

---

### 3.2 Global Inspection Tool (SonarQube)

**Extension point:** `globalInspection`

**What it does:** Adds a "SonarQube Issues" inspection that appears in IntelliJ's Analyze > Inspect Code results alongside built-in inspections.

**Architecture:**

```
SonarGlobalInspectionTool (GlobalInspectionTool)
  ├─ getGroupDisplayName(): "Workflow Orchestrator"
  ├─ getDisplayName(): "SonarQube Issues"
  ├─ runInspection(scope, manager, context, resultHolder)
  │    → fetch issues from SonarQube for files in scope
  │    → for each issue: resultHolder.reportProblem(...)
  └─ Maps SonarQube severity to IntelliJ ProblemHighlightType:
       BLOCKER/CRITICAL → ERROR
       MAJOR → WARNING
       MINOR → WEAK_WARNING
       INFO → INFORMATION
```

**Key behavior:**
- Fetches SonarQube issues for the inspection scope (project, module, or file)
- Maps Sonar issue locations to PSI elements using file path + line number
- Each issue shows rule key, message, and severity in the Problems view
- Quick fix "View in SonarQube" opens issue in browser
- Results cached per analysis run (not re-fetched on every inspection)

**Registration:**
```xml
<globalInspection
    implementationClass="com.workflow.orchestrator.sonar.inspection.SonarGlobalInspectionTool"
    groupName="Workflow Orchestrator"
    displayName="SonarQube Issues"
    enabledByDefault="true"
    level="WARNING"/>
```

**Files:**
- `sonar/src/main/kotlin/.../inspection/SonarGlobalInspectionTool.kt`

---

### 3.3 Coverage Engine Bridge

**Extension point:** `coverageRunner` + `coverageEngine`

**What it does:** Bridges SonarQube coverage data into IntelliJ's native coverage view, so developers see green/red lines in the gutter via the standard coverage overlay (Run > Show Coverage Data).

**Architecture:**

```
SonarCoverageEngine (CoverageEngine)
  └─ SonarCoverageRunner (CoverageRunner)
       ├─ getId(): "SonarQubeCoverage"
       ├─ getPresentableName(): "SonarQube"
       ├─ createCoverageSuite(...)
       │    → SonarCoverageSuite wrapping coverage data from SonarDataService
       └─ loadCoverageData(suite): ProjectData
            → converts SonarQube line coverage map to IntelliJ ProjectData/ClassData/LineData
```

**Key behavior:**
- No actual test execution — reads coverage from SonarQube API (`/api/measures/component_tree`)
- Converts `Map<filePath, Set<coveredLines>>` to IntelliJ's `ProjectData` model
- Lines show as covered (green) / uncovered (red) in standard gutter view
- Integrates with IntelliJ's coverage statistics panel (coverage %)
- Triggered manually via "Show SonarQube Coverage" action (not auto-run)

**Files:**
- `sonar/src/main/kotlin/.../coverage/SonarCoverageEngine.kt`
- `sonar/src/main/kotlin/.../coverage/SonarCoverageRunner.kt`
- `sonar/src/main/kotlin/.../coverage/SonarCoverageSuite.kt`

---

### 3.4 Run Configuration (Bamboo Trigger)

**Extension point:** `configurationType`

**What it does:** Adds a "Bamboo Build" run configuration type, allowing developers to trigger Bamboo builds from the Run dropdown with configured variables (including `dockerTagsAsJson`).

**Architecture:**

```
BambooBuildConfigurationType (ConfigurationType)
  └─ BambooBuildConfigurationFactory (ConfigurationFactory)
       └─ BambooBuildRunConfiguration (RunConfigurationBase)
            ├─ getConfigurationEditor(): SettingsEditor
            │    → plan key field, branch field, variables table
            ├─ getState(): RunProfileState
            │    → BambooBuildRunState (triggers build, polls status)
            └─ readExternal/writeExternal (persist config)

BambooBuildRunState (RunProfileState)
  └─ execute(executor, runner): ExecutionResult
       → POST /rest/api/latest/queue/{planKey}
       → poll status every 15s
       → output to ProcessHandler console
```

**Key behavior:**
- Plan key defaults to `settings.bambooPlanKey`
- Branch auto-detects from current Git branch
- Variables table pre-populated with `dockerTagsAsJson` (editable)
- Console output shows build status updates in real-time
- Build finish triggers notification
- "Stop" button cancels polling (can't stop remote build)

**Registration:**
```xml
<configurationType implementation="com.workflow.orchestrator.bamboo.run.BambooBuildConfigurationType"/>
```

**Files:**
- `bamboo/src/main/kotlin/.../run/BambooBuildConfigurationType.kt`
- `bamboo/src/main/kotlin/.../run/BambooBuildConfigurationFactory.kt`
- `bamboo/src/main/kotlin/.../run/BambooBuildRunConfiguration.kt`
- `bamboo/src/main/kotlin/.../run/BambooBuildRunState.kt`

---

### 3.5 TODO Pattern (Ticket References)

**Extension point:** `todoIndexPattern`

**What it does:** Highlights `PROJ-123` ticket references in source code comments as TODO items, visible in IntelliJ's TODO tool window.

**Architecture:**

```
<!-- Static registration — no code needed -->
<todoIndexPattern pattern="\b[A-Z][A-Z0-9]+-\d+\b" caseSensitive="true"/>
```

**Key behavior:**
- Pattern: `\b[A-Z][A-Z0-9]+-\d+\b` matches standard Jira ticket IDs
- Appears in TODO tool window alongside regular TODO/FIXME items
- No custom icon needed — uses IntelliJ's default TODO icon
- Zero runtime cost (indexed at file parse time)

**Files:**
- Registration only in `plugin.xml` — no Kotlin code needed

---

## 4. Tier 3 — Nice-to-Have Integrations

### 4.1 Diff Gutter Coverage

**Extension point:** `diff.DiffExtension`

**What it does:** Shows coverage data (green/red) in the diff gutter when viewing Git diffs, so developers see which changed lines have test coverage.

**Architecture:**

```
CoverageDiffExtension (DiffExtension)
  └─ onTwoSideViewer(context): DiffViewerListener
       → For each changed line in the right panel:
          if line in coveredLines → green marker
          if line in uncoveredLines → red marker
          else → no marker
```

**Key behavior:**
- Only activates when SonarQube coverage data is available
- Reads from `SonarDataService.coverageData` (already cached)
- Adds colored gutter icons to the right (new code) side of diffs
- Lightweight — no API calls, reads cached data only

**Files:**
- `sonar/src/main/kotlin/.../diff/CoverageDiffExtension.kt`

---

### 4.2 Project View Build Status Decorators

**Extension point:** `projectViewNodeDecorator`

**What it does:** Adds build status badges (green checkmark / red X) to module directories in the Project view based on latest Bamboo build results.

**Architecture:**

```
BuildStatusNodeDecorator (ProjectViewNodeDecorator)
  └─ decorate(node: ProjectViewNode, presentation: PresentationData)
       → if node is module directory AND build state available:
          append text: " ✓ #123" or " ✗ #123"
          set color: green or red
```

**Key behavior:**
- Only decorates top-level module directories that have corresponding Bamboo build results
- Reads from `BuildMonitorService.stateFlow` (already available)
- Updates reactively when build state changes (via `ProjectView.getInstance(project).refresh()`)

**Files:**
- `bamboo/src/main/kotlin/.../ui/BuildStatusNodeDecorator.kt`

---

### 4.3 Post-Commit Jira Transition

**Extension point:** `checkinHandlerFactory`

**What it does:** After a successful commit, offers to transition the Jira ticket status (e.g., "To Do" → "In Progress") if it hasn't been transitioned yet.

**Architecture:**

```
PostCommitTransitionHandlerFactory (CheckinHandlerFactory)
  └─ PostCommitTransitionHandler (CheckinHandler)
       └─ checkinSuccessful(changes, commitMessage)
            → if active ticket status is "To Do":
              ask via notification: "Transition PROJ-123 to In Progress?"
              if yes: POST /rest/api/2/issue/{key}/transitions
```

**Key behavior:**
- Only triggers if `activeTicketId` is set
- Checks current ticket status via cached data (no extra API call)
- Uses balloon notification with "Transition" action button (non-blocking)
- Transition uses configured workflow mappings from `TransitionMappingStore`
- If ticket is already in expected state, does nothing silently

**Files:**
- `jira/src/main/kotlin/.../vcs/PostCommitTransitionHandlerFactory.kt`

---

### 4.4 Before Run Tag Validation

**Extension point:** `stepsBeforeRunProvider`

**What it does:** Adds a "Validate Docker Tags" before-run step that verifies Docker tag existence in Nexus before triggering a Bamboo build.

**Architecture:**

```
TagValidationBeforeRunProvider (BeforeRunTaskProvider)
  ├─ getId(): Key<TagValidationBeforeRunTask>
  ├─ getName(): "Validate Docker Tags"
  ├─ getIcon(): AllIcons.Actions.Checked
  └─ executeTask(context, task): Boolean
       → for each tag in dockerTagsAsJson:
          HEAD /v2/{name}/manifests/{tag}
          if 404 → fail with "Tag not found: {name}:{tag}"
       → return true if all valid
```

**Key behavior:**
- Only relevant for `BambooBuildRunConfiguration` (our custom run config)
- Validates all Docker tags before triggering the remote build
- Fails fast with clear error message listing missing tags
- Uses Nexus Docker Registry v2 API (Basic auth, not Bearer)

**Files:**
- `automation/src/main/kotlin/.../run/TagValidationBeforeRunProvider.kt`

---

## 5. Cross-Cutting Concerns

### 5.1 Module Dependencies

All integrations follow the existing dependency rule: feature modules depend only on `:core`.

| Integration | Module | New Plugin Dependencies |
|------------|--------|------------------------|
| Task Tracker | `:jira` | `com.intellij.tasks` (optional) |
| VCS Log Column | `:jira` | `Git4Idea` (already declared) |
| Editor Tab | `:jira` | None |
| Search Everywhere | `:jira` | None |
| Commit Time Tracking | `:jira` | None |
| Test Runner UI | `:core` | None |
| Global Inspection | `:sonar` | None |
| Coverage Engine | `:sonar` | `com.intellij.coverage` (optional) |
| Run Config | `:bamboo` | None |
| TODO Pattern | `:jira` | None |
| Diff Coverage | `:sonar` | None |
| Build Status Decorator | `:bamboo` | None |
| Post-Commit Transition | `:jira` | None |
| Before Run Validation | `:automation` | None |

### 5.2 Threading

All integrations follow existing threading rules:
- Extension point callbacks on EDT: return fast, delegate to coroutines
- API calls: always `suspend fun` on `Dispatchers.IO`
- UI updates: `withContext(Dispatchers.Main)` or `invokeLater`
- Caching: concurrent-safe (ConcurrentHashMap or AtomicReference)

### 5.3 Optional Plugin Dependencies

New `<depends optional="true">` entries needed:

```xml
<depends optional="true" config-file="plugin-withTasks.xml">com.intellij.tasks</depends>
<depends optional="true" config-file="plugin-withCoverage.xml">com.intellij.java.coverage</depends>
```

### 5.4 Testing Strategy

| Integration | Test Approach |
|------------|--------------|
| Task Tracker | Unit test JiraTask mapping, MockWebServer for API calls |
| VCS Log Column | Unit test regex extraction, cache behavior |
| Editor Tab | Unit test title generation logic |
| Search Everywhere | Unit test JQL construction, result mapping |
| Commit Time Tracking | Unit test time calculation, worklog payload |
| Test Runner UI | Unit test TeamCity message generation |
| Global Inspection | Unit test severity mapping |
| Coverage Engine | Unit test ProjectData conversion |
| Run Config | Unit test serialization, variable building |
| TODO Pattern | Manual verification in runIde |
| Diff Coverage | Unit test line matching logic |
| Build Status | Unit test decoration logic |
| Post-Commit | Unit test transition decision logic |
| Before Run | MockWebServer for tag validation |

---

## 6. Implementation Priority

**Phase 3A (Tier 1 — estimated 5 days):**
1. TODO Pattern — 10 min (XML-only, zero risk)
2. Editor Tab Ticket Badge — 30 min
3. VCS Log Ticket Column — 2 hours
4. Post-Commit Jira Transition — 1 hour
5. Search Everywhere — 3 hours
6. Commit Dialog Time Tracking — 2 hours
7. Jira Task/Issue Tracker — 4 hours

**Phase 3B (Tier 2 — estimated 4 days):**
8. Surefire Test Runner UI — 3 hours
9. Global Inspection (Sonar) — 2 hours
10. Run Configuration (Bamboo) — 4 hours
11. Coverage Engine Bridge — 4 hours

**Phase 3C (Tier 3 — estimated 2 days):**
12. Build Status Decorators — 1 hour
13. Diff Gutter Coverage — 2 hours
14. Before Run Tag Validation — 2 hours
