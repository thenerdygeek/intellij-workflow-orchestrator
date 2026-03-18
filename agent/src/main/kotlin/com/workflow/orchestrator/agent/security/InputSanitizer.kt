package com.workflow.orchestrator.agent.security

import com.workflow.orchestrator.agent.context.TokenEstimator

/**
 * Sanitizes external data (Jira tickets, Bamboo logs, SonarQube issues, etc.)
 * before injecting into LLM prompts. Wraps content in boundary tags to mark
 * it as untrusted, strips dangerous control characters, and enforces token limits.
 */
object InputSanitizer {

    /**
     * Sanitizes external data for safe inclusion in LLM prompts.
     *
     * - Strips Unicode C0 (U+0000..U+001F) and C1 (U+0080..U+009F) control characters,
     *   except newline (U+000A) and tab (U+0009) which are legitimate in code.
     * - Wraps content in `<external_data>` boundary tags with source metadata and
     *   UNTRUSTED warning so the system prompt can instruct the LLM to treat it carefully.
     * - Truncates content if the token estimate exceeds [maxTokens].
     *
     * @param content Raw content from the external service
     * @param source Service name (e.g., "jira", "bamboo", "sonar")
     * @param key Identifier (e.g., ticket key, build key)
     * @param maxTokens Maximum token budget for the content (excluding wrapper tags)
     * @return Sanitized and wrapped content string
     */
    fun sanitizeExternalData(
        content: String,
        source: String,
        key: String,
        maxTokens: Int = 3000
    ): String {
        val sanitizedContent = stripControlCharacters(content)
        val truncatedContent = truncateToTokenLimit(sanitizedContent, maxTokens)
        val safeSource = escapeAttribute(source)
        val safeKey = escapeAttribute(key)
        return "<external_data source=\"$safeSource\" key=\"$safeKey\" warning=\"UNTRUSTED\">" +
            truncatedContent +
            "</external_data>"
    }

    /**
     * Strips C0 control characters (U+0000..U+001F) except newline and tab,
     * and C1 control characters (U+0080..U+009F).
     */
    private fun stripControlCharacters(text: String): String {
        return buildString(text.length) {
            for (ch in text) {
                when {
                    ch == '\n' || ch == '\t' -> append(ch)
                    ch.code in 0x00..0x1F -> {} // Skip C0 control chars
                    ch.code in 0x80..0x9F -> {} // Skip C1 control chars
                    else -> append(ch)
                }
            }
        }
    }

    /**
     * Truncates content to fit within the given token budget.
     * Uses character-based truncation derived from TokenEstimator's ratio.
     */
    private fun truncateToTokenLimit(content: String, maxTokens: Int): String {
        val estimatedTokens = TokenEstimator.estimate(content)
        if (estimatedTokens <= maxTokens) return content

        // Approximate character limit from token budget (3.5 chars per token)
        val maxChars = (maxTokens * 3.5).toInt()
        val truncated = content.take(maxChars)
        return "$truncated\n[TRUNCATED: content exceeded $maxTokens token limit]"
    }

    /**
     * Escapes double quotes in attribute values to prevent tag injection.
     */
    private fun escapeAttribute(value: String): String {
        return value.replace("\"", "&quot;")
    }
}
