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
import okhttp3.OkHttpClient

/**
 * Resolves the active [SearchProvider] from project and connection settings.
 *
 * Returns null when [PluginSettings.State.webSearchProviderType] is `"NONE"` or unrecognised,
 * which the pipeline interprets as [com.workflow.orchestrator.core.web.WebError.NoProviderConfigured].
 *
 * Constructor-injected so [WebSearchEngine] tests can supply a stub registry.
 */
open class SearchProviderRegistry(private val project: Project, private val client: OkHttpClient) {

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

    open fun resolve(): ResolvedProvider? {
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
}
