package com.workflow.orchestrator.document.assembler

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Table-caption anchor indexing in [MarkdownAssembler.assembleIndexed].
 *
 * Table captions ("Table 45. Fare Parameters", "TABLE 1-2: PINOUT I/O DESCRIPTIONS",
 * "Table 8.3.2-1. Query Parameter Usage") appear as Paragraph/Heading text in the assembled
 * content — the PDF Table block's own `caption` is null. The assembler detects them from the
 * block TEXT and records them in [com.workflow.orchestrator.core.model.DocumentIndex.tables],
 * keyed by the caption text, at the char offset where the caption begins.
 */
class MarkdownAssemblerTableAnchorTest {

    private val assembler = MarkdownAssembler()

    @Test
    fun `assembleIndexed records a table caption paragraph as a table anchor at its offset`() {
        val blocks = listOf(
            DocumentBlock.Heading(1, "Fares"),
            DocumentBlock.Paragraph("Table 45. Fare Parameters"),
            DocumentBlock.Table(
                headers = listOf("Name", "Type"),
                rows = listOf(listOf("origin", "string")),
                caption = null,
            ),
        )

        val out = assembler.assembleIndexed(blocks)

        // The caption begins right after the heading is serialized.
        val expectedOffset = assembler.serializeBlockForTest(blocks[0]).length
        val anchor = out.index.tables.firstOrNull { it.key == "Table 45. Fare Parameters" }
        assertNotNull(anchor, "table caption paragraph must be recorded as a table anchor; tables=${out.index.tables}")
        assertEquals(expectedOffset, anchor!!.offset)
        // Tables must NOT pollute the sections list.
        assertTrue(out.index.sections.none { it.key.contains("Fare Parameters") }, "table caption must not be in sections")
    }

    @Test
    fun `assembleIndexed detects all four caption shapes`() {
        val blocks = listOf(
            DocumentBlock.Paragraph("TABLE 1-2: PINOUT I/O DESCRIPTIONS"),          // uppercase, colon, dash-number
            DocumentBlock.Paragraph("Table 8.3.2-1. Query Parameter Usage"),         // dotted+dash, dot sep
            DocumentBlock.Paragraph("Table 8-1a. Sample Letter Suffix"),             // letter suffix
            DocumentBlock.Paragraph("Table B.2-1. Lettered Annex Numbering"),        // lettered annex
        )

        val out = assembler.assembleIndexed(blocks)
        val keys = out.index.tables.map { it.key }

        assertTrue(keys.contains("TABLE 1-2: PINOUT I/O DESCRIPTIONS"), "uppercase/colon shape; got $keys")
        assertTrue(keys.contains("Table 8.3.2-1. Query Parameter Usage"), "dotted-dash shape; got $keys")
        assertTrue(keys.contains("Table 8-1a. Sample Letter Suffix"), "letter-suffix shape; got $keys")
        assertTrue(keys.contains("Table B.2-1. Lettered Annex Numbering"), "lettered-annex shape; got $keys")
    }

    @Test
    fun `assembleIndexed does NOT treat a prose reference to a table as a caption`() {
        // A sentence that merely *references* a table (no separator directly after the number,
        // followed by lowercase prose) must not be indexed as a caption.
        val blocks = listOf(
            DocumentBlock.Paragraph("Table 8.7.3-2 specifies the default and optional Transfer Syntax UID combinations."),
            DocumentBlock.Paragraph("See Table 5 for details."),
        )
        val out = assembler.assembleIndexed(blocks)
        assertTrue(out.index.tables.isEmpty(), "prose references must not be indexed as captions; got ${out.index.tables}")
    }

    @Test
    fun `assembleIndexed resolves an indexed table caption through offsetForSection`() {
        val blocks = listOf(
            DocumentBlock.Paragraph("Intro prose."),
            DocumentBlock.Paragraph("Table 5.2-1. Request Header Fields"),
        )
        val out = assembler.assembleIndexed(blocks)
        val expectedOffset = assembler.serializeBlockForTest(blocks[0]).length
        // All three addressing forms resolve to the caption offset.
        assertEquals(expectedOffset, out.index.offsetForSection("Table 5.2-1"))
        assertEquals(expectedOffset, out.index.offsetForSection("Table 5.2-1. Request Header Fields"))
        assertEquals(expectedOffset, out.index.offsetForSection("Request Header Fields"))
    }

    @Test
    fun `assembleIndexed leaves tables empty for a document with no captions`() {
        val blocks = listOf(
            DocumentBlock.Heading(1, "Introduction"),
            DocumentBlock.Paragraph("Just prose, no tables here."),
        )
        val out = assembler.assembleIndexed(blocks)
        assertTrue(out.index.tables.isEmpty())
        assertNull(out.index.offsetForSection("Table 1"))
    }
}
