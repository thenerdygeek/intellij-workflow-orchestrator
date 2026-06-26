package com.workflow.orchestrator.core.toolwindow

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import javax.swing.JComponent

interface WorkflowTabProvider {
    val tabTitle: String
    val order: Int
    fun createPanel(project: Project): JComponent

    /**
     * Whether this tab should be shown for the given project. Default true.
     * MUST be cheap and non-blocking — called on the EDT during tab building.
     * Implementations that depend on a remote capability must read a cached
     * verdict and trigger any probe asynchronously (see JiraAgileCapabilityService),
     * then emit WorkflowEvent.TabAvailabilityChanged to request a rebuild.
     */
    fun isAvailable(project: Project): Boolean = true

    companion object {
        val EP_NAME = ExtensionPointName.create<WorkflowTabProvider>(
            "com.workflow.orchestrator.tabProvider"
        )
    }
}
