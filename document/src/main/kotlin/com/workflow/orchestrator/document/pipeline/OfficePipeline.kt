package com.workflow.orchestrator.document.pipeline

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.poi.DocxTableExtractor
import com.workflow.orchestrator.document.poi.PptxExtractor
import com.workflow.orchestrator.document.poi.XlsxTableExtractor
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
 *
 * @param xlsx Extractor for XLSX files (default: [XlsxTableExtractor]).
 * @param docx Extractor for DOCX files (default: [DocxTableExtractor]).
 * @param pptx Extractor for PPTX files (default: [PptxExtractor]).
 */
class OfficePipeline(
    private val xlsx: XlsxTableExtractor = XlsxTableExtractor(),
    private val docx: DocxTableExtractor = DocxTableExtractor(),
    private val pptx: PptxExtractor = PptxExtractor(),
) {

    /**
     * Extracts [DocumentBlock] values from [stream] based on the given [mime] type.
     *
     * @param stream Raw document bytes. The caller is responsible for closing the stream.
     * @param mime   Exact MIME type string from [OFFICE_MIMES].
     * @return Ordered list of document blocks.
     * @throws IllegalArgumentException if [mime] is not one of [OFFICE_MIMES].
     */
    fun extract(stream: InputStream, mime: String): List<DocumentBlock> {
        return when (mime) {
            MIME_XLSX -> xlsx.extract(stream)
            MIME_DOCX -> docx.extract(stream)
            MIME_PPTX -> pptx.extract(stream)
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
