package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable

/**
 * Visitor invoked once per `XWPFParagraph` during DOCX body iteration.
 *
 * Each visitor may emit zero, one, or many [DocumentBlock]s. Visitors run in
 * declaration order; the first non-empty emission wins for the "primary" content
 * (i.e. once any visitor returns a non-empty list, later visitors still run but
 * are expected to emit only adjunct content like a Comment attached to the paragraph).
 *
 * Phase 0 ships one default visitor that ports the current heading/paragraph logic.
 * Phases 1/2/3 add new visitors (comment-anchor, image-extraction, list-numbering)
 * without modifying the default.
 */
interface ParagraphVisitor {
    fun visit(paragraph: XWPFParagraph, doc: XWPFDocument): List<DocumentBlock>
}

/**
 * Visitor invoked once per `XWPFTable` during DOCX body iteration.
 *
 * Same emission semantics as [ParagraphVisitor].
 */
interface TableVisitor {
    fun visit(table: XWPFTable, doc: XWPFDocument): List<DocumentBlock>
}

/**
 * Visitor invoked ONCE after the body iteration completes. Used for content that
 * lives in the DOCX but is collected to the end of the extraction output — most
 * importantly, [DocumentBlock.Footnote] blocks (Phase 5) and document-summary
 * `KeyValueGroup`s (Phase 4 for PDF, not used in DOCX yet but slot is reserved).
 *
 * Emitted blocks are appended to the end of the extractor's returned `List<DocumentBlock>`,
 * which is the contract the extractor relies on for footnote ordering.
 */
interface PostBodyVisitor {
    fun visit(doc: XWPFDocument): List<DocumentBlock>
}
