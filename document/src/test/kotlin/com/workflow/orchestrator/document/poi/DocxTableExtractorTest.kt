package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [DocxTableExtractor] against the `design-doc.docx` fixture.
 *
 * Fixture contents (from fixtures/README.md):
 * - Two Heading 1s, two Heading 2s
 * - Two tables interleaved between paragraphs (RiskMatrix 3-row, AcceptanceCriteria 3-row)
 * - Explicit prose-to-table reference text in paragraphs
 */
class DocxTableExtractorTest {

    private val extractor = DocxTableExtractor()

    private fun loadFixture(name: String) =
        javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("fixture not found: fixtures/$name")

    // ── 6. First block is Heading(1, ...) ─────────────────────────────────────

    @Test
    fun `first block is Heading level 1 with document title`() {
        val blocks = loadFixture("design-doc.docx").use { extractor.extract(it) }

        assertTrue(blocks.isNotEmpty(), "Expected non-empty blocks from design-doc.docx")
        val first = blocks.first()
        assertTrue(first is DocumentBlock.Heading, "First block must be a Heading, was ${first::class.simpleName}")
        val heading = first as DocumentBlock.Heading
        assertEquals(1, heading.level, "First heading must be level 1")
        assertEquals("Workflow Orchestrator Design Spec", heading.text)
    }

    // ── 7. Document order: paragraph between two tables ───────────────────────

    @Test
    fun `at least one Paragraph appears between the two tables`() {
        val blocks = loadFixture("design-doc.docx").use { extractor.extract(it) }

        val tables = blocks.filterIsInstance<DocumentBlock.Table>()
        assertTrue(tables.size >= 2, "Expected at least 2 tables in design-doc.docx, found ${tables.size}")

        val firstTableIdx = blocks.indexOfFirst { it is DocumentBlock.Table }
        val secondTableIdx = blocks.indexOfLast { it is DocumentBlock.Table }

        // There should be at least one Paragraph between the first and second table
        val blocksBetween = blocks.subList(firstTableIdx + 1, secondTableIdx)
        val paragraphBetween = blocksBetween.any { it is DocumentBlock.Paragraph }

        assertTrue(paragraphBetween,
            "Expected at least one Paragraph block between the two tables; " +
                "blocks between tables: ${blocksBetween.map { it::class.simpleName }}")
    }

    // ── 8. RiskMatrix table headers and row count ─────────────────────────────

    @Test
    fun `RiskMatrix table has correct headers and 3 data rows`() {
        val blocks = loadFixture("design-doc.docx").use { extractor.extract(it) }

        val riskTable = findTableWithHeaders(blocks, listOf("RiskId", "Likelihood", "Impact"))
            ?: error("Could not find RiskMatrix table with headers [RiskId, Likelihood, Impact]")

        assertEquals(listOf("RiskId", "Likelihood", "Impact"), riskTable.headers)
        assertEquals(3, riskTable.rows.size, "Expected 3 data rows in RiskMatrix table")
    }

    // ── 9. AcceptanceCriteria table headers and row count ─────────────────────

    @Test
    fun `AcceptanceCriteria table has correct headers and 3 data rows`() {
        val blocks = loadFixture("design-doc.docx").use { extractor.extract(it) }

        val acTable = findTableWithHeaders(blocks, listOf("Criterion", "Owner"))
            ?: error("Could not find AcceptanceCriteria table with headers [Criterion, Owner]")

        assertEquals(listOf("Criterion", "Owner"), acTable.headers)
        assertEquals(3, acTable.rows.size, "Expected 3 data rows in AcceptanceCriteria table")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findTableWithHeaders(
        blocks: List<DocumentBlock>,
        headers: List<String>,
    ): DocumentBlock.Table? {
        return blocks.filterIsInstance<DocumentBlock.Table>()
            .firstOrNull { it.headers == headers }
    }
}
