# UI/UX Polish & Audit — Design Spec

**Date:** 2026-03-21
**Scope:** Full cold audit of all 6 tabs, settings pages, status bar widgets, dialogs, and editor integrations. Fix 52 findings across 40+ files.

## Context

A comprehensive UX audit revealed 52 findings (12 HIGH/CRITICAL, 22 MEDIUM, 18 LOW) clustered into 5 systemic patterns rather than isolated issues. The plugin's foundation (StatusColors, TimeFormatter, Disposable patterns, JBUI scaling) is solid — the problem is underuse of these shared utilities.

## Approach

Bottom-up fix sweep: fix systemic patterns in priority order across all modules simultaneously. Each category is independent and parallelizable.

---

## 1. Color System Consolidation

### Problem
50+ hardcoded `JBColor` instances across all modules. Three different dark-theme greys for "secondary text" (`0x8B949E`, `0x6c7086`, `0x585b70`). Catppuccin colors leaking into some dark-theme values (e.g., `0xa6e3a1`, `0xf38ba8`, `0x89b4fa`).

### Design

Expand `StatusColors` in `:core` with missing constants:

```kotlin
object StatusColors {
    // Existing (unchanged)
    val SUCCESS = JBColor(Color(0x1B, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))
    val ERROR = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x5E, 0x5E))
    val WARNING = JBColor(Color(0xBF, 0x80, 0x00), Color(0xE3, 0xB3, 0x41))
    val INFO = JBColor(Color(0x57, 0x60, 0x6A), Color(0x8B, 0x94, 0x9E))
    val LINK = JBColor(Color(0x1A, 0x73, 0xE8), Color(0x8A, 0xB4, 0xF8))
    val OPEN = SUCCESS
    val MERGED = JBColor(Color(0x6F, 0x42, 0xC1), Color(0xB8, 0x7B, 0xFF))
    val DECLINED = ERROR
    val SECONDARY_TEXT = JBColor(Color(0x65, 0x6D, 0x76), Color(0x8B, 0x94, 0x9E))

    // New additions
    val BORDER = JBColor(Color(0xD1, 0xD9, 0xE0), Color(0x44, 0x4D, 0x56))
    val CARD_BG = JBColor(Color(0xF7, 0xF8, 0xFA), Color(0x2B, 0x2D, 0x30))
    val HIGHLIGHT_BG = JBColor(Color(0xDE, 0xE9, 0xFC), Color(0x2D, 0x35, 0x48))  // non-list highlighting
    val WARNING_BG = JBColor(Color(0xFF, 0xF3, 0xE0), Color(0x4E, 0x34, 0x2E))
    val SUCCESS_BG = JBColor(Color(0xE8, 0xF5, 0xE9), Color(0x1A, 0x3D, 0x1A))
    val INFO_BG = JBColor(Color(0xE3, 0xF2, 0xFD), Color(0x1E, 0x3A, 0x5F))

    // Utility — must be called at render time, never cached across theme changes.
    // JBColor resolves to the current theme's color when cast to Color.
    fun htmlColor(color: JBColor): String {
        val c = color as Color
        return String.format("#%02x%02x%02x", c.red, c.green, c.blue)
    }
}
```

Then replace all hardcoded instances across every module. No module should define its own color constants. Delete all local `companion object` color constants (e.g., `SECONDARY_TEXT`, `BORDER_COLOR`, `CARD_BG`, `PROGRESS_DONE`, etc.) from individual files.

### Files affected
~20 files with ~118 hardcoded instances across all modules. Major clusters:
- Jira: SprintDashboardPanel, TicketDetailPanel, TicketListCellRenderer, CollapsibleSection, CurrentWorkSection, StartWorkDialog
- Bamboo: BuildDashboardPanel, PrBar, CreatePrDialog, StageDetailPanel, StageListPanel, BuildStatusNodeDecorator
- Sonar: QualityDashboardPanel, OverviewPanel, IssueListPanel, CoverageTablePanel, CoverageThresholds, SonarProjectPickerDialog
- PR: PrDashboardPanel, PrListPanel, PrDetailPanel
- Automation: AutomationPanel, MonitorPanel, TagStagingPanel, QueueStatusPanel
- Handover: HandoverContextPanel
- Settings: CiCdConfigurable, WorkflowSettingsConfigurable

---

## 2. Font Standardization

### Problem
Three conflicting patterns: raw absolute (`deriveFont(14f)`), scaled absolute (`deriveFont(JBUI.scale(11).toFloat())`), and relative (`deriveFont(font.size2D - 1f)`). Some break on HiDPI.

### Design

