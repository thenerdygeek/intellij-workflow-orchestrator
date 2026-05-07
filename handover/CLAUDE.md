# :handover Module

Task completion workflow: Jira closure, copyright enforcement, pre-review, QA handover.

## Architecture

- `JiraClosureService` — formats wiki-markup closure comments from suite results; calls `JiraService.addComment` via panel action. No project state; all Jira mutations route through `:core` `JiraService`.
- `HandoverStateService` — tracks handover progress across panels. Subscribes to `WorkflowContextService.activeTicketFlow` (ticket changes) and `EventBus` (build/automation/PR events). Uses platform-injected `cs: CoroutineScope` (see `:core` "Service & threading conventions").
- `TimeTrackingService` — time logging with worklog dialog
- `PreReviewService` — AI-powered diff analysis before PR review (uses PsiContextEnricher from :core)
- `CopyrightFixService` — copyright header enforcement with year consolidation (earliest-currentYear)
- `QaClipboardService` — formatted export for email/Slack

All five services are `@Service(Service.Level.PROJECT)` with a `(Project)` IntelliJ-DI constructor and a no-arg constructor annotated `@TestOnly` (Phase 5 / audit H-P1-2). Production DI never picks the no-arg form; tests use it to avoid spinning up a `BasePlatformTestCase` for pure-logic assertions.

## UI

- `HandoverPanel` — main container with toolbar, context sidebar, and detail card panels. Subscribes to `HandoverStateService.stateFlow` on `Dispatchers.IO`; fans state out to each wired panel on `Dispatchers.EDT`.
- `HandoverContextPanel` — shows active ticket + PR context sidebar with vertical nav (Context, PR Details, Builds, Quality, Docker, Suites) and a bottom CHECKLIST section with colored dots.
- Sub-panels: `JiraCommentPanel` (wired — Phase 1), `CopyrightPanel` (wired — Phase 2), `QaClipboardPanel` (wired — Phase 2), `TimeLogPanel` (wired — Phase 3), `PreReviewPanel`

## Wire-up status (post-Phase 3)

| Panel | Status | Actions wired |
|---|---|---|
| `JiraCommentPanel` | **WIRED** | Post Comment button calls `JiraService.addComment`; emits `JiraCommentPosted`; flips checklist dot |
| `CopyrightPanel` | **WIRED** | Rescan walks `ChangeListManager.allChanges` → `CopyrightFixService.analyzeFile`. Fix All applies year-consolidation / template-insertion in a single `WriteCommandAction` (one-step undo). Template lives in `PluginSettings.copyrightTemplate` (UI: Builds & Health Checks → Advanced). On success, flips `markCopyrightFixed()`; partial failures surface via `WorkflowNotificationService`. Custom cell renderer shows status icon + path + year transition. |
| `QaClipboardPanel` | **WIRED** | `HandoverPanel`'s state-flow collector calls `setDockerTags` + `setFormattedText` from `QaClipboardService.buildPayloadFromSuiteResults` on every state emission. Copy-All / per-row Copy already worked. `addServiceButton` deleted. |
| `TimeLogPanel` | **WIRED** | Log Work button calls `JiraService.logWork(ticketId, timeSpent, comment)` with `timeSpent` formatted as Jira's `"Nh Mm"` form via `TimeTrackingService.hoursToJiraTimeString`. State-flow collector drives `setTicket` (form↔empty) and `setStartedTimestamp` (Suggested-hours hint). Live validation on hours/date edits. On success, flips `markWorkLogged()` and disables the button to discourage double-log. |
| `PreReviewPanel` | pending (Phase 4) | — |

## Workflow

The handover workflow is sequential — each step depends on the previous one:
PR creation → Bamboo builds → docker tags → automation suites → QA handover.

Ticket transitions are handled from the Sprint tab's `TicketTransitionDialog` — not duplicated here.
