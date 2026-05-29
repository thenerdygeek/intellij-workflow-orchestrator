package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.util.IOUtils
import org.apache.poi.util.Units
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Characterization tests that pin the current scope of [DocxTableExtractor].
 *
 * Each test builds a minimal in-memory DOCX, runs the extractor, and asserts that a
 * specific feature is **dropped** by the body-element walk. These are not bug reports —
 * they document the deliberate "body-only" iteration in
 * [DocxTableExtractor.extract] (paragraphs + tables only). When the extractor is taught
 * to surface comments / footnotes / images / inline formatting, the matching `gap_…`
 * test will flip from green to red and the author must update it.
 */
class DocxExtractorFormatGapsTest {

    private val extractor = DocxTableExtractor()

    // ── Inline formatting ─────────────────────────────────────────────────────

    @Test
    fun `gap inline bold and italic markup is lost — only plain text reaches DocumentBlock`() {
        val bytes = buildDocx { doc ->
            val p = doc.createParagraph()
            val bold = p.createRun().apply { isBold = true; setText("BOLD ") }
            val italic = p.createRun().apply { isItalic = true; setText("ITALIC ") }
            val plain = p.createRun().apply { setText("plain") }
            check(bold.text() == "BOLD " && italic.text() == "ITALIC " && plain.text() == "plain")
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val paragraphs = blocks.filterIsInstance<DocumentBlock.Paragraph>()

        assertEquals(1, paragraphs.size, "Expected a single paragraph block")
        val text = paragraphs.first().text
        assertEquals("BOLD ITALIC plain", text,
            "Bold/italic runs collapse to plain text — DocumentBlock has no inline-style field")
        assertFalse(text.contains("**") || text.contains("<b>") || text.contains("*ITALIC*"),
            "Extractor must not invent Markdown/HTML formatting; it has none to invent from")
    }

    // ── Hyperlinks ────────────────────────────────────────────────────────────

    @Test
    fun `hyperlink URL is preserved as parenthetical suffix on the visible label`() {
        val bytes = buildDocx { doc ->
            val p = doc.createParagraph()
            // createHyperlinkRun returns an XWPFHyperlinkRun bound to the URI; the run text
            // is what the reader sees. The href itself lives in w:hyperlink relations.
            val link = p.createHyperlinkRun("https://workflow.example.com/spec")
            link.setText("the spec")
            p.createRun().setText(" — please read.")
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val text = blocks.filterIsInstance<DocumentBlock.Paragraph>().joinToString("\n") { it.text }

        assertTrue(text.contains("the spec (https://workflow.example.com/spec)"),
            "Hyperlink should appear as 'visible text (url)'; got: $text")
        assertTrue(text.contains("— please read."), "Surrounding prose preserved")
    }

    // ── Embedded images (positive coverage after Phase 2) ─────────────────────

    @Test
    fun `inline image emits EmbeddedFileRef with on-disk path when ImageExtractionService is wired`(
        @org.junit.jupiter.api.io.TempDir downloads: java.nio.file.Path,
    ) {
        // PoiHardening caps IOUtils byte-array allocations at 50 MB; XWPFRun.addPicture asks POI
        // to pre-size for up to 100 MB. Temporarily raise the cap for fixture creation, then
        // restore the hardening value so the extractor under test still sees a hardened POI.
        IOUtils.setByteArrayMaxOverride(200_000_000)
        val pngBytes = tinyPng()
        val bytes = try {
            buildDocx { doc ->
                val p = doc.createParagraph()
                p.createRun().apply {
                    setText("Before image. ")
                    addPicture(
                        ByteArrayInputStream(pngBytes),
                        XWPFDocument.PICTURE_TYPE_PNG,
                        "tiny.png",
                        Units.toEMU(10.0),
                        Units.toEMU(10.0),
                    )
                }
                doc.createParagraph().createRun().setText("After image.")
            }
        } finally {
            IOUtils.setByteArrayMaxOverride(50_000_000)
        }

        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        val extractor = DocxTableExtractor(imageService = imageService, docKey = "test-doc.docx")
        val blocks = extractor.extract(ByteArrayInputStream(bytes))

        assertTrue(blocks.any { it is DocumentBlock.Paragraph && it.text.contains("Before image") })
        assertTrue(blocks.any { it is DocumentBlock.Paragraph && it.text.contains("After image") })
        val imageRef = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>().singleOrNull()
        assertNotNull(imageRef, "Expected exactly one EmbeddedFileRef for the inline image")
        // XWPFPictureData.getFileName() returns the internal part name (e.g. "image1.png"),
        // not the suggested name passed to XWPFRun.addPicture. Both contain the ".png" extension.
        assertTrue(imageRef!!.name.endsWith(".png"),
            "Expected a PNG filename but got: ${imageRef.name}")
        assertEquals("image/png", imageRef.mimeType)
        assertNotNull(imageRef.path, "path should be non-null when ImageExtractionService is wired")
        assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of(imageRef.path!!)),
            "Saved file should exist on disk at the reported path")
    }

    @Test
    fun `oversize image emits EmbeddedFileRef with null path — bytes never materialise into memory`(
        @org.junit.jupiter.api.io.TempDir downloads: java.nio.file.Path,
    ) {
        // Build a "large" PNG by padding it with junk after the PNG signature — just enough
        // size to exceed a tiny test-only cap. POI's hardening dance still applies.
        IOUtils.setByteArrayMaxOverride(200_000_000)
        val largeBytes = tinyPng() + ByteArray(2 * 1024 * 1024)  // ~2 MB total
        val bytes = try {
            buildDocx { doc ->
                doc.createParagraph().createRun().apply {
                    setText("Body. ")
                    addPicture(
                        ByteArrayInputStream(largeBytes),
                        XWPFDocument.PICTURE_TYPE_PNG,
                        "large.png",
                        Units.toEMU(10.0),
                        Units.toEMU(10.0),
                    )
                }
            }
        } finally {
            IOUtils.setByteArrayMaxOverride(50_000_000)
        }

        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        // Use a tiny cap (1 MB) so the 2 MB image triggers the oversize branch.
        val visitor = com.workflow.orchestrator.document.poi.visitor.ImageExtractionVisitor(
            imageService = imageService,
            docKey = "oversize-test.docx",
            maxBytesPerImage = 1L * 1024 * 1024,
        )
        val extractor = DocxTableExtractor(
            paragraphVisitors = listOf(
                com.workflow.orchestrator.document.poi.visitor.DefaultHeadingParagraphVisitor(),
                visitor,
            ),
        )
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val imageRef = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>().singleOrNull()
        assertNotNull(imageRef, "Oversize image still emits an EmbeddedFileRef placeholder so the LLM knows an image exists")
        assertNull(imageRef!!.path, "Oversize image: path must be null — bytes never materialised in memory")
        // The downloads dir should be empty — no oversized file was written.
        val savedFiles = java.nio.file.Files.walk(downloads).filter { java.nio.file.Files.isRegularFile(it) }.toList()
        assertTrue(savedFiles.isEmpty(),
            "No file should have been written for the oversize image; got: $savedFiles")
    }

