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
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

/**
 * Main Automation tab panel.
 * Header: suite selector + status + action buttons.
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

    // Header components
    private val suiteCombo = JComboBox<SuiteComboItem>()
    private val statusLabel = JBLabel("").apply {
        foreground = StatusColors.SUCCESS
    }

    // Sub-panels
    private val tagStagingPanel = TagStagingPanel(project)
    private val suiteConfigPanel = SuiteConfigPanel(project)
    private val queueStatusPanel = QueueStatusPanel(project)
    private val monitorPanel = MonitorPanel(project)

    // Sub-tabs
    private val tabbedPane = JBTabbedPane()

    // State
    private var currentTags: List<TagEntry> = emptyList()
    private var currentSuitePlanKey: String = ""

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
                    font = font.deriveFont(java.awt.Font.BOLD, JBUI.scale(11).toFloat())
                })
                add(suiteCombo)
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
        add(headerPanel, BorderLayout.NORTH)

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

        // Wire queue/trigger callbacks
        queueStatusPanel.onCancel = { onCancel() }
        queueStatusPanel.onQueue = { onQueueRun() }
        queueStatusPanel.onTriggerNow = { onTriggerNow() }

        Disposer.register(this, tagStagingPanel)
        Disposer.register(this, suiteConfigPanel)
        Disposer.register(this, queueStatusPanel)
        Disposer.register(this, monitorPanel)

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
            statusLabel.text = "No suites configured — go to Settings"
        }
    }

    private fun onSuiteSelected(planKey: String) {
        currentSuitePlanKey = planKey
        statusLabel.text = "Loading..."
        log.info("[Automation:UI] Suite selected: $planKey")

        scope.launch {
            // Load baseline tags
            val tags = tagBuilderService.loadBaseline(planKey)

            // Auto-replace current repo's docker tag
            val dockerTagKey = settings.state.dockerTagKey.orEmpty()
            val updatedTags = if (dockerTagKey.isNotBlank()) {
                // Get current branch via RepoContextResolver + reflection to avoid git4idea dependency
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
                } catch (_: Exception) { "" }
                val serviceCiPlanKey = settings.state.serviceCiPlanKey.orEmpty()

                var featureTag: String? = null
                if (serviceCiPlanKey.isNotBlank() && branch.isNotBlank()) {
                    featureTag = tagBuilderService.extractDockerTagFromBuildLog(serviceCiPlanKey, branch)
                }

                if (featureTag != null) {
                    tagBuilderService.replaceCurrentRepoTag(tags, CurrentRepoContext(
                        serviceName = dockerTagKey,
                        branchName = branch,
                        featureBranchTag = featureTag,
                        detectedFrom = DetectionSource.SETTINGS_MAPPING
                    ))
                } else tags
            } else tags

            currentTags = updatedTags

            // Load plan variables via BambooService (core interface)
            val varsResult = bambooService.getPlanVariables(planKey)

            invokeLater {
                // Update tag table
                tagStagingPanel.updateTags(updatedTags)

                // Update variables panel
                if (!varsResult.isError) {
                    val varKeys = varsResult.data.map { it.name }
                    val varValues = varsResult.data.associate { it.name to it.value }
                    suiteConfigPanel.setAvailableVariables(varKeys)
                    suiteConfigPanel.setVariableValues(varValues)
                }

                statusLabel.text = if (tags.isEmpty()) "No baseline found" else "● Idle"
                statusLabel.foreground = StatusColors.SUCCESS
            }
        }
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
                    statusLabel.text = "▶ Triggered — $resultKey"
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
        statusLabel.text = "⟳ Queued"
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
