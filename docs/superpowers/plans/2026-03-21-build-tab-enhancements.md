# Build Tab Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enhance the Build tab with build history, stop/cancel controls, log search, tab order fix, build trigger with custom variables, and artifact download.

**Architecture:** Wire existing but unused BambooApiClient methods (stop, cancel, artifacts, history). Add new UI panels for build history list and artifact viewer. Enhance StageDetailPanel with log search. Fix tab ordering in WorkflowToolWindowFactory. Performance: lazy-load history and artifacts, cache build results.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, Bamboo REST API, OkHttp

---

## Dependency Graph

```
Task 1 (API: artifacts endpoint + wire unused methods) — foundation
  ├── Task 3 (Stop/Cancel build buttons)
  └── Task 6 (Artifact list + download)

Task 2 (Build history list) — uses existing getRecentResults API
Task 4 (Log search) — independent, StageDetailPanel only
Task 5 (Tab order fix) — independent, core module
Task 7 (Build trigger with variables) — independent, extends ManualStageDialog
```

---

### Task 1: API — Artifacts Endpoint + Wire Unused Methods

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt`

- [ ] **Step 1: Add artifact list endpoint to BambooApiClient**

```kotlin
@Serializable
data class BambooArtifactResponse(
    val artifacts: BambooArtifactList = BambooArtifactList()
)

@Serializable
data class BambooArtifactList(
    val artifact: List<BambooArtifact> = emptyList()
)

@Serializable
data class BambooArtifact(
    val name: String = "",
    val link: BambooArtifactLink? = null,
    val producerJobKey: String = "",
    val shared: Boolean = false,
    val size: Long = 0
)

@Serializable
data class BambooArtifactLink(
    val href: String = ""
)

suspend fun getArtifacts(resultKey: String): ApiResult<List<BambooArtifact>> {
    return get<BambooArtifactResponse>(
        "/rest/api/latest/result/$resultKey?expand=artifacts.artifact"
    ).map { it.artifacts.artifact }
}
```

- [ ] **Step 2: Add downloadArtifact method**

```kotlin
suspend fun downloadArtifact(artifactUrl: String, targetFile: File): Boolean {
    // GET the artifact URL with auth, write bytes to targetFile
    // Return true on success, false on failure
}
```

- [ ] **Step 3: Wire stop/cancel into BambooServiceImpl**

The `stopBuild()` and `cancelBuild()` methods exist in BambooApiClient but are not exposed via BambooServiceImpl. Add:

```kotlin
suspend fun stopBuild(resultKey: String): ToolResult<Unit> {
    return try {
        api.stopBuild(resultKey)
        ToolResult.success(Unit, "Build $resultKey stopped")
    } catch (e: Exception) {
        ToolResult.error("Failed to stop build: ${e.message}")
    }
}

suspend fun cancelBuild(resultKey: String): ToolResult<Unit> {
    return try {
        api.cancelBuild(resultKey)
        ToolResult.success(Unit, "Build $resultKey cancelled")
    } catch (e: Exception) {
        ToolResult.error("Failed to cancel build: ${e.message}")
    }
}

suspend fun getArtifacts(resultKey: String): ToolResult<List<BambooArtifact>> {
    return try {
        val result = api.getArtifacts(resultKey)
        when (result) {
            is ApiResult.Success -> ToolResult.success(result.data, "Found ${result.data.size} artifacts")
            is ApiResult.Error -> ToolResult.error("Failed to fetch artifacts: ${result.message}")
        }
    } catch (e: Exception) {
        ToolResult.error("Failed to fetch artifacts: ${e.message}")
    }
}
```

- [ ] **Step 4: Build and commit**

Run: `./gradlew :bamboo:compileKotlin`

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/
git commit -m "feat(bamboo): add artifacts API, wire stop/cancel/artifacts into service layer"
```

---

