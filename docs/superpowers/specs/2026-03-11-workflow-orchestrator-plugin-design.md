# IntelliJ Workflow Orchestrator Plugin: Design Specification

> **Date:** 2026-03-11
> **Status:** Approved
> **Design Philosophy:** A good feature that always works is better than an excellent feature that sometimes works.

---

## 1. Overview

An IntelliJ IDEA plugin that eliminates context-switching between Jira, Bamboo, SonarQube, Bitbucket, and Cody Enterprise by consolidating the entire Spring Boot development lifecycle into a single IDE interface.

**Target:** IntelliJ IDEA 2025.1+ (latest platform APIs, Kotlin UI DSL v2, coroutine threading model)
**Language:** Kotlin 2.x with coroutines
**Architecture:** Modular monolith (single plugin, multiple Gradle submodules)

---

## 2. External Service Stack

All services are self-hosted / on-premise.

| Service | Type | Auth (Phase 1) | Auth (Phase 2) | Key API |
|---|---|---|---|---|
| Jira | Server/Data Center | PAT (Bearer) | OAuth 2.0 (Authorization Code + PKCE) | REST v2 (`/rest/api/2/...`) |
| Bamboo | Server | PAT (Bearer) | OAuth 2.0 | REST (`/rest/api/latest/...`) |
| SonarQube | Server | User Token (Bearer) | N/A (no OAuth for API) | Web API (`/api/...`) |
| Bitbucket | Server/Data Center | HTTP Access Token | OAuth 2.0 | REST v1 |
| Nexus | Docker Registry v2 | PAT (Basic) | N/A | Docker Registry v2 API |
| Cody/Sourcegraph | Enterprise (self-hosted) | Access Token (JSON-RPC) | N/A | Cody Agent JSON-RPC over stdio |

**Auth strategy:** PAT/Token for all services in Phase 1 (zero admin setup). Shared OAuth 2.0 flow for the three Atlassian DC products in Phase 2 (one implementation covers Jira, Bamboo, Bitbucket).

---

## 3. Project Structure

```
intellij-workflow-orchestrator/
├── build.gradle.kts              # Root build config
├── settings.gradle.kts           # Module declarations
├── gradle.properties             # IntelliJ platform version, plugin metadata
│
├── core/                         # :core module
│   └── src/main/kotlin/com/workflow/orchestrator/core/
│       ├── auth/                 # Unified auth (PAT, OAuth 2.0, PasswordSafe)
│       ├── http/                 # OkHttp clients per service, interceptors
│       ├── ai/                   # Cody Agent JSON-RPC integration
│       ├── settings/             # PersistentStateComponent, Kotlin UI DSL v2
│       ├── notifications/        # NotificationGroup per category
│       ├── cache/                # 3-layer: memory, timed, SQLite persistent
│       ├── polling/              # CoroutineScope-based background polling
│       ├── events/               # SharedFlow event bus
│       ├── offline/              # Per-service connectivity tracking
│       ├── onboarding/           # GotItTooltip + collapsible setup dialog
│       └── model/                # Shared data models, ApiResult<T>
│
├── jira/                         # :jira module
│   └── src/main/kotlin/com/workflow/orchestrator/jira/
│       ├── api/                  # Jira REST v2 client + DTOs
│       ├── service/              # Sprint, branching, commit prefix, time tracking
│       ├── ui/                   # Sprint tab, ticket detail, status bar widget
│       └── listeners/            # CommitMessageProvider, BranchChangeListener
│
├── bamboo/                       # :bamboo module
│   └── src/main/kotlin/com/workflow/orchestrator/bamboo/
│       ├── api/                  # Bamboo REST client + DTOs
│       ├── service/              # Build monitor, log parser, CVE remediation
│       ├── ui/                   # Build dashboard tab, status bar widget
│       └── listeners/            # PushListener
│
├── sonar/                        # :sonar module
│   └── src/main/kotlin/com/workflow/orchestrator/sonar/
│       ├── api/                  # SonarQube API client + DTOs
│       ├── service/              # Coverage mapping, issue mapping, health check
│       ├── ui/                   # Quality tab, gutter markers, tree badges
│       └── listeners/            # PrePushHandler
│
├── automation/                   # :automation module
│   └── src/main/kotlin/com/workflow/orchestrator/automation/
│       ├── api/                  # Nexus Docker Registry v2 client
│       ├── service/              # Tag builder, drift detector, queue, conflict detector
│       ├── ui/                   # Automation tab, staging panel, queue panel
│       └── listeners/            # AutoTriggerListener
│
└── handover/                     # :handover module
    └── src/main/kotlin/com/workflow/orchestrator/handover/
        ├── service/              # Copyright, pre-review, PR creation
        ├── ui/                   # Handover tab, pre-review panel
        └── listeners/            # PrePrCheckinHandler
```

