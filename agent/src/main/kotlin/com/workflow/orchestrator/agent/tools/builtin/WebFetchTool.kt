package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.web.WebFetchService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class WebFetchTool : AgentTool {
    override val name = "web_fetch"
    override val description = "Fetch a URL and return sanitized text. Default-deny: unlisted domains require user approval. HTTPS-only by default. Read-only — no auth headers, cookies, or custom auth are forwarded."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "url" to ParameterProperty(type = "string", description = "The URL to fetch (https:// required by default)."),
            "max_bytes" to ParameterProperty(type = "integer", description = "Optional cap on bytes read; capped at the configured global maximum."),
        ),
        required = listOf("url"),
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val url = params["url"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("MALFORMED_URL: url parameter required")
        val planMode = AgentService.planModeActive.get()
        val planAllow = project.service<PluginSettings>().state.webPlanModeAllow
        val maxBytes = params["max_bytes"]?.jsonPrimitive?.int

        val svc = project.service<WebFetchService>()
        val rr = svc.fetch(WebFetchService.WebFetchRequest(
            url = url,
            maxBytes = maxBytes,
            planMode = planMode && !planAllow,    // R6
        ))
        if (rr.isError) return errorResult(rr.summary)
        val page = rr.data!!
        val content = "<external_content url='${page.finalUrl}' source='web_fetch' " +
                "verdict='${page.sanitizerVerdict}' size_chars='${page.extractedChars}'>\n" +
                page.extractedText +
                "\n</external_content>"
        return ToolResult(
            content = content,
            summary = "Fetched ${page.finalUrl} (${page.extractedChars} chars, ${page.sanitizerVerdict})",
            tokenEstimate = TokenEstimator.estimate(content),
        )
    }

    private fun errorResult(msg: String): ToolResult = ToolResult(
        content = msg,
        summary = msg,
        tokenEstimate = TokenEstimator.estimate(msg),
        isError = true,
    )
}
