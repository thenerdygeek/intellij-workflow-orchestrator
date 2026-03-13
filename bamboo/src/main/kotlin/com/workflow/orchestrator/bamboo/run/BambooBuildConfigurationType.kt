package com.workflow.orchestrator.bamboo.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.icons.AllIcons
import javax.swing.Icon

class BambooBuildConfigurationType : ConfigurationType {
    override fun getDisplayName(): String = "Bamboo Build"
    override fun getConfigurationTypeDescription(): String = "Trigger and monitor a Bamboo build"
    override fun getIcon(): Icon = AllIcons.Actions.Execute
    override fun getId(): String = "BambooBuildConfiguration"
    override fun getConfigurationFactories(): Array<ConfigurationFactory> =
        arrayOf(BambooBuildConfigurationFactory(this))
}
