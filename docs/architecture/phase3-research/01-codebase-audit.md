# Phase 3 Caching Research — Codebase Audit

**Date:** 2026-04-24  
**Branch:** `refactor/cleanup-perf-caching` (Phase 1 & 2 complete)  
**Scope:** HTTP + computation caching surface area for Phase 3 design

---

## 1. HttpClientFactory Full Surface

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpClientFactory.kt`

### ServiceType Enum Values

All 6 services defined in `core/src/main/kotlin/com/workflow/orchestrator/core/model/ServiceType.kt`:

- `JIRA` (displayName: "Jira")
- `BAMBOO` (displayName: "Bamboo")
- `BITBUCKET` (displayName: "Bitbucket")
- `SONARQUBE` (displayName: "SonarQube")
- `SOURCEGRAPH` (displayName: "Sourcegraph")
- `NEXUS` (displayName: "Nexus Docker Registry")

### clientFor(ServiceType) Architecture

**Method signature:** `HttpClientFactory.clientFor(service: ServiceType): OkHttpClient` (line 45-57)

- **Single shared pool:** All clients share `sharedConnectionPool: ConnectionPool(15, 5, TimeUnit.MINUTES)` (line 101)
- **Single shared cache:** All clients use `sharedCache: Cache(10 MB)` at `~/.cache/intellij/{version}/workflow-orchestrator/http-cache` (line 104-107)
- **Per-service client creation:** Clients are cached in `ConcurrentHashMap<ServiceType, OkHttpClient>` and reused (line 34)
- **Lazy init:** Base client and shared pool/cache created on first use via `lazy` delegates

### Auth Scheme Routing

Each service receives correct auth in `clientFor()` (lines 47-51):

```
ServiceType.NEXUS → AuthScheme.BASIC
ServiceType.SOURCEGRAPH → AuthScheme.TOKEN
All others (JIRA, BAMBOO, BITBUCKET, SONARQUBE) → AuthScheme.BEARER
```

### Interceptor Stack

**Common interceptors added to base client:**
- Line 40: `RetryInterceptor()` — automatic retries
- Line 41: `SensitiveEndpointNoCacheInterceptor()` — prevents caching sensitive paths

**Per-service interceptors added in clientFor():**
- Line 53: `HttpMetricsInterceptor()` — metrics collection
- Line 54: `AuthInterceptor(tokenProvider, scheme)` — adds auth header

### Timeouts

- **Connect timeout:** `connectTimeoutSeconds` parameter (default: 10s) applied to all clients via `baseClient.newBuilder()` (line 38)
- **Read timeout:** `readTimeoutSeconds` parameter (default: 30s) applied to all clients via `baseClient.newBuilder()` (line 39)
- **Settings integration:** `HttpClientFactory.timeoutsFromSettings(project)` (line 92-98) reads from `PluginSettings.state.httpConnectTimeoutSeconds` and `.httpReadTimeoutSeconds`

### Cache Configuration

**OkHttpClient HTTP cache enabled (line 116):**
- Size: 10 MB
- Location: `~/.cache/intellij/{version}/workflow-orchestrator/http-cache` (line 105)
- Shared across all service clients
- ETag/304 support via standard OkHttp caching

**SensitiveEndpointNoCacheInterceptor (lines 63-84):**
- Blocks cache storage for `/rest/api/2/myself`, `/rest/auth`, `/_api/graphql`, `/api/user`
- Removes `ETag` headers to prevent conditional requests
- Sets `Cache-Control: no-store` on responses

### Per-Service Client Instantiation in Implementation Layers

Each API client creates its own OkHttpClient from the factory's shared pool:

- **JiraApiClient** (jira/api/JiraApiClient.kt:40-47): Uses `HttpClientFactory.sharedPool.newBuilder()`, adds `AuthInterceptor` + `RetryInterceptor`
- **BambooApiClient** (bamboo/api/BambooApiClient.kt:33-40): Same pattern
- **SonarApiClient** (sonar/api/SonarApiClient.kt:45-52): Same pattern
- **DockerRegistryClient** (automation/api/DockerRegistryClient.kt:36-41): Uses shared pool but no auth interceptor (auth handled in `executeWithAuth()`)
- **BitbucketBranchClient** (core/bitbucket/BitbucketBranchClient.kt): Uses shared pool pattern

**Issue:** API clients recreate interceptor stacks rather than use `HttpClientFactory.clientFor(ServiceType)`. Phase 2 cleanup may not have fully centralized; verify all services route through factory.

---

## 2. SmartPoller Deep Dive

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/polling/SmartPoller.kt`

