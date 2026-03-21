# Sprint Tab Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enhance the Sprint tab with quick comments, PR/build status per ticket, worklog summaries, sprint time remaining, grouping/sorting, multi-sprint view, cross-tab active ticket visibility, and user-confirmed branch→ticket detection.

**Architecture:** Extend existing SprintDashboardPanel, TicketDetailPanel, and JiraApiClient. New UI sections follow the existing lazy-load pattern. Branch detection becomes a user-confirmed flow with popup + notification fallback. Active ticket visibility added to the tool window header, shared across all tabs.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, Jira REST API v2 + Agile API, Bitbucket Dev Status API

**Spec:** Brainstormed in conversation — 9 items agreed upon.

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `jira/src/.../ui/TicketDetectionPopup.kt` | Popup dialog for branch→ticket confirmation |
| ~~`jira/src/.../ui/TicketDetectionNotification.kt`~~ | Handled inline in SprintDashboardPanel (detection banner) |
| `jira/src/.../ui/WorklogSection.kt` | Worklog summary section for TicketDetailPanel |
| `jira/src/.../ui/DevStatusSection.kt` | PR/Build status section for TicketDetailPanel |
| `jira/src/.../ui/SprintTimeBar.kt` | Sprint time remaining + ticket progress composite bar |
| `jira/src/.../ui/QuickCommentPanel.kt` | Inline comment input at bottom of TicketDetailPanel |

### Modified Files
| File | Changes |
|------|---------|
| `jira/src/.../api/JiraApiClient.kt` | Add `getWorklogs()`, `getClosedSprints()`, `getDevStatusPullRequests()` |
| `jira/src/.../api/dto/JiraDtos.kt` | Add `JiraWorklog`, `DevStatusPullRequest` DTOs |
| `jira/src/.../service/SprintService.kt` | Add multi-sprint loading, sprint selection |
| `jira/src/.../ui/SprintDashboardPanel.kt` | Sprint time bar, sort/group controls, multi-sprint selector, detection notification |
| `jira/src/.../ui/TicketDetailPanel.kt` | Add worklog, dev-status, quick comment sections |
| `jira/src/.../ui/CurrentWorkSection.kt` | Minor: refresh from detection |
| `jira/src/.../listeners/BranchChangeTicketDetector.kt` | User-confirmed flow instead of auto-set |
| `core/src/.../toolwindow/WorkflowToolWindowFactory.kt` | Active ticket header bar across all tabs |
| `core/src/.../events/WorkflowEvent.kt` | Add `TicketDetected` event if needed |

---

## Dependency Graph

```
Task 1 (API + DTOs) — foundation, no UI
  ├── Task 2 (Sprint time bar) — uses sprint dates
  ├── Task 3 (Worklog section) — uses getWorklogs API, modifies TicketDetailPanel
  ├── Task 4 (Dev status section) — uses getDevStatusPullRequests API, modifies TicketDetailPanel — run AFTER Task 3
  └── Task 5 (Multi-sprint view) — uses getClosedSprints API

Task 6 (Sort/Group controls) — independent, client-side only
Task 7 (Quick comment) — modifies TicketDetailPanel — run AFTER Task 4
Task 8 (Branch→ticket detection) — independent
Task 9 (Active ticket header) — independent, core module
```

---

### Task 1: API Extensions + DTOs

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/dto/JiraDtos.kt`

- [ ] **Step 1: Add JiraWorklog DTO**

In `JiraDtos.kt`, add after the existing DTOs:

```kotlin
@Serializable
data class JiraWorklogResponse(
    val worklogs: List<JiraWorklog> = emptyList(),
    val total: Int = 0
)

