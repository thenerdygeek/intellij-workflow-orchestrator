package com.workflow.orchestrator.document.assembler

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarkdownAssemblerIndexedTest {

    private val assembler = MarkdownAssembler()

    @Test
    fun `assembleIndexed records page-marker and heading offsets at the char position before serialization`() {
        val blocks = listOf(
            DocumentBlock.PageMarker(1),
            DocumentBlock.Heading(1, "Introduction"),
            DocumentBlock.Paragraph("Body text."),
            DocumentBlock.PageMarker(2),
            DocumentBlock.Heading(2, "Results"),
        )

        val out = assembler.assembleIndexed(blocks)

        // The full markdown equals assemble() with an unbounded cap (no truncation marker).
        assertEquals(assembler.assemble(blocks, Int.MAX_VALUE).markdown, out.markdown)
        assertEquals(out.markdown.length, out.contentLength)

        // Page 1 anchor is at offset 0 (first block).
        assertEquals(0, out.index.offsetForPage(1))
        // Page 2 anchor offset equals the running length right before the page-2 marker.
        val expectedPage2Offset = listOf(blocks[0], blocks[1], blocks[2])
            .joinToString("") { assembler.serializeBlockForTest(it) }.length
        assertEquals(expectedPage2Offset, out.index.offsetForPage(2))
        // Headings recorded as sections.
        assertEquals(0 + assembler.serializeBlockForTest(blocks[0]).length, out.index.offsetForSection("Introduction"))
    }
}
