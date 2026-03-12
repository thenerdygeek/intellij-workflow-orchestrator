package com.workflow.orchestrator.jira.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.WorkflowIntent
import com.workflow.orchestrator.jira.workflow.TransitionMapping
import com.workflow.orchestrator.jira.workflow.TransitionMappingStore

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

        return panel {
            group("Board Type") {
                row("Board type:") {
                    comboBox(listOf("scrum", "kanban", ""))
                        .bindItem(
                            { settings.state.jiraBoardType ?: "scrum" },
                            { settings.state.jiraBoardType = it ?: "scrum" }
                        )
                        .comment("Filter Jira boards by type. Empty = show all.")
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
