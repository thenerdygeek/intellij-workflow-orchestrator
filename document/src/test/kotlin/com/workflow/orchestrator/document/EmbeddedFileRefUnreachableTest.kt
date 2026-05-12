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
 * or relies on Tika's `isExtractInlineImages = false` config (PDF). No code path
 * constructs an [DocumentBlock.EmbeddedFileRef]. This test pins both facts so:
 *
 * 1. Anyone adding image support will see *which* test to update.
 * 2. Anyone tweaking the hardened PDF config can't silently re-enable image extraction
 *    without breaking a test.
 */
class EmbeddedFileRefUnreachableTest {

    // ── PDF: the only explicit image gate in the codebase ────────────────────

    @Test
    fun `hardenedPdfConfig explicitly disables inline image extraction and OCR`() {
        val cfg = hardenedPdfConfig()

        assertFalse(cfg.isExtractInlineImages,
            "Images are explicitly OFF — if this flips, PDF images will start flowing through " +
                "Tika as separate parser invocations and the extractor still has nowhere to put them.")
        assertEquals(PDFParserConfig.OCR_STRATEGY.NO_OCR, cfg.ocrStrategy,
            "OCR is explicitly OFF — flipping this without adding an OCR parser will break TikaDocumentExtractor's " +
                "init-block 'no OCR parser registered' assertion.")
    }

    // ── EmbeddedFileRef constructor: no caller across all extractor code ─────

    @Test
    fun `no extractor source file constructs DocumentBlock EmbeddedFileRef`() {
        val mainRoot = Path.of("src/main/kotlin/com/workflow/orchestrator/document")
        val kotlinFiles = Files.walk(mainRoot)
            .filter { Files.isRegularFile(it) && it.extension == "kt" }
            .toList()

        val offenders = kotlinFiles.filter { file ->
            val text = Files.readString(file)
            // Anything that mentions EmbeddedFileRef other than:
            // - The MarkdownAssembler which serialises the variant (consumer, not producer).
            text.contains("EmbeddedFileRef(") &&
                !file.toString().endsWith("MarkdownAssembler.kt")
        }

        assertEquals(emptyList<Path>(), offenders,
            "Some extractor now constructs DocumentBlock.EmbeddedFileRef — great! Update this test " +
                "to assert the *positive* behaviour (e.g. that PptxExtractor emits one per picture shape). " +
                "Offending files: $offenders")
    }

    // ── DocumentBlock model surface (sentinel) ────────────────────────────────

    @Test
    fun `DocumentBlock sealed hierarchy has exactly 9 variants after Phase 0`() {
        // Phase 0 added: Comment, ListBlock, Footnote, KeyValueGroup.
        // If this set changes again, review every Format-Gaps test for new positive coverage.
        val variants = DocumentBlock::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertEquals(
            setOf(
                "Heading", "Paragraph", "Table", "PageMarker", "EmbeddedFileRef",
                "Comment", "ListBlock", "Footnote", "KeyValueGroup",
            ),
            variants,
            "DocumentBlock variants changed. Review every Format-Gaps test for new positive coverage."
        )
    }
}
