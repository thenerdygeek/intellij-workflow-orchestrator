package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project

class WorkflowSettingsConfigurable(
    private val project: Project
) : SearchableConfigurable, Configurable.Composite {

    override fun getId(): String = "workflow.orchestrator"
    override fun getDisplayName(): String = "Workflow Orchestrator"

    override fun getConfigurables(): Array<Configurable> {
        return arrayOf(
            ConnectionsConfigurable(project),
            HealthCheckConfigurable(project)
        )
    }

    override fun createComponent() = null
    override fun isModified() = false
    override fun apply() {}
}
