package com.workflow.orchestrator.agent.testing

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent

// ── FileType ──

object ToolTestingFileType : FileType {
    override fun getName() = "ToolTesting"
    override fun getDescription() = "Agent Tool Testing"
    override fun getDefaultExtension() = "tooltesting"
    override fun getIcon(): Icon = AllIcons.Debugger.Console
    override fun isBinary() = false
    override fun isReadOnly() = true
}

// ── VirtualFile ──

class ToolTestingVirtualFile(val ownerProject: Project) : LightVirtualFile(
    "Tool Testing",
    ToolTestingFileType,
    ""
) {
    override fun isWritable() = false
}

// ── FileEditor ──

class ToolTestingEditor(
    private val project: Project,
    private val file: ToolTestingVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val disposer: Disposable = Disposer.newDisposable("ToolTestingEditor")
    private val panel = ToolTestingPanel(project, disposer)

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel
    override fun getName() = "Tool Testing"
    override fun isValid() = true
    override fun isModified() = false
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        Disposer.dispose(disposer)
    }
}

// ── FileEditorProvider ──

class ToolTestingEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is ToolTestingVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return ToolTestingEditor(project, file as ToolTestingVirtualFile)
    }
    override fun getEditorTypeId() = "ToolTestingEditor"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
