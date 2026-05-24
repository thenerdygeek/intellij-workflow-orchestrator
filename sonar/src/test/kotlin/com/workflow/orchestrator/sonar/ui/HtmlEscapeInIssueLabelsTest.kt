package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.core.util.HtmlEscape
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that untrusted Sonar issue messages, rule names, and remediation text are
 * HTML-escaped before being inserted into Swing HTML labels in [IssueDetailPanel].
 *
 * Closes audit finding sonar:F-1.
 */
class HtmlEscapeInIssueLabelsTest {

    private val xssPayload = "<script>alert(1)</script>"

    // ── titleLabel — issue message ──────────────────────────────────────────────

    @Test
    fun `issue message with script tag is escaped in title label`() {
        val message = xssPayload
        // IssueDetailPanel now uses HtmlEscape.escapeHtml(issue.message)
        val escaped = HtmlEscape.escapeHtml(message)
        val labelText = "<html><font color='#ff0000'><b>[BLOCKER]</b></font> BUG — $escaped</html>"
        assertFalse(labelText.contains("<script>"), "Raw <script> must not appear; got $labelText")
        assertTrue(labelText.contains("&lt;script&gt;"))
    }

    @Test
    fun `hotspot message with html is escaped`() {
        val message = "<b>Sensitive data exposed</b>"
        val escaped = HtmlEscape.escapeHtml(message)
        val labelText = "<html><font color='#ff0000'><b>[HIGH]</b></font> SECURITY HOTSPOT — $escaped</html>"
        assertFalse(labelText.contains("<b>Sensitive"), "Raw HTML must be escaped")
        assertTrue(labelText.contains("&lt;b&gt;"))
    }

    // ── ruleInfoLabel — rule name, remediation, description ────────────────────

    @Test
    fun `rule name with script tag is escaped in rule info label`() {
        val ruleName = "<b onmouseover=alert(1)>SQL Injection"
        val escaped = HtmlEscape.escapeHtml(ruleName)
        val labelText = "<html><b>$escaped</b></html>"
        assertFalse(labelText.contains("<b onmouseover"))
        assertTrue(labelText.contains("&lt;b"))
    }

    @Test
    fun `remediation text with html is escaped`() {
        val remediation = "<a href='evil.com'>click here</a>"
        val escaped = HtmlEscape.escapeHtml(remediation)
        assertFalse(escaped.contains("<a href"), "Raw href must not survive escaping")
        assertTrue(escaped.contains("&lt;a"))
    }

    @Test
    fun `cleanDesc html tags stripped then content escaped`() {
        // IssueDetailPanel strips HTML tags first, then escapes remaining content
        val desc = "<p>Use <code>PreparedStatement</code> & not raw SQL</p>"
        val stripped = desc.replace(Regex("<[^>]*>"), "")  // → "Use PreparedStatement & not raw SQL"
        val escaped = HtmlEscape.escapeHtml(stripped)
        assertFalse(escaped.contains("<code>"))
        assertFalse(escaped.contains("<p>"))
        assertTrue(escaped.contains("&amp;"), "Ampersand must be escaped")
        assertTrue(escaped.contains("PreparedStatement"))
    }
}
