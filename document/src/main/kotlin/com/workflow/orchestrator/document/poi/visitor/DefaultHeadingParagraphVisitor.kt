package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun
import org.apache.poi.xwpf.usermodel.XWPFParagraph

/**
 * Ports the current `DocxTableExtractor.paragraphToBlock` logic verbatim.
 *
 * For each [XWPFParagraph]:
 * - Empty/whitespace-only text → no block.
 * - Style ID matches "heading 1".."heading 6" (with the LibreOffice `_20_` decoding) →
 *   [DocumentBlock.Heading] of the matching level.
 * - Otherwise → [DocumentBlock.Paragraph].
 *
 * Hyperlink URLs are preserved inline as `"<visible text> (<url>)"` by walking
 * `paragraph.runs` and resolving each [XWPFHyperlinkRun] via
 * `XWPFHyperlinkRun.getHyperlink(doc).getURL()`. Non-hyperlink runs are appended as-is.
 *
 * **P5a-5 OMML math:** After the run walk, any `<m:oMath>` or `<m:oMathPara>` elements
 * at the paragraph level are detected via the XmlBeans-generated accessors on
 * [org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP] and their
 * `<m:r><m:t>` text is concatenated and appended as plain text to the paragraph.
 * This is a plain-text fallback only — LaTeX conversion via `omml2mml.xsl` is Phase 5b.
 *
 * This visitor's behaviour must remain byte-for-byte identical to the pre-refactor
 * extractor for non-hyperlink / non-math content — `DocxVisitorChainTest` (Task 18)
 * regression-tests the round-trip against the existing `design-doc.docx` fixture.
 */
class DefaultHeadingParagraphVisitor : ParagraphVisitor {

    override fun visit(paragraph: XWPFParagraph, doc: XWPFDocument): List<DocumentBlock> {
        // List-item paragraphs are handled exclusively by ListAccumulatorVisitor so that
        // they do not double-emit as plain Paragraph blocks.
        if (paragraph.numID != null) return emptyList()

        val text = buildHyperlinkAwareText(paragraph, doc).trim()
        if (text.isEmpty()) return emptyList()

        val level = headingLevel(paragraph)
        val block = if (level != null) {
            DocumentBlock.Heading(level, text)
        } else {
            DocumentBlock.Paragraph(text)
        }
        return listOf(block)
    }

    /**
     * Walks [paragraph.runs] and appends `" (<url>)"` after each [XWPFHyperlinkRun]'s text.
     * After the run walk, also appends any OMML math text found in the paragraph (P5a-5).
     *
     * [XWPFHyperlinkRun.getHyperlink] resolves the relationship ID to the target URL via
     * the document's hyperlink table (no manual package-part navigation required). Returns
     * plain concatenated text for paragraphs that contain no hyperlinks — identical to the
     * previous `paragraph.text` output.
     *
     * OMML path: [extractOMathText] walks `<m:oMath>` and `<m:oMathPara>` elements on the
     * paragraph's [CTP][org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP] via
     * XmlBeans-generated accessors and concatenates their `<m:r><m:t>` leaf text. The
     * result is appended with a space separator. If the paragraph has ONLY math content
     * and no runs, the math text still surfaces correctly.
     *
     * LaTeX conversion via `omml2mml.xsl` is Phase 5b (separate PR).
     */
    private fun buildHyperlinkAwareText(paragraph: XWPFParagraph, doc: XWPFDocument): String {
        val sb = StringBuilder()
        for (run in paragraph.runs) {
            // SF-7 / HX-2: POI's XWPFRun.text() renders <w:footnoteReference w:id="N"/> as the
            // literal "[footnoteRef:N]" and <w:endnoteReference> as "[endnoteRef:N]", leaking raw
            // OOXML tokens into prose AND colliding when a footnote and endnote share id N.
            // Rewrite the run's text to namespaced GFM footnote references ([^fnN] / [^enN])
            // that link to the namespaced definitions emitted by FootnoteExtractionVisitor.
            val runText = rewriteNoteReferences(run.text() ?: "")
            if (run is XWPFHyperlinkRun) {
                val url = try {
                    run.getHyperlink(doc)?.url
                } catch (_: Exception) {
                    null
                }
                if (!url.isNullOrBlank()) {
                    sb.append(runText).append(" (").append(url).append(")")
                } else {
                    sb.append(runText)
                }
            } else {
                sb.append(runText)
            }
        }

        // P5a-5: append OMML math text (plain-text fallback; LaTeX via XSLT is Phase 5b).
        val mathText = extractOMathText(paragraph)
        if (mathText.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(mathText)
        }

        return sb.toString()
    }

