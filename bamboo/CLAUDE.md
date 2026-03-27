# :bamboo Module

Build monitoring, CI feedback, CVE remediation, and PR creation.

## Bamboo Server REST API

Auth: `Authorization: Bearer <PAT>`
Base: `https://{host}/rest/api/latest/`

Key endpoints:
- `GET /rest/api/latest/currentUser` — test connection
- `GET /rest/api/latest/result/{planKey}/latest` — latest build result
- `GET /rest/api/latest/result/{buildKey}` — specific build result + stages
- `GET /rest/api/latest/result/{buildKey}/log` — build log
- `POST /rest/api/latest/queue/{planKey}` — trigger build (with variables)
- `GET /rest/api/latest/result/{planKey}` — running/queued builds

Build variables include `dockerTagsAsJson` — JSON payload of service-to-docker-tag mappings used by automation suites.

## Architecture

- `BambooApiClient` — HTTP client for all Bamboo REST calls
- `BambooServiceImpl` — implements `BambooService` (in :core), returns `ToolResult<T>`
- `BuildMonitorService` — background polling via SmartPoller, emits `BuildFinished`/`BuildLogReady` events
- `BuildLogParser` — extracts errors, test failures, CVE warnings from build logs
- `CveRemediationService` — parses CVE data, provides version bump suggestions
- `PlanDetectionService` — auto-detects Bamboo plan key from project

## UI

- `BuildDashboardPanel` — build list + stage detail + log viewer
- `BuildStatusBarWidget` — build status in status bar
- `BuildStatusNodeDecorator` — project tree build status badges
- `CveAnnotator` / `CveIntentionAction` — inline CVE warnings + auto-fix in pom.xml
- `BambooBuildConfigurationType` — run configuration for manual stage triggers
- **UI Overhaul:** StitchLeftAccentBorder utility for status-colored left borders on cards. Uppercase section headers, monospace build numbers in cell renderers.
