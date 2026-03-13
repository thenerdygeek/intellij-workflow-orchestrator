package com.workflow.orchestrator.jira.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.WorkflowIntent
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.workflow.TransitionMapping
import com.workflow.orchestrator.jira.workflow.TransitionMappingStore
import kotlinx.coroutines.runBlocking
import javax.swing.JLabel
import javax.swing.SwingUtilities

/** Wrapper for JiraBoard to customize toString for the combo box. */
private data class BoardItem(val board: com.workflow.orchestrator.jira.api.dto.JiraBoard) {
    override fun toString(): String = "${board.name} (${board.type}, ID: ${board.id})"
}

class WorkflowMappingConfigurable(private val project: Project) :
    BoundSearchableConfigurable("Workflow Mapping", "workflow.orchestrator.workflow") {

    private val intentFields = mutableMapOf<WorkflowIntent, String>()
    private var boardRegexFieldRef: javax.swing.JTextField? = null

    override fun createPanel(): DialogPanel {
        val settings = PluginSettings.getInstance(project)
        val store = TransitionMappingStore()
        store.loadFromJson(settings.state.workflowMappings ?: "")

        for (intent in WorkflowIntent.entries) {
            val mapping = store.getMapping(intent.name, "")
            intentFields[intent] = mapping?.transitionName ?: ""
        }

        val boardStatusLabel = JLabel("")
        val boardSearchField = javax.swing.JTextField(20)
        val boardRegexField = javax.swing.JTextField(20)
        boardRegexField.text = settings.state.boardFilterRegex ?: ""
        boardRegexFieldRef = boardRegexField
        val boardComboBox = javax.swing.JComboBox<BoardItem>()
        boardComboBox.renderer = com.intellij.ui.SimpleListCellRenderer.create("") { item ->
            if (item == null) "Select a board..." else "${item.board.name} (${item.board.type}, ID: ${item.board.id})"
        }
        // Pre-populate with current board if set
        val currentBoardId = settings.state.jiraBoardId
        if (currentBoardId > 0) {
            val currentBoardName = settings.state.jiraBoardName ?: "Board $currentBoardId"
            val currentBoardType = settings.state.jiraBoardType ?: "scrum"
            val placeholder = com.workflow.orchestrator.jira.api.dto.JiraBoard(
                currentBoardId, currentBoardName, currentBoardType
            )
            boardComboBox.addItem(BoardItem(placeholder))
            boardComboBox.selectedIndex = 0
        }
        boardComboBox.addActionListener {
            val selected = boardComboBox.selectedItem as? BoardItem
            if (selected != null) {
                settings.state.jiraBoardId = selected.board.id
                settings.state.jiraBoardType = selected.board.type
                settings.state.jiraBoardName = selected.board.name
            }
        }

        return panel {
            group("Board Configuration") {
                row {
                    comment(
                        "Search for your Jira board by name, then optionally filter results with a regex."
                    )
                }
                row("Search boards:") {
                    cell(boardSearchField)
                        .applyToComponent {
                            toolTipText = "Enter part of your board name to search"
                        }
                    button("Search") {
                        val jiraUrl = settings.state.jiraUrl
                        if (jiraUrl.isNullOrBlank()) {
                            boardStatusLabel.text = "Configure Jira URL in Connections first"
                            return@button
                        }
                        val searchText = boardSearchField.text.trim()
                        if (searchText.isBlank()) {
                            boardStatusLabel.text = "Enter a board name to search"
                            return@button
                        }
                        val regexText = boardRegexField.text.trim()
                        val regex = if (regexText.isNotBlank()) {
                            try {
                                Regex(regexText, RegexOption.IGNORE_CASE)
                            } catch (_: Exception) {
                                boardStatusLabel.text = "Invalid regex: $regexText"
                                return@button
                            }
                        } else null

                        boardStatusLabel.text = "Searching..."
                        val credentialStore = CredentialStore()
                        val apiClient = JiraApiClient(
                            baseUrl = jiraUrl.trimEnd('/'),
                            tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
                        )
                        runBackgroundableTask("Searching Jira Boards", project, false) {
                            val result = runBlocking { apiClient.getBoards(nameFilter = searchText) }
                            SwingUtilities.invokeLater {
                                when (result) {
                                    is ApiResult.Success -> {
                                        val filtered = if (regex != null) {
                                            result.data.filter { regex.containsMatchIn(it.name) }
                                        } else {
                                            result.data
                                        }
                                        boardComboBox.removeAllItems()
                                        if (filtered.isEmpty()) {
                                            val msg = if (regex != null && result.data.isNotEmpty()) {
                                                "${result.data.size} board(s) found but none match regex \"$regexText\""
                                            } else {
                                                "No boards matching \"$searchText\""
                                            }
                                            boardStatusLabel.text = msg
                                        } else {
                                            for (board in filtered) {
                                                boardComboBox.addItem(BoardItem(board))
                                            }
                                            boardComboBox.selectedIndex = 0
                                            val regexNote = if (regex != null) " (${result.data.size} total, ${filtered.size} match regex)" else ""
                                            boardStatusLabel.text = "${filtered.size} board(s) found$regexNote"
                                            val first = filtered.first()
                                            settings.state.jiraBoardId = first.id
                                            settings.state.jiraBoardType = first.type
                                            settings.state.jiraBoardName = first.name
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
                row("Filter regex:") {
                    cell(boardRegexField)
                        .applyToComponent {
                            toolTipText = "Regex applied to board names after search (case-insensitive)"
                        }
                        .comment("e.g. <code>^MyTeam</code> or <code>sprint|kanban</code> — only matching boards appear in the dropdown")
                }
                row("Selected board:") {
                    cell(boardComboBox)
                        .comment("Choose from search results. The selected board will be used for the Sprint Dashboard.")
                }
                row {
                    cell(boardStatusLabel)
                }
                row("Board type:") {
                    comboBox(listOf("", "scrum", "kanban", "simple"))
                        .applyToComponent {
                            renderer = com.intellij.ui.SimpleListCellRenderer.create("") { value ->
                                when (value) {
                                    "" -> "All (auto-detect)"
                                    "scrum" -> "Scrum (has sprints)"
                                    "kanban" -> "Kanban (continuous flow)"
                                    "simple" -> "Simple (basic tracking)"
                                    else -> value
                                }
                            }
                        }
                        .bindItem(
                            { settings.state.jiraBoardType ?: "" },
                            { settings.state.jiraBoardType = it ?: "" }
                        )
                        .comment("Override board type. Usually auto-detected from the selected board.")
                }
                row("Board ID (manual):") {
                    intTextField(IntRange(0, 99999))
                        .bindIntText(
                            { settings.state.jiraBoardId },
                            { settings.state.jiraBoardId = it }
                        )
                        .comment("Auto-filled when you select a board above. Or enter manually if you know the ID.")
                }
            }

            group("Intent Mappings") {
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

            group("Plugin Guards") {
                row {
                    comment("Block transitions until conditions are met:")
                }
                row {
                    checkBox("Build must pass before Submit for Review")
                        .bindSelected(settings.state::guardBuildPassedBeforeReview)
                }
                row {
                    checkBox("Copyright headers checked before Close")
                        .bindSelected(settings.state::guardCopyrightBeforeClose)
                }
                row {
                    checkBox("Coverage gate must pass before Submit for Review")
                        .bindSelected(settings.state::guardCoverageBeforeReview)
                }
                row {
                    checkBox("All automation suites passed before Close")
                        .bindSelected(settings.state::guardAutomationBeforeClose)
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        val settings = PluginSettings.getInstance(project)
        settings.state.boardFilterRegex = boardRegexFieldRef?.text?.trim() ?: ""
        val store = TransitionMappingStore()
        store.loadFromJson(settings.state.workflowMappings ?: "")
        // Clear only explicit global mappings, preserve learned ones
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
    }
}
