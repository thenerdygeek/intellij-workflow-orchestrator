package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.ModelCatalogService
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 6 of multimodal-agent plan — Task 6.3a.
 *
 * `ModelFallbackManager.fallbackChainForVision()` must exclude any model
 * whose `capabilities` field does NOT include `"vision"`. Without this
 * filter, an image-bearing turn that triggers a fallback could silently
 * land on a non-vision model — the gateway strips image content server-side
 * and the user gets a confusing reply with no error.
 *
 * Test was written first (TDD red→green): the production implementation
 * was added immediately after observing this test fail to find the method.
 */
class ModelFallbackVisionFilterTest {

    @Test
    fun `fallbackChainForVision excludes non-vision models`() {
        val mgr = ModelFallbackManager(
            fallbackChain = listOf("a::vision", "b::no-vision", "c::vision"),
        )
        val catalog = StubCatalog(visionSupport = mapOf("a::vision" to true, "b::no-vision" to false, "c::vision" to true))
        val chain = mgr.fallbackChainForVision(catalog)
        assertEquals(listOf("a::vision", "c::vision"), chain)
    }

    @Test
    fun `fallbackChainForVision returns empty list when no model is vision-capable`() {
        val mgr = ModelFallbackManager(fallbackChain = listOf("a::no-vision", "b::no-vision"))
        val catalog = StubCatalog(visionSupport = mapOf("a::no-vision" to false, "b::no-vision" to false))
        val chain = mgr.fallbackChainForVision(catalog)
        assertTrue(chain.isEmpty(), "non-vision-only chain must produce empty filtered list")
    }

    @Test
    fun `fallbackChainForVision passes through fully vision-capable chain`() {
        val mgr = ModelFallbackManager(fallbackChain = listOf("a::vision", "b::vision", "c::vision"))
        val catalog = StubCatalog(visionSupport = mapOf("a::vision" to true, "b::vision" to true, "c::vision" to true))
        val chain = mgr.fallbackChainForVision(catalog)
        assertEquals(listOf("a::vision", "b::vision", "c::vision"), chain)
    }

    @Test
    fun `fallbackChainForVision treats unknown model as non-vision (catalog-not-loaded safety)`() {
        // When the catalog hasn't been fetched yet (network failure at startup, cold cache),
        // `supportsVision()` returns false. Vision-only routing must not silently route
        // image-bearing turns through a model the catalog has not yet vouched for.
        val mgr = ModelFallbackManager(fallbackChain = listOf("known::vision", "unknown::model"))
        val catalog = StubCatalog(visionSupport = mapOf("known::vision" to true)) // unknown::model not in map
        val chain = mgr.fallbackChainForVision(catalog)
        assertEquals(listOf("known::vision"), chain)
    }

    @Test
    fun `fullFallbackChain returns the unfiltered chain`() {
        val mgr = ModelFallbackManager(fallbackChain = listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), mgr.fullFallbackChain())
    }
}

/**
 * Stub `ModelCatalogService` that returns vision capability per the supplied
 * map. `supportsVision` is the only method this test needs; everything else
 * inherits the no-op cold-cache behavior of the real class.
 *
 * `ModelCatalogService` is `open` per Phase 2 deviation (handoff line 118).
 */
private class StubCatalog(
    private val visionSupport: Map<String, Boolean>,
) : ModelCatalogService(
    baseUrl = "http://stub",
    tokenProvider = { "stub_token" },
    httpClientOverride = OkHttpClient.Builder().build(),
) {
    override fun supportsVision(modelRef: String): Boolean = visionSupport[modelRef] == true
}
