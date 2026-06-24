# :core Module

Shared infrastructure for all feature modules. No feature module imports another feature module — everything goes through :core.

## Event Bus

`EventBus` uses `SharedFlow<WorkflowEvent>`. Key events:

- `TicketChanged` — active Jira ticket changed (Start Work, branch switch)
- `BranchChanged` — git branch changed
- `BuildFinished` / `BuildLogReady` — Bamboo build terminal state / log fetched
- `QualityGateResult` / `CoverageUpdated` — SonarQube status changes
- `HealthCheckStarted` / `HealthCheckFinished` — pre-commit health check lifecycle
- `AutomationTriggered` / `AutomationFinished` / `QueuePositionChanged` — automation queue
- `PullRequestCreated` / `PullRequestMerged` / `PullRequestDeclined` / `PullRequestApproved` / `PrSelected` — PR lifecycle
- `TicketDetected` — ticket detected from branch but dismissed (shows banner in Sprint tab)
- `JiraCommentPosted` — handover event

## ToolResult<T>

Universal return type for service operations. `data: T` for UI, `summary: String` for logging/notifications, `isError: Boolean`, optional `hint: String`.

## Caching

Dev-status responses (`/rest/dev-status/1.0/issue/detail`) are cached for 60s via `CachePolicyRegistry`.
`DevStatusCacheInvalidator` (project-scoped service, started by `DevStatusCacheInvalidatorActivity`) subscribes to
`EventBus.events` and calls `HttpResponseCache.invalidateByPrefix("/rest/dev-status/1.0/issue/detail")` on
`BranchChanged`, `PullRequest{Created,Merged,Declined,Approved}`, and `TicketChanged` — collapsing own-action
freshness latency from 60s to ~0ms. Teammates' changes are still bounded by the TTL.
HTTP-mutation-driven eviction (POST/PUT/DELETE responses) is handled separately by `MutationInvalidationInterceptor`.

## HttpClientFactory

Shared `ConnectionPool(5, 3min)` base client. Per-service clients via `clientFor(ServiceType)` with correct auth scheme:
- `BEARER` — Jira, Bamboo, Bitbucket, SonarQube
- `TOKEN` — Sourcegraph

Includes `RetryInterceptor` on all clients.

## SmartPoller

Activity-aware polling: `baseIntervalMs` (default 30s), `maxIntervalMs` (default 300s). Backoff 1.5x on no-change, resets on change. Jitter +/-10%. Visibility gating (4x interval when IDE unfocused or tab hidden).

**Connectivity gate.** At the top of each loop iteration, if `networkProbe.state != ONLINE` the poller suspends on `awaitOnline(maxIntervalMs)` instead of firing into a dead tunnel; on reconnect it resets backoff and applies a per-poller jittered stagger (0..baseInterval) to avoid a reconnect stampede. The `networkProbe` constructor param defaults to `NetworkStateService.getInstanceOrNull()`; it precedes `action` so existing trailing-lambda call sites are unaffected.

**Overlap guard (B13, 2026-06-10 perf audit).** `setVisible(true)`'s immediate resume poll and the poll loop both run `action()` under a shared `pollMutex`, so the visibility resume can never fire a duplicate HTTP refresh CONCURRENTLY with an in-flight loop poll — it serializes behind it instead. `currentBackoff` is `@Volatile` (mutated from both contexts). The lock is uncontended on the loop's hot path, so the delay math (focus gate, debounce, jitter) is unchanged. Pinned by `SmartPollerOverlapGuardTest`.

## NetworkStateService

