package com.workflow.orchestrator.agent.testing

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware

/**
 * Opens the Tool Testing editor tab — an interactive harness for inspecting
 * and executing every registered agent tool with custom parameters.
 *
 * Accessible from: Tools > Agent Tool Testing
 */
class OpenToolTestingAction : AnAction("Agent Tool Testing", "Open interactive agent tool testing harness", null), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editorManager = FileEditorManager.getInstance(project)

        // Reuse existing tab if open
        val existing = editorManager.openFiles.filterIsInstance<ToolTestingVirtualFile>().firstOrNull()
        if (existing != null) {
            editorManager.openFile(existing, true)
            return
        }

        // Open new tab
        val file = ToolTestingVirtualFile(project)
        editorManager.openFile(file, true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
