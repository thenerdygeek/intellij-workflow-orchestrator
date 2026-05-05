package com.workflow.orchestrator.core.util

/**
 * Extracts Jira ticket keys from branch names, commit messages, or arbitrary text.
 * Canonical location for the regex — avoids duplication across modules.
 * Matches: project key must start with uppercase letter, followed by 1+ uppercase/digit,
 * then a dash and 1+ digits. E.g. "AFTER8TE-912", "WO-882".
 *
 * Current adopters: :pullrequest (CreatePrPrefetch, TicketChipInput).
 * TODO: migrate :jira-internal usages in a follow-up — those are scoped to
 *       ActiveTicketService and changing them risks breaking unrelated functionality.
 */
object TicketKeyExtractor {
    private val PATTERN = Regex("([A-Z][A-Z0-9]+-\\d+)")

    /** Returns the first ticket key found in the given text, or null. */
    fun extractFromBranch(branchName: String): String? =
        PATTERN.find(branchName)?.groupValues?.get(1)

    /**
     * Returns all distinct ticket keys found anywhere in [text], preserving the order
     * of first occurrence. Use this to scan free-form text like commit-message bodies
     * or PR descriptions where multiple tickets may be referenced.
     */
    fun extractAllFromText(text: String): List<String> =
        PATTERN.findAll(text)
            .map { it.groupValues[1] }
            .toMutableSet()
            .toList()

    /** Validates the exact key format (no surrounding text). */
    fun isValidKey(key: String): Boolean =
        PATTERN.matches(key)
}
