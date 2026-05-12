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

        // Sanity: ensure the DocumentBlock sealed hierarchy still has no List variant.
        val variants = DocumentBlock::class.sealedSubclasses.map { it.simpleName }
        assertFalse("List" in variants,
            "If a DocumentBlock.List variant is added, update this test and the extractors that flatten")
    }

    // ── Body-only iteration boundary ──────────────────────────────────────────

    @Test
    fun `gap extractor visits only body elements — comments() collection is never read`() {
        // Source-scan: confirm the extractor never calls into doc.getComments()
        // or doc.getFootnotes() / doc.getEndnotes() / doc.getHeaderFooterPolicy().
        // Pinning by code-reading keeps the test independent of fixture quirks.
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
            ".comments",
            "getComments(",
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