**Dependency rule:** Each feature module depends only on `:core`, never on sibling modules. Cross-module communication goes through the event bus.

---

## 4. Core Module Architecture

### 4.1 Authentication

```
core/auth/
├── AuthProvider.kt              # Sealed interface: PAT | OAuth2
├── PatAuthProvider.kt           # Simple Bearer token (all services)
├── OAuth2AuthProvider.kt        # Shared Atlassian DC OAuth 2.0 (Phase 2)
├── CredentialStore.kt           # IntelliJ PasswordSafe wrapper
├── AuthTestService.kt           # "Test Connection" for onboarding
└── SourcegraphAuthProvider.kt   # Sourcegraph access token for Cody Agent
```

- Credentials stored via IntelliJ's `PasswordSafe` API (OS keychain integration) — never in XML settings files
- OAuth 2.0 for Jira/Bamboo/Bitbucket DC uses identical endpoints (`/rest/oauth2/latest/authorize`, `/rest/oauth2/latest/token`), one implementation covers all three
- SonarQube: User Token only (OAuth is web SSO only, not available for API)

### 4.2 HTTP Client Layer

```
core/http/
├── HttpClientFactory.kt         # One OkHttpClient per service
├── AuthInterceptor.kt           # Injects PAT/OAuth token per request
├── RetryInterceptor.kt          # Exponential backoff on 429/5xx
├── OfflineInterceptor.kt        # Detects unreachable hosts, flips to offline mode
├── RateLimiter.kt               # Per-service rate limiting
└── SseClient.kt                 # Server-Sent Events parser
```

- All HTTP calls are `suspend fun` via OkHttp coroutine extensions
- Responses deserialized with `kotlinx.serialization` (compile-time safe, no reflection)
- One `OkHttpClient` per service with its own base URL, auth, and timeouts

### 4.3 AI Layer (Cody Agent JSON-RPC — Enterprise Only)

**Constraint:** Only enterprise Sourcegraph/Cody. No third-party LLM providers.

```
core/ai/
├── CodyAgentManager.kt           # Spawns & manages Cody Agent Node.js process
├── CodyAgentProtocol.kt          # JSON-RPC method definitions (LSP4J interfaces)
├── CodyClient.kt                 # Client-side handlers (workspace/edit, etc.)
├── CodyAuth.kt                   # Enterprise auth (access token + server endpoint)
│
├── protocol/                     # Protocol data classes
│   ├── AgentSpecs.kt             # ClientInfo, ClientCapabilities
│   ├── ExtensionConfiguration.kt # Access token, server URL, custom headers
│   ├── ChatModels.kt             # Available models
│   ├── EditCommands.kt           # Edit task request/response
│   ├── CodeActions.kt            # Code action request/response
│   └── WorkspaceEdit.kt          # File edit diffs from agent
│
├── capabilities/                 # High-level Cody features
│   ├── CodyChatService.kt        # chat/new -> chat/submitMessage
│   ├── CodyEditService.kt        # editCommands/code -> editTask/accept
│   ├── CodyCodeActionService.kt  # "Ask Cody to fix" on errors/sonar issues
│   ├── CodyCommandService.kt     # /fix, /test, /explain
│   └── CodyContextService.kt     # Context file management, repo context
│
└── editor/                       # IntelliJ editor integration
    ├── CodyGutterAction.kt       # "Fix with Cody" gutter icon on Sonar markers
    ├── CodyIntentionAction.kt    # Alt+Enter "Ask Cody to fix" quick-fix
    ├── CodyEditApplier.kt        # Applies workspace/edit diffs to IntelliJ editor
    └── CodyEditPreview.kt        # Diff preview before accepting edits
```

**Integration path:** Cody Agent JSON-RPC over stdio. Agent binary downloaded from npm (`@sourcegraph/cody`) or user-configured path.

**Key protocol methods used:**