    /**
     * Extracts plain text from all `<m:oMath>` and `<m:oMathPara>` elements in [paragraph].
     *
     * Uses the XmlBeans-generated accessors on
     * [org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP]:
     * - `getOMathArray()` — direct inline `<m:oMath>` children of the paragraph.
     * - `getOMathParaArray()` — `<m:oMathPara>` wrappers, each containing one or more
     *   `<m:oMath>` children accessed via `getOMathArray()`.
     *
     * Within each `CTOMath`, text is carried by `<m:r><m:t>` leaf elements
     * ([org.openxmlformats.schemas.officeDocument.x2006.math.CTR] runs, each with a
     * [org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText] list on `getTArray()`).
     * However, CTOMath can nest many structural elements (fractions, radicals, etc.) each of
     * which contains further `CTOMathArg` containers. Recursing through every structural type
     * would be brittle as new schema types are added. Instead, we use
     * [org.apache.xmlbeans.XmlObject.xmlText] to serialize each `CTOMath` to XML and then
     * re-scan it with a StAX reader for `<m:t>` elements — robust to all nesting depths.
     *
     * Returns empty string when no math elements are present or on any extraction error.
     *
     * Phase 5b will replace the plain-text extraction here with an XSLT-based
     * OMML→MathML→LaTeX conversion path.
     */
    private fun extractOMathText(paragraph: XWPFParagraph): String {
        val ctp = try { paragraph.ctp } catch (_: Exception) { return "" }
        val sb = StringBuilder()

        // Collect all CTOMath objects: direct <m:oMath> and those nested in <m:oMathPara>.
        val oMathList: List<org.openxmlformats.schemas.officeDocument.x2006.math.CTOMath> = buildList {
            try { addAll(ctp.oMathArray.asList()) } catch (_: Exception) { /* not present */ }
            try {
                ctp.oMathParaArray.forEach { para ->
                    addAll(para.oMathArray.asList())
                }
            } catch (_: Exception) { /* not present */ }
        }

        for (oMath in oMathList) {
            val text = extractOMathTextFromElement(oMath)
            if (text.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(text)
            }
        }
        return sb.toString().trim()
    }

