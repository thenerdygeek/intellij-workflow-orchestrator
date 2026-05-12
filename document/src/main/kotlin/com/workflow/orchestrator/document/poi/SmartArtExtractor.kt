package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.openxml4j.opc.OPCPackage

/**
 * Extracts SmartArt text from OOXML documents (DOCX and PPTX) into flat
 * [DocumentBlock.ListBlock] values.
 *
 * ## What SmartArt is
 *
 * SmartArt is a feature that turns a flat list of text items into a rendered diagram
 * (flowcharts, pyramids, cycle rings, org charts, etc.). Structurally, each SmartArt
 * graphic is stored as two OPC parts: a layout definition and a data model
 * (`diagramData`). The data model holds all the text the user typed. The rendered
 * shapes are SVG/DML that we cannot interpret without rendering.
 *
 * ## Extraction scope (Phase 5a)
 *
 * Phase 5a extracts text ONLY — no visual hierarchy, no relationship arrows.
 * Each data-model part becomes one flat [DocumentBlock.ListBlock] containing
 * every `<a:t>` leaf found inside `<dgm:pt>` elements. The shape names and
 * `cxnLst` connection lists are silently dropped.
 *
 * Visual hierarchy (indent levels, arrow relationships, hierarchy type) is deferred
 * to a follow-up phase.
 *
 * ## OPC structure
 *
 * DOCX: `word/diagrams/data*.xml` parts, referenced from the document part via
 * `type = "…/relationships/diagramData"`.
 *
 * PPTX: `ppt/diagrams/data*.xml` parts, referenced from each slide part via
 * the same relationship type.
 *
 * Both use content type
 * `application/vnd.openxmlformats-officedocument.drawingml.diagramData+xml`.
 *
 * Rather than walking per-part relationships, we query the whole package by
 * content type via [OPCPackage.getPartsByContentType] — this is safe because
 * the content type is globally unique to SmartArt data-model parts.
 *
 * ## XML structure of a data-model part
 *
 * ```xml
 * <dgm:dataModel xmlns:dgm="…/diagram/2008/layout"
 *                xmlns:a="…/drawingml/2006/main">
 *   <dgm:ptLst>
 *     <dgm:pt type="node">
 *       <dgm:t>
 *         <a:bodyPr/>
 *         <a:p><a:r><a:t>Text here</a:t></a:r></a:p>
 *       </dgm:t>
 *     </dgm:pt>
 *   </dgm:ptLst>
 * </dgm:dataModel>
 * ```
 *
 * We scan for all `<a:t>` elements globally in the part (namespace
 * `http://schemas.openxmlformats.org/drawingml/2006/main`), collecting their text
 * content into one list.
 *
 * ## Thread safety
 *
 * Stateless singleton. The underlying [OPCPackage] is NOT thread-safe; callers
 * must ensure their package is not shared across threads.
 */
object SmartArtExtractor {

    // Content type used by BOTH DOCX and PPTX SmartArt data-model parts.
    private const val DIAGRAM_DATA_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.drawingml.diagramData+xml"

    // DrawingML namespace where <a:t> elements live.
    private const val DRAWINGML_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"

    /**
     * Extracts every SmartArt data-model part from [opcPackage] and returns one
     * [DocumentBlock.ListBlock] per part whose text content is non-empty.
     *
     * @param opcPackage The OPC package for the document. Obtained via
     *                   `XWPFDocument.getPackage()` or `XMLSlideShow.getPackage()`.
     * @return Zero or more [DocumentBlock.ListBlock] values (one per SmartArt part).
     */
    fun extract(opcPackage: OPCPackage): List<DocumentBlock.ListBlock> {
        val parts = try {
            opcPackage.getPartsByContentType(DIAGRAM_DATA_CONTENT_TYPE)
        } catch (_: Exception) {
            return emptyList()
        }
        if (parts.isNullOrEmpty()) return emptyList()

        return parts.mapNotNull { part ->
            val items = try {
                extractTextFromPart(part.inputStream.use { it.readBytes() })
            } catch (_: Exception) {
                emptyList()
            }
            if (items.isEmpty()) null
            else DocumentBlock.ListBlock(ordered = false, items = items)
        }
    }

    /**
     * Parses the raw XML bytes of a single diagramData part and collects the text
     * of all `<a:t>` elements found anywhere inside it.
     *
     * We use a simple SAX-level stax scan (javax.xml.stream.XMLStreamReader) rather
     * than a full DOM parse to avoid loading a large object graph for what amounts to
     * text scraping.
     *
     * @param xmlBytes Raw bytes of the `…diagramData+xml` part.
     * @return List of non-blank text strings in document order.
     */
    private fun extractTextFromPart(xmlBytes: ByteArray): List<String> {
        val items = mutableListOf<String>()
        try {
            val factory = javax.xml.stream.XMLInputFactory.newInstance().also { f ->
                // Harden against XXE: disable DTD and external entities.
                f.setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                f.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false)
            }
            val reader = factory.createXMLStreamReader(java.io.ByteArrayInputStream(xmlBytes))
            try {
                var inAT = false
                while (reader.hasNext()) {
                    when (reader.next()) {
                        javax.xml.stream.XMLStreamConstants.START_ELEMENT -> {
                            inAT = reader.localName == "t" && reader.namespaceURI == DRAWINGML_NS
                        }
                        javax.xml.stream.XMLStreamConstants.CHARACTERS,
                        javax.xml.stream.XMLStreamConstants.CDATA -> {
                            if (inAT) {
                                val text = reader.text?.trim()
                                if (!text.isNullOrEmpty()) items += text
                            }
                        }
                        javax.xml.stream.XMLStreamConstants.END_ELEMENT -> {
                            if (inAT) inAT = false
                        }
                        else -> Unit
                    }
                }
            } finally {
                reader.close()
            }
        } catch (_: Exception) {
            // Malformed XML in the SmartArt part — return whatever we collected.
        }
        return items
    }
}