| Method | Direction | Purpose |
|---|---|---|
| `initialize` | Plugin -> Agent | Auth + capabilities setup |
| `chat/new` + `chat/submitMessage` | Plugin -> Agent | Commit message gen, summaries |
| `editCommands/code` | Plugin -> Agent | Inline code fixes |
| `editTask/accept` / `editTask/undo` | Plugin -> Agent | Accept/reject edits |
| `codeActions/provide` / `codeActions/trigger` | Plugin -> Agent | "Ask Cody to fix" |
| `command/execute` | Plugin -> Agent | Named commands (/fix, /test) |
| `workspace/edit` | Agent -> Plugin | Agent sends file edits to apply |
| `textDocument/didOpen` / `didChange` | Plugin -> Agent | Keep agent aware of open files |

**"Fix with Cody" flow:**
1. User sees red Sonar gutter marker -> Right-clicks -> "Fix with Cody" (or Alt+Enter)
2. `CodyCodeActionService` gathers context: uncovered lines, surrounding class via PSI, Spring dependencies via `SpringManager`, existing test style
3. Sends `editCommands/code` via JSON-RPC
4. Agent processes with enterprise LLM, calls back with `workspace/edit`
5. `CodyEditApplier` shows diff preview in editor
6. User accepts/rejects -> edit applied via `WriteCommandAction`

### 4.4 Event Bus (Cross-Module Communication)

```
core/events/
├── EventBus.kt              # Central dispatcher (Kotlin SharedFlow)
├── WorkflowEvent.kt         # Sealed class hierarchy
└── EventSubscriber.kt       # DSL for subscribing
```

**Event types:**

```kotlin
sealed class WorkflowEvent {
    // From :jira
    data class WorkStarted(val ticketId: String, val branch: String) : WorkflowEvent()
    data class TaskCompleted(val ticketId: String) : WorkflowEvent()

    // From :bamboo
    data class BuildFinished(val planKey: String, val status: BuildStatus) : WorkflowEvent()
    data class BuildLogReady(val planKey: String, val log: String) : WorkflowEvent()

    // From :sonar
    data class QualityGateResult(val projectKey: String, val passed: Boolean) : WorkflowEvent()
    data class CoverageUpdated(val filePath: String, val coverage: CoverageData) : WorkflowEvent()

    // From :automation
    data class AutomationTriggered(val runId: String, val tags: String) : WorkflowEvent()
    data class AutomationFinished(val runId: String, val passed: Boolean) : WorkflowEvent()
    data class QueuePositionChanged(val position: Int) : WorkflowEvent()

    // From :core/ai
    data class CodyEditReady(val filePath: String, val diffs: List<TextEdit>) : WorkflowEvent()
}
```

No module imports another. Cross-module coordination flows through events.

### 4.5 Caching

| Layer | Type | Storage | Use Case |
|---|---|---|---|
| L1 | Memory | In-process | Current build status, queue position, active ticket |
| L2 | Timed (TTL) | In-memory, evicted | Sprint tickets (5min), coverage data (60s) |
| L3 | Persistent | SQLite in `.idea/` | Last 5 dockerTags configs, time tracking, build history, Cody summaries |

Cache reads follow waterfall: L1 -> L2 -> L3 -> API call -> populate all layers.

### 4.6 Threading Model

All work uses Kotlin coroutines scoped to `Project` lifecycle:

| Scope | Dispatcher | Purpose |
|---|---|---|
| PollingScope | `Dispatchers.IO` | Build monitor (30s), queue monitor (60s), Sonar (60s) |
| ApiScope | `Dispatchers.IO` | All HTTP calls, JSON-RPC to Cody Agent |
| CodyAgentScope | `Dispatchers.IO` | Agent subprocess stdin/stdout |
| UiScope | `Dispatchers.EDT` | All UI updates, editor modifications |

- `SupervisorJob` everywhere: one failing coroutine never kills siblings
- Project close cancels all scopes automatically
- `readAction {}` / `writeAction {}` suspend functions for PSI access
- Never block EDT: every API call is a `suspend fun`

### 4.7 Error Handling

```kotlin
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val type: ErrorType, val message: String) : ApiResult<Nothing>()
}

enum class ErrorType {
    AUTH_FAILED,      // 401 -> redirect to settings
    FORBIDDEN,        // 403 -> show permission error
    NOT_FOUND,        // 404 -> stale data, refresh cache
    RATE_LIMITED,     // 429 -> backoff + retry
    SERVER_ERROR,     // 5xx -> retry with backoff, then offline mode
    NETWORK_ERROR,    // Connection failed -> offline mode
    TIMEOUT           // -> offline mode
}
```

