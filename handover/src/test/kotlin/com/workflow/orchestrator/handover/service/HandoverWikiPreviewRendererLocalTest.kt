package com.workflow.orchestrator.handover.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class HandoverWikiPreviewRendererLocalTest {

    private fun render(input: String): String = HandoverWikiPreviewRenderer.local(input)

    @Test fun `h2 renders to h2 element`() {
        assertEquals("<h2>Hello</h2>", render("h2. Hello"))
    }

    @Test fun `h1 and h3 also render`() {
        assertEquals("<h1>Top</h1>", render("h1. Top"))
        assertEquals("<h3>Sub</h3>", render("h3. Sub"))
    }

    @Test fun `bold and italic inline`() {
        // Wrap in p because the line isn't a header/table/code block
        assertEquals("<p><b>x</b> <i>y</i></p>", render("*x* _y_"))
    }

    @Test fun `color span`() {
        assertEquals("<p><span style=\"color:red\">danger</span></p>", render("{color:red}danger{color}"))
    }

    @Test fun `link with label and url`() {
        assertEquals("""<p><a href="https://example.com">click</a></p>""",
            render("[click|https://example.com]"))
    }

    @Test fun `wiki table renders to html table`() {
        val input = """
            ||Suite||Result||
            |ORCH-API-SMOKE|PASS|
            |ORCH-WEB-E2E|running|
        """.trimIndent()
        val output = render(input)
        assertTrue(output.contains("<table>"), "expected <table>: $output")
        assertTrue(output.contains("<tr><th>Suite</th><th>Result</th></tr>"))
        assertTrue(output.contains("<tr><td>ORCH-API-SMOKE</td><td>PASS</td></tr>"))
        assertTrue(output.contains("<tr><td>ORCH-WEB-E2E</td><td>running</td></tr>"))
        assertTrue(output.contains("</table>"))
    }

    @Test fun `code block`() {
        val input = "{code:kotlin}\nval x = 1\nval y = 2\n{code}"
        val output = render(input)
        assertTrue(output.contains("<pre><code class=\"kotlin\">"), output)
        assertTrue(output.contains("val x = 1"), output)
        assertTrue(output.contains("val y = 2"), output)
        assertTrue(output.contains("</code></pre>"), output)
    }

    @Test fun `passthrough for unrecognized markup`() {
        // Plain text → wrapped in <p>
        assertEquals("<p>just plain text</p>", render("just plain text"))
    }

    @Test fun `mixed content with header, paragraph, and bold`() {
        val input = """
            h2. Handover

            This is *important*.
        """.trimIndent()
        val output = render(input)
        assertTrue(output.contains("<h2>Handover</h2>"), output)
        assertTrue(output.contains("<p>This is <b>important</b>.</p>"), output)
    }

    @Test fun `empty input renders to empty string`() {
        assertEquals("", render(""))
    }

    @Test fun `multiple paragraphs separated by blank lines`() {
        val output = render("first paragraph\n\nsecond paragraph")
        assertTrue(output.contains("<p>first paragraph</p>"))
        assertTrue(output.contains("<p>second paragraph</p>"))
    }

    @Test fun `code block content escapes ampersand and less-than`() {
        val input = "{code:text}\nfoo & bar < baz\n{code}"
        val output = render(input)
        assertTrue(output.contains("foo &amp; bar &lt; baz"), "expected escaped content in: $output")
        assertFalse(output.contains("foo & bar < baz"), "raw ampersand/lt must not appear: $output")
    }

    @Test fun `wiki table cell with ampersand is escaped`() {
        val input = "|a & b|c < d|"
        val output = render(input)
        assertTrue(output.contains("a &amp; b"), "expected &amp; in: $output")
        assertTrue(output.contains("c &lt; d"), "expected &lt; in: $output")
    }
}
