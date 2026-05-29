package com.workflow.orchestrator.document.pipeline_pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import com.workflow.orchestrator.document.service.ColumnLayoutPdfFixtureFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Increment 3 (SF-1) — per-page substitution wired into [PdfPipeline], with the byte-identical
 * single-column regression guard.
 *
 * - A synthetic TWO-column page must read left-column-then-right-column (no line interleave).
 * - A synthetic SINGLE-column page must extract BYTE-IDENTICAL whether SF-1 column handling is
 *   enabled or disabled — proving the hybrid leaves the single-column Tika path untouched.
 */
class PdfTwoColumnSubstitutionTest {

    private fun textOf(blocks: List<DocumentBlock>): String =
        blocks.joinToString("\n") { b ->
            when (b) {
                is DocumentBlock.Paragraph -> b.text
                is DocumentBlock.Heading -> "#".repeat(b.level) + " " + b.text
                is DocumentBlock.PageMarker -> "<!-- page: ${b.pageNumber} -->"
                else -> b::class.simpleName ?: ""
            }
        }

    @Test
    fun `two-column page reads left column fully then right column`(@TempDir dir: Path) {
        val pdf = ColumnLayoutPdfFixtureFactory.createTwoColumn(dir.resolve("two-col.pdf"))
        val blocks = PdfPipeline().extract(pdf)
        val text = textOf(blocks)

        val alpha = text.indexOf("ALPHA") // left col, first line
        val gamma = text.indexOf("GAMMA") // left col, LAST line
        val delta = text.indexOf("DELTA") // right col, first line
        val zeta = text.indexOf("ZETA")   // right col, last line
        assertTrue(alpha >= 0 && gamma >= 0 && delta >= 0 && zeta >= 0, "missing markers in: $text")
        // The interleave failure mode would place DELTA (right col) before GAMMA (left col's last
        // line). Correct order: the ENTIRE left column precedes the entire right column.
        assertTrue(alpha < gamma, "left col out of order:\n$text")
        assertTrue(gamma < delta, "left col not fully before right col (interleaved):\n$text")
        assertTrue(delta < zeta, "right col out of order:\n$text")
    }

    @Test
    fun `single-column page is byte-identical with SF-1 enabled vs disabled`(@TempDir dir: Path) {
        val pdf = ColumnLayoutPdfFixtureFactory.createSingleColumn(dir.resolve("single-col.pdf"))
        val withSf1 = PdfPipeline(enableColumnReorder = true).extract(pdf)
        val withoutSf1 = PdfPipeline(enableColumnReorder = false).extract(pdf)
        assertEquals(textOf(withoutSf1), textOf(withSf1),
            "SF-1 column handling must not alter single-column output")
    }
}
