# :pullrequest Module

PR dashboard with list, detail view, and merge actions via Bitbucket Server.

## Bitbucket Server REST API v1.0

Auth: `Authorization: Bearer <HTTP-access-token>`
Base: `https://{host}/rest/api/1.0/`

Key endpoints:
- `GET /rest/api/1.0/users` — test connection
- `GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests` — list PRs
  - **Note:** requires both `role.1` and `username.1` parameters for author filtering
- `GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}` — PR detail
- `PUT /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}` — update PR
  - **PUT replaces entire PR** — always fetch current state first to preserve title/reviewers
- `GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/merge` — merge preconditions (canMerge + vetoes)
- `POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/merge` — merge PR
  - Merge strategies: `strategyId` in POST body (e.g., `no-ff`, `squash`, `rebase-no-ff`)
- `POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests/{id}/approve` — approve PR
- `POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests` — create PR

## Architecture

- `BitbucketServiceImpl` — implements `BitbucketService` (in :core), returns `ToolResult<T>`
- `PrListService` — fetches and caches PR list, supports role-based filtering
- `PrDetailService` — fetches PR detail with activities/diff
- `PrActionService` — merge, approve, decline, update operations

## UI

- `PrDashboardPanel` — split view: PR list + detail panel
- `PrListPanel` — filterable PR list with status indicators
- `PrDetailPanel` — PR metadata, reviewers, merge status, action buttons
