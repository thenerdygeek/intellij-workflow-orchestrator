package com.workflow.orchestrator.cody.service

/**
 * Sanitizes text before sending to external AI services (Cody/Sourcegraph).
 * Redacts common sensitive patterns like API keys, passwords, tokens, and connection strings
 * to prevent accidental leakage of credentials in diffs, ticket descriptions, and file contents.
 */
object SensitiveContentSanitizer {

    private val REDACTION_PATTERNS: List<Pair<Regex, String>> = listOf(
        // Private keys (must be before shorter patterns to avoid partial matches)
        Regex("""-----BEGIN\s+(RSA\s+)?PRIVATE KEY-----[\s\S]*?-----END""")
            to "[REDACTED_PRIVATE_KEY]",

        // AWS keys
        Regex("""(?i)(AKIA|aws_access_key_id|aws_secret)\s*[:=]\s*\S+""")
            to "[REDACTED_AWS]",

        // API keys
        Regex("""(?i)(api[_-]?key|apikey)\s*[:=]\s*['"]?[\w-]{20,}""")
            to "[REDACTED_API_KEY]",

        // Passwords
        Regex("""(?i)(password|passwd|pwd)\s*[:=]\s*['"]?\S+""")
            to "[REDACTED_PASSWORD]",

        // Bearer tokens
        Regex("""Bearer\s+[\w.-]+""")
            to "Bearer [REDACTED]",

        // Connection strings (JDBC, MongoDB, Redis, AMQP)
        Regex("""(?i)(jdbc|mongodb|redis|amqp)://\S+""")
            to "[REDACTED_CONNECTION_STRING]"
    )

    /**
     * Redacts sensitive patterns from text before external transmission.
     * Email addresses are preserved as they provide useful context.
     */
    fun sanitizeForExternalTransmission(text: String): String {
        var result = text
        for ((pattern, replacement) in REDACTION_PATTERNS) {
            result = pattern.replace(result, replacement)
        }
        return result
    }
}
