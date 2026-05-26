package com.workflow.orchestrator.agent.ui.apidocs

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Tools → View API Documentation. Opens the single combined API-docs page
 * (Jira / Bitbucket / Bamboo / SonarQube / Sourcegraph endpoint reference).
 * No picker — the page itself has per-family tabs.
 */
class OpenApiDocsAction :
    AnAction("View API Documentation", "Browse documented external API endpoints", null),
    DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApiDocsEditor.open(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
