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
     * ellipsis (`…`) when truncation occurs. The returned string never
     * exceeds [maxLength] characters.
     *
     * Returns [text] unchanged when it already fits within [maxLength].
     */
    fun truncate(text: String, maxLength: Int): String {
        return if (text.length <= maxLength) text
        else text.substring(0, maxLength - 1) + "…"
    }

    /**
     * Zero-width / format characters that the LLM sometimes echoes back as a
     * "response" even though they carry no information.
     *
     * Includes U+200B (the placeholder we ourselves inject in
     * SourcegraphChatClient.sanitizeMessages to satisfy the API's
     * "content cannot be empty" rule for tool-call-only assistant turns),
     * the surrounding zero-width family (U+200C non-joiner, U+200D joiner),
     * U+FEFF byte-order mark, and U+00AD soft hyphen.
     */
    private val INVISIBLE_FORMAT_CHARS = Regex("[\\u200B-\\u200D\\uFEFF\\u00AD]")

    /** Strip zero-width / format characters from [content]. */
    fun stripInvisibleFormatChars(content: String): String =
        content.replace(INVISIBLE_FORMAT_CHARS, "")

    /**
     * Returns true when [content] is null, empty, whitespace-only, OR consists
     * entirely of zero-width / format characters once those are removed.
     *
     * String.isBlank() alone is insufficient: U+200B and friends are Unicode
     * category Cf (Format), not whitespace, so a U+200B-only string passes
     * !isBlank() and reads as "real content" — causing the agent loop to
     * dispatch echoed placeholder responses as text-only (Case B) and inject a
     * "[ERROR] You did not use a tool" nudge, priming the model to mimic the
     * pattern further. See AgentLoop.kt Case B vs Case C dispatch.
     */
    fun isEffectivelyBlank(content: String?): Boolean {
        if (content.isNullOrBlank()) return true
        return stripInvisibleFormatChars(content).isBlank()
    }
}
