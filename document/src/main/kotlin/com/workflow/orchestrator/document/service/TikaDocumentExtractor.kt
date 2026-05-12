package com.workflow.orchestrator.document.service

import com.workflow.orchestrator.core.api.DocumentExtractor
import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.core.model.DocumentContent
import com.workflow.orchestrator.core.model.ExtractOptions
import com.workflow.orchestrator.core.services.SessionDownloadDir
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.document.assembler.MarkdownAssembler
import com.workflow.orchestrator.document.pipeline.OfficePipeline
import com.workflow.orchestrator.document.pipeline.PdfPipeline
import com.workflow.orchestrator.document.pipeline.TikaXhtmlPipeline
import com.workflow.orchestrator.document.poi.PoiHardening
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.tika.config.TikaConfig
import org.apache.tika.parser.AutoDetectParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * Primary implementation of [DocumentExtractor]. Dispatches extractions by MIME type to the
 * appropriate pipeline, applies concurrency and timeout controls, and maps all exceptions to
 * user-readable [ToolResult] failure messages.
 *
 * ## Thread-safety / concurrency
 *
 * - A global [Semaphore](2) caps simultaneous extractions to avoid OOM on IDEA's default
 *   750 MB–2 GB heap. The semaphore wraps the timeout, so a slot is held for the duration
 *   of the extraction — not released between coroutine suspensions.
 * - All blocking I/O runs on [Dispatchers.IO].
 * - The ContextClassLoader is swapped per-call to the Tika classloader to avoid the
 *   ServiceLoader discovery failure described in TIKA-1145.
 *
 * ## Init-block assertions
 *
 * Construction fails fast (via [require]) when the Tika parser registry is empty or when an
 * OCR parser is registered unexpectedly. Silent empty extracts are worse than a hard fail at
 * construction time.
 *
 * @param pdfPipeline     Tabula + Tika prose merger. Default: [PdfPipeline].
 * @param officePipeline  POI direct dispatch for OOXML formats. Default: [OfficePipeline].
 * @param tikaXhtml       Tika XHTML pipeline for everything else. Default: [TikaXhtmlPipeline].
 * @param assembler       Markdown assembler. Default: [MarkdownAssembler].
 * @param mimeDetector    MIME type detector. Default: [MimeDetector].
 * @param maxCharsProvider Lambda consulted per-call to determine the character budget. Phase 8
 *                         will wire this to `PluginSettings.documentMaxChars`; v1 default: 200 000.
 */
