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
    )
}
