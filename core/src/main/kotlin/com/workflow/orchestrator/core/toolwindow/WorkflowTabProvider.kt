package com.workflow.orchestrator.core.toolwindow

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import javax.swing.JComponent

interface WorkflowTabProvider {
    val tabTitle: String
    val order: Int
    fun createPanel(project: Project): JComponent

    companion object {
        val EP_NAME = ExtensionPointName.create<WorkflowTabProvider>(
            "com.workflow.orchestrator.tabProvider"
        )
    }
}
