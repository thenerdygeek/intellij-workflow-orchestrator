package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.services.jira.TransitionDialogOpener

@Service(Service.Level.PROJECT)
class TransitionDialogOpenerImpl(private val project: Project) : TransitionDialogOpener {
    override fun open(project: Project, ticketKey: String, transitionId: String?) {
        TicketTransitionDialog(project, ticketKey, ticketKey.substringBefore("-"), transitionId).showAndGet()
    }
}
