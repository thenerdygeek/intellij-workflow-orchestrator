package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.ModelCatalogService
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration test for ContextManager + ModelCatalogService wiring.
 *
 * Multimodal-agent Phase 2 — verifies the read-side contract that downstream
 * phases depend on: per-model `maxInputTokens` resolves through the catalog
 * (live `modelConfigAllTiers.<tier>.contextWindow`) instead of the legacy
 * 150K constant or a name-heuristic.
 *
 * Wires a real [ModelCatalogService] backed by [MockWebServer] (rather than a
 * mock) — the entire fetch-parse-tier-override path runs.
 */
class ContextManagerCatalogIntegrationTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `maxInputTokensFor returns catalog enterprise tier value, not hard-coded 150K`() = runBlocking {
        val service = newCatalogService()
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_CATALOG_JSON))
        service.getCatalog()

        val mgr = ContextManager(modelCatalogService = service)
        val limit = mgr.maxInputTokensFor("anthropic::2024-10-22::claude-sonnet-4-5-latest")

        // Tier override (132K), NOT the top-level 45K cap and NOT the 150K legacy default.
        assertEquals(132_000, limit)
    }

    @Test
    fun `maxInputTokensFor falls back to FALLBACK_MAX_INPUT_TOKENS when catalog unreachable`() = runBlocking {
        val service = newCatalogService()
        // Don't load the catalog — getContextWindow returns null.
        val mgr = ContextManager(modelCatalogService = service)

        val limit = mgr.maxInputTokensFor("any::model::name")
        assertEquals(ContextManager.FALLBACK_MAX_INPUT_TOKENS, limit)
        assertEquals(90_000, ContextManager.FALLBACK_MAX_INPUT_TOKENS)
    }

    @Test
    fun `maxInputTokensFor falls back to FALLBACK_MAX_INPUT_TOKENS when no catalog service injected`() {
        val mgr = ContextManager(modelCatalogService = null)
        val limit = mgr.maxInputTokensFor("anthropic::2024-10-22::claude-sonnet-4-5-latest")
        assertEquals(ContextManager.FALLBACK_MAX_INPUT_TOKENS, limit)
    }

    @Test
    fun `maxInputTokensFor falls back to FALLBACK when model is not in catalog`() = runBlocking {
        val service = newCatalogService()
        server.enqueue(MockResponse().setResponseCode(200).setBody(SAMPLE_CATALOG_JSON))
        service.getCatalog()

        val mgr = ContextManager(modelCatalogService = service)
        val limit = mgr.maxInputTokensFor("openai::v1::not-in-catalog")
        assertEquals(ContextManager.FALLBACK_MAX_INPUT_TOKENS, limit)
    }

    private fun newCatalogService(): ModelCatalogService = ModelCatalogService(
        baseUrl = server.url("/").toString(),
        tokenProvider = { "sgp_test_token" },
        httpClientOverride = OkHttpClient.Builder().build()
    )

    companion object {
        const val SAMPLE_CATALOG_JSON = """
            {
              "schemaVersion": "1.0",
              "revision": "test-rev",
              "providers": [{"id":"anthropic","displayName":"Anthropic"}],
              "models": [{
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
              }],
              "defaultModels": {
                "chat": "anthropic::2024-10-22::claude-sonnet-4-5-latest"
              }
            }
        """
    }
}
