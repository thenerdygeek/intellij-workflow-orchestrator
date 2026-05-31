package com.workflow.orchestrator.pullrequest.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
import com.workflow.orchestrator.core.bitbucket.CreatePrLauncher
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.ui.ComboBoxWidth
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.bindBoundedWidth
import com.workflow.orchestrator.core.model.bitbucket.PrState
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import com.workflow.orchestrator.pullrequest.service.PrListService
import kotlinx.coroutines.flow.filterIsInstance
import com.intellij.openapi.vcs.BranchChangeListener
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.*
import com.workflow.orchestrator.core.ui.TimeFormatter
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.ButtonGroup

/**
 * Main PR Dashboard panel — the top-level component placed in the PR tab.
 *
 * Layout:
 * - NORTH: toolbar (Create PR, Refresh) + filter toggle ("My PRs" | "Reviewing")
 * - CENTER: JBSplitter with PrListPanel (left) and PrDetailPanel (right)
 * - SOUTH: status bar
 */
class PrDashboardPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(PrDashboardPanel::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -- Sub-panels --
    private val listPanel = PrListPanel()
    private val detailPanel = PrDetailPanel(project)

    // -- Role filter toggle --
    private val myPrsToggle = JToggleButton("My PRs").apply { isSelected = true }
    private val reviewingToggle = JToggleButton("Reviewing")
    private val allToggle = JToggleButton("All")
    private val filterGroup = ButtonGroup()

    // -- State filter toggle --
    private val openToggle = JToggleButton("Open").apply { isSelected = true }
    private val mergedToggle = JToggleButton("Merged")
    private val declinedToggle = JToggleButton("Declined")
    private val stateGroup = ButtonGroup()

    // -- Status bar --
    private val statusLabel = JBLabel("Ready").apply {
        foreground = SECONDARY_TEXT
        font = font.deriveFont(JBUI.scale(11).toFloat())
        border = JBUI.Borders.empty(4, 8)
    }
    private val loadingIcon = JBLabel(AnimatedIcon.Default()).apply {
        border = JBUI.Borders.emptyRight(4)
        isVisible = false
    }

    // -- Repo filter dropdown (only visible when multiple repos configured) --
    private val repoFilterModel = DefaultComboBoxModel(arrayOf("All Repos"))
    private val repoFilter = JComboBox(repoFilterModel).apply {
        isVisible = false
        bindBoundedWidth(ComboBoxWidth.WIDE)
    }

    // -- State --
    private var currentMyPrs: List<BitbucketPrDetail> = emptyList()
    private var currentReviewingPrs: List<BitbucketPrDetail> = emptyList()
    private var currentAllRepoPrs: List<BitbucketPrDetail> = emptyList()
    private enum class PrFilter { MY, REVIEWING, ALL }
    private var activeFilter = PrFilter.MY
    private var activeRepoFilter: String? = null  // null = all repos
    private var lastUpdatedMillis: Long = 0

    // -- Auto-select state --
    /** True after auto-select has fired for the current branch session. Reset on branch change. */
    private var autoSelectDone = false
    /** Suppresses toggle action listeners during programmatic toggle switches. */
    private var suppressToggleListener = false
    private var autoSelectTimer: javax.swing.Timer? = null

    init {
        background = JBColor.PanelBackground
        isOpaque = true

        // Cascade dispose to PrDetailPanel — its dispose() cancels the load job,
        // its scope, and closes commentsTabPanel/aiReviewTabPanel (AutoCloseable).
        // C1's factory cascade ensures this panel's dispose() runs; this
        // registration mirrors the Quality/Automation pattern instead of the
        // ad-hoc detailPanel.dispose() call inside dispose().
        Disposer.register(this, detailPanel)

        setupLayout()
        setupRepoFilter()
        setupListeners()
        startDataCollection()
        setupVisibilityListener()
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    private fun setupLayout() {
        // -- Top: toolbar + filter --
        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 0, 8)
        }

        // Toolbar
        val actionGroup = DefaultActionGroup().apply {
            add(CreatePrAction())
            add(RefreshAction())
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("PrDashboard", actionGroup, true)
        toolbar.targetComponent = this
        topPanel.add(toolbar.component, BorderLayout.WEST)

        // Filter toggles — use wrapping FlowLayout so they wrap at narrow widths
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(8)
        }
        filterGroup.add(myPrsToggle)
        filterGroup.add(reviewingToggle)
        filterGroup.add(allToggle)
        myPrsToggle.minimumSize = myPrsToggle.preferredSize
        reviewingToggle.minimumSize = reviewingToggle.preferredSize
        allToggle.minimumSize = allToggle.preferredSize
        filterPanel.add(myPrsToggle)
        filterPanel.add(reviewingToggle)
        filterPanel.add(allToggle)
        filterPanel.add(repoFilter)
        topPanel.add(filterPanel, BorderLayout.CENTER)

        // State filter toggles (Open | Merged | Declined) — right side
        val statePanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), JBUI.scale(2))).apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(8)
        }
        stateGroup.add(openToggle)
        stateGroup.add(mergedToggle)
        stateGroup.add(declinedToggle)
        openToggle.minimumSize = openToggle.preferredSize
        mergedToggle.minimumSize = mergedToggle.preferredSize
        declinedToggle.minimumSize = declinedToggle.preferredSize
        statePanel.add(openToggle)
        statePanel.add(mergedToggle)
        statePanel.add(declinedToggle)
        topPanel.add(statePanel, BorderLayout.EAST)

        add(topPanel, BorderLayout.NORTH)

        // -- Center: splitter --
        val splitter = JBSplitter(false, 0.30f).apply {
            setSplitterProportionKey("workflow.pr.splitter")
            firstComponent = listPanel
            secondComponent = detailPanel
            isOpaque = false
        }
        add(splitter, BorderLayout.CENTER)

        // -- Bottom: status bar --
        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.customLine(BORDER_COLOR, 1, 0, 0, 0)
        }
        bottomPanel.add(loadingIcon)
        bottomPanel.add(statusLabel)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    // ---------------------------------------------------------------
    // Repo filter
    // ---------------------------------------------------------------

    private fun setupRepoFilter() {
        val repos = PluginSettings.getInstance(project).getRepos().filter { it.isConfigured }
        val multiRepo = repos.size > 1
        repoFilter.isVisible = multiRepo
        listPanel.showRepoBadge = multiRepo

        if (multiRepo) {
            repoFilterModel.removeAllElements()
            repoFilterModel.addElement("All Repos")
            repos.forEach { repoFilterModel.addElement(it.displayLabel) }
            repoFilter.selectedIndex = 0
        }

        repoFilter.addActionListener {
            val selected = repoFilter.selectedItem as? String
            activeRepoFilter = if (selected == "All Repos") null else selected
            refreshListView()
        }
    }

    // ---------------------------------------------------------------
    // Listeners
    // ---------------------------------------------------------------

    private fun setupListeners() {
        listPanel.onPrSelected = { prId ->
            // Look up full PR detail from cached data to avoid re-fetching
            val prDetail = currentMyPrs.find { it.id == prId }
                ?: currentReviewingPrs.find { it.id == prId }
                ?: currentAllRepoPrs.find { it.id == prId }
            if (prDetail != null) {
                // Resolve the PR's own repo coordinates from its repoName badge — critical
                // for Open-in-Browser and other per-PR actions that would otherwise fall
                // back to the project-default repo and open the wrong module's PR URL.
                val pluginSettings = PluginSettings.getInstance(project)
                val prRepoConfig = prDetail.repoName.takeIf { it.isNotBlank() }?.let { name ->
                    pluginSettings.getRepos().find { it.displayLabel == name }
                }
                detailPanel.showPrDetail(
                    prDetail,
                    projectKey = prRepoConfig?.bitbucketProjectKey,
                    repoSlug = prRepoConfig?.bitbucketRepoSlug
                )
                // Phase 5 T12: drive the canonical WorkflowContextService.focusPr cascade
                // (single-source-of-truth write — Build + Quality + ActiveTicketBar all read
                // from service.state). Re-emit the legacy PrSelected event for unmigrated
                // subscribers (SonarDataService, :agent ProjectContextTool — to be removed
                // in T16). The mirror's state-equality guard short-circuits the round-trip.
                val fromBranch = prDetail.fromRef?.displayId ?: ""
                val toBranch = prDetail.toRef?.displayId ?: ""
                val repoName = prDetail.repoName ?: ""
                if (fromBranch.isNotBlank()) {
                    // Resolve RepoConfig only (no scalar fallback). The service fills in
                    // bambooPlanKey/sonarProjectKey from RepoConfig via its own resolver,
                    // which is the canonical lookup — and which also handles the single-
                    // repo scalar-fallback case. Doing it here would cross-pollute keys
                    // between repos in multi-repo setups (the bug Phase 5 was supposed
                    // to fix but only half-fixed).
                    val repoConfig = prRepoConfig
                        ?: pluginSettings.getRepos().find { it.displayLabel == repoName }
                    val bambooPlanKey = repoConfig?.bambooPlanKey?.takeIf { it.isNotBlank() }
                    val sonarProjectKey = repoConfig?.sonarProjectKey?.takeIf { it.isNotBlank() }

                    scope.launch {
                        val ref = PrRef(
                            prId = prId,
                            fromBranch = fromBranch,
                            toBranch = toBranch,
                            repoName = repoName,
                            bambooPlanKey = bambooPlanKey,
                            sonarProjectKey = sonarProjectKey,
                        )
                        // Canonical write — cascades focusBuild + focusQualityScope.
                        WorkflowContextService.getInstance(project).focusPr(ref)

                        // Per spec §5.3: re-emit the legacy event for back-compat subscribers
                        // until T16 deletes them. The mirror no-ops on the round-trip via its
                        // state-equality guard (WorkflowEventMirror.kt:58).
                        project.getService(EventBus::class.java).emit(WorkflowEvent.PrSelected(
                            prId = prId,
                            fromBranch = fromBranch,
                            toBranch = toBranch,
                            repoName = repoName,
                            bambooPlanKey = bambooPlanKey,
                            sonarProjectKey = sonarProjectKey,
                        ))
                    }
                }
            } else {
                detailPanel.showPr(prId)
            }
        }

        // Back navigation from detail panel clears selection
        detailPanel.onBackClicked = {
            listPanel.prList.clearSelection()
            detailPanel.showEmpty()
        }

        // Empty-state "Refresh" action link wired to PrListService.refresh() (ARC-7 fix)
        listPanel.onRefreshClicked = {
            scope.launch { PrListService.getInstance(project).refresh() }
        }

        myPrsToggle.addActionListener {
            if (suppressToggleListener) return@addActionListener
            activeFilter = PrFilter.MY
            refreshListView()
        }
        reviewingToggle.addActionListener {
            if (suppressToggleListener) return@addActionListener
            activeFilter = PrFilter.REVIEWING
            refreshListView()
        }
        allToggle.addActionListener {
            if (suppressToggleListener) return@addActionListener
            activeFilter = PrFilter.ALL
            refreshListView()
        }

        // Branch change listener — reset auto-select so it fires again for the new branch
        project.messageBus.connect(this).subscribe(
            BranchChangeListener.VCS_BRANCH_CHANGED,
            object : BranchChangeListener {
                override fun branchWillChange(branchName: String) {}
                override fun branchHasChanged(branchName: String) {
                    autoSelectDone = false
                    scope.launch {
                        PrListService.getInstance(project).refresh()
                    }
                }
            }
        )

        // State filter listeners — change PR state and trigger service refresh
        openToggle.addActionListener {
            setLoading(true, "Loading open PRs...")
            PrListService.getInstance(project).setState(PrState.OPEN)
        }
        mergedToggle.addActionListener {
            setLoading(true, "Loading merged PRs...")
            PrListService.getInstance(project).setState(PrState.MERGED)
        }
        declinedToggle.addActionListener {
            setLoading(true, "Loading declined PRs...")
            PrListService.getInstance(project).setState(PrState.DECLINED)
        }
    }

    // ---------------------------------------------------------------
    // Visibility-based polling control
    // ---------------------------------------------------------------

    private fun setupVisibilityListener() {
        addAncestorListener(object : javax.swing.event.AncestorListener {
            override fun ancestorAdded(e: javax.swing.event.AncestorEvent) {
                // Panel became visible — notify poller (resets backoff, polls immediately)
                PrListService.getInstance(project).setVisible(true)
            }

            override fun ancestorRemoved(e: javax.swing.event.AncestorEvent) {
                // Panel hidden (tab switched) — slow down polling to save resources
                PrListService.getInstance(project).setVisible(false)
            }

            override fun ancestorMoved(e: javax.swing.event.AncestorEvent) {}
        })
    }

    // ---------------------------------------------------------------
    // Data collection
    // ---------------------------------------------------------------

    private fun startDataCollection() {
        val prListService = PrListService.getInstance(project)
        prListService.startPolling()

        // Collect myPrs flow
        scope.launch {
            prListService.myPrs.collect { prs ->
                currentMyPrs = prs
                lastUpdatedMillis = System.currentTimeMillis()
                invokeLater {
                    refreshListView()
                    updateStatusWithTimestamp()
                }
            }
        }

        // Collect reviewingPrs flow
        scope.launch {
            prListService.reviewingPrs.collect { prs ->
                currentReviewingPrs = prs
                invokeLater {
                    refreshListView()
                    scheduleAutoSelect()
                }
            }
        }

        // Collect allRepoPrs flow
        scope.launch {
            prListService.allRepoPrs.collect { prs ->
                currentAllRepoPrs = prs
                invokeLater {
                    refreshListView()
                    scheduleAutoSelect()
                }
            }
        }

        // Initial load
        setLoading(true, "Loading pull requests...")
        scope.launch {
            prListService.refresh()
        }

        // Subscribe to PullRequestCreated — refresh the list whenever a new PR is created
        // (whether from this panel's button or from the Build tab's PrBar).
        scope.launch {
            project.getService(EventBus::class.java).events
                .filterIsInstance<WorkflowEvent.PullRequestCreated>()
                .collect {
                    log.info("[PR:Dashboard] PullRequestCreated received — refreshing PR list")
                    invokeLater { setLoading(true, "PR created — refreshing…") }
                    prListService.refresh()
                }
        }
    }

    private fun refreshListView() {
        val repoName = activeRepoFilter
        val filteredMyPrs = if (repoName != null) currentMyPrs.filter { it.repoName == repoName } else currentMyPrs
        val filteredReviewingPrs = if (repoName != null) currentReviewingPrs.filter { it.repoName == repoName } else currentReviewingPrs
        val filteredAllRepoPrs = if (repoName != null) currentAllRepoPrs.filter { it.repoName == repoName } else currentAllRepoPrs

        val myItems = filteredMyPrs.map { it.toPrListItem() }
        val reviewingItems = filteredReviewingPrs.map { it.toPrListItem() }

        when (activeFilter) {
            PrFilter.MY -> listPanel.updatePrs(myItems, emptyList())
            PrFilter.REVIEWING -> listPanel.updatePrs(emptyList(), reviewingItems)
            PrFilter.ALL -> listPanel.updateAllRepoPrs(filteredAllRepoPrs.map { it.toPrListItem() })
        }
    }

    private fun setLoading(loading: Boolean, message: String) {
        loadingIcon.isVisible = loading
        statusLabel.text = message
    }

    private fun updateStatusWithTimestamp() {
        val totalPrs = when (activeFilter) {
            PrFilter.MY -> currentMyPrs.size
            PrFilter.REVIEWING -> currentReviewingPrs.size
            PrFilter.ALL -> currentAllRepoPrs.size
        }
        val timeStr = if (lastUpdatedMillis > 0) " \u2022 Updated ${TimeFormatter.relative(lastUpdatedMillis)}" else ""
        setLoading(false, "$totalPrs PRs loaded$timeStr")
    }

    // ---------------------------------------------------------------
    // DTO conversion
    // ---------------------------------------------------------------

    private fun BitbucketPrDetail.toPrListItem(): PrListItem {
        return PrListItem(
            id = id,
            title = title,
            authorName = author?.user?.displayName ?: "",
            status = state,
            reviewerCount = reviewers.size,
            updatedDate = updatedDate,
            fromBranch = fromRef?.displayId ?: "",
            toBranch = toRef?.displayId ?: "",
            repoName = repoName
        )
    }

    // ---------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------

    private inner class RefreshAction : AnAction(
        "Refresh PRs",
        "Reload pull requests from Bitbucket",
        AllIcons.Actions.Refresh
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            setLoading(true, "Refreshing...")
            scope.launch {
                PrListService.getInstance(project).refresh()
                lastUpdatedMillis = System.currentTimeMillis()
                invokeLater {
                    updateStatusWithTimestamp()
                }
            }
        }
    }

    private inner class CreatePrAction : AnAction(
        "Create PR",
        "Create a new pull request from the current branch",
        AllIcons.General.Add
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val launcher = CreatePrLauncher.getInstance()
            if (launcher == null) {
                log.warn("[PR:Dashboard] CreatePrLauncher EP not registered — cannot open dialog")
                com.intellij.openapi.ui.Messages.showWarningDialog(
                    project,
                    "Create PR feature is unavailable — plugin may be misconfigured.",
                    "Create Pull Request"
                )
                return
            }
            launcher.launch(project, scope)
        }
    }

    // ---------------------------------------------------------------
    // Auto-select: pick the latest PR matching each repo's current branch
    // ---------------------------------------------------------------

    /**
     * Debounced trigger — waits for all three flows to settle before attempting auto-select.
     */
    private fun scheduleAutoSelect() {
        if (autoSelectDone) return
        autoSelectTimer?.stop()
        autoSelectTimer = javax.swing.Timer(150) { tryAutoSelect() }.apply {
            isRepeats = false
            start()
        }
    }

    /**
     * Find the latest PR whose source branch matches a repo's current git branch.
     * Priority: primary repo first, then most recently updated.
     * If the match is in myPrs → switch to "My PRs" toggle.
     * If only in allRepoPrs → switch to "All" toggle.
     * Fires once per branch session.
     */
    private fun tryAutoSelect() {
        if (autoSelectDone) return

        val bestPr = findBestPrForAutoSelect() ?: return
        autoSelectDone = true

        // Determine which toggle should be active for this PR
        val isMyPr = currentMyPrs.any { it.id == bestPr.id }
        val isReviewingPr = currentReviewingPrs.any { it.id == bestPr.id }
        val targetFilter = when {
            isMyPr -> PrFilter.MY
            isReviewingPr -> PrFilter.REVIEWING
            else -> PrFilter.ALL
        }

        // Switch toggle if needed (suppress listeners to avoid recursion)
        if (activeFilter != targetFilter) {
            suppressToggleListener = true
            activeFilter = targetFilter
            when (targetFilter) {
                PrFilter.MY -> myPrsToggle.isSelected = true
                PrFilter.REVIEWING -> reviewingToggle.isSelected = true
                PrFilter.ALL -> allToggle.isSelected = true
            }
            suppressToggleListener = false
            refreshListView()
        }

        // Select the PR in the list and trigger detail view
        listPanel.selectPrById(bestPr.id)
        log.info("[PR:AutoSelect] Auto-selected PR #${bestPr.id} '${bestPr.title}' (repo=${bestPr.repoName}, filter=$targetFilter)")
    }

    /**
     * Finds the best PR to auto-select: latest PR where a repo's current git branch
     * is the source branch. Primary repo gets priority.
     */
    private fun findBestPrForAutoSelect(): BitbucketPrDetail? {
        val settings = PluginSettings.getInstance(project)
        val repoConfigs = settings.getRepos().ifEmpty { return null }
        val gitRepos = GitRepositoryManager.getInstance(project).repositories

        // Map each repo's display label to its current git branch
        val branchByRepo = repoConfigs.associate { config ->
            val gitRepo = gitRepos.find { it.root.path == config.localVcsRootPath }
            config.displayLabel to (gitRepo?.currentBranchName ?: "")
        }

        // All unique PRs across all sources
        val allPrs = (currentMyPrs + currentReviewingPrs + currentAllRepoPrs)
            .distinctBy { it.id }

        // Find PRs whose source branch matches the repo's current branch
        val matchingPrs = allPrs.filter { pr ->
            val fromBranch = pr.fromRef?.displayId ?: ""
            val repoBranch = branchByRepo[pr.repoName] ?: ""
            repoBranch.isNotBlank() && fromBranch == repoBranch
        }

        if (matchingPrs.isEmpty()) return null

        // Sort: primary repo first, then most recently updated
        val primaryLabel = settings.getPrimaryRepo()?.displayLabel
        return matchingPrs.sortedWith(
            compareByDescending<BitbucketPrDetail> { it.repoName == primaryLabel }
                .thenByDescending { it.updatedDate }
        ).first()
    }

    // ---------------------------------------------------------------
    // Dispose
    // ---------------------------------------------------------------

    override fun dispose() {
        autoSelectTimer?.stop()
        // detailPanel disposed via Disposer.register(this, detailPanel) in init.
        scope.cancel()
    }

    companion object {
        private val SECONDARY_TEXT = StatusColors.SECONDARY_TEXT
        private val BORDER_COLOR = StatusColors.BORDER
    }
}