    /**
     * Serializes [oMath] to XML and scans the result for text-leaf elements via StAX.
     *
     * The StAX scan is safe: we never navigate the deep CTOMath XmlBeans tree (which
     * has ~30 structural-element types each with their own getter) and instead rely on
     * the schema-round-trip to flatten the document into a text stream.
     *
     * ## Namespace notes (POI 5.4.1 XmlBeans quirk)
     *
     * Within `<m:r>` (math run), text is carried by one of two elements depending on how
     * the POI XmlBeans schema wires them:
     * - `<m:t>` in the OMML namespace (`http://…officeDocument/2006/math`) — the spec
     *   form, created via `CTR.addNewT2()` and returned by `CTR.getT2Array()`.
     * - `<w:t>` in the wordprocessingml namespace (`http://…wordprocessingml/2006/main`) —
     *   the form created via `CTR.addNewT()` / `getTArray()`, which POI maps to
     *   `CTText` from the `wordprocessingml.x2006.main` package. Both element names
     *   carry the same text content.
     *
     * To handle both forms we match on `localName == "t"` regardless of namespace URI,
     * but only when we are currently inside an `<m:r>` (math run) element in the OMML
     * namespace. The depth guard prevents false positives from `<w:t>` elements that
     * might be present in non-math child elements of CTOMath (e.g. in CTOMath's own-run
     * elements when mixed with body runs).
     */
    private fun extractOMathTextFromElement(
        oMath: org.openxmlformats.schemas.officeDocument.x2006.math.CTOMath,
    ): String {
        val xml = try { oMath.xmlText() } catch (_: Exception) { return "" }
        val sb = StringBuilder()
        try {
            val factory = javax.xml.stream.XMLInputFactory.newInstance().also { f ->
                f.setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                f.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false)
            }
            val reader = factory.createXMLStreamReader(java.io.StringReader(xml))
            try {
                // Track whether we are inside an <m:r> element (OMML math-run depth).
                // Only collect <*:t> text when inside a math run to avoid spurious hits.
                var mathRunDepth = 0
                var inTextLeaf = false
                while (reader.hasNext()) {
                    when (reader.next()) {
                        javax.xml.stream.XMLStreamConstants.START_ELEMENT -> {
                            val local = reader.localName ?: ""
                            val ns = reader.namespaceURI ?: ""
                            if (local == "r" && ns == OMML_NS) {
                                mathRunDepth++
                            }
                            // Accept <m:t> (OMML NS) AND <w:t> (OOXML wordprocessingml NS),
                            // both of which POI uses for text inside math runs.
                            inTextLeaf = mathRunDepth > 0 && local == "t"
                        }
                        javax.xml.stream.XMLStreamConstants.CHARACTERS,
                        javax.xml.stream.XMLStreamConstants.CDATA -> {
                            if (inTextLeaf) {
                                val text = reader.text?.trim()
                                if (!text.isNullOrEmpty()) {
                                    if (sb.isNotEmpty()) sb.append(" ")
                                    sb.append(text)
                                }
                            }
                        }
                        javax.xml.stream.XMLStreamConstants.END_ELEMENT -> {
                            val local = reader.localName ?: ""
                            val ns = reader.namespaceURI ?: ""
                            if (inTextLeaf) inTextLeaf = false
                            if (local == "r" && ns == OMML_NS && mathRunDepth > 0) {
                                mathRunDepth--
                            }
                        }
                        else -> Unit
                    }
                }
            } finally {
                reader.close()
            }
        } catch (_: Exception) {
            // XML parse failure for this math element — return whatever was collected.
        }
        return sb.toString().trim()
    }

    /**
     * Rewrites POI's leaked footnote/endnote reference tokens to namespaced GFM footnote
     * references (SF-7 / HX-2):
     * - `[footnoteRef:N]` → `[^fnN]`
     * - `[endnoteRef:N]`  → `[^enN]`
     *
     * POI's `XWPFRun.text()` synthesizes these `[footnoteRef:N]` / `[endnoteRef:N]` strings for
     * `<w:footnoteReference>` / `<w:endnoteReference>` run children — there is no other text to
     * intercept (the elements carry no text node), so a string rewrite on the run text is the
     * faithful, lossless transform. The `fn`/`en` namespace prefix keeps a footnote and an
     * endnote that share the same numeric id from colliding on a single `[^N]` label, and the
     * markers match the definitions emitted by
     * [com.workflow.orchestrator.document.poi.visitor.FootnoteExtractionVisitor].
     */
    private fun rewriteNoteReferences(text: String): String {
        if (!text.contains("footnoteRef:") && !text.contains("endnoteRef:")) return text
        return text
            .replace(FOOTNOTE_REF_TOKEN) { "[^${NoteMarkers.FOOTNOTE_PREFIX}${it.groupValues[1]}]" }
            .replace(ENDNOTE_REF_TOKEN) { "[^${NoteMarkers.ENDNOTE_PREFIX}${it.groupValues[1]}]" }
    }

    private companion object {
        /** OMML namespace URI for `<m:t>` text elements. */
        const val OMML_NS = "http://schemas.openxmlformats.org/officeDocument/2006/math"

        /** POI's synthesized footnote-reference token: `[footnoteRef:N]`. */
        val FOOTNOTE_REF_TOKEN = Regex("""\[footnoteRef:(\d+)]""")

        /** POI's synthesized endnote-reference token: `[endnoteRef:N]`. */
        val ENDNOTE_REF_TOKEN = Regex("""\[endnoteRef:(\d+)]""")
    }

    private fun headingLevel(paragraph: XWPFParagraph): Int? {
        val styleId = paragraph.style ?: return null
        val normalized = styleId.lowercase().replace("_20_", " ").replace("_", " ")

        // Match "heading 1" through "heading 6".
        val withSpace = Regex("""^heading\s*(\d)$""").find(normalized)
        if (withSpace != null) {
            val level = withSpace.groupValues[1].toIntOrNull() ?: return null
            if (level in 1..6) return level
        }

        // Custom heading-equivalent styles. Tight allowlist — only the common Word built-ins.
        return when (normalized) {
            "title" -> 1
            "subtitle" -> 2
            "quote", "intensequote" -> 3
            else -> null
        }
    }
}