### Polling Mechanics

**Constructor parameters:**
- `name: String` — logger label
- `baseIntervalMs: Long = 30_000` — baseline poll interval (30s default)
- `maxIntervalMs: Long = 300_000` — max backoff ceiling (300s default)
- `scope: CoroutineScope` — lifecycle tied to caller's scope
- `action: suspend () -> Boolean` — poll lambda; returns true if data changed

### Backoff Logic (lines 36-68)

```
on change:  currentBackoff = 1.0
on no-change: currentBackoff = (currentBackoff * 1.5).coerceAtMost(maxIntervalMs / baseIntervalMs)
on error: currentBackoff = (currentBackoff * 2.0).coerceAtMost(maxIntervalMs / baseIntervalMs)
```

- **1.5x multiplier on no-change** (line 46)
- **2x multiplier on error** (line 52)
- Backoff caps at `maxIntervalMs / baseIntervalMs` ratio

### Visibility Gating (lines 55-59)

```kotlin
val effectiveInterval = if (visible.get() && isIdeFocused()) {
    (baseIntervalMs * currentBackoff).toLong()
} else {
    (baseIntervalMs * currentBackoff * 4).toLong().coerceAtMost(maxIntervalMs)
}
```

- **Active (visible + IDE focused):** `baseIntervalMs * backoff` (e.g., 30s × 1.5 = 45s)
- **Inactive (hidden or unfocused):** 4× multiplier (e.g., 30s × 1.5 × 4 = 180s, capped at 300s)

### Jitter (lines 61-63)

+/- 10% random jitter prevents thundering herd:
```kotlin
val jitter = (effectiveInterval * 0.1 * (Random.nextDouble() * 2 - 1)).toLong()
val finalDelay = (effectiveInterval + jitter).coerceAtLeast(baseIntervalMs)
```

### Visibility Debouncing (lines 76-102)

**Debounce constant:** `VISIBILITY_DEBOUNCE_MS = 1_000L` (line 33)

When tab becomes visible (`wasHidden && isVisible`):
- Reset backoff to 1.0
- Skip immediate poll if visibility changed within last 1 second (prevents rapid tab switching bursts)
- Otherwise, launch one immediate poll

### IDE Focus Detection (lines 104-114)

Uses `IdeFocusManager.getGlobalInstance().lastFocusedFrame` to check if IDE is focused. Returns `false` on exception (conservative default for shutdown).

### All SmartPoller Call Sites

| Location | Service | baseIntervalMs | maxIntervalMs | Endpoint(s) | Backoff use case |
|---|---|---|---|---|---|
| `bamboo/service/BuildMonitorService.kt:99-108` | Bamboo | `intervalMs` param (default 30s) | 300s | `/rest/api/latest/result/{planKey}/latest` + `/rest/api/latest/result/{planKey}` for newer builds | Reports state change if build number or status changes |
| `pullrequest/service/PrListService.kt:45-56` | Bitbucket | 60s | 300s | `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests` (3 concurrent repos) | Reports change if PR list size differs |
| `automation/ui/MonitorPanel.kt:116-124` | Bamboo | 15s | 300s (default) | `/rest/api/latest/result/{resultKey}` per active run | Polls while runs are active; resets on terminal state |
| `core/toolwindow/insights/InsightsPanel.kt` | Various | TBD | TBD | TBD (insights dashboard) | TBD |
| `pullrequest/ui/CommentsTabPanel.kt` | Bitbucket | TBD | TBD | PR comments endpoint | TBD |

---