    @Test
    fun `duplicate inline images share the same on-disk path via content addressing`(
        @org.junit.jupiter.api.io.TempDir downloads: java.nio.file.Path,
    ) {
        IOUtils.setByteArrayMaxOverride(200_000_000)
        val pngBytes = tinyPng()
        val bytes = try {
            buildDocx { doc ->
                // Same image embedded twice in the same paragraph
                val p = doc.createParagraph()
                repeat(2) {
                    p.createRun().apply {
                        addPicture(
                            ByteArrayInputStream(pngBytes),
                            XWPFDocument.PICTURE_TYPE_PNG,
                            "dup.png",
                            Units.toEMU(10.0),
                            Units.toEMU(10.0),
                        )
                    }
                }
            }
        } finally {
            IOUtils.setByteArrayMaxOverride(50_000_000)
        }

        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        val extractor = DocxTableExtractor(imageService = imageService, docKey = "dup-test.docx")
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val refs = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>()
        assertEquals(2, refs.size, "Two EmbeddedFileRef blocks for the two embedding sites")
        assertEquals(refs[0].path, refs[1].path,
            "Content-addressed: identical bytes within one doc share the same on-disk path")
    }

    @Test
    fun `DOCX with no images emits no EmbeddedFileRef even with imageService wired`(
        @org.junit.jupiter.api.io.TempDir downloads: java.nio.file.Path,
    ) {
        val bytes = buildDocx { doc ->
            doc.createParagraph().createRun().setText("Just text, no images at all.")
        }
        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        val extractor = DocxTableExtractor(imageService = imageService, docKey = "noimg-test.docx")
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        assertTrue(blocks.none { it is DocumentBlock.EmbeddedFileRef },
            "No images in the doc → visitor emits nothing")
    }

    @Test
    fun `inline image without ImageExtractionService wired emits no EmbeddedFileRef — legacy behaviour preserved`() {
        // PoiHardening dance for in-memory fixture
        IOUtils.setByteArrayMaxOverride(200_000_000)
        val pngBytes = tinyPng()
        val bytes = try {
            buildDocx { doc ->
                val p = doc.createParagraph()
                p.createRun().apply {
                    setText("Body. ")
                    addPicture(
                        ByteArrayInputStream(pngBytes),
                        XWPFDocument.PICTURE_TYPE_PNG,
                        "tiny.png",
                        Units.toEMU(10.0),
                        Units.toEMU(10.0),
                    )
                }
            }
        } finally {
            IOUtils.setByteArrayMaxOverride(50_000_000)
        }

        // No imageService → legacy behaviour: image is silently dropped (Phase 0/1 baseline).
        val blocks = DocxTableExtractor().extract(ByteArrayInputStream(bytes))
        assertTrue(blocks.none { it is DocumentBlock.EmbeddedFileRef },
            "Without ImageExtractionService, ImageExtractionVisitor is not in the chain → no image emission")
    }

    // ── Image alt-text (IMG-1) ────────────────────────────────────────────────

    @Test
    fun `inline image carries docPr descr as the EmbeddedFileRef altText (IMG-1)`(
        @org.junit.jupiter.api.io.TempDir downloads: java.nio.file.Path,
    ) {
        IOUtils.setByteArrayMaxOverride(200_000_000)
        val pngBytes = tinyPng()
        val bytes = try {
            buildDocx { doc ->
                val p = doc.createParagraph()
                val run = p.createRun()
                val pic = run.addPicture(
                    ByteArrayInputStream(pngBytes),
                    XWPFDocument.PICTURE_TYPE_PNG,
                    "abacus.png",
                    Units.toEMU(40.0),
                    Units.toEMU(40.0),
                )
                check(pic != null)
                // Set the drawing-level wp:docPr descr — this is where Word stores alt-text.
                val inline = run.ctr.drawingArray.first().inlineArray.first()
                inline.docPr.descr = "Abacus with solid fill"
            }
        } finally {
            IOUtils.setByteArrayMaxOverride(50_000_000)
        }

        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        val blocks = DocxTableExtractor(imageService = imageService, docKey = "alt.docx")
            .extract(ByteArrayInputStream(bytes))
        val ref = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>().single()
        assertEquals("Abacus with solid fill", ref.altText,
            "docPr descr must surface as the figure's alt-text")
    }

    @Test
    fun `docPr title is preferred over descr for altText`(
        @org.junit.jupiter.api.io.TempDir downloads: java.nio.file.Path,
    ) {
        IOUtils.setByteArrayMaxOverride(200_000_000)
        val pngBytes = tinyPng()
        val bytes = try {
            buildDocx { doc ->
                val run = doc.createParagraph().createRun()
                run.addPicture(
                    ByteArrayInputStream(pngBytes),
                    XWPFDocument.PICTURE_TYPE_PNG, "boulders.png",
                    Units.toEMU(40.0), Units.toEMU(40.0),
                )
                val inline = run.ctr.drawingArray.first().inlineArray.first()
                inline.docPr.title = "Photo of boulders on beach in bright sunshine"
                inline.docPr.descr = "fallback descr that should be ignored"
            }
        } finally {
            IOUtils.setByteArrayMaxOverride(50_000_000)
        }
        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        val ref = DocxTableExtractor(imageService = imageService, docKey = "title.docx")
            .extract(ByteArrayInputStream(bytes))
            .filterIsInstance<DocumentBlock.EmbeddedFileRef>().single()
        assertEquals("Photo of boulders on beach in bright sunshine", ref.altText)
    }

    // ── SmartArt / shape placeholders (IMG-3) ─────────────────────────────────

    @Test
    fun `a non-picture drawing object with the diagram graphicData URI emits a SmartArt placeholder`(
        @org.junit.jupiter.api.io.TempDir downloads: java.nio.file.Path,
    ) {
        IOUtils.setByteArrayMaxOverride(200_000_000)
        val pngBytes = tinyPng()
        // Build a valid inline drawing via POI, then re-point its graphicData URI to the
        // SmartArt diagram namespace — POI never returns this as an embeddedPicture, so the
        // visitor must detect it via the URI scan and emit a presence placeholder.
        val bytes = try {
            buildDocx { doc ->
                val run = doc.createParagraph().createRun()
                run.addPicture(
                    ByteArrayInputStream(pngBytes),
                    XWPFDocument.PICTURE_TYPE_PNG, "diagram.png",
                    Units.toEMU(40.0), Units.toEMU(40.0),
                )
                val inline = run.ctr.drawingArray.first().inlineArray.first()
                inline.docPr.name = "Diagram 2"
                // POI's addPicture auto-fills docPr@descr with the filename; the real corpus
                // SmartArt frame had no descr, so clear it to mirror that (name is the identity).
                if (inline.docPr.isSetDescr) inline.docPr.unsetDescr()
                inline.graphic.graphicData.uri =
                    "http://schemas.openxmlformats.org/drawingml/2006/diagram"
            }
        } finally {
            IOUtils.setByteArrayMaxOverride(50_000_000)
        }
        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        val blocks = DocxTableExtractor(imageService = imageService, docKey = "smart.docx")
            .extract(ByteArrayInputStream(bytes))
        val obj = blocks.filterIsInstance<DocumentBlock.EmbeddedObjectRef>().single()
        assertEquals(DocumentBlock.EmbeddedObjectRef.Kind.SMARTART, obj.kind)
        assertEquals("Diagram 2", obj.name)
        // NB: real-world SmartArt is never returned by POI's embeddedPictures, so it only
        // surfaces via the diagram-URI scan above. (This synthetic fixture re-points a real
        // picture, so POI still also yields it as an image — not asserted here.)
    }

