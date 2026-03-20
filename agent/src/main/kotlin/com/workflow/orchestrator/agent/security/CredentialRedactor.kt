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
        Regex("-----BEGIN [A-Z ]+KEY-----[\\s\\S]*?-----END [A-Z ]+KEY-----") to "[REDACTED: private key]",
        Regex("AKIA[0-9A-Z]{16}") to "[REDACTED: AWS key]",
        Regex("ghp_[a-zA-Z0-9]{36}") to "[REDACTED: GitHub token]",
        Regex("sgp_[a-fA-F0-9]{40,}") to "[REDACTED: Sourcegraph token]",
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
