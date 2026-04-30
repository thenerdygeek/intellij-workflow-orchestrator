package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [PptxExtractor] against the `slides.pptx` fixture.
 *
 * Fixture contents (from fixtures/README.md):
 * - Slide 1: title + subtitle + speaker notes ("Demo notes...")
 * - Slide 2: title + 4×3 table (headers: RiskId, Likelihood, Impact; 3 data rows) + notes
 * - Slide 3: title + body text
 */
class PptxExtractorTest {

    private val extractor = PptxExtractor()

    private fun loadFixture(name: String) =
        javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("fixture not found: fixtures/$name")

    // ── 10. Block sequence contains Slide 1, 2, 3 headings ───────────────────

    @Test
    fun `headings starting with Slide 1, Slide 2, Slide 3 appear in order`() {
        val blocks = loadFixture("slides.pptx").use { extractor.extract(it) }

        val headings = blocks.filterIsInstance<DocumentBlock.Heading>()
            .map { it.text }

        val slideHeadings = headings.filter { it.startsWith("Slide ") }
        assertTrue(slideHeadings.size >= 3,
            "Expected at least 3 slide headings, got: $slideHeadings")

        assertTrue(slideHeadings[0].startsWith("Slide 1"),
            "First slide heading should start with 'Slide 1', was: ${slideHeadings[0]}")
        assertTrue(slideHeadings[1].startsWith("Slide 2"),
            "Second slide heading should start with 'Slide 2', was: ${slideHeadings[1]}")
        assertTrue(slideHeadings[2].startsWith("Slide 3"),
            "Third slide heading should start with 'Slide 3', was: ${slideHeadings[2]}")
    }

    // ── 11. Slide 2 table has correct headers and 3 data rows ────────────────

    @Test
    fun `Slide 2 produces a Table with RiskId Likelihood Impact headers and 3 data rows`() {
        val blocks = loadFixture("slides.pptx").use { extractor.extract(it) }

        // Find blocks belonging to Slide 2: from its heading to before Slide 3's heading
        val slide2StartIdx = blocks.indexOfFirst {
            it is DocumentBlock.Heading && it.text.startsWith("Slide 2")
        }
        assertTrue(slide2StartIdx >= 0, "Could not find 'Slide 2' heading in blocks")

        val slide3StartIdx = blocks.drop(slide2StartIdx + 1).indexOfFirst {
            it is DocumentBlock.Heading && it.text.startsWith("Slide 3")
        }.let { if (it >= 0) slide2StartIdx + 1 + it else blocks.size }

        val slide2Blocks = blocks.subList(slide2StartIdx, slide3StartIdx)

        val table = slide2Blocks.filterIsInstance<DocumentBlock.Table>().firstOrNull()
        assertNotNull(table, "Expected a Table block in Slide 2, but none found")

        assertEquals(listOf("RiskId", "Likelihood", "Impact"), table!!.headers,
            "Slide 2 table headers should be [RiskId, Likelihood, Impact]")
        assertEquals(3, table.rows.size,
            "Slide 2 table should have 3 data rows")
    }

    // ── 12. Slide 1 has notes containing "Demo notes" ────────────────────────

    @Test
    fun `Slide 1 has a notes paragraph starting with greater-than Notes and containing Demo notes`() {
        val blocks = loadFixture("slides.pptx").use { extractor.extract(it) }

        // Find blocks belonging to Slide 1: from its heading to before Slide 2's heading
        val slide1StartIdx = blocks.indexOfFirst {
            it is DocumentBlock.Heading && it.text.startsWith("Slide 1")
        }
        assertTrue(slide1StartIdx >= 0, "Could not find 'Slide 1' heading in blocks")

        val slide2StartIdx = blocks.drop(slide1StartIdx + 1).indexOfFirst {
            it is DocumentBlock.Heading && it.text.startsWith("Slide 2")
        }.let { if (it >= 0) slide1StartIdx + 1 + it else blocks.size }

        val slide1Blocks = blocks.subList(slide1StartIdx, slide2StartIdx)

        val notesBlock = slide1Blocks
            .filterIsInstance<DocumentBlock.Paragraph>()
            .firstOrNull { it.text.startsWith("> Notes:") }

        assertNotNull(notesBlock,
            "Expected a notes paragraph starting with '> Notes:' in Slide 1 blocks; " +
                "paragraphs found: ${slide1Blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }}")

        assertTrue(notesBlock!!.text.contains("Demo notes", ignoreCase = true),
            "Notes paragraph should contain 'Demo notes', was: '${notesBlock.text}'")
    }
}
