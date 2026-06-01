package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
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

Common error responses: NO_PROVIDER_CONFIGURED means the user hasn't set up a search provider in Settings > Workflow Orchestrator > Web (ask the user to configure one); PROVIDER_AUTH_FAILED means the API key is wrong/expired; PLAN_MODE_BLOCKED means web tools are off in plan mode; WEB_SEARCH_DISABLED means the user has the tool turned off in Settings.

PROPRIETARY IDENTIFIERS: do NOT include internal hostnames (e.g. jenkins.acme.corp), internal class names or package paths (e.g. com.acme.payments.PaymentsService, MyComp.class), internal customer/project names, or internal file paths in your query — the query is sent to a third-party search engine and would reveal organizational structure. Use generic terms instead (e.g. "Jenkins" not "jenkins.acme.corp", "our payments service" not "InternalPaymentsService"). Queries containing these identifiers will be blocked by the egress filter and return QUERY_BLOCKED_SENSITIVE — rewrite without the proprietary terms and retry."""
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
        val planMode = project.service<AgentService>().isPlanModeActive()
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
        val retrievedAt = java.time.Instant.now()
        val content = buildString {
            appendLine("<external_search query='${query.replace("'", "&apos;")}' provider='${hits.firstOrNull()?.provider ?: "unknown"}' count='${hits.size}' retrieved_at='$retrievedAt'>")
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

    override fun documentation(): ToolDocumentation = toolDoc("web_search") {
        summary {
            technical(
                "Runs a query through a 5-stage search pipeline (query token-redaction → outbound egress filter → " +
                    "provider resolution + provider-URL SSRF screen → provider search → per-hit URL screen + batch " +
                    "sanitization) against a pluggable provider (SearXNG / Brave / Tavily / Custom HTTP) and returns " +
                    "ranked title + URL + sanitized snippet + screener-flag badges, wrapped in `<external_search …>`."
            )
            plain(
                "Looks something up on the web and hands back a short list of result links with one-line summaries — " +
                    "like typing into a search box. Before the query leaves your machine it is scrubbed of accidental " +
                    "secrets (API keys) and blocked if it contains internal company names. You then usually web_fetch " +
                    "the best result to actually read it."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.NETWORK)
        counterfactual(
            "Without web_search the agent can't discover URLs it doesn't already know, and would either guess URLs " +
                "(often 404) or shell out to a provider's API via `run_command curl` — which skips the egress filter " +
                "(leaking internal hostnames / class names to a third-party engine), skips token redaction, and skips " +
                "snippet sanitization. The discovery half of web research disappears, and a data-exfiltration path opens."
        )
        llmMistake(
            "Fetches every hit. The intended pattern is web_search → pick the 1–2 best → web_fetch those. Search is " +
                "cheap (~1s, one batch sanitizer call); fetch is expensive per URL."
        )
        llmMistake(
            "Puts internal identifiers in the query (hostnames like jenkins.acme.corp, class names like " +
                "com.acme.PaymentsService, project/customer names, file paths). The egress filter returns " +
                "QUERY_BLOCKED_SENSITIVE — rewrite with generic terms ('Jenkins', 'our payments service') and retry."
        )
        llmMistake(
            "Retries verbatim after NO_PROVIDER_CONFIGURED. No provider is set up — this is a settings problem, not a " +
                "transient one. Ask the user to configure a provider in Settings ▸ Web."
        )
        llmMistake("Treats snippets as authoritative. A snippet is an untrusted, sanitized teaser — confirm by fetching the page.")
        params {
            required("query", "string") {
                llmSeesIt("Search query (1-1000 chars). Tokens like Bearer/JWT/AWS keys are auto-redacted before sending.")
                humanReadable(
                    "What to search for, in plain words — like a search-box query. Keep it generic: secrets are " +
                        "auto-redacted and internal company terms are blocked from leaving the machine."
                )
                whenPresent(
                    "The query is token-redacted (Bearer/JWT/AWS), then run through the egress filter (deny-list + " +
                        "optional LLM screener); if it clears, it is dispatched to the configured provider."
                )
                constraint("1–1000 characters")
                constraint("must not contain proprietary identifiers — internal hostnames, class/package names, project or customer names, internal file paths")
                example("OkHttp connection pool default settings")
                example("Spring Boot 3 actuator health readiness probe")
            }
            optional("max_results", "integer") {
                llmSeesIt("Max results to return. Defaults to 5; capped by global setting.")
                humanReadable("How many hits to bring back. Defaults to 5; the plugin's global cap wins if it is smaller.")
                whenPresent("At most this many ranked hits are returned (after the global cap is applied).")
                whenAbsent("Defaults to 5.")
                constraint("capped at the global maximum (settings.webSearchMaxResults)")
                example("3")
                example("10")
            }
        }
        verdict {
            keep(
                "Pairs with web_fetch to make web research possible at all — it is the discovery half. The egress " +
                    "filter and token redaction make it safe to expose a third-party search engine to an agent that " +
                    "has the user's internal context in its prompt; that safety layer is the reason it's a first-class " +
                    "tool and not a curl call.",
                VerdictSeverity.STRONG,
            )
        }
        related("web_fetch", Relationship.COMPLEMENT, "The standard next step: search to find the URL, then fetch the best result to read it.")
        related("search_code", Relationship.ALTERNATIVE, "Use instead when searching the project's own code — web_search is for the public internet.")
        downside("Quality depends entirely on the configured provider. SearXNG needs a running instance; Brave/Tavily need an API key in PasswordSafe under ServiceType.WEB_SEARCH.")
        downside("The egress filter can false-positive on a legitimate query that happens to look like an internal identifier (QUERY_BLOCKED_SENSITIVE) — rephrase with more generic terms.")
        downside("Snippets are provider-supplied teasers, sanitized but shallow — they are for ranking which result to fetch, not for answering the question directly.")
        downside("A single hostile snippet can't poison the whole result set: a forged `</external_search>` close tag in any title/url/snippet is escaped (not refused), unlike web_fetch which refuses the whole page.")
        observation(
            "The egress filter (Stage 1.5) is the mirror image of web_fetch's sanitizer: the sanitizer guards what " +
                "comes IN, the egress filter guards what goes OUT. Stage 0 deny-list is always on; the LLM screener is " +
                "optional and gated by settings. See the narrative for worked redaction/block examples."
        )
        flowchart(
            """
            flowchart TD
                A[LLM calls web_search query] --> P{plan mode and not webPlanModeAllow?}
                P -- yes --> XP[PLAN_MODE_BLOCKED]
                P -- no --> S0{Stage 0: provider configured?}
                S0 -- no --> X0[NO_PROVIDER_CONFIGURED]
                S0 -- yes --> S1[Stage 1: redact Bearer/JWT/AWS tokens]
                S1 --> S15{Stage 1.5: egress filter<br/>deny-list + optional LLM screener}
                S15 -- proprietary --> X15[QUERY_BLOCKED_SENSITIVE]
                S15 -- clean --> S2{Stage 2: provider base-URL<br/>SSRF screen}
                S2 -- unsafe --> X2[PROVIDER_URL_UNSAFE]
                S2 -- ok --> S3[Stage 3: provider HTTP search]
                S3 -- auth fail --> X3[PROVIDER_AUTH_FAILED]
                S3 -- bad json --> X3b[PROVIDER_MALFORMED_RESPONSE]
                S3 -- hits --> S4[Stage 4: per-hit URL screen<br/>structural snippet strip]
                S4 --> S5[Stage 5: batch sanitize snippets<br/>truncate + cap result count]
                S5 --> R[Wrap ranked hits in external_search]
            """
        )
        narrative("web_search")
    }
}
