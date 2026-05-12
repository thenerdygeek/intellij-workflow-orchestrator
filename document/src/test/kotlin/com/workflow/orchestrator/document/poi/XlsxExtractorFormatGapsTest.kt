package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.common.usermodel.HyperlinkType
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Characterization tests pinning the scope of [XlsxTableExtractor].
 *
 * Each test builds a minimal in-memory XLSX with a specific feature (cell comment,
 * embedded image, hyperlink, hidden sheet, formula text) and asserts the extractor's
 * behaviour. Cell comments are now extracted as [DocumentBlock.Comment] blocks emitted
 * immediately after the sheet's Table (Phase 1).
 */
class XlsxExtractorFormatGapsTest {

    private val extractor = XlsxTableExtractor()

    // ── Cell comments (positive coverage after Phase 1) ────────────────────────

    @Test
    fun `cell comment is extracted as DocumentBlock Comment with cell-ref anchor immediately after the Table`() {
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            val helper = wb.creationHelper
            val drawing = sheet.createDrawingPatriarch()

            sheet.createRow(0).createCell(0).setCellValue("Item")
            val cell = sheet.createRow(1).createCell(0)
            cell.setCellValue("widget")
            val anchor = helper.createClientAnchor().apply {
                setCol1(0); setCol2(2); row1 = 1; row2 = 3
            }
            val comment = drawing.createCellComment(anchor).apply {
                string = helper.createRichTextString("REVIEW NOTE: confirm SKU")
                author = "alice"
            }
            cell.cellComment = comment
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))

        // Find the Table for Sheet1.
        val tableIdx = blocks.indexOfFirst { it is DocumentBlock.Table }
        assertTrue(tableIdx >= 0, "Expected a Table block for Sheet1")

        // The Comment should appear IMMEDIATELY after the Table.
        val next = blocks.getOrNull(tableIdx + 1)
        assertTrue(
            next is DocumentBlock.Comment,
            "Expected a Comment block immediately after the Table; got ${next?.let { it::class.simpleName }}. " +
                "All blocks: ${blocks.map { it::class.simpleName }}"
        )
        val comment = next as DocumentBlock.Comment
        assertEquals(DocumentBlock.Comment.Kind.REVIEW, comment.kind)
        assertEquals("alice", comment.author)
        assertEquals("REVIEW NOTE: confirm SKU", comment.text)
        assertEquals("A2", comment.anchorText,
            "Anchor should be the cell reference (column letter + 1-indexed row) so the LLM can map back to the cell")
    }

    @Test
    fun `multiple cell comments emit in row-major order after the Table`() {
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            val helper = wb.creationHelper
            val drawing = sheet.createDrawingPatriarch()

            sheet.createRow(0).createCell(0).setCellValue("Item")
            sheet.createRow(0).createCell(1).setCellValue("Note")

            // Cell B2 (col=1, row=1) — comment "two"
            val b2 = sheet.createRow(1).createCell(1)
            b2.setCellValue("b2-val")
            val b2Anchor = helper.createClientAnchor().apply { setCol1(1); setCol2(3); row1 = 1; row2 = 3 }
            b2.cellComment = drawing.createCellComment(b2Anchor).apply {
                string = helper.createRichTextString("two"); author = "bob"
            }
            // Cell A2 (col=0, row=1) — comment "one"
            val a2 = sheet.getRow(1).createCell(0)
            a2.setCellValue("a2-val")
            val a2Anchor = helper.createClientAnchor().apply { setCol1(0); setCol2(2); row1 = 1; row2 = 3 }
            a2.cellComment = drawing.createCellComment(a2Anchor).apply {
                string = helper.createRichTextString("one"); author = "alice"
            }
            // Cell A3 (col=0, row=2) — comment "three"
            val a3 = sheet.createRow(2).createCell(0)
            a3.setCellValue("a3-val")
            val a3Anchor = helper.createClientAnchor().apply { setCol1(0); setCol2(2); row1 = 2; row2 = 4 }
            a3.cellComment = drawing.createCellComment(a3Anchor).apply {
                string = helper.createRichTextString("three"); author = "carol"
            }
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val comments = blocks.filterIsInstance<DocumentBlock.Comment>()

        assertEquals(3, comments.size)
        assertEquals(listOf("A2", "B2", "A3"), comments.map { it.anchorText },
            "Comments should be emitted in row-major order: row 1 left-to-right (A2 then B2), then row 2 (A3)")
        assertEquals(listOf("one", "two", "three"), comments.map { it.text })
    }

    @Test
    fun `each sheets comments appear immediately after that sheets own Table`() {
        val bytes = buildXlsx { wb ->
            val helper = wb.creationHelper

            val s1 = wb.createSheet("Sheet1")
            val d1 = s1.createDrawingPatriarch()
            s1.createRow(0).createCell(0).setCellValue("s1-header")
            val s1Cell = s1.createRow(1).createCell(0).apply { setCellValue("s1-val") }
            s1Cell.cellComment = d1.createCellComment(
                helper.createClientAnchor().apply { setCol1(0); setCol2(2); row1 = 1; row2 = 3 }
            ).apply { string = helper.createRichTextString("s1-comment"); author = "alice" }

            val s2 = wb.createSheet("Sheet2")
            val d2 = s2.createDrawingPatriarch()
            s2.createRow(0).createCell(0).setCellValue("s2-header")
            val s2Cell = s2.createRow(1).createCell(0).apply { setCellValue("s2-val") }
            s2Cell.cellComment = d2.createCellComment(
                helper.createClientAnchor().apply { setCol1(0); setCol2(2); row1 = 1; row2 = 3 }
            ).apply { string = helper.createRichTextString("s2-comment"); author = "bob" }
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))

        // Expected order: Heading(Sheet1) → Table(Sheet1) → Comment(s1-comment) →
        //                 Heading(Sheet2) → Table(Sheet2) → Comment(s2-comment)
        val classes = blocks.map { it::class.simpleName!! }
        val expectedShape = listOf("Heading", "Table", "Comment", "Heading", "Table", "Comment")
        assertEquals(expectedShape, classes,
            "Multi-sheet workbook: each sheet's comments must belong to its OWN Table. Got: $classes")

        val comments = blocks.filterIsInstance<DocumentBlock.Comment>()
        assertEquals("s1-comment", comments[0].text)
        assertEquals("s2-comment", comments[1].text)
    }

    @Test
    fun `comments on cells beyond header arity are still collected`() {
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            val helper = wb.creationHelper
            val drawing = sheet.createDrawingPatriarch()

            // Headers only cover A and B
            val headerRow = sheet.createRow(0)
            headerRow.createCell(0).setCellValue("A")
            headerRow.createCell(1).setCellValue("B")

            // Data row 2 has a comment on column D (col=3, beyond header arity)
            val dataRow = sheet.createRow(1)
            dataRow.createCell(0).setCellValue("a-val")
            dataRow.createCell(1).setCellValue("b-val")
            val outerCell = dataRow.createCell(3).apply { setCellValue("extra") }
            outerCell.cellComment = drawing.createCellComment(
                helper.createClientAnchor().apply { setCol1(3); setCol2(5); row1 = 1; row2 = 3 }
            ).apply { string = helper.createRichTextString("comment-out-of-band"); author = "alice" }
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val comment = blocks.filterIsInstance<DocumentBlock.Comment>().singleOrNull()
        assertNotNull(comment, "Expected one Comment for the out-of-arity cell")
        assertEquals("comment-out-of-band", comment!!.text)
        assertEquals("D2", comment.anchorText,
            "Anchor should be the out-of-band cell's A1 ref (D2), not coerced to a header column")
    }

    // ── Embedded images (positive coverage after Phase 2) ─────────────────────

    @Test
    fun `embedded image is extracted as EmbeddedFileRef with on-disk path when ImageExtractionService is wired`(
        @org.junit.jupiter.api.io.TempDir downloads: java.nio.file.Path,
    ) {
        val pngBytes = tinyPng()
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            sheet.createRow(0).createCell(0).setCellValue("Header")
            sheet.createRow(1).createCell(0).setCellValue("data1")
            val picIdx = wb.addPicture(pngBytes, Workbook.PICTURE_TYPE_PNG)
            val drawing = sheet.createDrawingPatriarch()
            val anchor = wb.creationHelper.createClientAnchor().apply {
                setCol1(2); setCol2(4); row1 = 0; row2 = 5
            }
            drawing.createPicture(anchor, picIdx)
        }

        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        val imageExtractor = XlsxTableExtractor(imageService = imageService, docKey = "test-doc.xlsx")
        val blocks = imageExtractor.extract(ByteArrayInputStream(bytes))

        val imageRefs = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>()
        assertEquals(1, imageRefs.size, "Expected exactly one EmbeddedFileRef for the sheet picture")
        val ref = imageRefs.single()
        assertEquals("image/png", ref.mimeType)
        assertNotNull(ref.path, "path should be non-null when ImageExtractionService is wired")
        assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of(ref.path!!)),
            "Saved file should exist on disk at the reported path")
        // Ordering: image appears AFTER the Table.
        val tableIdx = blocks.indexOfFirst { it is DocumentBlock.Table }
        val imageIdx = blocks.indexOfFirst { it is DocumentBlock.EmbeddedFileRef }
        assertTrue(imageIdx > tableIdx, "Image must appear after the Table; got Table@$tableIdx Image@$imageIdx")
    }

    @Test
    fun `embedded image without ImageExtractionService wired emits no EmbeddedFileRef — legacy behaviour preserved`() {
        val pngBytes = tinyPng()
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            sheet.createRow(0).createCell(0).setCellValue("Header")
            sheet.createRow(1).createCell(0).setCellValue("data1")
            val picIdx = wb.addPicture(pngBytes, Workbook.PICTURE_TYPE_PNG)
            val drawing = sheet.createDrawingPatriarch()
            val anchor = wb.creationHelper.createClientAnchor().apply {
                setCol1(2); setCol2(4); row1 = 0; row2 = 5
            }
            drawing.createPicture(anchor, picIdx)
        }
        val blocks = XlsxTableExtractor().extract(ByteArrayInputStream(bytes))
        assertTrue(blocks.none { it is DocumentBlock.EmbeddedFileRef },
            "Without ImageExtractionService, image emission is skipped")
    }

    @Test
    fun `multi-sheet workbook attaches each sheets images to its own Table range`(
        @org.junit.jupiter.api.io.TempDir downloads: java.nio.file.Path,
    ) {
        val pngBytes = tinyPng()
        val bytes = buildXlsx { wb ->
            val s1 = wb.createSheet("Sheet1")
            s1.createRow(0).createCell(0).setCellValue("s1-h")
            s1.createRow(1).createCell(0).setCellValue("s1-v")
            val picIdx1 = wb.addPicture(pngBytes, Workbook.PICTURE_TYPE_PNG)
            s1.createDrawingPatriarch().createPicture(
                wb.creationHelper.createClientAnchor().apply { setCol1(2); setCol2(4); row1 = 0; row2 = 5 },
                picIdx1,
            )

            val s2 = wb.createSheet("Sheet2")
            s2.createRow(0).createCell(0).setCellValue("s2-h")
            s2.createRow(1).createCell(0).setCellValue("s2-v")
            // Different bytes so the test can tell which image belongs to which sheet.
            val pngBytes2 = pngBytes + byteArrayOf(0xFF.toByte(), 0xEE.toByte())  // pad to differ
            val picIdx2 = wb.addPicture(pngBytes2, Workbook.PICTURE_TYPE_PNG)
            s2.createDrawingPatriarch().createPicture(
                wb.creationHelper.createClientAnchor().apply { setCol1(2); setCol2(4); row1 = 0; row2 = 5 },
                picIdx2,
            )
        }

        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        val imageExtractor = XlsxTableExtractor(imageService = imageService, docKey = "multi-sheet.xlsx")
        val blocks = imageExtractor.extract(ByteArrayInputStream(bytes))

        // Expected shape: Heading(s1), Table(s1), EmbeddedFileRef(s1), Heading(s2), Table(s2), EmbeddedFileRef(s2)
        val classes = blocks.map { it::class.simpleName!! }
        assertEquals(
            listOf("Heading", "Table", "EmbeddedFileRef", "Heading", "Table", "EmbeddedFileRef"),
            classes,
            "Multi-sheet ordering: each sheet's image belongs to its OWN Table range. Got: $classes",
        )
    }

    @Test
    fun `XLSX with no images emits no EmbeddedFileRef even with imageService wired`(
        @org.junit.jupiter.api.io.TempDir downloads: java.nio.file.Path,
    ) {
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            sheet.createRow(0).createCell(0).setCellValue("only text")
        }
        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        val imageExtractor = XlsxTableExtractor(imageService = imageService, docKey = "noimg.xlsx")
        val blocks = imageExtractor.extract(ByteArrayInputStream(bytes))
        assertTrue(blocks.none { it is DocumentBlock.EmbeddedFileRef })
    }

    // ── Hyperlinks ────────────────────────────────────────────────────────────

    @Test
    fun `gap hyperlink target is dropped — only the cell display value survives`() {
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            sheet.createRow(0).createCell(0).setCellValue("Link")
            val cell = sheet.createRow(1).createCell(0)
            cell.setCellValue("Docs")
            val link = wb.creationHelper.createHyperlink(HyperlinkType.URL)
            link.address = "https://example.com/handbook"
            cell.hyperlink = link
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val cells = blocks.filterIsInstance<DocumentBlock.Table>().flatMap { it.rows.flatten() }

        assertTrue("Docs" in cells, "Display text survives")
        assertFalse(cells.any { it.contains("example.com") },
            "URL is dropped — XlsxTableExtractor never reads Cell.hyperlink")
    }

    // ── Formulas ──────────────────────────────────────────────────────────────

    @Test
    fun `formula text is intentionally dropped — only the evaluated value is extracted`() {
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            sheet.createRow(0).createCell(0).setCellValue("X")
            sheet.createRow(1).createCell(0).setCellFormula("1+2+3")
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val cell = blocks.filterIsInstance<DocumentBlock.Table>().first().rows[0][0]

        assertEquals("6", cell, "Formula is evaluated; the LLM sees the answer, not the recipe")
    }

    // ── Hidden sheets ─────────────────────────────────────────────────────────

    @Test
    fun `surprise hidden sheets are still extracted — no visibility filter`() {
        val bytes = buildXlsx { wb ->
            wb.createSheet("Visible").apply {
                createRow(0).createCell(0).setCellValue("v1")
            }
            val hidden = wb.createSheet("Hidden").apply {
                createRow(0).createCell(0).setCellValue("h1")
            }
            wb.setSheetHidden(wb.getSheetIndex(hidden), true)
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val sheetHeadings = blocks.filterIsInstance<DocumentBlock.Heading>().map { it.text }

        assertEquals(listOf("Visible", "Hidden"), sheetHeadings,
            "Hidden sheets ARE extracted today — sheet.sheetVisibility is never consulted. " +
                "This may or may not be desired (auditor data leak risk vs. completeness).")
    }

    // ── Inline formatting ─────────────────────────────────────────────────────

    @Test
    fun `gap rich text bold and color formatting is dropped on output`() {
        val bytes = buildXlsx { wb ->
            val sheet = wb.createSheet("Sheet1")
            sheet.createRow(0).createCell(0).setCellValue("Status")
            val cell = sheet.createRow(1).createCell(0)
            val rich = wb.creationHelper.createRichTextString("CRITICAL")
            // POI rich-text font application — only verifying the value path drops it.
            cell.setCellValue(rich)
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val v = blocks.filterIsInstance<DocumentBlock.Table>().first().rows[0][0]
        assertEquals("CRITICAL", v,
            "Rich-text runs collapse to plain text — DocumentBlock.Table cells are flat strings")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildXlsx(build: (XSSFWorkbook) -> Unit): ByteArray {
        val wb = XSSFWorkbook()
        try {
            build(wb)
            val out = ByteArrayOutputStream()
            wb.write(out)
            return out.toByteArray()
        } finally {
            wb.close()
        }
    }

    private fun tinyPng(): ByteArray {
        val img = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }
}
