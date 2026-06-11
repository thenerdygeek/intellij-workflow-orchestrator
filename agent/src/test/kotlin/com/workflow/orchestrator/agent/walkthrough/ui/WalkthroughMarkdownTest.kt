package com.workflow.orchestrator.agent.walkthrough.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WalkthroughMarkdownTest {
    @Test
    fun `renders bold, inline code, and lists`() {
        val html = WalkthroughMarkdown.toHtml("This is **bold**, `code`, and:\n- item one\n- item two")
        assertTrue(html.contains("<strong>bold</strong>"), html)
        assertTrue(html.contains("<code>code</code>"), html)
        assertTrue(html.contains("<li>"), html)
    }

    @Test
    fun `renders fenced code blocks`() {
        val html = WalkthroughMarkdown.toHtml("```kotlin\nval x = 1\n```")
        assertTrue(html.contains("<pre>"), html)
        assertTrue(html.contains("val x = 1"), html)
    }
}