`core/network/NetworkStateService.kt` — application-level (`@Service(Service.Level.APP)`, constructor-injected `cs`) connectivity authority. Single coalesced `StateFlow<NetworkState>` (`ONLINE`/`OFFLINE`/`RECONNECTING`); the VPN tunnel is per-machine, so this is APP-scoped, not per-project. Three inputs: (1) **reactive** — `NetworkStateReportingInterceptor` on `HttpClientFactory.baseClient` calls `reportSuccess()` on any HTTP response (server reached, even 4xx/5xx) and `reportFailure(origin)` on `IOException` (transport down); covers every `HttpClientFactory` client (all feature pollers). (2) **active probe** — one backoff loop (`NetworkReachabilityProbe`, a short-timeout HEAD on its OWN OkHttpClient that bypasses the factory) discovers reconnection while everything is paused; re-arms in `finally` to dodge a TOCTOU stuck-OFFLINE race. (3) **wake watchdog** — a monotonic clock-gap detector (`isWakeGap`) flips to `RECONNECTING` + reprobes when a tick gap shows the machine slept. APIs: `checkNow(url)` (bounded probe, used by the agent), `awaitOnline(timeoutMs)` (pollers suspend here). **Coverage note:** the agent's LLM client (`SourcegraphChatClient`) and `BitbucketBranchClient` bypass `HttpClientFactory` (kept isolated), so they are NOT reactively reported — the agent path is covered by the active `checkNow()` probe at its retry seam instead. Spec: `docs/superpowers/specs/2026-05-26-network-connectivity-resilience-design.md`.

## Extension points

| EP | Interface | Purpose |
|---|---|---|
| `textGenerationService` | `core/ai/TextGenerationService` | Cross-module AI text generation (used by :bamboo and :pullrequest for PR descriptions + titles). |
| `createPrLauncher` | `core/bitbucket/CreatePrLauncher` | Entry point for PR creation. Registered by :pullrequest. Called only from :pullrequest PrDashboardPanel (the Build-tab Create PR button was removed in the 2026-04-27 PrBar redesign). Signature: `launch(project, scope)`. |

## Settings

- `ConnectionSettings` — application-level (shared across projects): service URLs (Jira, Bamboo, Bitbucket, SonarQube, Sourcegraph), Bitbucket username
- `PluginSettings` — project-level: plan keys, polling intervals, feature toggles, docker tag key, sonar project key

### Settings-group anchor (plugin split)

`workflow.orchestrator` is the stable **public** settings-group anchor for the "Workflow Orchestrator" page in Tools &rarr; Settings. Depending plugins (e.g. Plugin B) nest their own pages under this group by declaring `<projectConfigurable parentId="workflow.orchestrator" .../>` in their own `plugin.xml` — **no custom EP is needed** on Plugin A's side; the platform resolves the parent/child relationship automatically.

**Do not rename this id.** It is the unique identifier that Platform uses for the group node AND the parent lookup for any contributing plugin. Renaming it silently orphans every B-contributed page (they fall back to "Other Settings" without any error).

Pinned by `SettingsAnchorContractTest` (`:konsist`) — the test asserts that `WorkflowSettingsConfigurable` carries `id = "workflow.orchestrator"` in A's `plugin.xml` registration. Keep the test and the id in sync if the configurable is ever restructured.

### Sub-agent settings

- `enableResearchSubagent: Boolean = true` (project-level) — gates the bundled `research` sub-agent persona; when false, `SpawnAgentTool` returns `RESEARCH_SUBAGENT_DISABLED` for both LLM-driven and slash-command invocations.

### PR creation settings

- `enableAiTitleGeneration: Boolean = true` (project-level) — controls the AI-sparkle icon inside the PR title field
- `jiraAcceptanceCriteriaFieldId: String? = null` (project-level) — Jira custom field ID for acceptance criteria (e.g. `customfield_10001`); used to enrich LLM context. Settings UI presents a dropdown sourced from `JiraService.getFields()` (5-min cached) with a textfield fallback when discovery fails.

### BitbucketBranchClient surface (extended 2026-05-07 audit)

`core.bitbucket.BitbucketBranchClient` adds eight methods + adopts two endpoint changes from the 2026-05-07 Bitbucket DC 9.4 audit. Source: `docs/research/2026-05-07-bitbucket-recommendations.md`.