    @Test
    fun `a wordprocessingShape drawing emits a Shape placeholder named from docPr`(
        @org.junit.jupiter.api.io.TempDir downloads: java.nio.file.Path,
    ) {
        IOUtils.setByteArrayMaxOverride(200_000_000)
        val pngBytes = tinyPng()
        val bytes = try {
            buildDocx { doc ->
                val run = doc.createParagraph().createRun()
                run.addPicture(
                    ByteArrayInputStream(pngBytes),
                    XWPFDocument.PICTURE_TYPE_PNG, "shape.png",
                    Units.toEMU(40.0), Units.toEMU(40.0),
                )
                val inline = run.ctr.drawingArray.first().inlineArray.first()
                inline.docPr.name = "Direct Access Storage 1"
                if (inline.docPr.isSetDescr) inline.docPr.unsetDescr()
                inline.graphic.graphicData.uri =
                    "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
            }
        } finally {
            IOUtils.setByteArrayMaxOverride(50_000_000)
        }
        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        val obj = DocxTableExtractor(imageService = imageService, docKey = "shape.docx")
            .extract(ByteArrayInputStream(bytes))
            .filterIsInstance<DocumentBlock.EmbeddedObjectRef>().single()
        assertEquals(DocumentBlock.EmbeddedObjectRef.Kind.SHAPE, obj.kind)
        assertEquals("Direct Access Storage 1", obj.name)
    }

    // ── Custom heading styles (positive coverage after Phase 3) ───────────────

    @Test
    fun `Title style is recognised as Heading level 1`() {
        val bytes = buildDocx { doc ->
            doc.createParagraph().apply {
                style = "Title"
                createRun().setText("Document Title")
            }
        }
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val heading = blocks.filterIsInstance<DocumentBlock.Heading>().single()
        assertEquals(1, heading.level)
        assertEquals("Document Title", heading.text)
    }

    @Test
    fun `Subtitle style is recognised as Heading level 2`() {
        val bytes = buildDocx { doc ->
            doc.createParagraph().apply {
                style = "Subtitle"
                createRun().setText("With a subtitle line")
            }
        }
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val heading = blocks.filterIsInstance<DocumentBlock.Heading>().single()
        assertEquals(2, heading.level)
        assertEquals("With a subtitle line", heading.text)
    }

    @Test
    fun `Quote and IntenseQuote styles are recognised as Heading level 3`() {
        val bytes = buildDocx { doc ->
            doc.createParagraph().apply {
                style = "Quote"
                createRun().setText("a quoted passage")
            }
            doc.createParagraph().apply {
                style = "IntenseQuote"
                createRun().setText("an intense quote")
            }
        }
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val headings = blocks.filterIsInstance<DocumentBlock.Heading>()
        assertEquals(2, headings.size, "Expected both Quote-style paragraphs as headings")
        assertTrue(headings.all { it.level == 3 })
        assertEquals(listOf("a quoted passage", "an intense quote"), headings.map { it.text })
    }

    @Test
    fun `unknown style still falls through to Paragraph`() {
        val bytes = buildDocx { doc ->
            doc.createParagraph().apply {
                style = "MyCustomStyle"
                createRun().setText("not a heading")
            }
        }
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        assertTrue(blocks.none { it is DocumentBlock.Heading })
        assertTrue(blocks.any { it is DocumentBlock.Paragraph && (it).text == "not a heading" })
    }

    // ── Lists (positive coverage after Phase 3) ──────────────────────────────

    /**
     * Creates a numbering definition using POI's XWPFNumbering API.
     *
     * Chain: CTAbstractNum.Factory.newInstance() → set abstractNumId + addLvl with numFmt →
     * wrap in XWPFAbstractNum → numbering.addAbstractNum → numbering.addNum(abstractNumId).
     *
     * [CTAbstractNum.Factory] is the xmlbeans-generated static factory; it creates a
     * properly typed instance without requiring access to the package-private
     * `ctNumbering` field of [org.apache.poi.xwpf.usermodel.XWPFNumbering].
     */
    private fun buildDocxWithList(
        items: List<Pair<String, Int>>,   // (text, ilvl)
        numFmtEnum: STNumberFormat.Enum,
        extraParagraphsBefore: List<String> = emptyList(),
        extraParagraphsAfter: List<String> = emptyList(),
    ): ByteArray {
        return buildDocx { doc ->
            for (text in extraParagraphsBefore) {
                doc.createParagraph().createRun().setText(text)
            }

            val numbering = doc.createNumbering()
            val ctAbstractNum: CTAbstractNum = CTAbstractNum.Factory.newInstance()
            ctAbstractNum.abstractNumId = BigInteger.ZERO
            ctAbstractNum.addNewLvl().addNewNumFmt().`val` = numFmtEnum

            val abstractNumId = numbering.addAbstractNum(XWPFAbstractNum(ctAbstractNum))
            val numId = numbering.addNum(abstractNumId)

            for ((text, ilvl) in items) {
                val p = doc.createParagraph()
                p.numID = numId
                p.setNumILvl(BigInteger.valueOf(ilvl.toLong()))
                p.createRun().setText(text)
            }

            for (text in extraParagraphsAfter) {
                doc.createParagraph().createRun().setText(text)
            }
        }
    }

    @Test
    fun `consecutive bulleted list items emit one ListBlock with ordered false`() {
        val bytes = buildDocxWithList(
            items = listOf("Apple" to 0, "Banana" to 0, "Cherry" to 0),
            numFmtEnum = STNumberFormat.BULLET,
        )

        val blocks = DocxTableExtractor().extract(ByteArrayInputStream(bytes))
        val listBlocks = blocks.filterIsInstance<DocumentBlock.ListBlock>()

        assertEquals(1, listBlocks.size, "Expected exactly one ListBlock from three consecutive list items")
        val lb = listBlocks.single()
        assertFalse(lb.ordered, "Bullet numFmt should produce ordered=false")
        assertEquals(listOf("Apple", "Banana", "Cherry"), lb.items)
        // No spurious Paragraphs for the list items.
        val paragraphs = blocks.filterIsInstance<DocumentBlock.Paragraph>()
        assertTrue(paragraphs.none { it.text in listOf("Apple", "Banana", "Cherry") },
            "List-item text must NOT also appear as Paragraph blocks (double-emission)")
    }

