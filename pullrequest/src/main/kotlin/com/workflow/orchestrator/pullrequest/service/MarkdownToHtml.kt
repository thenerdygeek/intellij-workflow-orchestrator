package com.workflow.orchestrator.pullrequest.service

import com.workflow.orchestrator.core.util.HtmlEscape

/**
 * Simple markdown-to-HTML converter for the PR description Preview tab.
 * Handles the subset of markdown that Bitbucket Server renders.
 */
object MarkdownToHtml {

    fun convert(markdown: String): String {
        val lines = markdown.lines()
        val html = StringBuilder()
        html.append("<html><body style='font-family: sans-serif; font-size: 12px; line-height: 1.6; padding: 8px;'>")

        var inCodeBlock = false
        var inList = false

        for (line in lines) {
            // Fenced code blocks
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    html.append("</code></pre>")
                    inCodeBlock = false
                } else {
                    if (inList) { html.append("</ul>"); inList = false }
                    html.append("<pre style='background:#f0f0f0; padding:8px; border-radius:4px; overflow-x:auto;'><code>")
                    inCodeBlock = true
                }
                continue
            }
            if (inCodeBlock) {
                html.append(HtmlEscape.escapeHtml(line)).append("\n")
                continue
            }

            // Empty line closes list
            if (line.isBlank()) {
                if (inList) { html.append("</ul>"); inList = false }
                html.append("<br>")
                continue
            }

            // Headings
            if (line.startsWith("## ")) {
                if (inList) { html.append("</ul>"); inList = false }
                html.append("<h2 style='border-bottom:1px solid #eee; padding-bottom:4px;'>")
                    .append(inlineFormat(line.removePrefix("## ")))
                    .append("</h2>")
                continue
            }
            if (line.startsWith("# ")) {
                if (inList) { html.append("</ul>"); inList = false }
                html.append("<h1>").append(inlineFormat(line.removePrefix("# "))).append("</h1>")
                continue
            }
            if (line.startsWith("### ")) {
                if (inList) { html.append("</ul>"); inList = false }
                html.append("<h3>").append(inlineFormat(line.removePrefix("### "))).append("</h3>")
                continue
            }

            // Unordered list items
            val listMatch = Regex("^\\s*[-*]\\s+(.+)").find(line)
            if (listMatch != null) {
                if (!inList) { html.append("<ul style='margin:4px 0; padding-left:20px;'>"); inList = true }
                html.append("<li>").append(inlineFormat(listMatch.groupValues[1])).append("</li>")
                continue
            }

            // Regular paragraph
            if (inList) { html.append("</ul>"); inList = false }
            html.append("<p style='margin:4px 0;'>").append(inlineFormat(line)).append("</p>")
        }

        if (inList) html.append("</ul>")
        if (inCodeBlock) html.append("</code></pre>")
        html.append("</body></html>")

        return html.toString()
    }

    private fun inlineFormat(text: String): String {
        var result = HtmlEscape.escapeHtml(text)
        // Bold
        result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        // Inline code
        result = result.replace(Regex("`([^`]+)`"), "<code style='background:#f0f0f0; padding:1px 4px; border-radius:2px;'>$1</code>")
        // Links
        result = result.replace(Regex("\\[([^]]+)]\\(([^)]+)\\)")) { match ->
            val text = match.groupValues[1]
            val url = sanitizeHref(match.groupValues[2])
            "<a href='$url'>$text</a>"
        }
        // Checkmarks
        result = result.replace("✅", "&#10004;")
        return result
    }

    private fun sanitizeHref(url: String): String {
        val trimmed = url.trim().lowercase()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) url else "#"
    }
}
