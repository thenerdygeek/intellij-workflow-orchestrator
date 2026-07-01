# SonarQube Branch Analysis, New Code, and Compute Engine API Research

Date: 2026-03-20

## 1. How Bamboo Tells SonarQube Which Branch to Analyze

### Scanner Properties

- **`sonar.branch.name`** — The branch being analyzed (visible in SonarQube UI). Required: Developer Edition or above.
- **`sonar.branch.target`** — DEPRECATED and REMOVED since SonarQube 8.0. No longer used. Do NOT pass this property.
- **`sonar.newCode.referenceBranch`** — Replacement for target branch comparison. Specifies which branch to compare against for new code calculation. Overrides global and project-level new code definitions.

### How Scanner Properties Are Passed

Via Maven: `-Dsonar.branch.name=feature/PROJ-123 -Dsonar.newCode.referenceBranch=main`
Via Gradle: `-Psonar.branch.name=feature/PROJ-123`
Via SonarScanner: as CLI args or in `sonar-project.properties`

### Bamboo Integration

Two approaches:
1. **Maven/Gradle sonar goal** — Pass branch properties as build variables. Bamboo exposes `bamboo_planRepository_1_branchName` which can be used in the Maven goal configuration: `-Dsonar.branch.name=${bamboo.planRepository.1.branchName}`
2. **"Include Code Quality for Bamboo" plugin** (Marketplace) — Automatically sets sonar.branch.name from the Bamboo plan branch. Supports auto-detection of branch parameters.

### Can We Read Branch Info from Bamboo REST API?

- Bamboo plan variables are accessible via `GET /rest/api/latest/result/{buildKey}?expand=variables`
- The branch name is in `bamboo.planRepository.1.branchName` (system variable)
- Custom sonar properties would only be visible if set as plan/build variables
- Better approach: Query SonarQube's CE API directly to find which branch was analyzed

### CI Auto-Detection

SonarQube scanners auto-detect branch parameters on: Azure Pipelines, Bitbucket Pipelines, Cirrus CI, Codemagic, GitHub Actions, GitLab CI/CD, Jenkins (with Branch Source plugin). Bamboo is NOT in this auto-detect list — branch name must be configured manually.

## 2. How SonarQube "New Code" Works

### New Code Definition Types

Configured via `api/new_code_periods/set` or SonarQube UI:

| Type | Description |
|------|-------------|
| PREVIOUS_VERSION | New code since the last version change (from pom.xml/build.gradle) |
| NUMBER_OF_DAYS | New code from the last N days |
| REFERENCE_BRANCH | New code = diff between current branch and reference branch |
| SPECIFIC_ANALYSIS | New code since a specific past analysis (API only) |

### How REFERENCE_BRANCH Works

- Compares current state of the analyzed branch against current state of the reference branch
- Uses SCM data (git) obtained during analysis
- Requirements: same branch name in SCM and SonarQube, reference branch must be fetched in CI local repo, local repo must match remote
- This is the recommended approach for feature branches (compare against main/develop)

### Default Behavior for Branches

- By default, branches inherit the project's new code definition
- Can be overridden per-branch via UI or API
- `sonar.newCode.referenceBranch` scanner property overrides everything (useful for first analysis of a new branch)

### `inNewCodePeriod=true` on Issues API

- `GET /api/issues/search?componentKeys={key}&branch={branch}&inNewCodePeriod=true`
- Returns only issues where one or more primary/secondary locations fall on lines that are in the new code period
- "New code" = lines added or modified compared to the new code definition (reference branch, previous version, etc.)
- Renamed/moved files: issues on those files are considered new if the lines were modified

### New Coverage Metrics

| Metric Key | Description |
|------------|-------------|
| `new_coverage` | Same formula as `coverage` but restricted to new/updated lines only. Formula: (CT + LC) / (B + EL) |
| `new_lines_to_cover` | Coverable lines in newly added/modified code |
| `new_uncovered_lines` | Lines in new code not executed by tests |
| `new_line_coverage` | Line coverage restricted to new code |
| `new_branch_coverage` | Condition/branch coverage restricted to new code |
| `new_uncovered_conditions` | Uncovered boolean conditions in new code |

### Per-File New Coverage via API

```
GET /api/measures/component_tree?component={projectKey}&branch={branchName}&metricKeys=new_coverage,new_lines_to_cover,new_uncovered_lines&qualifiers=FIL
```

- The `branch` parameter specifies which branch to query
- If the branch hasn't been analyzed, the API returns an error (branch not found)
- Returns measures for each file in the component tree

