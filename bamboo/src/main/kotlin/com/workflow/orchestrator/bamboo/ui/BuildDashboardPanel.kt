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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.BranchChangeListener
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.service.BuildLogParser
import com.workflow.orchestrator.bamboo.service.BuildMonitorService
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.maven.MavenBuildService
import com.workflow.orchestrator.core.maven.SurefireReportParser
import com.workflow.orchestrator.core.maven.TeamCityMessageConverter
import com.intellij.ui.JBColor
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import com.workflow.orchestrator.core.settings.RepoContextResolver
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class BuildDashboardPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(BuildDashboardPanel::class.java)
    private val settings = PluginSettings.getInstance(project)
    private var localBuildRunning = false
    private val credentialStore = CredentialStore()
    private val bambooService = project.getService(BambooService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Auto-detected plan key (not saved to settings — it's branch-specific)
    @Volatile
    private var activePlanKey: String = ""
    private val monitorService = BuildMonitorService.getInstance(project)

    private val stageListPanel = StageListPanel()
    private val stageDetailPanel = StageDetailPanel(project, this)

    // Divergence warning bar
    private val warningLabel = com.intellij.ui.components.JBLabel("").apply {
        foreground = StatusColors.WARNING
        icon = com.intellij.icons.AllIcons.General.Warning
        border = com.intellij.util.ui.JBUI.Borders.empty(4, 8)
        isVisible = false
    }

    // Newer build running banner
    private val newerBuildBanner = JPanel(BorderLayout()).apply {
        background = StatusColors.INFO_BG
        border = com.intellij.util.ui.JBUI.Borders.empty(4, 8)
        isVisible = false
        add(com.intellij.ui.components.JBLabel("").apply {
            foreground = StatusColors.LINK
            icon = com.intellij.icons.AllIcons.Toolwindows.ToolWindowRun
            name = "newerBuildLabel"
        }, BorderLayout.CENTER)
    }

    // Historical build banner — shown when viewing a non-latest build
    private val historicalBuildBanner = JPanel(BorderLayout()).apply {
        background = StatusColors.INFO_BG
        border = com.intellij.util.ui.JBUI.Borders.empty(4, 8)
        isVisible = false

        val label = JBLabel("").apply {
            foreground = StatusColors.INFO
            icon = AllIcons.General.Information
        }
        add(label, BorderLayout.CENTER)

        val viewLatestLink = JBLabel("View Latest").apply {
            foreground = StatusColors.LINK
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(0, 8, 0, 0)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    returnToLatestBuild()
                }
            })
        }
        add(viewLatestLink, BorderLayout.EAST)
    }

    // Build history list
    private val historyListModel = DefaultListModel<BuildResultData>()
    private val historyList = JBList(historyListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = BuildHistoryCellRenderer()
        visibleRowCount = 5
    }
    private val historyPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty()
        isVisible = false

        val historyHeader = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8, 2, 8)
            val titleLabel = JBLabel("Build History").apply {
                icon = AllIcons.Vcs.History
            }
            add(titleLabel, BorderLayout.WEST)
        }
        add(historyHeader, BorderLayout.NORTH)

        val scrollPane = com.intellij.ui.components.JBScrollPane(historyList).apply {
            preferredSize = java.awt.Dimension(0, JBUI.scale(120))
            border = JBUI.Borders.empty(0, 8, 4, 8)
        }
        add(scrollPane, BorderLayout.CENTER)
    }

    // Track whether we're viewing a historical build
    @Volatile
    private var viewingHistoricalBuild: Boolean = false
    @Volatile
    private var latestBuildNumber: Int? = null

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
                activePlanKey = detectedKey
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
            val resolver = RepoContextResolver.getInstance(project)
            val repoConfig = resolver.resolveFromCurrentEditor() ?: resolver.getPrimary()
            val repos = GitRepositoryManager.getInstance(project).repositories
            val repo = (if (repoConfig?.localVcsRootPath != null) {
                repos.find { it.root.path == repoConfig.localVcsRootPath }
            } else repos.firstOrNull()) ?: repos.firstOrNull() ?: return
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
    private val loadingIcon = JBLabel(AnimatedIcon.Default()).apply { isVisible = false }

    // Repo selector for multi-repo support
    private val bambooRepos: List<RepoConfig> = settings.getRepos().filter { !it.bambooPlanKey.isNullOrBlank() }
    private val repoSelector: ComboBox<String>? = if (bambooRepos.size > 1) {
        ComboBox(DefaultComboBoxModel(bambooRepos.map { it.displayLabel }.toTypedArray())).apply {
            // Pre-select the primary repo or the first one
            val primaryIndex = bambooRepos.indexOfFirst { it.isPrimary }.takeIf { it >= 0 } ?: 0
            selectedIndex = primaryIndex
        }
    } else null

    init {
        border = JBUI.Borders.empty()

        // Toolbar
        val toolbar = createToolbar()
        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            if (repoSelector != null) {
                add(repoSelector)
            }
            add(headerLabel)
        }
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(headerLeft, BorderLayout.WEST)
            add(toolbar, BorderLayout.EAST)
        }
        add(headerPanel, BorderLayout.NORTH)

        // PR bar + warning + newer build banner + historical build banner + history list + build splitter
        val topPanel2 = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(prBar)
            add(warningLabel)
            add(newerBuildBanner)
            add(historicalBuildBanner)
            add(historyPanel)
        }
        val contentPanel = JPanel(BorderLayout()).apply {
            add(topPanel2, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
        add(contentPanel, BorderLayout.CENTER)

        // Status bar
        val statusPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(2, 8)
            add(loadingIcon)
            add(statusLabel)
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

        // Build history click handler — load selected historical build
        historyList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = historyList.selectedValue ?: return@addListSelectionListener
                loadHistoricalBuild(selected)
            }
        }

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
                            // Clear history on branch switch
                            viewingHistoricalBuild = false
                            historicalBuildBanner.isVisible = false
                            historyListModel.clear()
                            historyPanel.isVisible = false
                        }
                    }
                }
            }
        )

        // Observe state changes — only rebuild stage list if build number changes
        var lastDisplayedBuildNumber: Int? = null
        scope.launch {
            monitorService.stateFlow.collect { state ->
                invokeLater {
                    if (state != null) {
                        loadingIcon.isVisible = false
                        latestBuildNumber = state.buildNumber

                        // Only update header/stages if not viewing a historical build
                        if (!viewingHistoricalBuild) {
                            headerLabel.text = "Plan: ${state.planKey} / ${state.branch}  #${state.buildNumber}"
                            statusLabel.text = "${state.overallStatus} — ${formatDuration(state.stages.sumOf { it.durationMs ?: 0 })}"

                            // Only rebuild stage list if build number changed (avoids resetting selection + re-fetching log)
                            if (state.buildNumber != lastDisplayedBuildNumber) {
                                lastDisplayedBuildNumber = state.buildNumber
                                stageListPanel.updateStages(state.stages)

                                // Load build history lazily after current build is displayed
                                loadBuildHistory()
                            }
                        }

                        // Show/hide newer build banner
                        val newer = state.newerBuild
                        if (newer != null) {
                            val statusText = when (newer.status) {
                                BuildStatus.IN_PROGRESS -> "running"
                                BuildStatus.PENDING -> "queued"
                                else -> newer.status.name.lowercase()
                            }
                            val label = newerBuildBanner.components
                                .filterIsInstance<com.intellij.ui.components.JBLabel>()
                                .firstOrNull()
                            label?.text = "Build #${newer.buildNumber} is $statusText — will update automatically when complete"
                            newerBuildBanner.isVisible = true
                        } else {
                            newerBuildBanner.isVisible = false
                        }
                    }
                }
            }
        }

        // Start polling
        startMonitoring()

        // Repo selector listener — switch monitored plan when a different repo is selected
        repoSelector?.addActionListener {
            val selectedIndex = repoSelector.selectedIndex
            if (selectedIndex >= 0 && selectedIndex < bambooRepos.size) {
                val selectedRepo = bambooRepos[selectedIndex]
                val newPlanKey = selectedRepo.bambooPlanKey.orEmpty()
                if (newPlanKey.isNotBlank()) {
                    activePlanKey = newPlanKey
                    val branch = getCurrentBranch() ?: (settings.state.defaultTargetBranch ?: "develop")
                    val interval = settings.state.buildPollIntervalSeconds.toLong() * 1000
                    headerLabel.text = "Plan: $newPlanKey / $branch"
                    loadingIcon.isVisible = true
                    // Clear history and reset state for the new plan
                    viewingHistoricalBuild = false
                    historicalBuildBanner.isVisible = false
                    historyListModel.clear()
                    historyPanel.isVisible = false
                    monitorService.switchBranch(newPlanKey, branch, interval)
                    prBar.refreshPrs()
                }
            }
        }

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

        // Wire visibility to SmartPoller so polling pauses when tab is not visible
        addAncestorListener(object : javax.swing.event.AncestorListener {
            override fun ancestorAdded(event: javax.swing.event.AncestorEvent) {
                monitorService.setVisible(true)
            }
            override fun ancestorRemoved(event: javax.swing.event.AncestorEvent) {
                monitorService.setVisible(false)
            }
            override fun ancestorMoved(event: javax.swing.event.AncestorEvent) {}
        })
    }

    override fun dispose() {
        monitorService.dispose()
        scope.cancel()
    }

    private fun startMonitoring() {
        // Use selected repo's plan key if multi-repo selector is active
        val selectedRepoPlanKey = if (repoSelector != null && bambooRepos.isNotEmpty()) {
            val idx = repoSelector.selectedIndex.takeIf { it >= 0 } ?: 0
            bambooRepos.getOrNull(idx)?.bambooPlanKey.orEmpty()
        } else ""
        val planKey = activePlanKey.ifBlank { selectedRepoPlanKey.ifBlank { settings.state.bambooPlanKey.orEmpty() } }
        if (planKey.isBlank()) {
            // Don't show error — PR detection will auto-detect the plan key
            headerLabel.text = "Waiting for PR detection to find Bamboo plan..."
            return
        }

        val branch = getCurrentBranch() ?: (settings.state.defaultTargetBranch ?: "develop")
        val interval = settings.state.buildPollIntervalSeconds.toLong() * 1000
        headerLabel.text = "Plan: $planKey / $branch"
        loadingIcon.isVisible = true
        monitorService.startPolling(planKey, branch, interval)
    }

    private fun getCurrentBranch(): String? {
        val resolver = RepoContextResolver.getInstance(project)
        val repoConfig = resolver.getPrimary()
        val repos = GitRepositoryManager.getInstance(project).repositories
        val targetRepo = repos.find { it.root.path == repoConfig?.localVcsRootPath } ?: repos.firstOrNull()
        return targetRepo?.currentBranchName
    }

    /** Load build history for the current plan key */
    private fun loadBuildHistory() {
        val planKey = activePlanKey.ifBlank { settings.state.bambooPlanKey.orEmpty() }
        if (planKey.isBlank()) return

        scope.launch {
            val result = bambooService.getRecentBuilds(planKey, 10)
            if (!result.isError) {
                val builds = result.data
                invokeLater {
                    historyListModel.clear()
                    builds.forEach { historyListModel.addElement(it) }
                    historyPanel.isVisible = builds.isNotEmpty()
                    log.info("[Build:Dashboard] Loaded ${builds.size} history entries for $planKey")
                }
            } else {
                log.warn("[Build:Dashboard] Failed to load build history: ${result.summary}")
            }
        }
    }

    /** Load a historical build's details and update the stage/detail panels */
    private fun loadHistoricalBuild(build: BuildResultData) {
        val resultKey = build.buildResultKey.ifBlank { "${build.planKey}-${build.buildNumber}" }
        log.info("[Build:Dashboard] Loading historical build: $resultKey (#${build.buildNumber})")

        stageDetailPanel.showArtifacts(resultKey)

        invokeLater {
            viewingHistoricalBuild = true
            // Show historical build banner
            val bannerLabel = historicalBuildBanner.components
                .filterIsInstance<JBLabel>()
                .firstOrNull { it.icon == AllIcons.General.Information }
            bannerLabel?.text = "Viewing build #${build.buildNumber}"
            historicalBuildBanner.isVisible = true

            headerLabel.text = "Plan: ${build.planKey}  #${build.buildNumber} (historical)"
            statusLabel.text = "Loading build #${build.buildNumber}..."
        }

        scope.launch {
            val buildResult = bambooService.getBuild(resultKey)
            if (!buildResult.isError) {
                val data = buildResult.data
                invokeLater {
                    statusLabel.text = "${data.state} — ${formatDurationSeconds(data.durationSeconds)}"

                    // Convert BuildResultData stages to StageState for the stage list panel
                    val stageStates = data.stages.map { stage ->
                        com.workflow.orchestrator.bamboo.model.StageState(
                            name = stage.name,
                            status = com.workflow.orchestrator.bamboo.model.BuildStatus.fromBambooState(stage.state, ""),
                            durationMs = stage.durationSeconds * 1000,
                            resultKey = "",
                            manual = false
                        )
                    }
                    stageListPanel.updateStages(stageStates)
                }
            } else {
                invokeLater {
                    statusLabel.text = "Failed to load build #${build.buildNumber}: ${buildResult.summary}"
                }
            }
        }
    }

    /** Return to monitoring the latest build */
    private fun returnToLatestBuild() {
        viewingHistoricalBuild = false
        historicalBuildBanner.isVisible = false
        historyList.clearSelection()

        // Re-display the latest state from the monitor
        val state = monitorService.stateFlow.value
        if (state != null) {
            headerLabel.text = "Plan: ${state.planKey} / ${state.branch}  #${state.buildNumber}"
            statusLabel.text = "${state.overallStatus} — ${formatDuration(state.stages.sumOf { it.durationMs ?: 0 })}"
            stageListPanel.updateStages(state.stages)
        }
    }

    private fun createToolbar(): javax.swing.JComponent {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Refresh", "Force poll build status now", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                val planKey = activePlanKey.ifBlank { settings.state.bambooPlanKey.orEmpty() }
                if (planKey.isBlank()) {
                    // Try to re-detect from PR
                    prBar.refreshPrs()
                    headerLabel.text = "Detecting Bamboo plan..."
                    return
                }
                // Return to latest build if viewing historical
                if (viewingHistoricalBuild) {
                    returnToLatestBuild()
                }
                scope.launch {
                    val branch = getCurrentBranch() ?: (settings.state.defaultTargetBranch ?: "develop")
                    monitorService.pollOnce(planKey, branch)
                }
                // Also refresh PR bar and build history
                prBar.refreshPrs()
                loadBuildHistory()
            }
        })

        group.add(object : AnAction("Stop Build", "Stop a running build", AllIcons.Actions.Suspend) {
            override fun actionPerformed(e: AnActionEvent) {
                val state = monitorService.stateFlow.value ?: return
                val resultKey = "${state.planKey}-${state.buildNumber}"
                val confirm = Messages.showYesNoDialog(
                    project,
                    "Stop build ${state.planKey} #${state.buildNumber}?",
                    "Stop Build",
                    Messages.getQuestionIcon()
                )
                if (confirm != Messages.YES) return
                statusLabel.text = "Stopping build..."
                scope.launch {
                    val result = bambooService.stopBuild(resultKey)
                    invokeLater {
                        if (!result.isError) {
                            statusLabel.text = "Build $resultKey stopped"
                            scope.launch {
                                kotlinx.coroutines.delay(2000)
                                monitorService.pollOnce(state.planKey, state.branch)
                            }
                        } else {
                            statusLabel.text = "Stop failed: ${result.summary}"
                        }
                    }
                }
            }
            override fun update(e: AnActionEvent) {
                val state = monitorService.stateFlow.value
                e.presentation.isEnabled = state != null &&
                    state.overallStatus == BuildStatus.IN_PROGRESS
            }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        })

        group.add(object : AnAction("Cancel Build", "Cancel a queued build", AllIcons.Actions.Cancel) {
            override fun actionPerformed(e: AnActionEvent) {
                val state = monitorService.stateFlow.value ?: return
                val resultKey = "${state.planKey}-${state.buildNumber}"
                val confirm = Messages.showYesNoDialog(
                    project,
                    "Cancel queued build ${state.planKey} #${state.buildNumber}?",
                    "Cancel Build",
                    Messages.getQuestionIcon()
                )
                if (confirm != Messages.YES) return
                statusLabel.text = "Cancelling build..."
                scope.launch {
                    val result = bambooService.cancelBuild(resultKey)
                    invokeLater {
                        if (!result.isError) {
                            statusLabel.text = "Build $resultKey cancelled"
                            scope.launch {
                                kotlinx.coroutines.delay(2000)
                                monitorService.pollOnce(state.planKey, state.branch)
                            }
                        } else {
                            statusLabel.text = "Cancel failed: ${result.summary}"
                        }
                    }
                }
            }
            override fun update(e: AnActionEvent) {
                val state = monitorService.stateFlow.value
                e.presentation.isEnabled = state != null &&
                    (state.overallStatus == BuildStatus.PENDING)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        })

        group.add(object : AnAction("Rerun Failed Jobs", "Rerun failed/incomplete jobs on Bamboo", AllIcons.Actions.Restart) {
            override fun actionPerformed(e: AnActionEvent) {
                val state = monitorService.stateFlow.value
                if (state == null) {
                    statusLabel.text = "No build to rerun"
                    return
                }
                val planKey = activePlanKey.ifBlank { state.planKey }
                val buildNumber = state.buildNumber
                if (planKey.isBlank() || buildNumber <= 0) {
                    statusLabel.text = "No build to rerun"
                    return
                }
                statusLabel.text = "Rerunning failed jobs..."
                scope.launch {
                    val result = bambooService.rerunFailedJobs(planKey, buildNumber)
                    invokeLater {
                        if (!result.isError) {
                            statusLabel.text = "Rerun triggered for $planKey #$buildNumber"
                            // Poll immediately to get updated status
                            scope.launch {
                                kotlinx.coroutines.delay(2000)
                                monitorService.pollOnce(planKey, state.branch)
                            }
                        } else {
                            statusLabel.text = "Rerun failed: ${result.summary}"
                        }
                    }
                }
            }
            override fun update(e: AnActionEvent) {
                val state = monitorService.stateFlow.value
                e.presentation.isEnabled = state != null &&
                    state.overallStatus == com.workflow.orchestrator.bamboo.model.BuildStatus.FAILED
            }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        })

        group.add(object : AnAction("Trigger Build", "Trigger a new build with custom variables", AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) {
                val planKey = activePlanKey.ifBlank { settings.state.bambooPlanKey.orEmpty() }
                if (planKey.isBlank()) return
                openTriggerDialog(planKey)
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = activePlanKey.isNotBlank() || !settings.state.bambooPlanKey.isNullOrBlank()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
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
        loadingIcon.isVisible = true
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
                    loadingIcon.isVisible = false
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
                    loadingIcon.isVisible = false
                    statusLabel.text = "Local build error: ${e.message}"
                    log.warn("[Build:Local] mvn $goals failed with exception", e)
                }
            }
        }
    }

    private fun loadJobLog(resultKey: String) {
        log.info("[Build:Dashboard] Loading job log + test results + artifacts for $resultKey")
        invokeLater { stageDetailPanel.showLog("Loading log...", emptyList()) }
        stageDetailPanel.showArtifacts(resultKey)
        scope.launch {
            // Fetch log first (needed for both display and test error extraction)
            var buildLogText: String? = null
            val logResult = bambooService.getBuildLog(resultKey)
            if (!logResult.isError) {
                buildLogText = logResult.data
                log.info("[Build:Dashboard] Log loaded: ${buildLogText.length} chars")
                invokeLater { stageDetailPanel.showLog(buildLogText, emptyList()) }
            } else {
                invokeLater { stageDetailPanel.showLog("Failed to load job log: ${logResult.summary}", emptyList()) }
            }

            // Fetch test results for this job
            val testResult = bambooService.getTestResults(resultKey)
            if (!testResult.isError) {
                val testData = testResult.data
                if (testData.total > 0) {
                    log.info("[Build:Dashboard] Test results: ${testData.total} total, ${testData.failed} failed, ${testData.passed} passed")
                    // Convert to TeamCity messages for native test runner UI
                    // Re-fetch raw DTO for BambooTestResultConverter compatibility
                    val messages = com.workflow.orchestrator.bamboo.service.BambooTestResultConverter
                        .fromTestResultsData(testData, buildLogText)
                    if (messages.isNotEmpty()) {
                        invokeLater { stageDetailPanel.showTestResults(messages) }
                    }
                }
            } else {
                log.warn("[Build:Dashboard] Failed to fetch test results: ${testResult.summary}")
            }
        }
    }

    private fun loadBuildLog() {
        val state = monitorService.stateFlow.value ?: return
        val resultKey = "${state.planKey}-${state.buildNumber}"

        invokeLater { stageDetailPanel.showLog("Loading log...", emptyList()) }
        scope.launch {
            val logResult = bambooService.getBuildLog(resultKey)
            if (!logResult.isError) {
                invokeLater { stageDetailPanel.showLog(logResult.data, emptyList()) }
            } else {
                invokeLater { stageDetailPanel.showLog("Failed to load log: ${logResult.summary}", emptyList()) }
            }
        }
    }

    private fun triggerManualStage(stageName: String) {
        val planKey = settings.state.bambooPlanKey.orEmpty()
        ManualStageDialog(project, planKey, stageName, scope).show()
    }

    private fun openTriggerDialog(planKey: String) {
        ManualStageDialog(project, planKey, scope = scope, triggerMode = TriggerMode.FULL_BUILD).show()
    }

    private fun formatDurationSeconds(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    }

    /**
     * Cell renderer for the build history list.
     * Shows: #buildNumber  status_icon  State  duration  relative_time
     */
    private class BuildHistoryCellRenderer : ColoredListCellRenderer<BuildResultData>() {
        override fun customizeCellRenderer(
            list: JList<out BuildResultData>,
            value: BuildResultData,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            // Build number in link color
            append("#${value.buildNumber}", SimpleTextAttributes(
                SimpleTextAttributes.STYLE_BOLD,
                if (!selected) StatusColors.LINK else null
            ))

            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // Status icon and text
            val stateNormalized = value.state.lowercase()
            when {
                stateNormalized == "successful" || stateNormalized == "success" -> {
                    icon = AllIcons.General.InspectionsOK
                    append("Success", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        if (!selected) StatusColors.SUCCESS else null
                    ))
                }
                stateNormalized == "failed" || stateNormalized == "error" -> {
                    icon = AllIcons.General.Error
                    append("Failed", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        if (!selected) StatusColors.ERROR else null
                    ))
                }
                stateNormalized == "inprogress" || stateNormalized == "building" -> {
                    icon = AllIcons.Process.Step_1
                    append("Running", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        if (!selected) StatusColors.WARNING else null
                    ))
                }
                stateNormalized == "queued" || stateNormalized == "pending" -> {
                    icon = AllIcons.Process.Step_1
                    append("Pending", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                else -> {
                    icon = AllIcons.General.Information
                    append(value.state, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }

            // Duration
            val durationStr = formatHistoryDuration(value.durationSeconds)
            append("   $durationStr", SimpleTextAttributes.GRAYED_ATTRIBUTES)

            // Relative time
            if (value.buildRelativeTime.isNotBlank()) {
                append("   ${value.buildRelativeTime}", SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_ITALIC,
                    if (!selected) StatusColors.SECONDARY_TEXT else null
                ))
            }
        }

        private fun formatHistoryDuration(seconds: Long): String {
            if (seconds <= 0) return "--"
            val mins = seconds / 60
            val secs = seconds % 60
            return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
        }
    }
}