## 3. RepoContextResolver Deep Dive

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/settings/RepoContextResolver.kt`

### Return Type and Shape

`resolveCurrentEditorRepoOrPrimary(): GitRepository?` returns:
- **Type:** `GitRepository` (from git4idea.repo)
- **Null fallback:** Returns `null` when no repo is available
- **Source:** Resolved from `RepoConfig` (project-level Bitbucket settings) to matching `GitRepository` via local VCS root path

### Dependency Inputs

**IntelliJ APIs:**
- `FileEditorManager.getInstance(project).selectedEditor?.file` — current open file (EDT-safe via `selectedEditor` property)
- `GitRepositoryManager.getInstance(project).repositories` — all registered Git repos (no sync call)
- `git4idea.repo.GitRepository` — VCS root path matching

**Plugin settings:**
- `PluginSettings.getInstance(project).getRepos()` — multi-repo list with `localVcsRootPath`
- `PluginSettings.getInstance(project).getPrimaryRepo()` — fallback repo

### Idempotency Within Single Editor Selection

**Not idempotent on every call:**
- Line 49: Reads `FileEditorManager.getInstance(project).selectedEditor?.file` fresh each time
- Line 23-26: Iterates through all repos to find ancestor path match (O(n) walk)
- **Caching opportunity:** Same file in same editor position yields same result within seconds/minutes

### All Call Sites (16 total)

| Location | Call type | Frequency estimate | Use case |
|---|---|---|---|
| `pullrequest/ui/PrDetailPanel.kt` | `resolveCurrentEditorRepoOrPrimary()` | On PR detail panel UI init | Determine which repo's PRs to show |
| `pullrequest/action/CreatePrPrefetch.kt` | `resolveCurrentEditorRepoOrPrimary()?.root?.path` | On "Create PR" dialog open | Auto-detect current branch |
| `core/vcs/GenerateCommitMessageAction.kt` | `resolveCurrentEditorRepoOrPrimary()` | On commit message action | Detect ticket from commit context |
| `agent/AgentService.kt` | `resolveCurrentEditorRepoOrPrimary()` | On agent init | Get current branch for context |
| `agent/tools/integration/SonarTool.kt` | `resolveCurrentEditorRepoOrPrimary()?.currentBranchName` | On Sonar tool invocation | Get current branch for Sonar |
| `bamboo/ui/PrBar.kt` (2x) | `resolveCurrentEditorRepoOrPrimary()` | On PrBar visibility / build selection | Determine PR creation context |
| `bamboo/ui/BuildDashboardPanel.kt` | `resolveFromCurrentEditor()` | On build dashboard init | Detect branch from current editor |
| `jira/ui/SprintDashboardPanel.kt` | `resolveFromCurrentEditor()` | On sprint dashboard init | Detect repo context for board filtering |
| `jira/ui/CurrentWorkSection.kt` | `resolveCurrentEditorRepoOrPrimary()` | On current work widget render | Show branch-aware active ticket |
| `core/healthcheck/HealthCheckCheckinHandlerFactory.kt` | `resolveFromCurrentEditor()` | Pre-commit check | Validate against current repo context |
| `core/model/ServiceType.kt` | (indirect via BranchingService) | On "Start Work" branch creation | Select correct Bitbucket repo for branch |

**Estimate:** Called **8–15 times per session** (mostly on UI initialization and dialog open). Not high-frequency, but adds up during active development.

---

## 4. Endpoint Inventory Per Backend

### JIRA

**API docs:** `jira/CLAUDE.md` + `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt`

| Endpoint | Method | Module:File:Method | Cache-ability | Notes |
|---|---|---|---|---|
| `/rest/api/2/myself` | GET | jira/api/JiraApiClient.kt:test connection | ❌ Always live (blocked by SensitiveEndpointNoCacheInterceptor) | Auth test, user info |
| `/rest/agile/1.0/board` | GET | jira/api/JiraApiClient.kt:getBoards() | ✅ Session-stable (1h) | Board list + metadata; paginated max 200 |
| `/rest/agile/1.0/board/{boardId}/sprint` | GET | jira/api/JiraApiClient.kt:getActiveSprints() | ✅ Session-stable (2h) | Active sprints only; metadata stable |
| `/rest/agile/1.0/sprint/{sprintId}/issue` | GET | jira/api/JiraApiClient.kt:getSprintIssues() | ✅ Session-stable (5m refresh) | Issue list for sprint; assignee filter |
| `/rest/agile/1.0/board/{boardId}/issue` | GET | jira/api/JiraApiClient.kt:getBoardIssues() | ✅ Session-stable (5m refresh) | Issue list for board; unresolved filter |
| `/rest/api/2/issue/{key}?expand=issuelinks` | GET | jira/api/JiraApiClient.kt:getIssue() | ✅ Session-stable (15m) | Issue details + links |
| `/rest/api/2/issue/{key}?fields=...&expand=renderedFields` | GET | jira/api/JiraApiClient.kt:getIssueWithContext() | ✅ Session-stable (15m) | Rich context for PR/commit; rendered HTML |
| `/rest/api/2/search` | GET | jira/api/JiraApiClient.kt:searchIssues() | ⚠️ Query-dependent (can cache by JQL hash) | Search with JQL; user-driven |
| `/rest/api/2/issue/{key}/transitions?expand=transitions.fields` | GET | jira/api/JiraApiClient.kt:getTransitions() | ✅ Session-stable (30m) | Transition meta for modal; used 60s cache in TicketTransitionService |
| `/rest/api/2/issue/{key}/comment` | POST | jira/api/JiraApiClient.kt:addComment() | ❌ Never cache | State-changing |
| `/rest/api/2/issue/{key}/worklog` | POST | jira/api/JiraApiClient.kt:postWorklog() | ❌ Never cache | State-changing |
| `/rest/api/2/issue/{key}/transitions` | POST | jira/api/JiraApiClient.kt:transitionIssue() | ❌ Never cache | State-changing |
| `/rest/api/2/user/search?query=` | GET | jira/service/JiraSearchService.kt | ✅ Session-stable (5m) | User lookup for search; autocomplete |
| `/rest/api/2/project/{key}/versions` | GET | jira/service/JiraSearchService.kt | ✅ Session-stable (5m cache in code) | Version list for fix versions |
| `/rest/api/2/project/{key}/components` | GET | jira/service/JiraSearchService.kt | ✅ Session-stable (5m cache in code) | Component list for components field |

### BAMBOO

**API docs:** `bamboo/CLAUDE.md` + `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`

| Endpoint | Method | Module:File:Method | Cache-ability | Notes |
|---|---|---|---|---|
| `/rest/api/latest/plan` | GET | bamboo/api/BambooApiClient.kt:getPlans() | ✅ Session-stable (1h) | Plan list; max 100 |
| `/rest/api/latest/project` | GET | bamboo/api/BambooApiClient.kt:getProjects() | ✅ Session-stable (1h) | Project list; max 100 |
| `/rest/api/latest/project/{key}?expand=plans.plan` | GET | bamboo/api/BambooApiClient.kt:getProjectPlans() | ✅ Session-stable (1h) | Plans for project |
| `/rest/api/latest/search/plans` | GET | bamboo/api/BambooApiClient.kt:searchPlans() | ⚠️ Query-dependent | Fuzzy search; user-driven |
| `/rest/api/latest/plan/{key}/specs?format=YAML` | GET | bamboo/api/BambooApiClient.kt:getPlanSpecs() | ✅ Effectively immutable | Build specs YAML |
| `/rest/api/latest/plan/{key}/branch` | GET | bamboo/api/BambooApiClient.kt:getBranches() | ✅ Session-stable (30m) | Branch list; max 100 |
| `/rest/api/latest/result/{planKey}/latest` | GET | bamboo/service/BuildMonitorService.kt:pollOnce() | ❌ Always live (live polling, 30s–300s) | Latest build status; SmartPoller dependency |
| `/rest/api/latest/result/{planKey}` | GET | bamboo/service/BuildMonitorService.kt:checkForNewerBuild() | ❌ Always live (live polling) | Detect newer builds while current runs |
| `/rest/api/latest/result/{buildKey}` | GET | bamboo/api/BambooApiClient.kt:getResult() | ⚠️ Static after terminal | Full build details; only needed on terminal state |
| `/download/{resultKey}/build_logs/{resultKey}.log` | GET | bamboo/api/BambooApiClient.kt:getBuildLog() | ✅ Effectively immutable | Build log; read-only after build finishes |
| `/rest/api/latest/result/{jobResultKey}?expand=testResults` | GET | bamboo/api/BambooApiClient.kt:getTestResults() | ✅ Effectively immutable | Test results; no change after terminal |
| `/rest/api/latest/queue/{planKey}` | POST | bamboo/service/BambooServiceImpl.kt:triggerBuild() | ❌ Never cache | State-changing |

### BITBUCKET

**API docs:** `pullrequest/CLAUDE.md` (Bitbucket Server v1.0)

| Endpoint | Method | Module:File:Method | Cache-ability | Notes |
|---|---|---|---|---|
| `/rest/api/1.0/users` | GET | core/bitbucket/BitbucketBranchClient.kt:getCurrentUsername() | ✅ Session-stable (1h) | Current user info; rarely changes |
| `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests` | GET | pullrequest/service/PrListService.kt:refresh() | ⚠️ State-dependent (15m) | PR list; filters by author, reviewer, state |
| `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}` | GET | pullrequest/service/PrDetailService.kt | ⚠️ State-dependent (1m) | PR detail; can change frequently (approval, merge) |
| `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/merge` | GET | pullrequest/service/PrActionService.kt | ⚠️ Dynamic (merge preconditions) | Merge preconditions; changes with CI state |
| `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/merge` | POST | pullrequest/service/PrActionService.kt | ❌ Never cache | State-changing |
| `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/approve` | POST | pullrequest/service/PrActionService.kt | ❌ Never cache | State-changing |
| `/rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests` | POST | pullrequest/service/BitbucketServiceImpl.kt:createPullRequest() | ❌ Never cache | State-changing |
| `/rest/api/1.0/projects/{proj}/repos/{repo}/branches` | GET | core/bitbucket/BitbucketBranchClient.kt:getBranches() | ✅ Session-stable (1h) | Branch list; metadata stable |
| `/rest/api/1.0/projects/{proj}/repos/{repo}/commits` | GET | core/bitbucket/BitbucketBranchClient.kt:getCommits() | ⚠️ Append-only (branch commits) | Commit list; only grows, can cache per branch |
| `/rest/api/1.0/admin/users` | GET | core/bitbucket/BitbucketBranchClient.kt:getUsers() | ✅ Session-stable (1h) | User list for reviewer selection |

### SONARQUBE

**API docs:** `sonar/CLAUDE.md` + `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt`

| Endpoint | Method | Module:File:Method | Cache-ability | Notes |
|---|---|---|---|---|
| `/api/authentication/validate` | GET | sonar/api/SonarApiClient.kt:validateConnection() | ⚠️ Keep-alive check | Auth test; can be expensive, cache 5m |
| `/api/components/search?qualifiers=TRK` | GET | sonar/api/SonarApiClient.kt:searchProjects() | ✅ Session-stable (2h) | Project search; user-driven but infrequent |
| `/api/project_branches/list?project={key}` | GET | sonar/api/SonarApiClient.kt:getBranches() | ✅ Session-stable (1h) | Branch list; metadata stable |
| `/api/qualitygates/project_status?projectKey={key}` | GET | sonar/service/SonarDataService.kt:refresh() | ⚠️ Live (5m refresh debounce in code) | Quality gate pass/fail; cached 5m in SonarState |
| `/api/issues/search?componentKeys={key}` | GET | sonar/api/SonarApiClient.kt:getIssues() | ⚠️ Live (cached via SonarState StateFlow) | Issue list; changes per analysis cycle (~15m) |
| `/api/measures/component_tree?component={key}&metricKeys=` | GET | sonar/api/SonarApiClient.kt:getMeasures() | ⚠️ Live (cached via SonarState) | Coverage + code metrics; per analysis cycle |
| `/api/ce/activity?component={key}` | GET | sonar/api/SonarApiClient.kt:getAnalysisActivity() | ⚠️ Live | Compute engine activity (analysis status) |
| `/api/new_code_periods/show?project={key}` | GET | sonar/api/SonarApiClient.kt:getNewCodePeriod() | ✅ Effectively immutable | New code period definition |
| `/api/sources/lines?key={fileKey}&branch={branch}` | GET | sonar/ui/CoverageLineMarkerProvider.kt | ✅ Effectively immutable | Source coverage; no change per branch |
| `/api/hotspots/search?project={key}&branch={branch}` | GET | sonar/api/SonarApiClient.kt:getSecurityHotspots() | ⚠️ Live | Security hotspots; per analysis cycle |

### SOURCEGRAPH

**No dedicated API client found.** Sourcegraph integration is via `LlmBrainFactory` (agent context generation). Likely uses GraphQL `/_api/graphql` endpoint.

**Assumption:** GraphQL queries for code search, symbols, etc. These are:
- ✅ **Effectively immutable** — code search results don't change per session (unless repo commits occur)
- ⚠️ **Query-dependent** — cache by query hash if implemented

### NEXUS

**API docs:** `automation/CLAUDE.md` + `automation/src/main/kotlin/com/workflow/orchestrator/automation/api/DockerRegistryClient.kt`

| Endpoint | Method | Module:File:Method | Cache-ability | Notes |
|---|---|---|---|---|
| `/v2/` | GET | automation/api/DockerRegistryClient.kt:validateConnection() | ⚠️ Keep-alive (5m) | Registry auth test |
| `/v2/{name}/tags/list` | GET | automation/api/DockerRegistryClient.kt:listTags() | ✅ Session-stable (5m TTL in code) | Docker tag list; paginated |
| `/v2/{name}/manifests/{tag}` | HEAD | automation/api/DockerRegistryClient.kt:tagExists() | ✅ Session-stable (5m) | Tag validation; once during trigger |

---

## 5. Existing Caching

### IntelliJ-Native Caching

**No `CachedValuesManager` usage found.** CachedValue is not adopted; custom caches only.

### Jira-Specific Caching

**TicketKeyCache** (`jira/service/TicketKeyCache.kt:18-104`)
- **WHAT:** Validated Jira ticket keys (e.g., `PROJ-123`)
- **SIZE:** Max 500 entries, LRU eviction (first-in-first-out simple removal)
- **INVALIDATION:** Manual `clear()` (called on `TicketChanged` event) or TTL-based eviction
- **KEYS:** Cache[String → TicketKeyInfo?]; null = invalid key
- **Frequency:** Used for hyperlinking in descriptions/comments; called per text render

**IssueDetailCache** (`jira/service/IssueDetailCache.kt:14-57`)
- **WHAT:** Per-issue comments, attachments (lazy-loaded details)
- **SIZE:** Max 200 entries (per-issue), LRU eviction by `fetchedAt` timestamp (line 52)
- **INVALIDATION:** On `IssueChanged` event (manual) or eviction
- **KEYS:** Cache[issueKey → IssueDetailData]
- **DATA:** comments: List[JiraCommentData]?, attachments: List[JiraAttachment]?, fetchedAt: Instant

**SprintPaginationCache** (`jira/service/SprintPaginationCache.kt:20-111`)
- **WHAT:** File-based cache for sprint pagination state (startAt offsets for closed sprints)
- **SIZE:** Per-board entry with lastStartAt offset + timestamp
- **LOCATION:** `~/.workflow-orchestrator/sprint-pagination-cache.json` (user home, shared across IDE instances)
- **INVALIDATION:** Timestamp-based; no explicit TTL
- **KEYS:** Cache[boardId.toString() → BoardCacheEntry]
- **THREAD-SAFETY:** Synchronized lock for in-memory access; file I/O outside lock

### Pullrequest-Specific Caching

**BitbucketBranchClientCache** (`pullrequest/service/BitbucketBranchClientCache.kt:31-59`)
- **WHAT:** Reuses single `BitbucketBranchClient` per URL
- **SIZE:** 1 entry per configured URL (stateless; just wraps client instance)
- **INVALIDATION:** Auto-invalidates when Bitbucket URL setting changes (line 44)
- **KEYS:** Cached by `baseUrl` (trimmed)
- **USE:** Shared by PrDetailService, PrActionService, PrListService, BitbucketServiceImpl

### Automation-Specific Caching

**DockerRegistryClient** (`automation/api/DockerRegistryClient.kt:43-45`)
- **tokenCache:** ConcurrentHashMap[realm → (token, expiresAt)] — Docker auth token (short-lived)
- **tagCache:** ConcurrentHashMap[serviceName → (tags, expiresAt)] — Tag list, **5m TTL** (line 45)
- **INVALIDATION:** TTL-based; no manual invalidation

### AI/Model Caching

**ModelCache** (`core/ai/ModelCache.kt`)
- Not detailed here; appears to cache LLM model info only, not API responses

---

## 6. CachedValuesManager Usage

**Result:** **Zero (0) usages found.**

The IntelliJ-native `CachedValuesManager` / `CachedValue` primitives are not adopted anywhere in the plugin. All caching is custom (`ConcurrentHashMap`, file-based, or OkHttp).

---

## 7. Settings Surface

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`