Services return `ApiResult<T>`. UI layer pattern-matches to show data, error message, or offline banner. No exceptions leak to UI.

### 4.8 Offline / Degraded Mode

Each service tracked independently. When offline: cached data shown with "stale" indicator. Core IDE operations (branching, committing, local builds) always work. Cody Agent runs as local subprocess, independent of network.

---

## 5. IDE-Native API Integration

The plugin leverages IntelliJ's full API surface rather than building custom solutions:

### 5.1 Maven Integration

**Dependency:** `org.jetbrains.idea.maven`

| API | Usage |
|---|---|
| `MavenProjectsManager` | Access project model, detect modules |
| `MavenRunner` + `MavenRunnerParameters` | Execute `mvn clean install -pl <changed> -am` |
| `MavenRunConfigurationType` | Create saved run configurations for health checks |
| PSI `XmlFile` navigation | Structured pom.xml editing for CVE fixes |

### 5.2 Spring Boot Integration

**Dependencies:** `com.intellij.spring`, `com.intellij.spring.boot`

| API | Usage |
|---|---|
| `SpringManager.getCombinedModel()` | Detect Spring beans in current module |
| `SpringModelSearchers` | Find beans by type for Cody context enrichment |
| `SpringBootLibraryUtil` | Detect Spring Boot version |

### 5.3 Git Integration (Git4Idea)

**Dependency:** `Git4Idea`

| API | Usage |
|---|---|
| `GitBrancher.checkoutNewBranch()` | Branch creation for "Start Work" |
| `GitRepositoryManager` | Get current branch, detect active ticket |
| `Git.diff()` | Get changed files for incremental builds |
| `GitRepository.GIT_REPO_CHANGE` listener | React to branch switches |

### 5.4 VCS Hooks

| Extension Point | Usage |
|---|---|
| `com.intellij.vcsCheckinHandlerFactory` | Commit message auto-prefix, format validation |
| `com.intellij.prePushHandler` | Pre-push health check gate |

### 5.5 Editor Integration

| Extension Point | Usage |
|---|---|
| `com.intellij.codeInsight.lineMarkerProvider` | Sonar coverage gutter markers (red/yellow/grey) |
| `com.intellij.externalAnnotator` | Sonar issue inline warnings (3-phase async) |
| `com.intellij.intentionAction` | Alt+Enter "Ask Cody to fix" quick-fix |
| `com.intellij.codeInsight.declarativeInlayProvider` | Inline coverage % next to method signatures |
| `com.intellij.editorNotificationProvider` | "3 uncovered branches" editor banner |
| `com.intellij.projectViewNodeDecorator` | File tree coverage % badges |

### 5.6 Notifications & Status Bar

| Extension Point | Usage |
|---|---|
| `com.intellij.notificationGroup` | 4 groups: build, quality, queue, automation |
| `com.intellij.statusBarWidgetFactory` | Composite widget: `PROJ-123 checkmark` |

### 5.7 Other Extension Points

| Extension Point | Usage |
|---|---|
| `com.intellij.toolWindow` | Single "Workflow" window with 5 tabs |
| `com.intellij.projectConfigurable` | Settings under Tools > Workflow Orchestrator |
| `com.intellij.configurationType` | Custom Maven run configs for health checks |
| `com.intellij.stepsBeforeRunProvider` | Health check as before-run task |
| `com.intellij.runConfigurationProducer` | Context-based run config creation |
| `com.intellij.copyright.updater` | Copyright header management |

---

## 6. UX Architecture

### 6.1 Tool Window: Single "Workflow" Window

Bottom-docked (matches Run/Debug/Terminal pattern). Five non-closable tabs:

```
[Sprint] [Build] [Quality] [Automation] [Handover]
```

Each tab uses master-detail layout with `JBSplitter`. Per-tab toolbar with contextual actions.

**Visibility:** Shown by default when at least one service is configured. Hidden behind "More tool windows" when unconfigured.

### 6.2 Status Bar Widget

Text-with-popup pattern: `PROJ-123 checkmark` or `PROJ-123 x-mark` or `Workflow: Idle`

Click opens popup with expanded status (ticket, build lanes, queue position). Link at bottom opens full tool window.

### 6.3 Visibility Tiers

