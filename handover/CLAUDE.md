# :handover Module

Task completion workflow: Jira closure, copyright enforcement, pre-review, QA handover.

## Architecture

- `JiraClosureService` — posts rich-text closure comment to Jira (docker tags + test results + links)
- `HandoverJiraClient` — Jira REST API client for comments and worklogs
- `TimeTrackingService` — time logging with worklog dialog
- `PreReviewService` — Cody-powered diff analysis before PR review
- `CopyrightFixService` — copyright header enforcement with year consolidation (earliest-currentYear)
- `QaClipboardService` — formatted export for email/Slack
- `CompletionMacroService` — orchestrates completion steps
- `HandoverStateService` — tracks handover progress across panels

## UI

- `HandoverPanel` — main container with toolbar and context panel
- `HandoverContextPanel` — shows active ticket + PR context
- Sub-panels: `JiraCommentPanel`, `TimeLogPanel`, `CopyrightPanel`, `PreReviewPanel`, `QaClipboardPanel`, `CompletionMacroPanel`
- **UI Overhaul:** HandoverContextPanel uses vertical nav sidebar for step navigation. Accent-line detail panel headers, tonal architecture (subtle background tints per section).

## Workflow

No single "Complete Task" button. The real workflow is sequential:
PR creation -> Bamboo builds -> docker tags -> automation suites -> QA handover.
Each step depends on the previous one completing.
