// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Source-text regression for the model-selection priority in [BrainFactory.create] (extracted
 * from `AgentService.createBrain` in Phase 3 cut D). The pure precedence is now also covered
 * behaviorally by `BrainModelResolutionTest`; this scan additionally pins the call-site wiring
 * inside `create()` (saved-settings read before the pickBest fetch).
 *
 * Bug reproduced 2026-05-06: a user picked Sonnet 4.5 in the model chip, but every fresh
 * task created a brain which called `ModelCache.pickBest()` (Opus thinking first) BEFORE
 * consulting `AgentSettings.sourcegraphChatModel`. The user's saved selection was silently
 * overridden, the API was called with Opus, and the cost meter showed ~5x the rate expected
 * for Sonnet.
 *
 * The fix flips priority: settings.sourcegraphChatModel wins; pickBest is only used on
 * first-launch when settings is blank. This test pins the order via source-text scan
 * so a future "let's always auto-pick the best" refactor can't silently regress it.
 */
class CreateBrainModelPriorityTest {

    private val brainFactorySrc: String by lazy {
        // Resolve robustly regardless of Gradle's working directory (project root vs module).
        val rel = "src/main/kotlin/com/workflow/orchestrator/agent/brain/BrainFactory.kt"
        val candidates = listOf(
            Paths.get(rel),
            Paths.get("agent", rel),
            Paths.get("..", "agent", rel),
        )
        val found = candidates.firstOrNull { Files.exists(it) }
            ?: error("Could not locate BrainFactory.kt; tried: $candidates from cwd=${System.getProperty("user.dir")}")
        Files.readString(found)
    }

    @Test
    fun `createBrain reads sourcegraphChatModel before calling pickBest`() {
        val src = brainFactorySrc
        val createBrainStart = src.indexOf("suspend fun create(")
        assertTrue(createBrainStart >= 0, "create() not found in BrainFactory.kt")

        // Match real code, not KDoc. `state.sourcegraphChatModel` is the property access
        // (not just the property name), and `ModelCache.pickBest(` (open-paren) is the
        // actual function call (not the bracketed `[ModelCache.pickBest]` doc reference).
        val settingsReadIdx = src.indexOf("state.sourcegraphChatModel", createBrainStart)
        val pickBestIdx = src.indexOf("ModelCache.pickBest(", createBrainStart)

        assertTrue(settingsReadIdx >= 0,
            "createBrain() must read AgentSettings.sourcegraphChatModel — the user's saved " +
            "selection from the model chip. Without this read, the chip is ignored.")
        assertTrue(pickBestIdx >= 0,
            "createBrain() should still call ModelCache.pickBest as a first-launch fallback.")
        assertTrue(settingsReadIdx < pickBestIdx,
            "PRIORITY REGRESSION: createBrain() must consult settings.sourcegraphChatModel " +
            "BEFORE ModelCache.pickBest(). Current order has pickBest first, which silently " +
            "overrides the user's chip selection on every fresh task — leading to 5x over-billing " +
            "when the user picked Sonnet but the auto-pick swaps in Opus.")
    }
}
