package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

class WorkflowSettingsConfigurable(
    private val project: Project
) : SearchableConfigurable, Configurable.Composite {

    private val settings = PluginSettings.getInstance(project)
    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    override fun getId(): String = "workflow.orchestrator"
    override fun getDisplayName(): String = "Workflow Orchestrator"

    override fun getConfigurables(): Array<Configurable> {
        return arrayOf(
            ConnectionsConfigurable(project),
            HealthCheckConfigurable(project)
        )
    }

    override fun createComponent(): JComponent {
        val panel = panel {
            group("Connection Status") {
                row {
                    comment(buildConnectionSummary())
                }
            }

            group("General") {
                row("Branch pattern:") {
                    textField()
                        .columns(40)
                        .bindText(
                            { settings.state.branchPattern ?: "feature/{ticketId}-{summary}" },
                            { settings.state.branchPattern = it }
                        )
                        .comment("Placeholders: {ticketId}, {summary}, {type}, {cody-summary}")
                }
                row {
                    checkBox("Use conventional commits (feat:, fix:, etc.)")
                        .bindSelected(
                            { settings.state.useConventionalCommits },
                            { settings.state.useConventionalCommits = it }
                        )
                }
            }

            group("Enabled Modules") {
                row {
                    checkBox("Sprint (Jira)")
                        .bindSelected(
                            { settings.state.sprintModuleEnabled },
                            { settings.state.sprintModuleEnabled = it }
                        )
                }
                row {
                    checkBox("Build (Bamboo)")
                        .bindSelected(
                            { settings.state.buildModuleEnabled },
                            { settings.state.buildModuleEnabled = it }
                        )
                }
                row {
                    checkBox("Quality (SonarQube)")
                        .bindSelected(
                            { settings.state.qualityModuleEnabled },
                            { settings.state.qualityModuleEnabled = it }
                        )
                }
                row {
                    checkBox("Automation (Docker/Bamboo)")
                        .bindSelected(
                            { settings.state.automationModuleEnabled },
                            { settings.state.automationModuleEnabled = it }
                        )
                }
                row {
                    checkBox("Handover (Bitbucket PR)")
                        .bindSelected(
                            { settings.state.handoverModuleEnabled },
                            { settings.state.handoverModuleEnabled = it }
                        )
                }
            }
        }
        dialogPanel = panel
        return panel
    }

    override fun isModified(): Boolean = dialogPanel?.isModified() ?: false

    override fun apply() {
        dialogPanel?.apply()
    }

    override fun reset() {
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }

    private fun buildConnectionSummary(): String {
        // Only check URL presence on EDT — credential reads are deferred
        // to avoid blocking EDT with PasswordSafe/Keychain calls
        val services = listOf(
            "Jira" to settings.state.jiraUrl,
            "Bamboo" to settings.state.bambooUrl,
            "Bitbucket" to settings.state.bitbucketUrl,
            "SonarQube" to settings.state.sonarUrl,
            "Cody" to settings.state.sourcegraphUrl,
            "Nexus" to settings.state.nexusUrl,
        )

        return services.joinToString("<br>") { (name, url) ->
            val hasUrl = !url.isNullOrBlank()
            val status = when {
                hasUrl -> "\u2705 $name — URL configured"
                else -> "\u274C $name — not configured"
            }
            status
        }
    }
}
