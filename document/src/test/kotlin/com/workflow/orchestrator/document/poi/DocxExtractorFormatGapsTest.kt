package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.util.IOUtils
import org.apache.poi.util.Units
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

    // ── Embedded images ───────────────────────────────────────────────────────

    @Test
    fun `gap embedded inline image produces no DocumentBlock — no EmbeddedFileRef, no placeholder`() {
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

        runImageGapAssertions(bytes)
    }

    private fun runImageGapAssertions(bytes: ByteArray) {
        val blocks = extractor.extract(ByteArrayInputStream(bytes))

        assertTrue(blocks.any { it is DocumentBlock.Paragraph && it.text.contains("Before image") })
        assertTrue(blocks.any { it is DocumentBlock.Paragraph && it.text.contains("After image") })
        assertTrue(blocks.none { it is DocumentBlock.EmbeddedFileRef },
            "Extractor never emits EmbeddedFileRef for inline images — silent drop")
        assertFalse(blocks.any { (it as? DocumentBlock.Paragraph)?.text?.contains("tiny.png") == true },
            "Image filename is not surfaced anywhere")
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

    // ── Lists ────────────────────────────────────────────────────────────────

    @Test
    fun `gap bulleted list items become individual Paragraphs with no marker, no nesting`() {
        // We can't easily wire a numbering definition in a synthetic doc without packing
        // numbering.xml by hand, but a list always renders as one paragraph per item.
        // This pins the "no DocumentBlock.List variant exists" architectural fact.
        val bytes = buildDocx { doc ->
            doc.createParagraph().createRun().setText("First item")
            doc.createParagraph().createRun().setText("Second item")
            doc.createParagraph().createRun().setText("Third item")
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val texts = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }

        assertEquals(listOf("First item", "Second item", "Third item"), texts,
            "Three list items become three flat Paragraphs — no bullet, no number, no nesting")

        // Sanity: ListBlock variant now exists in the model (Phase 0), but DocxTableExtractor
        // doesn't yet emit it. Phase 3 will populate ListBlock from XWPFParagraph.numID;
        // this gap test flips to a positive test then.
        val variants = DocumentBlock::class.sealedSubclasses.map { it.simpleName }
        assertTrue("ListBlock" in variants,
            "ListBlock should exist in the model after Phase 0")
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
        val commentIdx = blocks.subList(paragraphIdx + 1, blocks.size).indexOfFirst {
            it is DocumentBlock.Comment
        }.let { if (it >= 0) paragraphIdx + 1 + it else -1 }

        assertTrue(commentIdx > paragraphIdx,
            "Expected a Comment block after paragraph at $paragraphIdx; got: ${blocks.map { it::class.simpleName }}")
        val comment = blocks[commentIdx] as DocumentBlock.Comment
        assertEquals(DocumentBlock.Comment.Kind.REVIEW, comment.kind)
        assertEquals("Jane", comment.author)
        assertEquals("Confirm with the latest benchmark.", comment.text)
        assertTrue(
            comment.anchorText?.contains("caching layer") == true,
            "Anchor text should include the anchored phrase; got: ${comment.anchorText}"
        )
    }

    // ── Body-only iteration boundary ──────────────────────────────────────────

    /**
     * Source-scan gate: confirms that [DocxTableExtractor] still does NOT touch
     * footnotes, endnotes, header/footer policy, or embedded pictures. Comments are now
     * legitimately read (Phase 1 — [CommentExtractionVisitor]), so `.comments` and
     * `getComments(` are intentionally absent from the forbidden list.
     */
    @Test
    fun `gap extractor still skips footnotes endnotes headers and pictures — comments are now extracted in Phase 1`() {
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
            "embeddedPictures",
            "XWPFPicture",
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
