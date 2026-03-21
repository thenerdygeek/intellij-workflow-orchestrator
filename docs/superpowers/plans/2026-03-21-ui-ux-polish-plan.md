# UI/UX Polish & Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 52 UX audit findings across all plugin modules — colors, fonts, platform safety, bugs, empty states, and settings.

**Architecture:** Bottom-up fix sweep. Task 1 is the foundation (StatusColors expansion). Tasks 2-6 (color consolidation per module) depend on Task 1 but are independent of each other. Tasks 7-12 are all independent and can run in parallel.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (JBColor, JBUI, AllIcons, Messages API)

**Spec:** `docs/superpowers/specs/2026-03-21-ui-ux-polish-audit-design.md`

---

## Dependency Graph

```
Task 1 (Foundation)
  ├── Task 2 (Colors: Jira)
  ├── Task 3 (Colors: Bamboo)
  ├── Task 4 (Colors: Sonar)
  ├── Task 5 (Colors: PR)
  └── Task 6 (Colors: Automation + Settings)

Independent (can run anytime, but note file conflicts):
  Task 7  (Font standardization)
  Task 8  (Platform safety) — conflicts with Task 6 on CiCdConfigurable, run after Task 6
  Task 9  (Bug fixes + dead code) — conflicts with Task 2 on Jira files, run after Task 2
  Task 10 (Empty/loading/error states)
  Task 11 (Handover dead buttons)
  Task 12 (Settings fixes + Refreshable)
```

---

### Task 1: Foundation — StatusColors Expansion

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ui/StatusColors.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageThresholds.kt`

- [ ] **Step 1: Add new constants to StatusColors**

Add to `StatusColors.kt` after the existing constants:

```kotlin
val BORDER = JBColor(Color(0xD1, 0xD9, 0xE0), Color(0x44, 0x4D, 0x56))
val CARD_BG = JBColor(Color(0xF7, 0xF8, 0xFA), Color(0x2B, 0x2D, 0x30))
val HIGHLIGHT_BG = JBColor(Color(0xDE, 0xE9, 0xFC), Color(0x2D, 0x35, 0x48))
val WARNING_BG = JBColor(Color(0xFF, 0xF3, 0xE0), Color(0x4E, 0x34, 0x2E))
val SUCCESS_BG = JBColor(Color(0xE8, 0xF5, 0xE9), Color(0x1A, 0x3D, 0x1A))
val INFO_BG = JBColor(Color(0xE3, 0xF2, 0xFD), Color(0x1E, 0x3A, 0x5F))
```

- [ ] **Step 2: Add htmlColor utility**

Add to `StatusColors.kt`:

```kotlin
/**
 * Returns the current theme's resolved hex color string for use in HTML.
 * Must be called at render time — never cache the result across theme changes.
 */
