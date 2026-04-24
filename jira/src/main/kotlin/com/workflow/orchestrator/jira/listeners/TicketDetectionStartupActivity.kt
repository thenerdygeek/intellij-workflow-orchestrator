package com.workflow.orchestrator.jira.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.jira.ui.TicketDetectionPresenter

/**
 * Eagerly instantiates [TicketDetectionPresenter] so its `EventBus`
 * subscription is wired at project open. Without this, the presenter
 * would only be constructed on first `.getInstance(project)` call,
 * missing events fired earlier in the session.
 */
class TicketDetectionStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        TicketDetectionPresenter.getInstance(project)
    }
}
