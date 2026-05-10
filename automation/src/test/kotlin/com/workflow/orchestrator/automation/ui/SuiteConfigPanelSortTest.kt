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
}
