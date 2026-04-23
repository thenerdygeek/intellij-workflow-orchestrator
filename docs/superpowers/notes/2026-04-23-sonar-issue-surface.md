# Sonar Issue Surface Audit — 2026-04-23

## Panel

`sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt`

## Render Surface

`JBList<QualityListItem>` with a custom `IssueListCellRenderer` (implements `ListCellRenderer<QualityListItem>`).
The list model is `DefaultListModel<QualityListItem>`.

`QualityListItem` is a sealed interface with two variants:
- `IssueItem(val issue: MappedIssue)` — wraps SonarQube issues
- `HotspotItem(val hotspot: SecurityHotspotData)` — wraps security hotspots

## Row Model Fields

### MappedIssue (in `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/SonarModels.kt`)
- `filePath: String` — relative path (e.g. `src/main/kotlin/Foo.kt`), no module prefix
- `startLine: Int` — 1-based line number
- `startOffset: Int` — 0-based character offset within the line (column)

### SecurityHotspotData (in core model)
- `component: String` — Sonar component key in `"projectKey:src/path"` form; strip with `substringAfterLast(':')`
- `line: Int?` — 1-based line number (nullable)

## Pre-existing Navigation

`navigateToIssue` and `navigateToHotspot` were already wired via:
- Double-click mouse listener (`clickCount == 2`) → `navigateToItem`
- Right-click context menu → "Navigate to Issue" / "Navigate to Hotspot" → `navigateToItem`

Neither handler previously went through `PathLinkResolver`.

## Change Made (Task 15)

Both `navigateToIssue` and `navigateToHotspot` were retrofitted to route through
`PathLinkResolver.resolveForOpen(input)` before calling `FileEditorManager.openEditor`.
The trigger (double-click + context menu) is unchanged.
