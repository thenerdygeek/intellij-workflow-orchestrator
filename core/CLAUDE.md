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

## HttpClientFactory

Shared `ConnectionPool(5, 3min)` base client. Per-service clients via `clientFor(ServiceType)` with correct auth scheme:
- `BEARER` — Jira, Bamboo, Bitbucket, SonarQube
- `TOKEN` — Sourcegraph

Includes `RetryInterceptor` on all clients.

## SmartPoller

Activity-aware polling: `baseIntervalMs` (default 30s), `maxIntervalMs` (default 300s). Backoff 1.5x on no-change, resets on change. Jitter +/-10%. Visibility gating (4x interval when IDE unfocused or tab hidden).

## Extension points

| EP | Interface | Purpose |
|---|---|---|
| `textGenerationService` | `core/ai/TextGenerationService` | Cross-module AI text generation (used by :bamboo and :pullrequest for PR descriptions + titles). |
| `createPrLauncher` | `core/bitbucket/CreatePrLauncher` | Entry point for PR creation. Registered by :pullrequest. Called only from :pullrequest PrDashboardPanel (the Build-tab Create PR button was removed in the 2026-04-27 PrBar redesign). Signature: `launch(project, scope)`. |

## Settings

- `ConnectionSettings` — application-level (shared across projects): service URLs (Jira, Bamboo, Bitbucket, SonarQube, Sourcegraph), Bitbucket username
- `PluginSettings` — project-level: plan keys, polling intervals, feature toggles, docker tag key, sonar project key

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
  Spec: `docs/architecture/workflow-context-design.md`. Plan:
  `docs/architecture/phase5-workflow-context-plan.md`.

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
