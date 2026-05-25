package com.workflow.orchestrator.core.http

/**
 * Core-level prompt-body redactor used by [RawApiTraceInterceptor] when
 * [RawApiTraceConfig.redactPromptBody] is `true` (the default).
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
 *
 * What is NOT touched:
 * - Non-credential JSON fields (the body text, messages, etc.).
 * - The structure of the JSON (no parsing — pure regex replacement so it works
 *   on partial/streaming bodies too).
 *
 * This redactor lives in `:core` so it can be referenced by [RawApiTraceInterceptor]
 * without introducing a circular dependency on `:agent`'s `CredentialRedactor`.
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

    /**
     * Redact credential values from [body].
     *
     * @return A copy of [body] with credential values replaced by `***REDACTED***`.
     *         Never throws; returns [body] unchanged on unexpected error.
     */
    fun redact(body: String): String = try {
        body
            .replace(JSON_CREDENTIAL_FIELD) { m -> "${m.groupValues[1]}***REDACTED***" }
            .replace(JSON_CREDENTIAL_FIELD_ESCAPED) { m -> "${m.groupValues[1]}***REDACTED***" }
            .replace(AUTH_HEADER_VALUE) { m -> "${m.groupValues[1]}***REDACTED***" }
    } catch (_: Exception) {
        body
    }
}