@Serializable
data class JiraWorklog(
    val author: JiraUser? = null,
    val timeSpent: String = "",
    val timeSpentSeconds: Long = 0,
    val comment: String? = null,
    val started: String = "",
    val created: String = ""
)
```

- [ ] **Step 2: Add getWorklogs to JiraApiClient**

```kotlin
suspend fun getWorklogs(issueKey: String, maxResults: Int = 20): ApiResult<JiraWorklogResponse> {
    val encoded = URLEncoder.encode(issueKey, "UTF-8")
    return get<JiraWorklogResponse>("/rest/api/2/issue/$encoded/worklog?maxResults=$maxResults")
}
```

- [ ] **Step 3: Add getClosedSprints to JiraApiClient**

```kotlin
suspend fun getClosedSprints(boardId: Int, maxResults: Int = 5): ApiResult<List<JiraSprint>> {
    return get<JiraSprintSearchResult>("/rest/agile/1.0/board/$boardId/sprint?state=closed&maxResults=$maxResults")
        .map { it.values }
}
```

- [ ] **Step 4: Add getDevStatusPullRequests to JiraApiClient**

Extend the existing `getDevStatusBranches` pattern. The dev-status API already has `DevStatusPullRequest` DTO — verify it exists in JiraDtos.kt and add if not:

```kotlin
suspend fun getDevStatusPullRequests(issueId: String): ApiResult<List<DevStatusPullRequest>> {
    return try {
        val response = get<DevStatusResponse>(
            "/rest/dev-status/1.0/issue/detail?issueId=$issueId&applicationType=stash&dataType=pullrequest"
        )
        response.map { it.detail.flatMap { d -> d.pullRequests } }
    } catch (e: Exception) {
        ApiResult.Success(emptyList()) // graceful degradation
    }
}
```

Ensure `DevStatusPullRequest` DTO has: `name`, `url`, `status`, `lastUpdate`.

- [ ] **Step 5: Build and commit**

Run: `./gradlew :jira:compileKotlin`

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/api/
git commit -m "feat(jira): add worklog, closed sprints, and dev-status PR API methods"
```

---

### Task 2: Sprint Time Remaining Bar

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintTimeBar.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`

- [ ] **Step 1: Create SprintTimeBar component**

A composite panel showing two bars stacked:
1. **Time bar** — elapsed vs total sprint duration, with "X days remaining" label. Turns warning color when < 2 days, error when < 1 day.
2. **Ticket bar** — existing done/in-progress/todo proportional bar (moved here from SprintDashboardPanel).

```kotlin
class SprintTimeBar : JPanel(BorderLayout()) {
    private val timeLabel = JBLabel()       // "5 days remaining" or "Sprint ends Mar 28"
    private val ticketLabel = JBLabel()     // "8 done · 3 in progress · 2 to do"

    fun update(sprint: JiraSprint?, doneCount: Int, inProgressCount: Int, todoCount: Int) {
        // Compute days remaining from sprint.endDate
        // Update time bar color based on urgency
        // Update ticket breakdown bar
    }
}
```

The time bar should use `StatusColors.SUCCESS` (plenty of time), `StatusColors.WARNING` (< 2 days), `StatusColors.ERROR` (< 1 day or overdue).

- [ ] **Step 2: Integrate into SprintDashboardPanel**

Replace the existing progress bar section (lines ~187-189, ~683-756) with the new `SprintTimeBar`. Call `sprintTimeBar.update(sprintService.activeSprint, doneCount, inProgressCount, todoCount)` after loading data.

Add a tooltip to the time bar showing: "Sprint: {name} | Started: {startDate} | Ends: {endDate} | {doneCount}/{totalCount} tickets done"

- [ ] **Step 3: Build and commit**

Run: `./gradlew :jira:compileKotlin`

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintTimeBar.kt \
       jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt
git commit -m "feat(jira): sprint time remaining bar with urgency colors and ticket breakdown"
```

---

### Task 3: Worklog Summary Section

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/WorklogSection.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt`

- [ ] **Step 1: Create WorklogSection**

A lazy-loaded section showing:
- Total time logged (sum of timeSpentSeconds, formatted as "Xh Ym")
- Last 5 worklogs: author, time spent, date, optional comment (truncated)

```kotlin
class WorklogSection(private val project: Project) : JPanel(BorderLayout()) {
    fun loadWorklogs(issueKey: String) {
        // Async fetch via JiraApiClient.getWorklogs()
        // Show "Loading worklogs..." placeholder
        // On success, render summary + recent entries
        // On empty, show "No time logged."
    }
}
```

Each worklog entry: `"{author} logged {timeSpent} on {date}"` with optional comment below.

- [ ] **Step 2: Add to TicketDetailPanel**

In `showIssue()`, after the dependencies section (~line 99), add:

```kotlin
addSectionHeader("Time Logged")
val worklogSection = WorklogSection(project)
addFullWidthComponent(worklogSection)
worklogSection.loadWorklogs(issue.key)
```

- [ ] **Step 3: Build and commit**

Run: `./gradlew :jira:compileKotlin`

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/WorklogSection.kt \
       jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt
git commit -m "feat(jira): worklog summary section in ticket detail panel"
```

