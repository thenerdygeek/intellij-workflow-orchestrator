package com.workflow.orchestrator.core.web

import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.model.web.WebPage

interface WebFetchService {
    suspend fun fetch(request: WebFetchRequest): ToolResult<WebPage>

    data class WebFetchRequest(
        val url: String,
        val maxBytes: Int? = null,        // null = use settings default
        val preferText: Boolean = true,
        val planMode: Boolean = false,    // R6: tool layer pushes plan-mode in
        /**
         * Optional snippet of the most-recent assistant message preceding this fetch,
         * truncated to 200 chars. Shown in the approval dialog so the user can judge
         * "why does the agent want to fetch this?" — informational, never required.
         */
        val agentContext: String? = null,
        /**
         * Optional extraction prompt. When non-null, after the page is fetched and sanitized,
         * a second LLM call answers this prompt using the cleaned text as the source. The
         * returned text in the wrapper is the extracted answer, not the full page — useful
         * for targeted lookups ("what version of X does this support?"). Cost: ~2x a non-fused
         * fetch (sanitize + extract = two LLM calls); benefit: smaller, on-target result.
         */
        val extractionPrompt: String? = null,
    )
}
