package com.workflow.orchestrator.pullrequest.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownToHtmlTest {

    @Test
    fun `convert wraps output in html body tags with default style`() {
        val html = MarkdownToHtml.convert("hello")
        assertTrue(html.startsWith("<html><body style='"), "expected html/body wrapper")
        assertTrue(html.endsWith("</body></html>"), "expected closing tags")
    }

    @Test
    fun `convertFragment omits html and body tags`() {
        val html = MarkdownToHtml.convertFragment("hello")
        assertFalse(html.contains("<html>"), "fragment must not include html tag")
        assertFalse(html.contains("<body"), "fragment must not include body tag")
        assertTrue(html.contains("hello"), "fragment must still render content")
    }

    @Test
    fun `convertFragment and convert produce same body content`() {
        val md = "## Heading\n\nSome text with **bold** and *italic*."
        val fragment = MarkdownToHtml.convertFragment(md)
        val full = MarkdownToHtml.convert(md)
        assertTrue(full.contains(fragment), "full document must embed the fragment verbatim")
    }

    @Test
    fun `bold uses b tag`() {
        assertTrue(MarkdownToHtml.convertFragment("**strong**").contains("<b>strong</b>"))
    }

    @Test
    fun `italic uses i tag`() {
        assertTrue(MarkdownToHtml.convertFragment("*stressed*").contains("<i>stressed</i>"))
    }

    @Test
    fun `bold runs before italic so double-asterisk does not become double-italic`() {
        val html = MarkdownToHtml.convertFragment("**bold**")
        assertTrue(html.contains("<b>bold</b>"), "expected <b>bold</b> in: $html")
        assertFalse(html.contains("<i>"), "bold must not leave residual italic tags: $html")
    }

    @Test
    fun `mixed bold and italic in same line`() {
        val html = MarkdownToHtml.convertFragment("a **bold** and *italic* pair")
        assertTrue(html.contains("<b>bold</b>"))
        assertTrue(html.contains("<i>italic</i>"))
    }

    @Test
    fun `fenced code block escapes and wraps in pre code`() {
        val md = "```\n<html>\n```"
        val html = MarkdownToHtml.convertFragment(md)
        assertTrue(html.contains("<pre"), "expected <pre> wrapper")
        assertTrue(html.contains("&lt;html&gt;"), "code content must be html-escaped")
    }

    @Test
    fun `inline link is sanitized through sanitizeHref`() {
        val safe = MarkdownToHtml.convertFragment("[click](https://example.com)")
        assertTrue(safe.contains("href='https://example.com'"))

        val unsafe = MarkdownToHtml.convertFragment("[evil](javascript:alert(1))")
        assertTrue(unsafe.contains("href='#'"), "javascript: must be rejected to '#'")
    }

    @Test
    fun `heading levels convert`() {
        assertTrue(MarkdownToHtml.convertFragment("# h1").contains("<h1>h1</h1>"))
        assertTrue(MarkdownToHtml.convertFragment("## h2").contains("<h2"))
        assertTrue(MarkdownToHtml.convertFragment("### h3").contains("<h3>h3</h3>"))
    }

    @Test
    fun `unordered list groups items into single ul`() {
        val html = MarkdownToHtml.convertFragment("- one\n- two\n- three")
        val ulCount = "<ul".toRegex().findAll(html).count()
        val liCount = "<li>".toRegex().findAll(html).count()
        assertEquals(1, ulCount, "expected single <ul> wrapper")
        assertEquals(3, liCount, "expected three <li>")
    }

    @Test
    fun `html special chars are escaped in paragraph text`() {
        val html = MarkdownToHtml.convertFragment("a < b & c > d")
        assertTrue(html.contains("&lt;"))
        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("&gt;"))
    }
}
