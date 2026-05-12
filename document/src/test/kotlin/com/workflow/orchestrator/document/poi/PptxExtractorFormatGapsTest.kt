package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.util.IOUtils
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Characterization tests pinning the scope of [PptxExtractor].
 *
 * Slide content is iterated via `slide.shapes` and routed through a `when` block that
 * handles only `XSLFTable` and `XSLFTextShape`. Picture shapes, group shapes containing
 * pictures, and slide-level review comments fall off the end of the `when` silently.
 */
class PptxExtractorFormatGapsTest {

    private val extractor = PptxExtractor()

    // ── Embedded images on a slide ────────────────────────────────────────────

    @Test
    fun `gap picture shape on a slide is dropped — when branch has no handler for XSLFPictureShape`() {
        // PoiHardening caps allocations at 50 MB; XMLSlideShow.addPicture pre-sizes for up to
        // 100 MB. Raise the cap for fixture creation, then restore so the extractor still sees
        // a hardened POI.
        IOUtils.setByteArrayMaxOverride(200_000_000)
        val bytes = try {
            buildPptx { ppt ->
                val slide = ppt.createSlide()

                // Title placeholder via a text box (we don't rely on a master).
                val title = slide.createTextBox().apply {
                    anchor = Rectangle(50, 30, 500, 50)
                    setText("Slide with image")
                }
                check(title.text.startsWith("Slide"))

                val picData = ppt.addPicture(tinyPng(), PictureData.PictureType.PNG)
                slide.createPicture(picData).apply {
                    anchor = Rectangle(100, 100, 200, 200)
                }
            }
        } finally {
            IOUtils.setByteArrayMaxOverride(50_000_000)
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))

        assertTrue(blocks.any { (it as? DocumentBlock.Paragraph)?.text?.contains("Slide with image") == true },
            "Visible text shape survives")
        assertTrue(blocks.none { it is DocumentBlock.EmbeddedFileRef },
            "No EmbeddedFileRef for the picture shape")
        assertFalse(blocks.any {
            (it as? DocumentBlock.Paragraph)?.text?.contains("image", ignoreCase = false) == true &&
                (it as? DocumentBlock.Paragraph)?.text?.contains(".png") == true
        }, "Picture metadata is not surfaced")
    }

    // ── Inline formatting in text shapes ──────────────────────────────────────

    @Test
    fun `gap bold and italic runs in a text shape collapse to plain text`() {
        val bytes = buildPptx { ppt ->
            val slide = ppt.createSlide()
            val box = slide.createTextBox().apply {
                anchor = Rectangle(50, 50, 500, 200)
            }
            // First paragraph: bold + italic + plain runs combined.
            val p = box.addNewTextParagraph()
            p.addNewTextRun().apply { setText("BOLD "); isBold = true }
            p.addNewTextRun().apply { setText("ITALIC "); isItalic = true }
            p.addNewTextRun().setText("plain")
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val text = blocks.filterIsInstance<DocumentBlock.Paragraph>().joinToString("\n") { it.text }

        assertTrue(text.contains("BOLD ITALIC plain"),
            "All run text is concatenated — but style metadata is gone, was: $text")
        assertFalse(text.contains("<b>") || text.contains("**BOLD**"),
            "Extractor never invents Markdown/HTML markup for bold/italic")
    }

    // ── Slide-level review comments ───────────────────────────────────────────

    @Test
    fun `gap PptxExtractor source never reads slide comments — it iterates only shapes and notes`() {
        // POI's comment-authoring API for PPTX is awkward (requires raw OOXML manipulation),
        // so we pin this via source inspection: the extractor must NOT mention
        // slide.getComments / XSLFComments. If the extractor is updated to surface review
        // comments, this assertion will fail and the author must add a positive coverage test.
        val src = Files.readString(
            Path.of("src/main/kotlin/com/workflow/orchestrator/document/poi/PptxExtractor.kt")
        )

        listOf("getComments(", "XSLFComment", "XSLFComments", ".comments").forEach { needle ->
            assertFalse(src.contains(needle),
                "PptxExtractor still skips '$needle'; remove from this list when comments are extracted")
        }
    }

    // ── Group shapes containing pictures ──────────────────────────────────────

    @Test
    fun `gap shapes nested inside a group shape are not recursed into`() {
        val bytes = buildPptx { ppt ->
            val slide = ppt.createSlide()
            val group = slide.createGroup()
            val nestedBox = group.createTextBox().apply {
                anchor = Rectangle(0, 0, 300, 40)
                setText("text-inside-a-group")
            }
            check(nestedBox.text == "text-inside-a-group")
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val text = blocks.filterIsInstance<DocumentBlock.Paragraph>().joinToString("\n") { it.text }

        assertFalse(text.contains("text-inside-a-group"),
            "Group-shape children are NOT recursed into — only top-level shapes on slide.shapes are visited. " +
                "Decks that wrap text in a group lose all of that text.")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildPptx(build: (XMLSlideShow) -> Unit): ByteArray {
        val ppt = XMLSlideShow()
        try {
            build(ppt)
            val out = ByteArrayOutputStream()
            ppt.write(out)
            return out.toByteArray()
        } finally {
            ppt.close()
        }
    }

    private fun tinyPng(): ByteArray {
        val img = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }
}