### HTTP-Related Settings (State class, lines 13–163)

| Setting | Type | Default | Purpose |
|---|---|---|---|
| `httpConnectTimeoutSeconds` | Int (property) | 10 | HTTP connect timeout; read by `HttpClientFactory.timeoutsFromSettings()` |
| `httpReadTimeoutSeconds` | Int (property) | 30 | HTTP read timeout; read by `HttpClientFactory.timeoutsFromSettings()` |
| `buildPollIntervalSeconds` | Int (property) | 30 | SmartPoller base interval for Bamboo builds (legacy; override via SmartPoller constructor) |

### Cache-Related Settings

**None found.** No cache size, TTL, or enable/disable toggles currently exposed in PluginSettings.

### ConnectionSettings

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/settings/ConnectionSettings.kt`

- `jiraUrl`, `bambooUrl`, `bitbucketUrl`, `sonarUrl`, `sourcegraphUrl`, `nexusUrl` (String, blank defaults)
- `bitbucketUsername` (String, blank default)
- All URLs trimmed and validated before use

---

## 8. Known Perf-Sensitive Paths

### HTTP + EDT Interaction

**Pattern 1: Polling → EDT**
- SmartPoller calls `action()` suspend lambda (off EDT)
- Lambda fetches HTTP response (e.g., BuildMonitorService.pollOnce)
- Result posted to EDT via `invokeLater` to update UI (lines in BuildMonitorService, PrListService, MonitorPanel)
- **Cache target:** Latest build status, PR list state — reduce API calls during rapid tab switching

**Pattern 2: Dialog Open → Prefetch → LLM**
- CreatePrDialog open triggers CreatePrPrefetch.run() (IO thread)
- Prefetch fetches repo list, branch list, ticket contexts in parallel (all HTTP)
- Results passed to CreatePrDialog
- **Cache target:** Branch list (stable 1h), ticket context (15m), transition meta (30m)

**Pattern 3: Action → Sync Read → HTTP**
- User clicks "Start Work" (EDT)
- Dialog opens, calls HealthCheckCheckinHandlerFactory → resolveFromCurrentEditor() (EDT-safe)
- Pre-commit health check spawns IO task (ReadAction + HTTP)
- **Cache target:** Git repo context resolution (idempotent within editor position)

### Blocking Calls

**None explicitly marked as EDT-blocking found**, but:
- `FileEditorManager.getInstance().selectedEditor` (EDT-safe property read)
- `GitRepositoryManager.getInstance().repositories` (EDT-safe; returns cached list)
- `PasswordSafe.get()` can block 1–2s on first access (Windows), but cached in CredentialStore (line 36-37 in CredentialStore.kt)

---

## 9. Token Refresh / Auth Rotation

### Token Storage

**Mechanism:** `PasswordSafe` (IntelliJ native secure storage) — scoped by `ServiceType` key

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/auth/CredentialStore.kt`

