package com.workflow.orchestrator.document.pipeline_poi

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.pipeline.OfficePipeline
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [OfficePipeline] dispatch and MIME routing.
 */
class OfficePipelineTest {

    private val pipeline = OfficePipeline()

    private fun loadFixture(name: String) =
        javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("fixture not found: fixtures/$name")

    // ── 13. XLSX dispatch ─────────────────────────────────────────────────────

    @Test
    fun `passing XLSX file with correct MIME routes to xlsx extractor and returns blocks`() {
        val blocks = loadFixture("bug-tracker.xlsx").use { stream ->
            pipeline.extract(stream, OfficePipeline.MIME_XLSX)
        }

        assertFalse(blocks.isEmpty(), "Expected non-empty blocks from bug-tracker.xlsx via OfficePipeline")

        // Should have at least one Heading and one Table from the XLSX extractor
        assertTrue(blocks.any { it is DocumentBlock.Heading },
            "Expected at least one Heading block")
        assertTrue(blocks.any { it is DocumentBlock.Table },
            "Expected at least one Table block")

        // Sheet names from bug-tracker.xlsx should appear as headings
        val headingTexts = blocks.filterIsInstance<DocumentBlock.Heading>().map { it.text }
        assertTrue(headingTexts.contains("Bugs"),
            "Expected 'Bugs' sheet heading, found: $headingTexts")
    }

    // ── 14. Wrong MIME → IllegalArgumentException ─────────────────────────────

    @Test
    fun `wrong MIME type throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            loadFixture("bug-tracker.xlsx").use { stream ->
                pipeline.extract(stream, "application/pdf")
            }
        }
    }

    @Test
    fun `blank MIME type throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            loadFixture("bug-tracker.xlsx").use { stream ->
                pipeline.extract(stream, "")
            }
        }
    }

    // ── OFFICE_MIMES set contains all three expected MIME types ───────────────

    @Test
    fun `OFFICE_MIMES companion val contains all three OOXML MIME types`() {
        assertTrue(OfficePipeline.MIME_XLSX in OfficePipeline.OFFICE_MIMES)
        assertTrue(OfficePipeline.MIME_DOCX in OfficePipeline.OFFICE_MIMES)
        assertTrue(OfficePipeline.MIME_PPTX in OfficePipeline.OFFICE_MIMES)
        assertTrue(OfficePipeline.OFFICE_MIMES.size == 3)
    }

    // ── DOCX and PPTX dispatch smoke tests ────────────────────────────────────

    @Test
    fun `passing DOCX file with correct MIME routes to docx extractor`() {
        val blocks = loadFixture("design-doc.docx").use { stream ->
            pipeline.extract(stream, OfficePipeline.MIME_DOCX)
        }

        assertFalse(blocks.isEmpty(), "Expected non-empty blocks from design-doc.docx via OfficePipeline")
        assertTrue(blocks.any { it is DocumentBlock.Heading })
    }

    @Test
    fun `passing PPTX file with correct MIME routes to pptx extractor`() {
        val blocks = loadFixture("slides.pptx").use { stream ->
            pipeline.extract(stream, OfficePipeline.MIME_PPTX)
        }

        assertFalse(blocks.isEmpty(), "Expected non-empty blocks from slides.pptx via OfficePipeline")
        assertTrue(blocks.any { it is DocumentBlock.Heading && it.text.startsWith("Slide") })
    }
}
