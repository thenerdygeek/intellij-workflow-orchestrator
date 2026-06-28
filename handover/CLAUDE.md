# :handover Module

> **Phase 2b (2026-06-27):** `:handover` is carved into Plugin B. It stays a top-level Gradle
> module (bundled by `:plugin-b` with `isTransitive=false`, kover coverage retained). Plugin A
> does NOT ship it. Key moves in this carve:
> - `CopyrightFixService` + `CopyrightFileEntry`/`CopyrightStatus` moved to `:core/copyright`
>   (generic year-logic stays in A; the company copyright template is a blank `:core` setting
>   that B will seed in Phase 2c).
> - `HandoverConfigurable` moved INTO `:handover` (was `:core/settings`); now at
>   `handover.settings.HandoverConfigurable`; registered by B's `plugin.xml`.
> - Dead `PreReviewService` dropped.
> - Generic `PrService` stays in A (`:core.bitbucket`), relocated out of the handover block.
> - "Handover" default tab removed from A's `WorkflowToolWindowFactory.defaultTabs`; B registers
>   `HandoverTabProvider` via the `workflowTab` EP.
> - `quickClipboardChips` docker defaults still in `:core` `PluginSettings` — deferred to Phase 2c
>   (blank in A + seed in B preset together).
> - Git4Idea is `compileOnly` for `:handover` (runtime uses platform `ChangeListManager`); B needs
>   no extra Git4Idea runtime dep — PENDING-USER runIde smoke to confirm.
> - B's `plugin.xml` registers the tab, project/app services, settings configurable, and startup
>   activities for this module.

Task completion workflow: Jira closure, copyright enforcement, QA handover.

Spec: docs/superpowers/specs/2026-05-08-handover-tab-redesign-design.md

## Architecture

- `JiraClosureService` — formats wiki-markup closure comments from suite results; calls `JiraService.addComment` via panel action. No project state; all Jira mutations route through `:core` `JiraService`.
- `HandoverStateService` — tracks handover progress across panels. Subscribes to:
  - `WorkflowContextService.activeTicketFlow` — ticket changes; full state reset on new ticket.
  - `WorkflowContextService.state.map { it.focusPr }.distinctUntilChanged()` — **focusPr changes** (Phase 7 T-Handover-a); resets status slices only (see below).
  - `EventBus` — build/quality/health/automation/PR/comment events, all **PR-scope filtered** (see `isInScope()`).
  - Uses platform-injected `cs: CoroutineScope` (see `:core` "Service & threading conventions").
- `TimeTrackingService` — time logging with worklog dialog.
- `CopyrightFixService` — copyright header enforcement with year consolidation (earliest-currentYear).

### Phase 7 T-Handover-a: PR-scope event filtering and focus-change reset

**Architecture decision — Option C (EventBus with PR-scope filtering):** `:handover` depends only on `:core` and cannot import `BuildMonitorService` (`:bamboo`) or `QueueService` (`:automation`) directly — a direct cross-module dependency violates the module-graph rule. Status hydration therefore uses the existing `EventBus` with focus-anchored scope filtering. Bridging via extension points (Option A) is deferred if needed; it is out of scope for T-Handover-a.

**Focus-change reset semantics:** when `focusPr` changes (even within the same ticket), `HandoverStateService` clears all *status-derived* slices:

| Slice | Reset on focusPr change? | Reason |
|---|---|---|
| `buildStatus` | Yes | Build is PR-specific |
| `qualityGatePassed` | Yes | Quality gate is PR-specific |
| `healthCheckPassed` | Yes | Health check is run-specific |
| `suiteResults` | Yes | Suite runs are triggered per PR |
| `prCreated` | Yes | Tracks this PR's creation |
| `prUrl` | Yes | Tracks this PR's URL |
| `jiraCommentPosted` | Yes | Closure comment is per-handover action |
| `copyrightFixed` | **No** | Ticket-level action — performed once per ticket |
| `todayWorkLogged` | **No** | Ticket-level action — performed once per ticket |
| `jiraTransitioned` | **No** | Ticket-level action — performed once per ticket |

**PR-scope filter (`isInScope()`):** applied before every `handleEvent` call. Filtering rules:

