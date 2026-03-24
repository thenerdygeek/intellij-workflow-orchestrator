package com.workflow.orchestrator.agent.security

/**
 * Exception thrown when [OutputValidator.validateOrThrow] detects security-sensitive
 * content in LLM output.
 *
 * @param issues Human-readable descriptions of each detected violation
 */
class SecurityViolationException(
    val issues: List<String>
) : RuntimeException("Security violations detected: ${issues.joinToString("; ")}")

/**
 * Validates LLM output before presenting it to the user or applying it to files.
 * Detects potential credential leakage, sensitive file references, and other
 * security-concerning patterns in generated content.
 */
object OutputValidator {

    private data class SensitivePattern(
        val regex: Regex,
        val description: String
    )

    private val SENSITIVE_PATTERNS: List<SensitivePattern> = listOf(
        // SSH / PGP private keys
        SensitivePattern(
            Regex("-----BEGIN\\s+[A-Z\\s]*PRIVATE KEY-----"),
            "Output contains a private key header"
        ),

        // AWS access key IDs (starts with AKIA, 20 alphanumeric chars)
        SensitivePattern(
            Regex("AKIA[0-9A-Z]{16}"),
            "Output contains a potential AWS access key ID"
        ),

        // Sensitive file paths
        SensitivePattern(
            Regex("~/.ssh/", RegexOption.LITERAL),
            "Output references sensitive SSH directory (~/.ssh/)"
        ),
        SensitivePattern(
            Regex("~/.aws/credentials", RegexOption.LITERAL),
            "Output references AWS credentials file"
        ),
        SensitivePattern(
            Regex("(?<![\\w/])\\.env(?![\\w.])"),
            "Output references .env file"
        ),

        // Password/secret/token assignment with actual values
        // Matches PASSWORD=<value>, SECRET=<value>, TOKEN=<value>
        // but not password = getPassword() or similar function calls
        SensitivePattern(
            Regex("(?:PASSWORD|PASSWD|DB_PASSWORD|MYSQL_PASSWORD)=[^(\\s]\\S+", RegexOption.IGNORE_CASE),
            "Output contains a password assignment with a value"
        ),
        SensitivePattern(
            Regex("(?:SECRET|SECRET_KEY|API_SECRET)=[^(\\s]\\S+", RegexOption.IGNORE_CASE),
            "Output contains a sensitive secret assignment"
        ),
        SensitivePattern(
            Regex("(?<!access_)TOKEN=[^(\\s]\\S+", RegexOption.IGNORE_CASE),
            "Output contains a sensitive token assignment"
        ),

        // JDBC connection strings (may embed credentials)
        SensitivePattern(
            Regex("jdbc:[a-z]+://\\S+"),
            "Output contains a JDBC connection string"
        ),

        // MongoDB URIs (may embed credentials)
        SensitivePattern(
            Regex("mongodb(\\+srv)?://\\S+"),
            "Output contains a MongoDB connection URI"
        ),

        // Path traversal (two or more levels of ../ or ..\)
        SensitivePattern(
            Regex("""(?:\.\.[/\\]){2,}"""),
            "Output contains a path traversal sequence"
        ),

        // Redis URIs (may embed credentials)
        SensitivePattern(
            Regex("redis://\\S+"),
            "Output contains a Redis connection URI"
        )
    )

    /**
     * Validates LLM output for sensitive content.
     *
     * @param output The generated text to validate
     * @return List of issue descriptions. Empty list means the output is clean.
     */
    fun validate(output: String): List<String> {
        val issues = mutableListOf<String>()
        for (pattern in SENSITIVE_PATTERNS) {
            if (pattern.regex.containsMatchIn(output)) {
                issues.add(pattern.description)
            }
        }
        return issues
    }

    /**
     * Validates LLM output and throws [SecurityViolationException] if any
     * sensitive content is detected.
     *
     * @param output The generated text to validate
     * @throws SecurityViolationException if the output contains sensitive content
     */
    fun validateOrThrow(output: String) {
        val issues = validate(output)
        if (issues.isNotEmpty()) {
            throw SecurityViolationException(issues)
        }
    }
}
