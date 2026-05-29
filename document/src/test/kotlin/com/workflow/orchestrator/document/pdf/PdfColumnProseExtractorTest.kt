package com.workflow.orchestrator.document.pdf

import com.workflow.orchestrator.document.service.ColumnLayoutPdfFixtureFactory
import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Increment 2 (SF-1) — per-page column EXTRACTION via [PDFTextStripperByArea].
 *
 * Given a detected gutter x, the extractor must read the LEFT column fully, then the RIGHT column,
 * with no line-by-line interleave. The fixture lays out "ALPHA…GAMMA" left and "DELTA…ZETA" right.
 */
class PdfColumnProseExtractorTest {

    private val detector = PdfColumnDetector()
    private val extractor = PdfColumnProseExtractor()

    @Test
    fun `reads left column fully then right column`(@TempDir dir: Path) {
        val pdf = ColumnLayoutPdfFixtureFactory.createTwoColumn(dir.resolve("two-col.pdf"))
        Loader.loadPDF(pdf.toFile()).use { doc ->
            val page = doc.getPage(0)
            val gutter = detector.detectGutter(page)!!
            val text = extractor.extractColumns(page, gutter)

            // All left tokens must precede all right tokens.
            val alpha = text.indexOf("ALPHA")
            val gamma = text.indexOf("GAMMA")
            val delta = text.indexOf("DELTA")
            val zeta = text.indexOf("ZETA")
            assertTrue(alpha in 0 until gamma, "left col not in order: a=$alpha g=$gamma")
            assertTrue(gamma in 0 until delta, "left col not fully before right col: g=$gamma d=$delta")
            assertTrue(delta in 0 until zeta, "right col not in order: d=$delta z=$zeta")
        }
    }

    @Test
    fun `does not interleave columns line-by-line`(@TempDir dir: Path) {
        val pdf = ColumnLayoutPdfFixtureFactory.createTwoColumn(dir.resolve("two-col.pdf"))
        Loader.loadPDF(pdf.toFile()).use { doc ->
            val page = doc.getPage(0)
            val gutter = detector.detectGutter(page)!!
            val text = extractor.extractColumns(page, gutter)
            // BETA (left, line 2) must come before DELTA (right, line 1) — the interleave failure
            // would put DELTA between ALPHA and BETA.
            assertTrue(text.indexOf("BETA") < text.indexOf("DELTA"), "columns interleaved: $text")
        }
    }
}
