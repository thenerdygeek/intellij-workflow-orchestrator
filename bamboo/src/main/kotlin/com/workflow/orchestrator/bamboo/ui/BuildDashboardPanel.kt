package com.workflow.orchestrator.bamboo.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
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
import com.workflow.orchestrator.core.maven.MavenBuildService
import com.workflow.orchestrator.core.maven.SurefireReportParser
import com.workflow.orchestrator.core.maven.TeamCityMessageConverter
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

    private val log = Logger.getInstance(BuildDashboardPanel::class.java)
    private val settings = PluginSettings.getInstance(project)
    private var localBuildRunning = false
    private val credentialStore = CredentialStore()
    private val apiClient = BambooApiClient(
        baseUrl = settings.state.bambooUrl.orEmpty().trimEnd('/'),
        tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) },
        connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
        readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
    )
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val monitorService = BuildMonitorService.getInstance(project)

    private val stageListPanel = StageListPanel()
    private val stageDetailPanel = StageDetailPanel(project, this)

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

        val branch = getCurrentBranch() ?: (settings.state.defaultTargetBranch ?: "develop")
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

        group.add(object : AnAction("Refresh", "Force poll build status now", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                scope.launch {
                    val planKey = settings.state.bambooPlanKey.orEmpty()
                    val branch = getCurrentBranch() ?: (settings.state.defaultTargetBranch ?: "develop")
                    monitorService.pollOnce(planKey, branch)
                }
            }
        })

        group.add(Separator.getInstance())

        // Local Maven build actions
        group.add(object : AnAction("Compile", "Run local Maven compile", AllIcons.Actions.Compile) {
            override fun actionPerformed(e: AnActionEvent) = runLocalMavenBuild("clean compile")
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = !localBuildRunning }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        })

        group.add(object : AnAction("Test", "Run local Maven tests", AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) = runLocalMavenBuild("clean test")
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = !localBuildRunning }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        })

        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLBAR, group, true)
        actionToolbar.targetComponent = this
        return actionToolbar.component
    }

    private fun runLocalMavenBuild(goals: String) {
        localBuildRunning = true
        statusLabel.text = "Running local: mvn $goals..."

        scope.launch {
            try {
                val mavenService = MavenBuildService.getInstance(project)
                val result = mavenService.runBuild(goals)

                // Parse Surefire test results if this was a test run
                val basePath = project.basePath
                val detailedResults = if (goals.contains("test") && basePath != null) {
                    SurefireReportParser.parseDetailedReports(basePath)
                } else null

                val testResults = detailedResults?.first
                val teamCityMessages = if (detailedResults != null) {
                    TeamCityMessageConverter.convert(detailedResults.second)
                } else emptyList()

                invokeLater {
                    localBuildRunning = false
                    val statusText = buildString {
                        append(if (result.success) "Local build PASSED" else "Local build FAILED (exit ${result.exitCode})")
                        if (testResults != null) {
                            append(" — ${testResults.totalTests} tests, ${testResults.passed} passed")
                            if (testResults.failures > 0) append(", ${testResults.failures} failed")
                            if (testResults.errors > 0) append(", ${testResults.errors} errors")
                            if (testResults.skipped > 0) append(", ${testResults.skipped} skipped")
                        }
                    }
                    statusLabel.text = statusText

                    // Show errors or test failures in the detail panel
                    val logContent = buildString {
                        appendLine("=== Local Maven Build: mvn $goals ===")
                        appendLine()
                        if (result.output.isNotBlank()) appendLine(result.output)
                        if (result.errors.isNotBlank()) {
                            appendLine("--- ERRORS ---")
                            appendLine(result.errors)
                        }
                        if (testResults != null && testResults.failedTests.isNotEmpty()) {
                            appendLine()
                            appendLine("--- FAILED TESTS ---")
                            for (failure in testResults.failedTests) {
                                appendLine("  FAIL: ${failure.className}.${failure.testName}")
                                if (failure.message.isNotBlank()) appendLine("        ${failure.message}")
                            }
                        }
                    }
                    val errors = BuildLogParser.parse(logContent)
                    stageDetailPanel.showLog(logContent, errors)

                    // Feed native test runner UI with TeamCity messages
                    if (teamCityMessages.isNotEmpty()) {
                        stageDetailPanel.showTestResults(teamCityMessages)
                    }

                    log.info("[Build:Local] mvn $goals -> ${if (result.success) "SUCCESS" else "FAILED"}")
                }
            } catch (e: Exception) {
                invokeLater {
                    localBuildRunning = false
                    statusLabel.text = "Local build error: ${e.message}"
                    log.warn("[Build:Local] mvn $goals failed with exception", e)
                }
            }
        }
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
