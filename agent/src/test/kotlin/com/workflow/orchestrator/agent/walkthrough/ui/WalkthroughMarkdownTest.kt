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

    @Test
    fun `emits an inline style block so code reads as code even without highlighting`() {
        // The 0-arg path (no Project) cannot lex-color, but it still tightens the box
        // with an inline <style> block — JEditorPane honors inline styles, ignores class=.
        val html = WalkthroughMarkdown.toHtml("Some prose with `inline code`.")
        assertTrue(html.contains("<style>"), html)
        assertTrue(html.contains("pre {") && html.contains("code {"), html)
        // a fenced block still surfaces its source text inside a <pre>
        val withFence = WalkthroughMarkdown.toHtml("```kotlin\nval y = 2\n```")
        assertTrue(withFence.contains("<pre>") && withFence.contains("val y = 2"), withFence)
    }
}
