package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.ModelCatalogService
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ContentPart
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    // ---- Multimodal-agent Phase 6 — image-token estimation + compaction stripping ----

    @Test
    fun `compactTurn strips image parts and substitutes placeholder`() {
        val mgr = ContextManager()
        val original = ChatMessage(
            role = "user",
            content = "what is this?",
            parts = listOf(
                ContentPart.Image("abc", "image/png", null),
                ContentPart.Text("what is this?"),
            ),
        )
        val compacted = mgr.compactTurn(original)
        assertNull(compacted.parts, "parts should be cleared after compaction")
        assertNotNull(compacted.content)
        assertTrue(
            compacted.content!!.contains("[image attached earlier"),
            "compacted content should carry the placeholder. actual: '${compacted.content}'",
        )
    }

    @Test
    fun `compactTurn leaves text-only messages unchanged`() {
        val mgr = ContextManager()
        val original = ChatMessage(role = "user", content = "hello")
        val compacted = mgr.compactTurn(original)
        assertEquals(original, compacted)
    }

    @Test
    fun `compactTurn is idempotent on already-compacted messages`() {
        val mgr = ContextManager()
        val original = ChatMessage(
            role = "user",
            content = "hi",
            parts = listOf(ContentPart.Image("abc", "image/png", null)),
        )
        val once = mgr.compactTurn(original)
        val twice = mgr.compactTurn(once)
        assertEquals(once, twice)
    }

    @Test
    fun `estimateMessageTokens credits image parts at the per-image default`() {
        val mgr = ContextManager()
        val textOnly = ChatMessage(role = "user", content = "x".repeat(35))   // ~10 text tokens
        val withImage = ChatMessage(
            role = "user",
            content = "x".repeat(35),
            parts = listOf(ContentPart.Image("abc", "image/png", null)),
        )
        val textTokens = mgr.estimateMessageTokens(textOnly)
        val withImageTokens = mgr.estimateMessageTokens(withImage)
        // The delta MUST be exactly the image-token default — neither padded nor halved.
        assertEquals(
            ContextManager.IMAGE_TOKEN_ESTIMATE_DEFAULT,
            withImageTokens - textTokens,
            "image part should add exactly IMAGE_TOKEN_ESTIMATE_DEFAULT tokens to the per-message estimate",
        )
    }

    @Test
    fun `tokenEstimate counts image parts in the aggregate`() {
        val mgr = ContextManager()
        mgr.setSystemPrompt("system")
        // Phase 6 adds image parts to ChatMessage; ContextManager should
        // include them in the aggregate estimate so compaction triggers
        // before the gateway rejects on context-length.
        mgr.addAssistantMessage(
            ChatMessage(
                role = "user",
                content = "look at this",
                parts = listOf(
                    ContentPart.Image("abc", "image/png", null),
                    ContentPart.Image("def", "image/jpeg", null),
                ),
            ),
        )
        val estimate = mgr.tokenEstimate()
        // Two images @ 1500 tokens each = 3000 minimum, regardless of text overhead.
        assertTrue(
            estimate >= 2 * ContextManager.IMAGE_TOKEN_ESTIMATE_DEFAULT,
            "tokenEstimate ($estimate) must include at least the image-cost contribution (3000)",
        )
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
