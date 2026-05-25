package com.workflow.orchestrator.automation.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.model.workflow.BuildRef
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.model.*
import com.workflow.orchestrator.automation.service.*
import com.workflow.orchestrator.automation.service.BaselineCacheService
import com.workflow.orchestrator.automation.service.TriggerDefaultAction
import com.workflow.orchestrator.bamboo.ui.ManualStageDialog
import com.workflow.orchestrator.bamboo.ui.TriggerMode
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.ui.ComboBoxWidth
import com.workflow.orchestrator.core.ui.bindBoundedWidth
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*

/**
 * Main Automation tab panel.
 * Header: suite selector + status + action buttons.
 * Diagnostic banner: baseline info + docker tag detection indicator.
 * Two sub-tabs: Configure (tag table + variables) and Monitor (unified run view).
 */
class AutomationPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(AutomationPanel::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings = PluginSettings.getInstance(project)

    private val tagBuilderService by lazy { project.getService(TagBuilderService::class.java) }
    private val queueService by lazy { project.getService(QueueService::class.java) }
    private val bambooService by lazy { project.getService(BambooService::class.java) }
    private val driftDetectorService by lazy { project.getService(DriftDetectorService::class.java) }
    private val baselineCacheService: BaselineCacheService =
        project.getService(BaselineCacheService::class.java)
    private val refreshButton: JButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Re-scan recent builds for the selected suite"
        isFocusable = false
        margin = JBUI.insets(2)
    }

    // Header components
    private val suiteCombo = JComboBox<SuiteComboItem>().apply {
        bindBoundedWidth(ComboBoxWidth.DEFAULT)
    }
    private val statusLabel = JBLabel("").apply {
        foreground = StatusColors.SUCCESS
    }

    // Diagnostic banner components
    private val baselineInfoLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(11).toFloat())
        border = JBUI.Borders.emptyLeft(4)
    }
    private val dockerTagInfoLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(11).toFloat())
        border = JBUI.Borders.emptyLeft(4)
    }
    /**
     * Baseline picker (PR 7 #8) — shows the auto-selected build + all parseable
     * alternatives. Selecting a different entry swaps the staged tags to that
     * build's `dockerTagsAsJson`. Hidden until at least one parseable build is
     * found; populated from [BaselineLoadResult.allRanked].
     *
     * Declared before [baselinePickerRow] / [diagnosticPanel] because Kotlin
     * initializes properties in declaration order and the panel references it.
     */
    private val baselineCombo = JComboBox<BaselinePickerItem>().apply {
        toolTipText = "Pick a different parseable build to seed the staging table from"
        bindBoundedWidth(ComboBoxWidth.DEFAULT)
    }
    private var suppressBaselineListener = false
    /** Remembered alternatives so the combo listener can resolve the picked entry. */
    private var currentBaselineAlternatives: List<BaselineRun> = emptyList()

    /**
     * Wraps the baseline picker combo with a "Baseline:" label so the user can
     * see which build was auto-picked and switch to a parseable alternative.
     * Hidden when there are no alternatives (fewer than 2 parseable builds).
     */
    private val baselinePickerRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        border = JBUI.Borders.empty(0, 4, 2, 4)
        add(JBLabel("Baseline:").apply {
            font = font.deriveFont(JBUI.scale(11).toFloat())
            foreground = StatusColors.SECONDARY_TEXT
        })
        add(baselineCombo)
        isVisible = false
    }
    private val diagnosticPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(2, 8, 4, 8)
        add(baselineInfoLabel)
        add(dockerTagInfoLabel)
        add(baselinePickerRow)
        isVisible = false // hidden until suite is loaded
    }

    // Sub-panels
    private val tagStagingPanel = TagStagingPanel(project)
    private val suiteConfigPanel = SuiteConfigPanel(project)
    private val queueStatusPanel = QueueStatusPanel(project)
    private val monitorPanel = MonitorPanel(project).apply {
        // PR 8 #4: top status bar mirrors the row the user picked in the list.
        onSelectionChanged = { selected -> queueStatusPanel.setSelection(selected?.queueId) }
    }

    // Sub-tabs
    private val tabbedPane = JBTabbedPane()

    /**
     * Interactive branch selector — lets the user choose which Bamboo plan branch
     * to trigger against. Populated by [loadBranchesFor] on suite selection; persisted
     * per-suite by [AutomationSettingsService]. `null` branchKey means master/default.
     */
    private val branchCombo = JComboBox<BranchComboItem>().apply {
        bindBoundedWidth(ComboBoxWidth.DEFAULT)
    }
    @Volatile private var selectedBranchKey: String? = null
    private var suppressBranchListener = false

    // State
    private var currentSuitePlanKey: String = ""
    /**
     * The focused build from [WorkflowContextService] — drives docker-tag detection.
     * Null when no PR is focused or chain-key resolution failed.
     */
    @Volatile
    private var currentFocusBuild: BuildRef? = null
    // A-P1-6: monotonically incremented on each suite/branch switch so stale
    // invokeLater handlers from prior selections drop their UI updates instead
    // of clobbering the newer selection's state.
    private var loadGeneration: Long = 0L

    /**
     * Cache of canonical Bamboo `shortName` per planKey, populated lazily by
     * [refreshShortNamesInBackground] on tab init. The dropdown render prefers
     * this cached value over the saved `SuiteConfig.displayName` so suites
     * added before v0.85.0 (which kept the long `Project — Plan` form in
     * settings) display their canonical short name without requiring the user
     * to re-pick in Settings. Falls back to displayName on cache miss / fetch
     * failure / blank shortName.
     *
     * **Declared BEFORE the init block** — the init block calls [loadSuites]
     * which reads this field. Kotlin initialises properties in source order
     * interleaved with init blocks, so a declaration after the init block
     * leaves this field null when loadSuites runs (v0.85.3-alpha shipped with
     * exactly that bug).
     */
    private val planShortNames: java.util.concurrent.ConcurrentHashMap<String, String> =
        java.util.concurrent.ConcurrentHashMap()

    init {
        border = JBUI.Borders.empty(4)

        // Load suites into dropdown — initial render uses whatever displayName
        // is in SuiteConfig. The background refresh below replaces stale long-form
        // displayNames (legacy suites added before v0.85.0) with Bamboo's canonical
        // shortName when it's available.
        loadSuites()
        refreshShortNamesInBackground()

        // Header bar
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8, 4, 8)

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(JBLabel("SUITE").apply {
                    foreground = StatusColors.SECONDARY_TEXT
                    font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
                })
                add(suiteCombo)
                add(JBLabel("BRANCH").apply {
                    foreground = StatusColors.SECONDARY_TEXT
                    font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
                })
                add(branchCombo)
                add(statusLabel)
                add(refreshButton)
            }

            // Single split-button: primary action = enqueue first stage;
            // dropdown = "Trigger Customized\u2026" | "Trigger All Stages".
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                add(buildTriggerSplitButton())
            }

            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
        }

        // Top: header + diagnostic banner
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(headerPanel)
            add(diagnosticPanel)
        }
        add(topPanel, BorderLayout.NORTH)

        // Configure tab: tag table (left) + suite variable overrides (right).
        val configurePanel = JPanel(BorderLayout()).apply {
            val splitter = com.intellij.ui.JBSplitter(false, 0.65f).apply {
                setSplitterProportionKey("workflow.automation.splitter")
                firstComponent = JBScrollPane(tagStagingPanel)
                secondComponent = JBScrollPane(suiteConfigPanel)
            }
            add(splitter, BorderLayout.CENTER)
        }

        tabbedPane.addTab("Configure", configurePanel)

        // Monitor tab: queue status at top + run monitor below
        val monitorTabPanel = JPanel(BorderLayout()).apply {
            add(queueStatusPanel, BorderLayout.NORTH)
            add(monitorPanel, BorderLayout.CENTER)
        }
        tabbedPane.addTab("Monitor", monitorTabPanel)
        add(tabbedPane, BorderLayout.CENTER)

        // Suite selection listener
        suiteCombo.addActionListener {
            val item = suiteCombo.selectedItem as? SuiteComboItem ?: return@addActionListener
            onSuiteSelected(item.planKey)
        }

        // Branch selection listener — persists the chosen branch per suite
        branchCombo.addActionListener {
            if (suppressBranchListener) return@addActionListener
            val item = branchCombo.selectedItem as? BranchComboItem ?: return@addActionListener
            selectedBranchKey = item.branchKey
            if (currentSuitePlanKey.isNotBlank()) {
                AutomationSettingsService.getInstance().setSuiteSelectedBranch(currentSuitePlanKey, item.branchKey)
            }
        }

        // Refresh button: cache-bust for the currently-selected suite
        refreshButton.addActionListener {
            val planKey = currentSuitePlanKey.takeIf { it.isNotBlank() } ?: return@addActionListener
            val token = ++loadGeneration
            refreshButton.isEnabled = false
            statusLabel.text = "Refreshing…"
            statusLabel.foreground = StatusColors.INFO
            scope.launch {
                try {
                    refreshBaseline(planKey, token)
                } finally {
                    invokeLater { refreshButton.isEnabled = true }
                }
            }
        }

        // QueueStatusPanel observes QueueService directly (A-P1-2); Cancel is wired
        // inside the panel. Trigger Now / Queue Run live on the parent header above.

        // Phase B: subscribe to WorkflowContextService.state for focusBuild changes.
        // Whenever the focused PR changes (or its chain key resolves), we re-run
        // docker-tag detection using the resolved chain key. No user-driven branch
        // picking — the WorkflowContextService is the single source of truth.
        scope.launch {
            WorkflowContextService.getInstance(project).state
                .map { it.focusBuild }
                .distinctUntilChanged()
                .collect { focusBuild ->
                    onFocusBuildChanged(focusBuild)
                }
        }

        Disposer.register(this, tagStagingPanel)
        Disposer.register(this, suiteConfigPanel)
        Disposer.register(this, queueStatusPanel)
        Disposer.register(this, monitorPanel)

        // Baseline picker (PR 7 #8): on user pick, replace the staging table's
        // tag list with the chosen build's tags. The auto-picked entry remains
        // index 0 — picking it is a no-op.
        baselineCombo.addActionListener {
            if (suppressBaselineListener) return@addActionListener
            val item = baselineCombo.selectedItem as? BaselinePickerItem ?: return@addActionListener
            val pick = currentBaselineAlternatives.firstOrNull { it.buildNumber == item.buildNumber }
                ?: return@addActionListener
            log.info("[Automation:UI] Baseline manually swapped to build #${pick.buildNumber} (score=${pick.score})")
            tagStagingPanel.setBaseline(tagBuilderService.tagsForRun(pick))
        }

        // Subscribe to BuildLogReady events from the Build tab
        subscribeToBuildEvents()

        // Auto-select first suite and kick off the tab-open baseline load
        if (suiteCombo.itemCount > 0) {
            suiteCombo.selectedIndex = 0
            val initialPlanKey = (suiteCombo.selectedItem as? SuiteComboItem)?.planKey
            if (!initialPlanKey.isNullOrBlank()) {
                currentSuitePlanKey = initialPlanKey
                val token = ++loadGeneration
                loadBranchesFor(initialPlanKey, token)
                scope.launch { onTabOpenedFor(initialPlanKey, token) }
            }
        }
    }

    private fun loadSuites() {
        val automationSettings = AutomationSettingsService.getInstance()
        val suites = automationSettings.getAllSuites()
        suiteCombo.removeAllItems()
        // Effective label: prefer Bamboo's canonical shortName when we have it
        // cached, otherwise the saved displayName. Sort by the effective label so
        // the visible order matches what the user sees in the dropdown.
        val labelled = suites.map { it to (planShortNames[it.planKey]?.takeIf { n -> n.isNotBlank() } ?: it.displayName) }
        for ((suite, label) in labelled.sortedBy { it.second.lowercase() }) {
            suiteCombo.addItem(SuiteComboItem(suite.planKey, label))
        }
        if (suites.isEmpty()) {
            statusLabel.text = "No suites configured \u2014 go to Settings"
        }
    }

    /**
     * Walks the configured suites in the background, fetches each plan's
     * canonical `shortName` from Bamboo, populates [planShortNames], and
     * re-renders the dropdown when there's at least one new value. Run-once at
     * tab init; subsequent re-renders read from the cache and are instant.
     *
     * Suite selection is preserved across the refresh by planKey.
     */
    private fun refreshShortNamesInBackground() {
        scope.launch {
            val suites = AutomationSettingsService.getInstance().getAllSuites()
            if (suites.isEmpty()) return@launch
            var anyChanged = false
            for (suite in suites) {
                if (planShortNames.containsKey(suite.planKey)) continue
                val result = bambooService.getPlanShortName(suite.planKey)
                if (!result.isError) {
                    val short = result.data
                    if (!short.isNullOrBlank() && short != suite.displayName) {
                        planShortNames[suite.planKey] = short
                        anyChanged = true
                    } else if (!short.isNullOrBlank()) {
                        // Same value \u2014 cache it anyway so we don't refetch on subsequent panel re-inits.
                        planShortNames[suite.planKey] = short
                    }
                }
            }
            if (anyChanged) {
                invokeLater {
                    val previouslySelected = (suiteCombo.selectedItem as? SuiteComboItem)?.planKey
                    loadSuites()
                    if (previouslySelected != null) {
                        for (i in 0 until suiteCombo.itemCount) {
                            if ((suiteCombo.getItemAt(i) as? SuiteComboItem)?.planKey == previouslySelected) {
                                suiteCombo.selectedIndex = i
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    private fun onSuiteSelected(planKey: String) {
        currentSuitePlanKey = planKey
        val token = ++loadGeneration
        log.info("[Automation:UI] Suite selected: $planKey (gen=$token) — sticky baseline, no scan")
        // Suite-switch only re-binds the selected-suite reference used by
        // Refresh and Trigger Now. The displayed baseline is sticky; the
        // user must click Refresh to recompute it for a different suite.
        loadBranchesFor(planKey, token)
        scope.launch {
            // Reload plan variables (suite-specific) so the variables dropdown
            // in SuiteConfigPanel matches the new suite. The baseline display
            // and the docker-tag banner are NOT touched.
            val varsResult = bambooService.getPlanVariables(planKey)
            invokeLater {
                if (token != loadGeneration) return@invokeLater
                if (!varsResult.isError) {
                    val vars = varsResult.data!!
                    suiteConfigPanel.setAvailableVariables(vars)
                    suiteConfigPanel.loadSuiteVariables(planKey)
                }
            }
        }
    }

    /**
     * Fetches plan branches from Bamboo for the given [planKey] and populates
     * [branchCombo]. Restores the persisted selection for the suite, falling back
     * to index 0 ("default") when the previously chosen branch no longer exists.
     * Guarded by [loadGeneration] so stale async results don't clobber a newer
     * suite selection.
     */
    private fun loadBranchesFor(planKey: String, token: Long) {
        scope.launch {
            val result = bambooService.getPlanBranches(planKey)
            invokeLater {
                if (token != loadGeneration) return@invokeLater
                suppressBranchListener = true
                try {
                    branchCombo.removeAllItems()
                    branchCombo.addItem(BranchComboItem(null, "default"))
                    if (!result.isError) {
                        for (b in result.data.orEmpty()) {
                            branchCombo.addItem(BranchComboItem(b.key, b.shortName.ifBlank { b.name }))
                        }
                    } else {
                        log.warn("[Automation:UI] getPlanBranches failed for $planKey: ${result.summary}")
                    }
                    // Restore persisted selection (falls back to "default" if the branch no longer exists)
                    val persisted = AutomationSettingsService.getInstance().getSuiteSelectedBranch(planKey)
                    val idx = (0 until branchCombo.itemCount)
                        .firstOrNull { (branchCombo.getItemAt(it) as BranchComboItem).branchKey == persisted }
                        ?: 0
                    branchCombo.selectedIndex = idx
                    selectedBranchKey = (branchCombo.getItemAt(idx) as BranchComboItem).branchKey
                } finally {
                    suppressBranchListener = false
                }
            }
        }
    }

    /**
     * Phase B: called whenever [WorkflowContextService] emits a new [focusBuild].
     *
     * - Updates the passive branch label.
     * - If `focusBuild == null` or `focusBuild.chainKey == null` → shows empty state.
     *   No fallback to master/develop; better to show "No build context" than wrong data.
     * - Otherwise: launches docker-tag detection via [tagBuilderService.detectDockerTag]
     *   using the resolved chain key.
     */
    private fun onFocusBuildChanged(focusBuild: BuildRef?) {
        currentFocusBuild = focusBuild
        log.info("[Automation:UI] focusBuild changed: planKey=${focusBuild?.planKey}, chainKey=${focusBuild?.chainKey}, branch=${focusBuild?.branch}")

        if (focusBuild == null || focusBuild.chainKey == null) {
            invokeLater {
                updateDockerTagBanner(
                    TagDetectionResult.noBuild("No build context for this PR's branch yet")
                )
                diagnosticPanel.isVisible = true
                diagnosticPanel.revalidate()
            }
            return
        }

        val chainKey = focusBuild.chainKey!!
        scope.launch {
            val tagDetection = withTimeoutOrNull(15_000L) {
                tagBuilderService.detectDockerTag(chainKey)
            }
            log.info("[Automation:UI] Tag detection via chainKey='$chainKey': detected=${tagDetection?.detected}, tag=${tagDetection?.tag}")

            if (tagDetection != null) {
                val dockerTagKey = resolveDockerTagKey()
                if (tagDetection.detected && tagDetection.tag != null && dockerTagKey.isNotBlank()) {
                    invokeLater {
                        val currentTags = tagStagingPanel.getCurrentTags()
                        val updatedTags = tagBuilderService.replaceCurrentRepoTag(currentTags, CurrentRepoContext(
                            serviceName = dockerTagKey,
                            branchName = focusBuild.branch,
                            featureBranchTag = tagDetection.tag,
                            detectedFrom = DetectionSource.SETTINGS_MAPPING
                        ))
                        // Promote the auto-overlay output to baseline so that Revert is
                        // sticky: every auto-computed tag set (baseline pick OR per-build
                        // feature-branch overlay) becomes the new revert target.  Only the
                        // user's own edits (cell edit, Paste) diverge from baseline.
                        tagStagingPanel.setBaseline(updatedTags)
                    }
                }
                invokeLater {
                    updateDockerTagBanner(tagDetection)
                }
            }
        }
    }

    /**
     * Tab-open path: render from cache immediately (if present) for the
     * default suite, then reconcile with Bamboo in the background. Called
     * once at panel mount.
     */
    private suspend fun onTabOpenedFor(planKey: String, token: Long) {
        // Synchronous in-memory read — show whatever we last saved instantly.
        val cached = baselineCacheService.get(planKey)
        if (cached != null) {
            invokeLater {
                if (token != loadGeneration) return@invokeLater
                tagStagingPanel.setBaseline(cached.tags)
                refreshBaselinePicker(cached.allRanked, cached.selectedBuild)
                updateStatusLabel(cached)
                updateDiagnosticBanner(cached, tagDetection = null)
            }
        }
        // Reconcile in background. Errors keep the cached display untouched
        // and surface in the diagnostic banner.
        reconcileFromBamboo(planKey, token, isRefresh = false)
    }

    /**
     * Refresh path: bypass cache, hit Bamboo, overwrite cache on success.
     * Called on Refresh button click.
     */
    private suspend fun refreshBaseline(planKey: String, token: Long) {
        reconcileFromBamboo(planKey, token, isRefresh = true)
    }

    /** Shared core — single Bamboo call, optional cache write, EDT render. */
    private suspend fun reconcileFromBamboo(planKey: String, token: Long, isRefresh: Boolean) {
        // Step 1: Load baseline with the selected suite plan key
        val baselineResult = tagBuilderService.loadBaselineWithDiagnostics(planKey)
        var tags = baselineResult.tags
        log.info("[Automation:UI] Bamboo reconcile for $planKey (isRefresh=$isRefresh): " +
            "${baselineResult.diagnostics.buildsQueried} builds queried, " +
            "${baselineResult.diagnostics.buildsWithDockerTags} parseable, " +
            "selected=${baselineResult.selectedBuild?.buildNumber}")

        // Step 2: Enrich with latest release tags from Nexus (if configured)
        if (tags.isNotEmpty() && driftDetectorService.isRegistryConfigured()) {
            log.info("[Automation:UI] Fetching latest release tags from registry...")
            tags = driftDetectorService.enrichWithLatestReleaseTags(tags)
        }

        // Step 3: Load plan variables for the suite (master plan key)
        val varsResult = bambooService.getPlanVariables(planKey)

        // Step 4: Docker-tag detection is driven by onFocusBuildChanged (Phase B).
        // At reconcile time we apply whatever we already have from the current focusBuild;
        // subsequent focusBuild emissions will overwrite via onFocusBuildChanged.
        val focusBuild = currentFocusBuild
        val chainKey = focusBuild?.chainKey
        val tagDetection: TagDetectionResult? = if (chainKey != null) {
            withTimeoutOrNull(15_000L) {
                tagBuilderService.detectDockerTag(chainKey)
            }
        } else null
        log.info("[Automation:UI] Reconcile tag detection: chainKey='$chainKey', detected=${tagDetection?.detected}, tag=${tagDetection?.tag}")

        val dockerTagKey = resolveDockerTagKey()
        if (tagDetection?.detected == true && tagDetection.tag != null && dockerTagKey.isNotBlank()) {
            tags = tagBuilderService.replaceCurrentRepoTag(tags, CurrentRepoContext(
                serviceName = dockerTagKey,
                branchName = focusBuild?.branch ?: "",
                featureBranchTag = tagDetection.tag,
                detectedFrom = DetectionSource.SETTINGS_MAPPING
            ))
        }

        // Success path: write cache (only when we actually have data to cache).
        if (baselineResult.selectedBuild != null) {
            baselineCacheService.put(planKey, baselineResult.copy(tags = tags))
        }

        // Step 5: Update UI on EDT
        invokeLater {
            if (token != loadGeneration) {
                log.info("[Automation:UI] Dropping stale reconcile result (gen=$token, current=$loadGeneration)")
                return@invokeLater
            }
            tagStagingPanel.setBaseline(tags)

            if (!varsResult.isError) {
                val vars = varsResult.data!!
                suiteConfigPanel.setAvailableVariables(vars)
                suiteConfigPanel.loadSuiteVariables(planKey)
            }

            // PR 7 #8: refresh the baseline picker to reflect the alternatives
            // we just received from scoreAndRankRuns.
            refreshBaselinePicker(baselineResult.allRanked, baselineResult.selectedBuild)

            updateStatusLabel(baselineResult)

            // Diagnostic banner: pass the pulled detection through; a fresh
            // BuildLogReady from a later poll will overwrite it via onBuildLogReady.
            updateDiagnosticBanner(baselineResult, tagDetection = tagDetection)
        }
    }

    /**
     * Resolves the effective docker tag key using fallback chain:
     * focused-PR RepoConfig.dockerTagKey → primary RepoConfig.dockerTagKey → PluginSettings.State.dockerTagKey.
     *
     * Repo source is the focused PR (the user's "current task" anchor) — never the editor.
     * Editor-derived activeRepo previously caused cross-repo bleed: opening any file in
     * another submodule would swap the staged docker tag for the wrong service.
     */
    private fun resolveDockerTagKey(): String {
        val focusedRepoName = WorkflowContextService
            .getInstance(project).state.value.focusPr?.repoName
        val repoConfig = focusedRepoName
            ?.let { name -> settings.getRepos().firstOrNull { it.name == name || it.displayLabel == name } }
            ?: settings.getPrimaryRepo()
        val fromRepo = repoConfig?.dockerTagKey?.takeIf { it.isNotBlank() }
        val fromGlobal = settings.state.dockerTagKey?.takeIf { it.isNotBlank() }
        return (fromRepo ?: fromGlobal).orEmpty()
    }

    /**
     * Resolves the effective CI plan key using fallback chain:
     * PluginSettings.State.serviceCiPlanKey → focused-PR RepoConfig.bambooPlanKey →
     * primary RepoConfig.bambooPlanKey → PluginSettings.State.bambooPlanKey.
     *
     * Repo source is the focused PR — see [resolveDockerTagKey] for why editor-derived
     * resolution is intentionally avoided here.
     *
     * NOTE (Phase B): This method is no longer called from the docker-tag detection path.
     * Docker-tag detection now uses [currentFocusBuild]?.chainKey exclusively.
     * This method remains because it may be used by other future callers — keep it until
     * Phase D cleans up the remaining indirect usages.
     */
    private fun resolveServiceCiPlanKey(): String {
        val fromDedicated = settings.state.serviceCiPlanKey?.takeIf { it.isNotBlank() }
        if (fromDedicated != null) return fromDedicated

        val focusedRepoName = WorkflowContextService
            .getInstance(project).state.value.focusPr?.repoName
        val repoConfig = focusedRepoName
            ?.let { name -> settings.getRepos().firstOrNull { it.name == name || it.displayLabel == name } }
            ?: settings.getPrimaryRepo()
        val fromRepo = repoConfig?.bambooPlanKey?.takeIf { it.isNotBlank() }
        val fromGlobal = settings.state.bambooPlanKey?.takeIf { it.isNotBlank() }
        return (fromRepo ?: fromGlobal).orEmpty()
    }

    private fun updateStatusLabel(result: BaselineLoadResult) {
        if (result.selectedBuild != null) {
            statusLabel.text = "\u25CF Idle"
            statusLabel.foreground = StatusColors.SUCCESS
        } else {
            val diagnosticText = result.diagnostics.toStatusText()
            statusLabel.text = diagnosticText.ifEmpty { "No baseline found" }
            statusLabel.foreground = StatusColors.WARNING
        }
    }

    private fun updateDiagnosticBanner(baseline: BaselineLoadResult, tagDetection: TagDetectionResult?) {
        // Baseline info
        val build = baseline.selectedBuild
        if (build != null) {
            baselineInfoLabel.text = "\u2713 Baseline: build #${build.buildNumber} " +
                "(${build.releaseTagCount}/${build.totalServices} release tags, score ${build.score})"
            baselineInfoLabel.foreground = StatusColors.SUCCESS
        } else {
            val text = baseline.diagnostics.toStatusText()
            baselineInfoLabel.text = "\u2717 $text"
            baselineInfoLabel.foreground = StatusColors.ERROR
            for (reason in baseline.diagnostics.skippedReasons) {
                log.info("[Automation:UI] Skipped: $reason")
            }
        }

        // Docker tag detection info
        if (tagDetection == null) {
            // Pending — waiting for BuildLogReady event from Build tab
            dockerTagInfoLabel.text = "\u23F3 Waiting for CI build..."
            dockerTagInfoLabel.foreground = StatusColors.INFO
        } else if (tagDetection.detected) {
            dockerTagInfoLabel.text = "\u2713 Docker tag: ${tagDetection.tag} (from ${tagDetection.buildKey})"
            dockerTagInfoLabel.foreground = StatusColors.SUCCESS
            dockerTagInfoLabel.font = dockerTagInfoLabel.font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        } else {
            val isBuildFailure = tagDetection.reason.startsWith("CI build failed")
            dockerTagInfoLabel.text = "${if (isBuildFailure) "\u2717" else "\u26A0"} ${tagDetection.reason}"
            dockerTagInfoLabel.foreground = if (isBuildFailure) StatusColors.ERROR else StatusColors.WARNING
        }

        diagnosticPanel.isVisible = true
        diagnosticPanel.revalidate()
    }

    private fun subscribeToBuildEvents() {
        scope.launch {
            val eventBus = project.getService(EventBus::class.java)
            eventBus.events.collect { event ->
                if (event is WorkflowEvent.BuildLogReady) {
                    onBuildLogReady(event)
                }
            }
        }
    }

    private fun onBuildLogReady(event: WorkflowEvent.BuildLogReady) {
        if (currentSuitePlanKey.isBlank()) {
            log.info("[Automation:UI] Ignoring BuildLogReady — no suite selected (currentSuitePlanKey is blank)")
            return
        }

        // Phase B: filter by chain key — only process events that match the focused
        // PR's chain key. This prevents cross-branch bleed (e.g. develop's poll
        // overwriting a feature branch's detected tag).
        val focusedChainKey = currentFocusBuild?.chainKey
        log.info("[Automation:UI] BuildLogReady event: planKey='${event.planKey}', chainKey='${event.chainKey}', focusedChainKey='$focusedChainKey'")
        if (focusedChainKey == null || !event.chainKey.equals(focusedChainKey, ignoreCase = true)) {
            log.info("[Automation:UI] Ignoring BuildLogReady for chain '${event.chainKey}' — does not match focused chain '$focusedChainKey'")
            return
        }

        log.info("[Automation:UI] BuildLogReady received: ${event.resultKey}, status=${event.status}")

        val tagDetection = tagDetectionFromCachedEvent(event)

        log.info("[Automation:UI] Docker tag detection from event: detected=${tagDetection.detected}, tag=${tagDetection.tag}, reason=${tagDetection.reason}")

        // Update the tag in the staging table if detected. Model read +
        // transform + UI write happen together on EDT so the table model isn't
        // touched from the IO collector thread (Swing models aren't thread-safe).
        if (tagDetection.detected && tagDetection.tag != null) {
            val dockerTagKey = resolveDockerTagKey()
            if (dockerTagKey.isNotBlank()) {
                invokeLater {
                    val currentTags = tagStagingPanel.getCurrentTags()
                    val updatedTags = tagBuilderService.replaceCurrentRepoTag(currentTags, CurrentRepoContext(
                        serviceName = dockerTagKey,
                        branchName = currentFocusBuild?.branch ?: "",
                        featureBranchTag = tagDetection.tag,
                        detectedFrom = DetectionSource.SETTINGS_MAPPING
                    ))
                    // Promote the auto-overlay output to baseline so that Revert is
                    // sticky: every auto-computed tag set (baseline pick OR per-build
                    // feature-branch overlay) becomes the new revert target.  Only the
                    // user's own edits (cell edit, Paste) diverge from baseline.
                    tagStagingPanel.setBaseline(updatedTags)
                }
            }
        }

        // Update diagnostic banner on EDT
        invokeLater {
            updateDockerTagBanner(tagDetection)
        }
    }

    /**
     * Converts a [WorkflowEvent.BuildLogReady] (live or cached) into a
     * [TagDetectionResult] using the same regex pipeline the live-event handler
     * uses. Pure function — no I/O.
     */
    private fun tagDetectionFromCachedEvent(event: WorkflowEvent.BuildLogReady): TagDetectionResult =
        when (event.status) {
            WorkflowEvent.BuildEventStatus.FAILED ->
                TagDetectionResult.buildFailed(event.planKey, event.buildNumber)
            WorkflowEvent.BuildEventStatus.SUCCESS -> {
                if (event.logText.isEmpty()) {
                    TagDetectionResult.logFetchFailed(event.resultKey)
                } else {
                    val tag = tagBuilderService.extractDockerTagFromLog(event.logText)
                    if (tag != null) TagDetectionResult.success(tag, event.resultKey)
                    else TagDetectionResult.noTagInLog(event.resultKey)
                }
            }
        }

    /** Updates only the docker tag portion of the diagnostic banner. */
    private fun updateDockerTagBanner(tagDetection: TagDetectionResult) {
        if (tagDetection.detected) {
            dockerTagInfoLabel.text = "\u2713 Docker tag: ${tagDetection.tag} (from ${tagDetection.buildKey})"
            dockerTagInfoLabel.foreground = StatusColors.SUCCESS
            dockerTagInfoLabel.font = dockerTagInfoLabel.font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        } else {
            // Use ERROR color for build failures, WARNING for other issues
            val isBuildFailure = tagDetection.reason.startsWith("CI build failed")
            dockerTagInfoLabel.text = "${if (isBuildFailure) "\u2717" else "\u26A0"} ${tagDetection.reason}"
            dockerTagInfoLabel.foreground = if (isBuildFailure) StatusColors.ERROR else StatusColors.WARNING
        }
        diagnosticPanel.isVisible = true
        diagnosticPanel.revalidate()
    }

    /**
     * Populates the baseline picker dropdown with the auto-picked build at
     * index 0 followed by the parseable alternatives. Hidden when there are
     * fewer than 2 alternatives (nothing to pick from).
     */
    private fun refreshBaselinePicker(allRanked: List<BaselineRun>, selected: BaselineRun?) {
        currentBaselineAlternatives = allRanked
        suppressBaselineListener = true
        baselineCombo.removeAllItems()
        if (allRanked.size < 2) {
            baselinePickerRow.isVisible = false
            suppressBaselineListener = false
            return
        }
        for (run in allRanked) {
            baselineCombo.addItem(BaselinePickerItem(run.buildNumber, run.releaseTagCount, run.totalServices, run.score))
        }
        if (selected != null) {
            val idx = allRanked.indexOfFirst { it.buildNumber == selected.buildNumber }
            if (idx >= 0) baselineCombo.selectedIndex = idx
        }
        baselinePickerRow.isVisible = true
        suppressBaselineListener = false
    }

    /**
     * Builds the split-button for the header:
     *  - Primary button: trigger with the saved per-suite default stages.
     *    If no default is configured, opens the customize dialog instead.
     *    Never silently falls back to "first stage" or "all stages".
     *  - Arrow button opens a popup with:
     *      • "Trigger Customized…" — always opens ManualStageDialog(CUSTOM_STAGES),
     *        regardless of whether a saved default exists.
     *      • "Trigger All Stages" — enqueues with stages = null (explicit escape hatch).
     */
    private fun buildTriggerSplitButton(): JPanel {
        val mainBtn = JButton("Trigger").apply {
            toolTipText = "Trigger with saved default stages (opens dialog if no default configured)"
            addActionListener { onTriggerDefault() }
        }

        val arrowBtn = JButton("\u25BE").apply {  // \u25BE (small downward triangle)
            toolTipText = "More trigger options"
            isFocusPainted = false
            margin = java.awt.Insets(0, 2, 0, 2)
        }

        val popup = JPopupMenu()
        val customizeItem = JMenuItem("Trigger Customized\u2026")
        val allStagesItem = JMenuItem("Trigger All Stages")
        customizeItem.addActionListener { onTriggerCustomized() }
        allStagesItem.addActionListener { onEnqueueAllStages() }
        popup.add(customizeItem)
        popup.add(allStagesItem)

        arrowBtn.addActionListener {
            popup.show(arrowBtn, 0, arrowBtn.height)
        }

        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(mainBtn)
            add(arrowBtn)
        }
    }

    /**
     * Primary split-button click (H2): enqueue with the saved per-suite default stages.
     *
     * Delegates the async stage-fetch + stale-filter decision to [resolveTriggerDefaultAction]
     * (a standalone testable function) then acts on the returned [TriggerDefaultAction]:
     *
     * - [TriggerDefaultAction.EnqueueWith] — enqueue immediately with the filtered saved stages.
     * - [TriggerDefaultAction.OpenCustomizeDialog] — no default (or stale default emptied by
     *   filter); open the customize dialog so the user makes an explicit choice.
     * - [TriggerDefaultAction.FetchError] — plan-stage fetch failed; surface the error and do
     *   NOT trigger anything. No silent fallback to "all stages" or "first stage".
     */
    private fun onTriggerDefault() {
        if (currentSuitePlanKey.isBlank()) return
        val suitePlanKey = currentSuitePlanKey

        scope.launch {
            val action = resolveTriggerDefaultAction(
                suitePlanKey = suitePlanKey,
                bambooService = bambooService,
                automationSettings = AutomationSettingsService.getInstance()
            )

            invokeLater {
                when (action) {
                    is TriggerDefaultAction.EnqueueWith -> {
                        log.info("[Automation:UI] onTriggerDefault: using saved default stages=${action.stages} for $suitePlanKey")
                        enqueueWith(action.stages)
                    }
                    is TriggerDefaultAction.OpenCustomizeDialog -> {
                        log.info("[Automation:UI] onTriggerDefault: no (or stale) saved default for $suitePlanKey — opening customize dialog")
                        onTriggerCustomized()
                    }
                    is TriggerDefaultAction.FetchError -> {
                        log.warn("[Automation:UI] onTriggerDefault: stage fetch failed for $suitePlanKey — ${action.errorMessage}")
                        statusLabel.text = "✗ Could not load plan stages: ${action.errorMessage}"
                        statusLabel.foreground = com.workflow.orchestrator.core.ui.StatusColors.ERROR
                    }
                }
            }
        }
    }

    /**
     * "Trigger Customized…" popup action: open ManualStageDialog in CUSTOM_STAGES mode,
     * then enqueue with whatever stages the user checked. If the user checked
     * "Save as default for this suite", persists the selection before enqueueing.
     */
    private fun onTriggerCustomized() {
        if (currentSuitePlanKey.isBlank()) return
        val automationSettings = AutomationSettingsService.getInstance()
        val savedDefault = automationSettings.getSuiteDefaultStages(currentSuitePlanKey, currentPlanStages = null)

        // Build the variable preview shown in the dialog. Mirrors what
        // enqueueWith will actually submit so the user can verify the payload
        // before clicking Trigger. The dockerTagsAsJson key matches the Bamboo
        // variable name used by the automation plans.
        val tags = tagStagingPanel.getCurrentTags()
        val dockerTagsPayload = tagBuilderService.buildJsonPayload(tags)
        val previewVars = buildMap {
            putAll(suiteConfigPanel.getVariables())
            if (dockerTagsPayload.isNotBlank()) put("dockerTagsAsJson", dockerTagsPayload)
        }

        val dialog = ManualStageDialog(
            project = project,
            planKey = currentSuitePlanKey,
            scope = scope,
            triggerMode = TriggerMode.CUSTOM_STAGES,
            savedDefaultStages = savedDefault,
            variablesPreview = previewVars,
            suiteDisplayName = automationSettings.getSuiteConfig(currentSuitePlanKey)?.displayName ?: currentSuitePlanKey
        )
        if (dialog.showAndGet()) {
            val result = dialog.getResult()
            if (result.saveAsDefault) {
                automationSettings.setSuiteDefaultStages(currentSuitePlanKey, result.selectedStages)
                log.info("[Automation:UI] Saved default stages for $currentSuitePlanKey: ${result.selectedStages}")
            }
            val stages = result.selectedStages
            enqueueWith(if (stages.isEmpty()) null else stages)
        }
    }

    /**
     * "Trigger All Stages" popup action: enqueue with stages = null (run all stages).
     */
    private fun onEnqueueAllStages() {
        if (currentSuitePlanKey.isBlank()) return
        enqueueWith(stages = null)
    }

    /**
     * Single submission point: build a [QueueEntry] from current UI state and hand
     * it off to [QueueService.enqueue]. [stages] semantics:
     *  - null  → run all stages (backward-compatible with old entries)
     *  - non-null set → run from the first stage in the set forward
     */
    private fun enqueueWith(stages: Set<String>?) {
        val tags = tagStagingPanel.getCurrentTags()
        val mergedVars = suiteConfigPanel.getVariables()
        val dockerTagsPayload = tagBuilderService.buildJsonPayload(tags)

        val entry = QueueEntry(
            id = java.util.UUID.randomUUID().toString(),
            suitePlanKey = currentSuitePlanKey,
            dockerTagsPayload = dockerTagsPayload,
            variables = mergedVars,
            stages = stages,
            enqueuedAt = java.time.Instant.now(),
            status = QueueEntryStatus.WAITING_LOCAL,
            bambooResultKey = null,
            branchKey = selectedBranchKey
        )

        queueService.enqueue(entry)
        statusLabel.text = "\u27F3 Queued"
        statusLabel.foreground = JBColor(0x0969DA, 0x89b4fa)
        tabbedPane.selectedIndex = 1
        log.info("[Automation:UI] Run queued for suite $currentSuitePlanKey (stages=${stages ?: "all"}, branch=${selectedBranchKey ?: "<master>"})")
    }

    override fun dispose() {
        scope.cancel()
    }
}

private data class SuiteComboItem(val planKey: String, val displayName: String) {
    override fun toString() = displayName.ifBlank { planKey }
}

/**
 * Combo entry for the Bamboo plan branch selector. [branchKey] == null means
 * the master/default branch (triggers with the suite plan key directly).
 * [label] is shown in the dropdown — either `shortName` or `name` from Bamboo.
 */
private data class BranchComboItem(val branchKey: String?, val label: String) {
    override fun toString() = label
}

/**
 * Combo entry for the baseline picker (PR 7 #8). Includes the build number,
 * release-tag fraction, and score so the user can compare candidates at a
 * glance: e.g., `#847 — 3/5 release tags, score 35`.
 */
private data class BaselinePickerItem(
    val buildNumber: Int,
    val releaseTagCount: Int,
    val totalServices: Int,
    val score: Int
) {
    override fun toString(): String =
        "#$buildNumber — $releaseTagCount/$totalServices release tags, score $score"
}
