package com.workflow.orchestrator.web.service.egress

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.web.QueryEgressFilter

/**
 * Production implementation of [QueryEgressFilter].
 *
 * Constructor-injected for testability:
 *  - [denyListSupplier] — called on every [screen] invocation (cheap; recomputed so settings
 *    changes take effect without an engine restart). Production wiring calls
 *    `PluginSettings.getWebEgressDenyList()` + (optionally) [AutoDenyListSource].
 *  - [llmScreenerEnabled] — toggles Stage 1 (LLM screener) on/off.
 *  - [llmScreener] — suspend function that takes the post-Stage-0 query and returns the
 *    LLM decision. Wired in B7 to the real `SubagentSpawner`-backed screener.
 *
 * Stage 0 (this class): deterministic deny-list. Substring matches case-insensitively;
 * entries prefixed `re:` are compiled as case-insensitive regex (malformed regexes are
 * skipped silently — surfaced via [com.intellij.openapi.diagnostic.Logger] WARN).
 * Stage 1 (this class): delegates to [llmScreener] when [llmScreenerEnabled] is true.
 */
class QueryEgressFilterImpl(
    private val denyListSupplier: () -> Set<String>,
    private val llmScreenerEnabled: Boolean,
    private val llmScreener: suspend (String) -> QueryEgressFilter.Decision,
) : QueryEgressFilter {

    override suspend fun screen(project: Project, query: String): QueryEgressFilter.Decision {
        // Stage 0: deterministic deny-list
        val denyMatch = matchDenyList(query, denyListSupplier())
        if (denyMatch != null) {
            return QueryEgressFilter.Decision.Blocked(
                reason = "DENYLIST",
                maskedTerm = mask(denyMatch),
            )
        }
        // Stage 1: optional LLM screener
        if (llmScreenerEnabled) {
            return llmScreener(query)
        }
        return QueryEgressFilter.Decision.Safe(query)
    }

    /**
     * Returns the offending substring (the actual matched chars from the query, not the
     * pattern) so [mask] can show "first 3 of what we saw". For regex matches, returns
     * the matched group. Returns null when nothing matched.
     */
    private fun matchDenyList(query: String, entries: Set<String>): String? {
        for (entry in entries) {
            val matched = when {
                entry.startsWith("re:") -> {
                    val pattern = entry.removePrefix("re:")
                    try {
                        Regex(pattern, RegexOption.IGNORE_CASE).find(query)?.value
                    } catch (e: Exception) {
                        log.warn("Skipping malformed regex deny-list entry: '$entry' (${e.message})")
                        null
                    }
                }
                else -> {
                    val idx = query.indexOf(entry, ignoreCase = true)
                    if (idx >= 0) query.substring(idx, idx + entry.length) else null
                }
            }
            if (matched != null) return matched
        }
        return null
    }

    /**
     * Masks the offending term for audit/error display. Length >= 6 → keep first 3 chars,
     * replace rest with `***`. Length < 6 → fully `***`. Examples:
     *   "acme.corp" -> "acm***"
     *   "Foo42"     -> "***"
     */
    private fun mask(term: String): String =
        if (term.length >= 6) term.take(3) + "***" else "***"

    private companion object {
        private val log = com.intellij.openapi.diagnostic.Logger.getInstance(QueryEgressFilterImpl::class.java)
    }
}
