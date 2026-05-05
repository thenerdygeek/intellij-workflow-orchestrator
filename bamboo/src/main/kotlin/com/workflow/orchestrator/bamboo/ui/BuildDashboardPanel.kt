package com.workflow.orchestrator.bamboo.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
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
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.TimeFormatter
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.util.DefaultBranchResolver
import com.workflow.orchestrator.core.workflow.OpenPrLister
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import com.workflow.orchestrator.core.workflow.ui.ReadOnlyBanner
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private val workflowContextService = WorkflowContextService.getInstance(project)
    // Phase 5 T15: amber banner shown when interactionMode == ReadOnly. Wires
    // its own coroutine scope; we register it as a child Disposable so the
    // collector cancels when this panel is disposed (tool-window dispose cascade).
    private val readOnlyBanner = ReadOnlyBanner(project).also {
        Disposer.register(this, it)
    }
    /**
     * Single field scope for all fire-and-forget launches in this panel — follows the
     * non-`@Service` convention in `core/CLAUDE.md` "Service & threading conventions"
     * (canonical example: `AgentController.controllerScope`). Cancelled in [dispose],
     * which the tool-window `Content` lifecycle guarantees via the
     * `WorkflowToolWindowFactory` dispose cascade. `Dispatchers.IO` (not `.Default`)
     * because every launch here is I/O — Bamboo HTTP, log fetch, monitor polling.
     */
    private val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Auto-detected plan key (not saved to settings — it's branch-specific)
    @Volatile
    private var activePlanKey: String = ""

    /**
     * Derived "which repo am I looking at" — never mutable. Reads from the canonical
     * [WorkflowContextService.state] so the Build tab cannot drift from the project-wide
     * focus. When no PR is focused yet, falls back to the first configured repo (item 4
     * of the repo-resolution sweep) — the editor is no longer consulted for the Build
     * tab's initial repo. Once the user picks a PR or repo from the dropdown, the
     * service is the single source of truth.
     *
     * The previous implementation kept this as a `@Volatile var` mutated by three
     * paths (init, onFocusPrChanged, the repoSelector listener) — that's how the
     * dropdown could say repo_1 while the build content stayed on repo_3. Now there's
     * one source: the service.
     */
    private val activeRepoConfig: RepoConfig?
        get() {
            val focusedRepoName = workflowContextService.state.value.focusPr?.repoName
            if (focusedRepoName != null) {
                allRepos.firstOrNull { it.displayLabel == focusedRepoName }
                    ?.let { return it }
            }
            // No focused PR (or focused on an unknown repo). Item 4: fall back to the
            // first configured repo (primary if marked, else first). The editor is
            // explicitly NOT consulted — the user's dropdown selection is the source
            // of truth from then on. Returns null only when no repos are configured;
            // the rest of the panel already handles that case.
            return when {
                allRepos.isNotEmpty() -> allRepos.firstOrNull { it.isPrimary } ?: allRepos.first()
                else -> settings.getPrimaryRepo()
            }
        }
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

    private val prBar = PrBar(
        project = project,
        scope = panelScope,
    )

    /**
     * Resolve the Bamboo branch plan key for [branchName] and start monitoring.
     *
     * Routes through [com.workflow.orchestrator.core.services.BambooService.autoDetectPlan]
     * — the canonical 5-tier waterfall built in commit `a45bcfdb` (2026-04-26). Tier
     * order: T0 local bamboo-specs → T1 Bitbucket build-status commit walk → T2 Bamboo
     * `byChangeset` → T3 Linked-Repositories → T4 deep-scan (gated). After any tier
     * hits, [com.workflow.orchestrator.bamboo.service.PlanDetectionService.resolveBranchKey]
     * maps the master plan key to its branch plan (e.g. `SVC` + `featureX` → `SVC138`),
     * so the returned key can be polled directly via `/result/{key}/latest` — no
     * `/branch/{slug}/latest` slug-guessing.
     *
     * [configuredMasterKey] (`RepoConfig.bambooPlanKey`) is passed as `preferredMaster`
     * to disambiguate multi-module repos at T1 (Bitbucket commit-status walk picks the
     * status whose extracted plan key starts with the configured master). It also
     * serves as a last-resort fallback when all five tiers miss.
     */
    private suspend fun resolveBranchPlanAndMonitor(
        branchName: String,
        configuredMasterKey: String?,
    ) {
        val repo = getGitRepo()
        val repoRoot = repo?.root?.path?.let { java.nio.file.Paths.get(it) }
        val remoteUrl = repo?.remotes?.firstOrNull()?.firstUrl.orEmpty()
        val latestCommit = readActionLocalHead().orEmpty()

        activePlanKey = ""

        if (latestCommit.isNotBlank()) {
            checkDivergence(latestCommit)
        }

        val interval = settings.state.buildPollIntervalSeconds.toLong() * 1000

        // Multi-tier waterfall via the canonical service surface.
        val detection = if (remoteUrl.isNotBlank()) {
            bambooService.autoDetectPlan(repoRoot, remoteUrl, branchName, configuredMasterKey)
        } else {
            // No git remote — skip the waterfall and let the policy fall back to the
            // configured master if present, or surface the hint otherwise.
            com.workflow.orchestrator.core.services.ToolResult(
                data = "",
                summary = "no git remote configured",
                isError = true,
            )
        }

        when (val resolution = BuildPlanResolutionPolicy.resolve(detection, configuredMasterKey)) {
            is BuildPlanResolutionPolicy.Resolution.UseDetected -> {
                log.info("[Build:Dashboard] Resolved branch plan '${resolution.planKey}' via BambooService.autoDetectPlan (branch='$branchName', preferredMaster='${configuredMasterKey.orEmpty()}')")
                activePlanKey = resolution.planKey
                monitorService.switchBranch(resolution.planKey, branchName, interval)
                invokeLater {
                    hintLabel.isVisible = false
                    splitter.isVisible = true
                    headerLabel.text = "Plan: ${resolution.planKey} / $branchName"
                }
            }
            is BuildPlanResolutionPolicy.Resolution.UseConfigured -> {
                log.info("[Build:Dashboard] Waterfall miss — falling back to configured planKey '${resolution.planKey}' (autoDetect summary: ${detection.summary})")
                activePlanKey = resolution.planKey
                monitorService.switchBranch(resolution.planKey, branchName, interval)
                invokeLater { headerLabel.text = "Plan: ${resolution.planKey} / $branchName" }
            }
            is BuildPlanResolutionPolicy.Resolution.NoPlan -> {
                log.warn("[Build:Dashboard] No plan resolved for branch '$branchName' and no configured planKey")
                invokeLater { showHint(resolution.hintMessage) }
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

        // activeRepoConfig is now a derived getter — no init-time assignment. The first
        // read returns either the canonical service.state.focusPr.repoName (if a PR was
        // focused in another tab before this panel was opened) or the editor/primary
        // fallback. Either way, the value is correct without a writer race.
        log.info("[Build:Dashboard] Initial activeRepoConfig='${activeRepoConfig?.displayLabel}' (focusPr=${workflowContextService.state.value.focusPr?.repoName ?: "<none>"})")

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
        // Phase 5 T15: ReadOnly banner sits ABOVE prBar so it's the first thing the user
        // sees when the focused PR diverges from the active branch (spec §7.1).
        val topPanel2 = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(readOnlyBanner)
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
                    // On branch change, resolve the current repo and use its plan key.
                    // The focusPr will be updated by the PR tab's auto-select on branch
                    // change (via WorkflowContextService); our state.collect block above
                    // picks it up.
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

        // Observe state changes — rebuild stage list when (planKey, buildNumber) changes.
        //
        // Keying on buildNumber alone was a bug: if repo_1's latest build is #42 and the user
        // switches to repo_2 whose latest is also #42, the stage list would NOT rebuild — the
        // user saw repo_1's stages under repo_2's header, and clicking any stage loaded the
        // wrong plan's log. We now compare `"$planKey-$buildNumber"` so any plan change
        // forces a rebuild regardless of the build number collision.
        //
        // Null-state handling: switchBranch nullifies `_stateFlow.value` while the new poll
        // is in flight. Previously the collector did nothing on null, so stale stages from
        // the PREVIOUS plan lingered on screen — even worse, the user could click one and
        // load the OLD plan's log while the header showed the new plan. Null now clears the
        // stage list and log pane so there's never a mismatch between header and content.
        var lastDisplayedBuildKey: String? = null
        panelScope.launch {
            monitorService.stateFlow.collect { state ->
                invokeLater {
                    if (state == null) {
                        // Plan/branch switch in progress — clear stale content so the user
                        // can't click through to the wrong plan's logs.
                        lastDisplayedBuildKey = null
                        if (!viewingHistoricalBuild) {
                            stageListPanel.updateStages(emptyList())
                            stageDetailPanel.showEmpty()
                        }
                        return@invokeLater
                    }

                    loadingIcon.isVisible = false
                    hintLabel.isVisible = false
                    splitter.isVisible = true
                    latestBuildNumber = state.buildNumber

                    // Only update header/stages if not viewing a historical build
                    if (!viewingHistoricalBuild) {
                        headerLabel.text = "Plan: ${state.planKey} / ${state.branch}  #${state.buildNumber}"
                        statusLabel.text = "${state.overallStatus} — ${TimeFormatter.formatDurationMillis(state.stages.sumOf { it.durationMs ?: 0 })}"

                        val buildKey = "${state.planKey}-${state.buildNumber}"
                        // Rebuild when plan OR build number changes (plan change is the common
                        // case when switching repos; number change is the normal polling path).
                        if (buildKey != lastDisplayedBuildKey) {
                            val prevKey = lastDisplayedBuildKey
                            lastDisplayedBuildKey = buildKey
                            stageListPanel.updateStages(state.stages)
                            // Wipe the log pane too: if the user had a stage selected from the
                            // previous plan, its cached log view stays visible until a new
                            // stage is clicked otherwise.
                            if (prevKey != null && prevKey.substringBeforeLast("-") != state.planKey) {
                                stageDetailPanel.showEmpty()
                            }

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

        // Fallback polling — only when no PR is focused. The focusPr collector below
        // subscribes earlier and is the canonical driver when a PR exists; we wait a
        // short tick to let its first emission land, then fall back if state is empty.
        // Replaces the old unconditional startMonitoring() that raced the collector.
        panelScope.launch {
            delay(150)
            if (workflowContextService.state.value.focusPr == null) {
                invokeLater { startMonitoring() }
            }
        }

        // Repo selector listener — write through to the canonical service. The dropdown
        // is a USER input ("switch focus to this repo"), not a private tab-local field.
        // Look up an open PR matching the new repo; if found, call service.focusPr(...)
        // and let the cascade drive Build/Quality/PrBar uniformly. If no PR matches,
        // clear focus and fall back to the no-PR hint.
        repoSelector?.addActionListener {
            if (suppressRepoSelectorListener) return@addActionListener
            val selectedIndex = repoSelector.selectedIndex
            if (selectedIndex < 0 || selectedIndex >= allRepos.size) return@addActionListener

            val selectedRepo = allRepos[selectedIndex]
            val repoName = selectedRepo.displayLabel
            activePlanKey = ""
            log.info("[Build:Dashboard] repoSelector changed -> '$repoName' — writing through to service")

            // Look up an open PR matching the new repo via the canonical OpenPrLister
            // EP. If found, write through to the service's focusPr \u2014 that's the single
            // source of truth and drives Build/Quality/PrBar via the cascade. If no PR
            // matches, clear focus and let the no-PR fallback render.
            panelScope.launch {
                val matchedPr = OpenPrLister.getInstance()
                    ?.listOpenPrs(project)
                    ?.firstOrNull { it.repoName == repoName }
                if (matchedPr != null) {
                    workflowContextService.focusPr(matchedPr)
                    project.getService(EventBus::class.java).emit(WorkflowEvent.PrSelected(
                        prId = matchedPr.prId,
                        fromBranch = matchedPr.fromBranch,
                        toBranch = matchedPr.toBranch,
                        repoName = matchedPr.repoName,
                        bambooPlanKey = matchedPr.bambooPlanKey,
                        sonarProjectKey = matchedPr.sonarProjectKey,
                    ))
                } else {
                    workflowContextService.focusPr(null)
                    invokeLater {
                        showHint("No PR for $repoName \u2014 select one in the PR tab or push a branch to create one")
                        // PrBar updates itself via its own WorkflowContextService.state collector
                        // \u2014 no manual refresh needed.
                    }
                }
            }
        }

        // PrBar self-renders from WorkflowContextService.state — no explicit Bitbucket
        // fetch is needed at startup. The PR tab is responsible for hydrating focusPr
        // (and the editor seed at boot populates activeBranch); both flow into PrBar.

        // Item 10: subscribe to interactionMode so the action toolbar refreshes its
        // enabled state immediately when the local branch matches/diverges from the
        // focused PR. update() is BGT, but we trigger an explicit refresh so the user
        // doesn't have to mouse over the buttons to see the state change.
        panelScope.launch {
            workflowContextService.interactionModeFlow.collect {
                invokeLater {
                    @Suppress("DEPRECATION")
                    cachedActionToolbar?.updateActionsImmediately()
                }
            }
        }

        // Phase 5 T10: subscribe to the unified focusPr flow on WorkflowContextService.
        // PrBar header rendering AND the job-stages reader are both driven from this
        // single subscription via [onFocusPrChanged] → [loadBuildsForContext] +
        // [PrBar.showPrInfo], so they share one WorkflowContext snapshot per emit
        // (spec §4.4 single-merged-emission invariant; §9.2 coherence test).
        panelScope.launch {
            workflowContextService.state
                .map { it.focusPr }
                .distinctUntilChanged()
                .collect { pr ->
                    if (pr != null) invokeLater { onFocusPrChanged(pr) }
                    // pr == null: leave existing state alone — clearing focus is a Phase 5b
                    //             concern. The "no PR" UI is reached via PrBar.refreshPrs()'s
                    //             empty-list path, not by re-rendering on null focus.
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

    /**
     * Phase 5 T10: handle a focusPr change from [WorkflowContextService].
     *
     * Drives BOTH the PrBar header (via [PrBar.showPrInfo]) and the job-stages reader
     * (via [loadBuildsForContext] → [BuildMonitorService.switchBranch]) from a single
     * call site, so the two readers always share the same `WorkflowContext` snapshot.
     * That structural coherence is what spec §4.4 and §9.2 guarantee — see
     * `BuildDashboardPanelCoherenceTest`.
     */
    private fun onFocusPrChanged(pr: PrRef) {
        val matchedRepo = allRepos.firstOrNull { it.displayLabel == pr.repoName }
        if (matchedRepo == null && allRepos.isNotEmpty()) {
            log.warn("[Build:Dashboard] focusPr for unknown repoName='${pr.repoName}' — ignoring")
            return
        }

        log.info("[Build:Dashboard] onFocusPrChanged: matchedRepo='${matchedRepo?.displayLabel}' planKey='${pr.bambooPlanKey}'")

        // Sync repo selector UI to match. The listener is suppressed because the change
        // was driven by the canonical service state, not by the user.
        if (repoSelector != null && matchedRepo != null) {
            val repoIndex = allRepos.indexOf(matchedRepo)
            if (repoIndex >= 0) {
                suppressRepoSelectorListener = true
                repoSelector.selectedIndex = repoIndex
                suppressRepoSelectorListener = false
            }
        }

        // PrBar renders itself from WorkflowContextService.state — no manual hand-off.
        loadBuildsForContext(pr.fromBranch, pr.bambooPlanKey)
    }

    private fun loadBuildsForContext(branch: String, bambooPlanKey: String?) {
        hintLabel.isVisible = false
        splitter.isVisible = true

        loadingIcon.isVisible = true
        viewingHistoricalBuild = false
        historicalBuildBanner.isVisible = false
        historyListModel.clear()
        historyPanel.isVisible = false
        headerLabel.text = "Resolving Bamboo plan for $branch..."
        // Clear stale UI immediately — the monitor nullifies stateFlow inside switchBranch,
        // but we can't rely on the collector alone: there's a window between the user's
        // click here and the collector firing on EDT where they could still click a stage
        // from the previous plan. Clear synchronously here so stale stages are never
        // clickable during the switch.
        stageListPanel.updateStages(emptyList())
        stageDetailPanel.showEmpty()
        statusLabel.text = ""

        panelScope.launch {
            resolveBranchPlanAndMonitor(branch, configuredMasterKey = bambooPlanKey)
        }
    }

    private fun showHint(message: String) {
        hintLabel.text = message
        hintLabel.isVisible = true
        splitter.isVisible = false
        headerLabel.text = "Build"
        loadingIcon.isVisible = false
    }

    override fun dispose() {
        // BuildMonitorService is a project-level service whose lifecycle is owned by
        // the IntelliJ Platform; we only stop our polling subscription here.
        monitorService.stopPolling()
        panelScope.cancel()
    }

    private fun startMonitoring() {
        val planKey = currentPlanKey()
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
            panelScope.launch {
                val branch = getGitRepo()?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
                invokeLater { headerLabel.text = "Plan: $planKey / $branch" }
                monitorService.startPolling(planKey, branch, interval)
            }
        }
    }

    /**
     * Resolve the Git repository backing the Build tab's active repo. Prefers
     * [activeRepoConfig]'s `localVcsRootPath` so branch lookups and divergence
     * checks target the repo shown in the dropdown — NOT the editor's repo.
     * Falls back to the primary when nothing's selected yet (startup).
     */
    private fun getGitRepo(): git4idea.repo.GitRepository? {
        val activeRoot = activeRepoConfig?.localVcsRootPath
        if (activeRoot != null) {
            val repos = GitRepositoryManager.getInstance(project).repositories
            repos.find { it.root.path == activeRoot }?.let { return it }
        }
        return RepoContextResolver.getInstance(project).resolvePrimaryGitRepo()
    }

    private fun getCurrentBranch(): String? = getGitRepo()?.currentBranchName

    /** Local HEAD SHA of the active repo, or null when no repo is configured. */
    private fun readActionLocalHead(): String? = getGitRepo()?.currentRevision

    /**
     * Resolve the plan key for an action, preferring in order:
     *  1. [activePlanKey] — auto-detected from build-statuses or last-picked from PR context
     *  2. [activeRepoConfig]'s `bambooPlanKey` — the single source of truth for the
     *     Build tab's active repo (set by initial load, [onFocusPrChanged], and the
     *     [repoSelector] listener)
     *  3. scalar `settings.state.bambooPlanKey` — last-resort default for single-repo setups
     *
     * Every action handler (Refresh, Rerun, Trigger Build, Trigger Manual Stage, history)
     * must route through this helper.
     */
    private fun currentPlanKey(): String {
        if (activePlanKey.isNotBlank()) return activePlanKey
        val fromActive = activeRepoConfig?.bambooPlanKey
        return fromActive?.takeIf { it.isNotBlank() } ?: settings.state.bambooPlanKey.orEmpty()
    }

    /** Load build history for the current plan key */
    private fun loadBuildHistory() {
        val planKey = currentPlanKey()
        if (planKey.isBlank()) return

        panelScope.launch {
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

        panelScope.launch {
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
                val planKey = currentPlanKey()
                if (planKey.isBlank()) {
                    // No plan key — surface a hint. PR-driven detection is handled by the
                    // PR tab via WorkflowContextService.focusPr; this panel observes that.
                    headerLabel.text = "Detecting Bamboo plan..."
                    return
                }
                // Return to latest build if viewing historical
                if (viewingHistoricalBuild) {
                    returnToLatestBuild()
                }
                panelScope.launch {
                    val branch = getCurrentBranch() ?: getGitRepo()?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
                    monitorService.pollOnce(planKey, branch)
                }
                // Refresh the build history; PrBar self-updates via its state collector.
                loadBuildHistory()
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = isLiveMode()
                e.presentation.description = if (e.presentation.isEnabled)
                    "Force poll build status now"
                else readOnlyTooltip()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
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
                panelScope.launch {
                    val result = bambooService.stopBuild(resultKey)
                    invokeLater {
                        if (!result.isError) {
                            statusLabel.text = "Build $resultKey stopped"
                            panelScope.launch {
                                delay(2000)
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
                panelScope.launch {
                    val result = bambooService.cancelBuild(resultKey)
                    invokeLater {
                        if (!result.isError) {
                            statusLabel.text = "Build $resultKey cancelled"
                            panelScope.launch {
                                delay(2000)
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
                panelScope.launch {
                    val result = bambooService.rerunFailedJobs(planKey, buildNumber)
                    invokeLater {
                        if (!result.isError) {
                            statusLabel.text = "Rerun triggered for $planKey #$buildNumber"
                            // Poll immediately to get updated status
                            panelScope.launch {
                                delay(2000)
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
                val planKey = currentPlanKey()
                if (planKey.isBlank()) return
                openTriggerDialog(planKey)
            }
            override fun update(e: AnActionEvent) {
                val live = isLiveMode()
                e.presentation.isEnabled = live && currentPlanKey().isNotBlank()
                e.presentation.description = if (live)
                    "Trigger a new build with custom variables"
                else readOnlyTooltip()
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
        cachedActionToolbar = actionToolbar
        return actionToolbar.component
    }

    @Volatile
    private var cachedActionToolbar: com.intellij.openapi.actionSystem.ActionToolbar? = null

    private fun runLocalMavenBuild(goals: String) {
        localBuildRunning = true
        loadingIcon.isVisible = true
        statusLabel.text = "Running local: mvn $goals..."

        panelScope.launch {
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
        panelScope.launch {
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
        panelScope.launch {
            val logResult = bambooService.getBuildLog(resultKey)
            if (!logResult.isError) {
                invokeLater { stageDetailPanel.showLog(logResult.data, emptyList()) }
            } else {
                invokeLater { stageDetailPanel.showLog("Failed to load log: ${logResult.summary}", emptyList()) }
            }
        }
    }

    private fun triggerManualStage(stageName: String) {
        if (!isLiveMode()) {
            statusLabel.text = readOnlyTooltip()
            return
        }
        val planKey = currentPlanKey()
        ManualStageDialog(project, planKey, stageName, panelScope).show()
    }

    /**
     * Item 10 helpers — branch-match guard.
     *
     * `isLiveMode` returns true when [com.workflow.orchestrator.core.model.workflow.WorkflowContext.interactionMode]
     * is `Live` — i.e., either no PR is focused, or the focused PR's `fromBranch`
     * matches the local checkout's `activeBranch`. When false, the action buttons
     * (Refresh, Trigger Build, Trigger Manual Stage) are disabled and the status bar
     * messages with `readOnlyTooltip()` to explain why.
     */
    private fun isLiveMode(): Boolean =
        BuildDashboardActionGate.isLiveMode(workflowContextService.state.value)

    private fun readOnlyTooltip(): String =
        BuildDashboardActionGate.readOnlyTooltip(workflowContextService.state.value)

    private fun openTriggerDialog(planKey: String) {
        ManualStageDialog(project, planKey, scope = panelScope, triggerMode = TriggerMode.FULL_BUILD).show()
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