**In-Memory Caching (lines 32-46):**
- `tokenCache: ConcurrentHashMap<ServiceType, CachedToken>` (static, shared across all CredentialStore instances)
- **TTL: 1 hour** (CACHE_TTL_MS = 3_600_000L, line 37)
- After TTL expiry, token is re-read from PasswordSafe (line 62-68)

**Rotation During Session:**
- **Not implemented.** Tokens are read once per session (or once per hour after TTL).
- No refresh token mechanism; assumes user tokens don't expire within 1 hour.
- If token is manually rotated in PasswordSafe, cache evicts after 1h or manual `clearGlobalCache()` call.

**Cache Safety:**
- ✅ **Cache is keyed by ServiceType alone (no request URL).**
- ✅ **Auth is stable per session.** Tokens don't rotate during use; safe to cache HTTP responses by URL alone.
- ⚠️ **1-hour TTL limits exposure window** for compromised tokens.

---

## 10. Summary: Top 10 Phase 3 Design Considerations

### 1. **Shared HTTP Cache Already Exists**
OkHttpClient's 10 MB shared cache is enabled (line 104-107, HttpClientFactory.kt), but only works for ETag/304 responses. Phase 3 can build on this for immutable resources (build logs, source code snippets).

### 2. **Single Shared Connection Pool + 6 Service-Specific Clients**
All services share `ConnectionPool(15, 5min)` and cache, but each service wraps it in its own OkHttpClient with service-specific interceptors. Risk: Duplicate interceptor stacks (RetryInterceptor, AuthInterceptor) across 6 clients. Phase 3 should centralize via `HttpClientFactory.clientFor()` if not already done.

