package com.workflow.orchestrator.bamboo.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.BranchChangeListener
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.service.BuildLogParser
import com.workflow.orchestrator.bamboo.service.BuildMonitorService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.JPanel

class BuildDashboardPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val settings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
    private val apiClient = BambooApiClient(
        baseUrl = settings.state.bambooUrl.orEmpty().trimEnd('/'),
        tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) }
    )
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val monitorService = BuildMonitorService.getInstance(project)

    private val stageListPanel = StageListPanel()
    private val stageDetailPanel = StageDetailPanel()

    private val splitter = JBSplitter(false, 0.35f).apply {
        setSplitterProportionKey("workflow.build.splitter")
        firstComponent = stageListPanel
        secondComponent = stageDetailPanel
    }

    private val headerLabel = JBLabel("Build: loading...")
    private val statusLabel = JBLabel("")

    init {
        border = JBUI.Borders.empty()

        // Toolbar
        val toolbar = createToolbar()
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(headerLabel, BorderLayout.WEST)
            add(toolbar, BorderLayout.EAST)
        }
        add(headerPanel, BorderLayout.NORTH)

        // Splitter (master-detail)
        add(splitter, BorderLayout.CENTER)

        // Status bar
        val statusPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 8)
            add(statusLabel, BorderLayout.WEST)
        }
        add(statusPanel, BorderLayout.SOUTH)

        // Selection listener — load log for selected stage
        stageListPanel.stageList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = stageListPanel.stageList.selectedValue
                if (selected != null && selected.status == BuildStatus.FAILED) {
                    loadBuildLog()
                } else {
                    stageDetailPanel.showEmpty()
                }
            }
        }

        // Manual stage run handler
        stageListPanel.onRunStage = { stage -> triggerManualStage(stage.name) }

        // Subscribe to branch changes
        project.messageBus.connect(this).subscribe(
            BranchChangeListener.VCS_BRANCH_CHANGED,
            object : BranchChangeListener {
                override fun branchWillChange(branchName: String) {}
                override fun branchHasChanged(branchName: String) {
                    val planKey = settings.state.bambooPlanKey.orEmpty()
                    if (planKey.isNotBlank()) {
                        val interval = settings.state.buildPollIntervalSeconds.toLong() * 1000
                        monitorService.switchBranch(planKey, branchName, interval)
                        invokeLater { headerLabel.text = "Plan: $planKey / $branchName" }
                    }
                }
            }
        )

        // Observe state changes
        scope.launch {
            monitorService.stateFlow.collect { state ->
                invokeLater {
                    if (state != null) {
                        headerLabel.text = "Plan: ${state.planKey} / ${state.branch}  #${state.buildNumber}"
                        stageListPanel.updateStages(state.stages)
                        statusLabel.text = "${state.overallStatus} — ${formatDuration(state.stages.sumOf { it.durationMs ?: 0 })}"
                    }
                }
            }
        }

        // Start polling
        startMonitoring()
    }

    override fun dispose() {
        monitorService.dispose()
        scope.cancel()
    }

    private fun startMonitoring() {
        val planKey = settings.state.bambooPlanKey.orEmpty()
        if (planKey.isBlank()) {
            headerLabel.text = "No Bamboo plan configured. Set plan key in Settings."
            return
        }

        val branch = getCurrentBranch() ?: "master"
        val interval = settings.state.buildPollIntervalSeconds.toLong() * 1000
        headerLabel.text = "Plan: $planKey / $branch"
        monitorService.startPolling(planKey, branch, interval)
    }

    private fun getCurrentBranch(): String? {
        val repos = GitRepositoryManager.getInstance(project).repositories
        return repos.firstOrNull()?.currentBranchName
    }

    private fun createToolbar(): javax.swing.JComponent {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Refresh", "Force poll build status now", null) {
            override fun actionPerformed(e: AnActionEvent) {
                scope.launch {
                    val planKey = settings.state.bambooPlanKey.orEmpty()
                    val branch = getCurrentBranch() ?: "master"
                    monitorService.pollOnce(planKey, branch)
                }
            }
        })

        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLBAR, group, true)
        actionToolbar.targetComponent = this
        return actionToolbar.component
    }

    private fun loadBuildLog() {
        val state = monitorService.stateFlow.value ?: return
        val resultKey = "${state.planKey}-${state.buildNumber}"

        scope.launch {
            val logResult = apiClient.getBuildLog(resultKey)
            invokeLater {
                when (logResult) {
                    is ApiResult.Success -> {
                        val errors = BuildLogParser.parse(logResult.data)
                        stageDetailPanel.showLog(logResult.data, errors)
                    }
                    is ApiResult.Error -> {
                        stageDetailPanel.showLog("Failed to load log: ${logResult.message}", emptyList())
                    }
                }
            }
        }
    }

    private fun triggerManualStage(stageName: String) {
        val planKey = settings.state.bambooPlanKey.orEmpty()
        ManualStageDialog(project, apiClient, planKey, stageName, scope).show()
    }
}
