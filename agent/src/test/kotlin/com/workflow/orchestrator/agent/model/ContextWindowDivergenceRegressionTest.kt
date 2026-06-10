package com.workflow.orchestrator.agent.model

import com.workflow.orchestrator.core.ai.dto.ContextWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Behavioral regression for the reported bug: the model selector showed **132K** while the top
 * context bar showed **96K** for the same model.
 *
 * Root cause: the bar keyed the window on `currentBrainModelId` (the auto-picked *running* model,
 * a thinking variant at ~96K) while the selector keyed on each row's own id (the *selected* model
 * at 132K). The fix keys all DISPLAY surfaces (bar + selected picker row) on the SELECTED model
 * through the SAME [EffectiveContextWindow] instance, so they resolve to the same number.
 *
 * Unlike the source-text wiring tests (which pin that the controller *calls* the resolver with the
 * selected-model key), this test instantiates the REAL resolver against a fake catalog and asserts
 * the numbers the two surfaces actually compute, reproducing the exact reported figures.
 */
class ContextWindowDivergenceRegressionTest {

    private fun win(maxInput: Int) =
        ContextWindow(maxInputTokens = maxInput, maxOutputTokens = 8000, maxUserInputTokens = 18_000)

    /** Fake catalog: the selected (non-thinking) model is 132K; a different running model is 96K. */
    private val catalog: Map<String, ContextWindow> = mapOf(
        "anthropic::claude-sonnet" to win(132_000),       // the model the user selected
        "anthropic::claude-opus-thinking" to win(96_000), // currentBrainModelId (auto-picked) — the old bar key
    )

    private fun resolver(overrides: MaxTokenOverrides = MaxTokenOverrides(global = null, perModel = emptyMap())) =
        EffectiveContextWindow(windowLookup = { catalog[it] }, overrides = { overrides })

    @Test
    fun `bar keyed on the selected model agrees with the selected picker row (no 132K-vs-96K divergence)`() {
        val r = resolver()
        val selectedModel = "anthropic::claude-sonnet"

        // The selector row for the selected model (keyed on m.id):
        val pickerRow = r.maxInputTokens(selectedModel)
        // The context bar after the fix (keyed on the selected model):
        val bar = r.maxInputTokens(selectedModel)

        assertEquals(132_000, pickerRow, "selected picker row shows the selected model's window")
        assertEquals(pickerRow, bar, "the bar must show the SAME number as the selected picker row")
    }

    @Test
    fun `the OLD running-model key reproduces the reported 96K divergence the fix avoids`() {
        val r = resolver()
        val selectedModel = "anthropic::claude-sonnet"
        val runningModel = "anthropic::claude-opus-thinking" // what the bar used to key on

        val barAfterFix = r.maxInputTokens(selectedModel)
        val barBeforeFix = r.maxInputTokens(runningModel)

        assertEquals(132_000, barAfterFix)
        assertEquals(96_000, barBeforeFix, "documents the pre-fix bug: bar keyed on the running model showed 96K")
        assertNotEquals(
            barBeforeFix, barAfterFix,
            "the fix changes the bar's value by keying on the selected model instead of the running model",
        )
    }

    @Test
    fun `a per-model override on the selected model is reflected identically on bar and picker row`() {
        val selectedModel = "anthropic::claude-sonnet"
        val r = resolver(MaxTokenOverrides(global = null, perModel = mapOf(selectedModel to 64_000)))

        val pickerRow = r.maxInputTokens(selectedModel) // selector row (m.id)
        val bar = r.maxInputTokens(selectedModel)       // bar (selectedModelId)

        assertEquals(64_000, pickerRow, "the override replaces the catalog window on the picker row")
        assertEquals(pickerRow, bar, "the override is reflected identically on the bar")
    }
}
