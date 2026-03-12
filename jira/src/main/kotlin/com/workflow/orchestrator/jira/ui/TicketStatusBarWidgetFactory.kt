package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class TicketStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = TicketStatusBarWidget.ID

    override fun getDisplayName(): String = "Workflow Ticket Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return TicketStatusBarWidget(project)
    }
}
