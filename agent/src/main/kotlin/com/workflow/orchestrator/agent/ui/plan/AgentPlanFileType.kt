package com.workflow.orchestrator.agent.ui.plan

import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon
import com.intellij.icons.AllIcons

object AgentPlanFileType : FileType {
    override fun getName() = "AgentPlan"
    override fun getDescription() = "Agent Implementation Plan"
    override fun getDefaultExtension() = "agentplan"
    override fun getIcon(): Icon = AllIcons.Actions.ListFiles
    override fun isBinary() = false
    override fun isReadOnly() = true
}
