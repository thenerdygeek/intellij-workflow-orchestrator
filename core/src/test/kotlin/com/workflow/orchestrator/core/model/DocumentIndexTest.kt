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

    // ── B: normalized matching (case-fold + strip leading number + alphanumeric-only) ──

    @Test
    fun `offsetForSection strips a leading section number to match the bare title`() {
        // The LLM passes the human title ("Digital Identity Model"); the indexed heading
        // carries the section number ("4 Digital Identity Model"). Neither exact nor substring
        // matches (the needle is longer-by-prefix on the index side), so the number-stripped
        // normalized comparison must bridge them.
        val idx = DocumentIndex(
            pages = emptyList(),
            sections = listOf(
                DocumentIndex.Anchor("4 Digital Identity Model", 4200),
                DocumentIndex.Anchor("5 Authentication", 8800),
            ),
        )
        assertEquals(4200, idx.offsetForSection("Digital Identity Model"))
        assertEquals(8800, idx.offsetForSection("authentication"))
    }

    @Test
    fun `offsetForSection matches across punctuation and separators via alphanumeric reduction`() {
        // The model guesses a slugified label ("fetch-product-metadata") that must resolve to
        // the human-spaced heading "Fetch Product Metadata" — reducing both sides to
        // alphanumeric-only makes them equal.
        val idx = DocumentIndex(
            pages = emptyList(),
            sections = listOf(
                DocumentIndex.Anchor("Fetch Product Metadata", 1500),
                DocumentIndex.Anchor("Overview", 0),
            ),
        )
        assertEquals(1500, idx.offsetForSection("fetch-product-metadata"))
    }

    @Test
    fun `offsetForSection strips a dotted section number with trailing dot`() {
        val idx = DocumentIndex(
            pages = emptyList(),
            sections = listOf(
                DocumentIndex.Anchor("1.1. Requirements Notation", 600),
            ),
        )
        assertEquals(600, idx.offsetForSection("Requirements Notation"))
    }

    @Test
    fun `offsetForSection precedence — exact wins over number-stripped wins over substring`() {
        // "Introduction" must hit the EXACT "Introduction" (offset 50), not the earlier
        // "1 Introduction" (number-stripped, offset 10) nor a substring container.
        val idx = DocumentIndex(
            pages = emptyList(),
            sections = listOf(
                DocumentIndex.Anchor("1 Introduction", 10),
                DocumentIndex.Anchor("Introduction", 50),
                DocumentIndex.Anchor("Introduction and Scope", 90),
            ),
        )
        assertEquals(50, idx.offsetForSection("Introduction"))
    }

    @Test
    fun `offsetForSection number-stripped match wins over a substring-only match`() {
        // Needle "Scope". Index has "2 Scope" (number-stripped equal, offset 200) appearing
        // AFTER "Project Scope and Goals" (substring, offset 100). Number-stripped/normalized
        // equality must beat substring, regardless of order.
        val idx = DocumentIndex(
            pages = emptyList(),
            sections = listOf(
                DocumentIndex.Anchor("Project Scope and Goals", 100),
                DocumentIndex.Anchor("2 Scope", 200),
            ),
        )
        assertEquals(200, idx.offsetForSection("Scope"))
    }

    @Test
    fun `offsetForSection still returns null when nothing matches`() {
        val idx = DocumentIndex(
            pages = emptyList(),
            sections = listOf(DocumentIndex.Anchor("1 Introduction", 0)),
        )
        assertNull(idx.offsetForSection("Glossary"))
    }

    // ── sectionAt: inverse of offsetForSection — given a char offset, name the enclosing section ──

    @Test
    fun `sectionAt returns the nearest preceding section anchor`() {
        // Introduction @0, Results @8000.
        assertEquals("Introduction", index.sectionAt(0))
        assertEquals("Introduction", index.sectionAt(7999))
        assertEquals("Results", index.sectionAt(8000))
        assertEquals("Results", index.sectionAt(99999))
    }

    @Test
    fun `sectionAt returns null when the offset precedes the first anchor`() {
        val idx = DocumentIndex(
            pages = emptyList(),
            sections = listOf(DocumentIndex.Anchor("Body", 500)),
        )
        assertNull(idx.sectionAt(0), "no section anchor precedes offset 0")
        assertEquals("Body", idx.sectionAt(500))
    }

    @Test
    fun `sectionAt returns null when there are no section anchors`() {
        val idx = DocumentIndex(pages = listOf(DocumentIndex.Anchor("1", 0)), sections = emptyList())
        assertNull(idx.sectionAt(123))
    }
}