class TikaDocumentExtractor(
    private val pdfPipeline: PdfPipeline = PdfPipeline(),
    private val officePipeline: OfficePipeline = OfficePipeline(),
    private val tikaXhtml: TikaXhtmlPipeline = TikaXhtmlPipeline(),
    private val assembler: MarkdownAssembler = MarkdownAssembler(),
    private val mimeDetector: MimeDetector = MimeDetector(),
    private val maxCharsProvider: () -> Int = { 200_000 },
) : DocumentExtractor {

    private val semaphore = Semaphore(2)

    init {
        PoiHardening.applyOnce()

        // TIKA-1145 defense: assert at construction that parsers are discovered.
        // PluginClassLoader's META-INF/services scanning differs from the system
        // classloader; if Tika finds nothing, every extraction silently returns "".
        val testConfig = TikaConfig(
            this::class.java.classLoader.getResourceAsStream("tika-config.xml")
                ?: error("tika-config.xml not found on classpath")
        )
        val parsers = AutoDetectParser(testConfig).parsers
        require(parsers.isNotEmpty()) {
            "Tika parser ServiceLoader returned empty under PluginClassLoader. " +
                "Check META-INF/services/org.apache.tika.parser.Parser is on classpath."
        }

        // Defense in depth: assert no OCR parser is registered.
        // Walk the parsers map's value set since CompositeParser exposes parsers as Map<MediaType, Parser>.
        val ocrParsers = parsers.values.filter { p ->
            p::class.java.simpleName.contains("OCR", ignoreCase = true)
        }
        require(ocrParsers.isEmpty()) {
            "OCR parser unexpectedly registered: ${ocrParsers.map { it::class.java.name }}"
        }

        println(
            "[TikaDocumentExtractor] init: parsers.size=${parsers.size} (TIKA-1145 check: non-empty OK)"
        )
    }

    override suspend fun extract(
        path: Path,
        options: ExtractOptions,
    ): ToolResult<DocumentContent> =
        semaphore.withPermit {
            withTimeoutOrNull(options.timeoutMs) {
                withContext(Dispatchers.IO) {
                    val previousCcl = Thread.currentThread().contextClassLoader
                    try {
                        Thread.currentThread().contextClassLoader =
                            TikaConfig::class.java.classLoader

                        runCatching { doExtract(path, options) }.fold(
                            onSuccess = { content ->
                                ToolResult(
                                    data = content,
                                    summary = summarize(content),
                                    isError = false,
                                )
                            },
                            onFailure = { e -> mapErrorToFailure(e) },
                        )
                    } finally {
                        Thread.currentThread().contextClassLoader = previousCcl
                    }
                }
            } ?: timeoutFailure(options.timeoutMs)
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun doExtract(path: Path, options: ExtractOptions): DocumentContent {
        val mime = mimeDetector.detect(path)
        val downloadsRoot = SessionDownloadDir.current()
        val imageService = ImageExtractionService(downloadsRoot = downloadsRoot)
        val docKey = path.toAbsolutePath().toString()
        val blocks: List<DocumentBlock> = when {
            mime == "application/pdf" -> pdfPipeline.extract(path)
            mime in OfficePipeline.OFFICE_MIMES -> {
                Files.newInputStream(path).use { officePipeline.extract(it, mime, imageService, docKey) }
            }
            else -> Files.newInputStream(path).use { tikaXhtml.extract(it, mime) }
        }
        val maxChars = options.maxChars ?: maxCharsProvider()
        val (markdown, truncated) = assembler.assemble(blocks, maxChars)
        return DocumentContent(
            markdown = markdown,
            mime = mime,
            truncated = truncated,
            pageCount = blocks.count { it is DocumentBlock.PageMarker }.takeIf { it > 0 },
        )
    }

    private fun summarize(content: DocumentContent): String =
        "Extracted ${content.markdown.length} chars from ${content.mime}" +
            (if (content.truncated) " (truncated)" else "") +
            (content.pageCount?.let { " across $it pages" } ?: "")

    private fun mapErrorToFailure(e: Throwable): ToolResult<DocumentContent> {
        val msg = when (e) {
            is org.apache.poi.EncryptedDocumentException ->
                "Document is password-protected; v1 does not support encrypted documents."
            is org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException ->
                "PDF is password-protected; v1 does not support encrypted PDFs."
            is org.apache.poi.openxml4j.exceptions.OLE2NotOfficeXmlFileException ->
                "File is in legacy Office 97-2003 format; some content may be unsupported in v1."
            is org.apache.poi.ooxml.POIXMLException ->
                "Office document could not be parsed: ${e.message ?: "malformed OOXML"}"
            is org.apache.tika.exception.TikaException ->
                "Tika could not parse this document: ${e.message ?: e::class.simpleName ?: "unknown"}"
            is java.nio.charset.MalformedInputException ->
                "Document encoding could not be determined."
            is OutOfMemoryError -> {
                System.gc()
                "Document too large to process within heap budget; reduce max_chars or use a smaller subset."
            }
            is java.io.IOException ->
                "File appears corrupt, truncated, or unreadable: ${e.message ?: "I/O error"}"
            else ->
                "Document extraction failed: ${e::class.simpleName ?: "Throwable"}: ${e.message ?: ""}"
        }
        return ToolResult(
            data = DocumentContent(markdown = "", mime = "", truncated = false),
            summary = msg,
            isError = true,
        )
    }

    private fun timeoutFailure(ms: Long): ToolResult<DocumentContent> = ToolResult(
        data = DocumentContent(markdown = "", mime = "", truncated = false),
        summary = "Document extraction timed out after ${ms}ms",
        isError = true,
    )
}
