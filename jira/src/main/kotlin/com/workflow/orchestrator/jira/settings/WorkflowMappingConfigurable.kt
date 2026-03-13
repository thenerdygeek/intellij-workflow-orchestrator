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

    // Track board selection outside the DSL so it survives apply/reset cycles
    private var selectedBoardId: Int = 0
    private var selectedBoardType: String = ""
    private var selectedBoardName: String = ""
    private var boardRegexFieldRef: javax.swing.JTextField? = null

    override fun createPanel(): DialogPanel {
        val settings = PluginSettings.getInstance(project)
        val store = TransitionMappingStore()
        store.loadFromJson(settings.state.workflowMappings ?: "")

        for (intent in WorkflowIntent.entries) {
            val mapping = store.getMapping(intent.name, "")
            intentFields[intent] = mapping?.transitionName ?: ""
        }

        // Initialize from persisted settings
        selectedBoardId = settings.state.jiraBoardId
        selectedBoardType = settings.state.jiraBoardType ?: ""
        selectedBoardName = settings.state.jiraBoardName ?: ""
        val boardStatusLabel = JLabel("")
        val boardRegexField = javax.swing.JTextField(20)
        boardRegexField.text = settings.state.boardFilterRegex ?: ""
        boardRegexFieldRef = boardRegexField
        val boardComboBox = javax.swing.JComboBox<BoardItem>()
        boardComboBox.renderer = com.intellij.ui.SimpleListCellRenderer.create("") { item ->
            if (item == null) "Select a board..." else "${item.board.name} (${item.board.type}, ID: ${item.board.id})"
        }

        // Pre-populate with current saved board
        if (selectedBoardId > 0) {
            val placeholder = com.workflow.orchestrator.jira.api.dto.JiraBoard(
                selectedBoardId,
                selectedBoardName.ifBlank { "Board $selectedBoardId" },
                selectedBoardType.ifBlank { "scrum" }
            )
            boardComboBox.addItem(BoardItem(placeholder))
            boardComboBox.selectedIndex = 0
            boardStatusLabel.text = "Current: ${selectedBoardName.ifBlank { "Board $selectedBoardId" }} (ID: $selectedBoardId)"
        }

        boardComboBox.addActionListener {
            val selected = boardComboBox.selectedItem as? BoardItem ?: return@addActionListener
            selectedBoardId = selected.board.id
            selectedBoardType = selected.board.type
            selectedBoardName = selected.board.name
        }

        return panel {
            group("Board Configuration") {
                row {
                    comment(
                        "Filter boards by regex, pick one from the dropdown, then Apply."
                    )
                }
                row("Board filter:") {
                    cell(boardRegexField)
                        .applyToComponent {
                            toolTipText = "Case-insensitive regex matched against board names"
                        }
                        .comment("e.g. <code>^MyTeam</code> or <code>sprint|kanban</code>")
                    button("Fetch Boards") {
                        val jiraUrl = settings.state.jiraUrl
                        if (jiraUrl.isNullOrBlank()) {
                            boardStatusLabel.text = "Configure Jira URL in Connections first"
                            return@button
                        }
                        val currentRegex = boardRegexField.text.trim()
                        val regex = if (currentRegex.isNotBlank()) {
                            try {
                                Regex(currentRegex, RegexOption.IGNORE_CASE)
                            } catch (_: Exception) {
                                boardStatusLabel.text = "Invalid regex: $currentRegex"
                                return@button
                            }
                        } else null

                        boardStatusLabel.text = "Fetching boards..."
                        val credentialStore = CredentialStore()
                        val apiClient = JiraApiClient(
                            baseUrl = jiraUrl.trimEnd('/'),
                            tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
                        )
                        runBackgroundableTask("Fetching Jira Boards", project, false) {
                            val result = runBlocking { apiClient.getBoards() }
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
                                                "${result.data.size} board(s) found, none match regex"
                                            } else {
                                                "No boards found"
                                            }
                                            boardStatusLabel.text = msg
                                        } else {
                                            for (board in filtered) {
                                                boardComboBox.addItem(BoardItem(board))
                                            }
                                            boardComboBox.selectedIndex = 0
                                            val note = if (regex != null) " (${filtered.size}/${result.data.size} match)" else ""
                                            boardStatusLabel.text = "${filtered.size} board(s)$note"
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

        // Write board selection AFTER super.apply() so DSL bindings don't overwrite
        if (selectedBoardId > 0) {
            settings.state.jiraBoardId = selectedBoardId
            settings.state.jiraBoardType = selectedBoardType
            settings.state.jiraBoardName = selectedBoardName
        }
        settings.state.boardFilterRegex = boardRegexFieldRef?.text?.trim() ?: ""

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
    }

    override fun isModified(): Boolean {
        val settings = PluginSettings.getInstance(project)
        if (selectedBoardId != settings.state.jiraBoardId) return true
        if (selectedBoardType != (settings.state.jiraBoardType ?: "")) return true
        if (selectedBoardName != (settings.state.jiraBoardName ?: "")) return true
        if ((boardRegexFieldRef?.text?.trim() ?: "") != (settings.state.boardFilterRegex ?: "")) return true
        return super.isModified()
    }
}
