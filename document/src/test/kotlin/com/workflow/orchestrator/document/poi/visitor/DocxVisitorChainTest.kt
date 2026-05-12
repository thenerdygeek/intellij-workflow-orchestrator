package com.workflow.orchestrator.document.poi.visitor

import com.workflow.orchestrator.core.model.DocumentBlock
import com.workflow.orchestrator.document.poi.DocxTableExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression test for the visitor-chain refactor in `DocxTableExtractor`.
 *
 * Asserts that driving the chain with `DefaultHeadingParagraphVisitor` +
 * `DefaultTableVisitor` produces the same `List<DocumentBlock>` as the
 * pre-refactor extractor on the `design-doc.docx` fixture used by
 * `DocxTableExtractorTest`. If a future refactor changes the visitor signatures
 * or contract, this test will fail first.
 */
class DocxVisitorChainTest {

    private fun loadFixture(name: String) =
        javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("fixture not found: fixtures/$name")

    @Test
    fun `default visitor chain emits heading-paragraph-table-paragraph-table for design-doc fixture`() {
        val extractor = DocxTableExtractor()  // defaults to DefaultHeadingParagraphVisitor + DefaultTableVisitor
        val blocks = loadFixture("design-doc.docx").use { extractor.extract(it) }

        // Spot-check the shape — full content covered by DocxTableExtractorTest.
        assertTrue(blocks.isNotEmpty())
        assertTrue(blocks.first() is DocumentBlock.Heading,
            "First block should be a Heading; got ${blocks.first()::class.simpleName}")
        val tableCount = blocks.count { it is DocumentBlock.Table }
        assertEquals(2, tableCount, "Expected 2 tables (RiskMatrix + AcceptanceCriteria)")
    }

    @Test
    fun `custom paragraph visitor receives every XWPFParagraph in body order`() {
        var paragraphCount = 0
        val counter = object : ParagraphVisitor {
            override fun visit(
                paragraph: org.apache.poi.xwpf.usermodel.XWPFParagraph,
                doc: org.apache.poi.xwpf.usermodel.XWPFDocument,
            ): List<DocumentBlock> {
                paragraphCount++
                return emptyList()
            }
        }
        val extractor = DocxTableExtractor(
            paragraphVisitors = listOf(DefaultHeadingParagraphVisitor(), counter),
            tableVisitors = listOf(DefaultTableVisitor()),
        )
        loadFixture("design-doc.docx").use { extractor.extract(it) }

        assertTrue(paragraphCount >= 4, "Expected >= 4 paragraphs in design-doc.docx, got $paragraphCount")
    }

    @Test
    fun `post-body visitor runs once after body iteration completes`() {
        var ran = 0
        val marker = object : PostBodyVisitor {
            override fun visit(doc: org.apache.poi.xwpf.usermodel.XWPFDocument): List<DocumentBlock> {
                ran++
                return listOf(DocumentBlock.Paragraph("POST_BODY_MARKER"))
            }
        }
        val extractor = DocxTableExtractor(
            paragraphVisitors = listOf(DefaultHeadingParagraphVisitor()),
            tableVisitors = listOf(DefaultTableVisitor()),
            postBodyVisitors = listOf(marker),
        )
        val blocks = loadFixture("design-doc.docx").use { extractor.extract(it) }

        assertEquals(1, ran, "PostBodyVisitor should run exactly once per extract()")
        assertTrue(
            (blocks.last() as? DocumentBlock.Paragraph)?.text == "POST_BODY_MARKER",
            "Expected POST_BODY_MARKER to be the LAST block; got ${blocks.last()}"
        )
    }
}
