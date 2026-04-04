package com.workflow.orchestrator.agent.ui.plan

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * Minimal stub — plan editor will be reimplemented when plan mode is re-wired.
 */
class AgentPlanEditor(
    private val project: Project,
    private val planFile: AgentPlanVirtualFile
) : UserDataHolderBase(), FileEditor {

    var onCommentCountChanged: ((Int) -> Unit)? = null

    private val panel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(16)
        add(JBLabel("Plan editor will be available after agent rewrite."), BorderLayout.CENTER)
    }

    fun updatePlanStep(stepId: String, status: String) {
        // no-op stub
    }

    fun triggerRevise() {
        // no-op stub
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel
    override fun getName() = "Plan"
    override fun isValid() = true
    override fun isModified() = false
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = planFile

    override fun dispose() {}
}
