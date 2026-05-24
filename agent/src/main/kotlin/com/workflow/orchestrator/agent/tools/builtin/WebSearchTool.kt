package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.integration.ServiceLookup
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.web.WebSearchService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class WebSearchTool : AgentTool {
    override val name = "web_search"
    override val description = """Search the web and get a list of result hits. Returns title + URL + sanitized snippet + screener-flag badges for each result, wrapped in <external_search query='...' provider='SearXNG|Brave|Tavily|...' count='N'>...</external_search> tags. Use when you don't know the URL but need to find documentation, libraries, or recent information.

Workflow: typical pattern is web_search (find URLs) → pick the most relevant result → web_fetch (read that one URL). Don't fetch every result — pick the best one or two. Search is cheap (~1 second, 1 LLM batch call); fetch is expensive (~2-3 seconds per URL).

When NOT to use: searching project code (use search_code); searching code on the web (use the specific repo's site search via web_fetch); finding things you already know about. The query is screened for accidental token leakage (Bearer/JWT/AWS keys auto-redacted).

Common error responses: NO_PROVIDER_CONFIGURED means the user hasn't set up a search provider in Settings > Workflow Orchestrator > Web (ask the user to configure one); PROVIDER_AUTH_FAILED means the API key is wrong/expired; PLAN_MODE_BLOCKED means web tools are off in plan mode; WEB_SEARCH_DISABLED means the user has the tool turned off in Settings."""
    override val parameters = FunctionParameters(
        properties = mapOf(
            "query" to ParameterProperty(type = "string", description = "Search query (1-1000 chars). Tokens like Bearer/JWT/AWS keys are auto-redacted before sending."),
            "max_results" to ParameterProperty(type = "integer", description = "Max results to return. Defaults to 5; capped by global setting."),
        ),
        required = listOf("query"),
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val query = params["query"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("MALFORMED_QUERY: query parameter required")
        val maxResults = params["max_results"]?.jsonPrimitive?.intOrNull ?: 5
        val settings = project.service<PluginSettings>().state
        // Belt-and-suspenders: when both enableWebFetch=false and enableWebSearch=false the tool
        // is not registered in ToolRegistry and therefore never callable via the normal ReAct loop.
        // This early-return is a defensive safety net for the narrow race where settings change
        // mid-iteration (between reregisterConditionalTools firing and the next prompt rebuild).
        if (!settings.enableWebSearch) {
            val msg = "WEB_SEARCH_DISABLED: web_search is disabled in Workflow Orchestrator settings"
            return errorResult(msg)
        }
        val planMode = AgentService.planModeActive.get()
        val planAllow = settings.webPlanModeAllow

        val svc = ServiceLookup.webSearch(project) ?: return ServiceLookup.notConfigured("Web Search")
        val rr = svc.search(WebSearchService.WebSearchRequest(
            query = query,
            maxResults = maxResults,
            planMode = planMode && !planAllow,
        ))
        if (rr.isError) return errorResult(rr.summary)
        val hits = rr.data!!
        // I10 — neutralize any literal </external_search> close tags injected by a
        // jailbroken sanitizer into a snippet, title, or URL field. Replacing rather
        // than refusing because search returns N independent hits — a single hostile
        // snippet should not poison the entire result set.
        fun escTag(s: String): String = s.replace("</external_search>", "&lt;/external_search&gt;", ignoreCase = true)
        fun escUrl(s: String): String = s.replace("'", "&apos;")
        val content = buildString {
            appendLine("<external_search query='${query.replace("'", "&apos;")}' provider='${hits.firstOrNull()?.provider ?: "unknown"}' count='${hits.size}'>")
            hits.forEach { h ->
                val flags = if (h.screenerFlags.isNotEmpty()) " [${h.screenerFlags.joinToString(",") { it.name }}]" else ""
                appendLine("  [${h.rank + 1}] ${escTag(h.title)} — ${escUrl(escTag(h.url))}$flags")
                appendLine("      ${escTag(h.snippet)}")
            }
            appendLine("</external_search>")
        }
        return ToolResult(
            content = content,
            summary = "Searched '${query}' → ${hits.size} results",
            tokenEstimate = TokenEstimator.estimate(content),
        )
    }

    private fun errorResult(msg: String): ToolResult = ToolResult(
        content = msg, summary = msg,
        tokenEstimate = TokenEstimator.estimate(msg),
        isError = true,
    )
}
