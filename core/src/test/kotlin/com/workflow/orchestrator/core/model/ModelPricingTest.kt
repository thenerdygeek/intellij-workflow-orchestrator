// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.core.model

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Tests for [ModelPricing.computeCost] and [ModelPricingRegistry].
 *
 * Coverage:
 * - Cost formula correctness (spot-check against expected dollar amounts)
 * - Registry lookup by both raw (prefixed) and bare model IDs
 * - Null returned for unknown models, no exception thrown
 * - Hot-reload: user override file is picked up after [ModelPricingRegistry.reload]
 */
class ModelPricingTest {

    @BeforeEach
    fun setUp() {
        ModelPricingRegistry.resetForTests()
        ModelPricingRegistry.reload()
    }

    @AfterEach
    fun tearDown() {
        ModelPricingRegistry.resetForTests()
        // Restore real override path and reload so the singleton is clean for subsequent tests
        ModelPricingRegistry.reload()
    }

    // ----- Cost formula -----

    @Test
    fun `1M input plus 1M output at Sonnet pricing equals 18 dollars exactly`() {
        // claude-sonnet-4: $3.00 in + $15.00 out = $18.00 for 1M+1M
        val pricing = ModelPricing(
            modelId = "claude-sonnet-4",
            inputUsdPer1M = 3.00,
            outputUsdPer1M = 15.00,
            cacheReadUsdPer1M = 0.30,
            cacheWriteUsdPer1M = 3.75,
        )
        val cost = pricing.computeCost(inputTokens = 1_000_000, outputTokens = 1_000_000)
        assertEquals(18.00, cost, 1e-9)
    }

    @Test
    fun `1M cache-read tokens at Sonnet pricing equals 0_30 dollars`() {
        val pricing = ModelPricing(
            modelId = "claude-sonnet-4",
            inputUsdPer1M = 3.00,
            outputUsdPer1M = 15.00,
            cacheReadUsdPer1M = 0.30,
            cacheWriteUsdPer1M = 3.75,
        )
        val cost = pricing.computeCost(
            inputTokens = 0,
            outputTokens = 0,
            cacheReadTokens = 1_000_000,
        )
        assertEquals(0.30, cost, 1e-9)
    }

    @Test
    fun `1M cache-write tokens at Sonnet pricing equals 3_75 dollars`() {
        val pricing = ModelPricing(
            modelId = "claude-sonnet-4",
            inputUsdPer1M = 3.00,
            outputUsdPer1M = 15.00,
            cacheReadUsdPer1M = 0.30,
            cacheWriteUsdPer1M = 3.75,
        )
        val cost = pricing.computeCost(
            inputTokens = 0,
            outputTokens = 0,
            cacheWriteTokens = 1_000_000,
        )
        assertEquals(3.75, cost, 1e-9)
    }

    @Test
    fun `null cache rates treated as zero in cost computation`() {
        val pricing = ModelPricing(
            modelId = "gpt-4o",
            inputUsdPer1M = 2.50,
            outputUsdPer1M = 10.00,
            // no cache fields
        )
        val cost = pricing.computeCost(
            inputTokens = 0,
            outputTokens = 0,
            cacheReadTokens = 1_000_000,
            cacheWriteTokens = 1_000_000,
        )
        assertEquals(0.0, cost, 1e-9)
    }

    @Test
    fun `Opus pricing 1M in plus 1M out equals 90 dollars`() {
        val pricing = ModelPricing(
            modelId = "claude-opus-4",
            inputUsdPer1M = 15.00,
            outputUsdPer1M = 75.00,
        )
        assertEquals(90.00, pricing.computeCost(1_000_000, 1_000_000), 1e-9)
    }

    // ----- Registry lookup -----

    @Test
    fun `lookup by bare normalized model id succeeds`() {
        val entry = ModelPricingRegistry.lookup("claude-sonnet-4")
        assertNotNull(entry)
        assertEquals("claude-sonnet-4", entry!!.modelId)
        assertEquals(3.00, entry.inputUsdPer1M, 1e-9)
        assertEquals(15.00, entry.outputUsdPer1M, 1e-9)
    }

