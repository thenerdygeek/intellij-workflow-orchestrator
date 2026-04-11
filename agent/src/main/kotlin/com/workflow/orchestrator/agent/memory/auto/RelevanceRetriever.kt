package com.workflow.orchestrator.agent.memory.auto

import com.workflow.orchestrator.agent.memory.ArchivalMemory

/**
 * Retrieves relevant archival memory entries at session start
 * based on keywords extracted from the user's first message.
 *
 * No LLM call — uses the existing ArchivalMemory.search() with keyword extraction.
 * Results are formatted as XML for system prompt injection.
 */
class RelevanceRetriever(
    private val archival: ArchivalMemory,
    /**
     * Predicate for path existence. Called when a recalled entry mentions file
     * paths — if ALL mentioned paths fail this check, the entry is suppressed
     * as stale. Defaults to always-true (no staleness filter).
     *
     * Wired by AgentService to use `File(projectBasePath, relativePath).exists()`.
     */
    private val pathExists: (String) -> Boolean = { true }
) {

    companion object {
        /** Max entries to retrieve. */
        private const val MAX_RESULTS = 5

        /** Max total characters in the recalled memory section. */
        private const val MAX_CHARS = 3000

        /**
         * Regex matching common file-path patterns in archival content.
         * Matches paths with at least one slash and a file extension (e.g.
         * `src/main/kotlin/UserService.kt`, `agent/webview/tsconfig.json`).
         * Avoids matching URLs (no `http://`) and common prose word patterns.
         */
        private val FILE_PATH_PATTERN = Regex(
            """(?<![a-zA-Z:/.])(?:[a-zA-Z0-9_\-.]+/)+[a-zA-Z0-9_\-]+\.[a-zA-Z0-9]+\b"""
        )

        /** Extract file-path-looking substrings from content. */
        internal fun extractPaths(content: String): List<String> {
            return FILE_PATH_PATTERN.findAll(content).map { it.value }.toList()
        }

        /**
         * Minimum keyword length to include. Set to 2 so meaningful short identifiers
         * like "ci", "pr", "db", "ui", "id" are preserved. The SHORT_STOP_WORDS list
         * below excludes the handful of 2-char English words that aren't useful for search.
         */
        private const val MIN_KEYWORD_LENGTH = 2

        /** 2-char English words to exclude even though they meet MIN_KEYWORD_LENGTH. */
        private val SHORT_STOP_WORDS = setOf(
            "is", "be", "to", "of", "in", "on", "at", "by", "an", "or",
            "if", "it", "my", "we", "me", "so", "no", "do", "as", "am", "us"
        )

        /**
         * Common English stop words (length >= 3) to exclude from keyword extraction.
         * NOTE: English-only. If the plugin is ever localized, this list will need
         * per-locale variants or a smarter tokenizer.
         */
        private val STOP_WORDS = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "shall", "can", "need", "dare", "ought",
            "used", "to", "of", "in", "for", "on", "with", "at", "by", "from",
            "up", "about", "into", "through", "during", "before", "after",
            "above", "below", "between", "out", "off", "over", "under",
            "again", "further", "then", "once", "here", "there", "when",
            "where", "why", "how", "all", "both", "each", "few", "more",
            "most", "other", "some", "such", "no", "nor", "not", "only",
            "own", "same", "so", "than", "too", "very", "just", "because",
            "but", "and", "or", "if", "while", "that", "this", "these",
            "those", "it", "its", "i", "me", "my", "we", "our", "you",
            "your", "he", "she", "they", "them", "what", "which", "who",
            "please", "help", "want", "like", "look", "check", "see", "fix"
        )

        /**
         * Extract meaningful keywords from a user message.
         * Lowercases, splits on non-alphanumeric, removes stop words and short tokens.
         */
        fun extractKeywords(message: String): List<String> {
            return message.lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter {
                    it.length >= MIN_KEYWORD_LENGTH &&
                        it !in STOP_WORDS &&
                        it !in SHORT_STOP_WORDS
                }
                .distinct()
        }
    }

    /**
     * Retrieve relevant archival memories for a user message.
     *
     * @param userMessage the user's first message in the session
     * @return formatted XML string for system prompt injection, or null if nothing relevant
     */
    fun retrieveForMessage(userMessage: String): String? {
        val keywords = extractKeywords(userMessage)
        if (keywords.isEmpty()) return null

        val query = keywords.joinToString(" ")
        // trackUsage=false: session-start retrieval is system-managed and should not
        // distort the Codex-style usage-based decay signal, and avoids a disk persist
        // on every new task start.
        val rawResults = archival.search(query, limit = MAX_RESULTS, trackUsage = false)
        if (rawResults.isEmpty()) return null

        // Staleness filter: drop entries where ALL mentioned paths are missing.
        // Entries with NO path mentions pass through unchanged (can't be stale
        // by file-existence if they don't reference any files).
        val results = rawResults.filter { result ->
            val paths = extractPaths(result.entry.content)
            if (paths.isEmpty()) {
                true  // No paths = can't be stale by file-existence
            } else {
                paths.any { pathExists(it) }  // At least one mentioned path still exists
            }
        }
        if (results.isEmpty()) return null

        val entries = mutableListOf<String>()
        var totalChars = 0
        for (result in results) {
            val entry = "- [${result.entry.tags.joinToString(", ")}] ${result.entry.content}"
            if (totalChars + entry.length > MAX_CHARS) break
            entries.add(entry)
            totalChars += entry.length
        }

        if (entries.isEmpty()) return null

        return buildString {
            appendLine("<recalled_memory>")
            appendLine("Relevant memories retrieved from previous sessions:")
            for (entry in entries) {
                appendLine(entry)
            }
            append("</recalled_memory>")
        }
    }
}