### Task 2: Build History List

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt`

- [ ] **Step 1: Add getRecentBuilds to BambooServiceImpl**

```kotlin
suspend fun getRecentBuilds(planKey: String, maxResults: Int = 10): ToolResult<List<BambooBuildResult>> {
    return try {
        val result = api.getRecentResults(planKey, maxResults)
        when (result) {
            is ApiResult.Success -> ToolResult.success(result.data, "${result.data.size} recent builds")
            is ApiResult.Error -> ToolResult.error("Failed: ${result.message}")
        }
    } catch (e: Exception) {
        ToolResult.error("Failed: ${e.message}")
    }
}
```

Note: `BambooApiClient.getRecentResults()` already exists (endpoint #14). Check its return type and map accordingly.

- [ ] **Step 2: Add build history panel to BuildDashboardPanel**

Add a "History" section above or below the stage list (or as a collapsible section in the header). Show last 10 builds:

```
#142  ✓ Success   2m 15s   Mar 20, 14:30
#141  ✗ Failed    1m 45s   Mar 20, 11:20
#140  ✓ Success   2m 30s   Mar 19, 16:00
```

Each row: build number (link-colored), status icon, duration, timestamp.

Clicking a build row loads that build's stages/logs in the detail panels below (replacing the current "latest build" view).

Use a `JBList` with a cached cell renderer. Load history lazily after the latest build is displayed.

- [ ] **Step 3: Add "Latest" indicator and navigation**

When viewing a historical build, show a banner: "Viewing build #140 — [View Latest]" that returns to the latest build.

- [ ] **Step 4: Build and commit**

Run: `./gradlew :bamboo:compileKotlin`

```bash
git add bamboo/
git commit -m "feat(bamboo): build history list showing last 10 builds with click-to-view"
```

---

### Task 3: Stop/Cancel Build Buttons

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`

- [ ] **Step 1: Add Stop and Cancel toolbar actions**

Add two new toolbar actions (conditionally enabled):

```kotlin
// Stop button — enabled only when build is IN_PROGRESS
private inner class StopBuildAction : AnAction("Stop Build", "Stop the running build", AllIcons.Actions.Suspend) {
    override fun actionPerformed(e: AnActionEvent) {
        val resultKey = currentResultKey ?: return
        scope.launch {
            bambooService.stopBuild(resultKey)
            // Refresh after short delay
        }
    }
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = currentBuildStatus == BuildStatus.IN_PROGRESS
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

// Cancel button — enabled only when build is QUEUED
private inner class CancelBuildAction : AnAction("Cancel Build", "Cancel the queued build", AllIcons.Actions.Cancel) {
    override fun actionPerformed(e: AnActionEvent) {
        val resultKey = currentResultKey ?: return
        scope.launch {
            bambooService.cancelBuild(resultKey)
        }
    }
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = currentBuildStatus == BuildStatus.QUEUED
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
```

Add both to the toolbar between Refresh and Rerun. Track `currentResultKey` and `currentBuildStatus` from the build state flow.

- [ ] **Step 2: Show confirmation before stop/cancel**

Use `Messages.showYesNoDialog()` before stopping or cancelling.

- [ ] **Step 3: Build and commit**

Run: `./gradlew :bamboo:compileKotlin`

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt
git commit -m "feat(bamboo): stop and cancel build buttons in toolbar"
```

---

### Task 4: Log Search

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt`

- [ ] **Step 1: Add search bar to log tab**

Add a `SearchTextField` above the console view in the Log tab:

```kotlin
private val logSearchField = SearchTextField(false).apply {
    textEditor.emptyText.text = "Search in log..."
}
```

- [ ] **Step 2: Implement search with highlighting**

When the user types, find all occurrences in the log text and scroll to the first match:

```kotlin
private fun searchLog(query: String) {
    if (query.isBlank() || currentLogText.isNullOrBlank()) return

    val text = currentLogText!!
    val index = text.indexOf(query, ignoreCase = true)
    if (index >= 0) {
        // Scroll console to the match position
        // Highlight matches in the console editor
    }

    // Show match count: "3 of 12 matches"
    updateSearchStatus(matchCount, currentMatch)
}
```

Use IntelliJ's `FindModel` and `FindUtil` for proper search highlighting in the `ConsoleView`'s editor, or implement simple text search with `editor.caretModel.moveToOffset()`.

Add Next/Previous match navigation (Enter for next, Shift+Enter for previous).

Store `currentLogText` when `showLog()` is called.

- [ ] **Step 3: Build and commit**

Run: `./gradlew :bamboo:compileKotlin`

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt
git commit -m "feat(bamboo): log search with match highlighting and navigation"
```

---

### Task 5: Tab Order Fix (Sprint → PR → Build)

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt`

- [ ] **Step 1: Change tab order**

Find where `DefaultTab` enum or tab order is defined. Change from:

