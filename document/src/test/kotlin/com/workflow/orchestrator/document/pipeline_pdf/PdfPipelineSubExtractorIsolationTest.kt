package com.workflow.orchestrator.document.pipeline_pdf

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.pdf.PdfProseExtractor
import com.workflow.orchestrator.document.pdf.PdfTableExtractor
import com.workflow.orchestrator.document.pdf.PositionedBlock
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Robustness sweep — proves the [PdfPipeline] sub-extractor stage isolation: a throw in ONE
 * independent stream (tables OR prose) must not lose the SIBLING stream. This is the
 * generalisation of the skewed-ruling per-page fix (#2): the same class of "one unit aborts
 * everything" bug at the sub-extractor merge level.
 *
 * Mock-free: it uses minimal throwing subclasses of the two injected extractors (both are
 * constructor dependencies of [PdfPipeline]; subclassing is the design-correct test seam — no
 * MockK, per :document test constraints).
 */
class PdfPipelineSubExtractorIsolationTest {

    private fun fixture(name: String): Path =
        Paths.get(javaClass.classLoader.getResource("fixtures/$name")!!.toURI())

    /** A table extractor that always throws, simulating a Tabula blow-up on a pathological PDF. */
    private class ThrowingTableExtractor : PdfTableExtractor(enableStreamMode = true) {
        override fun extractRaw(
            file: Path,
            onPage: ((done: Int, total: Int) -> Unit)?,
        ): List<PositionedBlock<DocumentBlock.Table>> =
            throw IllegalArgumentException("lines must be orthogonal, vertical and horizontal")
    }

    /** A prose extractor that always throws, simulating a Tika parse blow-up. */
    private class ThrowingProseExtractor : PdfProseExtractor() {
        override fun extract(file: Path): List<PositionedBlock<DocumentBlock>> =
            throw RuntimeException("synthetic Tika prose failure")
    }

    @Test
    fun `table extractor throwing still yields prose blocks (partial result survives)`() {
        val pipeline = PdfPipeline(tableExtractor = ThrowingTableExtractor())
        val blocks = pipeline.extract(fixture("spec-with-tables.pdf"))

        // No tables (their stream failed) — but prose paragraphs/headings still came through.
        assertFalse(
            blocks.any { it is DocumentBlock.Table },
            "tables failed, so no Table blocks should appear",
        )
        assertTrue(
            blocks.any { it is DocumentBlock.Paragraph || it is DocumentBlock.Heading },
            "prose must survive a table-extractor failure (partial-but-useful result)",
        )
    }

    @Test
    fun `prose extractor throwing still yields table blocks (sibling stream survives)`() {
        val pipeline = PdfPipeline(proseExtractor = ThrowingProseExtractor())
        val blocks = pipeline.extract(fixture("spec-with-tables.pdf"))

        assertTrue(
            blocks.any { it is DocumentBlock.Table },
            "tables must survive a prose-extractor failure (sibling stream isolated)",
        )
    }
}
