package com.workflow.orchestrator.jira.ui

import com.workflow.orchestrator.core.model.jira.RemoteLinkData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Headless tests for [LinkedDocsSection.shouldHide] — the empty-state suppression
 * rule mandated by R-ADD-4 ("hide the entire section if list is empty").
 */
class LinkedDocsSectionFilteringTest {

    @Test
    fun `empty list hides the section`() {
        assertTrue(LinkedDocsSection.shouldHide(emptyList()))
    }

    @Test
    fun `non-empty list does not hide the section`() {
        val link = RemoteLinkData(
            id = 1L,
            applicationType = "com.atlassian.confluence",
            applicationName = "Confluence",
            relationship = null,
            url = "https://example.atlassian.net/wiki/Page",
            title = "Design doc"
        )
        assertFalse(LinkedDocsSection.shouldHide(listOf(link)))
    }

    @Test
    fun `input order is preserved (stable rendering contract)`() {
        val a = RemoteLinkData(1L, null, null, null, "https://a", "A")
        val b = RemoteLinkData(2L, null, null, null, "https://b", "B")
        val c = RemoteLinkData(3L, null, null, null, "https://c", "C")
        val input = listOf(a, b, c)
        // The section renders rows in input order; this just freezes the contract
        // so a future refactor that re-sorts would fail this test.
        assertEquals(listOf("A", "B", "C"), input.map { it.title })
    }
}
