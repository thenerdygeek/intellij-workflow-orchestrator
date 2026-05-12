package com.workflow.orchestrator.document.pipeline

import org.apache.tika.parser.pdf.PDFParserConfig

/**
 * Returns a hardened [PDFParserConfig] suitable for safe, memory-bounded PDF extraction.
 *
 * Settings applied:
 * - `sortByPosition = true` — correct reading order for multi-column PDFs
 * - `extractMarkedContent = true` — preserves marked tables when present in the PDF structure
 * - `extractAnnotationText = false` — **Phase 4b**: Tika annotation extraction is OFF.
 *   [PdfMetadataExtractor] is the sole source of PDF annotation content, emitting typed
 *   [com.workflow.orchestrator.core.model.DocumentBlock.Comment] blocks with
 *   `kind = PDF_ANNOTATION`. Re-enabling this flag would re-introduce duplicate annotation
 *   text as untyped [com.workflow.orchestrator.core.model.DocumentBlock.Paragraph] blocks
 *   alongside the typed Comment blocks.
 * - `ocrStrategy = NO_OCR` — never invoke Tesseract in v1; OCR is deferred to v2
 * - `extractInlineImages = false` — embedded images are not extracted in v1
 * - `maxMainMemoryBytes = 50_000_000` — caps PDFBox scratch-buffer usage; does not bound
 *   the total PDDocument resident size (that is bounded at the TikaDocumentExtractor level
 *   via a global Semaphore in Phase 6)
 *
 * Kept in an isolated helper so that Phase 5's PDF-table pipeline ([PdfPipeline]) can reuse
 * exactly the same config without duplicating constants.
 */
fun hardenedPdfConfig(): PDFParserConfig = PDFParserConfig().apply {
    isSortByPosition = true
    isExtractMarkedContent = true
    isExtractAnnotationText = false   // P4b: PdfMetadataExtractor is the sole annotation source
    ocrStrategy = PDFParserConfig.OCR_STRATEGY.NO_OCR
    isExtractInlineImages = false
    maxMainMemoryBytes = 50_000_000L
}
