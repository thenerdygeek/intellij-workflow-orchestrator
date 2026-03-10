# Workflow Orchestrator Plugin: UX Design Guide

> Concrete, research-backed UX guidance derived from JetBrains official UI guidelines, IntelliJ Platform SDK documentation, and analysis of production plugins (Docker, SonarLint, GitToolBox, Azure DevOps).

---

## Part 1: Foundational Principles

### 1.1 The Three Laws of IDE Plugin UX

**Law 1: Never Break the Flow.** The IDE is the developer's primary workspace. Every UI decision must minimize interruption to coding. Prefer non-modal interactions (tool windows, status bar, gutter icons) over modal ones (dialogs, alerts). Use modal dialogs only when the action requires immediate user input and cannot proceed without it.

**Law 2: Information at the Right Level.** Not all information is equally urgent. Match the information delivery mechanism to the urgency:
- Critical/blocking: Balloon notification with action buttons
- Contextual/ongoing: Tool window content
- Ambient/glanceable: Status bar widget
- In-context/code-related: Gutter icons, editor banners, inlay hints

**Law 3: Respect Platform Conventions.** Users have muscle memory from years of IDE use. Follow the patterns established by built-in features (Git, Run/Debug, Problems) rather than inventing new interaction paradigms. Use IntelliJ's custom Swing components (`JBList`, `JBTable`, `JBSplitter`, `ColoredTreeCellRenderer`) instead of standard Swing to maintain visual consistency.

### 1.2 Progressive Disclosure Strategy

Based on Nielsen Norman Group research and JetBrains guidelines, organize features into three tiers:

| Tier | Visibility | Content | Interaction Cost |
|------|-----------|---------|-----------------|
| **Primary** | Always visible | Current task status, active sprint ticket, build health | Zero clicks (glanceable) |
| **Secondary** | One click away | Sprint dashboard, build details, queue status, coverage data | One click from visible element |
| **Tertiary** | Settings/menus | API configuration, polling intervals, template formats, analytics | Navigate through Settings or menus |

---

## Part 2: Tool Window Architecture

### 2.1 Decision: Single Tool Window with Tabs (Recommended)

Use ONE tool window named **"Workflow"** (short, two words max per JetBrains naming guidelines) with multiple content tabs. This follows the pattern established by the built-in "Services" and "Git" tool windows.

**Rationale:**
- JetBrains guidelines state tool window names should be "short and descriptive, preferably not longer than two words"
- A single tool window consumes one slot in the tool window bar; multiple windows clutter the sidebar
- Tab-based organization mirrors how Run/Debug and Services work, so users already understand the pattern
- Tool window abbreviation for stripe display: "WF"

### 2.2 Tab Structure

```
[Workflow] Tool Window
  |-- [Sprint]          -- Active sprint tickets, assigned to current user
  |-- [Build]           -- CI build dashboard (three parallel lanes)
  |-- [Automation]      -- Queue + staging area + results
  |-- [Quality]         -- SonarQube issues, coverage summary
  |-- [Handover]        -- PR readiness, Jira closure
```

**Tab design rules (from JetBrains guidelines):**
- Tabs representing different functional areas should NOT be closable
- Only tabs representing instances (e.g., multiple build runs) should be closable
- Each tab gets its own toolbar with actions relevant to that context
- Use `Content.setPreferredFocusableComponent()` so keyboard users land in the right place

### 2.3 Docking and Orientation

| Position | Best For | Your Plugin |
|----------|----------|-------------|
| **Left** (vertical) | Tree-based navigation (Project, Structure) | Not recommended -- Sprint view is not a file tree |
| **Right** (vertical) | Secondary reference panels | Acceptable alternative position |
| **Bottom** (horizontal) | Tables, logs, wide content, master-detail | **Recommended default** -- matches Run/Debug, Terminal, Problems pattern |

**Bottom-docked is the correct default** because:
- The Sprint tab shows a table/list of tickets (wide content)
- The Build tab shows parallel status lanes (wide content)
- The Automation tab shows a staging table (wide content)
- Users expect operational/monitoring panels at the bottom (same as Terminal, Build, Run)

### 2.4 Tool Window Visibility Rules

