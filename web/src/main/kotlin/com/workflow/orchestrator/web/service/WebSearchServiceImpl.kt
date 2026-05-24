// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.web.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.web.SearchHit
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
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
        // Search providers (Brave, CustomHttp) authenticate via outgoing headers such as
        // X-Subscription-Token, X-API-Key, and Authorization. Installing StripAuthHeadersInterceptor
        // would silently strip those headers after the provider adds them, causing every
        // authenticated call to 401. Plan rev R5: auth-stripping is fetch-only — the fetch client
        // in WebFetchServiceImpl carries StripAuthHeadersInterceptor; this search client does not.
        val searchClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(state.webConnectTimeoutSec.toLong()))
            .readTimeout(Duration.ofSeconds(state.webReadTimeoutSec.toLong()))
            .build()

        val sanitizerSubagent = SanitizerSubagent(
            project.service<SubagentSpawner>()
        )

        val auditLogDir: Path = run {
            val basePath = project.basePath ?: System.getProperty("user.home")
            val logsDir = ProjectIdentifier.logsDir(basePath)
            logsDir.toPath().resolve("web")
        }

        // TODO B10: replace this pass-through with project.service<QueryEgressFilterImpl>()
        //  once QueryEgressFilterImpl is registered as a @Service in plugin.xml.
        val noOpEgressFilter = object : com.workflow.orchestrator.core.web.QueryEgressFilter {
            override suspend fun screen(
                project: com.intellij.openapi.project.Project,
                query: String,
            ) = com.workflow.orchestrator.core.web.QueryEgressFilter.Decision.Safe(query)
        }

        return WebSearchEngine(
            project = project,
            settings = settings,
            sanitizerSubagent = sanitizerSubagent,
            jsoupReadability = JsoupReadability(),
            registry = SearchProviderRegistry(project, searchClient),
            auditLog = WebAuditLog(auditLogDir),
            egressFilter = noOpEgressFilter,
        )
    }
}
