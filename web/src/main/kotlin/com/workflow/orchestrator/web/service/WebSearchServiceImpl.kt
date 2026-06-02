// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.web.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.http.IdeProxy
import com.workflow.orchestrator.core.http.IdeTrust
import com.workflow.orchestrator.core.model.web.SearchHit
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.getWebEgressDenyList
import com.workflow.orchestrator.core.settings.resolveSanitizerBrainId
import com.workflow.orchestrator.core.util.ProjectIdentifier
import com.workflow.orchestrator.core.web.SubagentSpawner
import com.workflow.orchestrator.core.web.WebSearchService
import com.workflow.orchestrator.core.web.WebSearchService.WebSearchRequest
import com.workflow.orchestrator.web.audit.WebAuditLog
import com.workflow.orchestrator.web.service.sanitizer.JsoupReadability
import com.workflow.orchestrator.web.service.sanitizer.SanitizerSubagent
import com.workflow.orchestrator.web.service.search.SearchProviderRegistry
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.nio.file.Path
import java.time.Duration

/**
 * [@Service] thin facade for [WebSearchEngine].
 *
 * The IntelliJ platform instantiates this class with only [project] and [cs] as
 * constructor args. All business-logic dependencies are wired here from default
 * production sources (project services, HttpClientFactory, etc.).
 *
 * Tests should NOT use this class — they should construct [WebSearchEngine] directly
 * with mocked/stub deps.
 */
@Service(Service.Level.PROJECT)
class WebSearchServiceImpl(
    private val project: Project,
    @Suppress("UNUSED_PARAMETER") private val cs: CoroutineScope,
) : WebSearchService {

    private val engine: WebSearchEngine by lazy { buildEngine() }

    override suspend fun search(request: WebSearchRequest): ToolResult<List<SearchHit>> =
        engine.search(request)

    // ─────────────────────────────────────────────────────────────────────────
    // Production wiring
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildEngine(): WebSearchEngine {
        val settings = project.service<PluginSettings>()
        val state = settings.state

        // Search client: basic GET client. Auth-stripping is intentionally ABSENT here.
        // Search providers (e.g. CustomHttp) may authenticate via outgoing headers such as
        // X-API-Key or Authorization. Installing StripAuthHeadersInterceptor
        // would silently strip those headers after the provider adds them, causing every
        // authenticated call to 401. Plan rev R5: auth-stripping is fetch-only — the fetch client
        // in WebFetchServiceImpl carries StripAuthHeadersInterceptor; this search client does not.
        val searchClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(state.webConnectTimeoutSec.toLong()))
            .readTimeout(Duration.ofSeconds(state.webReadTimeoutSec.toLong()))
            .proxySelector(IdeProxy.selector())
            .proxyAuthenticator(IdeProxy.proxyAuthenticator())
            // IdeTrust: same OS / IDE truststore the rest of the IDE uses, so search
            // providers behind a corporate SSL-inspection proxy don't fail the handshake.
            .let { IdeTrust.applyTo(it) }
            .build()

        val sanitizerSubagent = SanitizerSubagent(
            project.service<SubagentSpawner>()
        )

        val auditLogDir: Path = run {
            val basePath = project.basePath ?: System.getProperty("user.home")
            val logsDir = ProjectIdentifier.logsDir(basePath)
            logsDir.toPath().resolve("web")
        }

        val egressFilter = com.workflow.orchestrator.web.service.egress.QueryEgressFilterImpl(
            denyListSupplier = {
                val user = settings.getWebEgressDenyList().toSet()
                val auto = if (state.webEgressIncludeAutoDerivedTerms) {
                    val connState = ConnectionSettings.getInstance().state
                    val urls = listOfNotNull(
                        connState.jiraUrl,
                        connState.bambooUrl,
                        connState.bitbucketUrl,
                        connState.sonarUrl,
                        connState.sourcegraphUrl,
                        connState.webSearchSearxngUrl,
                        connState.webSearchCustomUrl,
                    ).filter { it.isNotBlank() }
                    com.workflow.orchestrator.web.service.egress.AutoDenyListSource.extractHostsFromUrls(urls) +
                        com.workflow.orchestrator.web.service.egress.AutoDenyListSource.extractModuleNames(project)
                } else emptySet<String>()
                user + auto
            },
            llmScreener = run {
                // Mandatory: the egress LLM screener ALWAYS runs. brainId=null -> cheapest
                // available model (resolveSanitizerBrainId returns null when unset).
                val llm = com.workflow.orchestrator.web.service.egress.QueryEgressLlmScreener(
                    spawner = project.service<com.workflow.orchestrator.core.web.SubagentSpawner>(),
                    brainId = settings.resolveSanitizerBrainId(),
                    timeoutMs = state.webEgressTimeoutMs.toLong(),
                )
                ;{ q -> llm.screen(project, q) }
            },
        )

        return WebSearchEngine(
            project = project,
            settings = settings,
            sanitizerSubagent = sanitizerSubagent,
            jsoupReadability = JsoupReadability(),
            registry = SearchProviderRegistry(project, searchClient),
            auditLog = WebAuditLog(auditLogDir),
            egressFilter = egressFilter,
        )
    }
}