From JetBrains guidelines: "Show tool window buttons by default only if they contain basic functionality used across projects."

**Your plugin should:**
- Register the tool window with `isApplicableAsync()` that checks for valid plugin configuration (at least one service connected)
- Show the tool window button by default once configured -- this is a core workflow tool
- If no services are configured, hide the button but make it available via "More tool windows" menu
- Mark the tool window as "dumb aware" if it does not depend on code indexes (API data does not need indexing)

### 2.5 Empty States

Every tab must handle the empty state. Follow JetBrains guidelines for empty states:

**Sprint tab (no tickets):**
```
No tickets assigned.
Check your Jira connection in Settings...    [Open Settings]
```

**Build tab (no builds):**
```
No builds found for the current branch.
Push your changes to trigger a CI build.
```

**Automation tab (not configured):**
```
Automation suite not configured.
Set up your Bamboo automation branch...      [Configure]
```

Rules:
- Use the pattern "No [entity] [state]."
- Provide 1-2 action links maximum
- Use `getEmptyText().setText()` on `JBList` and `Tree` components
- Hide toolbar actions that don't help fill the empty state

---

## Part 3: Status Bar Widget Design

### 3.1 What Goes in the Status Bar

Per JetBrains guidelines, status bar widgets should present "information or settings that are relevant enough to be 'always' shown." Given limited space, only the most critical ambient information qualifies.

**Recommended: One composite status bar widget** showing the most critical piece of current state:

```
[PROJ-123 | Build: Passing | Queue: #2]
```

This follows the DVCS branch widget pattern (text with popup on click).

### 3.2 Widget States and Display

| State | Display | Color |
|-------|---------|-------|
| Working on ticket, build passing | `PROJ-123 ✓` | Green icon |
| Working on ticket, build failing | `PROJ-123 ✗` | Red icon |
| Working on ticket, build running | `PROJ-123 ⟳` | Blue/animated icon |
| Queued for automation | `PROJ-123 Q#2` | Yellow icon |
| Automation running | `PROJ-123 ▶` | Blue icon |
| No active ticket | `Workflow: Idle` | Grey icon |

### 3.3 Widget Interaction Pattern

**Click behavior:** Open a popup (not a dialog) showing an expanded status summary:

```
+----------------------------------+
| Active Ticket: PROJ-123          |
| Branch: feature/PROJ-123-ui-fix  |
|                                   |
| Build Status                      |
|   Artifact: ✓ Passed (2m ago)    |
|   OSS Scan:  ✓ Passed            |
|   Sonar:     ✗ Failed (2 issues) |
|                                   |
| Queue Position: #2 (~15 min)     |
|                                   |
| [Open Workflow Panel]             |
+----------------------------------+
```

Use `EditorBasedStatusBarPopup` or `CustomStatusBarWidget` for this. The popup provides at-a-glance context; the link at the bottom opens the full tool window for action.

### 3.4 Widget Type

Use **Text with Popup** -- this is one of the three standard widget presentation types. JetBrains guidelines note that icon-only, text-only, and text-with-popup are the three patterns and they cannot be combined (no icon+text). Text-with-popup gives maximum information density in the status bar while enabling interaction.

---

## Part 4: Notification Strategy

### 4.1 Notification Decision Framework

JetBrains provides a clear two-axis decision model. Apply it to your plugin events:

| Event | User Action Required? | Initiated From | Notification Type |
|-------|-----------------------|----------------|-------------------|
| Build finished (success) | No | Background | Timeline balloon (auto-dismiss 10s) |
| Build failed | Yes, but not immediately | Background | Sticky balloon with "View Details" action |
| Sonar quality gate failed | Yes, but not immediately | Background | Sticky balloon with "View Issues" action |
| Queue turn started | Yes (attention needed) | Background | Suggestion balloon (sticky, prominent button) |
| Automation suite passed | No | Background | Timeline balloon (auto-dismiss 10s) |
| Automation suite failed - regressions | Yes, but not immediately | Background | Sticky balloon with "View Regressions" action |
| CVE found in dependency | Yes, but not immediately | Background | Sticky balloon with "Quick Fix" action |
| Configuration drift detected | Yes, but not immediately | Tool window | Banner within Automation tab |
| Service conflict detected | Yes (attention needed) | Background | Suggestion balloon (sticky) |
| Connection lost to service | No (informational) | Background | Timeline balloon, then status bar indicator |
| Weekly productivity digest | No | Background | Timeline balloon (auto-dismiss) |

