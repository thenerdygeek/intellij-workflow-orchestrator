package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

/**
 * Parent settings page: Tools > Workflow Orchestrator.
 * Shows a read-only connection status overview.
 * All editable fields live in child pages: General, Workflow, CI/CD, AI & Advanced.
 */
class WorkflowSettingsConfigurable(
    private val project: Project
) : SearchableConfigurable {

    override fun getId(): String = "workflow.orchestrator"
    override fun getDisplayName(): String = "Workflow Orchestrator"

    override fun createComponent(): JComponent {
        return panel {
            group("Connection Status") {
                row {
                    comment(buildConnectionSummary())
                }
            }
            row {
                comment("Configure connections, workflow, CI/CD, and AI settings in the sub-pages below.")
            }
        }
    }

    override fun isModified(): Boolean = false
    override fun apply() {}
    override fun reset() {}

    private fun buildConnectionSummary(): String {
        val cs = ConnectionSettings.getInstance().state
        val services = listOf(
            "Jira" to cs.jiraUrl,
            "Bamboo" to cs.bambooUrl,
            "Bitbucket" to cs.bitbucketUrl,
            "SonarQube" to cs.sonarUrl,
            "Cody" to cs.sourcegraphUrl,
            "Nexus" to cs.nexusUrl,
        )

        return services.joinToString("<br>") { (name, url) ->
            val hasUrl = !url.isNullOrBlank()
            if (hasUrl) "\u2705 $name — configured" else "\u274C $name — not configured"
        }
    }
}
