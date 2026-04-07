package com.workflow.orchestrator.bamboo.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.service.MarkdownToHtml
import com.workflow.orchestrator.bamboo.service.PrDescriptionGenerator
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketUser
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.intellij.openapi.application.EDT
import com.workflow.orchestrator.core.workflow.JiraTicketProvider
import com.workflow.orchestrator.core.workflow.TicketDetails
import com.workflow.orchestrator.core.workflow.TicketTransition
import com.workflow.orchestrator.core.util.DefaultBranchResolver
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.*
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent

/**
 * Modal dialog for creating a pull request on Bitbucket.
 * Features: searchable target branch, AI-generated markdown description with Edit/Preview,
 * reviewer autocomplete from Bitbucket users API, and optional Jira ticket transition.
 */
class CreatePrDialog(
    private val project: Project,
    private val scope: CoroutineScope,
    private val sourceBranch: String,
    private val remoteBranches: List<String>,
    private val ticketDetails: TicketDetails?,
    private val transitions: List<TicketTransition>,
    private val defaultTitle: String,
    private val defaultReviewers: List<String>
) : DialogWrapper(project, true) {

    private val log = Logger.getInstance(CreatePrDialog::class.java)
    private val settings = PluginSettings.getInstance(project)

    // Target branch
    private val targetField = JBTextField().apply {
        text = "develop" // async-resolved below in init
    }
    private val branchPopup = JPopupMenu()

    // Title
    private val titleField = JBTextField(40).apply { text = defaultTitle }

    // Description
    private val descriptionArea = JBTextArea(10, 50).apply {
        lineWrap = true; wrapStyleWord = true
        font = JBUI.Fonts.create(com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.editorFontName, 12)
    }
    private val previewPane = JEditorPane().apply {
        isEditable = false
        contentType = "text/html"
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                BrowserUtil.browse(e.url)
            }
        }
    }
    private val descCardLayout = CardLayout()
    private val descCardPanel = JPanel(descCardLayout)
    private val editTabButton = JButton("Edit")
    private val previewTabButton = JButton("Preview")
    private val regenerateButton = JButton("⟳ Regenerate")
    private val descLoadingLabel = JBLabel("Generating description...").apply {
        icon = AnimatedIcon.Default()
    }

    // Reviewers
    private val reviewerChipsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2)))
    private val reviewerInput = JBTextField(15)
    private val reviewerPopup = JPopupMenu()
    private val selectedReviewers = mutableListOf<String>()
    private var userSearchJob: Job? = null

    // Transition
    private val transitionCheckbox = JCheckBox("Transition ticket to").apply { isSelected = true }
    private val transitionCombo = JComboBox<TransitionItem>()

    // Result
    private val resultLabel = JBLabel("")

    init {
        title = "Create Pull Request" + (ticketDetails?.let { " — ${it.key}" } ?: "")
        setOKButtonText("Create PR")
        init()

        // Pre-fill reviewers
        defaultReviewers.forEach { addReviewerChip(it) }

        // Populate transitions
        if (transitions.isNotEmpty() && ticketDetails != null) {
            transitions.forEach { transitionCombo.addItem(TransitionItem(it)) }
            // Default to "In Review" if available
            val reviewIdx = transitions.indexOfFirst {
                it.name.contains("Review", ignoreCase = true) ||
                it.name.contains("In Review", ignoreCase = true)
            }
            if (reviewIdx >= 0) transitionCombo.selectedIndex = reviewIdx
        } else {
            transitionCheckbox.isVisible = false
            transitionCombo.isVisible = false
        }

        // Wire target branch search
        targetField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterBranches()
            override fun removeUpdate(e: DocumentEvent) = filterBranches()
            override fun changedUpdate(e: DocumentEvent) = filterBranches()
        })

        // Wire reviewer search
        reviewerInput.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = searchUsers()
            override fun removeUpdate(e: DocumentEvent) = searchUsers()
            override fun changedUpdate(e: DocumentEvent) = searchUsers()
        })

        // Wire description tabs
        editTabButton.addActionListener { showDescriptionTab("edit") }
        previewTabButton.addActionListener { showDescriptionTab("preview") }
        regenerateButton.addActionListener { regenerateDescription() }

        transitionCheckbox.addActionListener {
            transitionCombo.isEnabled = transitionCheckbox.isSelected
        }

        // Resolve default target branch asynchronously
        scope.launch {
            val repos = GitRepositoryManager.getInstance(project).repositories
            val repo = repos.firstOrNull()
            val resolvedTarget = repo?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
            invokeLater { if (targetField.text == "develop") targetField.text = resolvedTarget }
        }

        // Start description generation
        showDescriptionLoading()
        generateDescription()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.preferredSize = Dimension(JBUI.scale(600), JBUI.scale(550))
        panel.border = JBUI.Borders.empty(12)

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // Source & Target
        val branchPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply { fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insets(3, 0) }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        branchPanel.add(JBLabel("Source:").apply { border = JBUI.Borders.emptyRight(8) }, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        branchPanel.add(JBLabel(sourceBranch).apply { foreground = StatusColors.LINK }, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        branchPanel.add(JBLabel("Target:").apply { border = JBUI.Borders.emptyRight(8) }, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        branchPanel.add(targetField, gbc)

        content.add(branchPanel)
        content.add(Box.createVerticalStrut(JBUI.scale(8)))
        content.add(JSeparator())
        content.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Title
        content.add(JBLabel("Title").apply { foreground = StatusColors.SECONDARY_TEXT })
        content.add(Box.createVerticalStrut(JBUI.scale(4)))
        content.add(titleField)
        content.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Description with Edit/Preview tabs
        val descHeader = JPanel(BorderLayout()).apply {
            add(JBLabel("Description").apply { foreground = StatusColors.SECONDARY_TEXT }, BorderLayout.WEST)
            val tabButtons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                add(editTabButton); add(previewTabButton); add(regenerateButton)
            }
            add(tabButtons, BorderLayout.EAST)
        }
        content.add(descHeader)
        content.add(Box.createVerticalStrut(JBUI.scale(4)))

        descCardPanel.add(JBScrollPane(descriptionArea), "edit")
        descCardPanel.add(JBScrollPane(previewPane), "preview")
        descCardPanel.add(createLoadingPanel(), "loading")
        descCardPanel.preferredSize = Dimension(0, JBUI.scale(200))
        content.add(descCardPanel)
        content.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Reviewers
        content.add(JBLabel("Reviewers").apply { foreground = StatusColors.SECONDARY_TEXT })
        content.add(Box.createVerticalStrut(JBUI.scale(4)))
        val reviewerPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(4)
            )
            add(reviewerChipsPanel, BorderLayout.CENTER)
        }
        reviewerChipsPanel.add(reviewerInput)
        content.add(reviewerPanel)
        content.add(JBLabel("Type to search Bitbucket users").apply {
            foreground = StatusColors.INFO; font = font.deriveFont(font.size2D - 2f)
        })
        content.add(Box.createVerticalStrut(JBUI.scale(8)))
        content.add(JSeparator())
        content.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Transition
        val transPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        transPanel.add(transitionCheckbox)
        transPanel.add(transitionCombo)
        content.add(transPanel)
        content.add(Box.createVerticalStrut(JBUI.scale(4)))
        content.add(resultLabel)

        panel.add(JBScrollPane(content).apply {
            border = null
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)

        return panel
    }

    private fun createLoadingPanel(): JPanel = JPanel(BorderLayout()).apply {
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(Box.createVerticalGlue())
            add(descLoadingLabel.apply { alignmentX = Component.CENTER_ALIGNMENT })
            add(Box.createVerticalGlue())
        }, BorderLayout.CENTER)
    }

    // --- Branch search ---

    private fun filterBranches() {
        val text = targetField.text.lowercase()
        branchPopup.removeAll()
        val matches = remoteBranches.filter { it.lowercase().contains(text) }.take(15)
        if (matches.isEmpty() || (matches.size == 1 && matches[0] == targetField.text)) {
            branchPopup.isVisible = false
            return
        }
        matches.forEach { branch ->
            val item = JMenuItem(branch)
            item.addActionListener {
                targetField.text = branch
                branchPopup.isVisible = false
            }
            branchPopup.add(item)
        }
        branchPopup.show(targetField, 0, targetField.height)
    }

    // --- Reviewer autocomplete ---

    private fun searchUsers() {
        val text = reviewerInput.text.trim()
        if (text.length < 2) {
            reviewerPopup.isVisible = false
            return
        }
        userSearchJob?.cancel()
        userSearchJob = scope.launch {
            delay(300) // debounce
            val client = BitbucketBranchClient.fromConfiguredSettings() ?: return@launch
            val result = client.getUsers(text)
            invokeLater {
                if (result is ApiResult.Success) {
                    showUserResults(result.data.filter { it.name !in selectedReviewers })
                }
            }
        }
    }

    private fun showUserResults(users: List<BitbucketUser>) {
        reviewerPopup.removeAll()
        if (users.isEmpty()) {
            reviewerPopup.isVisible = false
            return
        }
        users.forEach { user ->
            val label = "${user.name} — ${user.displayName}"
            val item = JMenuItem(label)
            item.addActionListener {
                addReviewerChip(user.name)
                reviewerInput.text = ""
                reviewerPopup.isVisible = false
            }
            reviewerPopup.add(item)
        }
        reviewerPopup.show(reviewerInput, 0, reviewerInput.height)
    }

    private fun addReviewerChip(username: String) {
        if (username in selectedReviewers) return
        selectedReviewers.add(username)
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            background = StatusColors.CARD_BG
            border = JBUI.Borders.empty(1, 6)
            add(JBLabel(username).apply { font = font.deriveFont(11f) })
            val removeBtn = JBLabel("✕").apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                foreground = StatusColors.SECONDARY_TEXT
            }
            removeBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    selectedReviewers.remove(username)
                    reviewerChipsPanel.remove(this@apply)
                    reviewerChipsPanel.revalidate()
                    reviewerChipsPanel.repaint()
                }
            })
            add(removeBtn)
        }
        // Insert before the input field
        reviewerChipsPanel.remove(reviewerInput)
        reviewerChipsPanel.add(chip)
        reviewerChipsPanel.add(reviewerInput)
        reviewerChipsPanel.revalidate()
    }

    // --- Description ---

    private fun showDescriptionTab(tab: String) {
        if (tab == "preview") {
            previewPane.text = MarkdownToHtml.convert(descriptionArea.text)
        }
        descCardLayout.show(descCardPanel, tab)
        editTabButton.font = editTabButton.font.deriveFont(if (tab == "edit") Font.BOLD else Font.PLAIN)
        previewTabButton.font = previewTabButton.font.deriveFont(if (tab == "preview") Font.BOLD else Font.PLAIN)
    }

    private fun showDescriptionLoading() {
        descCardLayout.show(descCardPanel, "loading")
        regenerateButton.isEnabled = false
    }

    private fun generateDescription() {
        scope.launch {
            val targetBranch = withContext(Dispatchers.EDT) { targetField.text }
            val description = PrDescriptionGenerator.generate(
                project, ticketDetails, sourceBranch, targetBranch
            )
            invokeLater {
                descriptionArea.text = description
                regenerateButton.isEnabled = true
                showDescriptionTab("edit")
            }
        }
    }

    private fun regenerateDescription() {
        showDescriptionLoading()
        generateDescription()
    }

    // --- Validation ---

    override fun doValidate(): ValidationInfo? {
        if (titleField.text.isBlank()) return ValidationInfo("Title cannot be empty", titleField)
        if (targetField.text.isBlank()) return ValidationInfo("Select a target branch", targetField)
        if (targetField.text == sourceBranch) return ValidationInfo("Target cannot be the same as source", targetField)
        return null
    }

    // --- Submit ---

    override fun doOKAction() {
        isOKActionEnabled = false
        resultLabel.text = "Creating PR..."
        resultLabel.foreground = JBColor.foreground()

        val projectKey = settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = settings.state.bitbucketRepoSlug.orEmpty()

        val client = BitbucketBranchClient.fromConfiguredSettings()
        if (client == null) {
            resultLabel.text = "Bitbucket not configured"
            resultLabel.foreground = JBColor.RED
            isOKActionEnabled = true
            return
        }

        val reviewers = selectedReviewers.map {
            com.workflow.orchestrator.core.bitbucket.BitbucketReviewer(
                com.workflow.orchestrator.core.bitbucket.BitbucketReviewerUser(it)
            )
        }

        scope.launch {
            val result = client.createPullRequest(
                projectKey = projectKey,
                repoSlug = repoSlug,
                title = titleField.text.trim(),
                description = descriptionArea.text,
                fromBranch = sourceBranch,
                toBranch = targetField.text.trim(),
                reviewers = reviewers
            )

            invokeLater {
                when (result) {
                    is ApiResult.Success -> {
                        val prUrl = result.data.links.self.firstOrNull()?.href ?: ""
                        log.info("[Build:PR] PR #${result.data.id} created: $prUrl")

                        // Transition ticket if checked
                        if (transitionCheckbox.isSelected && transitionCheckbox.isVisible) {
                            val selected = transitionCombo.selectedItem as? TransitionItem
                            if (selected != null && ticketDetails != null) {
                                scope.launch {
                                    JiraTicketProvider.getInstance()
                                        ?.transitionTicket(ticketDetails.key, selected.transition.id)
                                }
                            }
                        }

                        // Emit event
                        val ticketId = ticketDetails?.key ?: settings.state.activeTicketId.orEmpty()
                        scope.launch {
                            project.getService(EventBus::class.java)
                                .emit(WorkflowEvent.PullRequestCreated(prUrl, result.data.id, ticketId))
                        }

                        close(OK_EXIT_CODE)
                    }
                    is ApiResult.Error -> {
                        isOKActionEnabled = true
                        resultLabel.text = result.message
                        resultLabel.foreground = JBColor.RED
                    }
                }
            }
        }
    }
}

private data class TransitionItem(val transition: TicketTransition) {
    override fun toString() = transition.name
}
