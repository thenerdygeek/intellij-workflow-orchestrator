package com.workflow.orchestrator.core.services.jira

import com.intellij.openapi.project.Project

interface TransitionDialogOpener {
    fun open(project: Project, ticketKey: String, transitionId: String? = null)
}
