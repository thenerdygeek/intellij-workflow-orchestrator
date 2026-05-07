package com.workflow.orchestrator.core.util

/**
 * HTML escape / unescape utility for handling HTML-encoded text.
 *
 * Handles the 5 entities that require escaping in HTML context:
 * `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&#39;` / `&apos;`.
 *
 * Suitable for both HTML body content and attribute values.
 */
object HtmlEscape {

    /**
     * Escapes HTML special characters in [text].
     *
     * Handles: `&` `<` `>` `"` `'`. Safe for use in body text and attribute values.
     * The `&` replacement runs first to avoid double-escaping.
     */
    fun escapeHtml(text: String): String {
        if (text.isEmpty()) return text
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /**
     * Unescapes the 5 named/numeric HTML entities produced by [escapeHtml]
     * plus `&apos;` (the alternate form of `&#39;` that some servers emit).
     *
     * The `&amp;` replacement runs LAST to avoid double-unescaping a
     * sequence like `&amp;lt;` (which should become `&lt;`, not `<`).
     */
    fun unescapeHtml(text: String): String {
        if (text.isEmpty()) return text
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
