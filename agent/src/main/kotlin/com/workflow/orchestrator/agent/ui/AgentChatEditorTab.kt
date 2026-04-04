package com.workflow.orchestrator.agent.ui

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
import com.workflow.orchestrator.agent.AgentService
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent

// ── FileType ──

object AgentChatFileType : FileType {
    override fun getName() = "AgentChat"
    override fun getDescription() = "Agent Chat"
    override fun getDefaultExtension() = "agentchat"
    override fun getIcon(): Icon = AllIcons.Actions.Expandall
    override fun isBinary() = false
    override fun isReadOnly() = true
}

// ── VirtualFile ──

/**
 * Marker virtual file used to open the agent chat in a full editor tab.
 * Holds a reference to the project so the editor provider can wire callbacks correctly.
 */
class AgentChatVirtualFile(val ownerProject: Project) : LightVirtualFile(
    "Agent Chat",
    AgentChatFileType,
    ""
) {
    override fun isWritable() = false
}

// ── FileEditor ──

/**
 * Full-screen agent chat editor tab.
 *
 * Creates a new [AgentDashboardPanel] backed by its own JCEF instance, then
 * registers it as a mirror of the primary dashboard via [AgentController.addMirrorPanel].
 * Every output call (append message, tool call, token stream, etc.) is broadcast to
 * this panel automatically. All input callbacks are wired back to the same controller
 * so the user can send messages and cancel tasks from the editor tab.
 */
class AgentChatEditor(
    private val project: Project,
    private val chatFile: AgentChatVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val disposer: Disposable = Disposer.newDisposable("AgentChatEditor")
    private val panel = AgentDashboardPanel(parentDisposable = disposer)

    init {
        // TODO: Wire to new AgentController when reimplemented
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel
    override fun getName() = "Agent Chat"
    override fun isValid() = true
    override fun isModified() = false
    override fun getFile(): VirtualFile = chatFile
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        Disposer.dispose(disposer)
    }
}

// ── FileEditorProvider ──

class AgentChatEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is AgentChatVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return AgentChatEditor(project, file as AgentChatVirtualFile)
    }
    override fun getEditorTypeId() = "AgentChatEditor"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
