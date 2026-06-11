package com.workflow.orchestrator.agent.walkthrough.ui

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Markdown -> HTML for the callout popup body, via the JetBrains `intellij-markdown`
 * library that ships with the platform (no webview round-trip; the popup is Swing).
 */
object WalkthroughMarkdown {
    private val flavour = GFMFlavourDescriptor()

    fun toHtml(markdown: String): String {
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        return HtmlGenerator(markdown, tree, flavour).generateHtml()
    }
}
