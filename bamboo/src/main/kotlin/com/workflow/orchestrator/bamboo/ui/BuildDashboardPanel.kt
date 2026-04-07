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
import com.workflow.orchestrator.core.maven.MavenBuildService
import com.workflow.orchestrator.core.maven.SurefireReportParser
import com.workflow.orchestrator.core.maven.TeamCityMessageConverter
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.TimeFormatter
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.PrContext
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.util.DefaultBranchResolver
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.border.AbstractBorder

class BuildDashboardPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(BuildDashboardPanel::class.java)
    private val settings = PluginSettings.getInstance(project)
    private var localBuildRunning = false
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
            border = JBUI.Borders.empty(6, 8, 2, 8)
            val titleLabel = JBLabel("BUILD HISTORY").apply {
                icon = AllIcons.Vcs.History
                font = Font(font.family, Font.BOLD, JBUI.scale(10))
                foreground = StatusColors.SECONDARY_TEXT
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
                invokeLater {
                    hintLabel.isVisible = false
                    splitter.isVisible = true
                    headerLabel.text = "Plan: $detectedKey / $branchName"
                }
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

    @Volatile private var cachedBitbucketClient: BitbucketBranchClient? = null
    @Volatile private var cachedBitbucketUrl: String? = null

    private fun getOrCreateBitbucketClient(url: String): BitbucketBranchClient {
        if (url != cachedBitbucketUrl || cachedBitbucketClient == null) {
            cachedBitbucketUrl = url
            cachedBitbucketClient = BitbucketBranchClient.fromConfiguredSettings()
                ?: error("Bitbucket URL not configured")
        }
        return cachedBitbucketClient!!
    }

    private suspend fun detectPlanKeyFromBuildStatus(commitId: String): String? {
        val bitbucketUrl = settings.connections.bitbucketUrl.orEmpty().trimEnd('/')
        if (bitbucketUrl.isBlank()) return null

        val client = getOrCreateBitbucketClient(bitbucketUrl)

        return when (val result = client.getBuildStatuses(commitId)) {
            is ApiResult.Success -> {
                val statuses = result.data
                if (statuses.isNotEmpty()) {
                    // Bitbucket reports the *build* key (e.g. `PROJ-PLAN-42`); strip the
                    // `-42` build-number suffix so the plan key can be used to trigger new
                    // builds. Prefers parsing the browse URL, falls back to trimming digits.
                    val first = statuses.first()
                    val planKey = BitbucketBranchClient.extractPlanKey(first)
                    log.info("[Build:Dashboard] Extracted plan key '$planKey' from build status (raw='${first.key}', url='${first.url}')")
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
        }
    }

    private suspend fun checkDivergence(remoteCommit: String) {
        try {
            val repo = getGitRepo() ?: return
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

    // Repo selector for multi-repo support — show all configured repos, not just those with Bamboo keys
    private val allRepos: List<RepoConfig> = settings.getRepos().filter { it.isConfigured }
    private val repoSelector: ComboBox<String>? = if (allRepos.size > 1) {
        ComboBox(DefaultComboBoxModel(allRepos.map { it.displayLabel }.toTypedArray())).apply {
            val primaryIndex = allRepos.indexOfFirst { it.isPrimary }.takeIf { it >= 0 } ?: 0
            selectedIndex = primaryIndex
        }
    } else null

    private var suppressRepoSelectorListener = false

    // Hint label shown when no PR or no Bamboo plan key is available
    private val hintLabel = JBLabel("").apply {
        foreground = StatusColors.SECONDARY_TEXT
        font = font.deriveFont(java.awt.Font.ITALIC, 11f)
        border = JBUI.Borders.empty(8, 12)
        isVisible = false
    }

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
            add(hintLabel)
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
                    // On branch change, resolve the current repo and use its plan key
                    // PrContext will be updated by the PR tab's auto-select on branch change
                    val currentRepo = if (repoSelector != null && allRepos.isNotEmpty()) {
                        val idx = repoSelector.selectedIndex.takeIf { it >= 0 } ?: 0
                        allRepos.getOrNull(idx)
                    } else null
                    val planKey = currentRepo?.bambooPlanKey?.takeIf { it.isNotBlank() }
                        ?: settings.state.bambooPlanKey.orEmpty()
                    if (planKey.isNotBlank()) {
                        val interval = settings.state.buildPollIntervalSeconds.toLong() * 1000
                        monitorService.switchBranch(planKey, branchName, interval)
                        invokeLater {
                            headerLabel.text = "Plan: $planKey / $branchName"
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
                        hintLabel.isVisible = false
                        splitter.isVisible = true
                        latestBuildNumber = state.buildNumber

                        // Only update header/stages if not viewing a historical build
                        if (!viewingHistoricalBuild) {
                            headerLabel.text = "Plan: ${state.planKey} / ${state.branch}  #${state.buildNumber}"
                            statusLabel.text = "${state.overallStatus} — ${TimeFormatter.formatDurationMillis(state.stages.sumOf { it.durationMs ?: 0 })}"

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

        // Subscribe to PrSelected events from the PR tab
        scope.launch {
            val eventBus = project.getService(EventBus::class.java)
            eventBus.events.collect { event ->
                if (event is WorkflowEvent.PrSelected) {
                    invokeLater { onPrSelectedEvent(event) }
                }
            }
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

    private fun onPrSelectedEvent(event: WorkflowEvent.PrSelected) {
        if (repoSelector == null || allRepos.isEmpty()) return

        // Find the repo in the selector matching the event's repoName
        val repoIndex = allRepos.indexOfFirst { it.displayLabel == event.repoName }
        if (repoIndex < 0) return

        // Switch repo selector (suppress listener to avoid double-fire)
        suppressRepoSelectorListener = true
        repoSelector.selectedIndex = repoIndex
        suppressRepoSelectorListener = false

        // Update PrBar and load builds for this PR
        prBar.showPrInfo(event.prId, event.fromBranch, event.toBranch)
        loadBuildsForContext(event.repoName, event.fromBranch, event.bambooPlanKey)
    }

    private fun loadBuildsForContext(repoName: String, branch: String, bambooPlanKey: String?) {
        hintLabel.isVisible = false
        splitter.isVisible = true

        if (bambooPlanKey.isNullOrBlank()) {
            // No Bamboo plan key — try auto-detect from build statuses, show hint if that also fails
            scope.launch {
                autoDetectAndMonitor(branch)
                // If auto-detect didn't find a plan key, show actionable hint
                if (activePlanKey.isBlank()) {
                    invokeLater {
                        showHint("Bamboo plan key not configured for $repoName \u2014 configure in Settings > CI/CD")
                    }
                }
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

    override fun dispose() {
        monitorService.dispose()
        scope.cancel()
    }

    private fun startMonitoring() {
        // Use selected repo's plan key if multi-repo selector is active
        val selectedRepoPlanKey = if (repoSelector != null && allRepos.isNotEmpty()) {
            val idx = repoSelector.selectedIndex.takeIf { it >= 0 } ?: 0
            allRepos.getOrNull(idx)?.bambooPlanKey.orEmpty()
        } else ""
        val planKey = activePlanKey.ifBlank { selectedRepoPlanKey.ifBlank { settings.state.bambooPlanKey.orEmpty() } }
        if (planKey.isBlank()) {
            // Don't show error — PR detection will auto-detect the plan key
            headerLabel.text = "Waiting for PR detection to find Bamboo plan..."
            return
        }

        val knownBranch = getCurrentBranch()
        val interval = settings.state.buildPollIntervalSeconds.toLong() * 1000
        if (knownBranch != null) {
            headerLabel.text = "Plan: $planKey / $knownBranch"
            loadingIcon.isVisible = true
            monitorService.startPolling(planKey, knownBranch, interval)
        } else {
            loadingIcon.isVisible = true
            scope.launch {
                val branch = getGitRepo()?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
                invokeLater { headerLabel.text = "Plan: $planKey / $branch" }
                monitorService.startPolling(planKey, branch, interval)
            }
        }
    }

    private fun getGitRepo(): git4idea.repo.GitRepository? =
        RepoContextResolver.getInstance(project).resolvePrimaryGitRepo()

    private fun getCurrentBranch(): String? = getGitRepo()?.currentBranchName

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
                    statusLabel.text = "${data.state} — ${TimeFormatter.formatDurationMillis(data.durationSeconds * 1000)}"

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
            statusLabel.text = "${state.overallStatus} — ${TimeFormatter.formatDurationMillis(state.stages.sumOf { it.durationMs ?: 0 })}"
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
                    val branch = getCurrentBranch() ?: getGitRepo()?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
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

    /**
     * Cell renderer for the build history list.
     * Stitch design: left border accent by status, monospace bold build number,
     * outline status badges, sharp 2px corners.
     */
    private class BuildHistoryCellRenderer : ColoredListCellRenderer<BuildResultData>() {

        override fun customizeCellRenderer(
            list: JList<out BuildResultData>,
            value: BuildResultData,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            // Determine status color for left border accent
            val stateNormalized = value.state.lowercase()
            val statusColor = when {
                stateNormalized == "successful" || stateNormalized == "success" -> StatusColors.SUCCESS
                stateNormalized == "failed" || stateNormalized == "error" -> StatusColors.ERROR
                stateNormalized == "inprogress" || stateNormalized == "building" -> StatusColors.WARNING
                else -> StatusColors.SECONDARY_TEXT
            }

            // Left border accent (3px colored strip) + padding
            border = javax.swing.border.CompoundBorder(
                StitchLeftAccentBorder(statusColor, JBUI.scale(3)),
                JBUI.Borders.empty(4, 8, 4, 4)
            )

            // Build number in monospace bold, LINK color
            append("#${value.buildNumber}", SimpleTextAttributes(
                SimpleTextAttributes.STYLE_BOLD,
                if (!selected) StatusColors.LINK else null
            ))

            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // Status icon and text
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
            val durationStr = TimeFormatter.formatDurationMillis(value.durationSeconds * 1000)
            append("   $durationStr", SimpleTextAttributes.GRAYED_ATTRIBUTES)

            // Relative time
            if (value.buildRelativeTime.isNotBlank()) {
                append("   ${value.buildRelativeTime}", SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_ITALIC,
                    if (!selected) StatusColors.SECONDARY_TEXT else null
                ))
            }
        }

    }
}

/**
 * Stitch design: left border accent strip — a thin colored bar on the left edge of a list item.
 */
internal class StitchLeftAccentBorder(
    private val color: Color,
    private val thickness: Int = JBUI.scale(3)
) : AbstractBorder() {
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        // Sharp rectangle (no rounding) for the accent strip
        g2.fillRect(x, y, thickness, height)
        g2.dispose()
    }

    override fun getBorderInsets(c: Component): Insets = Insets(0, thickness, 0, 0)
    override fun getBorderInsets(c: Component, insets: Insets): Insets {
        insets.set(0, thickness, 0, 0)
        return insets
    }
}