fun htmlColor(color: JBColor): String {
    val c = color as Color
    return String.format("#%02x%02x%02x", c.red, c.green, c.blue)
}
```

- [ ] **Step 3: Fix CoverageThresholds light/dark colors**

In `sonar/src/main/kotlin/.../sonar/ui/CoverageThresholds.kt`, the light and dark colors are identical — fix to have proper theme variants:

```kotlin
val GREEN = JBColor(Color(0x1B, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50))   // matches StatusColors.SUCCESS
val YELLOW = JBColor(Color(0xBF, 0x80, 0x00), Color(0xE3, 0xB3, 0x41))  // matches StatusColors.WARNING
val RED = JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x5E, 0x5E))    // matches StatusColors.ERROR
```

- [ ] **Step 4: Build to verify no compile errors**

Run: `./gradlew :core:compileKotlin :sonar:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ui/StatusColors.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageThresholds.kt
git commit -m "feat(core): expand StatusColors with BORDER, CARD_BG, HIGHLIGHT_BG, background variants, and htmlColor utility"
```

---

### Task 2: Color Consolidation — Jira Module (6 files, 47 instances)

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt` (lines 726-732)
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt` (lines 220, 872-885)
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketListCellRenderer.kt` (lines 77, 226-248)
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/CollapsibleSection.kt` (lines 29, 33, 37)
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/CurrentWorkSection.kt` (lines 28, 34, 41-43, 76, 125, 130, 134)
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/StartWorkDialog.kt` (lines 64, 105, 120, 151, 179, 191, 214)

- [ ] **Step 1: Add StatusColors import to all 6 files**

Add `import com.workflow.orchestrator.core.ui.StatusColors` to each file.

- [ ] **Step 2: Replace colors in SprintDashboardPanel.kt**

Replace the companion object color constants (lines 726-732) with StatusColors references:
- `SECONDARY_TEXT` → `StatusColors.SECONDARY_TEXT`
- `BORDER_COLOR` → `StatusColors.BORDER`
- `PROGRESS_DONE` → `StatusColors.SUCCESS`
- `PROGRESS_IN_PROGRESS` → `StatusColors.LINK`
- `PROGRESS_TODO` → `StatusColors.INFO`

Delete the local companion constants and update all usages.

- [ ] **Step 3: Replace colors in TicketDetailPanel.kt**

Replace companion object color constants (lines 872-885):
- `SECONDARY_TEXT` → `StatusColors.SECONDARY_TEXT`
- `CARD_BG` → `StatusColors.CARD_BG`
- `BORDER_COLOR` → `StatusColors.BORDER`
- `LINK_COLOR` → `StatusColors.LINK`
- `STATUS_DONE` → `StatusColors.SUCCESS`
- `STATUS_IN_PROGRESS` → `StatusColors.LINK`
- `STATUS_TODO` → `StatusColors.INFO`
- `PRIORITY_HIGHEST/HIGH` → `StatusColors.ERROR`
- `PRIORITY_MEDIUM` → `StatusColors.WARNING`
- `PRIORITY_LOW/LOWEST` → `StatusColors.SUCCESS`

Delete the local constants and update all usages. Line 220 link color → `StatusColors.LINK`.

- [ ] **Step 4: Replace colors in TicketListCellRenderer.kt**

Replace companion constants (lines 226-248):
- Map all `CARD_*`, `KEY_TEXT_COLOR`, `SUMMARY_TEXT_COLOR`, `SECONDARY_TEXT_COLOR`, `STATUS_*`, `PRIORITY_*` to StatusColors equivalents.
- Line 77 separator color → `StatusColors.BORDER`

- [ ] **Step 5: Replace colors in CollapsibleSection.kt**

Lines 29, 33, 37 — replace all three hardcoded colors with `StatusColors.SECONDARY_TEXT`.

- [ ] **Step 6: Replace colors in CurrentWorkSection.kt**

Lines 28, 34, 41-43, 76, 125, 130, 134 — replace with StatusColors equivalents:
- Link color → `StatusColors.LINK`
- Green background → `StatusColors.SUCCESS_BG`
- Green border → `StatusColors.SUCCESS`
- Label colors → `StatusColors.SECONDARY_TEXT`

- [ ] **Step 7: Replace colors in StartWorkDialog.kt**

Lines 64, 105, 120, 151, 179, 191, 214:
- Error color → `StatusColors.ERROR`
- Warning color → `StatusColors.WARNING`
- Link color → `StatusColors.LINK`

- [ ] **Step 8: Build to verify**

Run: `./gradlew :jira:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/
git commit -m "refactor(jira): replace 47 hardcoded JBColor instances with StatusColors constants"
```

---

### Task 3: Color Consolidation — Bamboo Module (5 files, 22 instances)

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt` (lines 60-73)
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt` (lines 72, 79, 87, 92-95, 121, 199, 225, 399)
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/CreatePrDialog.kt` (lines 168, 181, 188, 205, 217-218, 318, 323)
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt` (line 63)
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageListPanel.kt` (line 81)
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildStatusNodeDecorator.kt` (lines 34-37)

- [ ] **Step 1: Add StatusColors import to all files**

- [ ] **Step 2: Replace colors in BuildDashboardPanel.kt**

Lines 60-73:
- Warning foreground → `StatusColors.WARNING`
- Banner background → `StatusColors.INFO_BG`
- Link foreground → `StatusColors.LINK`

- [ ] **Step 3: Replace colors in PrBar.kt**

Lines 92-95 companion constants:
- `BLUE_BG` → `StatusColors.INFO_BG`
- `GREEN_BG` → `StatusColors.SUCCESS_BG`
- `BLUE_BORDER` → `StatusColors.LINK`
- `GREEN_BORDER` → `StatusColors.SUCCESS`

Lines 399-407 HTML status colors → use `StatusColors.htmlColor()`:
```kotlin
"OPEN" -> StatusColors.htmlColor(StatusColors.SUCCESS)
"MERGED" -> StatusColors.htmlColor(StatusColors.MERGED)
"DECLINED" -> StatusColors.htmlColor(StatusColors.ERROR)
```

- [ ] **Step 4: Replace colors in CreatePrDialog.kt**

Lines 168, 181, 188, 205 section labels → `StatusColors.SECONDARY_TEXT`
Lines 217-218 placeholder → `StatusColors.INFO`
Line 318 chip background → `StatusColors.CARD_BG`
Line 323 remove button → `StatusColors.SECONDARY_TEXT`

- [ ] **Step 5: Replace colors in StageDetailPanel, StageListPanel, BuildStatusNodeDecorator**

- StageDetailPanel line 63 → `StatusColors.WARNING`
- StageListPanel line 81 → `StatusColors.SECONDARY_TEXT`
- BuildStatusNodeDecorator lines 34-37 → `StatusColors.SUCCESS`, `StatusColors.ERROR`, `StatusColors.LINK`, `StatusColors.INFO`

- [ ] **Step 6: Build and commit**

Run: `./gradlew :bamboo:compileKotlin`

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/
git commit -m "refactor(bamboo): replace 22 hardcoded JBColor instances with StatusColors constants"
```

