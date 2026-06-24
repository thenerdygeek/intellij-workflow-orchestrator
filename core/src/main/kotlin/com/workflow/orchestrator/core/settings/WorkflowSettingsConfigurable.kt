package com.workflow.orchestrator.core.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

/**
 * Parent settings page: Tools > Workflow Orchestrator.
 * Shows a read-only connection status overview.
 * All editable fields live in child pages: General, Workflow, CI/CD, AI & Advanced.
 *
 * PUBLIC ANCHOR: the plugin.xml `id="workflow.orchestrator"` on this configurable's
 * <projectConfigurable> (root plugin.xml) is a STABLE nesting anchor — `parentId` resolves
 * against that plugin.xml id (NOT getId()). Depending plugins (e.g. plugin B) nest their own
 * Settings pages with <projectConfigurable parentId="workflow.orchestrator">. Do NOT rename
 * this id; a rename silently detaches every depending plugin's page. Pinned by SettingsAnchorContractTest.
 */
class WorkflowSettingsConfigurable(
    private val project: Project
) : SearchableConfigurable {

    override fun getId(): String = "workflow.orchestrator"
    override fun getDisplayName(): String = "Workflow Orchestrator"

    override fun createComponent(): JComponent {
        return panel {
            group("Connection Status") {
                buildConnectionRows()
            }
            row {
                comment("Configure connections, workflow, CI/CD, and AI settings in the sub-pages below.")
            }
        }
    }

    override fun isModified(): Boolean = false
    override fun apply() {}
    override fun reset() {}

    private fun Panel.buildConnectionRows() {
        val cs = ConnectionSettings.getInstance().state
        val services = listOf(
            "Jira" to cs.jiraUrl,
            "Bamboo" to cs.bambooUrl,
            "Bitbucket" to cs.bitbucketUrl,
            "SonarQube" to cs.sonarUrl,
            "Sourcegraph" to cs.sourcegraphUrl,
        )

        for ((name, url) in services) {
            val hasUrl = !url.isNullOrBlank()
            val statusIcon = if (hasUrl) AllIcons.General.InspectionsOK else AllIcons.General.Error
            val statusText = if (hasUrl) "$name — configured" else "$name — not configured"
            row {
                icon(statusIcon)
                label(statusText)
            }
        }
    }
}
