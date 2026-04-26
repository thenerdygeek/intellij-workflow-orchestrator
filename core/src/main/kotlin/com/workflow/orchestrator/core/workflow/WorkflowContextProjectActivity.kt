package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class WorkflowContextProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = WorkflowContextService.getInstance(project)
        WorkflowEventMirror(project, service).install()
    }
}