| Event | Filter key |
|---|---|
| `BuildFinished` | `event.planKey == focusBuild.planKey` — branch-level match not possible (no branch field in event) |
| `QualityGateResult` | `event.projectKey == focusQualityScope.sonarProjectKey` |
| `PullRequestCreated` | `event.ticketId == activeTicket.key` |
| `JiraCommentPosted` | `event.ticketId == activeTicket.key` |
| `HealthCheckFinished` | always accepted — no key in payload |
| `AutomationTriggered` / `AutomationFinished` | always accepted — no direct chainKey link to `focusBuild` (limitation; see queue item #3 in phase7-handover-context-plan.md) |

All `@Service(Service.Level.PROJECT)` classes have a `(Project)` IntelliJ-DI constructor and a no-arg constructor annotated `@TestOnly`. Production DI never picks the no-arg form; tests use it to avoid spinning up a `BasePlatformTestCase` for pure-logic assertions.

Historical note: `QaClipboardService`, `HandoverContextPanel` (sidebar), `HandoverToolbar`, `QaClipboardPanel`, `JiraCommentPanel`, and `PanelHeaders` were removed in the Handover-tab redesign (T26). The macro panel + Handover-side AI Pre-Review were removed earlier; the PR-tab `AiReviewTabPanel` covers pre-review.

### Phase 7 T-Handover-c: Placeholder source-of-truth audit (DONE)

**Audit result:** `HandoverPlaceholderResolver` correctly routes all placeholder reads. No identity drift in the resolver itself.

**Placeholder source-of-truth inventory:**

| Placeholder key | Source | Kind |
|---|---|---|
| `ticket.id` | `ctx.activeTicket?.key` (`WorkflowContextService.state`) | Identity |
| `ticket.summary` | `ctx.activeTicket?.summary` (`WorkflowContextService.state`) | Identity |
| `ticket.status` | `state.currentStatusName` (`HandoverStateService.stateFlow`) | Action-outcome |
| `pr.id` | `ctx.focusPr?.prId` (`WorkflowContextService.state`) | Identity |
| `pr.url` | `state.prUrl` (`HandoverStateService.stateFlow`) — set via `PullRequestCreated` event | Status/action |
| `build.url` | always unavailable — `BuildSummary` has no URL field | — |
| `docker.tag` | `state.suiteResults.last().dockerTagsJson` (`HandoverStateService.stateFlow`) | Status |
| `docker.tagsJson` | `state.suiteResults.last().dockerTagsJson` (`HandoverStateService.stateFlow`) | Status |
| `automation.suiteTable` | `state.suiteResults` (`HandoverStateService.stateFlow`) | Status |
| `ai.changeSummary` | `HandoverAiSummaryCache.changeSummary()` (uses `WorkflowContext` internally) | AI |
| `ai.ticketSummary` | `HandoverAiSummaryCache.ticketSummary()` (uses `WorkflowContext` internally) | AI |

**Drift fixed in `ShareTab.kt`:** two fallback reads of the stale `HandoverState.ticketId` mirror were replaced with canonical `WorkflowContextService.state` reads:

- `emitOverrideIfNeeded()` (line ~184): `ctx.activeTicket?.key?.takeIf { it.isNotBlank() } ?: state.ticketId` → `ctx.activeTicket?.key?.takeIf { it.isNotBlank() }.orEmpty()`
- `resolveTicketId()` (line ~203): `ctx.activeTicket?.key?.takeIf { it.isNotBlank() } ?: handoverStateFlow.value.ticketId` → `workflowContextFlow.value.activeTicket?.key?.takeIf { it.isNotBlank() }.orEmpty()`

**ShareTab subscription audit:** `ShareTab` receives both `StateFlow<HandoverState>` and `StateFlow<WorkflowContext>` at construction time. It reads both flows at action time (not via collect subscriptions) — this is correct for action handlers (read snapshot at invocation). Preview re-rendering on context change is driven by `TemplateEditorCard`'s `onSourceChanged()` → `resolveMarkup()` path which calls `HandoverPlaceholderResolver.resolve()` on each keystroke/template-select; the resolver always snapshots `workflowContext.state.value` at call time. No stale subscription.

**Tests added:** five focus-change regression tests in `HandoverPlaceholderResolverTest`:
- `focus-change from PR-A to PR-B — ticket-id placeholder reflects new context`
- `focus-change from PR-A to PR-B — ticket-summary placeholder reflects new context`
- `focus-change from PR-A to PR-B — pr-id placeholder reflects new PR`
- `focus-change clears focused PR — pr-id becomes unavailable`
- `focus-change clears active ticket — ticket-id becomes unavailable`

**Tests updated:** two existing `ShareTabTest` tests now pass `workflowContext = WorkflowContext(activeTicket = TicketRef(...))` to the `buildTab()` helper, replacing the previous implicit reliance on the removed `HandoverState.ticketId` fallback.

## UI

`HandoverPanel` hosts a `JBTabbedPane` with three tabs and a persistent override banner (`HandoverOverrideBanner`):

- **Checks tab** (`ChecksTab`) — status checks grid + ritual checklist with colored-dot progress indicators.
- **Actions tab** (`ActionsTab`) — composes `CopyrightFixCard` (rescan + fix-all copyright headers) and `TimeLogCard` (log work to Jira with date/hours stepper).
- **Share tab** (`ShareTab`) — composes Jira and Email `TemplateEditorCard`s plus `QuickValueChipsPanel` for one-click QA copy.

Card header helper (`handoverPanelHeader`) lives in `ui/cards/CardPanelHeader.kt` (moved from the deleted `ui/panels/PanelHeaders.kt`).

## Wire-up status

| Component | Actions wired |
|---|---|
| `CopyrightFixCard` | Rescan walks `ChangeListManager.allChanges` → `CopyrightFixService.analyzeFile`. Fix All applies year-consolidation / template-insertion in a single `WriteCommandAction` (one-step undo). On success, flips `markCopyrightFixed()`. File list uses a rubber-stamp cell renderer — widgets allocated once, ALL per-row properties reset per call (P2-20, 2026-06-10 perf audit). |
| `TimeLogCard` | Log Work calls `JiraService.logWork(ticketId, timeSpent, started)` via `TimeTrackingService.hoursToJiraTimeString`. Live validation on hours/date. On success, flips `markWorkLogged()` and disables button to discourage double-log. |
| `ChecksTab` | Driven by `HandoverStateService.stateFlow`; dots update on every state emission via `Dispatchers.EDT`. Checklist rows rebuild only when the four checklist flags actually change (P2-20 equality gate, 2026-06-10 perf audit). |
| `ShareTab` | `TemplateEditorCard`s resolve placeholders from `HandoverPlaceholderResolver`; `QuickValueChipsPanel` copies formatted text to clipboard. |
| `HandoverOverrideBanner` | Persisted via `HandoverOverrideTracker`; shown when active ticket diverges from branch. |

## Workflow

The handover workflow is sequential — each step depends on the previous one:
PR creation → Bamboo builds → docker tags → automation suites → QA handover.

Ticket transitions are handled from the Sprint tab's `TicketTransitionDialog` — not duplicated here.
