# Cross-Tab Multi-Repo Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect PR, Build, and Quality tabs so that selecting a PR in the PR tab drives the repo context in Build and Quality tabs, with proper multi-repo awareness and contextual hints.

**Architecture:** Enrich `PrSelected` event with repo fields (`repoName`, `bambooPlanKey`, `sonarProjectKey`). Add a `PrContext` state map to `EventBus` so tabs can query the last-selected PR per repo. Build and Quality tabs subscribe to `PrSelected` for live updates and query the map when the user manually switches their repo selector.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, Swing (JBColor, ComboBox, JBLabel), coroutines (SharedFlow), EventBus (SharedFlow-based)

**Spec:** `docs/superpowers/specs/2026-04-06-cross-tab-multi-repo-integration-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `core/events/WorkflowEvent.kt` | Modify | Add repo fields to `PrSelected` |
| `core/events/EventBus.kt` | Modify | Add `PrContext` data class + `prContextMap` |
| `pullrequest/ui/PrDashboardPanel.kt` | Modify | Resolve `RepoConfig` when emitting enriched `PrSelected` |
| `bamboo/ui/BuildDashboardPanel.kt` | Modify | EventBus subscription, all-repos selector, hint states, PrContext lookup |
| `bamboo/ui/PrBar.kt` | Modify | Slim down to read-only PR info strip |
| `sonar/service/SonarDataService.kt` | Modify | Use event's `sonarProjectKey`, add `refreshForBranch(branch, projectKey)` overload |
| `sonar/ui/QualityDashboardPanel.kt` | Modify | EventBus subscription, all-repos selector, hint states, PrContext lookup |

---

### Task 1: Enrich PrSelected Event and Add PrContext to EventBus

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt:122-126`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt`

- [ ] **Step 1: Add repo fields to PrSelected**

In `WorkflowEvent.kt`, replace lines 121-126:

```kotlin
    /** Emitted when a PR is selected in the PR dashboard. */
    data class PrSelected(
        val prId: Int,
        val fromBranch: String,
        val toBranch: String,
        val repoName: String,
        val bambooPlanKey: String?,
        val sonarProjectKey: String?,
    ) : WorkflowEvent()
```

- [ ] **Step 2: Add PrContext data class and state map to EventBus**

In `EventBus.kt`, add import and PrContext class before the `EventBus` class, and add the map + auto-update inside `emit()`:

```kotlin
package com.workflow.orchestrator.core.events

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight snapshot of the last-selected PR for a given repo.
 * Queryable by Build/Quality tabs when the user manually switches their repo selector.
 */
data class PrContext(
    val prId: Int,
    val fromBranch: String,
    val toBranch: String,
    val repoName: String,
    val bambooPlanKey: String?,
    val sonarProjectKey: String?,
)

@Service(Service.Level.PROJECT)
class EventBus {
    private val log = Logger.getInstance(EventBus::class.java)

    private val _events = MutableSharedFlow<WorkflowEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<WorkflowEvent> = _events.asSharedFlow()

    /** Last-selected PR per repo. Key = repoName (RepoConfig.displayLabel). */
    val prContextMap: ConcurrentHashMap<String, PrContext> = ConcurrentHashMap()

