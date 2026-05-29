package com.workflow.orchestrator.document

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.pipeline.hardenedPdfConfig
import org.apache.tika.parser.pdf.PDFParserConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.streams.toList

/**
 * Cross-cutting contract tests for image handling and the unreachable
 * [DocumentBlock.EmbeddedFileRef] variant.
 *
 * Today, every extractor either silently drops embedded images (POI extractors all do)
 * or relies on Tika's `isExtractInlineImages = false` config (PDF). Phase 2 wired image
 * extraction into DOCX/XLSX/PPTX/HTML, and Phase 4 Task 2 (P4T2) added PDF embedded
 * attachments and image XObjects via [PdfMetadataExtractor]. This test pins the known
 * producer set so:
 *
 * 1. Anyone adding image support will see *which* test to update.
 * 2. Anyone tweaking the hardened PDF config can't silently re-enable image extraction
 *    without breaking a test.
 */
class EmbeddedFileRefUnreachableTest {

    // ── PDF: the only explicit image gate in the codebase ────────────────────

    @Test
    fun `hardenedPdfConfig explicitly disables inline image extraction and OCR and Tika annotation text`() {
        val cfg = hardenedPdfConfig()

        assertFalse(cfg.isExtractInlineImages,
            "Images are explicitly OFF — if this flips, PDF images will start flowing through " +
                "Tika as separate parser invocations and the extractor still has nowhere to put them.")
        assertEquals(PDFParserConfig.OCR_STRATEGY.NO_OCR, cfg.ocrStrategy,
            "OCR is explicitly OFF — flipping this without adding an OCR parser will break TikaDocumentExtractor's " +
                "init-block 'no OCR parser registered' assertion.")
        // P4b: Tika annotation extraction is OFF — PdfMetadataExtractor is the SOLE annotation source.
        assertFalse(cfg.isExtractAnnotationText,
            "Annotation extraction is OFF — PdfMetadataExtractor is the SOLE annotation source post-Phase-4b. " +
                "Re-enabling this would re-introduce duplicate annotation Paragraphs alongside the typed Comment blocks.")
    }

    // ── EmbeddedFileRef constructor: exactly the known Phase-2 producers ─────

    @Test
    fun `only known Phase-2 producers construct DocumentBlock EmbeddedFileRef`() {
        val mainRoot = Path.of("src/main/kotlin/com/workflow/orchestrator/document")
        val kotlinFiles = Files.walk(mainRoot)
            .filter { Files.isRegularFile(it) && it.extension == "kt" }
            .toList()

        val producers = kotlinFiles.filter { file ->
            val text = Files.readString(file)
            text.contains("EmbeddedFileRef(") &&
                !file.toString().endsWith("MarkdownAssembler.kt")
        }.map { it.fileName.toString() }.sorted()

        // Phase 2 wires image extraction into DOCX (P2T2), XLSX (P2T3), PPTX (P2T4),
        // and HTML <img> via the SAX handler (P2T5).
        // Phase 4 Task 2 (P4T2) adds PDF embedded attachments + image XObjects.
        val expected = listOf("DocumentBlockHandler.kt", "ImageExtractionVisitor.kt", "PdfMetadataExtractor.kt", "PptxExtractor.kt", "XlsxTableExtractor.kt")
        assertEquals(expected, producers,
            "EmbeddedFileRef producer set changed — update this list to reflect the new positive behaviour. " +
                "Current producers: $producers")
    }

    // ── DocumentBlock model surface (sentinel) ────────────────────────────────

    @Test
    fun `DocumentBlock sealed hierarchy has exactly 10 variants`() {
        // Phase 0 added: Comment, ListBlock, Footnote, KeyValueGroup.
        // G-7 / IMG-3 added: EmbeddedObjectRef (SmartArt / shape / OLE placeholders).
        // If this set changes again, review every Format-Gaps test for new positive coverage.
        val variants = DocumentBlock::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertEquals(
            setOf(
                "Heading", "Paragraph", "Table", "PageMarker", "EmbeddedFileRef",
                "EmbeddedObjectRef",
                "Comment", "ListBlock", "Footnote", "KeyValueGroup",
            ),
            variants,
            "DocumentBlock variants changed. Review every Format-Gaps test for new positive coverage."
        )
    }
}
