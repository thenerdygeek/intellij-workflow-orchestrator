package com.workflow.orchestrator.agent.walkthrough

import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class WalkthroughNavigatorFixtureTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    // ONE test method per BasePlatformTestCase class — a second hangs on "Indexing timeout"
    // in headless CI (see agent/CLAUDE.md "platform-fixture infra").
    fun `test highlightRange adds one clamped lines-range highlighter and clearHighlight removes it`() {
        myFixture.configureByText("Sample.kt", "line1\nline2\nline3\n")
        val editor = myFixture.editor
        val navigator = WalkthroughNavigator(project)

        // Count only OUR layer to avoid pollution from platform-added highlighters on open.
        val ourLayer = HighlighterLayer.SELECTION - 1
        fun ourHighlighters() = editor.markupModel.allHighlighters.filter { it.layer == ourLayer }

        val before = ourHighlighters().size

        navigator.highlightRange(editor, startLine = 2, endLine = 99) // endLine clamps to 3 (or 4 w/ trailing newline)

        val afterFirst = ourHighlighters()
        assertEquals(before + 1, afterFirst.size)
        val h = afterFirst.last()
        assertEquals(editor.document.getLineStartOffset(1), h.startOffset)
        assertTrue(h.endOffset <= editor.document.textLength)

        navigator.highlightRange(editor, 1, 1) // swap: still exactly one
        assertEquals(before + 1, ourHighlighters().size)

        navigator.clearHighlight()
        assertEquals(before, ourHighlighters().size)
    }
}
