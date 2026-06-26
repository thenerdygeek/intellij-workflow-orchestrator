# :jira Module

Sprint dashboard, ticket management, branching, and commit integration.

## Jira Server REST API v2

Auth: `Authorization: Bearer <PAT>`
Base: `https://{host}/rest/api/2/`
Audit baseline: Jira DC 10.3.16, Server-only. See `docs/research/2026-05-06-jira-recommendations.md` for the per-endpoint keep/swap/add table and `tools/atlassian-probe/Result_Jira/bundle*.txt` for the redacted probe responses.

Key endpoints:
- `GET /rest/api/2/myself` — test connection
- `GET /rest/api/2/myself?expand=groups,applicationRoles` — onboarding banner (groups + roles)
- `GET /rest/api/2/serverInfo` — version detection
- `GET /rest/agile/1.0/board/{boardId}/sprint/{sprintId}/issue` — sprint tickets (filter: assignee=currentUser())
- `GET /rest/api/2/issue/{key}?expand=issuelinks` — ticket with links
- `GET /rest/api/2/issue/{key}?fields=…&expand=renderedFields,changelog` — combined rich fetch + history (one round-trip)
- `POST /rest/api/2/issue/{key}/transitions` — transition status
- `POST /rest/api/2/issue/{key}/comment` — add comment (wiki markup)
- `POST /rest/api/2/issue/{key}/worklog` — log time
- `POST /rest/api/2/search` — JQL search (used by `validateTicketKeys` to avoid URL-length limits)
- `GET /rest/api/2/issue/picker?query=&showSubTasks=true&showSubTaskParent=true` — `@`-mention key-prefix suggestions
- `GET /rest/api/2/mypermissions[?projectKey=…]` — UI button gating (5-min cache)
- `GET /rest/api/2/field` — custom-field discovery (5-min cache, settings dropdown)
- `GET /rest/api/2/issue/{key}/remotelink` — Confluence/external link section
- `GET /rest/api/2/issue/{key}/watchers` + `POST` (body=`"username"`) + `DELETE ?username=` — watch toggle
- `GET /rest/api/2/filter/favourite` + `GET /rest/api/2/filter/{id}` — Sprint-tab Saved Filters

Skipped on this DC (probe-confirmed not backported): `POST /rest/api/3/search/jql`, `POST /rest/api/2/search/approximate-count`, `GET /rest/api/2/filter/search`.

Server quirk: `/rest/api/2/user/search` requires `username=` (NOT `query=` — Cloud-only). `/rest/api/2/user/assignable/search` accepts both.

## Architecture

- `JiraApiClient` — HTTP client with DTO deserialization
- `JiraServiceImpl` — implements `JiraService` (in :core), wraps JiraApiClient, returns `ToolResult<T>`
- `SprintService` — sprint dashboard logic, supports scrum (sprint-based) + kanban (board-based) modes
- `ActiveTicketService` — tracks current ticket, emits `TicketChanged` events
- `BranchingService` — Start Work flow: creates branch on Bitbucket + transitions Jira ticket. PSI/VFS reads use `readAction { }` (see `:core` "Service & threading conventions").
- `BranchChangeTicketDetector` — detects ticket from branch name on branch switch, shows confirmation popup (dismissed branches tracked in-memory)
- `JiraAgileCapabilityService` (project `@Service`) — probes `GET /rest/agile/1.0/board` async on first
  `agileAvailableOrProbe()` call; caches a tri-state verdict per Jira URL (`true` = agile available,
  `false` = 404/unavailable → hide Sprint tab, `null` = transient/unknown → show). Emits
  `WorkflowEvent.TabAvailabilityChanged("Sprint")` ONLY on a definitive `false` verdict (a `true`
  verdict does not rebuild tabs — fail-open already shows the tab). `SprintTabProvider.isAvailable`
  returns `false` (hide) only when the cached verdict is definitively `false`.

Phase 4 survivor RESOLVED (P2-20, 2026-06-10 perf audit): `CurrentWorkSection.showBranchPicker` no longer runs `runReadAction { }` on the EDT — the git read happens in `scope.launch` off-EDT, then the popup is built back on the EDT behind an `editTargetLabel.isShowing` guard (the click→popup path is async; the ticket may clear mid-flight). The `editTargetLabel` mouse listener is registered once in `init`, not re-added per `buildActiveState`.

## UI

- `SprintDashboardPanel` — ticket list with detail panel (collapsible sections). Adds a "Saved Filters" section above the sprint list (`SavedFiltersSection`); clicking a filter swaps the list to filter results with a "← Back to sprint" header.
- `TicketStatusBarWidget` — shows active ticket in status bar
- `StartWorkDialog` — branch creation + Jira transition dialog
- `TicketDetectionPopup` — confirmation popup for branch-detected tickets (Set as Active / Dismiss)
- `TransitionDialog` — manual status transitions
- `JiraSearchContributorFactory` — Search Everywhere integration for tickets (single hoisted cell-renderer instance + cached fonts — no per-cell JBLabel allocation; P2-20, 2026-06-10 perf audit)
- `TicketDetailPanel` sections (lazy-loaded, dispose-cascading): `DevStatusSection`, `WorklogSection`, `ChangelogSection` (history feed), `LinkedDocsSection` (Confluence + external links). Watch toggle in the header row. Transition / Comment / Watch / Log-Work buttons are gated by `PermissionGate` against `JiraService.getMyPermissions(projectKey)` — fail-open on API error.
- **UI Overhaul:** SprintPaginationCache (file-based cache at `~/.workflow-orchestrator/`) for sprint pagination state. Cell renderer uses left border accents by status, side-by-side SprintTimeBar, worklog table layout, and PR badge styling in DevStatusSection.

## Transitions

Endpoints:
- `GET /rest/api/2/issue/{key}/transitions?expand=transitions.fields`
- `POST /rest/api/2/issue/{key}/transitions`

Search endpoints (via JiraSearchService):
- `/rest/api/2/user/assignable/search?issueKey=&query=`
- `/rest/api/2/user/search?username=`  ← Server requires `username`, NOT `query` (Cloud-only)
- `/rest/api/1.0/labels/suggest?query=` (404 => empty list fallback)
- `/rest/api/2/groups/picker?query=`
- `/rest/api/2/project/{key}/versions`  (5min cache)
- `/rest/api/2/project/{key}/components`  (5min cache)

UI: `TicketTransitionDialog` + `FieldWidgetFactory` (sealed FieldSchema → widget)
    + `SearchableChooser<T>` (debounced async)
Parser: `JiraTransitionResponseParser` (kotlinx.serialization.json)
Serializer: `TransitionInputSerializer` (FieldValue → Jira JSON)

Post-commit transition trigger statuses are configurable via
`PluginSettings.postCommitTransitionTriggerStatuses` (comma-separated, case-insensitive; default
`to do,open,new,backlog,selected for development`). Parsed by `PostCommitTransitionLogic.parseTriggerStatuses`;
surfaced in `JiraWorkflowConfigurable` → Ticket Transitions group (audit jira:F-14).
