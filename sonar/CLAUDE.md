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
- `GET /api/hotspots/search?project={key}&branch={branch}` — security hotspots (Sonar 25.x+ at all editions; pre-25.x Community returned 404)
- `GET /api/hotspots/show?hotspot={key}` — full hotspot detail (rule.fixRecommendations, riskDescription, vulnerabilityDescription) — used by agent for autonomous remediation. **`canChangeStatus: false` for non-admin tokens** — agent fixes go through code edit + re-analysis, not direct status mutation.
- `GET /api/issues/search?...&facets=...` — facet counts for triage (severities, types, impactSoftwareQualities, files, rules, etc.)
- `GET /api/users/current` — current user identity + global permissions
- `GET /api/qualitygates/list` — all configured gates with caycStatus, isAiCodeSupported, isDefault
- `GET /api/duplications/show?key={fileKey}&branch={branch}` — duplicate code block locations (branch is internal param)

## Architecture

- `SonarApiClient` — HTTP client for SonarQube REST API. `getIssuesWithPaging` loops all pages (≤10 000) for coverage/aggregation paths; `getIssuesSinglePage(page, pageSize, …)` issues ONE request (`&p=&ps=`) for callers that only need one page (e.g. agent `getIssuesPaged`) — avoids fetch-all-then-slice (audit sonar:F-15).
- `SonarMetricKey` (`api/SonarMetricKey.kt`) — canonical metric-key constants. All metric-key literals + CSV `metricKeys=` sets are built from these (`SonarMetricKey.csv(...)`); values are byte-identical to the former literals (audit sonar:F-17).
- `SonarServiceImpl` — implements `SonarService` (in :core), delegates to `SonarDataService`, provides `searchProjects` for the manual project key picker
- `SonarDataService` — `@Service(PROJECT)`, caches state via `StateFlow<SonarState>`, refresh debouncing (500ms). Constructor takes the **platform-injected** `cs: CoroutineScope` (2024.1+ pattern, mirrors core `HealthCheckService`); does NOT allocate or cancel its own scope — the platform cancels `cs` on teardown (audit sonar:F-14).
- `IssueMapper` / `CoverageMapper` — transform DTOs to domain models

## EventBus emissions (T-B1, Phase 7)

`SonarDataService.refreshWith()` emits `WorkflowEvent.QualityGateResult` after every terminal gate
result (OK → `passed=true`, ERROR → `passed=false`). Emissions are **deduplicated**: the event is
only fired when the result changes from the previous terminal state (or on the first terminal result
after a scope change). Non-terminal statuses (`IN_PROGRESS`, `PENDING`, `NONE` / API error) never
emit.

Subscribers:
- `HealthCheckService.SonarGateCheck` — populates the cached Sonar health-check status
- `HandoverStateService.handleEvent` — drives `HandoverState.qualityGatePassed` (Handover tab dot)

The emit is a **side-effect of the existing `QualityDashboardPanel`-driven fetch path**. No new
polling is introduced; if the Quality tab is not visible, no fetch occurs, no emit occurs.

## UI

- `QualityDashboardPanel` — 3 sub-panels: overview, issue list (with detail split pane), coverage table (with preview pane). GateStatusBanner shown when gate fails. **Perf (P1-19/B11/P2-20, 2026-06-10 audit):** the issue-list renderer is allocation-free on the paint path (cached accent borders, badge attributes, parsed creation dates); `CoverageTablePanel`'s model has an equality gate + pre-formatted cell cache, and search is 300 ms-debounced and preserves selection/preview (`CoverageFilterSelection`).
- `IssueDetailPanel` — Split pane detail view: code snippet, rule info, severity/type badges, Fix with AI Agent
- `CoveragePreviewPanel` — Uncovered region preview with file metrics, Open in Editor action. `Disposable` (Phase 4 C5/C5b) — owned by `CoverageTablePanel`, which is also `Disposable`, completing the `QualityDashboardPanel → CoverageTablePanel → CoveragePreviewPanel` cascade via the tool-window `setDisposer` chain (see `:core` "Service & threading conventions").
- `GateStatusBanner` — Full-width error banner for failed quality gate with Show Blocking Issues cross-tab action
- `QualityListItem` — Sealed interface unifying MappedIssue and SecurityHotspotData for the issues list
- `SonarIssueAnnotator` — `ExternalAnnotator` (3-phase async: collectInfo -> doAnnotate -> apply)
- `CoverageLineMarkerProvider` — gutter markers for coverage (on-demand fetch via getSourceLines)
  - Colors: red (blocker/critical), yellow (major), grey (minor)
  - Sizes: 12x12 Classic UI, 14x14 New UI
  - **Perf (P1-18/B21, 2026-06-10 audit):** per-file header data (settings/repo/branch resolution) is cached in `CoverageFileHeaderCache` — invalidated via `SonarDataService.clearLineCoverageCache` and project close; partially-resolved headers (blank projectKey / null branch) are never cached. Exactly one marker per line via `CoverageMarkerEmitGate` (first non-whitespace leaf on the line).
- `CoverageBannerProvider` — `EditorNotificationProvider` for low-coverage files
- `CoverageTreeDecorator` — `ProjectViewNodeDecorator` for coverage badges
- `CoverageDiffExtension` — diff view coverage highlighting
- `SonarGlobalInspectionTool` — global inspection for batch analysis
- `SonarProjectPickerDialog` — searchable project key selector
- **UI Overhaul:** Accent-colored overview cards for metrics, uppercase table headers in issue/coverage tables, outline severity badges for issue priorities.
