package com.workflow.orchestrator.automation.ui

import com.workflow.orchestrator.core.model.bamboo.PlanVariableData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for the categorised variable sort used by [SuiteConfigPanel].
 *
 * Sort order: PLAN (0) → PARENT (1) → GLOBAL (2) → unknown (99), then alpha within
 * each category. No IntelliJ infrastructure required.
 */
class SuiteConfigPanelSortTest {

    /** Mirrors the private helper in SuiteConfigPanel so we can test it standalone. */
    private fun sortedVariables(vars: List<PlanVariableData>): List<PlanVariableData> {
        val categoryRank = mapOf("PLAN" to 0, "PARENT" to 1, "GLOBAL" to 2)
        return vars.sortedWith(
            compareBy({ categoryRank[it.variableType.uppercase()] ?: 99 }, { it.name.lowercase() })
        )
    }

    private fun v(name: String, type: String) = PlanVariableData(name, "", variableType = type)

    @Test
    fun `PLAN sorts before PARENT sorts before GLOBAL`() {
        val input = listOf(
            v("TIMEOUT_MS", "GLOBAL"),
            v("DEPLOY_ENV", "PARENT"),
            v("SUITE_TYPE", "PLAN")
        )
        val sorted = sortedVariables(input)
        assertEquals(listOf("SUITE_TYPE", "DEPLOY_ENV", "TIMEOUT_MS"), sorted.map { it.name })
    }

    @Test
    fun `alpha within same category`() {
        val input = listOf(
            v("ZEBRA", "PLAN"),
            v("ALPHA", "PLAN"),
            v("MANGO", "PLAN")
        )
        val sorted = sortedVariables(input)
        assertEquals(listOf("ALPHA", "MANGO", "ZEBRA"), sorted.map { it.name })
    }

    @Test
    fun `unknown variableType sorts after GLOBAL`() {
        val input = listOf(
            v("GLOBAL_VAR", "GLOBAL"),
            v("CUSTOM_VAR", "CUSTOM"),
            v("PLAN_VAR", "PLAN")
        )
        val sorted = sortedVariables(input)
        assertEquals(listOf("PLAN_VAR", "GLOBAL_VAR", "CUSTOM_VAR"), sorted.map { it.name })
    }

    @Test
    fun `mixed categories sort correctly with alpha tie-break`() {
        val input = listOf(
            v("Z_GLOBAL", "GLOBAL"),
            v("A_GLOBAL", "GLOBAL"),
            v("M_PARENT", "PARENT"),
            v("A_PARENT", "PARENT"),
            v("Z_PLAN", "PLAN"),
            v("A_PLAN", "PLAN")
        )
        val sorted = sortedVariables(input)
        assertEquals(
            listOf("A_PLAN", "Z_PLAN", "A_PARENT", "M_PARENT", "A_GLOBAL", "Z_GLOBAL"),
            sorted.map { it.name }
        )
    }

    @Test
    fun `case-insensitive variableType matching`() {
        val input = listOf(
            v("G_VAR", "global"),
            v("P_VAR", "plan"),
            v("PR_VAR", "parent")
        )
        val sorted = sortedVariables(input)
        assertEquals(listOf("P_VAR", "PR_VAR", "G_VAR"), sorted.map { it.name })
    }

    @Test
    fun `empty list returns empty`() {
        assertEquals(emptyList<PlanVariableData>(), sortedVariables(emptyList()))
    }

    // ──────────────────────────── Docker-tags filter tests ────────────────────────────

    /**
     * Verifies [SuiteConfigPanel.isDockerTagsVariable] excludes the configured
     * variable name case-insensitively, matching both the canonical mixed-case
     * form and the all-lowercase variant.
     */
    @Test
    fun `isDockerTagsVariable excludes canonical DockerTagsAsJSON case-insensitively`() {
        val configuredName = "DockerTagsAsJSON"

        // Exact match
        assertEquals(true, SuiteConfigPanel.isDockerTagsVariable("DockerTagsAsJSON", configuredName))
        // All-lowercase variant must also be excluded
        assertEquals(true, SuiteConfigPanel.isDockerTagsVariable("dockertagsasjson", configuredName))
        // Unrelated variable must NOT be excluded
        assertEquals(false, SuiteConfigPanel.isDockerTagsVariable("DEPLOY_ENV", configuredName))
    }

    // ──────────────────────────── SkipSeparatorComboBox tests ────────────────────────────

    /**
     * Verifies the JComboBox subclass refuses to land on a separator entry. The
     * dropdown popup or arrow-key navigation can target a separator index; the
     * combo must transparently forward to the next real entry so the
     * "selectedItem is always a real VariableOption" contract holds.
     */
    @Test
    fun `SkipSeparatorComboBox jumps to next real entry when target is separator`() {
        val items = arrayOf(
            SuiteConfigPanel.VariableOption("", "PLAN", "", isSeparator = true, categoryCaption = "Plan variables"),
            SuiteConfigPanel.VariableOption("DEPLOY_ENV", "PLAN", "staging"),
            SuiteConfigPanel.VariableOption("", "PARENT", "", isSeparator = true, categoryCaption = "Project variables"),
            SuiteConfigPanel.VariableOption("TIMEOUT_MS", "PARENT", "30000")
        )
        val combo = SuiteConfigPanel.SkipSeparatorComboBox(items)

        // setSelectedIndex(0) targets the leading separator → must land on index 1 (DEPLOY_ENV)
        combo.selectedIndex = 0
        assertEquals(1, combo.selectedIndex)
        assertEquals("DEPLOY_ENV", (combo.selectedItem as SuiteConfigPanel.VariableOption).name)

        // setSelectedIndex(2) targets the middle separator → must land on index 3 (TIMEOUT_MS)
        combo.selectedIndex = 2
        assertEquals(3, combo.selectedIndex)
        assertEquals("TIMEOUT_MS", (combo.selectedItem as SuiteConfigPanel.VariableOption).name)
    }

    @Test
    fun `SkipSeparatorComboBox falls back to previous real entry when target is trailing separator`() {
        val items = arrayOf(
            SuiteConfigPanel.VariableOption("DEPLOY_ENV", "PLAN", "staging"),
            SuiteConfigPanel.VariableOption("", "PARENT", "", isSeparator = true, categoryCaption = "Project variables")
        )
        val combo = SuiteConfigPanel.SkipSeparatorComboBox(items)

        // setSelectedIndex(1) targets a trailing separator with no later real entry →
        // must fall back to index 0 (DEPLOY_ENV)
        combo.selectedIndex = 1
        assertEquals(0, combo.selectedIndex)
        assertEquals("DEPLOY_ENV", (combo.selectedItem as SuiteConfigPanel.VariableOption).name)
    }

    @Test
    fun `SkipSeparatorComboBox passes through real-entry selection unchanged`() {
        val items = arrayOf(
            SuiteConfigPanel.VariableOption("", "PLAN", "", isSeparator = true, categoryCaption = "Plan variables"),
            SuiteConfigPanel.VariableOption("ALPHA", "PLAN", "1"),
            SuiteConfigPanel.VariableOption("BETA", "PLAN", "2")
        )
        val combo = SuiteConfigPanel.SkipSeparatorComboBox(items)

        combo.selectedIndex = 2 // BETA
        assertEquals(2, combo.selectedIndex)
        assertEquals("BETA", (combo.selectedItem as SuiteConfigPanel.VariableOption).name)
    }
}