---

### Task 4: Color Consolidation — Sonar Module (6 files, 18 instances)

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt` (lines 43, 54, 153, 155, 214, 226, 231, 237, 242)
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/OverviewPanel.kt` (lines 217, 315)
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt` (line 37)
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageTablePanel.kt` (lines 60-61)
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/SonarProjectPickerDialog.kt` (line 41)

- [ ] **Step 1: Add StatusColors import to all files**

- [ ] **Step 2: Replace colors in QualityDashboardPanel.kt**

Lines 43, 54, 214, 226-244 — warning colors → `StatusColors.WARNING`, secondary → `StatusColors.SECONDARY_TEXT`
Lines 153, 155 — toggle button colors → `StatusColors.LINK` (selected), `StatusColors.SECONDARY_TEXT` (normal)

- [ ] **Step 3: Replace colors in OverviewPanel.kt**

Line 217 orange rating → `StatusColors.WARNING`
Line 315 progress bar background → distinct light/dark: `JBColor(Color(0xE0, 0xE0, 0xE0), Color(0x3C, 0x3C, 0x3C))`

- [ ] **Step 4: Replace colors in IssueListPanel, CoverageTablePanel, SonarProjectPickerDialog**

- IssueListPanel line 37 → `StatusColors.WARNING`
- CoverageTablePanel lines 60-61 → `StatusColors.ERROR`, `StatusColors.WARNING`
- SonarProjectPickerDialog line 41 → `StatusColors.SECONDARY_TEXT`

- [ ] **Step 5: Build and commit**

Run: `./gradlew :sonar:compileKotlin`

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/
git commit -m "refactor(sonar): replace 18 hardcoded JBColor instances with StatusColors constants"
```

---

