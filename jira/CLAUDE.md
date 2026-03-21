# :jira Module

Sprint dashboard, ticket management, branching, and commit integration.

## Jira Server REST API v2

Auth: `Authorization: Bearer <PAT>`
Base: `https://{host}/rest/api/2/`

Key endpoints:
- `GET /rest/api/2/myself` — test connection
- `GET /rest/agile/1.0/board/{boardId}/sprint/{sprintId}/issue` — sprint tickets (filter: assignee=currentUser())
- `GET /rest/api/2/issue/{key}?expand=issuelinks` — ticket with links
- `POST /rest/api/2/issue/{key}/transitions` — transition status
- `POST /rest/api/2/issue/{key}/comment` — add comment (wiki markup)
- `POST /rest/api/2/issue/{key}/worklog` — log time

## Architecture

- `JiraApiClient` — HTTP client with DTO deserialization
- `JiraServiceImpl` — implements `JiraService` (in :core), wraps JiraApiClient, returns `ToolResult<T>`
- `SprintService` — sprint dashboard logic, supports scrum (sprint-based) + kanban (board-based) modes
- `ActiveTicketService` — tracks current ticket, emits `TicketChanged` events
- `BranchingService` — Start Work flow: creates branch on Bitbucket + transitions Jira ticket
- `CommitPrefixService` — auto-prefixes commit messages with ticket ID
- `BranchChangeTicketDetector` — detects ticket from branch name on branch switch, shows confirmation popup (dismissed branches tracked in-memory)

## UI

- `SprintDashboardPanel` — ticket list with detail panel (collapsible sections)
- `TicketStatusBarWidget` — shows active ticket in status bar
- `StartWorkDialog` — branch creation + Jira transition dialog
- `TicketDetectionPopup` — confirmation popup for branch-detected tickets (Set as Active / Dismiss)
- `TransitionDialog` — manual status transitions
- `JiraSearchContributorFactory` — Search Everywhere integration for tickets
