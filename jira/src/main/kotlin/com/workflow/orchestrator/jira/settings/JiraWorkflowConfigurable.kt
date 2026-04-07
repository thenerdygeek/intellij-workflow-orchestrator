package com.workflow.orchestrator.jira.settings

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.ConnectionStatusBanner
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.WorkflowIntent
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraBoard
import com.workflow.orchestrator.jira.workflow.TransitionMapping
import com.workflow.orchestrator.jira.workflow.TransitionMappingStore
import kotlinx.coroutines.runBlocking
import javax.swing.JComponent
import javax.swing.JLabel

/** Wrapper for JiraBoard to customize toString for the combo box. */
private data class JiraWorkflowBoardItem(val board: JiraBoard) {
    override fun toString(): String = "${board.name} (${board.type}, ID: ${board.id})"
}

/**
 * Redesigned merged settings page for Jira + Workflow configuration.
 *
 * Sections (in order):
 *  1. Connection status banner (Jira)
 *  2. Jira Board (search + select)
 *  3. Workflow Transitions (10 intent -> transition name mappings)
 *  4. Branching (branch pattern + conventional commits)
 *  5. Pull Requests (title format, reviewers, AI review max diff lines)
 *  6. Time Tracking (collapsed, max hours, increment, auto-transition)
 *  7. Jira Custom Fields (collapsed, epic link / reviewer / tester field IDs)
 *  8. Advanced (collapsed, max branch name length, max PR title length, ticket key regex)
 *
 * Transition guards (build-passed, copyright, coverage, automation) are intentionally
 * removed as part of the settings UX redesign — those checkboxes were orphaned.
 */
class JiraWorkflowConfigurable(private val project: Project) : SearchableConfigurable {

    private val log = Logger.getInstance(JiraWorkflowConfigurable::class.java)
    private val intentFields = mutableMapOf<WorkflowIntent, String>()

    // Board selection state
    private var selectedBoardId: Int = 0
    private var selectedBoardType: String = ""
    private var selectedBoardName: String = ""
    private var boardSearchFieldRef: javax.swing.JTextField? = null

    // Ticket key regex (reads/writes ConnectionSettings)
    private var ticketKeyRegexField: javax.swing.JTextField? = null

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    override fun getId(): String = "workflow.orchestrator.jira_workflow"
    override fun getDisplayName(): String = "Jira & Workflow"

