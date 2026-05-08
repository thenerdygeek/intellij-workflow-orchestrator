# :handover Module

Task completion workflow: Jira closure, copyright enforcement, QA handover.

Spec: docs/superpowers/specs/2026-05-08-handover-tab-redesign-design.md

## Architecture

- `JiraClosureService` — formats wiki-markup closure comments from suite results; calls `JiraService.addComment` via panel action. No project state; all Jira mutations route through `:core` `JiraService`.
- `HandoverStateService` — tracks handover progress across panels. Subscribes to `WorkflowContextService.activeTicketFlow` (ticket changes) and `EventBus` (build/automation/PR events). Uses platform-injected `cs: CoroutineScope` (see `:core` "Service & threading conventions").
- `TimeTrackingService` — time logging with worklog dialog.
- `CopyrightFixService` — copyright header enforcement with year consolidation (earliest-currentYear).

All `@Service(Service.Level.PROJECT)` classes have a `(Project)` IntelliJ-DI constructor and a no-arg constructor annotated `@TestOnly`. Production DI never picks the no-arg form; tests use it to avoid spinning up a `BasePlatformTestCase` for pure-logic assertions.

Historical note: `QaClipboardService`, `HandoverContextPanel` (sidebar), `HandoverToolbar`, `QaClipboardPanel`, `JiraCommentPanel`, and `PanelHeaders` were removed in the Handover-tab redesign (T26). The macro panel + Handover-side AI Pre-Review were removed earlier; the PR-tab `AiReviewTabPanel` covers pre-review.

## UI

`HandoverPanel` hosts a `JBTabbedPane` with three tabs and a persistent override banner (`HandoverOverrideBanner`):

- **Checks tab** (`ChecksTab`) — status checks grid + ritual checklist with colored-dot progress indicators.
- **Actions tab** (`ActionsTab`) — composes `CopyrightFixCard` (rescan + fix-all copyright headers) and `TimeLogCard` (log work to Jira with date/hours stepper).
- **Share tab** (`ShareTab`) — composes Jira and Email `TemplateEditorCard`s plus `QuickValueChipsPanel` for one-click QA copy.

Card header helper (`handoverPanelHeader`) lives in `ui/cards/CardPanelHeader.kt` (moved from the deleted `ui/panels/PanelHeaders.kt`).

## Wire-up status

| Component | Actions wired |
|---|---|
| `CopyrightFixCard` | Rescan walks `ChangeListManager.allChanges` → `CopyrightFixService.analyzeFile`. Fix All applies year-consolidation / template-insertion in a single `WriteCommandAction` (one-step undo). On success, flips `markCopyrightFixed()`. |
| `TimeLogCard` | Log Work calls `JiraService.logWork(ticketId, timeSpent, started)` via `TimeTrackingService.hoursToJiraTimeString`. Live validation on hours/date. On success, flips `markWorkLogged()` and disables button to discourage double-log. |
| `ChecksTab` | Driven by `HandoverStateService.stateFlow`; dots update on every state emission via `Dispatchers.EDT`. |
| `ShareTab` | `TemplateEditorCard`s resolve placeholders from `HandoverPlaceholderResolver`; `QuickValueChipsPanel` copies formatted text to clipboard. |
| `HandoverOverrideBanner` | Persisted via `HandoverOverrideTracker`; shown when active ticket diverges from branch. |

## Workflow

The handover workflow is sequential — each step depends on the previous one:
PR creation → Bamboo builds → docker tags → automation suites → QA handover.

Ticket transitions are handled from the Sprint tab's `TicketTransitionDialog` — not duplicated here.
