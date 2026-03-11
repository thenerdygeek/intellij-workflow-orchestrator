package com.workflow.orchestrator.cody.editor

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.workflow.orchestrator.cody.protocol.*

class CodyEditApplier(private val project: Project) {

    fun showDiffPreview(editTask: EditTask, operations: List<WorkspaceEditOperation>) {
        for (op in operations) {
            when (op.type) {
                "edit-file" -> showEditFileDiff(editTask, op)
                "create-file" -> showCreateFileDiff(editTask, op)
            }
        }
    }

    private fun showEditFileDiff(editTask: EditTask, op: WorkspaceEditOperation) {
        val uri = op.uri ?: return
        val filePath = uri.removePrefix("file://")
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return

        val originalContent = document.text
        val modifiedContent = CodyEditApplierLogic.applyTextEditsToContent(originalContent, op.edits ?: emptyList())

        val contentFactory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Cody AI Fix — ${editTask.instruction ?: "Edit"}",
            contentFactory.create(project, originalContent, vFile.fileType),
            contentFactory.create(project, modifiedContent, vFile.fileType),
            "Original",
            "Cody Suggestion"
        )

        ApplicationManager.getApplication().invokeLater {
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    private fun showCreateFileDiff(editTask: EditTask, op: WorkspaceEditOperation) {
        val content = op.textContents ?: return
        val contentFactory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Cody AI — New File",
            contentFactory.createEmpty(),
            contentFactory.create(content),
            "Empty",
            "Cody Generated"
        )

        ApplicationManager.getApplication().invokeLater {
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    fun applyEdits(operations: List<WorkspaceEditOperation>) {
        WriteCommandAction.runWriteCommandAction(project, "Cody AI Edit", "cody.edit", {
            for (op in operations) {
                when (op.type) {
                    "edit-file" -> applyEditFileOperation(op)
                    "create-file" -> applyCreateFileOperation(op)
                }
            }
        })
    }

    private fun applyEditFileOperation(op: WorkspaceEditOperation) {
        val uri = op.uri ?: return
        val filePath = uri.removePrefix("file://")
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return

        val newContent = CodyEditApplierLogic.applyTextEditsToContent(document.text, op.edits ?: emptyList())
        document.setText(newContent)
    }

    private fun applyCreateFileOperation(op: WorkspaceEditOperation) {
        val uri = op.uri ?: return
        val filePath = uri.removePrefix("file://")
        val parentPath = filePath.substringBeforeLast("/")
        val fileName = filePath.substringAfterLast("/")

        val parentDir = LocalFileSystem.getInstance().findFileByPath(parentPath) ?: return
        val newFile = parentDir.createChildData(this, fileName)
        val document = FileDocumentManager.getInstance().getDocument(newFile) ?: return
        document.setText(op.textContents ?: "")
    }
}

object CodyEditApplierLogic {

    fun applyTextEditsToContent(content: String, edits: List<TextEdit>): String {
        val sortedEdits = edits.sortedByDescending { edit ->
            when (edit.type) {
                "replace", "delete" -> computeOffset(content, edit.range?.start ?: Position())
                "insert" -> computeOffset(content, edit.position ?: Position())
                else -> 0
            }
        }

        var result = content
        for (edit in sortedEdits) {
            result = when (edit.type) {
                "replace" -> {
                    val start = computeOffset(result, edit.range?.start ?: Position())
                    val end = computeOffset(result, edit.range?.end ?: Position())
                    result.substring(0, start) + (edit.value ?: "") + result.substring(end)
                }
                "insert" -> {
                    val offset = computeOffset(result, edit.position ?: Position())
                    result.substring(0, offset) + (edit.value ?: "") + result.substring(offset)
                }
                "delete" -> {
                    val start = computeOffset(result, edit.range?.start ?: Position())
                    val end = computeOffset(result, edit.range?.end ?: Position())
                    result.substring(0, start) + result.substring(end)
                }
                else -> result
            }
        }
        return result
    }

    fun computeOffset(content: String, pos: Position): Int {
        var offset = 0
        var currentLine = 0
        for (char in content) {
            if (currentLine == pos.line) break
            if (char == '\n') currentLine++
            offset++
        }
        return (offset + pos.character).coerceAtMost(content.length)
    }
}
