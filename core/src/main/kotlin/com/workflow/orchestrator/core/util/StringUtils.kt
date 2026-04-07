package com.workflow.orchestrator.core.util

/**
 * Small string helpers shared between feature modules.
 *
 * Anything more elaborate than a one-liner should live here so we don't end up
 * with eight slightly-different `truncate` implementations across the plugin.
 */
object StringUtils {

    /**
     * Truncates [text] to at most [maxLength] characters, appending a single
     * ellipsis (`\u2026`) when truncation occurs. The returned string never
     * exceeds [maxLength] characters.
     *
     * Returns [text] unchanged when it already fits within [maxLength].
     */
    fun truncate(text: String, maxLength: Int): String {
        return if (text.length <= maxLength) text
        else text.substring(0, maxLength - 1) + "\u2026"
    }
}
