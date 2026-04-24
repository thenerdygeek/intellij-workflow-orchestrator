package com.workflow.orchestrator.pullrequest.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarkdownToHtmlSanitizeHrefTest {

    @Test
    fun `http url passes through`() {
        assertEquals("http://example.com/x", MarkdownToHtml.sanitizeHref("http://example.com/x"))
    }

    @Test
    fun `https url passes through`() {
        assertEquals("https://example.com/x", MarkdownToHtml.sanitizeHref("https://example.com/x"))
    }

    @Test
    fun `uppercase scheme accepted and preserved original case`() {
        assertEquals("HTTPS://X.Y/Z", MarkdownToHtml.sanitizeHref("HTTPS://X.Y/Z"))
    }

    @Test
    fun `javascript scheme rejected`() {
        assertEquals("#", MarkdownToHtml.sanitizeHref("javascript:alert(1)"))
    }

    @Test
    fun `file scheme rejected`() {
        assertEquals("#", MarkdownToHtml.sanitizeHref("file:///etc/passwd"))
    }

    @Test
    fun `ftp scheme rejected`() {
        assertEquals("#", MarkdownToHtml.sanitizeHref("ftp://x.y/z"))
    }

    @Test
    fun `data scheme rejected`() {
        assertEquals("#", MarkdownToHtml.sanitizeHref("data:text/html,<script>"))
    }

    @Test
    fun `empty string rejected`() {
        assertEquals("#", MarkdownToHtml.sanitizeHref(""))
    }

    @Test
    fun `whitespace-surrounded http url accepted`() {
        assertEquals("  http://x  ", MarkdownToHtml.sanitizeHref("  http://x  "))
    }
}
