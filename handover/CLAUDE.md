# :handover Module

Task completion workflow: Jira closure, copyright enforcement, pre-review, QA handover.

## Architecture

- `JiraClosureService` — formats wiki-markup closure comments from suite results; calls `JiraService.addComment` via panel action. No project state; all Jira mutations route through `:core` `JiraService`.
- `HandoverStateService` — tracks handover progress across panels. Subscribes to `WorkflowContextService.activeTicketFlow` (ticket changes) and `EventBus` (build/automation/PR events). Uses platform-injected `cs: CoroutineScope` (see `:core` "Service & threading conventions").
- `TimeTrackingService` — time logging with worklog dialog
- `PreReviewService` — AI-powered diff analysis before PR review (uses PsiContextEnricher from :core)
- `CopyrightFixService` — copyright header enforcement with year consolidation (earliest-currentYear)
- `QaClipboardService` — formatted export for email/Slack

## UI

- `HandoverPanel` — main container with toolbar, context sidebar, and detail card panels. Subscribes to `HandoverStateService.stateFlow` on `Dispatchers.IO`; fans state out to each wired panel on `Dispatchers.EDT`.
- `HandoverContextPanel` — shows active ticket + PR context sidebar with vertical nav (Context, PR Details, Builds, Quality, Docker, Suites) and a bottom CHECKLIST section with colored dots.
- Sub-panels: `JiraCommentPanel` (wired — Phase 1), `TimeLogPanel`, `CopyrightPanel`, `PreReviewPanel`, `QaClipboardPanel`

## Wire-up status (post-Phase 1)

| Panel | Status | Actions wired |
|---|---|---|
| `JiraCommentPanel` | **WIRED** | Post Comment button calls `JiraService.addComment`; emits `JiraCommentPosted`; flips checklist dot |
| `CopyrightPanel` | pending (Phase 2) | — |
| `QaClipboardPanel` | pending (Phase 2) | — |
| `TimeLogPanel` | pending (Phase 3) | — |
| `PreReviewPanel` | pending (Phase 4) | — |

## Workflow

The handover workflow is sequential — each step depends on the previous one:
PR creation → Bamboo builds → docker tags → automation suites → QA handover.

Ticket transitions are handled from the Sprint tab's `TicketTransitionDialog` — not duplicated here.
