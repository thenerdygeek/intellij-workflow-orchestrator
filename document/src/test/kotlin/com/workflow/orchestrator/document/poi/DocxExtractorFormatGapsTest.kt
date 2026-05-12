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
    fun `gap hyperlink URL is lost — only the visible link label survives`() {
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

        assertTrue(text.contains("the spec"), "Visible label should survive")
        assertTrue(text.contains("please read"), "Surrounding prose should survive")
        assertFalse(text.contains("workflow.example.com"),
            "Hyperlink target URI is dropped — extractor never reads w:hyperlink relations")
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

    // ── Custom heading styles ─────────────────────────────────────────────────

    @Test
    fun `gap Title and Subtitle styles are not recognised as headings — they become paragraphs`() {
        val bytes = buildDocx { doc ->
            doc.createParagraph().apply {
                style = "Title"
                createRun().setText("This Is The Document Title")
            }
            doc.createParagraph().apply {
                style = "Subtitle"
                createRun().setText("With a subtitle line")
            }
            doc.createParagraph().apply {
                style = "Heading1"
                createRun().setText("First Real Heading")
            }
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val headings = blocks.filterIsInstance<DocumentBlock.Heading>()

        assertEquals(1, headings.size,
            "Only Heading1 should be detected — Title/Subtitle regex match fails")
        assertEquals("First Real Heading", headings.single().text)

        val paragraphs = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        assertTrue("This Is The Document Title" in paragraphs,
            "Title-styled text degrades to a Paragraph")
        assertTrue("With a subtitle line" in paragraphs,
            "Subtitle-styled text degrades to a Paragraph")
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
     * Source-scan gate: confirms that [DocxTableExtractor] still does NOT touch
     * footnotes, endnotes, or header/footer policy directly. Comments and images are now
     * legitimately read via Phase 1/2 visitors ([CommentExtractionVisitor],
     * [com.workflow.orchestrator.document.poi.visitor.ImageExtractionVisitor]), so those
     * needles are intentionally absent from the forbidden list.
     */
    @Test
    fun `gap extractor still skips footnotes endnotes and headers — comments and images extracted via visitors in Phase 1 and 2`() {
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
        listOf(
            ".footnotes",
            "getFootnotes(",
            ".endnotes",
            "getEndnotes(",
            "HeaderFooterPolicy",
        ).forEach { needle ->
            assertFalse(text!!.contains(needle),
                "DocxTableExtractor must NOT touch '$needle' until the gap is fixed; if it does, " +
                    "update this test to assert the new positive behaviour")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    /** Small opaque PNG, generated via ImageIO so POI can read its IHDR dimensions. */
    private fun tinyPng(): ByteArray {
        val img = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }
}