    @Test
    fun `consecutive ordered list items emit one ListBlock with ordered true`() {
        val bytes = buildDocxWithList(
            items = listOf("Step 1" to 0, "Step 2" to 0, "Step 3" to 0),
            numFmtEnum = STNumberFormat.DECIMAL,
        )

        val blocks = DocxTableExtractor().extract(ByteArrayInputStream(bytes))
        val listBlocks = blocks.filterIsInstance<DocumentBlock.ListBlock>()

        assertEquals(1, listBlocks.size, "Expected exactly one ListBlock")
        assertTrue(listBlocks.single().ordered, "Decimal numFmt should produce ordered=true")
        assertEquals(listOf("Step 1", "Step 2", "Step 3"), listBlocks.single().items)
    }

    @Test
    fun `list items interspersed with normal paragraphs emit ListBlock then Paragraph then ListBlock`() {
        val bytes = buildDocx { doc ->
            val numbering = doc.createNumbering()
            val ctAbstract: CTAbstractNum = CTAbstractNum.Factory.newInstance()
            ctAbstract.abstractNumId = BigInteger.ZERO
            ctAbstract.addNewLvl().addNewNumFmt().`val` = STNumberFormat.BULLET
            val abstractNumId = numbering.addAbstractNum(XWPFAbstractNum(ctAbstract))
            val numId = numbering.addNum(abstractNumId)

            // First list
            for (text in listOf("A", "B")) {
                val p = doc.createParagraph()
                p.numID = numId
                p.setNumILvl(BigInteger.ZERO)
                p.createRun().setText(text)
            }
            // Interlude paragraph
            doc.createParagraph().createRun().setText("Middle prose.")
            // Second list
            for (text in listOf("X", "Y")) {
                val p = doc.createParagraph()
                p.numID = numId
                p.setNumILvl(BigInteger.ZERO)
                p.createRun().setText(text)
            }
        }

        val blocks = DocxTableExtractor().extract(ByteArrayInputStream(bytes))
        val listBlocks = blocks.filterIsInstance<DocumentBlock.ListBlock>()
        assertEquals(2, listBlocks.size, "Expected two ListBlocks split by the prose paragraph")
        assertEquals(listOf("A", "B"), listBlocks[0].items)
        assertEquals(listOf("X", "Y"), listBlocks[1].items)

        val middleIdx = blocks.indexOfFirst { it is DocumentBlock.Paragraph && (it as DocumentBlock.Paragraph).text.contains("Middle prose") }
        assertTrue(middleIdx >= 0, "Middle prose paragraph should be present")
        val firstListIdx = blocks.indexOf(listBlocks[0])
        val secondListIdx = blocks.indexOf(listBlocks[1])
        assertTrue(firstListIdx < middleIdx, "First list must precede the middle paragraph")
        assertTrue(middleIdx < secondListIdx, "Middle paragraph must precede the second list")
    }

    @Test
    fun `list items at end of document are flushed via PostBodyVisitor`() {
        // The list is the LAST content in the document — no trailing non-list paragraph to
        // trigger flush during the body walk; the PostBodyVisitor must flush it.
        val bytes = buildDocxWithList(
            items = listOf("Last1" to 0, "Last2" to 0),
            numFmtEnum = STNumberFormat.BULLET,
            extraParagraphsBefore = listOf("Intro."),
        )

        val blocks = DocxTableExtractor().extract(ByteArrayInputStream(bytes))
        val listBlock = blocks.filterIsInstance<DocumentBlock.ListBlock>().singleOrNull()
        assertNotNull(listBlock, "Expected one ListBlock from the trailing list items")
        assertEquals(listOf("Last1", "Last2"), listBlock!!.items)
    }

    @Test
    fun `nested list items encode nesting as leading two-space indent per level`() {
        val bytes = buildDocxWithList(
            items = listOf("Parent" to 0, "Child" to 1, "Grandchild" to 2),
            numFmtEnum = STNumberFormat.BULLET,
        )

        val blocks = DocxTableExtractor().extract(ByteArrayInputStream(bytes))
        val listBlock = blocks.filterIsInstance<DocumentBlock.ListBlock>().singleOrNull()
        assertNotNull(listBlock, "Expected one ListBlock with nested items")
        assertEquals(listOf("Parent", "  Child", "    Grandchild"), listBlock!!.items,
            "Level 0 → no indent, level 1 → '  ', level 2 → '    '")
    }

    @Test
    fun `prose before list then list at end emits Paragraph then ListBlock in order`() {
        val bytes = buildDocxWithList(
            items = listOf("Item 1" to 0, "Item 2" to 0),
            numFmtEnum = STNumberFormat.BULLET,
            extraParagraphsBefore = listOf("Introduction text."),
        )

        val blocks = DocxTableExtractor().extract(ByteArrayInputStream(bytes))
        val introIdx = blocks.indexOfFirst { it is DocumentBlock.Paragraph }
        val listIdx = blocks.indexOfFirst { it is DocumentBlock.ListBlock }
        assertTrue(introIdx >= 0 && listIdx > introIdx,
            "Paragraph must appear before the ListBlock in extracted order")
    }

    @Test
    fun `comment anchored to a list-item paragraph emits AFTER the ListBlock, not mid-stream`() {
        // Build a DOCX with three list items where the SECOND item has a w:commentRangeEnd
        // anchored to it. Without the deferred-comment side channel, the chain would emit
        // [Comment, ListBlock] (because CommentExtractionVisitor runs before the accumulator
        // flushes on the next non-list paragraph or post-body). The fix routes the Comment
        // through the accumulator so it emits AFTER the ListBlock instead.
        val bytes = buildDocx { doc ->
            val numbering = doc.createNumbering()
            val ctAbstract: CTAbstractNum = CTAbstractNum.Factory.newInstance()
            ctAbstract.abstractNumId = BigInteger.ZERO
            ctAbstract.addNewLvl().addNewNumFmt().`val` = STNumberFormat.BULLET
            val abstractNumId = numbering.addAbstractNum(XWPFAbstractNum(ctAbstract))
            val numId = numbering.addNum(abstractNumId)

            // Item 1
            val p1 = doc.createParagraph().apply { numID = numId }
            p1.createRun().setText("item-one")

            // Item 2 — anchor a comment here
            val p2 = doc.createParagraph().apply { numID = numId }
            p2.createRun().setText("item-two")
            val comments = doc.createComments()
            val c = comments.createComment(BigInteger.valueOf(1))
            c.author = "Reviewer"
            c.createParagraph().createRun().setText("inline comment on item-two")
            val ctp = p2.ctp
            ctp.addNewCommentRangeStart().id = BigInteger.valueOf(1)
            ctp.addNewCommentRangeEnd().id = BigInteger.valueOf(1)
            ctp.addNewR().addNewCommentReference().id = BigInteger.valueOf(1)

            // Item 3
            val p3 = doc.createParagraph().apply { numID = numId }
            p3.createRun().setText("item-three")
        }

        val blocks = DocxTableExtractor().extract(ByteArrayInputStream(bytes))
        val classes = blocks.map { it::class.simpleName!! }

        val listIdx = blocks.indexOfFirst { it is DocumentBlock.ListBlock }
        val commentIdx = blocks.indexOfFirst { it is DocumentBlock.Comment }
        assertTrue(listIdx >= 0, "Expected a ListBlock in the output; got: $classes")
        assertTrue(commentIdx > listIdx,
            "Comment must emit AFTER the ListBlock, not between items. Got: $classes")

        val list = blocks[listIdx] as DocumentBlock.ListBlock
        assertEquals(listOf("item-one", "item-two", "item-three"), list.items,
            "All three items should be in the ListBlock, regardless of comment anchoring")

        val comment = blocks[commentIdx] as DocumentBlock.Comment
        assertEquals("Reviewer", comment.author)
        assertEquals("inline comment on item-two", comment.text)
        assertEquals(DocumentBlock.Comment.Kind.REVIEW, comment.kind)
    }

