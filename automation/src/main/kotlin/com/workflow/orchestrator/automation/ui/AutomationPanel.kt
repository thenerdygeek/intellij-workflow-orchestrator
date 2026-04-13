package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.model.*
import com.workflow.orchestrator.automation.service.*
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.*
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

    // Header components
    private val suiteCombo = JComboBox<SuiteComboItem>()
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
    private val diagnosticPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(2, 8, 4, 8)
        add(baselineInfoLabel)
        add(dockerTagInfoLabel)
        isVisible = false // hidden until suite is loaded
    }

    // Sub-panels
    private val tagStagingPanel = TagStagingPanel(project)
    private val suiteConfigPanel = SuiteConfigPanel(project)
    private val queueStatusPanel = QueueStatusPanel(project)
    private val monitorPanel = MonitorPanel(project)

    // Sub-tabs
    private val tabbedPane = JBTabbedPane()

    // Branch selector
    private val branchCombo = JComboBox<BranchComboItem>()
    private var suppressBranchListener = false

    // State
    private var currentSuitePlanKey: String = ""
    private var currentBranchPlanKey: String = ""

    init {
        border = JBUI.Borders.empty(4)

        // Load suites into dropdown
        loadSuites()

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
            }

            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                add(JButton("Queue Run").apply {
                    isFocusPainted = false
                    addActionListener { onQueueRun() }
                })
                add(JButton("Trigger Now \u25B6").apply {
                    isFocusPainted = false
                    addActionListener { onTriggerNow() }
                })
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

        // Configure tab: tag table (left) + variables (right)
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

        // Branch selection listener
        branchCombo.addActionListener {
            if (suppressBranchListener) return@addActionListener
            val item = branchCombo.selectedItem as? BranchComboItem ?: return@addActionListener
            onBranchSelected(item)
        }

        // Wire queue/trigger callbacks
        queueStatusPanel.onCancel = { onCancel() }
        queueStatusPanel.onQueue = { onQueueRun() }
        queueStatusPanel.onTriggerNow = { onTriggerNow() }

        Disposer.register(this, tagStagingPanel)
        Disposer.register(this, suiteConfigPanel)
        Disposer.register(this, queueStatusPanel)
        Disposer.register(this, monitorPanel)

        // Subscribe to BuildLogReady events from the Build tab
        subscribeToBuildEvents()

        // Auto-select first suite
        if (suiteCombo.itemCount > 0) {
            suiteCombo.selectedIndex = 0
        }
    }

    private fun loadSuites() {
        val automationSettings = AutomationSettingsService.getInstance()
        val suites = automationSettings.getAllSuites()
        suiteCombo.removeAllItems()
        for (suite in suites) {
            suiteCombo.addItem(SuiteComboItem(suite.planKey, suite.displayName))
        }
        if (suites.isEmpty()) {
            statusLabel.text = "No suites configured \u2014 go to Settings"
        }
    }

    private fun onSuiteSelected(planKey: String) {
        currentSuitePlanKey = planKey
        currentBranchPlanKey = ""
        statusLabel.text = "Loading branches..."
        statusLabel.foreground = StatusColors.INFO
        diagnosticPanel.isVisible = false
        log.info("[Automation:UI] Suite selected: $planKey")

        scope.launch {
            // Step 1: Fetch branches for this suite plan
            val branchesResult = bambooService.getPlanBranches(planKey)
            val branches = if (!branchesResult.isError) branchesResult.data else emptyList()
            log.info("[Automation:UI] Found ${branches.size} branches for $planKey")

            invokeLater {
                suppressBranchListener = true
                branchCombo.removeAllItems()

                for (branch in branches.filter { it.enabled }) {
                    val displayName = branch.shortName.ifBlank { branch.name }
                    branchCombo.addItem(BranchComboItem(branch.key, displayName))
                }

                if (branchCombo.itemCount == 0) {
                    // No branches returned — fall back to the plan key itself
                    branchCombo.addItem(BranchComboItem(planKey, planKey))
                }

                // Default to the branch whose key matches the suite plan key
                val defaultIndex = (0 until branchCombo.itemCount).firstOrNull { i ->
                    branchCombo.getItemAt(i).branchPlanKey == planKey
                } ?: 0
                branchCombo.selectedIndex = defaultIndex
                suppressBranchListener = false

                val selected = branchCombo.selectedItem as? BranchComboItem
                if (selected != null) {
                    onBranchSelected(selected)
                }
            }
        }
    }

    private fun onBranchSelected(branch: BranchComboItem) {
        currentBranchPlanKey = branch.branchPlanKey
        statusLabel.text = "Loading..."
        statusLabel.foreground = StatusColors.INFO
        diagnosticPanel.isVisible = false
        log.info("[Automation:UI] Branch selected: ${branch.branchName} (key=${branch.branchPlanKey})")

        scope.launch {
            // Step 1: Load baseline with the resolved branch plan key
            val baselineResult = tagBuilderService.loadBaselineWithDiagnostics(currentBranchPlanKey)
            var tags = baselineResult.tags
            log.info("[Automation:UI] Baseline result: ${baselineResult.diagnostics.buildsQueried} builds queried, " +
                "${baselineResult.diagnostics.buildsWithDockerTags} with tags, selected=${baselineResult.selectedBuild?.buildNumber}")

            // Step 2: Docker tag detection for current repo
            val tagDetection = detectCurrentRepoTag()

            // Step 3: Replace current repo's tag if detected
            if (tagDetection.detected && tagDetection.tag != null) {
                val dockerTagKey = resolveDockerTagKey()
                tags = tagBuilderService.replaceCurrentRepoTag(tags, CurrentRepoContext(
                    serviceName = dockerTagKey,
                    branchName = "",
                    featureBranchTag = tagDetection.tag,
                    detectedFrom = DetectionSource.SETTINGS_MAPPING
                ))
            }

            // Step 4: Enrich with latest release tags from Nexus (if configured)
            if (tags.isNotEmpty() && driftDetectorService.isRegistryConfigured()) {
                log.info("[Automation:UI] Fetching latest release tags from registry...")
                tags = driftDetectorService.enrichWithLatestReleaseTags(tags)
            }

            // Step 5: Load plan variables for the suite (master plan key)
            val varsResult = bambooService.getPlanVariables(currentSuitePlanKey)

            // Step 6: Update UI on EDT
            invokeLater {
                tagStagingPanel.updateTags(tags)

                if (!varsResult.isError) {
                    val varKeys = varsResult.data.map { it.name }
                    val varValues = varsResult.data.associate { it.name to it.value }
                    suiteConfigPanel.setAvailableVariables(varKeys)
                    suiteConfigPanel.loadSuiteVariables(currentSuitePlanKey)
                    suiteConfigPanel.setVariableValues(varValues)
                }

                // Update status label
                updateStatusLabel(baselineResult)

                // Update diagnostic banner
                updateDiagnosticBanner(baselineResult, tagDetection)
            }
        }
    }

    /**
     * Resolves the effective docker tag key using fallback chain:
     * RepoConfig.dockerTagKey → PluginSettings.State.dockerTagKey
     */
    private fun resolveDockerTagKey(): String {
        val resolver = com.workflow.orchestrator.core.settings.RepoContextResolver.getInstance(project)
        val repoConfig = resolver.resolveFromCurrentEditor() ?: resolver.getPrimary()
        val fromRepo = repoConfig?.dockerTagKey?.takeIf { it.isNotBlank() }
        val fromGlobal = settings.state.dockerTagKey?.takeIf { it.isNotBlank() }
        return (fromRepo ?: fromGlobal).orEmpty()
    }

    /**
     * Resolves the effective CI plan key using fallback chain:
     * PluginSettings.State.serviceCiPlanKey → RepoConfig.bambooPlanKey → PluginSettings.State.bambooPlanKey
     */
    private fun resolveServiceCiPlanKey(): String {
        val fromDedicated = settings.state.serviceCiPlanKey?.takeIf { it.isNotBlank() }
        if (fromDedicated != null) return fromDedicated

        val resolver = com.workflow.orchestrator.core.settings.RepoContextResolver.getInstance(project)
        val repoConfig = resolver.resolveFromCurrentEditor() ?: resolver.getPrimary()
        val fromRepo = repoConfig?.bambooPlanKey?.takeIf { it.isNotBlank() }
        val fromGlobal = settings.state.bambooPlanKey?.takeIf { it.isNotBlank() }
        return (fromRepo ?: fromGlobal).orEmpty()
    }

    private suspend fun detectCurrentRepoTag(): TagDetectionResult {
        val dockerTagKey = resolveDockerTagKey()
        val ciPlanKey = resolveServiceCiPlanKey()

        log.info("[Automation:UI] Resolved dockerTagKey='$dockerTagKey', ciPlanKey='$ciPlanKey'")

        if (dockerTagKey.isBlank()) {
            return TagDetectionResult.notConfigured("Docker Tag Key (check Repositories settings or add bamboo-specs)")
        }
        if (ciPlanKey.isBlank()) {
            return TagDetectionResult.notConfigured("Service CI Plan Key (check Repositories settings)")
        }

        // Detect current branch
        val branch = try {
            val resolver = com.workflow.orchestrator.core.settings.RepoContextResolver.getInstance(project)
            val repoConfig = resolver.resolveFromCurrentEditor() ?: resolver.getPrimary()
            val gitRepoManager = Class.forName("git4idea.repo.GitRepositoryManager")
            val getInstance = gitRepoManager.getMethod("getInstance", Project::class.java)
            val manager = getInstance.invoke(null, project)
            val repos = gitRepoManager.getMethod("getRepositories").invoke(manager) as List<*>
            val targetRepo = if (repoConfig?.localVcsRootPath != null) {
                repos.find { repo ->
                    val root = repo?.javaClass?.getMethod("getRoot")?.invoke(repo)
                    val path = root?.javaClass?.getMethod("getPath")?.invoke(root) as? String
                    path == repoConfig.localVcsRootPath
                }
            } else repos.firstOrNull()
            val repo = targetRepo ?: repos.firstOrNull()
            repo?.javaClass?.getMethod("getCurrentBranchName")?.invoke(repo) as? String ?: ""
        } catch (e: Exception) {
            log.warn("[Automation:UI] Branch detection failed: ${e.message}")
            ""
        }

        if (branch.isBlank()) {
            return TagDetectionResult.branchDetectionFailed()
        }

        return tagBuilderService.detectDockerTag(ciPlanKey, branch)
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

    private fun updateDiagnosticBanner(baseline: BaselineLoadResult, tagDetection: TagDetectionResult) {
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
            // Log skipped reasons for deeper debugging
            for (reason in baseline.diagnostics.skippedReasons) {
                log.info("[Automation:UI] Skipped: $reason")
            }
        }

        // Docker tag detection info
        if (tagDetection.detected) {
            dockerTagInfoLabel.text = "\u2713 Docker tag: ${tagDetection.tag} (from ${tagDetection.buildKey})"
            dockerTagInfoLabel.foreground = StatusColors.SUCCESS
            dockerTagInfoLabel.font = dockerTagInfoLabel.font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        } else {
            dockerTagInfoLabel.text = "\u26A0 ${tagDetection.reason}"
            dockerTagInfoLabel.foreground = StatusColors.WARNING
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
        if (currentSuitePlanKey.isBlank()) return

        // Only process events matching the configured service CI plan key
        val ciPlanKey = resolveServiceCiPlanKey()
        if (ciPlanKey.isBlank() || !event.planKey.equals(ciPlanKey, ignoreCase = true)) {
            log.info("[Automation:UI] Ignoring BuildLogReady for ${event.planKey} (configured CI plan: $ciPlanKey)")
            return
        }

        log.info("[Automation:UI] BuildLogReady received: ${event.resultKey}, status=${event.status}")

        val tagDetection = when (event.status) {
            WorkflowEvent.BuildEventStatus.FAILED ->
                TagDetectionResult.buildFailed(event.planKey, event.buildNumber)
            WorkflowEvent.BuildEventStatus.SUCCESS -> {
                if (event.logText.isEmpty()) {
                    TagDetectionResult.logFetchFailed(event.resultKey)
                } else {
                    val tag = tagBuilderService.extractDockerTagFromLog(event.logText)
                    if (tag != null) {
                        TagDetectionResult.success(tag, event.resultKey)
                    } else {
                        TagDetectionResult.noTagInLog(event.resultKey)
                    }
                }
            }
        }

        log.info("[Automation:UI] Docker tag detection from event: detected=${tagDetection.detected}, tag=${tagDetection.tag}, reason=${tagDetection.reason}")

        // Update the tag in the staging table if detected
        if (tagDetection.detected && tagDetection.tag != null) {
            val dockerTagKey = resolveDockerTagKey()
            if (dockerTagKey.isNotBlank()) {
                val currentTags = tagStagingPanel.getCurrentTags()
                val updatedTags = tagBuilderService.replaceCurrentRepoTag(currentTags, CurrentRepoContext(
                    serviceName = dockerTagKey,
                    branchName = "",
                    featureBranchTag = tagDetection.tag,
                    detectedFrom = DetectionSource.SETTINGS_MAPPING
                ))
                invokeLater { tagStagingPanel.updateTags(updatedTags) }
            }
        }

        // Update diagnostic banner on EDT
        invokeLater {
            updateDockerTagBanner(tagDetection)
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

    private fun onTriggerNow() {
        if (currentSuitePlanKey.isBlank()) return

        val tags = tagStagingPanel.getCurrentTags()
        val extraVars = suiteConfigPanel.getVariables()
        val variables = tagBuilderService.buildTriggerVariables(tags, extraVars)

        log.info("[Automation:UI] Triggering suite $currentSuitePlanKey with ${variables.size} variables")
        statusLabel.text = "Triggering..."

        scope.launch {
            val result = bambooService.triggerBuild(currentSuitePlanKey, variables)
            invokeLater {
                if (!result.isError) {
                    val resultKey = result.data.buildKey
                    log.info("[Automation:UI] Build triggered: $resultKey")
                    statusLabel.text = "\u25B6 Triggered \u2014 $resultKey"
                    statusLabel.foreground = StatusColors.LINK
                    // Switch to Monitor tab
                    tabbedPane.selectedIndex = 1
                    monitorPanel.addRun(currentSuitePlanKey, resultKey)
                } else {
                    statusLabel.text = "Failed: ${result.summary}"
                    statusLabel.foreground = StatusColors.ERROR
                    log.warn("[Automation:UI] Trigger failed: ${result.summary}")
                }
            }
        }
    }

    private fun onQueueRun() {
        if (currentSuitePlanKey.isBlank()) return
        val tags = tagStagingPanel.getCurrentTags()
        val extraVars = suiteConfigPanel.getVariables()
        val dockerTagsPayload = tagBuilderService.buildJsonPayload(tags)

        val entry = QueueEntry(
            id = java.util.UUID.randomUUID().toString(),
            suitePlanKey = currentSuitePlanKey,
            dockerTagsPayload = dockerTagsPayload,
            variables = extraVars,
            stages = suiteConfigPanel.getEnabledStages(),
            enqueuedAt = java.time.Instant.now(),
            status = QueueEntryStatus.WAITING_LOCAL,
            bambooResultKey = null
        )

        queueService.enqueue(entry)
        statusLabel.text = "\u27F3 Queued"
        statusLabel.foreground = JBColor(0x0969DA, 0x89b4fa)
        tabbedPane.selectedIndex = 1
        log.info("[Automation:UI] Run queued for suite $currentSuitePlanKey")
    }

    private fun onCancel() {
        log.info("[Automation:UI] Cancel requested")
        // TODO: cancel from QueueService or Bamboo
    }

    override fun dispose() {
        scope.cancel()
    }
}

private data class SuiteComboItem(val planKey: String, val displayName: String) {
    override fun toString() = displayName.ifBlank { planKey }
}

private data class BranchComboItem(val branchPlanKey: String, val branchName: String) {
    override fun toString() = branchName.ifBlank { branchPlanKey }
}
