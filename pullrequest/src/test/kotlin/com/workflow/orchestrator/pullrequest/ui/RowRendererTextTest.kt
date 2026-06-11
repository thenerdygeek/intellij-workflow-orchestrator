package com.workflow.orchestrator.pullrequest.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure text-derivation helpers extracted from [FindingRowRenderer] and
 * [CommentRowRenderer] during the P1-20 rubber-stamp rewrite. The renderers now reuse one
 * component tree, so these helpers carry the per-cell text/HTML contract that used to live
 * inline in the per-paint allocation path.
 */
class RowRendererTextTest {

    // ------------------------------------------------------------------
    // FindingRowRenderer
    // ------------------------------------------------------------------

    @Test
    fun `finding anchor renders file colon line, bare file, or general fallback`() {
        assertEquals("src/A.kt:42", FindingRowRenderer.anchorText("src/A.kt", 42))
        assertEquals("src/A.kt", FindingRowRenderer.anchorText("src/A.kt", null))
        assertEquals("general", FindingRowRenderer.anchorText(null, 42))
        assertEquals("general", FindingRowRenderer.anchorText(null, null))
    }

    @Test
    fun `finding body escapes html and converts newlines to br`() {
        val html = FindingRowRenderer.bodyHtml("a < b\nnext")

        assertTrue(html.startsWith("<html>") && html.endsWith("</html>"))
        assertTrue(html.contains("a &lt; b"), "raw '<' must be escaped, got: $html")
        assertTrue(html.contains("<br>next"), "newline must become <br>, got: $html")
        assertFalse(html.contains("\n"))
    }

    @Test
    fun `finding body truncates to 600 chars before escaping`() {
        val message = "x".repeat(700)

        val html = FindingRowRenderer.bodyHtml(message)

        assertEquals("<html>${"x".repeat(600)}</html>", html)
    }

    // ------------------------------------------------------------------
    // CommentRowRenderer
    // ------------------------------------------------------------------

    @Test
    fun `comment anchor renders path with optional line`() {
        assertEquals("src/B.kt:7", CommentRowRenderer.anchorText("src/B.kt", 7))
        assertEquals("src/B.kt", CommentRowRenderer.anchorText("src/B.kt", null))
    }

    @Test
    fun `comment body escapes html, converts newlines, and truncates to 500 chars`() {
        val html = CommentRowRenderer.bodyHtml("<b>hi</b>\nthere")
        assertTrue(html.contains("&lt;b&gt;hi&lt;/b&gt;"), "tags must be escaped, got: $html")
        assertTrue(html.contains("<br>there"))

        val long = CommentRowRenderer.bodyHtml("y".repeat(700))
        assertEquals("<html>${"y".repeat(500)}</html>", long)
    }
}
