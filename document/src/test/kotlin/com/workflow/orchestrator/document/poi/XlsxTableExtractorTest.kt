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
        assertEquals("1", criticalRow!![1],
            "COUNTIF formula for CRITICAL should evaluate to 1 (one BUG with CRITICAL severity)")
    }

    // ── 4. Notes sheet: merged cells repeat value ─────────────────────────────

    @Test
    fun `Notes sheet A2 and A3 both contain merged value Q1`() {
        val blocks = loadFixture("bug-tracker.xlsx").use { extractor.extract(it) }
        val notesTable = notesTable(blocks)

        // The Notes sheet has merged cells A2:A3 with value "Q1".
        // Both data rows should have "Q1" in the first column.
        assertTrue(notesTable.rows.size >= 2, "Notes table should have at least 2 rows")
        assertEquals("Q1", notesTable.rows[0][0], "A2 (first data row, first col) should be Q1")
        assertEquals("Q1", notesTable.rows[1][0], "A3 (second data row, first col) should be Q1")
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
