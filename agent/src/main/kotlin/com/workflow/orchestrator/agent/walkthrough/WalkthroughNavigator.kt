package com.workflow.orchestrator.agent.walkthrough

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * EDT helper: open the step's file, scroll its range to center, and own the ONE
 * active RangeHighlighter (disposed on step change / tour end). All methods EDT-only.
 */
class WalkthroughNavigator(private val project: Project) {

    private var activeHighlighter: RangeHighlighter? = null

    /** @return the editor showing the step, or null when the file no longer resolves. */
    fun showStep(step: WalkthroughStep): Editor? {
        val vfile = resolveStepFile(project, step.file) ?: return null
        val editor = FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, vfile, step.startLine - 1, 0), true)
            ?: return null
        highlightRange(editor, step.startLine, step.endLine)
        editor.caretModel.moveToLogicalPosition(LogicalPosition(step.startLine - 1, 0))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        return editor
    }

    internal fun highlightRange(editor: Editor, startLine: Int, endLine: Int) {
        clearHighlight()
        val doc = editor.document
        val (start, end) = clampLineRange(startLine, endLine, doc.lineCount)
        val attributes = TextAttributes().apply {
            backgroundColor = HIGHLIGHT_COLOR
        }
        activeHighlighter = editor.markupModel.addRangeHighlighter(
            doc.getLineStartOffset(start - 1),
            doc.getLineEndOffset(end - 1),
            HighlighterLayer.SELECTION - 1,
            attributes,
            HighlighterTargetArea.LINES_IN_RANGE,
        )
    }

    fun clearHighlight() {
        activeHighlighter?.let { if (it.isValid) it.dispose() }
        activeHighlighter = null
    }

    private companion object {
        /** Selection-blue at ~18% alpha; same value both themes (JBColor keeps it LAF-safe). */
        val HIGHLIGHT_COLOR = JBColor(Color(0x56, 0x8A, 0xF2, 46), Color(0x56, 0x8A, 0xF2, 46))
    }
}

/**
 * Pure 1-based line-range clamp (no platform deps, unit-testable): `start` into `[1, lineCount]`,
 * `end` into `[start, lineCount]`. `lineCount` is floored at 1 so an empty document still yields a
 * valid 1..1 range. Extracted from [WalkthroughNavigator.highlightRange].
 */
internal fun clampLineRange(startLine: Int, endLine: Int, lineCount: Int): Pair<Int, Int> {
    val lastLine = lineCount.coerceAtLeast(1)
    val start = startLine.coerceIn(1, lastLine)
    val end = endLine.coerceIn(start, lastLine)
    return start to end
}