---

### Task 4: PR/Build Status Section

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/DevStatusSection.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt`

- [ ] **Step 1: Create DevStatusSection**

Shows linked PRs and their status:
- PR name, status (OPEN/MERGED/DECLINED with StatusColors), link to open in browser
- If build info is available from the PR, show build status

```kotlin
class DevStatusSection(private val project: Project) : JPanel(BorderLayout()) {
    fun loadDevStatus(issueId: String) {
        // Fetch via getDevStatusPullRequests(issueId)
        // Show "Loading..." placeholder
        // Render PR list with status badges
        // Empty: "No pull requests linked."
    }
}
```

Each PR row: `[STATUS_BADGE] PR #123: "Fix login flow" → target-branch`
Status badge colors: OPEN → `StatusColors.SUCCESS`, MERGED → `StatusColors.MERGED`, DECLINED → `StatusColors.ERROR`.

- [ ] **Step 2: Add to TicketDetailPanel**

In `showIssue()`, after subtasks section, add:

```kotlin
addSectionHeader("Pull Requests")
val devStatusSection = DevStatusSection(project)
addFullWidthComponent(devStatusSection)
devStatusSection.loadDevStatus(issue.id)
```

- [ ] **Step 3: Build and commit**

Run: `./gradlew :jira:compileKotlin`

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/DevStatusSection.kt \
       jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt
git commit -m "feat(jira): PR/build status section in ticket detail panel via dev-status API"
```

---

### Task 5: Multi-Sprint View

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/SprintService.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`

- [ ] **Step 1: Add sprint listing to SprintService**

```kotlin
suspend fun loadAvailableSprints(boardId: Int): List<JiraSprint> {
    val active = apiClient.getActiveSprints(boardId)
    val closed = apiClient.getClosedSprints(boardId, maxResults = 5)
    // Combine: active sprints first, then recent closed (sorted by endDate desc)
    return (active + closed).distinctBy { it.id }
}
```

Add `fun loadSprintById(sprintId: Int, allUsers: Boolean)` to load issues for a specific sprint.

- [ ] **Step 2: Add sprint selector to SprintDashboardPanel**

Add a `JComboBox<JiraSprint>` dropdown in the header area, next to the sprint name. When the user selects a different sprint, reload issues for that sprint.

```kotlin
val sprintSelector = com.intellij.openapi.ui.ComboBox<JiraSprint>().apply {
    renderer = SimpleListCellRenderer.create("") { it.name + if (it.state == "active") " (Active)" else " (Closed)" }
}
sprintSelector.addActionListener {
    val selected = sprintSelector.selectedItem as? JiraSprint ?: return@addActionListener
    loadSprintIssues(selected)
}
```

- [ ] **Step 3: Build and commit**

Run: `./gradlew :jira:compileKotlin`

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/service/SprintService.kt \
       jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt
git commit -m "feat(jira): multi-sprint view with dropdown selector for active and recent closed sprints"
```

---

### Task 6: Sort and Group Controls

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`

- [ ] **Step 1: Add sort/group toolbar controls**

Add two combo boxes below the search field:

**Group by:** None | Assignee (current default) | Status | Priority | Type
**Sort by:** Default | Priority (High→Low) | Status | Updated (Recent first) | Key

```kotlin
val groupByCombo = com.intellij.openapi.ui.ComboBox(arrayOf("Assignee", "Status", "Priority", "Type", "None"))
val sortByCombo = com.intellij.openapi.ui.ComboBox(arrayOf("Default", "Priority", "Status", "Updated", "Key"))
```

- [ ] **Step 2: Implement grouping logic**