| Method | Backing endpoint | Notes |
|---|---|---|
| `getDashboardPullRequests(role, state)` | `GET /rest/api/1.0/dashboard/pull-requests` | R-SWAP-1 / R-SWAP-2 — collapses per-repo iteration into 1 call. `PrListService.refresh()` adopts this for AUTHOR/REVIEWER buckets. |
| `getBlockerComments(p, r, prId, countOnly)` | `GET /pull-requests/{id}/blocker-comments?count=true` | R-SWAP-4 — replaces client-side `comments.filter { severity == BLOCKER }`. |
| `getPullRequestParticipants(p, r, prId)` | `GET /pull-requests/{id}/participants` | R-SWAP-5 — explicit endpoint with `state` + `lastReviewedCommit`. |
| `getCommitPullRequests(p, r, sha)` | `GET /commits/{sha}/pull-requests` | R-ADD-5 — reverse lookup; powers the Bamboo bridge. |
| `getCommitBuildStats(sha)` | `GET /rest/build-status/1.0/commits/stats/{sha}` | R-ADD-12 — `{successful, failed, inProgress}` counter. |
| `getLinkedJiraIssues(p, r, prId)` | `GET /rest/jira/1.0/.../pull-requests/{id}/issues` | R-ADD-11 — Atlassian Jira-link plugin. 404 → empty list (plugin not installed). |
| `getRequiredBuilds(p, r)` | `GET /rest/required-builds/latest/projects/{p}/repos/{r}/conditions` | R-ADD-15 — canonical path; v0 path under `/rest/api/1.0/` 404s on DC 9.4. |
| `listPrComments` (rewritten) | derived from `getPullRequestActivities` | R-1.1 — DC 9.4 rejects direct `/comments` listing; activities timeline carries every COMMENTED action. |
| `getMergeStrategies` (extended) | repo URL → project URL on 404 | R-1.2 — repo absence-of-override is 404, falls back to project-level setting. Resolution cached per session. |
| `getBranches` (extended) | now passes `&details=true` | R-SWAP-3 — branches return `metadata` (aheadBehind, latestCommit, jiraIssues, build) inline. |

The new methods all surface through `core.services.BitbucketService` (Phase 5: `getBlockerCommentsCount`, `getPullRequestParticipants`, `getPullRequestsForCommit`, `getCommitBuildStats`, `getLinkedJiraIssues`, `getRequiredBuilds`) with `ToolResult<T>` semantics; `:pullrequest`'s `BitbucketServiceImpl` adapts them to `core.model.bitbucket.{ParticipantData,BuildStatsData,JiraIssueRef,RequiredBuildsCondition}`. Agent wrappers in `:agent` (`BitbucketPrTool`, `BitbucketRepoTool`) expose them as actions.

### BitbucketBranchClient surface (extended 2026-05-07 write-ops audit, PR 6)

