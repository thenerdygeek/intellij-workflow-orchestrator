package com.workflow.orchestrator.agent.walkthrough

import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

/**
 * Combined platform-fixture coverage for the walkthrough editor layer.
 *
 * Deliberately ONE BasePlatformTestCase class with ONE test method. A second heavy
 * fixture *class* (not just a second method) sharing the test JVM wedges the next
 * class's setUp on `waitUntilIndexesAreReady` ("Indexing timeout") on loaded / headless
 * runners (issue #51; agent/CLAUDE.md "platform-fixture infra"), so the navigator and
 * validator checks are folded into one method rather than split into two fixture classes.
 *
 * The validator check never touches LocalFileSystem/disk: it injects a LightVirtualFile
 * resolver through the `validateSteps` seam (a real-disk refresh leaks UnindexedFilesScanner
 * work and makes the hang worse), matching the no-real-VFS spirit of EditFilePersistenceFixtureTest.
 */
class WalkthroughFixtureTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun `test navigator highlights one clamped range and validator rejects bad steps individually`() {
        // ── Navigator: highlightRange adds exactly one highlighter on our layer, clamps
        //    the end line to the document, swaps in place, and clearHighlight removes it.
        myFixture.configureByText("Sample.kt", "line1\nline2\nline3\n")
        val editor = myFixture.editor
        val navigator = WalkthroughNavigator(project)
        val ourLayer = HighlighterLayer.SELECTION - 1
        fun ourHighlighters() = editor.markupModel.allHighlighters.filter { it.layer == ourLayer }

        val before = ourHighlighters().size

        navigator.highlightRange(editor, startLine = 2, endLine = 99) // endLine clamps to last line

        val afterFirst = ourHighlighters()
        assertEquals(before + 1, afterFirst.size)
        val h = afterFirst.last()
        assertEquals(editor.document.getLineStartOffset(1), h.startOffset)
        assertTrue(h.endOffset <= editor.document.textLength)

        navigator.highlightRange(editor, 1, 1) // swap: still exactly one
        assertEquals(before + 1, ourHighlighters().size)

        navigator.clearHighlight()
        assertEquals(before, ourHighlighters().size)

        // ── Validator: a missing file and an out-of-bounds start line are rejected
        //    individually while the one valid step survives.
        val realFile = LightVirtualFile("Real.kt", "a\nb\nc\n")
        val resolver = { _: Project, path: String ->
            if (path == "Real.kt") realFile as VirtualFile else null
        }
        val steps = listOf(
            WalkthroughStep("Real.kt", 1, 3, null, "ok"), // valid
            WalkthroughStep("does/not/Exist.kt", 1, 1, null, "missing"), // file not found
            WalkthroughStep("Real.kt", 99, 100, null, "oob"), // start_line beyond EOF
        )
        val result = runBlocking { validateSteps(project, steps, resolver) }
        assertEquals(1, result.valid.size)
        assertEquals(2, result.errors.size)
        assertTrue(result.errors[0].contains("file not found"))
        assertTrue(result.errors[1].contains("start_line 99 exceeds"))
    }
}
