package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ClientConfig
import com.workflow.orchestrator.core.ai.dto.ContextWindow
import com.workflow.orchestrator.core.ai.dto.ModelCatalog
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Service tests for [ModelCatalogService].
 *
 * Multimodal-agent Phase 2 — verifies the catalog-service contract that downstream
 * phases (3, 5, 6, 7) depend on:
 * - Live read of `/.api/modelconfig/supported-models.json` and `/.api/client-config`
 * - Per-tier `modelConfigAllTiers` override is preferred over the misleading top-level cap
 * - 1-hour TTL caching with `force=true` bypass
 * - Vision/tools capability detection via the catalog (NOT model-name heuristics)
 * - Auth failures degrade gracefully to `null` (no exception)
 *
 * Construction matches the Sourcegraph isolation pattern (`SourcegraphChatClient`):
 * the service accepts an injectable OkHttpClient instead of going through
 * `HttpClientFactory.clientFor()` — see `project_sourcegraph_isolation.md`.
 */
class ModelCatalogServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: ModelCatalogService

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        service = ModelCatalogService(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "sgp_test_token" },
            httpClientOverride = OkHttpClient.Builder().build()
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getCatalog returns parsed catalog for valid 200 response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_MODEL_CATALOG_JSON))
        val catalog = service.getCatalog()
        assertNotNull(catalog)
        assertEquals("6.12.5040", catalog!!.revision)
        assertEquals(2, catalog.models.size)
    }

    @Test
    fun `getContextWindow reads modelConfigAllTiers override, not top-level cap`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_MODEL_CATALOG_JSON))
        service.getCatalog()
        val window = service.getContextWindow(
            "anthropic::2024-10-22::claude-sonnet-4-5-latest",
            "enterprise"
        )
        assertNotNull(window)
        // Tier override (132K), NOT the top-level 45K cap.
        assertEquals(132000, window!!.maxInputTokens)
        assertEquals(8192, window.maxOutputTokens)
        assertEquals(18000, window.maxUserInputTokens)
    }

    @Test
    fun `getContextWindow falls back to top-level when no tier override exists`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_MODEL_CATALOG_JSON))
        service.getCatalog()
        // The free-tier model in the sample has no modelConfigAllTiers — should
        // fall back to the top-level contextWindow.
        val window = service.getContextWindow(
            "google::v1::gemini-2.0-flash",
            "enterprise"
        )
        assertNotNull(window)
        assertEquals(8000, window!!.maxInputTokens)
    }

    @Test
    fun `supportsVision is true for catalog model with vision capability`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_MODEL_CATALOG_JSON))
        service.getCatalog()
        assertTrue(service.supportsVision("anthropic::2024-10-22::claude-sonnet-4-5-latest"))
    }

    @Test
    fun `supportsVision is false for catalog model without vision capability`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_MODEL_CATALOG_JSON))
        service.getCatalog()
        assertFalse(service.supportsVision("google::v1::gemini-2.0-flash"))
    }

    @Test
    fun `supportsTools is true for catalog model with tools capability`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_MODEL_CATALOG_JSON))
        service.getCatalog()
        assertTrue(service.supportsTools("anthropic::2024-10-22::claude-sonnet-4-5-latest"))
    }

    @Test
    fun `getDefaultChatModel returns null before catalog is loaded`() {
        assertNull(service.getDefaultChatModel())
    }

    @Test
    fun `getDefaultChatModel returns catalog defaultModels chat after load`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_MODEL_CATALOG_JSON))
        service.getCatalog()
        assertEquals(
            "anthropic::2024-10-22::claude-sonnet-4-5-latest",
            service.getDefaultChatModel()
        )
    }

    @Test
    fun `getStatus returns model status when loaded`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_MODEL_CATALOG_JSON))
        service.getCatalog()
        assertEquals("stable", service.getStatus("anthropic::2024-10-22::claude-sonnet-4-5-latest"))
    }

    @Test
    fun `getStatus returns null for unknown model`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_MODEL_CATALOG_JSON))
        service.getCatalog()
        assertNull(service.getStatus("unknown::model::name"))
    }

    @Test
    fun `getClientConfig returns latestSupportedCompletionsStreamAPIVersion`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_CLIENT_CONFIG_JSON))
        val cfg = service.getClientConfig()
        assertNotNull(cfg)
        assertEquals(9, cfg!!.latestSupportedCompletionsStreamAPIVersion)
    }

    @Test
    fun `getLatestStreamApiVersion defaults to 8 when client-config not loaded`() {
        assertEquals(8, service.getLatestStreamApiVersion())
    }

    @Test
    fun `getCatalog returns null on HTTP 401, no exception`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        assertNull(service.getCatalog())
    }

    @Test
    fun `cache returns within TTL without re-fetching`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_MODEL_CATALOG_JSON))
        val first = service.getCatalog()
        // No second enqueue — would fail with HTTP 404 from MockWebServer if hit.
        val second = service.getCatalog()
        assertSame(first, second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `force=true bypasses cache`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_MODEL_CATALOG_JSON))
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_MODEL_CATALOG_JSON))
        service.getCatalog()
        service.getCatalog(force = true)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `tokenProvider null returns null without calling network`() = runBlocking {
        val noTokenService = ModelCatalogService(
            baseUrl = server.url("/").toString(),
            tokenProvider = { null },
            httpClientOverride = OkHttpClient.Builder().build()
        )
        val catalog = noTokenService.getCatalog()
        assertNull(catalog)
        assertEquals(0, server.requestCount)
    }

    /**
     * Phase 2 review followup: locks in the production auth-header contract.
     *
     * The default `httpClientOverride = null` path constructs an OkHttpClient with
     * AuthInterceptor(tokenProvider, AuthScheme.TOKEN). This test asserts the actual
     * `Authorization: token <sgp_...>` header reaches the wire — the other tests
     * inject a plain OkHttpClient (no AuthInterceptor) and never exercise this path.
     *
     * If a future refactor swaps AuthScheme.TOKEN to BEARER, the request would get
     * `Authorization: Bearer <token>`, which Sourcegraph rejects with 401. Without
     * this test, that regression would land green and silently break production.
     */
    @Test
    fun `production httpClient default emits Authorization token header per Sourcegraph contract`() = runBlocking {
        val productionService = ModelCatalogService(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "sgp_test_token_for_auth_check" },
            // httpClientOverride omitted → uses the lazy default construction with AuthInterceptor
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_MODEL_CATALOG_JSON))
        productionService.getCatalog()
        val recorded = server.takeRequest()
        assertEquals(
            "token sgp_test_token_for_auth_check",
            recorded.getHeader("Authorization"),
            "Production httpClient must emit 'Authorization: token <sgp_...>' (NOT 'Bearer ...'). " +
            "AuthScheme.TOKEN is the only correct scheme for Sourcegraph endpoints per " +
            "project_sourcegraph_isolation.md. If this fails, check that ModelCatalogService " +
            "still uses AuthInterceptor(tokenProvider, AuthScheme.TOKEN) in its default httpClient."
        )
    }

    companion object {
        const val SAMPLE_MODEL_CATALOG_JSON = """
            {
              "schemaVersion": "1.0",
              "revision": "6.12.5040",
              "providers": [{"id":"anthropic","displayName":"Anthropic"}],
              "models": [
                {
                  "modelRef": "anthropic::2024-10-22::claude-sonnet-4-5-latest",
                  "displayName": "Claude Sonnet 4.5",
                  "modelName": "claude-sonnet-4-5-20250929",
                  "capabilities": ["chat","vision","tools"],
                  "category": "accuracy",
                  "status": "stable",
                  "tier": "enterprise",
                  "contextWindow": {"maxInputTokens": 45000, "maxOutputTokens": 4000},
                  "modelConfigAllTiers": {
                    "enterprise": {
                      "contextWindow": {"maxInputTokens": 132000, "maxOutputTokens": 8192, "maxUserInputTokens": 18000}
                    }
                  }
                },
                {
                  "modelRef": "google::v1::gemini-2.0-flash",
                  "displayName": "Gemini 2.0 Flash",
                  "modelName": "gemini-2.0-flash",
                  "capabilities": ["chat"],
                  "category": "speed",
                  "status": "stable",
                  "tier": "free",
                  "contextWindow": {"maxInputTokens": 8000, "maxOutputTokens": 4000}
                }
              ],
              "defaultModels": {
                "chat": "anthropic::2024-10-22::claude-sonnet-4-5-latest",
                "fastChat": "anthropic::2024-10-22::claude-haiku-4-5-latest",
                "codeCompletion": "anthropic::2024-10-22::claude-haiku-4-5-latest",
                "fallbackChat": "google::v1::gemini-2.0-flash"
              }
            }
        """

        const val SAMPLE_CLIENT_CONFIG_JSON = """
            {
              "codyEnabled": true,
              "chatEnabled": true,
              "autoCompleteEnabled": true,
              "customCommandsEnabled": true,
              "attributionEnabled": true,
              "smartContextWindowEnabled": true,
              "modelsAPIEnabled": true,
              "latestSupportedCompletionsStreamAPIVersion": 9
            }
        """
    }
}