Extend the existing `updateList()` method (lines 358-392) to support different grouping modes. Currently it only groups by assignee. Add:

```kotlin
private fun groupIssues(issues: List<JiraIssue>, groupBy: String): Map<String, List<JiraIssue>> {
    return when (groupBy) {
        "Assignee" -> issues.groupBy { it.fields.assignee?.displayName ?: "Unassigned" }
        "Status" -> issues.groupBy { it.fields.status.name }
        "Priority" -> issues.groupBy { it.fields.priority?.name ?: "None" }
        "Type" -> issues.groupBy { it.fields.issuetype?.name ?: "Unknown" }
        "None" -> mapOf("All Tickets" to issues)
        else -> mapOf("All Tickets" to issues)
    }
}
```

- [ ] **Step 3: Implement sorting logic**

```kotlin
private fun sortIssues(issues: List<JiraIssue>, sortBy: String): List<JiraIssue> {
    return when (sortBy) {
        "Priority" -> issues.sortedBy { priorityOrder(it.fields.priority?.name) }
        "Status" -> issues.sortedBy { statusOrder(it.fields.status.statusCategory?.key) }
        "Updated" -> issues.sortedByDescending { it.fields.updated ?: "" }
        "Key" -> issues.sortedBy { it.key }
        else -> issues // Default order from Jira
    }
}
```

- [ ] **Step 4: Wire controls to list updates**

Both combos trigger `applyFilter()` which calls `updateList()` with the current group/sort settings. Persist user's choice in `PluginSettings` so it survives IDE restarts.

- [ ] **Step 5: Build and commit**

Run: `./gradlew :jira:compileKotlin`

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt
git commit -m "feat(jira): sort and group controls for sprint ticket list"
```

---

### Task 7: Quick Comment

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/QuickCommentPanel.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt`

- [ ] **Step 1: Create QuickCommentPanel**

A compact input bar at the bottom of the ticket detail:

```kotlin
class QuickCommentPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val commentField = JBTextField()
    private val sendButton = JButton(AllIcons.Actions.Execute)

    init {
        commentField.emptyText.text = "Add a comment..."
        sendButton.toolTipText = "Post comment"
        sendButton.addActionListener { postComment() }
        // Enter key also posts
        commentField.addActionListener { postComment() }

        add(commentField, BorderLayout.CENTER)
        add(sendButton, BorderLayout.EAST)
        border = JBUI.Borders.empty(4, 8)
    }

    var issueKey: String = ""

    private fun postComment() {
        val text = commentField.text.trim()
        if (text.isEmpty() || issueKey.isEmpty()) return
        sendButton.isEnabled = false
        // Async post via JiraService.addComment(issueKey, text)
        // On success: clear field, refresh comments section, show brief "Comment posted" status
        // On error: re-enable button, show error
    }
}
```

- [ ] **Step 2: Add to TicketDetailPanel**

Add the QuickCommentPanel at the very bottom of the detail panel (after comments section), pinned to `BorderLayout.SOUTH` of the root panel so it stays visible while scrolling:

```kotlin
private val quickCommentPanel = QuickCommentPanel(project)

// In showIssue():
quickCommentPanel.issueKey = issue.key
quickCommentPanel.isVisible = true
```

After posting, trigger a comments refresh in the detail panel.

- [ ] **Step 3: Build and commit**

Run: `./gradlew :jira:compileKotlin`

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/QuickCommentPanel.kt \
       jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetailPanel.kt
git commit -m "feat(jira): quick comment input bar in ticket detail panel"
```

---

### Task 8: Branch→Ticket Detection with User Confirmation

**Files:**
- Create: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetectionPopup.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/listeners/BranchChangeTicketDetector.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`

- [ ] **Step 1: Create TicketDetectionPopup**

A lightweight popup (not a full dialog) showing detected ticket info:

```kotlin
class TicketDetectionPopup(
    private val project: Project,
    private val ticketKey: String,
    private val summary: String,
    private val sprint: String?,
    private val assignee: String?,
    private val onAccept: () -> Unit,
    private val onDismiss: () -> Unit
) {
    fun show(component: Component) {
        // JBPopupFactory.getInstance().createComponentPopupBuilder(...)
        // Content: ticket key (bold), summary, sprint name, assignee
        // Two buttons: "Set as Active" (accept) and "Dismiss" (decline)
    }
}
```