### 4.2 Notification Groups

Register separate notification groups in `plugin.xml` so users can configure each independently:

```xml
<notificationGroup id="workflow.build" displayType="BALLOON" key="notification.group.build"/>
<notificationGroup id="workflow.quality" displayType="BALLOON" key="notification.group.quality"/>
<notificationGroup id="workflow.queue" displayType="BALLOON" key="notification.group.queue"/>
<notificationGroup id="workflow.automation" displayType="BALLOON" key="notification.group.automation"/>
```

### 4.3 Preventing Notification Fatigue

- **Maximum 2 action buttons per notification** (JetBrains guideline). If more are needed, put the most likely action first and hide others under a "More" dropdown.
- **Include "Don't show again"** on informational (non-error) notifications
- **Collapse rapid-fire events**: If three builds complete within seconds, show one notification summarizing all three, not three separate notifications
- **Use the Notifications tool window log**: All balloon notifications automatically appear in the Notifications tool window. Users who dismissed a balloon can find it there.
- **Respect user settings**: Register notification groups properly so users can mute specific categories via Settings > Appearance & Behavior > Notifications

### 4.4 Editor Banners (Non-Balloon Notifications)

Use `EditorNotificationProvider` for in-editor banners in these cases:
- File has uncovered lines (show at top of editor: "This file has 3 uncovered branches. [View in Quality tab]")
- File has a CVE-affected dependency in pom.xml ("Vulnerability detected in commons-text:1.9. [Quick Fix] [Dismiss]")
- Branch is behind develop ("Your branch is 5 commits behind develop. [Rebase] [Dismiss]")

Banners are non-modal and appear within the editor content area, so they are contextually relevant without interrupting flow.

---

## Part 5: Gutter Icon Design

### 5.1 Icon Specifications

| Context | Size (Classic UI) | Size (New UI) |
|---------|-------------------|---------------|
| Editor gutter | 12x12 | 14x14 |
| Node/action | 16x16 | 16x16 |
| Tool window | 13x13 | 20x20 + 16x16 (compact) |

All icons must be SVG format with explicit width/height attributes. Provide both light and dark variants (`icon.svg` and `icon_dark.svg`).

### 5.2 Gutter Icon Usage in Your Plugin

**SonarQube coverage markers:**

