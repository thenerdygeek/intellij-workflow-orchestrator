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
- `BASIC` — Nexus
- `TOKEN` — Sourcegraph

Includes `RetryInterceptor` on all clients.

## SmartPoller

Activity-aware polling: `baseIntervalMs` (default 30s), `maxIntervalMs` (default 300s). Backoff 1.5x on no-change, resets on change. Jitter +/-10%. Visibility gating (4x interval when IDE unfocused or tab hidden).

## Settings

- `ConnectionSettings` — application-level (shared across projects): service URLs, Nexus username
- `PluginSettings` — project-level: plan keys, polling intervals, feature toggles, docker tag key, sonar project key

## CredentialStore

Wraps `PasswordSafe`. Keys scoped by `ServiceType`. All tokens stored here, never in `workflowOrchestrator.xml`.

## StatusColors

JBColor constants with light/dark variants: SUCCESS (green), ERROR (red), WARNING (amber), INFO (grey), LINK (blue), MERGED (purple), SECONDARY_TEXT (dim grey), BORDER, CARD_BG, HIGHLIGHT_BG, WARNING_BG, SUCCESS_BG, INFO_BG. Includes `htmlColor(JBColor): String` utility for HTML rendering.
