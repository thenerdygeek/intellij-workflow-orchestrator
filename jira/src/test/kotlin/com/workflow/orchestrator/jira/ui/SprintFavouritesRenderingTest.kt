package com.workflow.orchestrator.jira.ui

import com.workflow.orchestrator.core.model.jira.FilterData
import com.workflow.orchestrator.core.services.ToolResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R-ADD-7 — visibility-decision logic for the Saved Filters section.
 *
 * Spec:
 *  - empty list → hide entire section (no "No favourite filters" empty state)
 *  - load error → hide entire section (favourites are an enhancement, not load-bearing)
 *  - non-empty  → show section
 *
 * Pulled out into [SavedFiltersSection.shouldShowSection] so we can exercise
 * the rule without spinning a Swing tree.  Per the subagent spec, we skip the
 * Swing-level test and assert against this small helper.
 */
class SprintFavouritesRenderingTest {

    @Test
    fun `empty list result hides the section`() {
        val result = ToolResult.success(emptyList<FilterData>(), "0 favourite filter(s)")
        assertFalse(SavedFiltersSection.shouldShowSection(result),
            "Empty favourites list must hide the section.")
    }

    @Test
    fun `error result hides the section`() {
        val result = ToolResult(
            data = emptyList<FilterData>(),
            summary = "Error fetching favourite filters: 500",
            isError = true
        )
        assertFalse(SavedFiltersSection.shouldShowSection(result),
            "Error favourites load must hide the section.")
    }

    @Test
    fun `non-empty list shows the section`() {
        val filters = listOf(
            FilterData(id = 10001L, name = "My Open Bugs", jql = "assignee = currentUser() AND status != Done"),
            FilterData(id = 10002L, name = "Sprint 42 Reviewing", description = "Tickets I am reviewing", jql = null)
        )
        val result = ToolResult.success(filters, "2 favourite filter(s)")
        assertTrue(SavedFiltersSection.shouldShowSection(result),
            "Non-empty favourites must show the section.")
    }

    @Test
    fun `error result with non-empty data still hides the section`() {
        // Defensive: a faux "successful" data list paired with isError=true should still hide.
        val result = ToolResult(
            data = listOf(FilterData(id = 1L, name = "Stale")),
            summary = "Partial failure",
            isError = true
        )
        assertFalse(SavedFiltersSection.shouldShowSection(result),
            "isError trumps data presence — section must stay hidden.")
    }
}
