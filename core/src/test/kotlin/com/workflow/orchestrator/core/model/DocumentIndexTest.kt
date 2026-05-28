package com.workflow.orchestrator.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DocumentIndexTest {

    private val index = DocumentIndex(
        pages = listOf(
            DocumentIndex.Anchor("1", 0),
            DocumentIndex.Anchor("2", 5000),
            DocumentIndex.Anchor("3", 12000),
        ),
        sections = listOf(
            DocumentIndex.Anchor("Introduction", 0),
            DocumentIndex.Anchor("Results", 8000),
        ),
    )

    @Test
    fun `offsetForPage returns the recorded offset`() {
        assertEquals(5000, index.offsetForPage(2))
        assertNull(index.offsetForPage(99))
    }

    @Test
    fun `offsetForSection is case-insensitive`() {
        assertEquals(8000, index.offsetForSection("results"))
        assertNull(index.offsetForSection("missing"))
    }

    @Test
    fun `offsetForSection matches on substring so partial heading labels resolve`() {
        // Real PDF headings carry numbering/version suffixes (e.g. "1.3 Revision History (v2.0)").
        // The LLM passes the human label ("Revision history"); the doc contract promises a
        // case-insensitive SUBSTRING match, not an exact match.
        val richIndex = DocumentIndex(
            pages = emptyList(),
            sections = listOf(
                DocumentIndex.Anchor("1. Introduction", 0),
                DocumentIndex.Anchor("1.3 Revision History (v2.0)", 4200),
                DocumentIndex.Anchor("itemOptions schema", 9100),
            ),
        )
        assertEquals(4200, richIndex.offsetForSection("Revision history"))
        assertEquals(9100, richIndex.offsetForSection("itemOptions"))
        assertEquals(0, richIndex.offsetForSection("introduction"))
    }

    @Test
    fun `offsetForSection prefers an exact heading over an earlier partial match`() {
        // "Test" must resolve to the exact "Test" heading, not the earlier "Test Plan" that
        // merely contains it — exact match wins over an earlier substring hit.
        val richIndex = DocumentIndex(
            pages = emptyList(),
            sections = listOf(
                DocumentIndex.Anchor("Test Plan", 100),
                DocumentIndex.Anchor("Test", 900),
            ),
        )
        assertEquals(900, richIndex.offsetForSection("Test"))
    }

    @Test
    fun `pageAt returns the page whose offset is the greatest not exceeding the position`() {
        assertEquals(1, index.pageAt(0))
        assertEquals(2, index.pageAt(5001))
        assertEquals(3, index.pageAt(99999))
    }
}