PR 6 of the write-ops fix plan (audit P1 findings #6 + #7) extends the client
surface for branch-aware default reviewers and commit-pinned inline comments:

- `getDefaultReviewersForBranch(repo, sourceBranch, targetBranch)` — resolves the
  default-reviewer conditions whose `sourceRefMatcher` AND `targetRefMatcher`
  both accept the branch pair, and returns the union of reviewers across the
  matching conditions only. Replaces the previous union-all logic for the
  PR-creation path. The `DefaultReviewerCondition` DTO now carries
  `sourceRefMatcher` / `targetRefMatcher` (with `RefMatcher.matches()` covering
  `BRANCH`, `MODEL_BRANCH`, `MODEL_CATEGORY`, `ANY_REF`, `PATTERN`); legacy
  `getDefaultReviewers` is retained for admin/preview callers that want every
  configured reviewer.
- `addInlineComment(..., diffType, fromHash, toHash)` — pins the comment to the
  specific diff range so it doesn't float when new commits land. AI-review
  pushes capture `toRef.latestCommit` at review-time and pass `diffType=COMMIT`
  + `toHash=<that commit>`; legacy callers that omit the new args keep the
  server-default `EFFECTIVE` behaviour.
- `PrActionService.updateDescription` now routes through
  `BitbucketBranchClient.modifyPullRequest` (same retry-on-409 pattern as
  `updateTitle` / `addReviewer` / `removeReviewer`), and the dialog caller
  drops the now-redundant `version` argument.
- `BitbucketServiceImpl.{addReviewer, removeReviewer, updatePrTitle}` (the
  agent entry points) now delegate to `PrActionService` for the primary repo
  and use `modifyPullRequest` directly for non-primary multi-repo coords —
  retry semantics flow through both paths instead of being duplicated.

### JiraService surface (extended 2026-05-07 write-ops audit, PR 5)

`addComment` now takes an optional `CommentVisibility` (`role`/`group` + name) so closure
comments can be restricted to a project role or Jira group; the `visibility` JSON block is
omitted entirely when null (Jira rejects `visibility: null`). `logWork` now takes optional
`started: OffsetDateTime` (formatted as Jira's `yyyy-MM-dd'T'HH:mm:ss.SSSZ`) and
`adjustEstimate: WorklogEstimateAdjustment` (lifts to `?adjustEstimate=…` query param when
non-AUTO). `getCommentVisibilityOptions(projectKey)` is the new lookup that backs the
visibility dropdown — fetches `/project/{key}/role` (name-keyed object) + `/groups/picker?query=`
in parallel and caches the merged result per project (same 5-min TTL as `permissionsCache`).
Pinned by `JiraApiClientCommentVisibilityWorklogTest` and `JiraServiceImplCommentAndWorklogTest`.

### JiraService surface (extended 2026-05-06 audit)

`core.services.JiraService` adds eleven methods to support permission-aware UI gating, on-demand custom-field discovery, history/remote-link/watcher panels, saved filters, and key-prefix mention search:

| Method | Backing endpoint | Cache |
|---|---|---|
| `getMyPermissions(projectKey?)` | `GET /rest/api/2/mypermissions` | 5 min, keyed `projectKey ?: "_global"` |
| `getFields()` | `GET /rest/api/2/field` | 5 min global; `JiraServiceImpl.invalidateFieldsCache()` for settings refresh button |
| `getRemoteLinks(key)` | `GET /rest/api/2/issue/{key}/remotelink` | none |
| `getWatchers(key)`, `addWatcher(key, user)`, `removeWatcher(key, user)` | `/rest/api/2/issue/{key}/watchers` (GET / POST `"user"` / DELETE) | none |
| `getMyselfExpanded()` | `GET /rest/api/2/myself?expand=groups,applicationRoles` | none |
| `getIssueSuggestions(query)` | `GET /rest/api/2/issue/picker` | none; flattens whichever sections come back |
| `getFavouriteFilters()` / `getFilter(id)` | `/rest/api/2/filter/favourite` and `/rest/api/2/filter/{id}` | none |
| `getTicketHistory(key)` | `GET /rest/api/2/issue/{key}?expand=renderedFields,changelog` | none; flattens histories × items into `TicketHistoryEntry` rows |

Models live in `core.model.jira.*` (`MyPermissionsData`, `JiraFieldData`, `RemoteLinkData`, `WatchersData`, `MyselfData`, `IssueSuggestion`, `FilterData`, `TicketHistoryEntry`).

`JiraApiClient` adds an HTML content-type guard: any 200 response with `Content-Type: text/html` (auth-expired login redirect) is mapped to `ApiResult.Error(AUTH_FAILED)` so the loop doesn't try to JSON-parse the login page.

## CredentialStore

Wraps `PasswordSafe`. Keys scoped by `ServiceType`. All tokens stored here, never in `workflowOrchestrator.xml`.

## StatusColors

JBColor constants with light/dark variants: SUCCESS (green), ERROR (red), WARNING (amber), INFO (grey), LINK (blue), MERGED (purple), SECONDARY_TEXT (dim grey), BORDER, CARD_BG, HIGHLIGHT_BG, WARNING_BG, SUCCESS_BG, INFO_BG. Includes `htmlColor(JBColor): String` utility for HTML rendering.

## TicketContext

`core/workflow/TicketContext.kt` — rich Jira ticket payload for LLM context. Fields: key, summary, description (renderedFields preferred), status, priority, issueType, assignee, reporter, labels, components, fixVersions, comments (List<TicketComment>), acceptanceCriteria. Fetched via `JiraTicketProvider.getTicketContext(key)`.

## TicketKeyExtractor

`core/util/TicketKeyExtractor.kt` — canonical regex helper for extracting ticket keys. `extractFromBranch(branchName)` returns the first match; `isValidKey(key)` validates exact format. Pattern: `[A-Z][A-Z0-9]+-\d+` (e.g., `AFTER8TE-912`).

## Services

- `TicketTransitionService` — unified Jira transition orchestrator. Always fetches
  with expand=transitions.fields. Emits TicketTransitioned on success. 60s cache,
  invalidated on the event.
- `JiraSearchService` — user/label/version/component/group + autoCompleteUrl lookups
  used by the transition dialog widgets. Versions/components cached 5 min.
- `TransitionDialogOpener` — bridge interface so :core callers can open the
  transition dialog without depending on :jira/ui.
- `WorkflowContextService` (Phase 5) — single source of truth for active ticket,
  focused PR, editor-derived branch/repo/module across all 6 tool-window tabs and
  the agent. Exposes `StateFlow<WorkflowContext>` plus `activeTicketFlow` and
  `interactionModeFlow` projections. All mutators (`setActiveTicket`, `focusPr`,
  `onEditorRepoChanged`) are mutex-serialized; cascades produce one observable
  `WorkflowContext` transition per call (spec §4.4 single-merged-emission). Two
  EPs in `:core` (`openPrLister`, `latestBuildLookup`) bridge `:pullrequest` and
  `:bamboo` without module-cycle. `WorkflowEventMirror` (one-way bridge from
  legacy `EventBus` events into the service) is installed at startup by
  `WorkflowContextProjectActivity` so panels see a hydrated state on first
  subscribe (spec R8). Anchor (`activeTicket`) persisted via `PluginSettings`;
  focus chain (`focusPr → focusBuild → focusQualityScope`) is session-only.
  **Phase 7 / T-AutoSeed (2026-05-11):** `WorkflowContextService.init` calls
  `loadAnchorFromSettings()` which sets `activeTicket` synchronously from
  `PluginSettings.activeTicketId`, but does NOT trigger the `focusPr` cascade.
  `WorkflowContextProjectActivity.execute()` now calls `service.setActiveTicket(persistedAnchor)`
  (Option A) after `recomputeFromEditor()` and before installing `WorkflowEventMirror`.
  This fires the full cascade so `focusBuild` is populated on fresh IDE, enabling
  `BuildMonitorService` ambient polling without the user opening any tab. Without this,
  `focusBuild` stays null until the user manually opens the PR tab.
  Spec: `docs/architecture/workflow-context-design.md`. Plan:
  `docs/architecture/phase5-workflow-context-plan.md`. T-AutoSeed:
  `docs/architecture/phase7-handover-context-plan.md` § 6.

## Connector seams (plugin split, Phase 0b-2)

Neutral, vendor-agnostic seams layered ABOVE the concrete vendor services so a future
GitHub/GitLab/Jenkins connector is additive. Both are `public` + `@InternalApi` (unfrozen),
shape-reservation only (no consumer/registration yet — like `NativeProtocol` pre-Phase-4):

- `core/services/VcsHostClient.kt` — neutral VCS-host ops (branch/PR/review/file). Implemented by
  `BitbucketServiceImpl` (`:pullrequest`) alongside `BitbucketService` — identical JVM signatures, so
  zero extra methods. Excludes `getLinkedJiraIssues`/`getRequiredBuilds` (vendor-coupled).
  `typealias VcsUserData = BitbucketUserData`; comment ops use neutral `repoOwner`/`repoName` params.
  **No default values on any parameter** — declaring defaults here too would trigger Kotlin
  `MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES` when `BitbucketServiceImpl` inherits from both
  `BitbucketService` (which has the defaults) and `VcsHostClient`; every existing call site passes
  args explicitly, so this is behavior-neutral.
- `core/services/CiService.kt` — neutral CI ops (build/trigger/log/test/pipeline list). Implemented by
  `BambooServiceImpl` (`:bamboo`) alongside `BambooService` via 7 explicit delegating methods (11 of 18 bind "for free" via identical JVM signatures).
  Neutral DTOs `PipelineData`/`CiGroupData` (`core/model/CiModels.kt`) replace `PlanData`/`ProjectData`;
  opaque `pipelineId`/`buildId` strings. Bamboo-specific ops (plan branches/variables/stage trigger/
  auto-detect) stay on `BambooService`. `ToolResult.mapData` (`core/services/ToolResultMapping.kt`)
  is the envelope-preserving remap helper used by the delegating methods. `getRecentBuilds` likewise
  declares no default for `maxResults` (same MULTIPLE_DEFAULTS reason; default stays on `BambooService`).

## Repo resolution

Multi-module / multi-repo projects (parent has no `.git`, each submodule has its own) need callers to pick the right `GitRepository` for the action they're performing. `RepoContextResolver` exposes:

- **`findRepositoryForPath(path: String): GitRepository?`** — preferred for action handlers that already know a file path (checked changes, focused PR's source branch tip, build's plan VCS root). Returns the deepest-matching repo. Use this when *any* user action names a file.
- **`resolveFromFile(file: VirtualFile): RepoConfig?`** — same idea, returns `RepoConfig` instead of `GitRepository`.
- **`resolveFromGitRepo(gitRepo)`** — when you already have the `GitRepository`, get the matching `RepoConfig`.
- **`resolveCurrentEditorRepoOrPrimary()` / `resolveFromCurrentEditor()` / `getPrimary()`** — *editor-or-primary fallback chain*. Always returns a repo, regardless of whether it matches what the user is actually doing. **Do not use these as a default.** Every call is guarded by `MultiRepoScopeInvariantTest`'s `editor-fallback-allowed` marker — the marker forces a per-site justification and is grep-able for audits. The 2026-04-27 sweep (`docs/architecture/repo-resolution-sweep-plan.md`) fixed 11 misuse sites and added the marker convention.

## Service & threading conventions (Phase 4)

Canonical patterns enforced across all modules. Migration history in `docs/architecture/phase4-prong-a-plan.md`, `phase4-prong-c-plan.md`, `phase4-prong-d-grep-plan.md`; status in `phase4-parked-prongs.md`.

- **Service-injected scope.** `@Service` constructors take `cs: CoroutineScope` (2024.1+ platform pattern). Do not allocate `CoroutineScope(SupervisorJob() + …)` inside services — the platform owns lifecycle. `HealthCheckService` and `DefaultBranchResolver` (and the 8 project services across modules) use this form. Non-`@Service` classes consolidate fire-and-forget launches onto a single field scope (e.g., `AgentController.controllerScope`).
- **`runBlocking` policy.** Never on EDT. On background threads (`Task.Backgroundable.run`, `executeOnPooledThread`, `runBackgroundableTask`, `ExternalAnnotator.doAnnotate`, `SearchEverywhereContributor.fetchWeightedElements`), use `runBlockingCancellable { … }` so `ProgressIndicator` cancel propagates.
- **Read actions.** `ReadAction.compute / ReadAction.run / runReadAction` are deprecated for 2026.1. From suspend code use `readAction { }` (writes-may-cancel) or `smartReadAction(project) { }` (waits for indexing) or `readActionBlocking { }` (write-priority blocking). Two intentional `runReadAction { }` survivors are documented in code with TODO for the 2026.1 platform bump: `jira/ui/CurrentWorkSection.kt:185` (non-suspend EDT MouseAdapter) and `agent/ide/IdeContextDetector.kt:114` (synchronous `@Service.init { }` chain).
- **Tool-window dispose cascade.** `WorkflowToolWindowFactory` wires `content.setDisposer(panel)` for tabs whose panel is `Disposable`, so dispose cascades from the tool window into panel children (`Disposer.register(this, child)` inside dashboards completes the chain).