### Task 5: Color Consolidation — PR Module (3 files, 7 instances)

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt` (lines 327-328)
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrListPanel.kt` (lines 315-316)
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt` (lines 58-59, 1084)

- [ ] **Step 1: Replace colors in all 3 files**

- PrDashboardPanel: `SECONDARY_TEXT` → `StatusColors.SECONDARY_TEXT`, `BORDER_COLOR` → `StatusColors.BORDER`
- PrListPanel: `BRANCH_TEXT` → `StatusColors.SECONDARY_TEXT`, `SELECTION_BG` → `UIManager.getColor("List.selectionBackground")`
- PrDetailPanel: `CARD_BG` → `StatusColors.CARD_BG`, `BORDER_COLOR` → `StatusColors.BORDER`, line 1084 → `UIManager.getColor("List.selectionBackground")`

Delete local companion constants.

- [ ] **Step 2: Build and commit**

Run: `./gradlew :pullrequest:compileKotlin`

```bash
git add pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/
git commit -m "refactor(pr): replace hardcoded colors with StatusColors, use UIManager for selection"
```

---

### Task 6: Color Consolidation — Automation + Settings (6 files, 32 instances)

**Files:**
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt` (lines 42, 69, 193, 225, 259)
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/MonitorPanel.kt` (lines 178-180, 197, 218, 226, 230, 242-244, 266, 271, 277, 291, 335-337)
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/TagStagingPanel.kt` (lines 128-130)
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/QueueStatusPanel.kt` (line 42)
- Modify: `automation/src/main/kotlin/com/workflow/orchestrator/automation/settings/CiCdConfigurable.kt` (lines 313, 336, 478, 498, 517, 524)

- [ ] **Step 1: Replace colors in AutomationPanel.kt**

- Line 42 success → `StatusColors.SUCCESS`
- Line 69 secondary → `StatusColors.SECONDARY_TEXT`
- Line 193 success → `StatusColors.SUCCESS`
- Line 225, 259 link → `StatusColors.LINK`
- Line 213 `JBColor.RED` → `StatusColors.ERROR`

- [ ] **Step 2: Replace colors in MonitorPanel.kt (17 instances)**

This file has the most diversity — 3 different secondary greys. Unify all to `StatusColors.SECONDARY_TEXT`.
- Lines 178-180 status colors → `StatusColors.SUCCESS`, `StatusColors.ERROR`, `StatusColors.LINK`
- Lines 335-337 list renderer → same StatusColors
- Lines 242-244 alpha colors → use `ColorUtil.withAlpha(StatusColors.SUCCESS, 0x33)`
- Fix inconsistent "failed" color (orange in list, red in detail) → standardize to `StatusColors.ERROR`

- [ ] **Step 3: Replace colors in TagStagingPanel, QueueStatusPanel, CiCdConfigurable**

- TagStagingPanel lines 128-130 → `StatusColors.SUCCESS_BG`, `StatusColors.WARNING_BG`, `StatusColors.ERROR`
- QueueStatusPanel line 42 → `StatusColors.WARNING`
- CiCdConfigurable lines 313, 336, 478, 498, 517, 524 → `StatusColors.SECONDARY_TEXT`, `StatusColors.SUCCESS`

- [ ] **Step 4: Build and commit**

Run: `./gradlew :automation:compileKotlin`

```bash
git add automation/src/main/kotlin/com/workflow/orchestrator/automation/
git commit -m "refactor(automation): replace 32 hardcoded colors with StatusColors, fix inconsistent failed color"
```

---

### Task 7: Font Standardization (12 files, 53+ instances)

**Files:**
- Modify: `handover/src/main/kotlin/.../panels/CompletionMacroPanel.kt` (line 29)
- Modify: `handover/src/main/kotlin/.../panels/CopyrightPanel.kt` (line 26)
- Modify: `handover/src/main/kotlin/.../panels/JiraCommentPanel.kt` (lines 16, 26)
- Modify: `handover/src/main/kotlin/.../panels/PreReviewPanel.kt` (line 26)
- Modify: `handover/src/main/kotlin/.../panels/QaClipboardPanel.kt` (lines 17, 31)
- Modify: `handover/src/main/kotlin/.../panels/TimeLogPanel.kt` (line 39)
- Modify: `handover/src/main/kotlin/.../ui/HandoverContextPanel.kt` (line 170)
- Modify: `automation/src/main/kotlin/.../ui/SuiteConfigPanel.kt` (line 33)
- Modify: `automation/src/main/kotlin/.../ui/TagStagingPanel.kt` (line 45)
- Modify: `automation/src/main/kotlin/.../ui/MonitorPanel.kt` (lines 189, 265, 272)
- Modify: `bamboo/src/main/kotlin/.../ui/StageDetailPanel.kt` (lines 64, 67)
- Modify: `bamboo/src/main/kotlin/.../ui/CreatePrDialog.kt` (line 68)
- Modify: `automation/src/main/kotlin/.../settings/CiCdConfigurable.kt` (lines 303, 364, 505, 509, 519, 525)

Note: SprintDashboardPanel, TicketDetailPanel, OverviewPanel, PrDashboardPanel use `JBUI.scale(N).toFloat()` pattern extensively. While technically wrong for fonts, these files work correctly in practice since `JBUI.scale` at 1x returns the input value. Changing all 40+ instances in these files carries regression risk for minimal benefit. **Only fix the clearly broken patterns** (raw `14f`, `font.size + 1f`, `Font("JetBrains Mono",...)`).

- [ ] **Step 1: Fix all raw `14f` font sizes (7 instances in Handover)**

Replace `font.deriveFont(java.awt.Font.BOLD, 14f)` with:
```kotlin
font = JBUI.Fonts.label().deriveFont(Font.BOLD)
```

Files: CompletionMacroPanel:29, CopyrightPanel:26, JiraCommentPanel:26, PreReviewPanel:26, QaClipboardPanel:31, TimeLogPanel:39, MonitorPanel:189

- [ ] **Step 2: Fix arithmetic font sizing (2 instances)**

Replace `font.deriveFont(font.size + 1f)` with:
```kotlin
font = JBUI.Fonts.label().deriveFont(Font.BOLD)
```

Files: SuiteConfigPanel:33, TagStagingPanel:45

- [ ] **Step 3: Fix hardcoded font family (1 instance)**

Replace `Font("JetBrains Mono", Font.PLAIN, 12)` in CreatePrDialog:68 with:
```kotlin
font = JBUI.Fonts.create(EditorColorsManager.getInstance().globalScheme.editorFontName, 12)
```

- [ ] **Step 4: Fix "Monospaced" string literals (2 instances)**

Replace `JBUI.Fonts.create("Monospaced", 12)` with:
```kotlin
JBUI.Fonts.create(EditorColorsManager.getInstance().globalScheme.editorFontName, 12)
```

Files: JiraCommentPanel:16, QaClipboardPanel:17

- [ ] **Step 5: Fix JBUI.scale() misuse in CiCdConfigurable (4 instances)**

Lines 505, 509, 519, 525 use `JBUI.scale(N).toFloat()` for fonts. Replace with `JBUI.Fonts.smallFont()` or `JBUI.Fonts.label()` as appropriate.

Lines 303, 364 use `font.size + Nf`. Replace with `JBUI.Fonts.label().deriveFont(Font.BOLD)`.

- [ ] **Step 6: Fix StageDetailPanel (2 instances)**

Lines 64, 67 use `JBUI.scale(10/11).toFloat()`. Replace with `JBUI.Fonts.smallFont()`.

- [ ] **Step 7: Build all affected modules and commit**

Run: `./gradlew :handover:compileKotlin :automation:compileKotlin :bamboo:compileKotlin`

```bash
git add handover/ automation/ bamboo/
git commit -m "refactor: standardize font sizing to JBUI.Fonts across handover, automation, bamboo"
```

---

### Task 8: Platform Safety (Unicode, HTML colors, JOptionPane, SimpleDateFormat, Dispatchers)

**Files:**
- Modify: `sonar/src/main/kotlin/.../ui/IssueListPanel.kt` (lines 258-261 HTML colors, 122 warning symbol, 268 bullet, 290 bullet, 298 em-dash)
- Modify: `sonar/src/main/kotlin/.../ui/OverviewPanel.kt` (lines 98, 108, 152 HTML gray, 162-163 bullet)
- Modify: `bamboo/src/main/kotlin/.../ui/PrBar.kt` (line 72 `✕`, line 399 HTML gray)
- Modify: `bamboo/src/main/kotlin/.../ui/BuildStatusBarWidget.kt` (lines 71-74 unicode status)
- Modify: `automation/src/main/kotlin/.../ui/QueueStatusPanel.kt` (lines 24, 34, 97 unicode)
- Modify: `automation/src/main/kotlin/.../ui/AutomationStatusBarWidgetFactory.kt` (line 84 unicode, Dispatchers.Main)
- Modify: `pullrequest/src/main/kotlin/.../ui/PrDetailPanel.kt` (line 269 unicode, line 446 JOptionPane, line 66 SimpleDateFormat)
- Modify: `automation/src/main/kotlin/.../settings/CiCdConfigurable.kt` (line 192 JOptionPane)
- Modify: `jira/src/main/kotlin/.../ui/SprintDashboardPanel.kt` (lines 224, 304, 501, 511, 535, 553, 558, 565, 583, 610 Dispatchers.Main)
- Modify: `sonar/src/main/kotlin/.../ui/QualityDashboardPanel.kt` (line 30 Dispatchers.Main)
- Modify: `handover/src/main/kotlin/.../ui/HandoverPanel.kt` (line 20 Dispatchers.Main)
- Modify: `bamboo/src/main/kotlin/.../ui/CreatePrDialog.kt` (line 360 Dispatchers.Main)
- Modify: `core/src/main/kotlin/.../settings/WorkflowSettingsConfigurable.kt` (emoji checkmarks)
- Modify: `jira/src/main/kotlin/.../ui/TicketDetailPanel.kt` (emoji icons: 📦🖼️📄📎)
- Modify: `jira/src/main/kotlin/.../ui/CurrentWorkSection.kt` (emoji: 🔀)
- Modify: `jira/src/main/kotlin/.../ui/TicketStatusBarWidget.kt` (unescaped HTML user data)
- Modify: `jira/src/main/kotlin/.../ui/TicketListCellRenderer.kt` (antialiasing hint)
- Modify: `jira/src/main/kotlin/.../ui/CollapsibleSection.kt` (▼/▶ arrows)

- [ ] **Step 1: Fix HTML hardcoded colors in IssueListPanel**

Replace lines 258-261 severity color mapping to use `StatusColors.htmlColor()`:
```kotlin
val color = when (value.severity) {
    IssueSeverity.BLOCKER, IssueSeverity.CRITICAL -> StatusColors.htmlColor(StatusColors.ERROR)
    IssueSeverity.MAJOR -> StatusColors.htmlColor(StatusColors.WARNING)
    IssueSeverity.MINOR -> StatusColors.htmlColor(StatusColors.WARNING)
    IssueSeverity.INFO -> StatusColors.htmlColor(StatusColors.INFO)
}
```

- [ ] **Step 2: Fix HTML hardcoded "gray" in OverviewPanel and PrBar**

Replace `<font color='gray'>` with `<font color='${StatusColors.htmlColor(StatusColors.SECONDARY_TEXT)}'>`.

- [ ] **Step 3: Replace JOptionPane with Messages API (2 instances)**

PrDetailPanel line 446:
```kotlin
val confirm = Messages.showYesNoDialog(
    project,
    "Decline PR #$prId?",
    "Confirm Decline",
    Messages.getWarningIcon()
)
if (confirm == Messages.YES) { ... }
```

CiCdConfigurable line 192:
```kotlin
Messages.showWarningDialog(
    mainPanel,
    "Could not detect sonar.projectKey from pom.xml...",
    "Auto-detect Failed"
)
```

- [ ] **Step 4: Replace SimpleDateFormat with DateTimeFormatter**

PrDetailPanel line 66:
```kotlin
private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
```

Update usage from `DATE_FORMAT.format(Date(timestamp))` to `DATE_FORMAT.format(Instant.ofEpochMilli(timestamp))`.

- [ ] **Step 5: Replace Dispatchers.Main with EDT (14 instances)**

In each file, add import `import com.intellij.openapi.application.EDT` and replace:
- `Dispatchers.Main` → `Dispatchers.EDT`
- `withContext(Dispatchers.Main)` → `withContext(Dispatchers.EDT)`

Files: SprintDashboardPanel (10 instances), QualityDashboardPanel (1), HandoverPanel (1), AutomationStatusBarWidgetFactory (1), CreatePrDialog (1).

- [ ] **Step 6: Replace emoji icons in Jira module**

In TicketDetailPanel, replace emoji with AllIcons:
- `📦` → `AllIcons.Nodes.Module` (component icon)
- `🖼️` / `📄` / `📎` → `AllIcons.FileTypes.Any_type` / `AllIcons.FileTypes.Text` / `AllIcons.FileTypes.Any_type`

In CurrentWorkSection, replace `🔀` with `AllIcons.Vcs.Branch`.

In CollapsibleSection, replace `▼`/`▶` with `AllIcons.General.ArrowDown`/`AllIcons.General.ArrowRight`.

- [ ] **Step 7: Fix antialiasing in custom renderers**

In files with custom `paintComponent()` that set `VALUE_TEXT_ANTIALIAS_LCD_HRGB`, replace with system desktop hints:
```kotlin
val desktopHints = Toolkit.getDefaultToolkit()
    .getDesktopProperty("awt.font.desktophints") as? Map<*, *>
