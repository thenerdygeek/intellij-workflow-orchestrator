# Quality Tab Enhancements Design

**Date:** 2026-04-03  
**Status:** Draft  
**Stitch Project:** `projects/12571415715609134057` (7 screens — dashboard, variants, option comparisons)

## Overview

Four enhancements to the Quality (SonarQube) tab, implemented as new focused panels composed into the existing dashboard. All designs validated via Google Stitch mockups using the Darcula Pro design system.

**Approach:** Panel extraction (Approach 2) — new panel classes for each enhancement, composed into the existing `QualityDashboardPanel` via `JBSplitter`. Preserves the existing `StateFlow<SonarState>` architecture and tab-aware rendering.

## Enhancement 1: Security Hotspots in Issues Tab

### Decision
Merge hotspots into the existing Issues tab rather than creating a 4th sub-tab. Hotspot data kept as a separate list (`SecurityHotspotData`) and merged at display time — not forced into the `MappedIssue` model.

### Data Flow
- `SonarDataService.refreshWith()` gains a parallel fetch: `sonarService.getSecurityHotspots(projectKey, branch)`
- `SonarState` gets new field: `securityHotspots: List<SecurityHotspotData>`
- Hotspots and issues are merged at display time in `IssueListPanel`: when the Type filter is "All" or "Security Hotspot", hotspots are mapped to display items alongside issues. The list model holds a sealed interface (`QualityListItem`) with two variants: `IssueItem(MappedIssue)` and `HotspotItem(SecurityHotspotData)`. The cell renderer checks which variant to determine badge/column rendering

### UI Changes

**IssueListPanel:**
- Type filter dropdown gains "Security Hotspot" option
- When type filter = "Security Hotspot", the cell renderer adapts:
  - Shows vulnerability probability badge (High=red, Medium=yellow, Low=grey) instead of severity
  - Shows review status (To Review / Acknowledged / Fixed) instead of effort
- Right-click context menu for hotspots: "Navigate to Hotspot" + "Review in SonarQube" (opens browser to hotspot URL)

**OverviewPanel:**
- Issues card hotspot count ("H" suffix) becomes real data instead of counting `SECURITY_HOTSPOT` typed issues (which were never fetched)

### API
- Uses existing `SonarService.getSecurityHotspots()` — no new endpoint needed
- Requires SonarQube Developer Edition+ (service already handles this gracefully with error message)

### Files Changed
| File | Change |
|---|---|
| `sonar/model/SonarState.kt` | Add `securityHotspots: List<SecurityHotspotData>` field |
| `sonar/service/SonarDataService.kt` | Add `getSecurityHotspots()` to parallel fetch in `refreshWith()` |
| `sonar/ui/IssueListPanel.kt` | Extend Type filter, adapt cell renderer for hotspots |
| `sonar/ui/OverviewPanel.kt` | Use real hotspot count from `state.securityHotspots` |

---

## Enhancement 2: Issue Detail Drill-Down (Split Pane)

### Decision
Split pane layout — issue list stays visible on the left, detail panel opens on the right. Single-click selects and shows detail; double-click navigates to editor (existing behavior preserved).

### New Panel: `IssueDetailPanel`

**Content (for regular issues):**
- Header: Severity badge (color-coded) + Type badge + issue message as title
- Metadata row: File path (clickable → opens editor), line number, effort estimate, relative age, rule key
- Code snippet: Read-only `JBTextArea` (monospace), ~10 lines of context around the issue line. Issue line highlighted with error background. Source fetched via existing `SonarService.getSourceLines()`
- Rule section: Rule key (e.g., `java:S2259`) + rule name + description + remediation guidance. Fetched via new `SonarService.getRule()` method
- Actions bar: "Fix with AI Agent" (primary button), "Open in Editor" (secondary), prev/next issue arrows

**Content adaptation for hotspots:**
- Probability badge (High/Medium/Low) instead of severity
- Review status instead of effort  
- Category/OWASP classification instead of rule remediation

### Layout
- `IssueListPanel` wraps its existing content + `IssueDetailPanel` in a `JBSplitter` (horizontal)
- Proportion: 40% list / 60% detail (user-resizable)
- Right side hidden until first issue selection; empty state: "Select an issue to view details"

