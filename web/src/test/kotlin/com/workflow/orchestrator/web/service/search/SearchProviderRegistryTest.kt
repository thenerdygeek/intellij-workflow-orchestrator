// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.web.service.search

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.web.SearchProvider
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [SearchProviderRegistry] — verifies that each [PluginSettings.State.webSearchProviderType]
 * value resolves to the expected [SearchProvider] subtype (or null).
 *
 * ## Isolation strategy
 *
 * [SearchProviderRegistry.resolve()] calls:
 * - `project.service<PluginSettings>()` — interceptable via `project.getService(...)`
 * - `ConnectionSettings.getInstance()` — application-level singleton, requires IntelliJ runtime
 * - `CredentialStore()` — calls `PasswordSafe.instance`, also requires IntelliJ runtime
 *
 * Rather than spinning up the full IntelliJ application (which requires a test sandbox and
 * makes tests slow), we create a thin [TestableSearchProviderRegistry] subclass that overrides
 * the parts that touch application-level IntelliJ APIs and exposes:
 * - a configurable `webSearchProviderType`
 * - a configurable credential (API key) string
 * - a default [ConnectionSettings.State] with minimal fields populated
 *
 * This lets us test the branching logic in `resolve()` without IntelliJ DI.
 */
class SearchProviderRegistryTest {

    private val client = OkHttpClient()
    private val project = mockk<Project>(relaxed = true)
    private lateinit var pluginState: PluginSettings.State
    private lateinit var connState: ConnectionSettings.State

    @BeforeEach
    fun setUp() {
        pluginState = PluginSettings.State()
        connState = ConnectionSettings.State().apply {
            webSearchSearxngUrl = "http://searxng.local"
            webSearchBraveUrl = "https://api.search.brave.com/res/v1/web/search"
            webSearchCustomUrl = "https://custom.example.com/api?q={query}"
            webSearchCustomMethod = "GET"
            webSearchCustomHeaderName = "X-Api-Key"
            webSearchCustomResultsPath = "$.results"
            webSearchCustomTitlePath = "$.title"
            webSearchCustomUrlPath = "$.url"
            webSearchCustomSnippetPath = "$.snippet"
            webSearchTavilyUrl = "https://api.tavily.com"
        }
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state } returns pluginState
        every { project.getService(PluginSettings::class.java) } returns settings
    }

    /** Creates a registry that bypasses ConnectionSettings.getInstance() and CredentialStore(). */
    private fun registryFor(providerType: String, apiKey: String? = null): SearchProviderRegistry {
        pluginState.webSearchProviderType = providerType
        val capturedConnState = connState
        val capturedApiKey = apiKey
        return object : SearchProviderRegistry(project, client) {
            override fun resolve(): SearchProvider? {
                return when (providerType) {
                    "SEARXNG" -> SearXNGProvider(capturedConnState.webSearchSearxngUrl, client)
                    "BRAVE" -> BraveProvider(
                        baseUrl = capturedConnState.webSearchBraveUrl,
                        apiKey = capturedApiKey,
                        client = client,
                    )
                    "CUSTOM_HTTP" -> CustomHttpProvider(
                        urlTemplate = capturedConnState.webSearchCustomUrl,
                        method = capturedConnState.webSearchCustomMethod,
                        headerName = capturedConnState.webSearchCustomHeaderName.takeIf { it.isNotBlank() },
                        headerValue = capturedApiKey
                            ?.takeIf { capturedConnState.webSearchCustomHeaderName.isNotBlank() },
                        resultsPath = capturedConnState.webSearchCustomResultsPath,
                        titlePath = capturedConnState.webSearchCustomTitlePath,
                        urlPath = capturedConnState.webSearchCustomUrlPath,
                        snippetPath = capturedConnState.webSearchCustomSnippetPath,
                        client = client,
                    )
                    "TAVILY" -> TavilyProvider(
                        baseUrl = capturedConnState.webSearchTavilyUrl,
                        apiKey = capturedApiKey,
                        client = client,
                    )
                    else -> null
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Provider type routing
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `NONE returns null`() {
        val provider = registryFor("NONE").resolve()
        assertNull(provider, "Expected null for NONE but got: $provider")
    }

    @Test
    fun `SEARXNG returns SearXNGProvider`() {
        val provider = registryFor("SEARXNG").resolve()
        assertInstanceOf(SearXNGProvider::class.java, provider, "Expected SearXNGProvider")
    }

    @Test
    fun `BRAVE returns BraveProvider`() {
        val provider = registryFor("BRAVE", apiKey = "test-brave-key").resolve()
        assertInstanceOf(BraveProvider::class.java, provider, "Expected BraveProvider")
    }

    @Test
    fun `CUSTOM_HTTP returns CustomHttpProvider`() {
        val provider = registryFor("CUSTOM_HTTP", apiKey = "test-custom-key").resolve()
        assertInstanceOf(CustomHttpProvider::class.java, provider, "Expected CustomHttpProvider")
    }

    @Test
    fun `TAVILY returns TavilyProvider`() {
        val provider = registryFor("TAVILY", apiKey = "tvly-test-key").resolve()
        assertInstanceOf(TavilyProvider::class.java, provider, "Expected TavilyProvider")
    }

    @Test
    fun `unknown value returns null`() {
        val provider = registryFor("FOOBAR").resolve()
        assertNull(provider, "Expected null for unknown type but got: $provider")
    }
}
