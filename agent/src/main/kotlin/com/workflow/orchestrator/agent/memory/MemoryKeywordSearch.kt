package com.workflow.orchestrator.agent.memory

/**
 * Shared helper for keyword frequency counting used by memory search.
 *
 * Unifies the previously inconsistent counting implementations in
 * [ArchivalMemory.search] and [ConversationRecall.search]:
 *
 *  - `ArchivalMemory` used `content.lowercase().windowed(kw.length, 1).count { it == kw }`
 *    which counts **overlapping** occurrences (e.g. "aa" in "aaaa" = 3).
 *  - `ConversationRecall` used `content.lowercase().split(kw).size - 1`
 *    which counts **non-overlapping** occurrences (e.g. "aa" in "aaaa" = 2).
 *
 * Both call sites feed the result into a relevance score for ranking search
 * results, so the exact count semantics matters less than **consistency** across
 * the two tiers of memory search. We standardize on non-overlapping counting
 * because it more intuitively answers "how many distinct times does this
 * keyword appear in this content" — and it matches the user's mental model
 * when skimming results.
 *
 * **Behavior guarantees:**
 *  - **Non-overlapping**: after matching a keyword at index `i`, scanning
 *    resumes at `i + keyword.length`.
 *  - **Case-insensitive**: both the content and keyword are compared with
 *    `ignoreCase = true` (matching the prior behavior of both call sites,
 *    which lowercased before comparing).
 *  - **Empty keyword**: returns 0 (no match) rather than infinite matches.
 *
 * **Behavior change note:** Previously `ArchivalMemory` counted overlapping
 * occurrences. For normal search terms (words, phrases, identifiers) overlapping
 * and non-overlapping counts are identical because search terms rarely overlap
 * with themselves. The semantic change only affects pathological keywords like
 * "aa" or "abab" — unlikely in real memory search usage.
 */
object MemoryKeywordSearch {

    /**
     * Count non-overlapping, case-insensitive occurrences of [keyword] in
     * [content]. Used for keyword-frequency ranking in memory search.
     *
     * @param content the text to search within
     * @param keyword the keyword to count
     * @return number of non-overlapping matches; 0 if [keyword] is empty
     */
    fun countOccurrences(content: String, keyword: String): Int {
        if (keyword.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            index = content.indexOf(keyword, index, ignoreCase = true)
            if (index < 0) break
            count++
            index += keyword.length // non-overlapping
        }
        return count
    }
}
