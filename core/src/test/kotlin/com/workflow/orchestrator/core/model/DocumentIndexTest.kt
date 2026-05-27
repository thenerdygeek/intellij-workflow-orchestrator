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
    fun `pageAt returns the page whose offset is the greatest not exceeding the position`() {
        assertEquals(1, index.pageAt(0))
        assertEquals(2, index.pageAt(5001))
        assertEquals(3, index.pageAt(99999))
    }
}
