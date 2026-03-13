package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import javax.swing.JComponent

class WorkflowSettingsConfigurable(
    private val project: Project
) : SearchableConfigurable, Configurable.Composite {

    private val settings = PluginSettings.getInstance(project)
    private val credentialStore = CredentialStore()
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
                        .comment("Placeholders: {ticketId}, {summary}")
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
        val services = listOf(
            "Jira" to (settings.state.jiraUrl to ServiceType.JIRA),
            "Bamboo" to (settings.state.bambooUrl to ServiceType.BAMBOO),
            "Bitbucket" to (settings.state.bitbucketUrl to ServiceType.BITBUCKET),
            "SonarQube" to (settings.state.sonarUrl to ServiceType.SONARQUBE),
            "Cody" to (settings.state.sourcegraphUrl to ServiceType.SOURCEGRAPH),
            "Nexus" to (settings.state.nexusUrl to ServiceType.NEXUS),
        )

        return services.joinToString("<br>") { (name, pair) ->
            val (url, serviceType) = pair
            val hasUrl = !url.isNullOrBlank()
            val hasToken = if (serviceType == ServiceType.NEXUS) {
                !credentialStore.getNexusPassword().isNullOrBlank()
            } else {
                !credentialStore.getToken(serviceType).isNullOrBlank()
            }
            val status = when {
                hasUrl && hasToken -> "\u2705 $name — configured"
                hasUrl && !hasToken -> "\u26A0\uFE0F $name — URL set, token missing"
                else -> "\u274C $name — not configured"
            }
            status
        }
    }
}