One convention using `JBUI.Fonts` utilities and relative sizing:

```kotlin
// Headers (section titles)
font = JBUI.Fonts.label().deriveFont(Font.BOLD)

// Sub-headers (card titles)
font = JBUI.Fonts.label().biggerOn(2f).deriveFont(Font.BOLD)

// Small/secondary text
font = JBUI.Fonts.smallFont()

// Monospaced (log viewers, code)
// Note: JBUI.Fonts.create() already applies DPI scaling internally,
// so pass the logical size (e.g., 12), not a pre-scaled value.
font = JBUI.Fonts.create(EditorColorsManager.getInstance().globalScheme.editorFontName, 12)
```

Rules:
- Never use raw `deriveFont(14f)` or `Font("JetBrains Mono", ...)`
- Never use `JBUI.scale()` on font point sizes (it's for pixel dimensions only)
- Use `JBUI.Fonts.label()` as the base, then `.biggerOn()` / `.lessOn()` for relative
- Use `JBUI.Fonts.smallFont()` for secondary text
- Get editor font from `EditorColorsManager`, never hardcode font family names

### Files affected
~15 instances across: QualityDashboardPanel, PrBar, CreatePrDialog, CiCdConfigurable, CompletionMacroPanel, CopyrightPanel, JiraCommentPanel, PreReviewPanel, QaClipboardPanel, TimeLogPanel, MonitorPanel, StageDetailPanel, TagStagingPanel, SuiteConfigPanel.

---

## 3. Empty, Loading, and Error States

### Problem
10+ panels show blank content, stale "Loading..." text, or no feedback on API failure.

### Design

Three standard patterns:

**Empty state** — reuse EmptyStatePanel pattern: centered message + action link.

**Loading state** — `AnimatedIcon.Default()` + `JBLabel` in place of content.

**Error state** — error icon + message + retry action on statusLabel.

### Panels needing updates

| Panel | Missing | Message |
|-------|---------|---------|
| SprintDashboardPanel (ticket list) | Empty | "No tickets in sprint." |
| QualityDashboardPanel | Empty + Loading | "Configure project key in Settings > CI/CD." + spinner |
| StageListPanel | Empty | "No stages found." |
| ManualStageDialog | Loading | "Loading variables..." (replace misleading "No variables") |
| AutomationPanel (tag table) | Empty | "No docker tags configured." |
| CompletionMacroPanel | Empty | "No steps configured." |
| CopyrightPanel | Empty + Loading | "No files to check." + spinner during scan |
| PreReviewPanel | Empty | "Click Analyze to run Cody pre-review." (style as secondary) |
| QaClipboardPanel | Empty | "No services configured." |
| TimeLogPanel | Empty | "Select a ticket to log time." |
| PrDetailPanel actions | Error | Show notification on approve/merge/decline failure |
| SonarProjectPickerDialog | Loading | Spinner during search |

---

## 4. Platform Safety

### Problem
- 10+ emoji/Unicode symbols used as icons (inconsistent on Windows)
- Hardcoded `VALUE_TEXT_ANTIALIAS_LCD_HRGB` (wrong on macOS)
- HTML in JBLabels with baked-in color strings that don't adapt to theme
- Unescaped user data in HTML

### Design

**Emoji replacements:**

| Current | Replacement |
|---------|-------------|
| `🔀` (git branch) | `AllIcons.Vcs.Branch` |
| `📦` (component) | `AllIcons.Nodes.Module` |
| `📎` (attachment) | `AllIcons.FileTypes.Any_type` |
| `🖼️` (image) | `AllIcons.FileTypes.Any_type` (use generic; `FileTypes.Image` may not exist in all versions) |
| `📄` (document) | `AllIcons.FileTypes.Text` |
| `▼`/`▶` (collapse/expand) | `AllIcons.General.ArrowDown` / `AllIcons.General.ArrowRight` |
| `✕` (close) | `AllIcons.Actions.Close` |
| `⟳` (refresh/running) | `AllIcons.Actions.Refresh` |
| `▶` (trigger) | `AllIcons.Actions.Execute` |
| `✅` (settings OK) | `AllIcons.General.InspectionsOK` |
| `❌` (settings fail) | `AllIcons.General.Error` |

**Antialiasing** — replace hardcoded hints with system defaults:
```kotlin
val desktopHints = Toolkit.getDefaultToolkit()
    .getDesktopProperty("awt.font.desktophints") as? Map<*, *>
desktopHints?.forEach { (k, v) -> g2.setRenderingHint(k as RenderingHints.Key, v!!) }
```

**HTML colors** — use `StatusColors.htmlColor()` helper instead of hardcoded hex strings.

**HTML escaping** — escape user data (`ticketId`, `branchName`) with `StringUtil.escapeXmlEntities()` before embedding in HTML.

### Files affected
Jira: TicketDetailPanel, CurrentWorkSection, TicketStatusBarWidget, TicketListCellRenderer
Bamboo: PrBar, CreatePrDialog, BuildStatusNodeDecorator
Sonar: IssueListPanel, OverviewPanel
Automation: AutomationPanel, AutomationStatusBarWidgetFactory
Settings: WorkflowSettingsConfigurable

---

## 5. Functional Bugs, Dead Code, and Component Fixes

### Bug fixes

1. **CurrentWorkSection accumulating mouse listeners** — Remove existing listeners before adding new ones in `buildActiveState()`.

2. **TicketDetailPanel maxHeight clipping** — Remove `maximumSize` cap in `addFullWidthComponent()`. Use `Dimension(Int.MAX_VALUE, Int.MAX_VALUE)` for unconstrained vertical growth.

### Dead code removal

| Item | Action |
|------|--------|
| QueueStatusPanel (instantiated, callbacks wired, but never added to layout) | Add to AutomationPanel layout (Monitor tab) — it was intended to be visible. Move its callback logic into the visible UI. |
| SuiteConfigPanel stages area (never called) | Remove dead stages panel |
| `MAX_DOWNLOAD_CHARS` constant | Remove |

### Dead Handover buttons (8 across 5 panels)

For each button without an action listener:
- If business logic exists in service layer: wire the listener
- If not: disable with tooltip "Coming soon" or remove entirely

Specific buttons: CopyrightPanel (fixAll, rescan), JiraCommentPanel (edit), PreReviewPanel (analyze), QaClipboardPanel (copyAll, addService), TimeLogPanel (log), HandoverContextPanel (transition).

### Component swaps

| Before | After | Count |
|--------|-------|-------|
| `JScrollPane` | `JBScrollPane` | 2 |
| `JCheckBox` | `JBCheckBox` | 2 |
| `JList` | `JBList` | 1 |
| `JOptionPane.showConfirmDialog` | `Messages.showYesNoDialog` | 2 |
| `DefaultListCellRenderer` | `SimpleListCellRenderer` or `ColoredListCellRenderer` | 1 |
| `SimpleDateFormat` | `DateTimeFormatter` | 1 |

### PR cell renderers — convert from allocate-per-paint to reusable cached components

Create components once in constructor, reuse in `getListCellRendererComponent`. Applies to: PrListCellRenderer, ActivityCellRenderer, FileCellRenderer.

### Selection colors — replace hardcoded with UIManager

For list selection backgrounds, use the theme's native selection color (respects user customization):
```kotlin
// Before:
val SELECTION_BG = JBColor(0xDEE9FC, 0x2D3548)

// After:
UIManager.getColor("List.selectionBackground")
```

Note: `StatusColors.HIGHLIGHT_BG` is for non-list highlighting contexts (e.g., card hover, info banners). List selection must always use `UIManager`.

### Dispatchers.Main → EDT

Replace ~10 instances with `Dispatchers.EDT` (import: `com.intellij.openapi.application.EDT`, an IntelliJ-specific extension property on `Dispatchers`) or `invokeLater` per IntelliJ conventions. Files: SprintDashboardPanel, AutomationStatusBarWidgetFactory, HandoverPanel.

---

## 6. Settings Fixes

### GeneralConfigurable false modification
Add `isInitializing` flag. Skip `onChanged` callbacks while true. Set false after `createComponent()` completes.

### SetupDialog premature persistence
Move `credentialStore.storeToken()` and URL saves from Test Connection success into `doOKAction()`. Store test results in temporary map, persist only on OK.

### WorkflowSettingsConfigurable stale summary
Replace emoji checkmarks with `AllIcons.General.InspectionsOK` / `AllIcons.General.Error`. Staleness is acceptable for a read-only overview.

### Refresh destroys all tabs
Add `Refreshable` interface in `:core`:
```kotlin
interface Refreshable {
    fun refresh()
}
```
Tab panels implement it. `RefreshAction` calls `refresh()` on the selected tab only, instead of rebuilding all tabs.

---

## Out of Scope

- Keyboard mnemonics and shortcuts (LOW priority, separate effort)
- Full accessibility audit (screen reader support, ARIA-like patterns)
- Settings input validation beyond basic sanity
- Handover service layer wiring (separate feature work) — buttons are wired only where service methods already exist; buttons without backing service methods are disabled with "Coming soon" tooltip
- i18n / localization
