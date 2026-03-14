# Quality Tab: New Code / Overall Code Toggle

## Problem

SonarQube's web UI has "New Code" / "Overall Code" tabs on the project dashboard, but clicking into a specific issue or file navigates to the project-level page and loses the new-code filter. Our Quality tab currently shows only overall data with no way to focus on new code.

## Solution

Add a "New Code / Overall" toggle to the Quality tab header that filters ALL sub-tabs (Overview, Issues, Coverage) and inline editor integration (gutter markers, squiggles) consistently. Default: "New Code" (matches SonarQube's "Clean as You Code" philosophy).

## Requirements

- **SonarQube Server 9.4+** required for `inNewCodePeriod` parameter. For older versions, fall back to `sinceLeakPeriod=true`.
- Capture real API responses with `new_*` metrics as test fixtures before implementation.

## Toggle Design

The toggle sits in the header bar, right-aligned, next to the Refresh button:

```
┌─────────────────────────────────────────────────────────┐
│ my-service:feature/PROJ-123  ✓   [ New Code | Overall ] ⟳│
├─────────────────────────────────────────────────────────┤
│ Overview | Issues | Coverage                              │
```

- Pill-style segmented control: two buttons, selected one highlighted
- Persists across sub-tab switches (Overview → Issues → Coverage keeps the same filter)
- Persists across IDE restarts via `PropertiesComponent`
- Default: "New Code" selected
- Switching triggers a data refresh (no blocking EDT — toggle listener calls `dataService.setMode()` which launches a coroutine)

## Data Flow

### Strategy: Fetch both datasets on refresh, cache both

On each refresh, `SonarDataService` fetches BOTH overall and new-code data in parallel. Both are stored in `SonarState`. Toggling switches which cached dataset is displayed — no network round-trip on toggle switch. Only Refresh (or branch change) triggers API calls.

### Measures API

Both metric sets fetched in a single `api/measures/component_tree` call:

```
metricKeys=coverage,branch_coverage,uncovered_lines,uncovered_conditions,lines_to_cover,
           new_coverage,new_branch_coverage,new_uncovered_lines,new_uncovered_conditions,new_lines_to_cover,
           bugs,vulnerabilities,code_smells,
           new_bugs,new_vulnerabilities,new_code_smells
```

Note: `new_line_coverage` does not exist as a SonarQube metric. Use `new_coverage` instead (it is the new-code equivalent of `coverage`/`line_coverage`).

### Issues API

Two parallel calls on each refresh:

| Dataset | API call |
|---|---|
| **Overall** | `GET /api/issues/search?componentKeys=...&resolved=false` (current) |
| **New Code** | `GET /api/issues/search?componentKeys=...&resolved=false&inNewCodePeriod=true` |

Fallback for SonarQube < 9.4: use `sinceLeakPeriod=true` instead of `inNewCodePeriod=true`.

### Quality Gate

`api/qualitygates/project_status` already evaluates on new code by default. The gate status is the same regardless of toggle — only the condition labels change (show `new_` prefix in New Code mode for clarity).

## Sub-tab Behavior

### Overview

| Mode | Quality Gate card | Coverage card | Issues card |
|---|---|---|---|
| **New Code** | Shows `new_*` conditions | `new_coverage`, `new_branch_coverage`, `new_lines_to_cover` | `new_bugs`, `new_vulnerabilities`, `new_code_smells` counts |
| **Overall** | Shows overall conditions | `coverage`, `branch_coverage`, `lines_to_cover` | `bugs`, `vulnerabilities`, `code_smells` counts |

Recent Issues section: shows issues from the active dataset (new code or overall).

### Issues

| Mode | Data source | Empty state |
|---|---|---|
| **New Code** | Cached new-code issues (from `inNewCodePeriod=true` call) | "No new issues in this branch" with celebration icon |
| **Overall** | Cached overall issues (current behavior) | "No issues found" |

Type and severity dropdown filters apply on top of the mode filter.

### Coverage

| Mode | Table columns | Files shown |
|---|---|---|
| **New Code** | New Coverage %, New Branch %, New Uncov. Lines, New Lines to Cover | Only files with `new_lines_to_cover > 0` |
| **Overall** | Line %, Branch %, Uncov. Lines, Uncov. Conditions | All files (current behavior) |

## Editor Integration

### Gutter Markers (CoverageLineMarkerProvider)

SonarQube does not provide per-line "is this new code?" information. The gutter marker filtering works at the **file level**:

| Mode | Behavior |
|---|---|
| **New Code** | Gutter markers shown only in files that have `new_lines_to_cover > 0`. Within those files, all lines show markers (we cannot distinguish individual new lines from SonarQube data alone). |
| **Overall** | Show on all files (current behavior) |

### Sonar Squiggles (SonarIssueAnnotator)

The annotator uses whichever issue dataset is active in `SonarState`. No client-side filtering needed — the server-side `inNewCodePeriod=true` filter already returns only new-code issues.

| Mode | Behavior |
|---|---|
| **New Code** | Squiggles shown only for issues in the new-code dataset |
| **Overall** | Squiggles shown for all issues (current behavior) |

### Re-annotation

When the toggle switches, call `DaemonCodeAnalyzer.restart()` on EDT to re-annotate all open editors. This is EDT-safe.

## Intention Action Rename

Rename "Ask Cody to fix" to **"Fix with Cody (Workflow)"** to distinguish from the Sourcegraph Cody plugin's own "Ask Cody to fix" intention action.

**Note:** `CodyIntentionAction` currently imports `MappedIssue` and `SonarIssueAnnotator` from `:sonar`, which is a pre-existing architecture violation (`:cody` → `:sonar`). This should be fixed in a separate cleanup by moving the shared types to `:core`. For now, the rename is a cosmetic change within `:cody`.

## State Management

### SonarState changes

```kotlin
data class SonarState(
    // ... existing fields ...
    val newCodeMode: Boolean = true,

    // New-code specific data (cached alongside overall data)
    val newCodeIssues: List<MappedIssue> = emptyList(),
    val newCodeFileCoverage: Map<String, FileCoverageData> = emptyMap(),
    val newCodeOverallCoverage: CoverageMetrics? = null,
    val newCodeIssuesCounts: IssueCounts? = null
)

data class IssueCounts(
    val bugs: Int = 0,
    val vulnerabilities: Int = 0,
    val codeSmells: Int = 0,
    val securityHotspots: Int = 0
)
```

### FileCoverageData changes

Add missing `new_*` fields:

```kotlin
data class FileCoverageData(
    // ... existing fields ...
    val newCoverage: Double? = null,        // already exists
    val newBranchCoverage: Double? = null,  // already exists
    val newUncoveredLines: Int? = null,     // NEW
    val newLinesToCover: Int? = null         // NEW
)
```

### Toggle flow

1. User clicks toggle → calls `dataService.setNewCodeMode(mode)` (non-blocking)
2. `setNewCodeMode` updates `newCodeMode` in state flow
3. All sub-tabs and editor integration react to state change (no API call — cached data)
4. `DaemonCodeAnalyzer.restart()` triggers re-annotation

### Refresh flow

1. Refresh button / branch change / polling → `dataService.refresh()`
2. Fetch in parallel on `Dispatchers.IO`:
   - `api/measures/component_tree` with ALL metric keys (both overall + new_*)
   - `api/issues/search` (overall)
   - `api/issues/search?inNewCodePeriod=true` (new code)
   - `api/qualitygates/project_status`
3. Map results into `SonarState` with both datasets populated
4. Emit state update → UI re-renders based on `newCodeMode`

## Files to Modify

| File | Change |
|---|---|
| `sonar/ui/QualityDashboardPanel.kt` | Add toggle to header, wire to `dataService.setNewCodeMode()` |
| `sonar/ui/OverviewPanel.kt` | Read new vs overall metrics based on `state.newCodeMode` |
| `sonar/ui/IssueListPanel.kt` | Use `state.newCodeIssues` or `state.issues` based on mode |
| `sonar/ui/CoverageTablePanel.kt` | Switch column keys, filter files by `newLinesToCover > 0` |
| `sonar/service/SonarDataService.kt` | Fetch both datasets, add `setNewCodeMode()`, cache both |
| `sonar/api/SonarApiClient.kt` | Add `inNewCodePeriod` param to `getIssues()`, expand default metric keys |
| `sonar/service/CoverageMapper.kt` | Map `new_uncovered_lines`, `new_lines_to_cover` to `FileCoverageData` |
| `sonar/model/FileCoverageData.kt` | Add `newUncoveredLines`, `newLinesToCover` fields |
| `sonar/ui/CoverageLineMarkerProvider.kt` | File-level filter: skip files with no new code in new-code mode |
| `sonar/ui/SonarIssueAnnotator.kt` | Use active issue dataset from state |
| `sonar/ui/CoverageBannerProvider.kt` | Show banner based on active coverage dataset |
| `cody/editor/CodyIntentionAction.kt` | Rename text to "Fix with Cody (Workflow)" |
| `cody/editor/CodyGutterAction.kt` | Update tooltip text to include "(Workflow)" |

## Edge Cases

- **No new code data available** (first analysis, no new code period defined): Show "New code metrics not available" in Overview, auto-switch to Overall
- **SonarQube < 9.4**: Use `sinceLeakPeriod=true` fallback. If that also fails, show warning and disable New Code toggle
- **Toggle persists across IDE restarts**: Store via `PropertiesComponent.getInstance(project)`
- **Branch switch**: Keep the toggle mode, re-fetch both datasets for new branch
- **Editor files**: `DaemonCodeAnalyzer.restart()` on toggle change
- **User-customized `sonarMetricKeys`**: The toggle appends `new_*` equivalents to whatever custom keys the user configured, rather than replacing them

## Testing

- **Fixture-driven**: Capture real SonarQube responses with `new_*` metrics. Add test fixtures for: measures with mixed keys, issues with `inNewCodePeriod`, quality gate with `new_*` conditions.
- **Unit tests**: `CoverageMapper` handling of `new_*` fields. `SonarDataService` mode switching. Issue filtering.
- **Manual `runIde`**: Toggle behavior across sub-tabs, editor re-annotation, branch switching.