| Tier | Elements |
|---|---|
| **Always visible** | Status bar widget, gutter coverage markers, file tree badges |
| **One click** | Tool window tabs, "Start Work" / "Complete Task" buttons, gutter click for Cody fix, Alt+Enter quick-fix |
| **Discoverable** | Settings pages, commit dialog checkboxes, context menu actions, editor banners |
| **Invisible** | API polling, caching, time tracking, offline detection, commit auto-prefix |

### 6.4 Notifications

| Group | Events | Behavior |
|---|---|---|
| `workflow.build` | Build pass/fail | Auto-dismiss success, sticky failure with "View Details" |
| `workflow.quality` | Sonar gate fail, CVE found | Sticky with "View Issues" / "Quick Fix" actions |
| `workflow.queue` | Queue turn started | Sticky "Your turn started" |
| `workflow.automation` | Suite pass/fail, conflicts | Sticky failure, auto-dismiss success |

Max 2 action buttons per notification. Collapse rapid-fire events. All groups independently configurable.

### 6.5 Onboarding

Non-modal, progressive:
1. First run: `GotItTooltip` on Workflow button -> "[Start Setup] [Later]"
2. Setup: collapsible dialog with per-service sections, each with "Test Connection"
3. Partial config OK: plugin shows features based on connected services
4. Feature tooltips shown lazily on first encounter

### 6.6 Context Menus

One submenu "Workflow Orchestrator" in `EditorPopupMenu` with max 5 context-sensitive actions. Hidden when plugin unconfigured. Individual actions hidden when not applicable.

### 6.7 Anti-Patterns to Avoid

- No modal dialogs at IDE startup
- No multiple tool windows (one with tabs)
- No API calls on EDT
- No standard Swing components (use JBList, JBTable, JBColor, JBUI.Borders)
- No secrets in PersistentStateComponent XML
- No expensive logic in `update()` methods

---

## 7. Module Designs

### 7.1 Module: :jira

**API calls:** `getAssignedIssues()`, `getIssueLinks()`, `transitionIssue()`, `addComment()`, `logWork()`, `getBoards()`, `getSprints()`

**Services:**
- `SprintService` — fetches & caches active sprint tickets
- `BranchingService` — branch creation via `GitBrancher` + Jira transition + commit prefix registration
- `CommitPrefixService` — auto-prefix via `VcsCheckinHandlerFactory` (standard `PROJ-123:` or conventional `feat(PROJ-123):`)
- `TimeTrackingService` — start/stop timer, log work to Jira
- `JiraCommentService` — rich-text closure comments with wiki markup

**UI:**
- Sprint tab: master-detail (ticket list + detail panel with dependencies, "Start Work", "Summarize with Cody")
- Status bar widget: current ticket ID + build indicator
- Commit dialog integration: auto-prefix checkbox

**Listeners:**
- `CommitMessageProvider` (CheckinHandler) — auto-prefix ticket ID
- `BranchChangeListener` — detect active ticket from branch name

### 7.2 Module: :bamboo

**API calls:** `getLatestResult()`, `getBuildLog()`, `triggerBuild()`, `getBuildVariables()`, `getRunningBuilds()`

**Services:**
- `BuildMonitorService` — polls build status on background coroutine, fires `BuildFinished` events
- `BuildLogParser` — extracts Maven errors, CVE reports from logs
- `CveRemediationService` — maps CVEs to pom.xml dependencies, creates `IntentionAction` quick-fixes

**UI:**
- Build tab: 3 parallel lanes (Artifact, OSS, Sonar) with progress bars, expandable Sonar details
- Status bar: green/red/spinning build indicator
- pom.xml: CVE warning annotations + Alt+Enter quick-fix to bump version

### 7.3 Module: :sonar

**API calls:** `getComponentMeasures()`, `getIssues()`, `getQualityGate()`, `getCoverage()`

**Services:**
- `CoverageService` — maps Sonar coverage data to local file paths
- `IssueMappingService` — maps Sonar issues to editor positions
- `HealthCheckService` — incremental Maven build via `MavenRunner` (changed modules only)

**UI:**
- Quality tab: quality gate status + issue list
- Editor: gutter markers (`LineMarkerProvider`, severity-coded), inline annotations (`ExternalAnnotator`), editor banner (`EditorNotificationProvider`)
- File tree: coverage % badges (`ProjectViewNodeDecorator`)
- Actions: "Fix with Cody" (gutter), "Ask Cody to fix" (Alt+Enter `IntentionAction`), "Cover this branch"

