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

    open fun resolve(): SearchProvider? {
        val settings = project.service<PluginSettings>().state
        val conn = ConnectionSettings.getInstance().state
        val creds = CredentialStore()
        return when (settings.webSearchProviderType) {
            "SEARXNG" -> SearXNGProvider(conn.webSearchSearxngUrl, client)
            "BRAVE" -> BraveProvider(
                baseUrl = conn.webSearchBraveUrl,
                apiKey = creds.getToken(ServiceType.WEB_SEARCH),
                client = client,
            )
            "CUSTOM_HTTP" -> CustomHttpProvider(
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
            )
            else -> null
        }
    }
}