    @Test
    fun `lookup by raw Sourcegraph-prefixed model id normalizes and finds entry`() {
        val entry = ModelPricingRegistry.lookup("anthropic::v1::claude-sonnet-4-20250514")
        assertNotNull(entry)
        assertEquals("claude-sonnet-4", entry!!.modelId)
    }

    @Test
    fun `lookup for thinking variant returns separate entry`() {
        val entry = ModelPricingRegistry.lookup("anthropic::v1::claude-opus-4-20250514-thinking")
        assertNotNull(entry)
        assertEquals("claude-opus-4-thinking", entry!!.modelId)
    }

    @Test
    fun `lookup for unknown model returns null without throwing`() {
        val entry = ModelPricingRegistry.lookup("unknown-model-xyz-99")
        assertNull(entry)
    }

    @Test
    fun `lookup for blank model id returns null without throwing`() {
        val entry = ModelPricingRegistry.lookup("")
        assertNull(entry)
    }

    @Test
    fun `gpt-4o lookup succeeds and has no cache rates`() {
        val entry = ModelPricingRegistry.lookup("gpt-4o")
        assertNotNull(entry)
        assertEquals(2.50, entry!!.inputUsdPer1M, 1e-9)
        assertEquals(10.00, entry.outputUsdPer1M, 1e-9)
        assertNull(entry.cacheReadUsdPer1M)
        assertNull(entry.cacheWriteUsdPer1M)
    }

    @Test
    fun `all bundled Sonnet variants are present in registry`() {
        for (variant in listOf(
            "claude-sonnet-4", "claude-sonnet-4-thinking",
            "claude-sonnet-4-5", "claude-sonnet-4-5-thinking",
            "claude-sonnet-4-6", "claude-sonnet-4-6-thinking",
            "claude-sonnet-4-7", "claude-sonnet-4-7-thinking",
        )) {
            assertNotNull(ModelPricingRegistry.lookup(variant), "Missing bundled entry: $variant")
        }
    }

    // ----- Hot-reload via user override -----

    @Test
    fun `reload picks up user override file and new entry is found`(@TempDir tempDir: Path) {
        // Write a custom pricing.json with a made-up model
        val overrideFile = tempDir.resolve("pricing.json")
        overrideFile.writeText(
            """
            {
              "test-model-xyz": { "in": 99.00, "out": 199.00, "cacheRead": 9.00, "cacheWrite": 19.00 }
            }
            """.trimIndent()
        )

        ModelPricingRegistry.overridePath = overrideFile
        ModelPricingRegistry.reload()

        val entry = ModelPricingRegistry.lookup("test-model-xyz")
        assertNotNull(entry, "Custom model should be found after reload")
        assertEquals(99.00, entry!!.inputUsdPer1M, 1e-9)
        assertEquals(199.00, entry.outputUsdPer1M, 1e-9)
        assertEquals(9.00, entry.cacheReadUsdPer1M!!, 1e-9)
        assertEquals(19.00, entry.cacheWriteUsdPer1M!!, 1e-9)

        // Bundled entries must still be present (merge, not replace)
        assertNotNull(ModelPricingRegistry.lookup("claude-sonnet-4"),
            "Bundled entries must survive user override merge")
    }

    @Test
    fun `user override wins over bundled entry for same key`(@TempDir tempDir: Path) {
        val overrideFile = tempDir.resolve("pricing.json")
        overrideFile.writeText(
            """{ "claude-sonnet-4": { "in": 1.00, "out": 2.00 } }"""
        )

        ModelPricingRegistry.overridePath = overrideFile
        ModelPricingRegistry.reload()

        val entry = ModelPricingRegistry.lookup("claude-sonnet-4")
        assertNotNull(entry)
        assertEquals(1.00, entry!!.inputUsdPer1M, 1e-9, "User override should win for same key")
        assertEquals(2.00, entry.outputUsdPer1M, 1e-9)
    }

    @Test
    fun `reload without user override file keeps bundled entries intact`() {
        // Ensure no override file exists — point to a non-existent path
        val fakePath = Path.of(System.getProperty("java.io.tmpdir"), "nonexistent-pricing-xyz.json")
        ModelPricingRegistry.overridePath = fakePath
        ModelPricingRegistry.reload()

        assertNotNull(ModelPricingRegistry.lookup("claude-sonnet-4"))
        assertNotNull(ModelPricingRegistry.lookup("gpt-4o"))
    }
}
