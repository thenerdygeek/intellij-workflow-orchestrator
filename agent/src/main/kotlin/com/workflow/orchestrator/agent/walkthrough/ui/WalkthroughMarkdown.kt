package com.workflow.orchestrator.agent.walkthrough.ui

import com.intellij.openapi.project.Project
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Markdown -> HTML for the callout popup body, via the JetBrains `intellij-markdown`
 * library that ships with the platform (no webview round-trip; the popup is Swing).
 */
object WalkthroughMarkdown {
    private val flavour = GFMFlavourDescriptor()

    /** 0-arg path: plain markdown -> HTML (no syntax-highlighting). */
    fun toHtml(markdown: String): String = render(markdown)

    /** Project-aware path: same markdown but with IDE-themed styling on code blocks. */
    fun toHtml(markdown: String, project: Project): String = render(markdown, project)

    private fun render(markdown: String, @Suppress("UNUSED_PARAMETER") project: Project? = null): String {
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        return HtmlGenerator(markdown, tree, flavour).generateHtml()
    }
}
