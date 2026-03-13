package com.workflow.orchestrator.bamboo.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.project.Project

class BambooBuildConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "BambooBuildConfigurationFactory"
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        BambooBuildRunConfiguration(project, this, "Bamboo Build")
    override fun getOptionsClass(): Class<out RunConfigurationOptions> =
        BambooBuildRunConfigurationOptions::class.java
}
