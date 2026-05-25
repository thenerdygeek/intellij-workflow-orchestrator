package com.workflow.orchestrator.core.http

/**
 * Core-level prompt-body redactor used by [RawApiTraceInterceptor] when
 * [RawApiTraceConfig.redactPromptBody] is `true` (the default), and by the
 * `:pullrequest` AI-review pipeline to scrub diff content before sending to an LLM.
 *
 * Strips values for well-known credential-carrying JSON fields and
 * Authorization header values that may appear inside serialized request bodies
 * written to disk during API tracing.
 *
 * What is redacted:
 * - JSON string values whose key is one of: `password`, `token`, `access_token`,
 *   `api_key`, `apiKey`, `api-key`, `secret`, `private_key`, `privateKey`,
 *   `auth`, `bearer`, `credential`.
 * - The value component of `Authorization: <scheme> <credentials>` lines that
 *   sometimes appear in JSON-serialized request dumps.
 * - AWS access key IDs (`AKIA…`, `ASIA…`).
 * - PEM private key headers (`-----BEGIN ... PRIVATE KEY-----`).
 * - Assignment-style secret patterns: `api_key=`, `api-key=`, `apikey=`,
 *   `token=`, `secret=`, `password=`, `passwd=` followed by a non-whitespace value.
 * - Bearer tokens appearing as bare header lines (`Bearer <token>`) in diff context.
 *
 * What is NOT touched:
 * - Non-credential JSON fields (the body text, messages, etc.).
 * - The structure of the JSON (no parsing — pure regex replacement so it works
 *   on partial/streaming bodies too).
 *
 * This redactor lives in `:core` so it can be referenced by [RawApiTraceInterceptor]
 * without introducing a circular dependency on `:agent`'s `CredentialRedactor`,
 * and so `:pullrequest` can use it without depending on `:agent`.
 */
object PromptBodyRedactor {

    /**
     * Matches a JSON key that is a credential field name followed by a quoted value.
     * Covers both lowercase field names (e.g. `"token"`) and HTTP header keys
     * (e.g. `"Authorization"`).
     *
     * Two variants:
     * - Unescaped quotes: `"token": "value"` — top-level JSON fields
     * - Escaped quotes: `\"token\": \"value\"` — fields inside JSON strings
     *   (as seen in pre-sanitize dumps where message content is a JSON string)
     *
     * Group 1 = everything up to and including the opening quote of the value.
     * Group 2 = the value itself (at least 1 char).
     */
    private val JSON_CREDENTIAL_FIELD = Regex(
        """("(?:password|token|access_token|api_key|apiKey|api-key|secret|private_key|privateKey|auth|Authorization|bearer|credential)"\s*:\s*")([^"]{1,})""",
        RegexOption.IGNORE_CASE
    )

    /** Same as [JSON_CREDENTIAL_FIELD] but for escaped-quote form inside serialized JSON strings. */
    private val JSON_CREDENTIAL_FIELD_ESCAPED = Regex(
        """(\\?"(?:password|token|access_token|api_key|apiKey|api-key|secret|private_key|privateKey|auth|Authorization|bearer|credential)\\?"\s*:\s*\\?")([^"\\]{1,})""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Matches `Authorization: <any-scheme> <credentials>` as it appears as a
     * bare header line or inside a JSON string value in a serialized headers map.
     * Group 1 = scheme portion ("Bearer ", "token ", etc.).
     * Group 2 = the credential portion.
     */
    private val AUTH_HEADER_VALUE = Regex(
        """(Authorization\s*:\s*(?:Bearer|token|basic|apikey)\s+)([A-Za-z0-9\-._~+/=]{4,})""",
        RegexOption.IGNORE_CASE
    )

    // ── Diff-context patterns (used when redacting PR diff content before LLM send) ──

    /**
     * AWS IAM Access Key IDs: 20-char string starting with AKIA or ASIA.
     * Group 1 = the key ID itself.
     */
    private val AWS_ACCESS_KEY = Regex("""((?:AKIA|ASIA)[A-Z0-9]{16})""")

    /**
     * PEM private-key header lines.
     * Matches `-----BEGIN (RSA |EC |OPENSSH |DSA |)PRIVATE KEY-----`.
     * The full multi-line PEM body is not parsed (would require multi-line look-ahead);
     * redacting the header line is sufficient to signal that a key is present and
     * prevents the LLM from processing key material embedded in a diff hunk.
     */
    private val PEM_PRIVATE_KEY_HEADER = Regex(
        """(-----BEGIN\s+(?:RSA\s+|EC\s+|OPENSSH\s+|DSA\s+|ENCRYPTED\s+)?PRIVATE\s+KEY-----)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Assignment-style secrets in source or config file diffs:
     * `api_key=<value>`, `token=<value>`, `password=<value>`, etc.
     * Covers `=`, `: `, ` = ` separators and optional surrounding quotes.
     * Group 1 = key + separator (kept), Group 2 = the secret value (replaced).
     */
    private val ASSIGNMENT_SECRET = Regex(
        """((?:api[_-]?key|access[_-]?token|auth[_-]?token|secret[_-]?key?|password|passwd|private[_-]?key|client[_-]?secret)\s*[=:]\s*["']?)(\S{6,})""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Redact credential values from [body].
     *
     * All patterns use the single `***REDACTED***` marker — the canonical marker shared with
     * the rest of the HTTP-redaction code (`RawApiTraceInterceptor`, `SourcegraphChatClient`).
     * (Previously the diff-specific patterns emitted `[REDACTED]`; normalized to one marker.)
     *
     * @return A copy of [body] with credential values replaced by the redaction marker.
     *         Never throws; returns [body] unchanged on error.
     */
    fun redact(body: String): String = try {
        body
            .replace(JSON_CREDENTIAL_FIELD) { m -> "${m.groupValues[1]}***REDACTED***" }
            .replace(JSON_CREDENTIAL_FIELD_ESCAPED) { m -> "${m.groupValues[1]}***REDACTED***" }
            .replace(AUTH_HEADER_VALUE) { m -> "${m.groupValues[1]}***REDACTED***" }
            .replace(AWS_ACCESS_KEY) { "***REDACTED***" }
            .replace(PEM_PRIVATE_KEY_HEADER) { "***REDACTED***" }
            .replace(ASSIGNMENT_SECRET) { m -> "${m.groupValues[1]}***REDACTED***" }
    } catch (_: Exception) {
        body
    }
}
