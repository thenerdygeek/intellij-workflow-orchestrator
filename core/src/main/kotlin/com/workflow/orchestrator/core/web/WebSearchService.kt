package com.workflow.orchestrator.core.web

import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.model.web.SearchHit

interface WebSearchService {
    suspend fun search(request: WebSearchRequest): ToolResult<List<SearchHit>>

    data class WebSearchRequest(
        val query: String,
        val maxResults: Int = 5,
        val planMode: Boolean = false,
    )
}