| Severity | Color | Icon Shape | Meaning |
|----------|-------|------------|---------|
| Blocker/Critical | Red (#E05555) | Filled circle | Must fix before merge |
| Major | Yellow/Orange (#E8A838) | Half-filled circle | Should fix |
| Minor/Info | Grey (#9AA7B0) | Empty circle | Optional improvement |

**Implementation rules (from JetBrains guidelines):**
- Return leaf elements only (e.g., `PsiIdentifier`, not `PsiMethod`) to avoid duplicate/blinking markers
- Extend `GutterIconDescriptor` so users can toggle coverage markers via Settings > Editor > General > Gutter Icons
- Set tooltip text: "SonarQube: No test coverage for this branch [Click to generate test]"
- Click behavior: Navigate to the related issue detail, or open a context menu with "Generate Test with Cody" / "View in SonarQube"

### 5.3 Project Tree Badges

Use `ProjectViewNodeDecorator` for coverage percentage badges on files:
- Green badge: 90-100% coverage
- Yellow badge: 70-89% coverage
- Red badge: Below 70% coverage
- No badge: File not in SonarQube analysis scope

Keep badges small and unobtrusive. Use `ColoredFragment` for the text to ensure theme compatibility.

---

## Part 6: Settings Page Design

### 6.1 Settings Hierarchy

Register under the `tools` parent group (for third-party integrations):

```
Settings
  +-- Tools
       +-- Workflow Orchestrator
            +-- Connections        (Jira, Bamboo, Bitbucket, SonarQube, Cody)
            +-- Notifications      (per-event enable/disable)
            +-- Branching & Commits (naming patterns, message format)
            +-- Automation Suite   (Docker registry URL, default tags)
            +-- Advanced           (polling intervals, cache settings)
```

### 6.2 Connection Settings Page

For each service connection, follow this layout pattern (derived from Docker plugin and database connection patterns):

```
+-- Jira Connection ---------------------------------+
|                                                     |
|  Server URL:  [https://jira.company.com           ] |
|  Auth Type:   (o) Personal Access Token  ( ) OAuth  |
|  Token:       [********************************** ] |
|                                                     |
|  Board ID:    [PROJ                               ] |
|               Auto-detected from project key        |
|                                                     |
|  [Test Connection]    Connection successful ✓       |
+-----------------------------------------------------+
```

**Key patterns:**
- Use `Configurable.Composite` to split into sub-pages when the settings panel would exceed one screen
- Include a "Test Connection" button for every external service -- this is a well-established pattern across IDE plugins (Docker, Database, deployment tools)
- Show inline validation results directly below the test button rather than in a separate dialog
- Store credentials via `PasswordSafe` API (never in plain text settings files)
- Use `key` and `bundle` attributes for all display text (localization readiness)

### 6.3 Settings Implementation

- Use Kotlin UI DSL Version 2 for modern settings layout
- Apply `group("title") { }` for visual grouping and `collapsibleGroup("title") { }` for advanced settings
- Use `visibleIf(predicate)` for conditional settings (e.g., show OAuth fields only when OAuth is selected)
- Bind data with `bindText()`, `bindSelected()`, `bindItem()` for clean separation
- Implement `doValidate()` for real-time inline validation (URL format, token format)

---

## Part 7: Wizard / Onboarding Design

### 7.1 First-Run Experience

When the plugin detects no configured connections (first install), trigger a progressive onboarding flow:

**Step 1: Welcome + first connection**
Use a `GotItTooltip` anchored to the Workflow tool window button:
```
"Welcome to Workflow Orchestrator!
 Connect your tools to get started.
 [Start Setup]  [Later]"
```

GotItTooltip rules (from JetBrains guidelines):
- Show only once, never repeat after dismissal
- Maximum 5 lines of body text
- Do not cover content the user is actively working with
- Provide an escape hatch ("Later")

**Step 2: Connection wizard (if user clicks Start Setup)**
Use a `DialogWrapper` with a multi-step flow. Do NOT use a traditional wizard with Back/Next if there are fewer than 5 steps. Instead, use a single dialog with collapsible sections:

```
+-- Setup Workflow Orchestrator -----------------------+
|                                                       |
|  v Jira Connection                 [Test] Connected  |
|    Server: [https://jira.company.com    ]            |
|    Token:  [*****                       ]            |
|                                                       |
|  v Bamboo Connection               [Test] Connected  |
|    Server: [https://bamboo.company.com  ]            |
|    Token:  [*****                       ]            |
|                                                       |
|  > Bitbucket Connection            [Test] Not Set    |
|  > SonarQube Connection            [Test] Not Set    |
|  > Cody Enterprise                 [Test] Not Set    |
|                                                       |
|  Tip: You can configure any service later             |
|       in Settings > Tools > Workflow Orchestrator     |
|                                                       |
|                              [Skip]  [Finish Setup]  |
+-------------------------------------------------------+
```

**Design rationale:**
- Collapsible sections let users focus on one connection at a time (progressive disclosure)
- Each section has its own Test Connection button for immediate validation
- Services can be configured independently -- no forced ordering
- "Skip" allows users to configure partially and return later
- The same dialog can be re-opened from Settings if the user skips

### 7.2 Feature Discovery (Post-Onboarding)

Use `GotItTooltip` sparingly to introduce key features on first encounter:
- First time user opens Sprint tab: tooltip on "Start Work" button explaining one-click branching
- First time a build fails: tooltip pointing to the remediation action
- First time user opens Automation tab: tooltip explaining the queue system

**Never** show tooltips at IDE startup. Only show them when the user navigates to the relevant context.

---

## Part 8: Context Menu Integration

### 8.1 Editor Context Menu

Add actions to `EditorPopupMenu` group. Keep the plugin's submenu to ONE top-level group:

```
Right-click in editor:
  ...
  > Workflow Orchestrator
      Generate Test for Uncovered Branch    (only if gutter marker present)
      Ask Cody About This Code
      View SonarQube Issues for This File
  ...
```

**Rules:**
- Use `<add-to-group group-id="EditorPopupMenu" anchor="last"/>` to avoid pushing built-in items down
- Use `update()` to hide the entire group when the plugin is not configured
- Use `update()` to show/hide individual actions based on context (e.g., "Generate Test" only appears when cursor is on an uncovered line)
- Never add more than 5 items to the submenu

### 8.2 Project View Context Menu

Add to `ProjectViewPopupMenu`:

```
Right-click on file/folder:
  ...
  > Workflow Orchestrator
      View Coverage Report
      Check Copyright Headers
  ...
```

### 8.3 VCS Commit Dialog

Add a pre-commit check via `CheckinHandler`:
- "Run Health Check before Push" checkbox in the commit dialog (follows the pattern of "Run Tests" and "Analyze Code" checkboxes)
- Copyright header check as a pre-commit inspection

### 8.4 Menu Design Rules

From JetBrains action system guidelines:
- Action instances persist for application lifetime -- NEVER store short-lived data in action fields (causes memory leaks)
- The `update()` method runs frequently -- it must be lightweight (no API calls, no file reads)
- Use `DumbAwareAction` if the action does not depend on code indexes
- Register actions in `plugin.xml` declaratively for keymap visibility and discoverability
- Use `compact="true"` on groups to hide disabled actions rather than showing them greyed out

---

## Part 9: Layout Patterns for Key Screens

### 9.1 Sprint Tab Layout

Use a master-detail pattern (standard for list-based tool windows):

```
+-- Sprint Tab ------------------------------------------------+
| [Toolbar: Refresh | Filter by Status | Sort]                 |
|                                                               |
| +-- Ticket List (master) ----+-- Detail Panel (detail) ----+ |
| | > PROJ-123 Login Fix  [IP] |  PROJ-123: Login Fix         | |
| |   PROJ-124 API Update [TD] |  Status: In Progress         | |
| |   PROJ-125 DB Migration    |  Priority: High              | |
| |                            |  Sprint: Sprint 14           | |
| |                            |                              | |
| |                            |  Dependencies:               | |
| |                            |  - Blocked by TEAM-456 (Ext) | |
| |                            |                              | |
| |                            |  [Start Work] [Summarize]    | |
| +----------------------------+------------------------------+ |
+---------------------------------------------------------------+
```

Use `JBSplitter` with `setSplitterProportionKey()` to persist the user's preferred split ratio.

### 9.2 Build Dashboard Tab Layout

```
+-- Build Tab -------------------------------------------------+
| [Toolbar: Refresh | View: Current Branch | History]           |
|                                                               |
| Build #1234 (feature/PROJ-123-ui-fix)           2 min ago    |
| +-----------------------------------------------------------+ |
| | Artifact Build    [============================] ✓ Passed  | |
| | OSS Analysis      [============================] ✓ Passed  | |
| | SonarQube         [===============>            ] Running... | |
| +-----------------------------------------------------------+ |
|                                                               |
| Sonar Details (expandable)                                    |
| +-----------------------------------------------------------+ |
| | New Code Coverage: 87% (target: 100%)   ✗ FAILING         | |
| | Branch Coverage: 96% (target: 95%)      ✓ PASSING         | |
| | New Bugs: 0                             ✓                  | |
| | New Smells: 2                           ⚠                  | |
| +-----------------------------------------------------------+ |
+---------------------------------------------------------------+
```

Use `collapsibleGroup()` from Kotlin UI DSL for the expandable detail sections.

### 9.3 Automation Tab Layout

```
+-- Automation Tab --------------------------------------------+
| [Toolbar: Refresh | Queue Status | History]                   |
|                                                               |
| Queue Status: Running (triggered by @dev-x, ~8 min left)     |
| Your Position: #2 (estimated wait: ~12 min)                   |
|                                                               |
| +-- Staged Configuration -----------------------------------+ |
| | Service          | Your Tag           | Latest Release    | |
| |------------------|--------------------|-------------------| |
| | service-auth     | feature/PROJ-123 ▲ | release-2.3.1     | |
| | service-payments | release-2.4.0      | release-2.4.0     | |
| | service-orders   | release-1.8.0  ⚠   | release-1.8.2 NEW | |
| +-----------------------------------------------------------+ |
|                                                               |
| [Update All to Latest] [Validate Tags] [Queue Run]           |
+---------------------------------------------------------------+
```

The triangle icon (up arrow) indicates the user's changed service. The warning icon indicates a drift-detected tag. These follow the IntelliJ convention of using small icon badges to convey state changes.

---

## Part 10: Keyboard and Accessibility

### 10.1 Essential Keyboard Shortcuts

Register all major actions with the keymap system so users can customize them:

| Action | Suggested Default | Rationale |
|--------|-------------------|-----------|
| Open Workflow tool window | Alt+W | Matches Alt+number pattern for tool windows |
| Start Work on ticket | None (discoverable via action) | Infrequent, does not need a default binding |
| Refresh current tab | Ctrl+F5 (matches browser refresh) | Consistent with existing refresh patterns |
| Toggle build notifications | None | Configurable but not frequently toggled |

### 10.2 Command Palette Integration

All plugin actions should be findable via "Find Action" (Ctrl+Shift+A / Cmd+Shift+A). This is automatic when actions are registered in `plugin.xml` with proper text and descriptions. Ensure:
- Every action has a clear, searchable `text` attribute ("Workflow: Start Work on Ticket", "Workflow: Refresh Build Status")
- Use the "Workflow:" prefix so all plugin actions cluster together in search results
- Provide `description` attributes for disambiguation

### 10.3 Speed Search

Enable speed search on all lists and trees using `ListSpeedSearch` and `TreeSpeedSearch`. This lets users type to filter in the Sprint ticket list, build history, and service table.

---

## Part 11: Background Processing UX

### 11.1 What Runs in Background (Invisible to User)

| Process | Interval | Thread | Cancellation |
|---------|----------|--------|--------------|
| Jira sprint ticket polling | 120s (configurable) | Pooled background | On project close |
| Bamboo build status polling | 30s (configurable) | Pooled background | On project close |
| Bamboo queue status polling | 60s (configurable) | Pooled background | On project close |
| SonarQube coverage data fetch | On file open + 300s | Pooled background | On file close |
| Docker tag validation | On-demand only | Pooled background | User-cancelable |
| Cody API calls | On-demand only | Non-blocking read action | User-cancelable |

### 11.2 Progress Indicator Patterns

| Operation | Duration | Indicator Type |
|-----------|----------|---------------|
| Test Connection | 2-5s | Inline spinner next to button |
| Fetch sprint tickets | 1-3s | Tool window loading decorator (`LoadingDecorator`) |
| Trigger build | 1-2s | Brief inline progress, then background polling takes over |
| Generate test with Cody | 5-30s | Modal progress dialog with cancel (user is waiting for result) |
| Pre-push health check (mvn build) | 30s-5min | Background task with progress bar in status bar area |
| Queue wait | Minutes to hours | Status bar widget + background polling (no progress dialog) |

### 11.3 Implementation Rules

From JetBrains threading model guidelines:
- Never perform API calls, VFS traversal, or PSI parsing on the Event Dispatch Thread (EDT)
- Use `ReadAction.nonBlocking()` for operations that read the code model
- Call `ProgressIndicator.checkCanceled()` frequently in long loops
- Never catch or suppress `ProcessCanceledException` -- always rethrow
- Use `Application.invokeLater()` with appropriate `ModalityState` to update UI from background threads
- Event listeners must be lightweight -- clear caches only, never perform network calls

---

## Part 12: Consolidated UX Visibility Matrix

### ALWAYS VISIBLE (Zero Interaction)

| Element | UI Component | Content |
|---------|-------------|---------|
| Current ticket + build health | Status bar widget | "PROJ-123 ✓" or "PROJ-123 ✗" |
| Tool window button | Tool window bar (bottom) | "Workflow" stripe button |
| Sonar coverage gutter markers | Editor gutter | Red/yellow/grey circles on uncovered lines |
| File coverage badges | Project tree | Percentage badges on file nodes |
| Offline/degraded indicator | Status bar widget | Grey icon + "Offline" text when APIs unreachable |

### ONE-CLICK ACCESSIBLE (Single Action Required)

| Element | UI Component | Trigger |
|---------|-------------|---------|
| Sprint ticket list | Tool window tab | Click "Workflow" tool window button |
| Build status details | Tool window tab | Click "Build" tab in Workflow window |
| Queue status + staging | Tool window tab | Click "Automation" tab |
| Start Work (branch + Jira transition) | Button in Sprint tab | Click "Start Work" on selected ticket |
| Generate test for uncovered code | Gutter icon click | Click red gutter marker |
| Status bar expanded popup | Status bar popup | Click status bar widget |
| Refresh data | Toolbar button | Click refresh in any tab toolbar |
| Cody explain/summarize | Button in Sprint tab detail | Click "Summarize" on selected ticket |

### DISCOVERABLE BUT HIDDEN (Multiple Steps)

| Element | UI Component | Access Path |
|---------|-------------|-------------|
| Service connections | Settings page | Settings > Tools > Workflow Orchestrator > Connections |
| Notification preferences | Settings page | Settings > Tools > Workflow Orchestrator > Notifications |
| Branch naming patterns | Settings page | Settings > Tools > Workflow Orchestrator > Branching |
| Polling intervals | Settings page | Settings > Tools > Workflow Orchestrator > Advanced |
| Copyright template config | Settings page | Settings > Tools > Workflow Orchestrator > Advanced |
| Build timeline (Gantt) | Tab within Build tab | Click "Timeline" sub-view in Build tab |
| Productivity dashboard | Separate tab | Tab in Workflow window (Phase 2) |
| Historical build trends | Expandable section | Expand "History" in Build tab |
| Editor context actions | Right-click submenu | Right-click > Workflow Orchestrator > ... |
| Pre-push health check | Commit dialog checkbox | VCS > Commit dialog > "Run Health Check" |
| Commit message generation | Commit dialog | VCS > Commit dialog (auto-triggered when Cody enabled) |
| Regression blame analysis | Automation results | Automation tab > Results > "Investigate" |

### AUTOMATED AND INVISIBLE (No User Interaction)

| Process | Behavior | User Learns Via |
|---------|----------|-----------------|
| Background API polling | Continuous, configurable intervals | Data freshness in tool window |
| Cody summary caching | Cache per ticket ID + last-modified | Instant response on re-open |
| Credential refresh | Automatic token refresh via PasswordSafe | Transparent unless token expires |
| Build result caching | Cache last N build results locally | Fast load of Build tab |
| Exponential backoff on API failure | Auto-retry with increasing delay | Status bar shows "Offline" state |
| Commit message auto-prefix | Inject ticket ID based on active branch | Pre-filled in commit dialog |
| Docker tag latest-version lookup | Background fetch when Automation tab opens | Drift warnings in staging table |
| Local time tracking | Timestamps on Start Work / Complete Task | Pre-filled in worklog dialog |

---

## Part 13: Anti-Patterns to Avoid

### Do NOT:

1. **Show a modal dialog on IDE startup** for onboarding. Users opening IntelliJ want to code, not configure a plugin. Use a GotItTooltip on first interaction instead.

2. **Create multiple tool windows.** One tool window with tabs. Never fracture the plugin across Sprint Window, Build Window, Queue Window.

3. **Poll APIs on the EDT.** Every network call must be on a background thread. Violation causes IDE freezes that users will attribute to your plugin.

4. **Use standard Swing components.** Always use `JBList` over `JList`, `JBTable` over `JTable`, `JBColor` over `Color`, `JBUI.Borders` over standard borders. Standard components break in HiDPI and dark themes.

5. **Fire notifications for every event.** Collapse rapid events, respect notification group settings, and always include a "Don't show again" option for informational notifications.

6. **Store secrets in PersistentStateComponent XML.** Use `PasswordSafe` exclusively for tokens and credentials. PersistentStateComponent writes to plain-text XML files.

7. **Put expensive logic in `update()` methods.** Action `update()` and `isApplicableAsync()` are called frequently. They must return in microseconds with no I/O.

8. **Change the tool window icon to indicate state.** JetBrains guidelines say to add colored badges to indicate content changes, errors, or updates -- never swap the base icon itself.

9. **Add more than 5 items to a context menu submenu.** Group less-common actions under "More..." or move them to the tool window toolbar.

10. **Use `java.awt.Color` directly.** Always use `JBColor` or `JBColor.lazy()` with lambdas to support theme changes.

---

## Part 14: Theme and Visual Consistency

### Icon Requirements

- Provide SVG icons in both light and dark variants
- Tool window icons: 20x20 (standard) + 16x16 (compact) for New UI
- Light mode icon color: `#6C707E`
- Dark mode icon color: `#CED0D6`
- Register icon mappings via `$PluginName$IconMappings.json` and `com.intellij.iconMapper` extension point
- Reuse existing platform icons (`AllIcons.*`) wherever the concept matches -- do not create custom icons for standard concepts (refresh, settings, filter, search)

### Color Usage

- Use `JBColor.lazy()` for all custom colors to support live theme switching
- Use `UIUtil` and `JBUI` for accessing standard UI colors
- Use `ColorUtil` to derive shades from existing theme colors rather than hardcoding
- Test all UI in both IntelliJ Light and Darcula themes before release

### Text Formatting

- Use `StringUtil` for natural sorting, file size formatting, duration formatting
- Use `ColoredListCellRenderer` / `ColoredTreeCellRenderer` for multi-styled text in lists and trees (e.g., ticket ID in bold, title in regular, status in colored tag)
- Follow "Writing Short and Clear" guidelines from JetBrains: sentence case for labels, title case for tool window names only

---

## Part 15: Reference Patterns from Production Plugins

### Docker Plugin Pattern (Services Tool Window)
- Single Services tool window with tree hierarchy
- Connection nodes > Container/Image nodes
- Detail panel on selection
- Toolbar with Connect, Add, Configure actions
- Configuration-first approach (Settings before usage)
- **Applicable to your plugin:** Automation tab staging area should follow the master-detail pattern

### SonarLint Pattern (Multi-Surface Integration)
- Dedicated tool window for issue list
- Gutter icons in editor for issue markers with severity coloring
- Editor banners for file-level warnings
- Status bar indicator for connection/analysis status
- Context menu integration for "Analyze this file"
- **Applicable to your plugin:** Quality module should mirror this multi-surface approach -- gutter markers + tool window detail + editor banners

### GitToolBox Pattern (Ambient Information)
- Status bar widget showing branch + behind/ahead count
- Inline blame annotations in editor
- Minimal tool window usage -- information is ambient
- **Applicable to your plugin:** Status bar widget design should follow this minimal ambient pattern

### Run/Debug Pattern (Execution Monitoring)
- Bottom-docked tool window with tabs per run
- Real-time console output
- Toolbar with Stop, Rerun, Pin actions
- Tree view for test results with pass/fail icons
- **Applicable to your plugin:** Build Dashboard and Automation Results should follow this execution monitoring pattern

---

## Summary Checklist for Implementation

- [ ] Single tool window "Workflow" with 5 tabs, bottom-docked by default
- [ ] Status bar widget: text-with-popup showing ticket + build health
- [ ] Gutter icons for SonarQube coverage (red/yellow/grey, 12x12 Classic, 14x14 New UI)
- [ ] Project tree badges for file coverage percentages
- [ ] Notification groups registered per category (build, quality, queue, automation)
- [ ] Settings under Tools > Workflow Orchestrator with 5 sub-pages
- [ ] Onboarding via GotItTooltip + collapsible connection dialog
- [ ] Context menu submenu with max 5 context-sensitive actions
- [ ] All API calls on background threads with ProgressIndicator
- [ ] Empty states for every tab following "No [entity] [state]." pattern
- [ ] Speed search enabled on all lists and trees
- [ ] All icons in SVG with light/dark variants
- [ ] All colors via JBColor, all borders via JBUI.Borders
- [ ] All actions registered in plugin.xml with "Workflow:" prefix for Find Action
- [ ] Credentials in PasswordSafe, never in PersistentStateComponent
