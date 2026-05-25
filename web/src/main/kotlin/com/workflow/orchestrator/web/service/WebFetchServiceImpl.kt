package com.workflow.orchestrator.web.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.web.WebPage
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import com.workflow.orchestrator.core.web.SubagentSpawner
import com.workflow.orchestrator.core.web.WebFetchService
import com.workflow.orchestrator.web.audit.WebAuditLog
import com.workflow.orchestrator.web.service.sanitizer.JsoupReadability
import com.workflow.orchestrator.web.service.sanitizer.SanitizerSubagent
import com.workflow.orchestrator.web.ui.ApprovalGateImpl
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.nio.file.Path
import java.time.Duration

/**
 * @Service thin facade for [WebFetchEngine].
 *
 * The IntelliJ platform instantiates this class with only [project] and [cs] as
 * constructor args. All 8 business-logic dependencies are wired here from default
 * production sources (project services, HttpClientFactory, etc.).
 *
 * Tests should NOT use this class — they should construct [WebFetchEngine] directly
 * with mocked deps.
 */
@Service(Service.Level.PROJECT)
class WebFetchServiceImpl(
    private val project: Project,
    private val cs: CoroutineScope,
) : WebFetchService {

    /** Lazy-initialized production engine with all default deps. */
    private val engine: WebFetchEngine by lazy { buildEngine() }

    override suspend fun fetch(request: WebFetchService.WebFetchRequest): ToolResult<WebPage> =
        engine.fetch(request)

    // ─────────────────────────────────────────────────────────────────────────
    // Production wiring
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildEngine(): WebFetchEngine {
        val settings = project.service<PluginSettings>()
        val state = settings.state

        // Fetch client template: followRedirects=false (we walk redirects manually to re-screen
        // each hop), plus StripAuthHeadersInterceptor to guarantee no credential leak on any
        // request. The shared template owns the ConnectionPool, dispatcher, timeouts, and
        // interceptors; per-call clients are derived via `newBuilder().dns(pinnedDns).build()`
        // so DNS pinning is per-call (S1 DNS-rebinding fix) while connection reuse is preserved.
        val fetchClientTemplate = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(Duration.ofSeconds(state.webConnectTimeoutSec.toLong()))
            .readTimeout(Duration.ofSeconds(state.webReadTimeoutSec.toLong()))
            .addInterceptor(StripAuthHeadersInterceptor())
            .build()

        // Shortener resolver uses its own no-auth client (HEAD/GET to shortener services).
        // I12 — defense-in-depth: install StripAuthHeadersInterceptor so a stray
        // Authorization / Cookie header on a shortener call cannot leak credentials
        // to a third-party redirector.
        val shortenerClient = OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(Duration.ofSeconds(state.webConnectTimeoutSec.toLong()))
            .readTimeout(Duration.ofSeconds(5))
            .addInterceptor(StripAuthHeadersInterceptor())
            .build()

        val sanitizerSubagent = SanitizerSubagent(
            project.service<SubagentSpawner>()
        )

        val auditLogDir: Path = run {
            val basePath = project.basePath ?: System.getProperty("user.home")
            val logsDir = ProjectIdentifier.logsDir(basePath)
            logsDir.toPath().resolve("web")
        }

        val fetchCache = if (state.webFetchCacheEnabled) {
            com.workflow.orchestrator.web.service.cache.WebFetchCache(
                maxEntries = state.webFetchCacheMaxEntries,
                ttl = Duration.ofMinutes(state.webFetchCacheTtlMinutes.toLong()),
            )
        } else null

        return WebFetchEngine(
            project = project,
            settings = settings,
            clientFactory = { pinnedDns ->
                fetchClientTemplate.newBuilder().dns(pinnedDns).build()
            },
            sanitizer = JsoupReadability(),
            sanitizerSubagent = sanitizerSubagent,
            approvalGate = ApprovalGateImpl(project),
            auditLog = WebAuditLog(auditLogDir),
            shortenerResolver = ShortenerResolver(shortenerClient),
            fetchCache = fetchCache,
        )
    }
}
