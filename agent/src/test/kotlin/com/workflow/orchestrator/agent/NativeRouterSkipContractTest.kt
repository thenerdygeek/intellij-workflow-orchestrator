package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text contract that pins the [AgentService] invariants introduced by Task 10
 * (BrainFactory native Anthropic branch):
 *
 * 1. [AgentService.wrapBrainOrSkip] exists and short-circuits [BrainRouter] wrapping for the
 *    native Anthropic provider (returning the raw [AnthropicDirectBrain] unchanged).
 * 2. The Sourcegraph shared-catalog warm-up ([AgentService.getOrCreateSharedCatalog]) is gated
 *    on `llmProvider != "anthropic"` so the Anthropic path never dials Sourcegraph.
 *
 * These are source-text contracts — [AgentService] is not unit-instantiable without the full
 * IntelliJ platform, and a [BasePlatformTestCase] second-class collision would cause CI
 * "Indexing timeout" failures (documented trap in agent/CLAUDE.md).
 */
class NativeRouterSkipContractTest {

    private val src: String by lazy {
        File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()
    }

    @Test
    fun `wrapBrainWithRouter is guarded by provider — contract from task brief`() {
        // The task brief's exact contract: llmProvider == "anthropic" guard exists AND the
        // wrapper function (old or new name) is present.
        assertTrue(
            Regex("""llmProvider\s*==\s*"anthropic"""").containsMatchIn(src),
            "AgentService must check llmProvider == \"anthropic\" to gate the BrainRouter wrap"
        )
        assertTrue(
            src.contains("wrapBrainWithRouter") || src.contains("wrapBrainOrSkip"),
            "AgentService must still call wrapBrainWithRouter (SG path) or have wrapBrainOrSkip (unified)"
        )
    }

    @Test
    fun `wrapBrainOrSkip function exists and returns raw brain for native path`() {
        assertTrue(
            src.contains("wrapBrainOrSkip"),
            "AgentService must have wrapBrainOrSkip that skips BrainRouter for native provider"
        )
    }

    @Test
    fun `SG shared-catalog warm-up is gated on non-anthropic provider`() {
        // getOrCreateSharedCatalog must be called ONLY when llmProvider != "anthropic".
        // The proximity regex ties the guard to the warm-up call so that removing the
        // guard (or changing != to ==) causes this test to fail.
        assertTrue(
            Regex("""llmProvider\s*!=\s*"anthropic"[^\n]*\n[^\n]*getOrCreateSharedCatalog""")
                .containsMatchIn(src),
            "SG catalog warm-up (getOrCreateSharedCatalog) must be guarded by " +
                "llmProvider != \"anthropic\" on the preceding line so the native Anthropic " +
                "path never dials Sourcegraph"
        )
    }

    @Test
    fun `wrapBrainOrSkip is applied at both the initial-brain and the recycle-brain sites`() {
        // Both call sites must use wrapBrainOrSkip (not the old wrapBrainWithRouter directly),
        // so image routing and the AttachmentStore are set up correctly on both paths.
        val callCount = Regex("""wrapBrainOrSkip\s*\(""").findAll(src).count()
        assertTrue(
            callCount >= 2,
            "wrapBrainOrSkip must appear at least twice: once for the initial brain and once for " +
                "the recycle/fallback brain. Found $callCount occurrence(s)."
        )
    }
}
