package com.workflow.orchestrator.core.web

import com.intellij.openapi.project.Project

/**
 * Outbound query screener for the web_search pipeline. Runs after [UrlScreener.screenQuery]
 * (token redaction) and before the provider dispatch. Rewrites queries so proprietary
 * identifiers do not leave the user's organization. Mandatory — never disabled.
 *
 * Both layers in the production impl run on every query, behind this one interface:
 *  - Stage 0 — deterministic deny-list (user-supplied + auto-derived from configured service
 *    URLs and project module names). Force-substitutes matched terms with `[redacted]`
 *    (never blocks), ~µs.
 *  - Stage 1 — MANDATORY LLM screener (~700ms, ~$0.0005/call). Rewrites remaining proprietary
 *    data to neutral dummy values, preserving intent. Returns SAFE / Rewritten, or — only when
 *    the screener itself is unavailable — a fail-closed Blocked.
 *  - (A system-prompt hint also lives in [WebSearchTool.description] in :agent, not behind
 *    this interface.)
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
