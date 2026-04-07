package com.workflow.orchestrator.core.util

/**
 * HTML escaping utility for safely embedding user-provided text into HTML.
 *
 * Escapes the 5 characters that require escaping in HTML context:
 * `&`, `<`, `>`, `"`, `'`.
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
}
