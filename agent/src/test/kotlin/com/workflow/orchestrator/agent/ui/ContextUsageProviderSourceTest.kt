package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the fix for the context-usage bar showing the wrong MAX.
 *
 * Original fix: the bar stopped keying max off `sourcegraphChatModel` (blank on auto-pick → 90K
 * fallback) and used `effectiveMaxInputTokens()` instead.
 *
 * Task-5 fix: the bar and the model-picker rows now both route through
 * `service.getEffectiveContextWindow().maxInputTokens(selectedModelId())` so the user-visible
 * capacity number is always keyed on the SELECTED model, eliminating the picker-vs-bar divergence
 * (e.g. picker shows 132K, bar shows 96K for the same model).
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
        return src.substring(start, minOf(start + 600, src.length))
    }

    @Test
    fun `context usage provider uses the resolved effectiveMaxInputTokens budget`() {
        // Task-5 (+code-review cleanup): the bar provider calls the shared displayMaxInputTokens()
        // helper, which keys the override-aware resolver on the SELECTED model. Assert both: the
        // provider block delegates to the helper, AND the helper resolves via the selection.
        assertTrue(
            "displayMaxInputTokens()" in providerBlock(),
            "the context-usage bar must delegate to displayMaxInputTokens()",
        )
        assertTrue(
            "getEffectiveContextWindow().maxInputTokens(selectedModelId())" in controllerSource(),
            "displayMaxInputTokens() must key the resolver on the selected model " +
                "(getEffectiveContextWindow().maxInputTokens(selectedModelId())), " +
                "so the bar shows the same capacity as the selected picker row",
        )
    }

    @Test
    fun `context usage provider does not key max off the saved sourcegraphChatModel setting`() {
        assertFalse(
            "sourcegraphChatModel" in providerBlock(),
            "the saved model setting is blank on auto-pick → 90K fallback; the bar must use the " +
                "resolver (getEffectiveContextWindow) instead",
        )
    }
}
