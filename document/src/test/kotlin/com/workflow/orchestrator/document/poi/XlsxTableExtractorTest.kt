package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [XlsxTableExtractor] against the `bug-tracker.xlsx` fixture.
 *
 * Fixture contents (from fixtures/README.md):
 * - Sheet "Bugs": 5 rows: BUG-001..BUG-005, severities, dates, owners
 * - Sheet "Counts": COUNTIF formulas referencing the Bugs sheet
 * - Sheet "Notes": merged cells A2:A3 (value "Q1")
 */
class XlsxTableExtractorTest {

    private val extractor = XlsxTableExtractor()

    private fun loadFixture(name: String) =
        javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("fixture not found: fixtures/$name")

    // ── 1. Sheet headings appear in order ─────────────────────────────────────

    @Test
    fun `three Heading blocks with sheet names Bugs Counts Notes appear in order`() {
        val blocks = loadFixture("bug-tracker.xlsx").use { extractor.extract(it) }

        val headings = blocks.filterIsInstance<DocumentBlock.Heading>()
            .filter { it.level == 2 }
            .map { it.text }

        assertEquals(listOf("Bugs", "Counts", "Notes"), headings,
            "Expected sheet headings in workbook order")
    }

    // ── 2. Bugs sheet: headers and first row ──────────────────────────────────

    @Test
    fun `Bugs sheet has correct headers`() {
        val blocks = loadFixture("bug-tracker.xlsx").use { extractor.extract(it) }
        val bugsTable = bugsTable(blocks)

        assertEquals(listOf("BugId", "Severity", "Status", "OpenedOn", "Owner"), bugsTable.headers)
    }

    @Test
    fun `Bugs sheet has 5 data rows`() {
        val blocks = loadFixture("bug-tracker.xlsx").use { extractor.extract(it) }
        val bugsTable = bugsTable(blocks)

        assertEquals(5, bugsTable.rows.size, "Expected 5 data rows in Bugs sheet")
    }

    @Test
    fun `Bugs sheet first row matches expected values including ISO date`() {
        val blocks = loadFixture("bug-tracker.xlsx").use { extractor.extract(it) }
        val bugsTable = bugsTable(blocks)

        val row0 = bugsTable.rows[0]
        assertEquals("BUG-001", row0[0], "BugId")
        assertEquals("HIGH", row0[1], "Severity")
        assertEquals("OPEN", row0[2], "Status")
        assertEquals("2026-01-05", row0[3], "OpenedOn (ISO date)")
        assertEquals("alice", row0[4], "Owner")
    }

    // ── 3. Counts sheet: formula evaluated ───────────────────────────────────

    @Test
    fun `Counts sheet CRITICAL row evaluates to 1`() {
        val blocks = loadFixture("bug-tracker.xlsx").use { extractor.extract(it) }
        val countsTable = countsTable(blocks)

        // Find the row where first cell is "CRITICAL"
        val criticalRow = countsTable.rows.firstOrNull { it[0] == "CRITICAL" }
        assertNotNull(criticalRow, "Expected a row with Severity=CRITICAL in Counts sheet")
        // P-1: the formula TEXT is surfaced alongside the evaluated value. The empty <v></v>
        // recompute marker falls through the P-2 gate to live evaluation (one CRITICAL bug ⇒ 1).
        assertEquals("=COUNTIF(Bugs!B:B,\"CRITICAL\") (1)", criticalRow!![1],
            "COUNTIF formula text + evaluated value (1) should both be shown")
    }

    // ── 4. Notes sheet: merged value lives ONLY in the anchor cell (P-3) ──────

