package com.workflow.orchestrator.document.pipeline

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.sax.DocumentBlockHandler
import org.apache.tika.config.TikaConfig
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.html.HtmlMapper
import org.apache.tika.parser.pdf.PDFParserConfig
import java.io.InputStream

/**
 * Tika XHTML extraction pipeline — the "everything that isn't PDF or Office" branch.
 *
 * Handles RTF, ODT, EPUB, HTML, CSV, plain text, and any other format Tika's bundled
 * parsers understand. Also used as the *prose backbone* for PDF (Phase 5 adds Tabula
 * on top for table extraction).
 *
 * The pipeline:
 * 1. Builds a [TikaConfig] from the hardened `tika-config.xml` on the classpath.
 * 2. Wraps it in an [AutoDetectParser].
 * 3. Applies a hardened [PDFParserConfig] via [ParseContext] (no-op for non-PDF formats,
 *    but harmless and ensures consistent config if the MIME hint is wrong).
 * 4. Drives the [DocumentBlockHandler] as the SAX content handler.
 * 5. Returns `handler.blocks` — the structured [List<DocumentBlock>] for the assembler.
 *
 * **Thread-safety:** a new [TikaConfig] and [AutoDetectParser] are constructed per call.
 * This is intentional for Phase 3 to keep the implementation simple. Phase 6's
 * [TikaDocumentExtractor] will own a singleton config and parser instance, with per-call
 * [ParseContext] and [DocumentBlockHandler] (the only stateful per-call objects).
 *
 * **TIKA-1145 note:** [TikaConfig] is loaded via `this::class.java.classLoader` so that
 * the plugin's own classloader is used. Phase 6 adds a `contextClassLoader` wrap on top.
 */
class TikaXhtmlPipeline {

    /**
     * Extracts document blocks from [stream] using Tika's XHTML SAX output.
     *
     * @param stream The raw bytes of the document. The caller is responsible for closing the stream.
     * @param mime   Optional MIME type hint (e.g. `"text/html"`, `"text/csv"`). When provided,
     *               it is set on the [Metadata] object so Tika can skip MIME detection.
     *               When null, Tika auto-detects from the stream content.
     * @return Ordered list of [DocumentBlock] values: headings, paragraphs, tables, page
     *         markers. Never throws for well-formed input; malformed input is handled
     *         defensively by [DocumentBlockHandler].
     */
    fun extract(stream: InputStream, mime: String? = null): List<DocumentBlock> {
        val configStream = this::class.java.classLoader.getResourceAsStream("tika-config.xml")
            ?: error("tika-config.xml not found on classpath — check :core/src/main/resources/")

        val config = configStream.use { TikaConfig(it) }
        val parser = AutoDetectParser(config)

        val metadata = Metadata().apply {
            if (mime != null) {
                // Pass the bare MIME (no charset). The encoding detector chain in
                // tika-config.xml owns charset resolution:
                //   1. StandardHtmlEncodingDetector — WHATWG pre-scan: BOM → in-document
                //      `<meta charset>` / Content-Type → null for HTML with no declaration,
                //      and null for non-HTML.
                //   2. HtmlDefaultUtf8EncodingDetector — for HTML only, defaults to UTF-8 when
                //      (1) found nothing declared (SF-6: never let the statistical detector
                //      mis-guess undeclared HTML as windows-1252).
                //   3. UniversalEncodingDetector — statistical fallback for non-HTML formats.
                // We must NOT stuff `charset=…` into the Content-Type here: doing so would let
                // the passed value override a legitimately declared in-document `<meta charset>`
                // (verified — StandardHtmlEncodingDetector trusts a Content-Type charset over a
                // conflicting meta in some buffer layouts), corrupting genuinely non-UTF-8 pages.
                set(Metadata.CONTENT_TYPE, mime)
            }
        }

        val context = ParseContext().apply {
            set(PDFParserConfig::class.java, hardenedPdfConfig())
            // NAV-3: for HTML, discard navigation/header/footer/aside chrome subtrees so their
            // boilerplate text never leaks into the body and gets promoted to section anchors.
            // Tika's DefaultHtmlMapper otherwise drops these tags but keeps their orphaned text.
            // Inert for non-HTML formats (only the HtmlParser consults HtmlMapper) but gated on
            // the MIME hint anyway to keep the behaviour explicit. See ChromeStrippingHtmlMapper.
            if (mime in HTML_LIKE_MIMES) {
                set(HtmlMapper::class.java, ChromeStrippingHtmlMapper())
            }
        }

        // CSV detection in the SAX handler is opt-in based on the MIME hint, never
        // by content sniffing — see DocumentBlockHandler.csvDetectionEnabled docs.
        // When mime is null, Tika auto-detects; we use the detection result from
        // the metadata after parse to retroactively classify (handled in Phase 6).
        val csvDetection = mime in CSV_LIKE_MIMES
        // URL-boundary restoration is a PDF-only workaround for Tika eating spaces around
        // link annotations. Enabling it for non-PDF (HTML/CSV/JSON/text) would silently
        // inject leading spaces into URL values inside JSON/markdown — see
        // DocumentBlockHandler.restoreUrlBoundaries docs.
        val isPdf = mime == PDF_MIME
        val handler = DocumentBlockHandler(
            csvDetectionEnabled = csvDetection,
            restoreUrlBoundaries = isPdf,
        )
        parser.parse(stream, handler, metadata, context)
        return handler.blocks
    }

    private companion object {
        /**
         * MIME types for which the CSV/TSV detection heuristic in
         * [DocumentBlockHandler] should be enabled.
         */
        val CSV_LIKE_MIMES = setOf("text/csv", "text/tab-separated-values")

        /**
         * MIME types for which the NAV-3 chrome-stripping [HtmlMapper] is installed. XHTML is
         * included because Tika routes it through the same HtmlParser.
         */
        val HTML_LIKE_MIMES = setOf("text/html", "application/xhtml+xml")

        /** MIME for which the PDF-only URL-boundary restoration workaround is enabled. */
        const val PDF_MIME = "application/pdf"
    }
}