### "New" vs "Overall" Metrics

- "New" metrics (prefixed `new_`) apply only to lines added/modified during the new code period
- "Overall" metrics apply to the entire codebase
- "New" metrics are what quality gates typically enforce (Clean as You Code)

## 3. SonarQube Compute Engine API

### GET /api/ce/component

Get pending tasks, in-progress tasks, and the last executed task of a given component.

Parameters:
- `component` (required) — project key

Response structure:
```json
{
  "queue": [],        // pending and in-progress tasks
  "current": {        // last executed task
    "id": "...",
    "type": "REPORT",
    "componentId": "...",
    "componentKey": "project_key",
    "componentName": "Project Name",
    "componentQualifier": "TRK",
    "analysisId": "...",
    "status": "SUCCESS",
    "submittedAt": "2025-10-02T11:32:15+0200",
    "startedAt": "2025-10-02T11:32:16+0200",
    "executedAt": "2025-10-02T11:32:22+0200",
    "executionTimeMs": 5286,
    "logs": false,
    "hasScannerContext": true
  }
}
```

NOTE: `api/ce/component` returns the LAST task for the component. It may not include branch info in older versions. Use `api/ce/activity` for branch-specific task history.

### GET /api/ce/task?id={taskId}

Get details of a specific CE task.

Parameters:
- `id` (required) — task ID
- `additionalFields` — comma-separated: scannerContext, stacktrace, warnings

Response (confirmed from source code examples):
```json
{
  "task": {
    "id": "AVAn5RKqYwETbXvgas-I",
    "type": "REPORT",
    "componentId": "...",
    "componentKey": "project_key",
    "componentName": "Project Name",
    "componentQualifier": "TRK",
    "analysisId": "123456",
    "status": "FAILED",
    "submittedAt": "2025-10-02T11:32:15+0200",
    "startedAt": "2025-10-02T11:32:16+0200",
    "executedAt": "2025-10-02T11:32:22+0200",
    "executionTimeMs": 5286,
    "errorMessage": "Fail to extract report from database",
    "hasErrorStacktrace": true,
    "errorStacktrace": "java.lang.IllegalStateException...",
    "logs": false,
    "hasScannerContext": true,
    "scannerContext": "SonarQube plugins..."
  }
}
```

Branch-related fields (added in SonarQube 6.6+):
- `branch` — branch name
- `branchType` — "LONG" or "SHORT" (pre-8.0), or "BRANCH" (8.0+)

### GET /api/ce/activity

Search for past CE tasks. Supports filtering.

Parameters:
- `component` — project key
- `status` — comma-separated: SUCCESS, FAILED, CANCELED, PENDING, IN_PROGRESS
- `type` — task type (typically "REPORT")
- `minSubmittedAt` / `maxExecutedAt` — date range

### Task Status Values

| Status | Description |
|--------|-------------|
| PENDING | Task queued, waiting to be processed |
| IN_PROGRESS | Task currently being processed |
| SUCCESS | Task completed successfully |
| FAILED | Task failed (check errorMessage) |
| CANCELED | Task was canceled (manually or due to newer submission) |

### Permissions Required

- `api/ce/task` — requires 'Administer System' or 'Execute Analysis' permission
- `api/ce/component` — requires 'Administer System' or 'Execute Analysis' permission

## 4. Strategy for the Plugin

### Determining Which Branch Was Analyzed

Best approach: Query `GET /api/ce/activity?component={projectKey}&status=SUCCESS&type=REPORT` and look at the `branch` field in recent tasks. This is more reliable than trying to extract sonar properties from Bamboo.

### Showing Failed Analysis

Query `GET /api/ce/activity?component={projectKey}&status=FAILED` and display `errorMessage` from the task. Use `api/ce/task?id={taskId}&additionalFields=stacktrace` for the full error stacktrace.

### Getting Branch-Specific Quality Data

1. First verify the branch exists: `GET /api/project_branches/list?project={projectKey}`
2. Then query metrics: `GET /api/measures/component?component={projectKey}&branch={branchName}&metricKeys=new_coverage,new_lines_to_cover,...`
3. For issues: `GET /api/issues/search?componentKeys={projectKey}&branch={branchName}&inNewCodePeriod=true&resolved=false`
4. For quality gate: `GET /api/qualitygates/project_status?projectKey={projectKey}&branch={branchName}`

### New Code Period Detection

`GET /api/new_code_periods/show?project={projectKey}&branch={branchName}` returns:
```json
{
  "type": "REFERENCE_BRANCH",
  "value": "main",
  "inherited": true
}
```