```
Sprint (0), Build (1), PR (2), Quality (3), Automation (4), Handover (5)
```

To:

```
Sprint (0), PR (1), Build (2), Quality (3), Automation (4), Handover (5)
```

This means swapping the order values of Build and PR tabs.

- [ ] **Step 2: Update any hardcoded tab index references**

Search for hardcoded tab indices (e.g., `getOrNull(3)` for Quality tab in CoverageBannerProvider). Update:
- Sprint: 0 (unchanged)
- PR: 1 (was 2)
- Build: 2 (was 1)
- Quality: 3 (unchanged)
- Automation: 4 (unchanged)
- Handover: 5 (unchanged)

Check files:
- `sonar/src/main/kotlin/.../ui/CoverageBannerProvider.kt` — uses `getOrNull(3)` for Quality tab
- `core/src/main/kotlin/.../toolwindow/WorkflowToolWindowFactory.kt` — active ticket header clicks tab 0

- [ ] **Step 3: Build and commit**

Run: `./gradlew compileKotlin`

```bash
git add core/ sonar/
git commit -m "fix(core): correct tab order — Sprint → PR → Build → Quality → Automation → Handover"
```

---

### Task 6: Artifact List + Download

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt`

- [ ] **Step 1: Add Artifacts tab to StageDetailPanel**

Add a third tab "Artifacts" to the `JBTabbedPane` (alongside Log and Tests):

```kotlin
private val artifactsPanel = JPanel(BorderLayout())
tabbedPane.addTab("Artifacts", artifactsPanel)
```

- [ ] **Step 2: Populate artifacts list**

When a build is selected, fetch artifacts via `bambooService.getArtifacts(resultKey)` and display:

```
build-output.jar          12.5 MB    [Download]
test-report.html          245 KB     [Download]  [Open in Browser]
coverage-report.xml       89 KB      [Download]
```

Each row: name, size (formatted), download button, optional "Open in Browser" for HTML artifacts.

Use a `JBList` with cached renderer. Show loading spinner while fetching. Empty state: "No artifacts for this build."

- [ ] **Step 3: Implement download action**

On "Download" click:
1. Show `FileChooser` directory picker
2. Download via `BambooApiClient.downloadArtifact(artifactUrl, targetFile)` on `Dispatchers.IO`
3. Show notification: "Downloaded {name} to {path}"

On "Open in Browser" click:
- `BrowserUtil.browse(artifact.link.href)`

- [ ] **Step 4: Build and commit**

Run: `./gradlew :bamboo:compileKotlin`

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/StageDetailPanel.kt
git commit -m "feat(bamboo): artifacts tab with download and browser open"
```

---

### Task 7: Build Trigger with Custom Variables

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/ManualStageDialog.kt`

- [ ] **Step 1: Add "Trigger Build" toolbar action**

Add a new toolbar action that opens a dialog for triggering a build with custom variables:

```kotlin
private inner class TriggerBuildAction : AnAction("Trigger Build", "Trigger a new build with custom variables", AllIcons.Actions.Execute) {
    override fun actionPerformed(e: AnActionEvent) {
        val planKey = currentPlanKey ?: return
        openTriggerDialog(planKey)
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
```

- [ ] **Step 2: Enhance ManualStageDialog for full build trigger**

The existing `ManualStageDialog` already shows plan variables with editable fields. Extend it to support triggering the entire build (not just a stage):

- Add a `triggerMode` parameter: `FULL_BUILD` or `STAGE`
- When `FULL_BUILD`: title says "Trigger Build", OK button says "Trigger", calls `bambooService.triggerBuild(planKey, variables)` without stage parameter
- When `STAGE`: existing behavior (trigger specific stage)

- [ ] **Step 3: Build and commit**

Run: `./gradlew :bamboo:compileKotlin`

```bash
git add bamboo/
git commit -m "feat(bamboo): trigger build with custom variables dialog"
```

---

## Final Verification

- [ ] Run full build: `./gradlew buildPlugin`
- [ ] Run tests: `./gradlew :bamboo:test`
- [ ] Verify in IDE: `./gradlew runIde`
  - Build tab: history list, stop/cancel buttons, log search, artifacts tab
  - Tab order: Sprint → PR → Build → Quality → Automation → Handover
  - Trigger build with variables dialog
- [ ] Update `bamboo/CLAUDE.md` with new features
- [ ] Update `core/CLAUDE.md` with tab order change