**Listeners:**
- `PrePushHandler` — gates push on health check result

### 7.4 Module: :automation

**API calls:** Nexus Docker Registry v2 (`listTags()`, `checkTagExists()`)

**Services:**
- `TagBuilderService` — constructs `dockerTagsAsJson` payload
- `DriftDetectorService` — compares staged tags vs latest releases
- `QueueService` — local queue management + auto-trigger via Bamboo polling
- `ConflictDetectorService` — checks running builds for tag overlap
- `TagHistoryService` — persists last 5 configs in SQLite

**UI:**
- Automation tab: service table with tag selector, JSON preview, diff view, queue panel with position/wait time
- Actions: "Validate Tags", "Update All to Latest", "Queue Run", "Cancel"
- Status bar: queue position indicator

### 7.5 Module: :handover

**Services:**
- `CopyrightService` — scans changed `.java` files via `com.intellij.copyright.updater`
- `PreReviewService` — Cody diff analysis for Spring Boot issues (`@Transactional`, unclosed resources, N+1)
- `PrService` — Bitbucket REST API for PR creation, Cody-generated description

**UI:**
- Handover tab: PR readiness checklist, Cody review findings
- Actions: "Complete Task" (gated on automation pass), "Copyright Check", "Cody Pre-Review", "Create PR"

---

## 8. Data Flow & Cross-Module Communication

### 8.1 Event-Driven Architecture

Modules communicate only through `:core`'s `EventBus` (Kotlin `SharedFlow`). Example flow for "Complete Task":

```
:automation emits AutomationFinished(passed=true)
    |
    +---> :handover receives -> unlocks "Complete Task" button
    +---> :jira receives -> pre-fills closure comment with results
              |
              v
      User clicks "Complete Task"
              |
              +-- :handover runs copyright check
              +-- :jira posts comment + transitions ticket
              +-- :jira logs work time
```

### 8.2 Module Dependency Graph

```
:jira --------+
:bamboo ------+
:sonar -------+---> :core
:automation --+
:handover ----+
```

No sibling dependencies. All cross-module data flows through `:core` cache and event bus.

---

## 9. Persistent State

| What | Where | Format |
|---|---|---|
| Plugin settings | `.idea/workflowOrchestrator.xml` | PersistentStateComponent |
| Credentials | OS Keychain | PasswordSafe |
| dockerTagsAsJson history | `.idea/workflowOrchestrator.db` | SQLite |
| Time tracking data | `.idea/workflowOrchestrator.db` | SQLite |
| Cody summaries cache | `.idea/workflowOrchestrator.db` | SQLite (key + version) |
| Build history | `.idea/workflowOrchestrator.db` | SQLite (bounded, last 20) |
| Productivity metrics | `.idea/workflowOrchestrator.db` | SQLite (local only) |

---

## 10. Testing Strategy

### 10.1 Test Layers

| Layer | Target Coverage | Tools |
|---|---|---|
| Unit (DTOs, pure functions) | 95%+ | JUnit 5, kotlinx.serialization |
| Service (business logic) | 90%+ | JUnit 5, MockK |
| API Client (HTTP) | 95%+ | OkHttp MockWebServer |
| Event Bus / Threading | 85%+ | kotlinx-coroutines-test, Turbine |
| Cody Agent Protocol | 80%+ | Mock JSON-RPC server |
| UI Components | 60%+ | IntelliJ BasePlatformTestCase |
| Plugin Verification | N/A | `gradle verifyPlugin` |

### 10.2 Test Fixtures

Real API responses captured from actual service instances (secrets/PII scrubbed), stored in `<module>/src/test/resources/fixtures/`.

---

## 11. Phased Development Plan

### Phase 1A: Foundation (Gate 1 -- "Plugin boots and connects")

| # | Feature |
|---|---|
| 1 | Gradle project setup (modular monolith, `:core` + stubs) |
| 2 | Settings UI (Kotlin UI DSL v2) -- connection pages for all services |
| 3 | PasswordSafe credential storage |
| 4 | AuthTestService -- "Test Connection" per service |
| 5 | Onboarding (GotItTooltip + collapsible setup dialog) |
| 6 | HTTP client layer (OkHttp + coroutines + auth interceptor) |
| 7 | Offline detection + ApiResult<T> error handling |
| 8 | Empty tool window shell ("Workflow" with tabs) |

**Gate 1 milestone:** Plugin installs, user connects to services, verifies connections.

