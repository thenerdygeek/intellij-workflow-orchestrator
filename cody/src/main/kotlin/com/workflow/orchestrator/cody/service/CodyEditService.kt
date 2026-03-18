package com.workflow.orchestrator.cody.service

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.workflow.orchestrator.cody.protocol.ContextFile
import com.workflow.orchestrator.cody.protocol.Range

/**
 * Applies AI-generated code fixes via Cody chat.
 *
 * The Cody CLI agent doesn't support editCommands/code (that's only in the
 * full IDE plugin agent). Instead, we use chat/submitMessage to get the fix
 * as text, then apply it to the editor via WriteCommandAction.
 */
class CodyEditService(private val project: Project) {

    private val log = Logger.getInstance(CodyEditService::class.java)

    /**
     * Request a fix via Cody chat and apply it to the file.
     * Returns the generated fix text (for display/notification purposes).
     */
    suspend fun requestFix(
        filePath: String,
        range: Range,
        instruction: String,
        contextFiles: List<ContextFile> = emptyList()
    ): String? {
        log.info("[Cody:Edit] Requesting fix via chat for $filePath lines ${range.start.line}-${range.end.line}")

        // Read the current code at the range
        val currentCode = readCodeAtRange(filePath, range)
        val prompt = buildString {
            appendLine(instruction)
            appendLine()
            appendLine("Current code (lines ${range.start.line + 1}-${range.end.line}):")
            appendLine("```")
            appendLine(currentCode)
            appendLine("```")
            appendLine()
            appendLine("Output ONLY the fixed code — no explanations, no markdown code blocks wrapping. Just the raw replacement code.")
        }

        val textGen = com.workflow.orchestrator.core.ai.TextGenerationService.getInstance()
        if (textGen == null) {
            log.warn("[Cody:Edit] TextGenerationService not available")
            return null
        }
        val result = textGen.generateText(project, prompt, contextFiles.map { it.uri.fsPath })
        if (result.isNullOrBlank()) {
            log.warn("[Cody:Edit] Chat returned empty result")
            return null
        }

        // Clean markdown wrapping if present
        val fixedCode = result
            .replace(Regex("^```[a-z]*\\n?"), "")
            .replace(Regex("\\n?```$"), "")
            .trim()

        log.info("[Cody:Edit] Got fix (${fixedCode.length} chars), applying to editor")

        // Apply the fix to the editor
        applyFixToEditor(filePath, range, fixedCode)
        return fixedCode
    }

    /**
     * Request test generation via Cody chat.
     */
    suspend fun requestTestGeneration(
        filePath: String,
        targetRange: Range,
        existingTestFile: String? = null
    ): String? {
        log.info("[Cody:Edit] Requesting test generation for $filePath")

        val currentCode = readCodeAtRange(filePath, targetRange)
        val prompt = buildString {
            append("Generate a unit test covering the code at lines ")
            append("${targetRange.start.line + 1}-${targetRange.end.line}")
            if (existingTestFile != null) {
                append(". Add to existing test file: $existingTestFile")
            }
            appendLine()
            appendLine()
            appendLine("Source code:")
            appendLine("```")
            appendLine(currentCode)
            appendLine("```")
            appendLine()
            appendLine("Output ONLY the test code — no explanations.")
        }

        val textGen = com.workflow.orchestrator.core.ai.TextGenerationService.getInstance()
        if (textGen == null) {
            log.warn("[Cody:Edit] TextGenerationService not available for test generation")
            return null
        }
        return textGen.generateText(project, prompt, listOf(filePath))
    }

    private fun readCodeAtRange(filePath: String, range: Range): String {
        val vf = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return ""
        val editors = FileEditorManager.getInstance(project).getEditors(vf)
        val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull() ?: return ""
        val document = textEditor.editor.document
        return try {
            val startLine = range.start.line.coerceIn(0, document.lineCount - 1)
            val endLine = range.end.line.coerceIn(startLine, document.lineCount - 1)
            val startOffset = document.getLineStartOffset(startLine)
            val endOffset = document.getLineEndOffset(endLine)
            document.getText(com.intellij.openapi.util.TextRange(startOffset, endOffset))
        } catch (e: Exception) {
            log.warn("[Cody:Edit] Failed to read code at range: ${e.message}")
            ""
        }
    }

    private fun applyFixToEditor(filePath: String, range: Range, fixedCode: String) {
        val vf = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        com.intellij.openapi.application.invokeLater {
            val editors = FileEditorManager.getInstance(project).getEditors(vf)
            val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull() ?: return@invokeLater
            val document = textEditor.editor.document
            try {
                val startLine = range.start.line.coerceIn(0, document.lineCount - 1)
                val endLine = range.end.line.coerceIn(startLine, document.lineCount - 1)
                val startOffset = document.getLineStartOffset(startLine)
                val endOffset = document.getLineEndOffset(endLine)
                WriteCommandAction.runWriteCommandAction(project, "Fix with Cody", null, {
                    document.replaceString(startOffset, endOffset, fixedCode)
                })
                log.info("[Cody:Edit] Fix applied to $filePath")
            } catch (e: Exception) {
                log.warn("[Cody:Edit] Failed to apply fix: ${e.message}", e)
            }
        }
    }
}
