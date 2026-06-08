package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the fix for the context-usage bar showing the wrong MAX (90K fallback instead of the real
 * window, e.g. 132K). The bar's `setContextUsageProvider` block must derive its max from the
 * ContextManager's RESOLVED budget (`effectiveMaxInputTokens()`, which uses `currentBrainModelId`),
 * NOT from the saved `AgentSettings.sourcegraphChatModel` setting — that setting is blank when the
 * model was auto-picked, which sent the provider down the constructor `maxInputTokens` (90K)
 * fallback while the agent's actual compaction budget used the real 132K window.
 *
 * Source-text pin: the provider lambda is wired into a JCEF panel and can't be exercised headless.
 */
class ContextUsageProviderSourceTest {

    private fun controllerSource(): String {
        val d = System.getProperty("user.dir")
        val root = if (File("$d/src/main/kotlin").isDirectory) {
            File("$d/src/main/kotlin")
        } else {
            File("$d/agent/src/main/kotlin")
        }
        return File(root, "com/workflow/orchestrator/agent/ui/AgentController.kt").readText()
    }

    private fun providerBlock(): String {
        val src = controllerSource()
        val start = src.indexOf("setContextUsageProvider {")
        assertTrue(start >= 0, "AgentController must wire setContextUsageProvider")
        return src.substring(start, minOf(start + 500, src.length))
    }

    @Test
    fun `context usage provider uses the resolved effectiveMaxInputTokens budget`() {
        assertTrue(
            "effectiveMaxInputTokens()" in providerBlock(),
            "the context-usage bar must read the resolved budget (effectiveMaxInputTokens), " +
                "so it shows the real model window, not the 90K fallback",
        )
    }

    @Test
    fun `context usage provider does not key max off the saved sourcegraphChatModel setting`() {
        assertFalse(
            "sourcegraphChatModel" in providerBlock(),
            "the saved model setting is blank on auto-pick → 90K fallback; the bar must use the " +
                "resolved currentBrainModelId budget instead",
        )
    }
}
