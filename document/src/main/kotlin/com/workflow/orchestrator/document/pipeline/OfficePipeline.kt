package com.workflow.orchestrator.document.pipeline

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.poi.DocxTableExtractor
import com.workflow.orchestrator.document.poi.PptxExtractor
import com.workflow.orchestrator.document.poi.XlsxTableExtractor
import com.workflow.orchestrator.document.service.ImageExtractionService
import java.io.InputStream

/**
 * Dispatches OOXML Office document streams to their format-specific POI extractor.
 *
 * The three OOXML MIME types are routed as follows:
 *
 * | MIME type | Extractor |
 * |---|---|
 * | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | [XlsxTableExtractor] |
 * | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | [DocxTableExtractor] |
 * | `application/vnd.openxmlformats-officedocument.presentationml.presentation` | [PptxExtractor] |
 *
 * Any other MIME type throws [IllegalArgumentException]. The Phase 6 dispatcher
 * (`TikaDocumentExtractor`) is responsible for routing only OOXML MIMEs here; callers that
 * want to check before calling can use [OFFICE_MIMES].
 *
 * ## Thread safety
 *
 * Each [extract] call creates a new POI document instance internally (per the contract of
 * the individual extractors). [OfficePipeline] itself holds no mutable state and is safe to
 * share across threads.
 */
class OfficePipeline {

    /**
     * Extracts [DocumentBlock] values from [stream] based on the given [mime] type.
     *
     * For DOCX and XLSX files, an [ImageExtractionService] and [docKey] may be supplied so
     * that embedded images are extracted. A new extractor is constructed per call so each
     * extraction gets its own [imageService] + [docKey] binding.
     *
     * - DOCX: [com.workflow.orchestrator.document.poi.visitor.ImageExtractionVisitor] is
     *   wired into the paragraph visitor chain.
     * - XLSX: [XlsxTableExtractor] walks each sheet's `XSSFDrawing` for pictures.
     *
     * @param stream       Raw document bytes. The caller is responsible for closing the stream.
     * @param mime         Exact MIME type string from [OFFICE_MIMES].
     * @param imageService Optional service that saves extracted images to disk. When non-null,
     *                     inline images in DOCX and XLSX files are emitted as
     *                     [com.workflow.orchestrator.core.model.DocumentBlock.EmbeddedFileRef]
     *                     with an on-disk path. When null (legacy / non-agent callers), images
     *                     are silently dropped.
     * @param docKey       Stable key for the source document (typically its absolute path).
     *                     Passed through to [ImageExtractionService.save] for per-doc directory
     *                     keying; ignored when [imageService] is null.
     * @return Ordered list of document blocks.
     * @throws IllegalArgumentException if [mime] is not one of [OFFICE_MIMES].
     */
    fun extract(
        stream: InputStream,
        mime: String,
        imageService: ImageExtractionService? = null,
        docKey: String = "anonymous",
    ): List<DocumentBlock> {
        return when (mime) {
            MIME_XLSX -> {
                val extractor = if (imageService != null) {
                    XlsxTableExtractor(imageService = imageService, docKey = docKey)
                } else {
                    XlsxTableExtractor()
                }
                extractor.extract(stream)
            }
            MIME_DOCX -> {
                val extractor = if (imageService != null) {
                    DocxTableExtractor(imageService = imageService, docKey = docKey)
                } else {
                    DocxTableExtractor()
                }
                extractor.extract(stream)
            }
            MIME_PPTX -> {
                val extractor = if (imageService != null) {
                    PptxExtractor(imageService = imageService, docKey = docKey)
                } else {
                    PptxExtractor()
                }
                extractor.extract(stream)
            }
            else -> throw IllegalArgumentException(
                "OfficePipeline does not handle MIME type '$mime'. " +
                    "Supported types: ${OFFICE_MIMES.joinToString()}"
            )
        }
    }

    companion object {
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

        /**
         * The complete set of MIME types that [OfficePipeline] handles.
         *
         * Phase 6's `TikaDocumentExtractor` uses this set to route Office documents here
         * rather than to the Tika XHTML pipeline.
         */
        val OFFICE_MIMES: Set<String> = setOf(MIME_XLSX, MIME_DOCX, MIME_PPTX)
    }
}