### Phase 1B: Sprint & Branching (Gate 2 -- "Daily workflow starts here")

| # | Feature |
|---|---|
| 9 | Jira API client |
| 10 | Sprint Dashboard tab (master-detail) |
| 11 | Cross-team dependency view |
| 12 | "Start Work" action (GitBrancher + Jira transition) |
| 13 | Branch naming validator |
| 14 | Commit message auto-prefix (VcsCheckinHandlerFactory) |
| 15 | Ticket status bar widget |
| 16 | BranchChangeListener -- auto-detect active ticket |

**Gate 2 milestone:** Start alpha testing. Replaces daily Jira tab usage.

### Phase 1C: Build Monitoring (Gate 3 -- "CI feedback in IDE")

| # | Feature |
|---|---|
| 17 | Bamboo API client |
| 18 | Build Dashboard tab (3 parallel lanes) |
| 19 | Background polling service |
| 20 | Build status bar widget |
| 21 | Notification group: workflow.build |
| 22 | Event bus: BuildFinished event |

**Gate 3 milestone:** Replaces Bamboo browser tab.

### Phase 1D: Quality & Sonar (Gate 4 -- "Coverage visible in editor")

| # | Feature |
|---|---|
| 23 | SonarQube API client |
| 24 | Quality tab |
| 25 | Gutter coverage markers (LineMarkerProvider) |
| 26 | ExternalAnnotator for Sonar issues |
| 27 | File tree coverage badges (ProjectViewNodeDecorator) |
| 28 | EditorNotificationProvider banner |
| 29 | Notification group: workflow.quality |

**Gate 4 milestone:** Replaces SonarQube web UI for daily use.

### Phase 1E: Cody AI + Fixes (Gate 5 -- "AI-powered code fixes")

| # | Feature |
|---|---|
| 30 | Cody Agent Manager (spawn, JSON-RPC, lifecycle) |
| 31 | CodyEditService (editCommands/code + workspace/edit) |
| 32 | "Fix with Cody" gutter action |
| 33 | SonarIntentionAction (Alt+Enter) |
| 34 | CodyEditApplier (diff preview + accept/reject) |
| 35 | "Cover this branch" (test generation with style matching) |
| 36 | Commit message generation via Cody |
| 37 | Spring-aware context (SpringManager + PSI) |

**Gate 5 milestone:** Major productivity boost. AI fixes directly in editor.

### Phase 1F: Pre-Push & Health Check (Gate 6 -- "Quality enforcement")

| # | Feature |
|---|---|
| 38 | PrePushHandler -- health check gate |
| 39 | Incremental Maven build (MavenRunner, changed modules only) |
| 40 | Maven console integration (RunConfiguration + ConsoleView) |
| 41 | CVE auto-bumper (IntentionAction on pom.xml, PSI-based) |
| 42 | Copyright enforcer (com.intellij.copyright.updater) |

**Gate 6 milestone:** Phase 1 complete. Full daily workflow coverage.

### Phase 2A: Automation Orchestrator (Gate 7 -- "Queue management")

| # | Feature |
|---|---|
| 43-54 | Nexus client, staging panel, tag builder, drift detector, smart queue, auto-trigger, conflict detector, notifications, history persistence |

**Gate 7 milestone:** Full automation orchestration in IDE.

### Phase 2B: Handover & Closure (Gate 8 -- "End-to-end lifecycle")

| # | Feature |
|---|---|
| 55-61 | Handover tab, "Complete Task", Jira closure comment, status transition, time tracking, Cody pre-review, one-click PR |

**Gate 8 milestone:** Production-ready. Full workflow in IDE.

### Phase 3: Advanced (Post-Production)

| # | Feature |
|---|---|
| 62-69 | Cody Epic Summarization, Build Timeline Gantt, Inline Build Error Tracing, Regression Blame, Productivity Dashboard, OAuth 2.0, Keyboard Shortcuts, Multi-Repo Support |

---

## 12. Build & Deployment

- **Build tool:** Gradle with IntelliJ Platform Gradle Plugin v2 (`org.jetbrains.intellij.platform`)
- **Target:** IntelliJ IDEA 2025.1+ (unified distribution for 2025.3+)
- **Artifact:** Single `.zip` plugin archive
- **Distribution:** JetBrains Marketplace or internal enterprise repository
- **CI:** `gradle test` -> `gradle integrationTest` -> `gradle verifyPlugin` -> `gradle buildPlugin`
