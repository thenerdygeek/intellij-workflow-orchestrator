package com.workflow.orchestrator.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Table-caption anchor support on [DocumentIndex] (agent-requested: "navigate to a TABLE by its
 * caption/number").
 *
 * Table captions live in their OWN anchor list ([DocumentIndex.tables]) so they don't bloat the
 * heading/section list. `section=` resolution ([DocumentIndex.offsetForSection]) matches headings
 * FIRST, then falls back to table anchors so a caller can address a table by:
 *  - its number alone (`"Table 46"`),
 *  - the full caption (`"Table 46. Fare Parameters"`), or
 *  - just the title (`"Fare Parameters"`).
 */
class DocumentIndexTableAnchorTest {

    // A DICOM-shaped table caption ("Table 5.2-1. Request Header Fields") plus a microchip-shaped
    // one ("TABLE 1-2: ... PINOUT I/O DESCRIPTIONS"), alongside a couple of real headings.
    private val index = DocumentIndex(
        pages = listOf(DocumentIndex.Anchor("1", 0)),
        sections = listOf(
            DocumentIndex.Anchor("4 Digital Identity Model", 100),
            DocumentIndex.Anchor("5 Authentication", 9000),
        ),
        tables = listOf(
            DocumentIndex.Anchor("Table 46. Fare Parameters", 4200),
            DocumentIndex.Anchor("Table 5.2-1. Request Header Fields", 5500),
            DocumentIndex.Anchor("TABLE 1-2: PIC18F2455/2550 PINOUT I/O DESCRIPTIONS", 7000),
        ),
    )

    // (a) tables list carries the caption anchors at their offsets.
    @Test
    fun `tables list carries table-caption anchors at their offsets`() {
        assertEquals(4200, index.tables.first { it.key == "Table 46. Fare Parameters" }.offset)
        assertEquals(5500, index.tables.first { it.key == "Table 5.2-1. Request Header Fields" }.offset)
    }

    // (b) section= resolves a table by NUMBER alone, FULL caption, and TITLE alone.
    @Test
    fun `offsetForSection resolves a table by its number alone`() {
        assertEquals(4200, index.offsetForSection("Table 46"))
    }

    @Test
    fun `offsetForSection resolves a table by its full caption`() {
        assertEquals(4200, index.offsetForSection("Table 46. Fare Parameters"))
    }

    @Test
    fun `offsetForSection resolves a table by its title alone`() {
        assertEquals(4200, index.offsetForSection("Fare Parameters"))
    }

    @Test
    fun `offsetForSection resolves DICOM dotted-dash table numbers`() {
        assertEquals(5500, index.offsetForSection("Table 5.2-1"))
        assertEquals(5500, index.offsetForSection("Request Header Fields"))
    }

    @Test
    fun `offsetForSection resolves microchip uppercase colon table numbers`() {
        assertEquals(7000, index.offsetForSection("Table 1-2"))
        assertEquals(7000, index.offsetForSection("TABLE 1-2"))
    }

    // (c) a real heading still resolves first (regression) — headings beat tables.
    @Test
    fun `offsetForSection still resolves a real heading (regression)`() {
        assertEquals(100, index.offsetForSection("Digital Identity Model"))
        assertEquals(9000, index.offsetForSection("authentication"))
    }

    @Test
    fun `offsetForSection returns null when neither a heading nor a table matches`() {
        assertNull(index.offsetForSection("Glossary of nonsense"))
    }

    // Backward compat: tables defaults to empty, so legacy indexes (no table anchors) behave as before.
    @Test
    fun `tables defaults to empty for backward compatibility`() {
        val legacy = DocumentIndex(pages = emptyList(), sections = listOf(DocumentIndex.Anchor("Intro", 0)))
        assertEquals(emptyList<DocumentIndex.Anchor>(), legacy.tables)
        assertEquals(0, legacy.offsetForSection("Intro"))
        assertNull(legacy.offsetForSection("Table 1"))
    }
}
