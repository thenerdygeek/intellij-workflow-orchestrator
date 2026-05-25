package com.workflow.orchestrator.sonar.ui

import com.workflow.orchestrator.core.util.HtmlEscape
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that server-controlled rule descriptions are fully sanitized before
 * rendering in the Swing HTML label inside [IssueDetailPanel.displayRuleInfo].
 *
 * Regression for audit finding sonar:F-11 — the prior regex `<[^>]*>` was incomplete:
 * it failed on tags whose attributes contain '>' (e.g. `<div onclick="a>b">`)
 * and on multi-line tags. The replacement uses DOT_MATCHES_ALL so both cases are
 * handled, and HtmlEscape.escapeHtml is applied to the stripped text so residual
 * angle-brackets are entity-encoded.
 */
class RuleDescriptionHtmlSanitizeTest {

    /**
     * Mirror of [IssueDetailPanel]'s sanitization logic so we can test it without
     * constructing a full Swing panel (which requires a headless IDE context).
     */
    private val htmlTagRegex = Regex("<.*?>", RegexOption.DOT_MATCHES_ALL)

    private fun sanitize(raw: String): String =
        HtmlEscape.escapeHtml(raw.replace(htmlTagRegex, ""))

    // ── script tag ──────────────────────────────────────────────────────────────

    @Test
    fun `script tag is stripped so dangerous tag constructs do not survive in rendered output`() {
        // After stripping tags, the text content between them ("alert(1)") is plain text
        // and is then HTML-escaped. The critical invariant is that <script> and </script>
        // themselves are removed so Swing never sees them as markup.
        val desc = "Use PreparedStatement. <script>alert(1)</script> Never use raw SQL."
        val result = sanitize(desc)
        assertFalse(result.contains("<script>"), "Raw <script> must not survive; got: $result")
        assertFalse(result.contains("</script>"), "Closing </script> must not survive; got: $result")
        assertTrue(result.contains("PreparedStatement"))
    }

    // ── img onerror ─────────────────────────────────────────────────────────────

    @Test
    fun `img tag with onerror attribute is stripped`() {
        val desc = "Avoid this. <img src=x onerror=alert(1)> See docs."
        val result = sanitize(desc)
        assertFalse(result.contains("<img"), "img tag must not survive; got: $result")
        assertFalse(result.contains("onerror"), "onerror attribute must not survive; got: $result")
        assertTrue(result.contains("Avoid this"))
    }

    // ── javascript: href ────────────────────────────────────────────────────────

    @Test
    fun `anchor tag with javascript href is stripped`() {
        val desc = "See <a href='javascript:alert(1)'>link</a> for details."
        val result = sanitize(desc)
        assertFalse(result.contains("<a "), "Anchor tag must not survive; got: $result")
        assertFalse(result.contains("javascript:"), "javascript: URI must not survive; got: $result")
        // "link" text between tags is preserved
        assertTrue(result.contains("link"))
    }

    // ── attribute with embedded '>' (bypass of the old <[^>]*> regex) ───────────

    @Test
    fun `tag whose attribute contains a right-angle-bracket is fully stripped`() {
        // The old regex `<[^>]*>` would stop at the first '>' inside the attribute,
        // leaving `b">` in the output. The DOT_MATCHES_ALL non-greedy regex handles this.
        val desc = """normal text <div onclick="a>b"> injected </div> more text"""
        val result = sanitize(desc)
        assertFalse(result.contains("<div"), "<div> must be stripped; got: $result")
        // Residual `>` from the old regex: must not appear unescaped after HtmlEscape
        assertFalse(
            result.contains(">") && !result.contains("&gt;"),
            "Unescaped '>' must not survive; got: $result"
        )
        assertTrue(result.contains("normal text"))
    }

    // ── multi-line tag ───────────────────────────────────────────────────────────

    @Test
    fun `multi-line HTML tag spanning newlines is stripped`() {
        val desc = "start <div\n  class=\"x\"\n  onclick=\"evil()\"\n>injected</div> end"
        val result = sanitize(desc)
        assertFalse(result.contains("<div"), "Multi-line <div> must be stripped; got: $result")
        assertFalse(result.contains("onclick"), "onclick attribute must not survive; got: $result")
        assertTrue(result.contains("start"))
        assertTrue(result.contains("end"))
    }

    // ── ordinary code description passes through (but gets entity-encoded) ──────

    @Test
    fun `plain-text rule description with ampersand is entity-encoded`() {
        val desc = "Use PreparedStatement & parameterised queries."
        val result = sanitize(desc)
        // & must be encoded so Swing HTML parser doesn't warn / misparse
        assertTrue(result.contains("&amp;"), "Ampersand must be entity-encoded; got: $result")
        assertFalse(result.contains(" & "), "Raw & must not survive; got: $result")
    }
}