### State
- `SonarState` gains `selectedIssue: MappedIssue?` — set on single-click
- Detail panel observes this, fetches code snippet + rule info on demand (lazy, not in bulk refresh)
- Rule info cached in-memory to avoid re-fetching same rule across issues

### New Service Method
```kotlin
// core/services/SonarService.kt
suspend fun getRule(ruleKey: String): ToolResult<SonarRuleData>
```

### New Data Model
```kotlin
// core/model/sonar/SonarRuleData.kt
data class SonarRuleData(
    val ruleKey: String,       // e.g., "java:S2259"
    val name: String,          // e.g., "Null pointers should not be dereferenced"
    val description: String,   // HTML or markdown description
    val remediation: String?,  // Remediation guidance text
    val tags: List<String>     // e.g., ["bad-practice", "reliability"]
)
```

### Files
| File | Change |
|---|---|
| **New:** `sonar/ui/IssueDetailPanel.kt` | Issue/hotspot detail view |
| **New:** `core/model/sonar/SonarRuleData.kt` | Rule data class |
| `core/services/SonarService.kt` | Add `getRule()` method signature |
| `sonar/service/SonarServiceImpl.kt` | Implement `getRule()` via `/api/rules/show` |
| `sonar/api/SonarApiClient.kt` | Add `getRule()` API call |
| `sonar/model/SonarState.kt` | Add `selectedIssue: MappedIssue?` |
| `sonar/ui/IssueListPanel.kt` | Wrap in `JBSplitter`, single-click selection handler |

---

## Enhancement 3: Coverage Explorer with Preview Pane

### Decision
Option C — enhanced coverage table with directory grouping + a lightweight preview pane showing only uncovered code regions with "Open in Editor" button.

### Changes to CoverageTablePanel

**Directory grouping:**
- Files grouped by directory path (e.g., `src/main/java/com/engine/`)
- Each directory header row shows: folder name, aggregate line coverage %, file count
- Collapsible groups — click to expand/collapse
- Implemented as a custom `AbstractTableModel` with group header rows interspersed with file rows

**Summary bar (top):**
- "X files | Y% avg coverage | Z files below threshold"
- Threshold configurable (default 80%)

**Search/filter:**
- Text field above table to filter files by name
- Filters within expanded groups

### New Panel: `CoveragePreviewPanel`

**Content:**
- Metrics header: Line Coverage %, Branch Coverage %, Uncovered Lines, Uncovered Conditions, Cyclomatic Complexity, Cognitive Complexity — for the selected file
- Uncovered regions viewer: Read-only `JBTextArea` (monospace) showing only uncovered code regions — ~5 lines of context around each uncovered block. Gutter indicators: green = covered, red = uncovered, yellow = partial
- Footer: "Open in Editor" button + file path display
- Source data from existing `SonarDataService.getLineCoverage()` (already cached in `lineCoverageCache`)

### Layout
- `CoverageTablePanel` wraps its content + `CoveragePreviewPanel` in a `JBSplitter` (vertical)
- Proportion: 60% table / 40% preview
- Preview hidden until file selected; empty state: "Select a file to preview coverage"

### State
- `SonarState` gains `selectedCoverageFile: String?` — set on single-click in table
- Preview fetches line coverage on demand from existing cache

### Files
| File | Change |
|---|---|
| **New:** `sonar/ui/CoveragePreviewPanel.kt` | Uncovered-region preview with metrics |
| `sonar/ui/CoverageTablePanel.kt` | Wrap in `JBSplitter`, add directory grouping model, search field, summary bar |
| `sonar/model/SonarState.kt` | Add `selectedCoverageFile: String?` |

---

## Enhancement 4: Failed Quality Gate Treatment

### Decision
Prominent visual banner + "Show Blocking Issues" action link that pre-filters the Issues tab.

### New Component: `GateStatusBanner`

**Appearance (gate = FAILED only):**
- Full-width banner at top of Quality tab, above sub-tabs
- Red/error tonal background (`StatusColors.ERROR` at ~15% opacity)
- Left: Warning icon + "Quality Gate Failed" in bold
- Center: Failing conditions listed inline, e.g., "Coverage on New Code: 62.3% (threshold: 80%) | New Bugs: 3 (threshold: 0)"
- Right: "Show Blocking Issues" clickable action link
- Hidden entirely when gate = PASSED or NONE (removed from layout, not just invisible)