    override fun createComponent(): JComponent {
        val settings = PluginSettings.getInstance(project)
        val connSettings = ConnectionSettings.getInstance()
        val store = TransitionMappingStore()
        store.loadFromJson(settings.state.workflowMappings ?: "")

        for (intent in WorkflowIntent.entries) {
            val mapping = store.getMapping(intent.name, "")
            intentFields[intent] = mapping?.transitionName ?: ""
        }

        // Initialize board state from persisted settings
        selectedBoardId = settings.state.jiraBoardId
        selectedBoardType = settings.state.jiraBoardType ?: ""
        selectedBoardName = settings.state.jiraBoardName ?: ""

        val boardStatusLabel = JLabel("")
        val boardSearchField = javax.swing.JTextField(20)
        boardSearchField.text = settings.state.boardFilterRegex ?: ""
        boardSearchFieldRef = boardSearchField
        val boardComboBox = javax.swing.JComboBox<JiraWorkflowBoardItem>()
        boardComboBox.renderer = com.intellij.ui.SimpleListCellRenderer.create("") { item ->
            if (item == null) "Select a board..." else "${item.board.name} (${item.board.type}, ID: ${item.board.id})"
        }

        // Pre-populate with current saved board
        if (selectedBoardId > 0) {
            val placeholder = JiraBoard(
                selectedBoardId,
                selectedBoardName.ifBlank { "Board $selectedBoardId" },
                selectedBoardType.ifBlank { "scrum" }
            )
            boardComboBox.addItem(JiraWorkflowBoardItem(placeholder))
            boardComboBox.selectedIndex = 0
            boardStatusLabel.text = "Current: ${selectedBoardName.ifBlank { "Board $selectedBoardId" }} (ID: $selectedBoardId)"
        }

        boardComboBox.addActionListener {
            val selected = boardComboBox.selectedItem as? JiraWorkflowBoardItem ?: return@addActionListener
            selectedBoardId = selected.board.id
            selectedBoardType = selected.board.type
            selectedBoardName = selected.board.name
        }

        // Ticket key regex field
        val regexField = javax.swing.JTextField(30)
        regexField.text = connSettings.state.ticketKeyRegex
        ticketKeyRegexField = regexField

        val panel = panel {
            // === 1. Connection status banner ===
            ConnectionStatusBanner.render(
                this, project,
                listOf(ConnectionStatusBanner.Requirement.JIRA)
            )

            // === 2. Jira Board ===
            group("Jira Board") {
                row {
                    comment("Search for boards by name, pick one from the dropdown, then Apply.")
                }
                row("Search:") {
                    cell(boardSearchField)
                        .applyToComponent {
                            toolTipText = "Board name to search for (server-side, leave blank to fetch all)"
                        }
                        .comment("e.g. <code>MyTeam</code> or <code>Sprint Board</code> (leave blank for all)")
                    button("Search Boards") {
                        val jiraUrl = settings.connections.jiraUrl
                        if (jiraUrl.isNullOrBlank()) {
                            boardStatusLabel.text = "Configure Jira URL in Connections first"
                            return@button
                        }
                        val searchText = boardSearchField.text.trim()

                        boardStatusLabel.text = "Searching boards..."
                        val credentialStore = CredentialStore()
                        val apiClient = JiraApiClient(
                            baseUrl = jiraUrl.trimEnd('/'),
                            tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
                        )
                        runBackgroundableTask("Searching Jira Boards", project, false) {
                            val result = runBlocking {
                                apiClient.getBoards(nameFilter = searchText)
                            }
                            invokeLater {
                                when (result) {
                                    is ApiResult.Success -> {
                                        val boards = result.data
                                        log.info("[JiraWorkflow:Settings] Search '${searchText}' returned ${boards.size} boards")
                                        boardComboBox.removeAllItems()
                                        if (boards.isEmpty()) {
                                            boardStatusLabel.text = if (searchText.isNotBlank()) {
                                                "No boards matching '$searchText'. Try a different search or leave blank."
                                            } else {
                                                "No boards returned from Jira. Check your permissions."
                                            }
                                        } else {
                                            for (board in boards) {
                                                boardComboBox.addItem(JiraWorkflowBoardItem(board))
                                            }
                                            boardComboBox.selectedIndex = 0
                                            boardStatusLabel.text = "${boards.size} board(s) found"
                                        }
                                    }
                                    is ApiResult.Error -> {
                                        boardStatusLabel.text = "Error: ${result.message}"
                                    }
                                }
                            }
                        }
                    }
                }
                row("Selected board:") {
                    cell(boardComboBox)
                        .comment("Choose from results, then click Apply.")
                }
                row {
                    cell(boardStatusLabel)
                }
            }

            // === 3. Workflow Transitions ===
            group("Workflow Transitions") {
                row {
                    comment("Map plugin actions to your Jira workflow transitions. Leave blank to auto-detect.")
                }
                for (intent in WorkflowIntent.entries) {
                    row("${intent.displayName}:") {
                        textField()
                            .bindText(
                                { intentFields[intent] ?: "" },
                                { intentFields[intent] = it }
                            )
                            .comment("Auto: ${intent.defaultNames.firstOrNull() ?: "not mapped"}")
                    }
                }
            }

            // === 4. Branching ===
            group("Branching") {
                row("Branch pattern:") {
                    textField()
                        .bindText(
                            { settings.state.branchPattern ?: "feature/{ticketId}-{summary}" },
                            { settings.state.branchPattern = it }
                        )
                        .columns(COLUMNS_LARGE)
                        .comment("Placeholders: {ticketId}, {summary}, {type}, {ai-summary}")
                }
                row {
                    checkBox("Use conventional commits (feat:, fix:, etc.)")
                        .bindSelected(settings.state::useConventionalCommits)
                }
            }

            // === 5. Pull Requests ===
            group("Pull Requests") {
                row("PR title format:") {
                    textField()
                        .bindText(
                            { settings.state.prTitleFormat ?: "" },
                            { settings.state.prTitleFormat = it }
                        )
                        .columns(COLUMNS_LARGE)
                        .comment("Variables: {ticketId}, {summary}, {branch}")
                }
                row("Default reviewers:") {
                    textField()
                        .bindText(
                            { settings.state.prDefaultReviewers ?: "" },
                            { settings.state.prDefaultReviewers = it }
                        )
                        .columns(COLUMNS_LARGE)
                        .comment("Comma-separated Bitbucket usernames")
                }
                row("AI review max diff lines:") {
                    intTextField(range = 100..100000)
                        .bindIntText(settings.state::maxDiffLinesForReview)
                        .comment("Maximum diff size for AI-powered pre-review")
                }
            }

            // === 6. Time Tracking (collapsed by default) ===
            collapsibleGroup("Time Tracking") {
                row("Max hours per worklog:") {
                    textField()
                        .bindText(
                            { settings.state.maxWorklogHours.toString() },
                            { settings.state.maxWorklogHours = it.toFloatOrNull() ?: 7.0f }
                        )
                }
                row("Time increment (hours):") {
                    textField()
                        .bindText(
                            { settings.state.worklogIncrementHours.toString() },
                            { settings.state.worklogIncrementHours = it.toFloatOrNull() ?: 0.5f }
                        )
                }
                row {
                    checkBox("Automatically transition ticket on commit")
                        .bindSelected(settings.state::autoTransitionOnCommit)
                        .comment("Move the active ticket to 'In Progress' on first commit")
                }
            }.expanded = false

            // === 7. Jira Custom Fields (collapsed by default) ===
            collapsibleGroup("Jira Custom Fields (Advanced)") {
                row("Epic link field ID:") {
                    textField()
                        .bindText(
                            { settings.state.epicLinkFieldId ?: "" },
                            { settings.state.epicLinkFieldId = it }
                        )
                        .comment("e.g., customfield_10014")
                }
                row("Reviewer field ID:") {
                    textField()
                        .bindText(
                            { settings.state.reviewerFieldId ?: "" },
                            { settings.state.reviewerFieldId = it }
                        )
                        .comment("e.g., customfield_10050 (leave blank if not used)")
                }
                row("Tester field ID:") {
                    textField()
                        .bindText(
                            { settings.state.testerFieldId ?: "" },
                            { settings.state.testerFieldId = it }
                        )
                        .comment("e.g., customfield_10051 (leave blank if not used)")
                }
            }.expanded = false

            // === 8. Advanced (collapsed by default) ===
            collapsibleGroup("Advanced") {
                row("Max branch name length:") {
                    intTextField(range = 10..200)
                        .bindIntText(settings.state::branchMaxSummaryLength)
                }
                row("Max PR title length:") {
                    intTextField(range = 20..300)
                        .bindIntText(settings.state::maxPrTitleLength)
                }
                row("Ticket key regex:") {
                    cell(regexField)
                        .columns(COLUMNS_LARGE)
                        .comment("Used to detect and hyperlink Jira ticket keys (e.g., PROJ-123)")
                }
            }.expanded = false
        }

        dialogPanel = panel
        return JBScrollPane(panel)
    }

