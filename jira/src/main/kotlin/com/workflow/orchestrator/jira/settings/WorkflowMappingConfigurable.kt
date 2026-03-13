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

class WorkflowMappingConfigurable(private val project: Project) :
    BoundSearchableConfigurable("Workflow Mapping", "workflow.orchestrator.workflow") {

    private val intentFields = mutableMapOf<WorkflowIntent, String>()

    override fun createPanel(): DialogPanel {
        val settings = PluginSettings.getInstance(project)
        val store = TransitionMappingStore()
        store.loadFromJson(settings.state.workflowMappings ?: "")

        for (intent in WorkflowIntent.entries) {
            val mapping = store.getMapping(intent.name, "")
            intentFields[intent] = mapping?.transitionName ?: ""
        }

        val boardStatusLabel = JLabel("")

        return panel {
            group("Board Configuration") {
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
                        .comment("Scrum boards have sprints. Kanban boards show unresolved issues instead.")
                }
                row("Board ID:") {
                    intTextField(IntRange(0, 99999))
                        .bindIntText(
                            { settings.state.jiraBoardId },
                            { settings.state.jiraBoardId = it }
                        )
                        .comment("Leave 0 to auto-discover the first matching board.")
                }
                row {
                    button("Discover Boards") {
                        val jiraUrl = settings.state.jiraUrl
                        if (jiraUrl.isNullOrBlank()) {
                            boardStatusLabel.text = "Configure Jira URL first"
                            return@button
                        }
                        boardStatusLabel.text = "Discovering..."
                        val credentialStore = CredentialStore()
                        val apiClient = JiraApiClient(
                            baseUrl = jiraUrl.trimEnd('/'),
                            tokenProvider = { credentialStore.getToken(ServiceType.JIRA) }
                        )
                        runBackgroundableTask("Discovering Jira Boards", project, false) {
                            val result = runBlocking { apiClient.getBoards() }
                            SwingUtilities.invokeLater {
                                when (result) {
                                    is ApiResult.Success -> {
                                        if (result.data.isEmpty()) {
                                            boardStatusLabel.text = "No boards found"
                                        } else {
                                            val boards = result.data.joinToString("\n") { b ->
                                                "  ${b.id}: ${b.name} (${b.type})"
                                            }
                                            boardStatusLabel.text = "<html>${result.data.size} board(s) found:<br>${
                                                result.data.joinToString("<br>") { b ->
                                                    "&nbsp;&nbsp;<b>${b.id}</b>: ${b.name} (${b.type})"
                                                }
                                            }</html>"
                                        }
                                    }
                                    is ApiResult.Error -> {
                                        boardStatusLabel.text = "Error: ${result.message}"
                                    }
                                }
                            }
                        }
                    }
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
