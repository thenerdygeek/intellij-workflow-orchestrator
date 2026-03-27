package com.workflow.orchestrator.bamboo.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketPrResponse
import com.workflow.orchestrator.core.bitbucket.PrService
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.util.DefaultBranchResolver
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * PR bar for the Build tab — thin adaptive strip that shows PR status.
 *
 * Three states:
 * 1. No PR: blue banner with "Create PR" button (expandable inline form)
 * 2. Single PR: green info bar with PR title, target branch, status
 * 3. Multiple PRs: green bar with dropdown selector
 *
 * PR detection uses BitbucketBranchClient.getPullRequestsForBranch() from :core.
 */
class PrBar(
    private val project: Project,
    private val scope: CoroutineScope,
    private val onPrSelected: (branchName: String) -> Unit
) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(PrBar::class.java)
    private val settings = PluginSettings.getInstance(project)
    private val contentPanel = JPanel(BorderLayout())

    // State
    private var currentPrs: List<BitbucketPrResponse> = emptyList()
    private var selectedPr: BitbucketPrResponse? = null

    fun getSelectedPr(): BitbucketPrResponse? = selectedPr
    private var formExpanded = false

    // --- No PR state components ---
    private val noPrPanel = JPanel(BorderLayout())
    private val createButton = JButton("Create PR")

    // --- Create form components ---
    private val formPanel = JPanel(BorderLayout())
    private val titleField = JBTextField()
    private val descriptionArea = JBTextArea(4, 40).apply { lineWrap = true; wrapStyleWord = true }
    private val submitButton = JButton("Create PR")
    private val regenerateButton = JButton("Regenerate Description")
    private val cancelButton = JButton("✕ Cancel").apply { isBorderPainted = false }
    private val formResultLabel = JBLabel("")

    // --- Single PR state components ---
    private val singlePrPanel = JPanel(BorderLayout())
    private val prInfoLabel = JBLabel("")
    private val openInBrowserLink = JBLabel("Open in browser ↗").apply {
        foreground = StatusColors.LINK
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }

    // --- Multiple PRs state components ---
    private val multiPrPanel = JPanel(BorderLayout())
    private val prDropdown = JComboBox<PrComboItem>()
    private val multiOpenLink = JBLabel("Open in browser ↗").apply {
        foreground = StatusColors.LINK
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }

    companion object {
        private val BLUE_BG = StatusColors.INFO_BG
        private val GREEN_BG = StatusColors.SUCCESS_BG
        private val BLUE_BORDER = StatusColors.LINK
        private val GREEN_BORDER = StatusColors.SUCCESS
    }

    init {
        buildNoPrPanel()
        buildFormPanel()
        buildSinglePrPanel()
        buildMultiPrPanel()

        add(contentPanel, BorderLayout.CENTER)

        // Default state
        showPanel(noPrPanel)
    }

    private fun buildNoPrPanel() {
        noPrPanel.background = BLUE_BG
        noPrPanel.border = JBUI.Borders.customLine(BLUE_BORDER, 0, 0, 1, 0)
        noPrPanel.preferredSize = java.awt.Dimension(0, JBUI.scale(36))
        noPrPanel.maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(36))

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            isOpaque = false
            add(JBLabel(AllIcons.Vcs.Branch))
            add(JBLabel("No pull request for this branch"))
            add(JBLabel("Create a PR to trigger Bamboo builds").apply {
                foreground = StatusColors.SECONDARY_TEXT
                font = font.deriveFont(font.size2D - 1f)
            })
        }

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), JBUI.scale(4))).apply {
            isOpaque = false
            add(createButton)
        }

        noPrPanel.add(left, BorderLayout.CENTER)
        noPrPanel.add(right, BorderLayout.EAST)

        createButton.addActionListener { openCreatePrDialog() }
    }

    private fun buildFormPanel() {
        formPanel.background = BLUE_BG
        formPanel.border = JBUI.Borders.customLine(BLUE_BORDER, 0, 0, 1, 0)

        val inner = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12)
        }

        // Header
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("Create Pull Request", AllIcons.Vcs.Branch, JBLabel.LEFT).apply { font = font.deriveFont(font.style or java.awt.Font.BOLD) }, BorderLayout.WEST)
            add(cancelButton, BorderLayout.EAST)
        }

        // Fields
        val fields = JPanel(GridBagLayout()).apply { isOpaque = false }
        val gbc = GridBagConstraints().apply { fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insets(2, 0) }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        fields.add(JBLabel("Target:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        val targetLabel = JBLabel("develop")
        scope.launch {
            val repo = com.intellij.openapi.application.ReadAction.compute<git4idea.repo.GitRepository?, Throwable> { getGitRepo() }
            val target = repo?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
            invokeLater { targetLabel.text = target }
        }
        fields.add(targetLabel, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        fields.add(JBLabel("Title:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        fields.add(titleField, gbc)

        // Buttons
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(submitButton)
            add(regenerateButton)
            add(formResultLabel)
        }

        inner.add(header, BorderLayout.NORTH)
        val center = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(fields, BorderLayout.NORTH)
            add(JBScrollPane(descriptionArea).apply { preferredSize = java.awt.Dimension(0, JBUI.scale(70)) }, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        inner.add(center, BorderLayout.CENTER)
        formPanel.add(inner, BorderLayout.CENTER)

        cancelButton.addActionListener { showPanel(noPrPanel) }
        submitButton.addActionListener { onSubmitPr() }
        regenerateButton.addActionListener { onRegenerateDescription() }
    }

    private fun buildSinglePrPanel() {
        singlePrPanel.background = GREEN_BG
        singlePrPanel.border = JBUI.Borders.customLine(GREEN_BORDER, 0, 0, 1, 0)
        singlePrPanel.preferredSize = java.awt.Dimension(0, JBUI.scale(36))
        singlePrPanel.maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(36))

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            isOpaque = false
            add(JBLabel("✓").apply { foreground = StatusColors.SUCCESS })
            add(prInfoLabel)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), JBUI.scale(4))).apply {
            isOpaque = false
            add(openInBrowserLink)
        }

        singlePrPanel.add(left, BorderLayout.CENTER)
        singlePrPanel.add(right, BorderLayout.EAST)

        openInBrowserLink.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                selectedPr?.links?.self?.firstOrNull()?.href?.let { BrowserUtil.browse(it) }
            }
        })
    }

    private fun buildMultiPrPanel() {
        multiPrPanel.background = GREEN_BG
        multiPrPanel.border = JBUI.Borders.customLine(GREEN_BORDER, 0, 0, 1, 0)
        multiPrPanel.preferredSize = java.awt.Dimension(0, JBUI.scale(36))
        multiPrPanel.maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(36))

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            isOpaque = false
            add(JBLabel("✓").apply { foreground = StatusColors.SUCCESS })
            add(prDropdown)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), JBUI.scale(4))).apply {
            isOpaque = false
            add(multiOpenLink)
        }

        multiPrPanel.add(left, BorderLayout.CENTER)
        multiPrPanel.add(right, BorderLayout.EAST)

        prDropdown.addActionListener {
            val item = prDropdown.selectedItem as? PrComboItem ?: return@addActionListener
            selectedPr = item.pr
            onPrSelected(item.pr.fromRef?.displayId ?: "")
        }

        multiOpenLink.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                selectedPr?.links?.self?.firstOrNull()?.href?.let { BrowserUtil.browse(it) }
            }
        })
    }

    // --- State management ---

    private fun showPanel(panel: JPanel) {
        contentPanel.removeAll()
        contentPanel.add(panel, BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()
        // Force parent to recalculate layout since our preferred size changed
        this.revalidate()
        this.repaint()
    }

    /**
     * Opens the full Create PR dialog. Prefetches all data in background,
     * then shows the dialog on EDT.
     */
    private fun openCreatePrDialog() {
        val ticketId = settings.state.activeTicketId.orEmpty()
        val prService = PrService.getInstance(project)
        val defaultReviewers = prService.buildDefaultReviewers().map { it.user.name }

        // Fetch data in background, show dialog when ready
        scope.launch {
            val currentBranch = com.intellij.openapi.application.ReadAction.compute<_, Throwable> { resolveCurrentBranch() } ?: return@launch
            val bitbucketUrl = settings.connections.bitbucketUrl.orEmpty().trimEnd('/')
            val projectKey = settings.state.bitbucketProjectKey.orEmpty()
            val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()

            val remoteBranches = if (bitbucketUrl.isNotBlank()) {
                val client = BitbucketBranchClient(
                    baseUrl = bitbucketUrl,
                    tokenProvider = { CredentialStore().getToken(ServiceType.BITBUCKET) }
                )
                when (val r = client.getBranches(projectKey, repoSlug)) {
                    is ApiResult.Success -> r.data.map { it.displayId }
                    is ApiResult.Error -> emptyList()
                }
            } else emptyList()

            val jiraProvider = com.workflow.orchestrator.core.workflow.JiraTicketProvider.getInstance()
            val ticketDetails = if (ticketId.isNotBlank()) {
                jiraProvider?.getTicketDetails(ticketId)
            } else null

            val transitions = if (ticketId.isNotBlank()) {
                jiraProvider?.getAvailableTransitions(ticketId) ?: emptyList()
            } else emptyList()

            val defaultTitle = com.workflow.orchestrator.bamboo.service.PrDescriptionGenerator
                .generateTitle(project, ticketDetails, currentBranch)

            invokeLater {
                val dialog = CreatePrDialog(
                    project = project,
                    scope = scope,
                    sourceBranch = currentBranch,
                    remoteBranches = remoteBranches,
                    ticketDetails = ticketDetails,
                    transitions = transitions,
                    defaultTitle = defaultTitle,
                    defaultReviewers = defaultReviewers
                )

                if (dialog.showAndGet()) {
                    // Dialog handles PR creation internally
                    // Refresh PR bar to show the new PR
                    refreshPrs()
                }
            }
        }
    }

    // --- Actions ---

    fun refreshPrs() {
        val bitbucketUrl = settings.connections.bitbucketUrl.orEmpty().trimEnd('/')
        val projectKey = settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()

        log.info("[Build:PrBar] refreshPrs: url='$bitbucketUrl' project='$projectKey' repo='$repoSlug'")
        if (bitbucketUrl.isBlank() || projectKey.isBlank() || repoSlug.isBlank()) {
            log.warn("[Build:PrBar] Bitbucket not configured, hiding PrBar")
            isVisible = false
            return
        }
        isVisible = true

        val credentialStore = CredentialStore()
        val client = BitbucketBranchClient(
            baseUrl = bitbucketUrl,
            tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
        )

        scope.launch {
            // Resolve branch off-EDT to avoid synchronous VCS repository update on EDT
            val currentBranch = com.intellij.openapi.application.ReadAction.compute<_, Throwable> { resolveCurrentBranch() }
            if (currentBranch == null) {
                log.warn("[Build:PrBar] No Git branch detected")
                return@launch
            }
            log.info("[Build:PrBar] Fetching PRs for branch '$currentBranch'")

            val result = client.getPullRequestsForBranch(projectKey, repoSlug, currentBranch)
            invokeLater {
                when (result) {
                    is ApiResult.Success -> setPrs(result.data)
                    is ApiResult.Error -> {
                        log.warn("[Build:PrBar] Failed to fetch PRs: ${result.message}")
                        setPrs(emptyList())
                    }
                }
            }
        }
    }

    private fun setPrs(prs: List<BitbucketPrResponse>) {
        currentPrs = prs
        log.info("[Build:PrBar] setPrs called with ${prs.size} PRs")
        for (pr in prs) {
            log.info("[Build:PrBar]   PR #${pr.id}: '${pr.title}' fromRef=${pr.fromRef?.displayId} toRef=${pr.toRef?.displayId}")
        }
        when {
            prs.isEmpty() -> {
                selectedPr = null
                if (formExpanded) showPanel(formPanel) else showPanel(noPrPanel)
            }
            prs.size == 1 -> {
                selectedPr = prs[0]
                formExpanded = false
                updateSinglePrInfo(prs[0])
                showPanel(singlePrPanel)
                val branchName = prs[0].fromRef?.displayId ?: ""
                log.info("[Build:PrBar] Single PR selected, branch='$branchName'")
                onPrSelected(branchName)
            }
            else -> {
                formExpanded = false
                prDropdown.removeAllItems()
                prs.forEach { prDropdown.addItem(PrComboItem(it)) }
                selectedPr = prs[0]
                showPanel(multiPrPanel)
                val branchName = prs[0].fromRef?.displayId ?: ""
                log.info("[Build:PrBar] Multi PR, first selected, branch='$branchName'")
                onPrSelected(branchName)
            }
        }
    }

    private fun updateSinglePrInfo(pr: BitbucketPrResponse) {
        val target = pr.toRef?.displayId ?: "?"
        prInfoLabel.text = "<html><b>PR #${pr.id}</b> &nbsp; ${escapeHtml(pr.title)} &nbsp; <font color='${StatusColors.htmlColor(StatusColors.SECONDARY_TEXT)}'>→ $target</font> &nbsp; <font color='${statusColor(pr.state)}'>${pr.state}</font></html>"
    }

    private fun statusColor(state: String): String = when (state.uppercase()) {
        "OPEN" -> StatusColors.htmlColor(StatusColors.SUCCESS)
        "MERGED" -> StatusColors.htmlColor(StatusColors.MERGED)
        "DECLINED" -> StatusColors.htmlColor(StatusColors.ERROR)
        else -> StatusColors.htmlColor(StatusColors.INFO)
    }

    private fun getGitRepo(): git4idea.repo.GitRepository? {
        val resolver = RepoContextResolver.getInstance(project)
        val repoConfig = resolver.resolveFromCurrentEditor() ?: resolver.getPrimary()
        val repos = GitRepositoryManager.getInstance(project).repositories
        return repos.find { it.root.path == repoConfig?.localVcsRootPath } ?: repos.firstOrNull()
    }

    private fun resolveCurrentBranch(): String? = getGitRepo()?.currentBranchName

    private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun onSubmitPr() {
        val title = titleField.text.orEmpty().trim()
        if (title.isBlank()) {
            formResultLabel.text = "Title cannot be empty"
            formResultLabel.foreground = JBColor.RED
            return
        }

        val bitbucketUrl = settings.connections.bitbucketUrl.orEmpty().trimEnd('/')
        val projectKey = settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()
        submitButton.isEnabled = false
        regenerateButton.isEnabled = false
        formResultLabel.text = "Creating PR..."
        formResultLabel.foreground = JBColor.foreground()

        val credentialStore = CredentialStore()
        val prService = PrService.getInstance(project)
        val reviewers = prService.buildDefaultReviewers()

        scope.launch {
            val fromBranch = com.intellij.openapi.application.ReadAction.compute<String?, Throwable> { resolveCurrentBranch() } ?: ""
            val toRepo = com.intellij.openapi.application.ReadAction.compute<git4idea.repo.GitRepository?, Throwable> { getGitRepo() }
            val toBranch = toRepo?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
            val client = BitbucketBranchClient(
                baseUrl = bitbucketUrl,
                tokenProvider = { credentialStore.getToken(ServiceType.BITBUCKET) }
            )
            val result = client.createPullRequest(
                projectKey, repoSlug, title, descriptionArea.text.orEmpty(),
                fromBranch, toBranch, reviewers
            )
            invokeLater {
                submitButton.isEnabled = true
                regenerateButton.isEnabled = true
                when (result) {
                    is ApiResult.Success -> {
                        val prUrl = result.data.links.self.firstOrNull()?.href ?: ""
                        log.info("[Build:PrBar] PR #${result.data.id} created: $prUrl")
                        formExpanded = false

                        val ticketId = settings.state.activeTicketId.orEmpty()
                        scope.launch {
                            project.getService(EventBus::class.java)
                                .emit(WorkflowEvent.PullRequestCreated(prUrl, result.data.id, ticketId))
                        }

                        // Refresh to show the new PR
                        refreshPrs()
                    }
                    is ApiResult.Error -> {
                        formResultLabel.text = result.message
                        formResultLabel.foreground = JBColor.RED
                    }
                }
            }
        }
    }

    private fun onRegenerateDescription() {
        val ticketId = settings.state.activeTicketId.orEmpty()
        val ticketSummary = settings.state.activeTicketSummary.orEmpty()
        val prService = PrService.getInstance(project)

        regenerateButton.isEnabled = false
        formResultLabel.text = "Generating..."

        scope.launch {
            val branch = com.intellij.openapi.application.ReadAction.compute<_, Throwable> { resolveCurrentBranch() } ?: ""
            val changedFiles = withContext(Dispatchers.IO) {
                try {
                    val changes = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project).allChanges
                    changes.mapNotNull { it.virtualFile }
                } catch (_: Exception) { emptyList() }
            }

            val description = prService.buildEnrichedDescription(
                ticketId.ifBlank { "" }, ticketSummary.ifBlank { branch }, branch, changedFiles
            )

            invokeLater {
                descriptionArea.text = description
                regenerateButton.isEnabled = true
                formResultLabel.text = ""
            }
        }
    }
}

/** ComboBox item wrapper to show PR info in the dropdown. */
private data class PrComboItem(val pr: BitbucketPrResponse) {
    override fun toString(): String {
        val target = pr.toRef?.displayId ?: "?"
        return "PR #${pr.id}  ${pr.title}  → $target"
    }
}