desktopHints?.forEach { (k, v) -> g2.setRenderingHint(k as RenderingHints.Key, v!!) }
```

Files: TicketListCellRenderer, PrListPanel (PrListCellRenderer), PrDetailPanel (StatusBadge, ActivityCellRenderer).

- [ ] **Step 8: Escape user data in HTML**

In TicketStatusBarWidget, escape `ticketId` and `currentBranch` before embedding in HTML:
```kotlin
val safeTicketId = StringUtil.escapeXmlEntities(ticketId)
val safeBranch = StringUtil.escapeXmlEntities(currentBranch)
```

Apply the same pattern anywhere user-controlled strings are embedded in `<html>` labels.

- [ ] **Step 9: Replace emoji checkmarks in WorkflowSettingsConfigurable**

Replace `\u2705` / `\u274C` with icon-based rendering:
```kotlin
val icon = if (isConfigured) AllIcons.General.InspectionsOK else AllIcons.General.Error
val label = JBLabel(serviceName, icon, SwingConstants.LEFT)
```

- [ ] **Step 10: Build all affected modules and commit**

Run: `./gradlew compileKotlin`

```bash
git add sonar/ bamboo/ pullrequest/ automation/ jira/ handover/ core/
git commit -m "fix: platform safety — theme-aware HTML colors, Messages API, DateTimeFormatter, Dispatchers.EDT, emoji→AllIcons, antialiasing, HTML escaping"
```

---

### Task 9: Bug Fixes + Dead Code + PR Cell Renderers

**Files:**
- Modify: `jira/src/main/kotlin/.../ui/CurrentWorkSection.kt` (line 106 — listener accumulation)
- Modify: `jira/src/main/kotlin/.../ui/TicketDetailPanel.kt` (line 203 — maxHeight clipping)
- Modify: `pullrequest/src/main/kotlin/.../ui/PrListPanel.kt` (lines 189-267 — renderer allocation)
- Modify: `pullrequest/src/main/kotlin/.../ui/PrDetailPanel.kt` (lines 928-1006, 1073-1136 — renderer allocation)
- Modify: `automation/src/main/kotlin/.../ui/AutomationPanel.kt` (lines 48, 109-111 — QueueStatusPanel)
- Modify: `bamboo/src/main/kotlin/.../ui/StageDetailPanel.kt` (line 84 — unused constant)
- Modify: `automation/src/main/kotlin/.../ui/SuiteConfigPanel.kt` (dead stages panel)

- [ ] **Step 1: Fix CurrentWorkSection listener accumulation**

In `CurrentWorkSection.kt`, before `addMouseListener` at line 106, remove existing listeners:
```kotlin
// Remove previous listeners to prevent accumulation on refresh()
mouseListeners.forEach { removeMouseListener(it) }
addMouseListener(object : java.awt.event.MouseAdapter() { ... })
```

- [ ] **Step 2: Fix TicketDetailPanel maxHeight clipping**

In `TicketDetailPanel.kt` line 203, change the maximumSize to allow vertical growth:
```kotlin
maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
```

Or remove the `maximumSize` assignment entirely and let BoxLayout compute the natural height.

- [ ] **Step 3: Convert PrListCellRenderer to cached components**

In `PrListPanel.kt`, refactor `PrListCellRenderer` (lines 189-267) to create components once:

```kotlin
private inner class PrListCellRenderer : ListCellRenderer<PrListItem> {
    private val rootPanel = JPanel(BorderLayout())
    private val titleLabel = JBLabel()
    private val metaLabel = JBLabel()
    private val statusBadge = StatusBadge()
    // ... create all components in init

    override fun getListCellRendererComponent(...): Component {
        // Reuse existing components, just update text/colors
        titleLabel.text = item.title
        // ...
        return rootPanel
    }
}
```

- [ ] **Step 4: Convert ActivityCellRenderer and FileCellRenderer similarly**

In `PrDetailPanel.kt`, apply the same cached-component pattern to:
- `ActivityCellRenderer` (lines 928-1006)
- `FileCellRenderer` (lines 1073-1136)

- [ ] **Step 5: Wire QueueStatusPanel into AutomationPanel layout**

In `AutomationPanel.kt`, add the `queueStatusPanel` to the Monitor tab layout. It's already instantiated (line 48) and has callbacks (lines 109-111), just needs to be added to the visual hierarchy.

- [ ] **Step 6: Remove unused MAX_DOWNLOAD_CHARS**

In `StageDetailPanel.kt`, delete line 84: `private const val MAX_DOWNLOAD_CHARS = 2_000_000`

- [ ] **Step 7: Investigate and clean up SuiteConfigPanel dead stages**

In `SuiteConfigPanel.kt`, check if `setStages()` is ever called from `AutomationPanel`. If not called, remove the dead `stagesPanel` and `setStages()` method. If it is called, leave it.

- [ ] **Step 8: Build and commit**

Run: `./gradlew :jira:compileKotlin :pullrequest:compileKotlin :automation:compileKotlin :bamboo:compileKotlin`

```bash
git add jira/ pullrequest/ automation/ bamboo/
git commit -m "fix: listener accumulation bug, maxHeight clipping, PR renderer GC pressure, wire QueueStatusPanel, clean dead code"
```

---

### Task 10: Empty, Loading, and Error States

**Files:**
- Modify: `jira/src/main/kotlin/.../ui/SprintDashboardPanel.kt`
- Modify: `sonar/src/main/kotlin/.../ui/QualityDashboardPanel.kt`
- Modify: `bamboo/src/main/kotlin/.../ui/StageListPanel.kt`
- Modify: `bamboo/src/main/kotlin/.../ui/ManualStageDialog.kt`
- Modify: `automation/src/main/kotlin/.../ui/AutomationPanel.kt`
- Modify: `handover/src/main/kotlin/.../panels/CompletionMacroPanel.kt`
- Modify: `handover/src/main/kotlin/.../panels/CopyrightPanel.kt`
- Modify: `handover/src/main/kotlin/.../panels/PreReviewPanel.kt`
- Modify: `handover/src/main/kotlin/.../panels/QaClipboardPanel.kt`
- Modify: `handover/src/main/kotlin/.../panels/TimeLogPanel.kt`
- Modify: `pullrequest/src/main/kotlin/.../ui/PrDetailPanel.kt`
- Modify: `sonar/src/main/kotlin/.../ui/SonarProjectPickerDialog.kt`

- [ ] **Step 1: Add empty state to SprintDashboardPanel ticket list**

When `issues.isEmpty()`, show a centered label in the list area:
```kotlin
val emptyLabel = JBLabel("No tickets in sprint.").apply {
    foreground = JBUI.CurrentTheme.Label.disabledForeground()
    horizontalAlignment = SwingConstants.CENTER
    border = JBUI.Borders.emptyTop(40)
}
```

- [ ] **Step 2: Add empty + loading states to QualityDashboardPanel**

When `state.projectKey.isEmpty()`, show: "Configure SonarQube project key in Settings > CI/CD."
During refresh, show `AnimatedIcon.Default()` spinner.

- [ ] **Step 3: Add empty state to StageListPanel**

When `stages` is empty: "No stages found."

- [ ] **Step 4: Fix ManualStageDialog loading state**

Show "Loading variables..." with a spinner instead of the misleading "No build variables configured."

- [ ] **Step 5: Add empty state to AutomationPanel tag table**

When no tags: "No docker tags configured."

- [ ] **Step 6: Add empty states to Handover sub-panels**

- CompletionMacroPanel: "No steps configured."
- CopyrightPanel: "No files to check." + spinner during scan
- PreReviewPanel: style existing hint as secondary text (`JBUI.CurrentTheme.Label.disabledForeground()`)
- QaClipboardPanel: "No services configured."
- TimeLogPanel: "Select a ticket to log time."

- [ ] **Step 7: Add error feedback to PR actions**

In PrDetailPanel, wrap approve/merge/decline coroutines with try/catch and show notification on failure:
```kotlin
catch (e: Exception) {
    WorkflowNotificationService.getInstance(project).notifyError(
        "PR Action Failed", e.message ?: "Unknown error"
    )
}
```

- [ ] **Step 8: Add loading spinner to SonarProjectPickerDialog**

Show `AnimatedIcon.Default()` next to status label during search.

- [ ] **Step 9: Build and commit**

Run: `./gradlew compileKotlin`

```bash
git add jira/ sonar/ bamboo/ automation/ handover/ pullrequest/
git commit -m "ux: add empty, loading, and error states to 12 panels"
```

---

### Task 11: Handover Dead Buttons

**Files:**
- Modify: `handover/src/main/kotlin/.../panels/CopyrightPanel.kt` (fixAll:19, rescan:20)
- Modify: `handover/src/main/kotlin/.../panels/JiraCommentPanel.kt` (edit:18, post:19)
- Modify: `handover/src/main/kotlin/.../panels/PreReviewPanel.kt` (analyze:18)
- Modify: `handover/src/main/kotlin/.../panels/QaClipboardPanel.kt` (copyAll:19, addService:20)
- Modify: `handover/src/main/kotlin/.../panels/TimeLogPanel.kt` (log:32)
- Modify: `handover/src/main/kotlin/.../ui/HandoverContextPanel.kt` (transition:29)

- [ ] **Step 1: Audit which buttons have backing service methods**

Check the handover service layer for existing implementations. Read:
- `handover/src/main/kotlin/.../service/` directory
- `handover/src/main/kotlin/.../ui/HandoverPanel.kt` for any wiring

- [ ] **Step 2: For buttons with existing service methods — wire them**

Add `addActionListener` calls that invoke the appropriate service method.

- [ ] **Step 3: For buttons without service methods — disable with tooltip**

```kotlin
button.isEnabled = false
button.toolTipText = "Coming soon"
```

- [ ] **Step 4: Build and commit**

Run: `./gradlew :handover:compileKotlin`

```bash
git add handover/
git commit -m "ux(handover): wire or disable 8 dead buttons across 6 panels"
```

---

### Task 12: Settings Fixes + Refreshable Interface

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/Refreshable.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/GeneralConfigurable.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/onboarding/SetupDialog.kt`