### "Show Blocking Issues" Logic
Maps failing gate conditions to issue filters:
- "New Bugs > 0" → switch to Issues tab, filter Type = Bug, mode = New Code
- "New Vulnerabilities > 0" → switch to Issues tab, filter Type = Vulnerability, mode = New Code
- "New Security Hotspots > 0" → switch to Issues tab, filter Type = Security Hotspot, mode = New Code
- Coverage/duplication conditions → switch to Coverage tab

### IssueListPanel API
```kotlin
fun applyPreFilter(type: IssueType?, newCodeMode: Boolean?)
```
Callable from `QualityDashboardPanel` when "Show Blocking Issues" is clicked. Sets the filter dropdowns and mode toggle programmatically, then refreshes the list.

### OverviewPanel Enhancement
- Quality Gate card: when FAILED, left accent border widens from 2px to 4px
- Failing conditions prefixed with red "✗", passing conditions with green "✓"
- Conditions that caused failure shown in `StatusColors.ERROR` color

### Files
| File | Change |
|---|---|
| **New:** `sonar/ui/GateStatusBanner.kt` | Full-width failure banner |
| `sonar/ui/QualityDashboardPanel.kt` | Insert banner between header and branch info, wire "Show Blocking Issues" |
| `sonar/ui/IssueListPanel.kt` | Add `applyPreFilter()` public method |
| `sonar/ui/OverviewPanel.kt` | Enhanced gate card failure styling |

---

## Complete File Inventory

### New Files (4)
1. `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueDetailPanel.kt`
2. `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoveragePreviewPanel.kt`
3. `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/GateStatusBanner.kt`
4. `core/src/main/kotlin/com/workflow/orchestrator/core/model/sonar/SonarRuleData.kt`

### Modified Files (9)
1. `core/src/main/kotlin/com/workflow/orchestrator/core/services/SonarService.kt` — add `getRule()`
2. `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt` — implement `getRule()`
3. `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt` — add `getRule()` API call
4. `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/SonarState.kt` — add `securityHotspots`, `selectedIssue`, `selectedCoverageFile`
5. `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt` — fetch hotspots in refresh loop
6. `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt` — JBSplitter, hotspot support, `applyPreFilter()`
7. `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTablePanel.kt` — JBSplitter, directory grouping, search, summary bar
8. `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/OverviewPanel.kt` — real hotspot count, enhanced gate card
9. `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt` — insert GateStatusBanner, wire cross-tab actions

### Unchanged Files
- `CoverageLineMarkerProvider.kt` — gutter markers work independently
- `SonarIssueAnnotator.kt` — editor annotations unchanged
- `SonarProjectPickerDialog.kt` — project picker unchanged
- Authentication/HTTP layer — no changes

## Testing Strategy

Each enhancement tested independently:
1. **Hotspots:** Mock `getSecurityHotspots()` response, verify filter shows hotspots with probability/status rendering
2. **Issue detail:** Mock `getSourceLines()` + `getRule()`, verify code snippet display and rule info rendering
3. **Coverage preview:** Mock `getLineCoverage()`, verify uncovered regions extracted and displayed correctly
4. **Gate banner:** Mock FAILED gate with conditions, verify banner visibility, condition display, and pre-filter action

Existing tests remain green — no existing behavior changes, only additions.

## Stitch Design References

| Screen | ID | Description |
|---|---|---|
| Quality Dashboard (passed) | `cbd713d1222f41e0bc7ac78ae605dac0` | Main dashboard layout reference |
| Coverage File Explorer | `1e14debcc3e94c2aaaa138cefd33afbd` | Three-column reference (adapted to Option C) |
| Issue Drill-Down | `412b74e4786a4c5cae46ed549d1fd403` | Detail view reference (adapted to split pane) |
| Quality Gate Failed | `5bf3d1b41be74eb2b3a1fbb8d62208e3` | Failed state banner and styling reference |
| Coverage Option A | `b4fba131c18049a29753eb34373f4aad` | Enhanced table reference |
| Coverage Option B | `5cd6974acbd14d33acf9ef455a3ec763` | Three-column reference |
| Coverage Option C | `0c3a0830a98044deb2ce15802f3d10e6` | Table + preview reference (chosen) |