    override fun isModified(): Boolean {
        val settings = PluginSettings.getInstance(project)
        val connSettings = ConnectionSettings.getInstance()

        // Board selection
        if (selectedBoardId != settings.state.jiraBoardId) return true
        if (selectedBoardType != (settings.state.jiraBoardType ?: "")) return true
        if (selectedBoardName != (settings.state.jiraBoardName ?: "")) return true
        if ((boardSearchFieldRef?.text?.trim() ?: "") != (settings.state.boardFilterRegex ?: "")) return true

        // Ticket key regex
        if (ticketKeyRegexField?.text != connSettings.state.ticketKeyRegex) return true

        // Workflow transition fields (not bound via DSL — track manually)
        val store = TransitionMappingStore()
        store.loadFromJson(settings.state.workflowMappings ?: "")
        for (intent in WorkflowIntent.entries) {
            val saved = store.getMapping(intent.name, "")?.transitionName ?: ""
            val current = intentFields[intent] ?: ""
            if (saved != current) return true
        }

        // DSL-bound fields
        return dialogPanel?.isModified() ?: false
    }

    override fun apply() {
        dialogPanel?.apply()

        val settings = PluginSettings.getInstance(project)
        val connSettings = ConnectionSettings.getInstance()

        // Write board selection
        if (selectedBoardId > 0) {
            settings.state.jiraBoardId = selectedBoardId
            settings.state.jiraBoardType = selectedBoardType
            settings.state.jiraBoardName = selectedBoardName
        }
        settings.state.boardFilterRegex = boardSearchFieldRef?.text?.trim() ?: ""

        // Write workflow transition mappings
        val store = TransitionMappingStore()
        store.loadFromJson(settings.state.workflowMappings ?: "")
        for (intent in WorkflowIntent.entries) {
            store.clearExplicitGlobalMapping(intent.name)
        }
        for ((intent, transitionName) in intentFields) {
            if (transitionName.isNotBlank()) {
                store.saveMapping(
                    TransitionMapping(intent.name, transitionName, "", null, "explicit")
                )
            }
        }
        settings.state.workflowMappings = store.toJson()

        // Write ticket key regex (validate pattern before saving)
        val candidateRegex = ticketKeyRegexField?.text?.trim()?.ifBlank { null }
        val defaultRegex = "\\b([A-Z][A-Z0-9]+-\\d+)\\b"
        connSettings.state.ticketKeyRegex = if (candidateRegex != null) {
            try {
                Regex(candidateRegex) // validate that it compiles
                candidateRegex
            } catch (e: java.util.regex.PatternSyntaxException) {
                defaultRegex // fall back to default on invalid pattern
            }
        } else {
            defaultRegex
        }
    }

    override fun reset() {
        val settings = PluginSettings.getInstance(project)
        val connSettings = ConnectionSettings.getInstance()

        // Reset board state
        selectedBoardId = settings.state.jiraBoardId
        selectedBoardType = settings.state.jiraBoardType ?: ""
        selectedBoardName = settings.state.jiraBoardName ?: ""
        boardSearchFieldRef?.text = settings.state.boardFilterRegex ?: ""

        // Reset ticket key regex
        ticketKeyRegexField?.text = connSettings.state.ticketKeyRegex

        // Reset intent fields
        val store = TransitionMappingStore()
        store.loadFromJson(settings.state.workflowMappings ?: "")
        for (intent in WorkflowIntent.entries) {
            val mapping = store.getMapping(intent.name, "")
            intentFields[intent] = mapping?.transitionName ?: ""
        }

        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
        boardSearchFieldRef = null
        ticketKeyRegexField = null
    }
}
