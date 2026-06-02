package com.workflow.orchestrator.web.service.egress

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.web.QueryEgressFilter

/**
 * Production implementation of [QueryEgressFilter].
 *
 * The egress screener is MANDATORY and rewrite-first — it never blocks in normal
 * operation; it sanitizes the outbound query so proprietary data does not reach the
 * third-party search provider. Two stages, both always run:
 *
 *  - Stage 0 — deterministic deny-list FORCE-SUBSTITUTION. Every configured deny-list
 *    term found in the query is replaced with `[redacted]` (case-insensitive; entries
 *    prefixed `re:` are case-insensitive regex; malformed regexes are skipped with a
 *    WARN). This guarantees known-sensitive terms are gone even if the LLM misses them.
 *    It never blocks.
 *  - Stage 1 — mandatory LLM sanitizing rewrite. The post-Stage-0 query is always handed
 *    to [llmScreener], which replaces remaining proprietary data with neutral dummy
 *    values (preserving search intent) and returns Safe / Rewritten, or — only when the
 *    screener itself is unavailable — a fail-closed Blocked.
 *
 * Constructor-injected for testability:
 *  - [denyListSupplier] — called on every [screen] invocation (cheap; recomputed so
 *    settings changes take effect without an engine restart). Production wiring calls
 *    `PluginSettings.getWebEgressDenyList()` + (optionally) [AutoDenyListSource].
 *  - [llmScreener] — suspend function that takes the post-Stage-0 query and returns the
 *    LLM decision (the real `SubagentSpawner`-backed screener in production).
 */
class QueryEgressFilterImpl(
    private val denyListSupplier: () -> Set<String>,
    private val llmScreener: suspend (String) -> QueryEgressFilter.Decision,
) : QueryEgressFilter {

    override suspend fun screen(project: Project, query: String): QueryEgressFilter.Decision {
        // Stage 0: deterministic deny-list -- FORCE-SUBSTITUTE every matched term with a
        // masked dummy (never blocks; guarantees known-sensitive terms are gone even if
        // the LLM misses them). The cleaned query is then handed to the mandatory LLM.
        var working = query
        var substituted = false
        for (entry in denyListSupplier()) {
            val masked = substituteDenyTerm(working, entry)
            if (masked != working) { working = masked; substituted = true }
        }
        // Stage 1: mandatory LLM sanitizing rewrite -- always runs.
        val llmDecision = llmScreener(working)
        // If the deny-list already changed the text, ensure the caller learns about it.
        return if (substituted && llmDecision is QueryEgressFilter.Decision.Safe) {
            QueryEgressFilter.Decision.Rewritten(
                query = llmDecision.query,
                original = query,
                note = "removed configured sensitive term(s)",
            )
        } else llmDecision
    }

    /** Replaces deny-list matches in [query] with `[redacted]`. `re:` prefix = regex. */
    private fun substituteDenyTerm(query: String, entry: String): String =
        when {
            entry.startsWith("re:") -> {
                val pattern = entry.removePrefix("re:")
                try {
                    Regex(pattern, RegexOption.IGNORE_CASE).replace(query, "[redacted]")
                } catch (e: Exception) {
                    log.warn("Skipping malformed regex deny-list entry: '$entry' (${e.message})")
                    query
                }
            }
            entry.isBlank() -> query
            else -> query.replace(entry, "[redacted]", ignoreCase = true)
        }

    private companion object {
        private val log = com.intellij.openapi.diagnostic.Logger.getInstance(QueryEgressFilterImpl::class.java)
    }
}