    suspend fun emit(event: WorkflowEvent) {
        log.info("[Core:Events] Emitting event: ${event::class.simpleName}")
        // Auto-update PrContext map on PrSelected events
        if (event is WorkflowEvent.PrSelected) {
            prContextMap[event.repoName] = PrContext(
                prId = event.prId,
                fromBranch = event.fromBranch,
                toBranch = event.toBranch,
                repoName = event.repoName,
                bambooPlanKey = event.bambooPlanKey,
                sonarProjectKey = event.sonarProjectKey,
            )
        }
        if (!_events.tryEmit(event)) {
            log.warn("[Core:Events] Failed to emit event (buffer full): ${event::class.simpleName}")
        }
    }
}
```

- [ ] **Step 3: Fix compile errors from PrSelected callers**

The enriched `PrSelected` now requires 3 new fields. All callers must be updated. Search for `PrSelected(` across the codebase and add the new fields. The main caller is in `PrDashboardPanel.kt` (handled in Task 2). Any other callers should pass empty defaults:

```bash
cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite
grep -rn "PrSelected(" --include="*.kt" | grep -v "is WorkflowEvent" | grep -v "test" | grep -v "docs/"
```

Fix each caller by adding `repoName = ""`, `bambooPlanKey = null`, `sonarProjectKey = null` temporarily. The PR tab caller gets the real values in Task 2.

- [ ] **Step 4: Compile core module**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/events/WorkflowEvent.kt \
        core/src/main/kotlin/com/workflow/orchestrator/core/events/EventBus.kt
git commit -m "feat: enrich PrSelected event with repo fields, add PrContext state map to EventBus"
```

---

### Task 2: Emit Enriched PrSelected from PR Tab

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt:211-227`

- [ ] **Step 1: Resolve RepoConfig when emitting PrSelected**

In `PrDashboardPanel.kt`, replace the `onPrSelected` event emission block (lines 219-227). The `prDetail.repoName` is the `displayLabel` — match it against configured repos to get the full `RepoConfig`:

```kotlin
        listPanel.onPrSelected = { prId ->
            // Look up full PR detail from cached data to avoid re-fetching
            val prDetail = currentMyPrs.find { it.id == prId }
                ?: currentReviewingPrs.find { it.id == prId }
                ?: currentAllRepoPrs.find { it.id == prId }
            if (prDetail != null) {
                detailPanel.showPrDetail(prDetail)
                // Emit PrSelected event so other tabs (Build, Quality) can update context
                val fromBranch = prDetail.fromRef?.displayId ?: ""
                val toBranch = prDetail.toRef?.displayId ?: ""
                val repoName = prDetail.repoName ?: ""
                if (fromBranch.isNotBlank()) {
                    // Resolve RepoConfig to get bambooPlanKey and sonarProjectKey
                    val repoConfig = PluginSettings.getInstance(project).getRepos()
                        .find { it.displayLabel == repoName }
                    scope.launch {
                        project.getService(EventBus::class.java)
                            .emit(WorkflowEvent.PrSelected(
                                prId = prId,
                                fromBranch = fromBranch,
                                toBranch = toBranch,
                                repoName = repoName,
                                bambooPlanKey = repoConfig?.bambooPlanKey?.takeIf { it.isNotBlank() },
                                sonarProjectKey = repoConfig?.sonarProjectKey?.takeIf { it.isNotBlank() },
                            ))
                    }
                }
            } else {
                detailPanel.showPr(prId)
            }
        }
```

- [ ] **Step 2: Compile pullrequest module**

Run: `./gradlew :pullrequest:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDashboardPanel.kt
git commit -m "feat: emit enriched PrSelected with repo context from PR tab"
```

---

### Task 3: Build Tab — EventBus Subscription and All-Repos Selector

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`

- [ ] **Step 1: Change repo selector to show all configured repos**

Replace the `bambooRepos` and `repoSelector` declarations (lines 310-318). Change filter from `bambooPlanKey` to `isConfigured`, and always show selector when 2+ repos:

```kotlin
    // Repo selector for multi-repo support — show all configured repos, not just those with Bamboo keys
    private val allRepos: List<RepoConfig> = settings.getRepos().filter { it.isConfigured }
    private val repoSelector: ComboBox<String>? = if (allRepos.size > 1) {
        ComboBox(DefaultComboBoxModel(allRepos.map { it.displayLabel }.toTypedArray())).apply {
            val primaryIndex = allRepos.indexOfFirst { it.isPrimary }.takeIf { it >= 0 } ?: 0
            selectedIndex = primaryIndex
        }
    } else null
```

- [ ] **Step 2: Add a suppress flag and hint label**

Add these fields after the repo selector declarations:

```kotlin
    private var suppressRepoSelectorListener = false

    // Hint label shown when no PR or no Bamboo plan key is available
    private val hintLabel = JBLabel("").apply {
        foreground = StatusColors.SECONDARY_TEXT
        font = font.deriveFont(Font.ITALIC, 11f)
        border = JBUI.Borders.empty(8, 12)
        isVisible = false
    }
```

Add `hintLabel` to the `topPanel2` layout (around line 340-347), after `prBar`:

```kotlin
        val topPanel2 = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(prBar)
            add(hintLabel)
            add(warningLabel)
            add(newerBuildBanner)
            add(historicalBuildBanner)
            add(historyPanel)
        }
```

- [ ] **Step 3: Add EventBus subscription for PrSelected**

At the end of the `init` block (before `addAncestorListener` around line 499), add EventBus subscription. Import `EventBus`, `WorkflowEvent`, and `PrContext` at the top of the file:

```kotlin
        // Subscribe to PrSelected events from the PR tab
        scope.launch {
            val eventBus = project.getService(EventBus::class.java)
            eventBus.events.collect { event ->
                if (event is WorkflowEvent.PrSelected) {
                    invokeLater { onPrSelectedEvent(event) }
                }
            }
        }
```

Add the handler method:

```kotlin
    private fun onPrSelectedEvent(event: WorkflowEvent.PrSelected) {
        if (repoSelector == null || allRepos.isEmpty()) return

        // Find the repo in the selector matching the event's repoName
        val repoIndex = allRepos.indexOfFirst { it.displayLabel == event.repoName }
        if (repoIndex < 0) return

        // Switch repo selector (suppress listener to avoid double-fire)
        suppressRepoSelectorListener = true
        repoSelector.selectedIndex = repoIndex
        suppressRepoSelectorListener = false

        // Load builds for this PR
        loadBuildsForContext(event.repoName, event.fromBranch, event.bambooPlanKey)
    }

    private fun loadBuildsForContext(repoName: String, branch: String, bambooPlanKey: String?) {
        hintLabel.isVisible = false
        splitter.isVisible = true

        if (bambooPlanKey.isNullOrBlank()) {
            // No Bamboo plan key — try auto-detect, otherwise show hint
            scope.launch {
                autoDetectAndMonitor(branch)
                // If no builds found after auto-detect, the headerLabel will say so
            }
            return
        }

        activePlanKey = bambooPlanKey
        val interval = settings.state.buildPollIntervalSeconds.toLong() * 1000
        loadingIcon.isVisible = true
        viewingHistoricalBuild = false
        historicalBuildBanner.isVisible = false
        historyListModel.clear()
        historyPanel.isVisible = false
        headerLabel.text = "Plan: $bambooPlanKey / $branch"
        monitorService.switchBranch(bambooPlanKey, branch, interval)
    }

    private fun showHint(message: String) {
        hintLabel.text = message
        hintLabel.isVisible = true
        splitter.isVisible = false
        headerLabel.text = "Build"
        loadingIcon.isVisible = false
    }
```

- [ ] **Step 4: Update repo selector listener to use PrContext map**

Replace the existing repo selector action listener (around lines 462-484). Add suppress check and PrContext lookup:

```kotlin
        repoSelector?.addActionListener {
            if (suppressRepoSelectorListener) return@addActionListener
            val selectedIndex = repoSelector.selectedIndex
            if (selectedIndex < 0 || selectedIndex >= allRepos.size) return@addActionListener

            val selectedRepo = allRepos[selectedIndex]
            val repoName = selectedRepo.displayLabel

            // Look up PrContext for this repo
            val eventBus = project.getService(EventBus::class.java)
            val context = eventBus.prContextMap[repoName]

            if (context != null) {
                loadBuildsForContext(repoName, context.fromBranch, context.bambooPlanKey)
                // Update PrBar info strip
                prBar.showPrInfo(context.prId, context.fromBranch, context.toBranch)
            } else {
                showHint("No PR selected for $repoName \u2014 select one in the PR tab")
                prBar.showNoPr()
            }
        }
```

- [ ] **Step 5: Update references from `bambooRepos` to `allRepos`**

Search and replace all remaining references to `bambooRepos` in the file. The `startMonitoring()` method (around line 516-520) references `bambooRepos`:

```kotlin
    private fun startMonitoring() {
        val selectedRepoPlanKey = if (repoSelector != null && allRepos.isNotEmpty()) {
            val idx = repoSelector.selectedIndex.takeIf { it >= 0 } ?: 0
            allRepos.getOrNull(idx)?.bambooPlanKey.orEmpty()
        } else ""
```

- [ ] **Step 6: Compile bamboo module**

Run: `./gradlew :bamboo:compileKotlin`
Expected: BUILD SUCCESSFUL (PrBar changes may cause errors — addressed in Task 4)

- [ ] **Step 7: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt
git commit -m "feat: Build tab subscribes to PrSelected, shows all repos, adds hint states"
```

---

### Task 4: Slim Down PrBar to Read-Only Info Strip

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt`

- [ ] **Step 1: Add read-only info methods**

Add two public methods to `PrBar` that the `BuildDashboardPanel` calls to update the strip. These replace the interactive PR selection with a display-only view:

```kotlin
    /**
     * Show a read-only PR info strip for the given PR context.
     * Called by BuildDashboardPanel when PrSelected event or PrContext provides the PR.
     */
    fun showPrInfo(prId: Int, fromBranch: String, toBranch: String) {
        prInfoLabel.text = "<html><b>PR #$prId</b> &nbsp; ${escapeHtml(fromBranch)} \u2192 $toBranch</html>"
        selectedPr = currentPrs.find { it.id.toInt() == prId }
        showPanel(singlePrPanel)
    }

    /**
     * Show the "no PR" state without the create button.
     */
    fun showNoPr() {
        selectedPr = null
        showPanel(noPrPanel)
    }
```

Also add an `escapeHtml` utility if not already present:

```kotlin
    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
```

- [ ] **Step 2: Compile bamboo module**

Run: `./gradlew :bamboo:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt
git commit -m "feat: add read-only info methods to PrBar for event-driven updates"
```

---

### Task 5: Quality Tab — SonarDataService Uses Event's sonarProjectKey

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt:86-124`

- [ ] **Step 1: Add refreshForBranch overload accepting explicit projectKey**

Add this new method after the existing `refreshForBranch(branch: String)` (after line 124):

```kotlin
    /**
     * Refresh Sonar data for a specific branch AND project key.
     * Used by PrSelected events where the repo context provides the correct project key.
     */
    fun refreshForBranch(branch: String, projectKey: String) {
        if (projectKey.isBlank()) return
        refreshDebounceJob?.cancel()
        refreshDebounceJob = scope.launch {
            delay(500)
            val client = apiClient ?: return@launch
            refreshWith(client, projectKey, branch)
        }
    }
```

- [ ] **Step 2: Update PrSelected handler to use event's sonarProjectKey**

Replace the `PrSelected` case in `subscribeToEvents()` (lines 91-94):

```kotlin
                    is WorkflowEvent.PrSelected -> {
                        val projectKey = event.sonarProjectKey
                        if (!projectKey.isNullOrBlank()) {
                            log.info("[Sonar:Events] PR selected (id=${event.prId}, repo=${event.repoName}), refreshing for branch '${event.fromBranch}' with project '$projectKey'")
                            refreshForBranch(event.fromBranch, projectKey)
                        } else {
                            log.info("[Sonar:Events] PR selected (id=${event.prId}, repo=${event.repoName}), no sonarProjectKey configured — skipping refresh")
                        }
                    }
```

- [ ] **Step 3: Compile sonar module**

Run: `./gradlew :sonar:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt
git commit -m "feat: SonarDataService uses event's sonarProjectKey instead of scalar setting"
```

---

### Task 6: Quality Tab — EventBus Subscription, All-Repos Selector, Hint States

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt`

- [ ] **Step 1: Change repo selector to show all configured repos**

Replace the `sonarRepos` and `repoSelector` declarations (lines 44-50):

```kotlin
    // Repo selector for multi-repo support — show all configured repos
    private val allRepos: List<RepoConfig> = settings.getRepos().filter { it.isConfigured }
    private val repoSelector: ComboBox<String>? = if (allRepos.size > 1) {
        ComboBox(DefaultComboBoxModel(allRepos.map { it.displayLabel }.toTypedArray())).apply {
            val primaryIndex = allRepos.indexOfFirst { it.isPrimary }.takeIf { it >= 0 } ?: 0
            selectedIndex = primaryIndex
        }
    } else null
```

- [ ] **Step 2: Add suppress flag and hint label**

Add these fields after the `repoSelector`:

```kotlin
    private var suppressRepoSelectorListener = false

    // Hint label shown when no PR or no Sonar key is available
    private val qualityHintLabel = JBLabel("").apply {
        foreground = StatusColors.SECONDARY_TEXT
        font = font.deriveFont(Font.ITALIC, 11f)
        border = JBUI.Borders.empty(12, 12)
        isVisible = false
    }
```

Add `qualityHintLabel` to the layout. In the `init` block, add it to the `topSection` panel (around line 152-161), after `gateBanner`:

```kotlin
        val topSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            val toolbarRow = JPanel(BorderLayout()).apply {
                add(headerPanel, BorderLayout.CENTER)
                add(createToolbar(), BorderLayout.EAST)
            }
            add(toolbarRow)
            add(branchInfoPanel)
            add(gateBanner)
            add(qualityHintLabel)
        }
```

- [ ] **Step 3: Add EventBus subscription for PrSelected**

Add imports for `EventBus`, `WorkflowEvent`, `PrContext` at the top. In the `init` block (after the stateFlow collection around line 249), add:

```kotlin
        // Subscribe to PrSelected events to auto-switch repo selector
        scope.launch {
            val eventBus = project.getService(EventBus::class.java)
            eventBus.events.collect { event ->
                if (event is WorkflowEvent.PrSelected) {
                    onPrSelectedEvent(event)
                }
            }
        }
```

Add the handler method:

```kotlin
    private fun onPrSelectedEvent(event: WorkflowEvent.PrSelected) {
        if (repoSelector == null || allRepos.isEmpty()) return

        val repoIndex = allRepos.indexOfFirst { it.displayLabel == event.repoName }
        if (repoIndex < 0) return

        suppressRepoSelectorListener = true
        repoSelector.selectedIndex = repoIndex
        suppressRepoSelectorListener = false

        // Clear hint — SonarDataService handles the actual data refresh via its own event subscription
        if (!event.sonarProjectKey.isNullOrBlank()) {
            qualityHintLabel.isVisible = false
            tabbedPane.isVisible = true
        } else {
            showQualityHint("SonarQube project key not configured for ${event.repoName} \u2014 configure in Settings > CI/CD")
        }
    }

    private fun showQualityHint(message: String) {
        qualityHintLabel.text = message
        qualityHintLabel.isVisible = true
        tabbedPane.isVisible = false
        loadingIcon.isVisible = false
        statusLabel.text = ""
    }
```

- [ ] **Step 4: Update repo selector listener to use PrContext map**

Replace the existing repo selector action listener (lines 226-238):

```kotlin
        repoSelector?.addActionListener {
            if (suppressRepoSelectorListener) return@addActionListener
            val selectedIndex = repoSelector.selectedIndex
            if (selectedIndex < 0 || selectedIndex >= allRepos.size) return@addActionListener

            val selectedRepo = allRepos[selectedIndex]
            val repoName = selectedRepo.displayLabel
            val sonarKey = selectedRepo.sonarProjectKey?.takeIf { it.isNotBlank() }

            // Priority 1: No sonar key configured
            if (sonarKey == null) {
                showQualityHint("SonarQube project key not configured for $repoName \u2014 configure in Settings > CI/CD")
                return@addActionListener
            }

            // Priority 2: Check if a PR is selected for this repo
            val eventBus = project.getService(EventBus::class.java)
            val context = eventBus.prContextMap[repoName]

            if (context != null) {
                qualityHintLabel.isVisible = false
                tabbedPane.isVisible = true
                statusLabel.text = "Switching to $sonarKey..."
                loadingIcon.isVisible = true
                dataService.refreshForBranch(context.fromBranch, sonarKey)
            } else {
                showQualityHint("No PR selected for $repoName \u2014 select one in the PR tab")
            }
        }
```

- [ ] **Step 5: Update references from `sonarRepos` to `allRepos`**

Search and replace any remaining references to `sonarRepos` in the file. These are gone since the declarations were replaced in Step 1.

- [ ] **Step 6: Update the updateUI hint for empty/failed analysis**

In the `updateUI` method (around line 293-298), enhance the empty state check to show analysis-failure hints:

```kotlin
    private fun updateUI(state: SonarState) {
        if (state.projectKey.isEmpty()) {
            loadingIcon.isVisible = false
            // Check if hint is already showing (e.g., "no PR selected")
            if (!qualityHintLabel.isVisible) {
                statusLabel.text = "Configure SonarQube project key in Settings > CI/CD."
                statusLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }
            return
        }

        // Hide hint when valid data arrives
        qualityHintLabel.isVisible = false
        tabbedPane.isVisible = true
```

After the branch info update section (around line 330), add a check for analysis failure:

```kotlin
        // Show analysis failure hint if the last analysis for this branch failed
        val lastAnalysis = state.lastAnalysisForBranch
        if (lastAnalysis != null && lastAnalysis.status == "FAILED") {
            val errorMsg = lastAnalysis.errorMessage ?: "Unknown error"
            qualityHintLabel.text = "SonarQube analysis failed: $errorMsg"
            qualityHintLabel.foreground = StatusColors.ERROR
            qualityHintLabel.isVisible = true
        } else if (!state.currentBranchAnalyzed && state.issues.isEmpty()) {
            qualityHintLabel.text = "No SonarQube analysis found for branch '${state.branch}'. Analysis may be pending."
            qualityHintLabel.foreground = StatusColors.WARNING
            qualityHintLabel.isVisible = true
        } else {
            qualityHintLabel.foreground = StatusColors.SECONDARY_TEXT
        }
```

- [ ] **Step 7: Compile sonar module**

Run: `./gradlew :sonar:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt
git commit -m "feat: Quality tab subscribes to PrSelected, shows all repos, adds hint states"
```

---

### Task 7: Full Build Verification

**Files:** None (verification only)

- [ ] **Step 1: Compile all modules**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL for all modules

- [ ] **Step 2: Run core tests**

Run: `./gradlew :core:test`
Expected: All tests pass

- [ ] **Step 3: Run sonar tests**

Run: `./gradlew :sonar:test`
Expected: All tests pass (existing tests may need PrSelected constructor updates if they reference it)

- [ ] **Step 4: Run bamboo tests**

Run: `./gradlew :bamboo:test`
Expected: All tests pass

- [ ] **Step 5: Run pullrequest tests**

Run: `./gradlew :pullrequest:test`
Expected: All tests pass

- [ ] **Step 6: Fix any test failures**

If tests reference `WorkflowEvent.PrSelected` with the old 3-arg constructor, update them to include the new fields with defaults:

```kotlin
WorkflowEvent.PrSelected(
    prId = 123,
    fromBranch = "feature/x",
    toBranch = "develop",
    repoName = "test-repo",
    bambooPlanKey = null,
    sonarProjectKey = null,
)
```

- [ ] **Step 7: Run verifyPlugin**

Run: `./gradlew verifyPlugin`
Expected: No API compatibility issues

- [ ] **Step 8: Commit any test fixes**

```bash
git add -A
git commit -m "fix: update tests for enriched PrSelected event constructor"
```
