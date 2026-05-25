package com.workflow.orchestrator.core.web

import com.intellij.openapi.project.Project

/**
 * Outbound query screener for the web_search pipeline. Runs after [UrlScreener.screenQuery]
 * (token redaction) and before the provider dispatch. Blocks or rewrites queries that contain
 * proprietary identifiers from leaving the user's organization.
 *
 * Three layers in the production impl, all behind this one interface:
 *  - Stage 0 — deterministic deny-list (user-supplied + auto-derived from configured service
 *    URLs and project module names). Hard block, ~µs.
 *  - Stage 1 — opt-in LLM screener (~700ms, ~$0.0005/call). Catches paraphrase / synonym
 *    leaks the deny-list cannot. Returns SAFE / REWRITTEN / BLOCKED.
 *  - Stage 2 — system-prompt hint baked into [WebSearchTool.description]. Lives in :agent, not
 *    behind this interface.
 *
 * The `:web` module registers the production impl as a project service. Tests construct
 * [QueryEgressFilterImpl] directly with mocked deps.
 */
interface QueryEgressFilter {

    suspend fun screen(project: Project, query: String): Decision

    sealed class Decision {
        /** Query passed both stages cleanly. Use [query] verbatim. */
        data class Safe(val query: String) : Decision()

        /**
         * LLM screener generalized one or more proprietary identifiers. Use [query] (the
         * rewritten version); surface [note] to the LLM so it knows its query was modified.
         * Original (unsafe) query is in [original] for audit logging only.
         */
        data class Rewritten(val query: String, val original: String, val note: String) : Decision()

        /**
         * Query was blocked. [reason] identifies the layer that fired (DENYLIST / LLM_REFUSED /
         * LLM_TIMEOUT). [maskedTerm] is the offending identifier with most characters replaced
         * by `*` (first 3 chars preserved when length >= 6, else fully masked) so the audit
         * log records what triggered the block without dumping the sensitive term verbatim.
         */
        data class Blocked(val reason: String, val maskedTerm: String) : Decision()
    }
}