    @Test
    fun `Notes sheet merged value Q1 appears only in the anchor cell, spanned cell is blank`() {
        val blocks = loadFixture("bug-tracker.xlsx").use { extractor.extract(it) }
        val notesTable = notesTable(blocks)

        // The Notes sheet is ENTIRELY textual (Section/Note header over Q1/Q2 labels). With no
        // type break / styling / defined-table evidence, P-5 declines to promote row 1 to a
        // header and uses positional column headers instead — so the original "Section"/"Note"
        // row survives as the first DATA row and the A1-cell values shift down by one.
        assertEquals(listOf("Col A", "Col B"), notesTable.headers,
            "All-text Notes sheet has no header evidence ⇒ positional headers (P-5)")
        assertEquals(listOf("Section", "Note"), notesTable.rows[0],
            "The former 'header' row is now the first data row")

        // The merged region A2:A3 (value "Q1") sits at table rows[1] (A2 anchor) / rows[2] (A3
        // spanned). Per OOXML the value belongs to the anchor only; the spanned cell is blank
        // and must NOT be fabricated (audit finding P-3).
        assertTrue(notesTable.rows.size >= 3, "Notes table should have at least 3 rows")
        assertEquals("Q1", notesTable.rows[1][0], "A2 (anchor) should be Q1")
        assertEquals("", notesTable.rows[2][0],
            "A3 (spanned) must be blank — the anchor value must not be duplicated")
    }

    @Test
    fun `Notes sheet emits a Merged ranges note so the merge structure is preserved`() {
        val blocks = loadFixture("bug-tracker.xlsx").use { extractor.extract(it) }
        // After dropping the duplicated anchor value, the merge structure is preserved as a note.
        val noteTexts = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        assertTrue(
            noteTexts.any { it.startsWith("Merged ranges:") && it.contains("A2:A3") },
            "Expected a 'Merged ranges: … A2:A3 …' note; got paragraphs: $noteTexts"
        )
    }

    // ── 5. Per-call instantiation: two sequential calls work ─────────────────

    @Test
    fun `calling extract twice on different streams both succeed`() {
        val blocks1 = loadFixture("bug-tracker.xlsx").use { extractor.extract(it) }
        val blocks2 = loadFixture("bug-tracker.xlsx").use { extractor.extract(it) }

        val headings1 = blocks1.filterIsInstance<DocumentBlock.Heading>().map { it.text }
        val headings2 = blocks2.filterIsInstance<DocumentBlock.Heading>().map { it.text }

        assertEquals(headings1, headings2, "Two sequential extractions should produce identical headings")
        assertTrue(blocks1.isNotEmpty())
        assertTrue(blocks2.isNotEmpty())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun bugsTable(blocks: List<DocumentBlock>): DocumentBlock.Table {
        val bugsHeadingIdx = blocks.indexOfFirst {
            it is DocumentBlock.Heading && it.text == "Bugs"
        }
        assertTrue(bugsHeadingIdx >= 0, "Expected a Heading block for sheet 'Bugs'")
        return blocks.drop(bugsHeadingIdx + 1)
            .filterIsInstance<DocumentBlock.Table>()
            .firstOrNull()
            ?: error("No Table block found after 'Bugs' heading")
    }

    private fun countsTable(blocks: List<DocumentBlock>): DocumentBlock.Table {
        val countsHeadingIdx = blocks.indexOfFirst {
            it is DocumentBlock.Heading && it.text == "Counts"
        }
        assertTrue(countsHeadingIdx >= 0, "Expected a Heading block for sheet 'Counts'")
        return blocks.drop(countsHeadingIdx + 1)
            .filterIsInstance<DocumentBlock.Table>()
            .firstOrNull()
            ?: error("No Table block found after 'Counts' heading")
    }

    private fun notesTable(blocks: List<DocumentBlock>): DocumentBlock.Table {
        val notesHeadingIdx = blocks.indexOfFirst {
            it is DocumentBlock.Heading && it.text == "Notes"
        }
        assertTrue(notesHeadingIdx >= 0, "Expected a Heading block for sheet 'Notes'")
        return blocks.drop(notesHeadingIdx + 1)
            .filterIsInstance<DocumentBlock.Table>()
            .firstOrNull()
            ?: error("No Table block found after 'Notes' heading")
    }
}
