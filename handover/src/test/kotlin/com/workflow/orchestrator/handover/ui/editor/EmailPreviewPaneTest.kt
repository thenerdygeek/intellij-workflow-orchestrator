package com.workflow.orchestrator.handover.ui.editor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import javax.swing.JEditorPane
import java.awt.Container

class EmailPreviewPaneTest {

    private fun walk(c: Container): List<java.awt.Component> = buildList {
        for (i in 0 until c.componentCount) {
            val child = c.getComponent(i)
            add(child)
            if (child is Container) addAll(walk(child))
        }
    }

    @Test
    fun `mounts a JEditorPane`() {
        val pane = EmailPreviewPane()
        val editor = walk(pane).filterIsInstance<JEditorPane>().firstOrNull()
        assertNotNull(editor)
    }

    @Test
    fun `setRenderedMarkup updates the editor text`() {
        val pane = EmailPreviewPane()
        val html = "<h1>Handover: AFTER8TE-912</h1><p>x</p>"
        pane.setRenderedMarkup(html)
        val editor = walk(pane).filterIsInstance<JEditorPane>().first()
        assertTrue(editor.text.contains("<h1>Handover: AFTER8TE-912</h1>"), editor.text)
    }

    @Test
    fun `editor uses text-html content type`() {
        val pane = EmailPreviewPane()
        val editor = walk(pane).filterIsInstance<JEditorPane>().first()
        assertEquals("text/html", editor.contentType.lowercase())
    }

    @Test
    fun `editor is read-only`() {
        val pane = EmailPreviewPane()
        val editor = walk(pane).filterIsInstance<JEditorPane>().first()
        assertFalse(editor.isEditable)
    }
}