### 3. **SmartPoller Backoff Already In Use (4 Active Pollers)**
BuildMonitor (30–300s), PrList (60–300s), AutomationMonitor (15–300s), plus InsightsPanel. Phase 3 can leverage existing 1.5x backoff for dedup; when backoff = max, HTTP traffic is already throttled 10x.

### 4. **RepoContextResolver Needs Memoization**
Called 16 times per session but not cached. Lookups are O(n) in repos count. Phase 3 should memoize `resolveCurrentEditorRepoOrPrimary()` with 60s TTL, invalidated on `BranchChanged` event.

### 5. **Jira Has Most Existing Caching (3 Caches)**
TicketKeyCache (500 entries), IssueDetailCache (200 entries), SprintPaginationCache (file-based). All use simple LRU. Phase 3 should audit invalidation triggers to ensure consistency.

### 6. **Token Cache Is Session-Stable (1h TTL)**
Tokens don't rotate mid-session, so URL-only cache keys are safe. However, 1h TTL means long-lived IDE sessions could miss rotated tokens. Phase 3 can safely add HTTP response caching without worrying about auth changes.

### 7. **SensitiveEndpointNoCacheInterceptor Blocks 4 Paths**
`/rest/api/2/myself`, `/rest/auth`, `/_api/graphql`, `/api/user` are explicitly excluded from HTTP cache. Phase 3 must not cache these; they contain mutable sensitive data (auth status, user info).

