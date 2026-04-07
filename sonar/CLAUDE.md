# :sonar Module

Code quality visibility: coverage markers, inline issue warnings, quality gate status.

## SonarQube Web API

Auth: `Authorization: Bearer <user-token>` (SonarQube uses user tokens, not OAuth)

Key endpoints:
- `GET /api/authentication/validate` — test connection
- `GET /api/components/search?qualifiers=TRK&q={query}` — search projects (for picker)
- `GET /api/project_branches/list?project={key}` — list branches
- `GET /api/qualitygates/project_status?projectKey={key}&branch={branch}` — quality gate status
- `GET /api/issues/search?componentKeys={key}&resolved=false` — open issues (supports branch, filePath, inNewCodePeriod filters)
- `GET /api/measures/component_tree?component={key}&metricKeys=...` — coverage + code metrics
- `GET /api/ce/activity?component={key}` — compute engine activity (analysis status)
- `GET /api/new_code_periods/show?project={key}` — new code period definition
- `GET /api/sources/lines?key={fileKey}&from={line}&to={line}&branch={branch}` — source lines with coverage data (branch is internal param)
- `GET /api/hotspots/search?project={key}&branch={branch}` — security hotspots (Developer Edition+)
- `GET /api/duplications/show?key={fileKey}&branch={branch}` — duplicate code block locations (branch is internal param)

## Architecture

- `SonarApiClient` — HTTP client for SonarQube REST API
- `SonarServiceImpl` — implements `SonarService` (in :core), delegates to `SonarDataService`, provides `searchProjects` for the manual project key picker
- `SonarDataService` — caches state via `StateFlow<SonarState>`, refresh debouncing (500ms)
- `IssueMapper` / `CoverageMapper` — transform DTOs to domain models

## UI

- `QualityDashboardPanel` — 3 sub-panels: overview, issue list (with detail split pane), coverage table (with preview pane). GateStatusBanner shown when gate fails.
- `IssueDetailPanel` — Split pane detail view: code snippet, rule info, severity/type badges, Fix with AI Agent
- `CoveragePreviewPanel` — Uncovered region preview with file metrics, Open in Editor action
- `GateStatusBanner` — Full-width error banner for failed quality gate with Show Blocking Issues cross-tab action
- `QualityListItem` — Sealed interface unifying MappedIssue and SecurityHotspotData for the issues list
- `SonarIssueAnnotator` — `ExternalAnnotator` (3-phase async: collectInfo -> doAnnotate -> apply)
- `CoverageLineMarkerProvider` — gutter markers for coverage (on-demand fetch via getSourceLines)
  - Colors: red (blocker/critical), yellow (major), grey (minor)
  - Sizes: 12x12 Classic UI, 14x14 New UI
- `CoverageBannerProvider` — `EditorNotificationProvider` for low-coverage files
- `CoverageTreeDecorator` — `ProjectViewNodeDecorator` for coverage badges
- `CoverageDiffExtension` — diff view coverage highlighting
- `SonarGlobalInspectionTool` — global inspection for batch analysis
- `SonarProjectPickerDialog` — searchable project key selector
- **UI Overhaul:** Accent-colored overview cards for metrics, uppercase table headers in issue/coverage tables, outline severity badges for issue priorities.
