package com.workflow.orchestrator.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HtmlEscapeTest {

    @Test
    fun `empty input returned unchanged`() {
        assertEquals("", HtmlEscape.escapeHtml(""))
    }

    @Test
    fun `plain text without specials returned unchanged`() {
        assertEquals("hello world", HtmlEscape.escapeHtml("hello world"))
    }

    @Test
    fun `ampersand escapes to amp`() {
        assertEquals("a &amp; b", HtmlEscape.escapeHtml("a & b"))
    }

    @Test
    fun `ampersand escapes first to avoid double-escape`() {
        assertEquals("&amp;lt;", HtmlEscape.escapeHtml("&lt;"))
    }

    @Test
    fun `less-than escapes to lt`() {
        assertEquals("a &lt; b", HtmlEscape.escapeHtml("a < b"))
    }

    @Test
    fun `greater-than escapes to gt`() {
        assertEquals("a &gt; b", HtmlEscape.escapeHtml("a > b"))
    }

    @Test
    fun `double-quote escapes to quot`() {
        assertEquals("a &quot;b&quot; c", HtmlEscape.escapeHtml("a \"b\" c"))
    }

    @Test
    fun `single-quote escapes to numeric entity`() {
        assertEquals("it&#39;s", HtmlEscape.escapeHtml("it's"))
    }

    @Test
    fun `all five special chars together`() {
        assertEquals(
            "&amp; &lt; &gt; &quot; &#39;",
            HtmlEscape.escapeHtml("& < > \" '")
        )
    }

    @Test
    fun `newlines are NOT escaped by this helper`() {
        assertEquals("a\nb", HtmlEscape.escapeHtml("a\nb"))
    }

    @Test
    fun `tag-like input fully escaped`() {
        assertEquals(
            "&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;",
            HtmlEscape.escapeHtml("<script>alert('x')</script>")
        )
    }
}