    // ── Comments (positive coverage after Phase 1) ─────────────────────────────

    @Test
    fun `comment anchored to a paragraph emits DocumentBlock Comment immediately after that paragraph`() {
        val bytes = buildDocx { doc ->
            val p = doc.createParagraph()
            p.createRun().setText("The new caching layer reduces P99 latency to 80ms.")

            // Create the comment in comments.xml
            val comments = doc.createComments()
            val xwpfComment = comments.createComment(BigInteger.valueOf(1))
            xwpfComment.author = "Jane"
            xwpfComment.initials = "J"
            // setText sets the first paragraph of the comment's body
            val commentPara = xwpfComment.createParagraph()
            commentPara.createRun().setText("Confirm with the latest benchmark.")

            // Insert w:commentRangeStart / End / Reference into the paragraph's CTP
            val ctp = p.ctp
            ctp.addNewCommentRangeStart().id = BigInteger.valueOf(1)
            ctp.addNewCommentRangeEnd().id = BigInteger.valueOf(1)
            ctp.addNewR().addNewCommentReference().id = BigInteger.valueOf(1)
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))

        // Find the paragraph block and the comment block.
        val paragraphIdx = blocks.indexOfFirst {
            it is DocumentBlock.Paragraph && it.text.contains("caching layer")
        }
        assertTrue(paragraphIdx >= 0, "Expected the body paragraph in extracted blocks")

