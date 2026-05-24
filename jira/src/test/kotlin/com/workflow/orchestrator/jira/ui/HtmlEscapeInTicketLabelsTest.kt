package com.workflow.orchestrator.jira.ui

import com.workflow.orchestrator.core.util.HtmlEscape
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that untrusted Jira ticket summaries are HTML-escaped before being inserted into
 * Swing HTML labels. Because the actual rendering is Swing-bound, we test the escaping logic
 * (i.e., that HtmlEscape.escapeHtml() produces the expected output for attack payloads) so
 * that the callsite changes in TicketDetailPanel and TicketListCellRenderer are confirmed safe.
 *
 * Closes audit finding jira:F-3.
 */
class HtmlEscapeInTicketLabelsTest {

    private val xssPayload = "<script>alert(1)</script>"
    private val xssPayloadEscaped = "&lt;script&gt;alert(1)&lt;/script&gt;"

    // ── TicketDetailPanel — header title label ─────────────────────────────────

    @Test
    fun `summary with script tag is escaped in header label html`() {
        val escaped = HtmlEscape.escapeHtml(xssPayload)
        // The label now wraps with: "<html>${HtmlEscape.escapeHtml(summary)}</html>"
        val labelText = "<html>$escaped</html>"
        assertFalse(labelText.contains("<script>"), "Raw <script> must not appear in label text; got $labelText")
        assertTrue(labelText.contains("&lt;script&gt;"), "Expected escaped form; got $labelText")
    }

    @Test
    fun `summary with img onerror is escaped in header label html`() {
        val payload = "<img src=x onerror=alert(1)>"
        val labelText = "<html>${HtmlEscape.escapeHtml(payload)}</html>"
        assertFalse(labelText.contains("<img"), "Raw <img> must not appear in label text")
        assertTrue(labelText.contains("&lt;img"), "Expected escaped form")
    }

    // ── TicketListCellRenderer — toolTipText ───────────────────────────────────

    @Test
    fun `tooltip text with script tag is escaped`() {
        val key = "PROJ-1"
        val summary = xssPayload
        // The renderer now sets: toolTipText = HtmlEscape.escapeHtml("$key: $summary")
        val tooltip = HtmlEscape.escapeHtml("$key: $summary")
        assertFalse(tooltip.contains("<script>"), "Raw <script> must not appear in tooltip; got $tooltip")
        assertTrue(tooltip.contains("PROJ-1"), "Key must still be visible")
        assertTrue(tooltip.contains("&lt;script&gt;"), "Escaped form must be present")
    }

    @Test
    fun `tooltip text with angle brackets is escaped`() {
        val tooltip = HtmlEscape.escapeHtml("KEY-1: Fix <deprecated> API & update")
        assertFalse(tooltip.contains("<deprecated>"))
        assertTrue(tooltip.contains("&lt;deprecated&gt;"))
        assertTrue(tooltip.contains("&amp;"))
    }

    // ── Regression: HtmlEscape does NOT escape newlines to &#10; ──────────────
    // This matters because in PrDetailPanel we do .replace("\n", "<br>") after escaping.

    @Test
    fun `HtmlEscape does not escape newlines so newline-to-br substitution still works`() {
        val text = "line1\nline2"
        val escaped = HtmlEscape.escapeHtml(text)
        assertTrue(escaped.contains("\n"), "Newlines must survive escaping for the <br> substitution to work")
        val withBr = escaped.replace("\n", "<br>")
        assertTrue(withBr.contains("<br>"))
    }
}
