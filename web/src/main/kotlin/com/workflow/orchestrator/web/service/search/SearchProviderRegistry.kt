// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.web.service.search

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.web.SearchProvider
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * Resolves the active [SearchProvider] from project and connection settings.
 *
 * Returns null when [PluginSettings.State.webSearchProviderType] is `"NONE"` or unrecognised,
 * which the pipeline interprets as [com.workflow.orchestrator.core.web.WebError.NoProviderConfigured].
 *
 * Constructor-injected so [WebSearchEngine] tests can supply a stub registry.
 *
 * **S1 — per-call DNS pinning.** Callers pass a per-call [okhttp3.Dns] (typically a
 * [com.workflow.orchestrator.web.service.PinnedDns]) so OkHttp's DNS resolution is bound to
 * the same addresses the SSRF guard cleared. Without this, an attacker-controlled DNS
 * record could resolve to a public IP for the safety check and 127.0.0.1 on the actual
 * outbound call.
 */
open class SearchProviderRegistry(
    private val project: Project,
    private val clientFactory: (Dns) -> OkHttpClient,
) {

    /**
     * Backwards-compatible constructor for tests that pass a single shared [OkHttpClient].
     * The DNS pinning hook is still applied per-call via `newBuilder().dns(pinnedDns).build()`
     * so connection pool / interceptors / timeouts survive while DNS is per-call.
     */
    constructor(project: Project, client: OkHttpClient) : this(
        project = project,
        clientFactory = { pinnedDns -> client.newBuilder().dns(pinnedDns).build() },
    )

    /**
     * Resolved provider plus the effective base URL to screen via [UrlSafetyGuard].
     *
     * [baseUrl] is the raw setting value (not the full constructed search URL with query appended).
     * For CUSTOM_HTTP the `{query}` placeholder has been stripped before this field is set so that
     * [UrlPipeline] / [UrlSafetyGuard] can parse the host without the template literal.
     *
     * [allowLoopback] is true only for SearXNG, where a local instance is the common deployment.
     */
    data class ResolvedProvider(
        val provider: SearchProvider,
        val baseUrl: String,
        val allowLoopback: Boolean,
    )

    /**
     * Resolves the active provider, constructing it with an OkHttp client whose DNS is
     * pinned for this call. [pinnedDns] should be a fresh [com.workflow.orchestrator.web.service.PinnedDns]
     * unique to the calling search request — never reused across calls.
     */
    open fun resolve(pinnedDns: Dns): ResolvedProvider? {
        val client = clientFactory(pinnedDns)
        val settings = project.service<PluginSettings>().state
        val conn = ConnectionSettings.getInstance().state
        val creds = CredentialStore()
        return when (settings.webSearchProviderType) {
            "SEARXNG" -> ResolvedProvider(
                provider = SearXNGProvider(conn.webSearchSearxngUrl, client),
                baseUrl = conn.webSearchSearxngUrl,
                allowLoopback = true,  // SearXNG is commonly self-hosted on localhost
            )
            "BRAVE" -> ResolvedProvider(
                provider = BraveProvider(
                    baseUrl = conn.webSearchBraveUrl,
                    apiKey = creds.getToken(ServiceType.WEB_SEARCH),
                    client = client,
                ),
                baseUrl = conn.webSearchBraveUrl,
                allowLoopback = false,
            )
            "CUSTOM_HTTP" -> ResolvedProvider(
                provider = CustomHttpProvider(
                    urlTemplate = conn.webSearchCustomUrl,
                    method = conn.webSearchCustomMethod,
                    headerName = conn.webSearchCustomHeaderName.takeIf { it.isNotBlank() },
                    headerValue = creds.getToken(ServiceType.WEB_SEARCH)
                        ?.takeIf { conn.webSearchCustomHeaderName.isNotBlank() },
                    resultsPath = conn.webSearchCustomResultsPath,
                    titlePath = conn.webSearchCustomTitlePath,
                    urlPath = conn.webSearchCustomUrlPath,
                    snippetPath = conn.webSearchCustomSnippetPath,
                    client = client,
                ),
                // Strip the {query} placeholder so UrlPipeline can parse the host cleanly.
                baseUrl = conn.webSearchCustomUrl.replace("{query}", "PLACEHOLDER"),
                allowLoopback = false,
            )
            "TAVILY" -> ResolvedProvider(
                provider = TavilyProvider(
                    baseUrl = conn.webSearchTavilyUrl,
                    apiKey = creds.getToken(ServiceType.WEB_SEARCH),
                    client = client,
                ),
                baseUrl = conn.webSearchTavilyUrl,
                allowLoopback = false,
            )
            else -> null
        }
    }

    /**
     * Legacy no-arg overload for callers that haven't been refactored to pass a per-call DNS.
     * Uses the system DNS — DOES NOT guarantee DNS pinning. Prefer [resolve(Dns)].
     */
    open fun resolve(): ResolvedProvider? = resolve(Dns.SYSTEM)
}