- [ ] **Step 1: Create Refreshable interface**

```kotlin
package com.workflow.orchestrator.core.toolwindow

/**
 * Interface for tab panels that support incremental refresh
 * without full rebuild.
 */
interface Refreshable {
    fun refresh()
}
```

- [ ] **Step 2: Update WorkflowToolWindowFactory RefreshAction**

Change `RefreshAction` to call `refresh()` on the selected tab panel instead of rebuilding all tabs:

```kotlin
override fun actionPerformed(e: AnActionEvent) {
    val content = toolWindow.contentManager.selectedContent
    val component = content?.component
    if (component is Refreshable) {
        component.refresh()
    }
}
```

- [ ] **Step 3: Fix GeneralConfigurable false modification**

Add `isInitializing` flag:
```kotlin
private var isInitializing = true

// In createComponent():
isInitializing = true
// ... build UI, load tokens ...
isInitializing = false

// In onChanged callback:
if (!isInitializing) {
    pendingTokens[serviceType] = newToken
}
```

- [ ] **Step 4: Fix SetupDialog premature persistence**

Move credential storage from Test Connection success handler into `doOKAction()`:
- Store test results in `private val testResults = mutableMapOf<ServiceType, TestResult>()`
- On Test Connection success: save to `testResults`, update UI
- On OK: iterate `testResults` and persist to `credentialStore` + `settings`

- [ ] **Step 5: Build and commit**

Run: `./gradlew :core:compileKotlin`

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/
git commit -m "fix(settings): false modification flag, deferred credential persistence, Refreshable interface"
```

---

## Final Verification

After all tasks complete:

- [ ] Run full build: `./gradlew buildPlugin`
- [ ] Run all tests: `./gradlew test`
- [ ] Verify plugin loads: `./gradlew runIde` — check all 6 tabs render, settings pages open, status bar widgets show
- [ ] Update `docs/architecture/` if StatusColors changes affect the docs
- [ ] Update `core/CLAUDE.md` to document new StatusColors constants
