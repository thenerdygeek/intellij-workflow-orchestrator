package com.workflow.orchestrator.agent.ui.plan

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class AgentPlanEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is AgentPlanVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return AgentPlanEditor(project, file as AgentPlanVirtualFile)
    }
    override fun getEditorTypeId() = "AgentPlanEditor"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