        // The comment should appear immediately after the paragraph (no gap).
        val nextBlock = blocks.getOrNull(paragraphIdx + 1)
        assertTrue(
            nextBlock is DocumentBlock.Comment,
            "Expected the block IMMEDIATELY after the paragraph to be a Comment; " +
                "got ${nextBlock?.let { it::class.simpleName } ?: "nothing"} at index ${paragraphIdx + 1}. " +
                "All blocks: ${blocks.map { it::class.simpleName }}"
        )
        val comment = nextBlock as DocumentBlock.Comment
        assertEquals(DocumentBlock.Comment.Kind.REVIEW, comment.kind)
        assertEquals("Jane", comment.author)
        assertEquals("Confirm with the latest benchmark.", comment.text)
        assertTrue(
            comment.anchorText?.contains("caching layer") == true,
            "Anchor text should include the anchored phrase; got: ${comment.anchorText}"
        )
    }

    @Test
    fun `multiple comments ending in the same paragraph emit in ascending w-id order`() {
        val bytes = buildDocx { doc ->
            val p = doc.createParagraph()
            p.createRun().setText("Anchored text.")

            val comments = doc.createComments()
            // Create in REVERSE id order to verify the visitor sorts ascending.
            for (id in listOf(3, 1, 2)) {
                val c = comments.createComment(BigInteger.valueOf(id.toLong()))
                c.author = "author-$id"
                c.createParagraph().createRun().setText("body-$id")
            }

            val ctp = p.ctp
            // Range markers in arbitrary order — visitor sorts by id.
            for (id in listOf(3, 1, 2)) {
                ctp.addNewCommentRangeStart().id = BigInteger.valueOf(id.toLong())
                ctp.addNewCommentRangeEnd().id = BigInteger.valueOf(id.toLong())
                ctp.addNewR().addNewCommentReference().id = BigInteger.valueOf(id.toLong())
            }
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val commentBlocks = blocks.filterIsInstance<DocumentBlock.Comment>()
        assertEquals(3, commentBlocks.size, "Expected 3 Comment blocks")
        assertEquals(listOf("author-1", "author-2", "author-3"), commentBlocks.map { it.author },
            "Comments should be emitted in ascending w:id order")
        assertEquals(listOf("body-1", "body-2", "body-3"), commentBlocks.map { it.text })
    }

    @Test
    fun `commentRangeEnd marker with no matching comment in comments-xml is silently skipped`() {
        val bytes = buildDocx { doc ->
            val p = doc.createParagraph()
            p.createRun().setText("Anchored text.")
            // Insert a commentRangeEnd with id=99 but DO NOT create comment 99 in comments.xml.
            // Mix with a valid id=1 so we know the visitor doesn't fail-fast on the orphan.
            doc.createComments().createComment(BigInteger.valueOf(1)).apply {
                author = "Jane"
                createParagraph().createRun().setText("real comment")
            }
            val ctp = p.ctp
            ctp.addNewCommentRangeEnd().id = BigInteger.valueOf(99)
            ctp.addNewCommentRangeEnd().id = BigInteger.valueOf(1)
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val commentBlocks = blocks.filterIsInstance<DocumentBlock.Comment>()
        // Only id=1 has a body; id=99 is orphaned and skipped.
        assertEquals(1, commentBlocks.size,
            "Expected the orphaned marker (id=99) to be skipped, only the real id=1 comment emitted")
        assertEquals("Jane", commentBlocks.single().author)
        assertEquals("real comment", commentBlocks.single().text)
    }

    // ── Tracked changes (positive coverage after Phase 1) ──────────────────────

    @Test
    fun `tracked insertion emits DocumentBlock Comment with TRACKED_INSERTION kind`() {
        val bytes = buildDocx { doc ->
            val p = doc.createParagraph()
            p.createRun().setText("Before insertion. ")

            val ctp = p.ctp
            val ins = ctp.addNewIns()
            ins.author = "Tom"
            ins.id = java.math.BigInteger.valueOf(1)
            val insRun = ins.addNewR()
            insRun.addNewT().stringValue = "ADDED"
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val tracked = blocks.filterIsInstance<DocumentBlock.Comment>()
            .filter { it.kind == DocumentBlock.Comment.Kind.TRACKED_INSERTION }

        assertEquals(1, tracked.size, "Expected one TRACKED_INSERTION Comment")
        assertEquals("Tom", tracked.single().author)
        assertEquals("ADDED", tracked.single().text)
    }

    @Test
    fun `tracked deletion emits DocumentBlock Comment with TRACKED_DELETION kind and deleted text in anchor`() {
        val bytes = buildDocx { doc ->
            val p = doc.createParagraph()
            p.createRun().setText("Before deletion. ")

            val ctp = p.ctp
            val del = ctp.addNewDel()
            del.author = "Tom"
            del.id = java.math.BigInteger.valueOf(2)
            val delRun = del.addNewR()
            delRun.addNewDelText().stringValue = "REMOVED"
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val tracked = blocks.filterIsInstance<DocumentBlock.Comment>()
            .filter { it.kind == DocumentBlock.Comment.Kind.TRACKED_DELETION }

        assertEquals(1, tracked.size, "Expected one TRACKED_DELETION Comment")
        assertEquals("Tom", tracked.single().author)
        assertEquals("REMOVED", tracked.single().anchorText,
            "Deleted text should be in anchorText so the assembler can render it in the header")
        assertTrue(tracked.single().text.isBlank(),
            "TRACKED_DELETION body should be blank — the deleted text is in the header")
    }

    @Test
    fun `mixed ins and del in one paragraph emit in document order`() {
        val bytes = buildDocx { doc ->
            val p = doc.createParagraph()
            p.createRun().setText("Start. ")

            val ctp = p.ctp
            ctp.addNewDel().apply {
                author = "Tom"
                id = java.math.BigInteger.valueOf(1)
                addNewR().addNewDelText().stringValue = "first-removed"
            }
            ctp.addNewIns().apply {
                author = "Tom"
                id = java.math.BigInteger.valueOf(2)
                addNewR().addNewT().stringValue = "second-added"
            }
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val tracked = blocks.filterIsInstance<DocumentBlock.Comment>()
            .filter { it.kind == DocumentBlock.Comment.Kind.TRACKED_INSERTION ||
                      it.kind == DocumentBlock.Comment.Kind.TRACKED_DELETION }

        assertEquals(2, tracked.size)
        assertEquals(DocumentBlock.Comment.Kind.TRACKED_DELETION, tracked[0].kind)
        assertEquals("first-removed", tracked[0].anchorText)
        assertEquals(DocumentBlock.Comment.Kind.TRACKED_INSERTION, tracked[1].kind)
        assertEquals("second-added", tracked[1].text)
    }

    @Test
    fun `empty w-ins with no inserted text emits no TRACKED_INSERTION Comment`() {
        val bytes = buildDocx { doc ->
            val p = doc.createParagraph()
            p.createRun().setText("Body prose.")
            val ctp = p.ctp
            // <w:ins> with author but NO child runs — the LLM should see nothing about this.
            ctp.addNewIns().apply {
                author = "Tom"
                id = java.math.BigInteger.valueOf(1)
                // intentionally no addNewR()
            }
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val tracked = blocks.filterIsInstance<DocumentBlock.Comment>()
            .filter { it.kind == DocumentBlock.Comment.Kind.TRACKED_INSERTION }

        assertEquals(0, tracked.size,
            "Empty w:ins (no text inside) should not emit a Comment block")
        // The paragraph itself still extracts.
        assertTrue(blocks.any { it is DocumentBlock.Paragraph && it.text.contains("Body prose") })
    }

    // ── Body-only iteration boundary ──────────────────────────────────────────

    /**
     * Source-scan gate: confirms that [DocxTableExtractor] itself does NOT touch
     * footnotes or endnotes directly — that work is delegated to
     * [com.workflow.orchestrator.document.poi.visitor.FootnoteExtractionVisitor].
     * Comments, images, and header/footer policy are legitimately read via Phase 1/2/3
     * visitors and [DocxTableExtractor.extractHeaderFooter], so those needles are
     * intentionally absent from the forbidden list.
     */
    @Test
    fun `gap extractor delegates footnotes to FootnoteExtractionVisitor — comments images and headers extracted via visitors in Phase 1 2 and 3`() {
        val source = javaClass.classLoader
            .getResource("../../main/kotlin/com/workflow/orchestrator/document/poi/DocxTableExtractor.kt")
            ?.readText()
        // Fallback: read directly from filesystem if classpath resource is unavailable.
        val text = source ?: java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "src/main/kotlin/com/workflow/orchestrator/document/poi/DocxTableExtractor.kt"
            )
        )

        assertNotNull(text, "Could not locate DocxTableExtractor.kt source for inspection")
        // DocxTableExtractor must NOT call footnote APIs directly — FootnoteExtractionVisitor owns that.
        listOf(
            "doc.footnotes",
            "doc.getFootnotes(",
            "doc.endnotes",
            "doc.getEndnotes(",
        ).forEach { needle ->
            assertFalse(text!!.contains(needle),
                "DocxTableExtractor must NOT touch '$needle' directly; delegate to FootnoteExtractionVisitor")
        }
    }

    // ── Footnotes (positive coverage after Phase 5a) ──────────────────────────

    @Test
    fun `DOCX footnotes are extracted as Footnote blocks at the end of the output`() {
        val bytes = buildDocx { doc ->
            doc.createParagraph().createRun().setText("Body text.")

            val footnotesContainer = doc.createFootnotes()
            val fn = footnotesContainer.createFootnote()
            fn.ctFtnEdn.id = java.math.BigInteger.valueOf(1)
            fn.createParagraph().createRun().setText("First footnote text.")
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val footnoteBlocks = blocks.filterIsInstance<DocumentBlock.Footnote>()

        assertEquals(1, footnoteBlocks.size, "Expected one Footnote block")
        assertEquals("1", footnoteBlocks.single().marker)
        assertEquals("First footnote text.", footnoteBlocks.single().text)

        // Footnote should appear AFTER body content.
        val bodyIdx = blocks.indexOfFirst { it is DocumentBlock.Paragraph && (it as DocumentBlock.Paragraph).text.contains("Body text") }
        val footnoteIdx = blocks.indexOfFirst { it is DocumentBlock.Footnote }
        assertTrue(footnoteIdx > bodyIdx, "Footnote should land after body; got body@$bodyIdx footnote@$footnoteIdx")
    }

    @Test
    fun `DOCX with no footnotes emits no Footnote blocks`() {
        val bytes = buildDocx { doc ->
            doc.createParagraph().createRun().setText("plain body")
        }
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        assertTrue(blocks.none { it is DocumentBlock.Footnote })
    }

    @Test
    fun `multiple DOCX footnotes emit all markers as a set`() {
        val bytes = buildDocx { doc ->
            doc.createParagraph().createRun().setText("body")
            val footnotesContainer = doc.createFootnotes()
            for (id in listOf(2, 1, 3)) {
                val fn = footnotesContainer.createFootnote()
                fn.ctFtnEdn.id = java.math.BigInteger.valueOf(id.toLong())
                fn.createParagraph().createRun().setText("note-$id")
            }
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val footnotes = blocks.filterIsInstance<DocumentBlock.Footnote>()
        assertEquals(3, footnotes.size)
        // POI iterates the list in document-insertion order. We accept either insertion-order or id-order
        // — what matters is all three are present. Tighten if a sort guarantee is needed in the impl.
        assertTrue(setOf("1", "2", "3") == footnotes.map { it.marker }.toSet())
    }

    // ── Header / Footer (positive coverage after Phase 3 T4) ──────────────────

    @Test
    fun `default header text emits as a leading Paragraph prefixed with Header colon`() {
        val bytes = buildDocxWithHeaderFooter(headerText = "Confidential — Q4 Spec", footerText = null)
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val firstParagraph = blocks.firstOrNull() as? DocumentBlock.Paragraph
        assertNotNull(firstParagraph, "Header should be the first block")
        assertEquals("> Header: Confidential — Q4 Spec", firstParagraph!!.text)
    }

    @Test
    fun `default footer text emits as a leading Paragraph prefixed with Footer colon`() {
        val bytes = buildDocxWithHeaderFooter(headerText = null, footerText = "page X of Y")
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val first = blocks.firstOrNull() as? DocumentBlock.Paragraph
        assertNotNull(first, "Footer should be the first block when no header is present")
        assertEquals("> Footer: page X of Y", first!!.text)
    }

    @Test
    fun `both header and footer emit header first then footer both before body content`() {
        val bytes = buildDocxWithHeaderFooter(
            headerText = "Doc title",
            footerText = "footer text",
            bodyText = "body paragraph",
        )
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val paragraphs = blocks.filterIsInstance<DocumentBlock.Paragraph>()
        assertTrue(paragraphs.size >= 3, "Expected header + footer + body paragraph at minimum")
        assertEquals("> Header: Doc title", paragraphs[0].text)
        assertEquals("> Footer: footer text", paragraphs[1].text)
        assertTrue(paragraphs.any { it.text == "body paragraph" })
    }

    @Test
    fun `empty header policy emits nothing — body iteration proceeds normally`() {
        val bytes = buildDocx { doc ->
            doc.createParagraph().createRun().setText("just a body paragraph")
        }
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        assertTrue(blocks.none {
            it is DocumentBlock.Paragraph && (it.text.startsWith("> Header:") || it.text.startsWith("> Footer:"))
        }, "No header/footer policy → no header/footer Paragraph")
    }

    // ── Vertical merge in DOCX tables (positive coverage after Phase 3) ───────

    @Test
    fun `vertical-merge continuation rows inherit value from the restart row`() {
        val bytes = buildDocx { doc ->
            val table = doc.createTable(4, 2)
            // Row 0: headers
            table.getRow(0).getCell(0).text = "Section"
            table.getRow(0).getCell(1).text = "Item"
            // Row 1: data row with vMerge=restart in column 0
            table.getRow(1).getCell(0).text = "Risks"
            setVMerge(table.getRow(1).getCell(0), org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.RESTART)
            table.getRow(1).getCell(1).text = "R-001"
            // Row 2: vMerge=continue in column 0 (cell is "empty" but should inherit "Risks")
            setVMerge(table.getRow(2).getCell(0), org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.CONTINUE)
            table.getRow(2).getCell(1).text = "R-002"
            // Row 3: still continuing
            setVMerge(table.getRow(3).getCell(0), org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.CONTINUE)
            table.getRow(3).getCell(1).text = "R-003"
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val table = blocks.filterIsInstance<DocumentBlock.Table>().single()
        assertEquals(listOf("Section", "Item"), table.headers)
        assertEquals(3, table.rows.size)
        assertEquals(listOf("Risks", "R-001"), table.rows[0])
        assertEquals(listOf("Risks", "R-002"), table.rows[1])
        assertEquals(listOf("Risks", "R-003"), table.rows[2])
    }

    @Test
    fun `cells with no vMerge use their own text — merge does not stick across non-merged cells`() {
        val bytes = buildDocx { doc ->
            val table = doc.createTable(4, 2)
            table.getRow(0).getCell(0).text = "Col1"
            table.getRow(0).getCell(1).text = "Col2"
            // Row 1: restart in col 0
            table.getRow(1).getCell(0).text = "Group-A"
            setVMerge(table.getRow(1).getCell(0), org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.RESTART)
            table.getRow(1).getCell(1).text = "a1"
            // Row 2: continue
            setVMerge(table.getRow(2).getCell(0), org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.CONTINUE)
            table.getRow(2).getCell(1).text = "a2"
            // Row 3: no vMerge — own text wins
            table.getRow(3).getCell(0).text = "Group-B"
            table.getRow(3).getCell(1).text = "b1"
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val table = blocks.filterIsInstance<DocumentBlock.Table>().single()
        assertEquals(listOf("Group-A", "a1"), table.rows[0])
        assertEquals(listOf("Group-A", "a2"), table.rows[1])
        assertEquals(listOf("Group-B", "b1"), table.rows[2])
    }

    // ── OMML math (P5a-5) ─────────────────────────────────────────────────────

    /**
     * P5a-5: inline `<m:oMath>` in a paragraph surfaces as plain text appended to the
     * paragraph text.
     *
     * Fixture construction: we use [CTP.addNewOMath] (generated by XmlBeans) and then
     * [CTOMath.addNewR] + [CTR.addNewT] to avoid raw XML injection and the cursor
     * `beginElement` / `insertChars` dance — the typed API is simpler and less fragile.
     *
     * The extraction path in [com.workflow.orchestrator.document.poi.visitor.DefaultHeadingParagraphVisitor]
     * serialises each CTOMath to XML via `xmlText()` and scans for `<m:t>` elements, so
     * the text must appear under `<m:r><m:t>` to be found.
     */
    @Test
    fun `DOCX inline OMath text is appended to the paragraph as plain-text fallback`() {
        val bytes = buildDocx { doc ->
            val p = doc.createParagraph()
            p.createRun().setText("The equation is ")
            // Insert <m:oMath><m:r><m:t>x + 2 = y</m:t></m:r></m:oMath> via typed XmlBeans API.
            val ctp = p.ctp
            val oMath = ctp.addNewOMath()
            val mRun = oMath.addNewR()
            val tNode = mRun.addNewT()
            tNode.stringValue = "x + 2 = y"
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val text = blocks.filterIsInstance<DocumentBlock.Paragraph>().joinToString(" ") { it.text }
        assertTrue(text.contains("x + 2 = y"),
            "OMath plain-text fallback should appear in the paragraph; got: $text")
        assertTrue(text.contains("The equation is"),
            "Surrounding prose should be preserved; got: $text")
    }

    /**
     * P5a-5: `<m:oMathPara>` wrapper containing `<m:oMath>` is also extracted correctly.
     *
     * `<m:oMathPara>` is a paragraph-level wrapper that groups one or more `<m:oMath>` blocks
     * for centered display. The same plain-text extraction path handles it via
     * [CTP.getOMathParaArray].
     */
    @Test
    fun `DOCX oMathPara wrapper is also extracted as plain-text fallback`() {
        val bytes = buildDocx { doc ->
            val p = doc.createParagraph()
            p.createRun().setText("See: ")
            val ctp = p.ctp
            val oMathPara = ctp.addNewOMathPara()
            val oMath = oMathPara.addNewOMath()
            val mRun = oMath.addNewR()
            val tNode = mRun.addNewT()
            tNode.stringValue = "a^2 + b^2 = c^2"
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val text = blocks.filterIsInstance<DocumentBlock.Paragraph>().joinToString(" ") { it.text }
        assertTrue(text.contains("a^2 + b^2 = c^2"),
            "oMathPara plain-text should surface; got: $text")
        assertTrue(text.contains("See:"), "Surrounding prose preserved; got: $text")
    }

    /**
     * P5a-5: a paragraph with ONLY math content (no prose runs) still surfaces the math text
     * as a [DocumentBlock.Paragraph].
     */
    @Test
    fun `DOCX paragraph with only OMath and no prose runs emits a Paragraph block`() {
        val bytes = buildDocx { doc ->
            val p = doc.createParagraph()
            // No prose run — only math.
            val ctp = p.ctp
            val oMath = ctp.addNewOMath()
            val mRun = oMath.addNewR()
            mRun.addNewT().stringValue = "E = mc^2"
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val text = blocks.filterIsInstance<DocumentBlock.Paragraph>().joinToString(" ") { it.text }
        assertTrue(text.contains("E = mc^2"),
            "Math-only paragraph should still emit a Paragraph; got: $text")
    }

    /**
     * P5a-4: a DOCX containing a SmartArt diagramData part emits a [DocumentBlock.ListBlock]
     * with the SmartArt text items.
     *
     * We construct the DOCX bytes manually as a ZIP, appending a minimal
     * `diagramData+xml` part with `<a:t>` text nodes. POI's high-level API has no
     * SmartArt insertion method; raw ZIP construction mirrors the approach used for
     * PPTX slide comments in [PptxExtractorFormatGapsTest].
     */
    @Test
    fun `DOCX SmartArt diagramData part is extracted as a flat ListBlock`() {
        val bytes = buildDocxWithSmartArt(listOf("Step 1", "Step 2", "Step 3"))

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val listBlocks = blocks.filterIsInstance<DocumentBlock.ListBlock>()
        assertTrue(listBlocks.isNotEmpty(), "Expected at least one ListBlock from SmartArt; got: ${blocks.map { it::class.simpleName }}")
        val items = listBlocks.flatMap { it.items }
        assertTrue(items.containsAll(listOf("Step 1", "Step 2", "Step 3")),
            "All SmartArt text items should appear in the ListBlock; got items: $items")
    }

    @Test
    fun `DOCX with no SmartArt emits no extra ListBlocks beyond any regular lists`() {
        val bytes = buildDocx { doc ->
            doc.createParagraph().createRun().setText("No SmartArt here.")
        }
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        // Only regular content — no SmartArt ListBlocks.
        val listBlocks = blocks.filterIsInstance<DocumentBlock.ListBlock>()
        assertTrue(listBlocks.isEmpty(),
            "No SmartArt → no extra ListBlocks; got: $listBlocks")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun setVMerge(cell: org.apache.poi.xwpf.usermodel.XWPFTableCell,
                          merge: org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge.Enum) {
        val tcPr = cell.ctTc.tcPr ?: cell.ctTc.addNewTcPr()
        val vMerge = tcPr.vMerge ?: tcPr.addNewVMerge()
        vMerge.`val` = merge
    }

    private fun buildDocx(build: (XWPFDocument) -> Unit): ByteArray {
        val doc = XWPFDocument()
        try {
            build(doc)
            val out = ByteArrayOutputStream()
            doc.write(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }

    /**
     * Builds an in-memory DOCX with optional header and footer text using POI's
     * [org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy]. Either or both can be null.
     * [bodyText] is appended as a single body paragraph (default: "Body.").
     *
     * POI 5.4.1 note: `XWPFDocument.createHeaderFooterPolicy()` constructs a new
     * [org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy] tied to the document's
     * `CTSectPr`. We call `createHeader`/`createFooter` with
     * [org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy.DEFAULT] (`STHdrFtr.DEFAULT`)
     * which is a `STHdrFtr$Enum` constant — NOT a String or Int.
     */
    private fun buildDocxWithHeaderFooter(
        headerText: String?,
        footerText: String?,
        bodyText: String = "Body.",
    ): ByteArray {
        return buildDocx { doc ->
            doc.createParagraph().createRun().setText(bodyText)

            val policy = doc.createHeaderFooterPolicy()
            if (headerText != null) {
                val hf = policy.createHeader(org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy.DEFAULT)
                hf.createParagraph().createRun().setText(headerText)
            }
            if (footerText != null) {
                val ff = policy.createFooter(org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy.DEFAULT)
                ff.createParagraph().createRun().setText(footerText)
            }
        }
    }

    /**
     * Small opaque PNG, generated via ImageIO so POI can read its IHDR dimensions.
     * Dimensions are 32×32 — the minimum that passes the fragment filter in
     * [ImageExtractionService.saveImage] (images below 32px in either axis are dropped).
     */
    private fun tinyPng(): ByteArray {
        val img = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }

    /**
     * Constructs a minimal DOCX ZIP with a SmartArt diagramData part injected.
     *
     * Steps:
     * 1. Build a base DOCX via POI (one plain paragraph).
     * 2. Post-process the ZIP to inject:
     *    - `word/diagrams/data1.xml` — a minimal `diagramData+xml` part with `<a:t>` text nodes.
     *    - Update `[Content_Types].xml` to register the new part's content type.
     *
     * This mirrors the approach used in [PptxExtractorFormatGapsTest.buildPptxWithComment].
     *
     * The relationship from the document part to the diagram part is NOT added here because
     * [SmartArtExtractor] uses [OPCPackage.getPartsByContentType] (package-wide scan), not
     * per-part relationship traversal, so no relationship entry is required for the test to pass.
     */
    private fun buildDocxWithSmartArt(items: List<String>): ByteArray {
        val baseBytes = buildDocx { doc ->
            doc.createParagraph().createRun().setText("Document with SmartArt.")
        }

        // Build the diagramData XML: one <dgm:pt> per item, each with <a:t> text.
        val dgmNs = "http://schemas.openxmlformats.org/drawingml/2008/diagram"
        val aNs = "http://schemas.openxmlformats.org/drawingml/2006/main"
        val ptElements = items.joinToString("\n") { item ->
            """    <dgm:pt type="node" xmlns:dgm="$dgmNs">
      <dgm:t xmlns:dgm="$dgmNs">
        <a:bodyPr xmlns:a="$aNs"/>
        <a:p xmlns:a="$aNs"><a:r xmlns:a="$aNs"><a:t xmlns:a="$aNs">$item</a:t></a:r></a:p>
      </dgm:t>
    </dgm:pt>"""
        }
        val diagramXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<dgm:dataModel xmlns:dgm="$dgmNs" xmlns:a="$aNs">
  <dgm:ptLst>
$ptElements
  </dgm:ptLst>
</dgm:dataModel>""".trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zout ->
            ZipInputStream(ByteArrayInputStream(baseBytes)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val bytes = zin.readBytes()
                    when {
                        name == "[Content_Types].xml" -> {
                            val updated = String(bytes, Charsets.UTF_8).replace(
                                "</Types>",
                                """  <Override PartName="/word/diagrams/data1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.diagramData+xml"/>
</Types>""",
                            )
                            zout.putNextEntry(ZipEntry(name))
                            zout.write(updated.toByteArray(Charsets.UTF_8))
                        }
                        else -> {
                            zout.putNextEntry(ZipEntry(name))
                            zout.write(bytes)
                        }
                    }
                    zout.closeEntry()
                    entry = zin.nextEntry
                }
            }
            // Inject the diagramData part.
            zout.putNextEntry(ZipEntry("word/diagrams/data1.xml"))
            zout.write(diagramXml.toByteArray(Charsets.UTF_8))
            zout.closeEntry()
        }
        return out.toByteArray()
    }
}