Layout:
```
┌──────────────────────────────────┐
│ Detected ticket from branch      │
│                                  │
│  PROJ-123                        │
│  Fix login validation bug        │
│  Sprint: Sprint 42 | User: John  │
│                                  │
│  [Set as Active]    [Dismiss]    │
└──────────────────────────────────┘
```

- [ ] **Step 2: Modify BranchChangeTicketDetector**

Change the flow:
1. Detect ticket ID from branch name (existing logic)
2. **If no active ticket is set** (or active ticket differs from detected):
   - Fetch ticket details from Jira (existing)
   - Check if this branch was previously dismissed (maintain a `dismissedBranches: MutableSet<String>` in memory)
   - If not dismissed: show `TicketDetectionPopup`
   - If dismissed: emit event for notification banner instead
3. **On accept**: Set active ticket in PluginSettings + ActiveTicketService (no branch creation, no status transition)
4. **On dismiss**: Add branch to `dismissedBranches`, emit event for notification banner

- [ ] **Step 3: Add notification banner to SprintDashboardPanel**

When a detection is dismissed, show a subtle clickable banner at the top of the Sprint tab:

```kotlin
private val detectionBanner = JPanel(BorderLayout()).apply {
    background = StatusColors.INFO_BG
    border = JBUI.Borders.empty(4, 8)
    isVisible = false
}
private val detectionLabel = JBLabel().apply {
    foreground = StatusColors.LINK
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
}
```

Text: "Detected: PROJ-123 — Fix login bug" (clickable, re-shows popup).

- [ ] **Step 4: Build and commit**

Run: `./gradlew :jira:compileKotlin`

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketDetectionPopup.kt \
       jira/src/main/kotlin/com/workflow/orchestrator/jira/listeners/BranchChangeTicketDetector.kt \
       jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt
git commit -m "feat(jira): user-confirmed branch→ticket detection with popup and notification fallback"
```

---

### Task 9: Active Ticket Header Across All Tabs

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt`

- [ ] **Step 1: Add active ticket header bar**

Add a compact header bar above the tab content in the tool window showing the active ticket:

```kotlin
private fun createActiveTicketBar(project: Project): JPanel {
    val bar = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(2, 8)
        background = StatusColors.INFO_BG
    }
    val ticketLabel = JBLabel().apply {
        icon = AllIcons.Nodes.Tag  // or a ticket-like icon
    }
    // Update from PluginSettings.state.activeTicketId + activeTicketSummary
    // If no active ticket, hide the bar
    // Click opens the Sprint tab and selects the ticket
    return bar
}
```

Place this bar between the title actions and the tab content using `BorderLayout.NORTH` on a wrapper panel.

- [ ] **Step 2: Subscribe to TicketChanged events**

Listen for `TicketChanged` events via EventBus to update the header bar dynamically:

```kotlin
scope.launch {
    EventBus.events.filterIsInstance<WorkflowEvent.TicketChanged>().collect { event ->
        invokeLater {
            updateActiveTicketBar(event.ticketId, event.summary)
        }
    }
}
```

When clicked, switch to Sprint tab and select the ticket.

- [ ] **Step 3: Build and commit**

Run: `./gradlew :core:compileKotlin`

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt
git commit -m "feat(core): active ticket header bar visible across all tabs"
```

---

## Final Verification

After all tasks complete:

- [ ] Run full build: `./gradlew buildPlugin`
- [ ] Run tests: `./gradlew test`
- [ ] Verify in IDE: `./gradlew runIde`
  - Sprint tab: check time bar, sort/group, multi-sprint selector
  - Ticket detail: check worklog section, PR status, quick comment
  - Branch switch: check detection popup
  - Other tabs: check active ticket header bar
- [ ] Update `jira/CLAUDE.md` with new API endpoints and UI sections
- [ ] Update `core/CLAUDE.md` with active ticket header
- [ ] Update `docs/architecture/` if needed
