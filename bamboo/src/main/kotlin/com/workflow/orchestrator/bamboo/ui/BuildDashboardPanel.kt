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
import com.intellij.ui.JBColor
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
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
        baseUrl = settings.connections.bambooUrl.orEmpty().trimEnd('/'),
        tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) },
        connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
        readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
    )
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val monitorService = BuildMonitorService.getInstance(project)

    private val stageListPanel = StageListPanel()
    private val stageDetailPanel = StageDetailPanel(project, this)

    // Divergence warning bar
    private val warningLabel = com.intellij.ui.components.JBLabel("").apply {
        foreground = JBColor(java.awt.Color(0xCC, 0x66, 0x00), java.awt.Color(0xFF, 0xAA, 0x33))
        icon = com.intellij.icons.AllIcons.General.Warning
        border = com.intellij.util.ui.JBUI.Borders.empty(4, 8)
        isVisible = false
    }

    private val prBar = PrBar(project, scope) { branchName ->
        log.info("[Build:Dashboard] onPrSelected called with branch='$branchName'")
        if (branchName.isNotBlank()) {
            // Auto-detect Bamboo plan + check divergence
            scope.launch {
                autoDetectAndMonitor(branchName)
            }
        } else {
            log.warn("[Build:Dashboard] Branch name is blank — fromRef may not be in API response")
        }
    }

    private suspend fun autoDetectAndMonitor(branchName: String) {
        val selectedPr = prBar.getSelectedPr()
        val latestCommit = selectedPr?.fromRef?.latestCommit.orEmpty()

        // Check divergence
        if (latestCommit.isNotBlank()) {
            checkDivergence(latestCommit)
        }

        // Try auto-detect Bamboo branch plan key from build statuses
        val configuredPlanKey = settings.state.bambooPlanKey.orEmpty()

        if (latestCommit.isNotBlank()) {
            val detectedKey = detectPlanKeyFromBuildStatus(latestCommit)
            if (detectedKey != null) {
                // Use the detected branch plan key directly (e.g., PROJ-PLAN123)
                log.info("[Build:Dashboard] Using auto-detected branch plan key: $detectedKey")
                val interval = settings.state.buildPollIntervalSeconds.toLong() * 1000
                monitorService.switchBranch(detectedKey, branchName, interval)
                invokeLater { headerLabel.text = "Plan: $detectedKey / $branchName" }
                return
            }
        }

        // Fallback to configured plan key
        if (configuredPlanKey.isNotBlank()) {
            val interval = settings.state.buildPollIntervalSeconds.toLong() * 1000
            monitorService.switchBranch(configuredPlanKey, branchName, interval)
            invokeLater { headerLabel.text = "Plan: $configuredPlanKey / $branchName" }
        } else {
            log.warn("[Build:Dashboard] No Bamboo plan key — configure in Settings or create a build")
            invokeLater { headerLabel.text = "No Bamboo builds found for this branch" }
        }
    }

    private suspend fun detectPlanKeyFromBuildStatus(commitId: String): String? {
        val bitbucketUrl = settings.connections.bitbucketUrl.orEmpty().trimEnd('/')
        if (bitbucketUrl.isBlank()) return null

        val client = BitbucketBranchClient(
            baseUrl = bitbucketUrl,
            tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
        )

        return when (val result = client.getBuildStatuses(commitId)) {
            is ApiResult.Success -> {
                val statuses = result.data
                if (statuses.isNotEmpty()) {
                    // Use the build status key directly — it's the Bamboo branch plan key
                    // e.g., "PROJ-PLAN123" is the key for this branch's builds
                    val planKey = statuses.first().key
                    log.info("[Build:Dashboard] Using build status key as plan key: '$planKey' (url='${statuses.first().url}')")
                    planKey
                } else {
                    log.info("[Build:Dashboard] No build statuses found for commit ${commitId.take(8)}")
                    null
                }
            }
            is ApiResult.Error -> {
                log.warn("[Build:Dashboard] Failed to get build statuses: ${result.message}")
                null
            }
            else -> null
        }
    }

    private suspend fun checkDivergence(remoteCommit: String) {
        try {
            val repos = GitRepositoryManager.getInstance(project).repositories
            val repo = repos.firstOrNull() ?: return
            val localHead = repo.currentRevision ?: return

            if (localHead == remoteCommit) {
                invokeLater { warningLabel.isVisible = false }
                return
            }

            // Count commits ahead/behind using git
            val git = git4idea.commands.Git.getInstance()

            // Local ahead: commits in local but not in remote
            val aheadHandler = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.REV_LIST)
            aheadHandler.addParameters("--count", "$remoteCommit..HEAD")
            val aheadResult = git.runCommand(aheadHandler)
            val ahead = if (aheadResult.success()) aheadResult.getOutputOrThrow().trim().toIntOrNull() ?: 0 else 0

            // Remote ahead: commits in remote but not in local
            val behindHandler = git4idea.commands.GitLineHandler(project, repo.root, git4idea.commands.GitCommand.REV_LIST)
            behindHandler.addParameters("--count", "HEAD..$remoteCommit")
            val behindResult = git.runCommand(behindHandler)
            val behind = if (behindResult.success()) behindResult.getOutputOrThrow().trim().toIntOrNull() ?: 0 else 0

            invokeLater {
                when {
                    ahead > 0 && behind > 0 -> {
                        warningLabel.text = "Local and PR have diverged ($ahead local, $behind remote). Pull and push to sync."
                        warningLabel.isVisible = true
                    }
                    ahead > 0 -> {
                        warningLabel.text = "Local branch is $ahead commit(s) ahead of PR. Push to trigger new builds."
                        warningLabel.isVisible = true
                    }
                    behind > 0 -> {
                        warningLabel.text = "PR has $behind commit(s) not in your local branch. Pull to sync."
                        warningLabel.isVisible = true
                    }
                    else -> warningLabel.isVisible = false
                }
            }
        } catch (e: Exception) {
            log.warn("[Build:Dashboard] Failed to check divergence: ${e.message}")
        }
    }

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

        // PR bar + warning + build splitter
        val topPanel2 = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(prBar)
            add(warningLabel)
        }
        val contentPanel = JPanel(BorderLayout()).apply {
            add(topPanel2, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
        add(contentPanel, BorderLayout.CENTER)

        // Status bar
        val statusPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 8)
            add(statusLabel, BorderLayout.WEST)
        }
        add(statusPanel, BorderLayout.SOUTH)

        // Selection listener — load log for selected job (skip headers)
        stageListPanel.stageList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = stageListPanel.stageList.selectedValue
                if (selected == null || selected.name.startsWith("§")) {
                    stageDetailPanel.showEmpty()
                } else if (selected.resultKey.isNotBlank()) {
                    loadJobLog(selected.resultKey)
                } else if (selected.status == BuildStatus.FAILED) {
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
                        invokeLater {
                            headerLabel.text = "Plan: $planKey / $branchName"
                            prBar.refreshPrs()
                        }
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

        // Fetch PRs for current branch — delay to wait for Git repository initialization
        scope.launch {
            // Wait for Git to be ready (repositories list becomes non-empty)
            var retries = 0
            while (retries < 10) {
                val repos = GitRepositoryManager.getInstance(project).repositories
                if (repos.isNotEmpty()) break
                kotlinx.coroutines.delay(1000)
                retries++
            }
            invokeLater { prBar.refreshPrs() }
        }
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
                val planKey = settings.state.bambooPlanKey.orEmpty()
                if (planKey.isBlank()) {
                    headerLabel.text = "No Bamboo plan configured. Set plan key in Settings."
                    return
                }
                scope.launch {
                    val branch = getCurrentBranch() ?: (settings.state.defaultTargetBranch ?: "develop")
                    monitorService.pollOnce(planKey, branch)
                }
                // Also refresh PR bar
                prBar.refreshPrs()
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

    private fun loadJobLog(resultKey: String) {
        log.info("[Build:Dashboard] Loading job log for $resultKey")
        invokeLater { stageDetailPanel.showLog("Loading log...", emptyList()) }
        scope.launch {
            val logResult = apiClient.getBuildLog(resultKey)
            when (logResult) {
                is ApiResult.Success -> {
                    val logText = logResult.data
                    log.info("[Build:Dashboard] Log loaded: ${logText.length} chars")
                    // ConsoleView handles display — pass full text, it manages truncation internally
                    invokeLater { stageDetailPanel.showLog(logText, emptyList()) }
                }
                is ApiResult.Error -> {
                    invokeLater { stageDetailPanel.showLog("Failed to load job log: ${logResult.message}", emptyList()) }
                }
            }
        }
    }

    private fun loadBuildLog() {
        val state = monitorService.stateFlow.value ?: return
        val resultKey = "${state.planKey}-${state.buildNumber}"

        invokeLater { stageDetailPanel.showLog("Loading log...", emptyList()) }
        scope.launch {
            val logResult = apiClient.getBuildLog(resultKey)
            when (logResult) {
                is ApiResult.Success -> {
                    invokeLater { stageDetailPanel.showLog(logResult.data, emptyList()) }
                }
                is ApiResult.Error -> {
                    invokeLater { stageDetailPanel.showLog("Failed to load log: ${logResult.message}", emptyList()) }
                }
            }
        }
    }

    private fun triggerManualStage(stageName: String) {
        val planKey = settings.state.bambooPlanKey.orEmpty()
        ManualStageDialog(project, apiClient, planKey, stageName, scope).show()
    }
}