### 8. **Build Status + PR State Are Live-Polled, Not Cached**
BuildMonitorService and PrListService poll every 30–60s. HTTP responses are not cached; only SmartPoller backoff prevents thrashing. Phase 3 should add dedup via result hashing (e.g., hash of build #/status).

### 9. **No Query Result Caching**
Jira/Sonar searches are user-driven and not cached. Phase 3 can add LRU by query hash (e.g., `hash(JQL)` → results, 5m TTL). Requires query normalization for collision avoidance.

### 10. **CachedValuesManager Not Adopted; Phase 3 Can Introduce It**
No IntelliJ-native caching is used. Phase 3 can adopt `CachedValuesManager` for project-scoped caches (e.g., memoized RepoContextResolver, transition metadata), with automatic invalidation on project close.

---

## Appendix: File Paths for Every Claim

### HttpClientFactory
- `core/src/main/kotlin/com/workflow/orchestrator/core/http/HttpClientFactory.kt:1–120`
  - ServiceType routing: lines 47–51
  - Shared pool: line 101
  - Shared cache: lines 104–107
  - SensitiveEndpointNoCacheInterceptor: lines 63–84

### SmartPoller
- `core/src/main/kotlin/com/workflow/orchestrator/core/polling/SmartPoller.kt:1–115`
  - Backoff: lines 36–68
  - Visibility gating: lines 55–59
  - Jitter: lines 61–63
  - Debounce: lines 76–102
  - Call sites: grep for `SmartPoller(` in:
    - bamboo/service/BuildMonitorService.kt:99
    - pullrequest/service/PrListService.kt:45
    - automation/ui/MonitorPanel.kt:116
    - core/toolwindow/insights/InsightsPanel.kt (TBD)

### RepoContextResolver
- `core/src/main/kotlin/com/workflow/orchestrator/core/settings/RepoContextResolver.kt:1–109`
  - resolveCurrentEditorRepoOrPrimary: lines 63–66
  - resolveFromCurrentEditor: lines 48–52
  - Call sites: 16 locations (grep for `resolveCurrentEditorRepoOrPrimary\|resolveFromCurrentEditor`)

### Caching
- **TicketKeyCache:** `jira/service/TicketKeyCache.kt:18–104`
- **IssueDetailCache:** `jira/service/IssueDetailCache.kt:14–57`
- **SprintPaginationCache:** `jira/service/SprintPaginationCache.kt:20–111`
- **BitbucketBranchClientCache:** `pullrequest/service/BitbucketBranchClientCache.kt:31–59`
- **DockerRegistryClient token/tag cache:** `automation/api/DockerRegistryClient.kt:43–45`

### Settings
- **PluginSettings.State HTTP fields:** `core/settings/PluginSettings.kt:82–83`
- **CredentialStore token cache:** `core/auth/CredentialStore.kt:34–37`

### API Clients
- `jira/api/JiraApiClient.kt:33–47` (httpClient init)
- `bamboo/api/BambooApiClient.kt:33–40`
- `sonar/api/SonarApiClient.kt:45–52`
- `automation/api/DockerRegistryClient.kt:36–41`
- `core/bitbucket/BitbucketBranchClient.kt` (TBD — shared pool usage)

