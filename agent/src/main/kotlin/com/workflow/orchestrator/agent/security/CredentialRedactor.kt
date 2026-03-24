package com.workflow.orchestrator.agent.security

/**
 * Redacts known credential patterns from text before it is displayed to the user
 * or persisted to trace/event log files.
 *
 * This is a defense-in-depth measure: even if the LLM leaks a credential in its
 * output, the user and disk logs never see the raw value.
 */
object CredentialRedactor {
    private val PATTERNS = listOf(
        // Full private key blocks (PEM)
        Regex("-----BEGIN [A-Z ]+KEY-----[\\s\\S]*?-----END [A-Z ]+KEY-----") to "[REDACTED: private key]",
        // EC / DSA / OPENSSH private key headers (catch partial blocks too)
        Regex("-----BEGIN\\s+(EC|DSA|OPENSSH)\\s+PRIVATE KEY-----") to "[REDACTED_PRIVATE_KEY]",
        // JWT tokens (header.payload.signature)
        Regex("eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+") to "[REDACTED_JWT]",
        // Bearer tokens in text
        Regex("Bearer\\s+[A-Za-z0-9_.-]{20,}") to "Bearer [REDACTED]",
        // AWS access key IDs
        Regex("AKIA[0-9A-Z]{16}") to "[REDACTED: AWS key]",
        // Azure keys/secrets/tokens
        Regex("(?i)(azure|az)[_-]?(key|secret|token)\\s*[:=]\\s*['\"]?\\S{20,}") to "[REDACTED_AZURE]",
        // GitHub tokens
        Regex("ghp_[a-zA-Z0-9]{36}") to "[REDACTED: GitHub token]",
        // GitLab tokens
        Regex("glpat-[A-Za-z0-9_-]{20,}") to "[REDACTED_GITLAB]",
        // Slack tokens
        Regex("xox[bpsa]-[A-Za-z0-9-]{10,}") to "[REDACTED_SLACK]",
        // Sourcegraph tokens
        Regex("sgp_[a-fA-F0-9]{40,}") to "[REDACTED: Sourcegraph token]",
        // Generic API secret keys
        Regex("sk-[a-zA-Z0-9]{32,}") to "[REDACTED: API key]",
    )

    /**
     * Replace all known credential patterns with safe placeholders.
     *
     * @param text The text that may contain credentials
     * @return The text with all matched credentials replaced
     */
    fun redact(text: String): String {
        var result = text
        for ((pattern, replacement) in PATTERNS) {
            result = pattern.replace(result, replacement)
        }
        return result
    }
}
