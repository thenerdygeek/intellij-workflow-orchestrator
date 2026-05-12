package com.workflow.orchestrator.document.poi

import com.workflow.orchestrator.core.model.DocumentBlock
import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.util.IOUtils
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Characterization tests covering format-gap behaviour and positive coverage for [PptxExtractor].
 *
 * Gap tests: slide content is iterated via `slide.shapes` routed through a `when` block that
 * handles only `XSLFTable` and `XSLFTextShape`. Picture shapes and group shapes containing
 * pictures fall off the end of the `when` silently.
 *
 * Positive tests: slide-level review comments are extracted via `XSLFSlide.getComments()` and
 * emitted as `DocumentBlock.Comment(REVIEW, author, anchorText=null, text)` blocks after notes.
 */
class PptxExtractorFormatGapsTest {

    private val extractor = PptxExtractor()

    // ── Embedded images on a slide (positive coverage after Phase 2) ──────────

    @Test
    fun `picture shape on a slide is extracted as EmbeddedFileRef with on-disk path when ImageExtractionService is wired`(
        @TempDir downloads: java.nio.file.Path,
    ) {
        IOUtils.setByteArrayMaxOverride(200_000_000)
        val bytes = try {
            buildPptx { ppt ->
                val slide = ppt.createSlide()
                slide.createTextBox().apply {
                    anchor = Rectangle(50, 30, 500, 50)
                    setText("Slide with image")
                }
                val picData = ppt.addPicture(tinyPng(), PictureData.PictureType.PNG)
                slide.createPicture(picData).apply {
                    anchor = Rectangle(100, 100, 200, 200)
                }
            }
        } finally {
            IOUtils.setByteArrayMaxOverride(50_000_000)
        }

        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        val extractor = PptxExtractor(imageService = imageService, docKey = "test.pptx")
        val blocks = extractor.extract(ByteArrayInputStream(bytes))

        val imageRefs = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>()
        assertEquals(1, imageRefs.size, "Expected exactly one EmbeddedFileRef for the picture shape")
        val ref = imageRefs.single()
        assertEquals("image/png", ref.mimeType)
        assertNotNull(ref.path)
        assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of(ref.path!!)),
            "Saved file should exist at the reported path")
    }

    @Test
    fun `picture shape on a slide without ImageExtractionService is silently dropped (legacy)`() {
        IOUtils.setByteArrayMaxOverride(200_000_000)
        val bytes = try {
            buildPptx { ppt ->
                val slide = ppt.createSlide()
                val picData = ppt.addPicture(tinyPng(), PictureData.PictureType.PNG)
                slide.createPicture(picData).apply {
                    anchor = Rectangle(100, 100, 200, 200)
                }
            }
        } finally {
            IOUtils.setByteArrayMaxOverride(50_000_000)
        }

        val blocks = PptxExtractor().extract(ByteArrayInputStream(bytes))
        assertTrue(blocks.none { it is DocumentBlock.EmbeddedFileRef },
            "Without ImageExtractionService wired, picture shapes are skipped (legacy behaviour)")
    }

    @Test
    fun `multi-slide PPTX attaches each slides image to its own slide range`(
        @TempDir downloads: java.nio.file.Path,
    ) {
        IOUtils.setByteArrayMaxOverride(200_000_000)
        val png1 = tinyPng()
        val png2 = tinyPng() + byteArrayOf(0xFF.toByte(), 0xEE.toByte())  // different bytes
        val bytes = try {
            buildPptx { ppt ->
                val s1 = ppt.createSlide()
                s1.createTextBox().apply {
                    anchor = Rectangle(50, 30, 500, 50); setText("slide-1-title")
                }
                val pic1 = ppt.addPicture(png1, PictureData.PictureType.PNG)
                s1.createPicture(pic1).apply { anchor = Rectangle(100, 100, 50, 50) }

                val s2 = ppt.createSlide()
                s2.createTextBox().apply {
                    anchor = Rectangle(50, 30, 500, 50); setText("slide-2-title")
                }
                val pic2 = ppt.addPicture(png2, PictureData.PictureType.PNG)
                s2.createPicture(pic2).apply { anchor = Rectangle(100, 100, 50, 50) }
            }
        } finally {
            IOUtils.setByteArrayMaxOverride(50_000_000)
        }

        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        val extractor = PptxExtractor(imageService = imageService, docKey = "multi.pptx")
        val blocks = extractor.extract(ByteArrayInputStream(bytes))

        val s1Idx = blocks.indexOfFirst { it is DocumentBlock.Heading && (it as DocumentBlock.Heading).text.contains("Slide 1") }
        val s2Idx = blocks.indexOfFirst { it is DocumentBlock.Heading && (it as DocumentBlock.Heading).text.contains("Slide 2") }
        assertTrue(s1Idx >= 0 && s2Idx > s1Idx)

        val s1Range = blocks.subList(s1Idx, s2Idx)
        val s2Range = blocks.subList(s2Idx, blocks.size)
        assertEquals(1, s1Range.count { it is DocumentBlock.EmbeddedFileRef },
            "Slide 1 should own exactly one EmbeddedFileRef")
        assertEquals(1, s2Range.count { it is DocumentBlock.EmbeddedFileRef },
            "Slide 2 should own exactly one EmbeddedFileRef")
    }

    @Test
    fun `PPTX with no picture shapes emits no EmbeddedFileRef even with imageService wired`(
        @TempDir downloads: java.nio.file.Path,
    ) {
        val bytes = buildPptx { ppt ->
            ppt.createSlide().createTextBox().apply {
                anchor = Rectangle(50, 30, 500, 50)
                setText("Just a text slide")
            }
        }
        val imageService = com.workflow.orchestrator.document.service.ImageExtractionService(downloadsRoot = downloads)
        val extractor = PptxExtractor(imageService = imageService, docKey = "noimg.pptx")
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        assertTrue(blocks.none { it is DocumentBlock.EmbeddedFileRef })
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
    fun `slide-level review comment emits DocumentBlock Comment after the slide content`() {
        // POI 5.4.1 requires raw XML manipulation to add slide comments programmatically:
        //   1. createRelationship(XSLFRelation.COMMENT_AUTHORS, …) on the presentation to get
        //      an XSLFCommentAuthors part, then add an author via CTCommentAuthorList.addNewCmAuthor().
        //   2. createRelationship(XSLFRelation.COMMENTS, …) on the slide to get an
        //      XSLFComments part, then add a comment via CTCommentList.addNewCm().
        val bytes = buildPptxWithComment(authorName = "Jane", commentText = "Looks great overall.")

        val blocks = extractor.extract(ByteArrayInputStream(bytes))

        val commentBlock = blocks.filterIsInstance<DocumentBlock.Comment>().singleOrNull()
        assertNotNull(commentBlock, "Expected exactly one Comment block in the extracted output")
        assertEquals(DocumentBlock.Comment.Kind.REVIEW, commentBlock!!.kind)
        assertEquals("Jane", commentBlock.author)
        assertEquals("Looks great overall.", commentBlock.text)
        assertNull(
            commentBlock.anchorText,
            "PPTX comments are slide-level, not text-anchored, so anchorText must be null per spec",
        )
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

    /**
     * Constructs a single-slide PPTX with one slide-level review comment.
     *
     * POI 5.4.1 has no public high-level "addComment" API, and the internal approach via
     * `createRelationship` + no-arg `XSLFComments()` constructor doesn't auto-serialize the
     * in-memory XML back to the OPC package part on `write()` because `XSLFComments` has no
     * `commit()` override.
     *
     * Instead, we build the fixture in two steps:
     * 1. Create a minimal PPTX via POI (slide with text, first slide is at `ppt/slides/slide1.xml`).
     * 2. Post-process the ZIP bytes to inject:
     *    - `ppt/commentAuthors.xml` — one author with `id=0, name=[authorName]`
     *    - `ppt/slides/comments/comment1.xml` — one comment `authorId=0, text=[commentText]`
     *    - Updated `[Content_Types].xml` — add the two new content types
     *    - Updated `ppt/_rels/presentation.xml.rels` — add commentAuthors relationship
     *    - New `ppt/slides/_rels/slide1.xml.rels` — add slide → comments relationship
     *
     * This avoids all reflection hacks and produces a structurally valid PPTX ZIP.
     */
    private fun buildPptxWithComment(authorName: String, commentText: String): ByteArray {
        // Step 1: build a minimal PPTX with POI (slide with one text box).
        val baseBytes = buildPptx { ppt ->
            val slide = ppt.createSlide()
            slide.createTextBox().apply {
                anchor = Rectangle(50, 30, 500, 50)
                setText("Slide with a comment")
            }
        }

        // Step 2: post-process the ZIP to inject comment parts.
        val authorXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:cmAuthorLst xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cmAuthor id="0" name="$authorName" initials="${authorName.take(1)}" lastIdx="0" clrIdx="0"/>
</p:cmAuthorLst>""".trimIndent()

        val commentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:cmLst xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
         xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
  <p:cm authorId="0" dt="2024-01-01T00:00:00" idx="1">
    <p:pos x="0" y="0"/>
    <p:text>$commentText</p:text>
  </p:cm>
</p:cmLst>""".trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zout ->
            ZipInputStream(ByteArrayInputStream(baseBytes)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val bytes = zin.readBytes()

                    when {
                        name == "[Content_Types].xml" -> {
                            // Inject content types for the two new parts.
                            val updated = String(bytes, Charsets.UTF_8)
                                .replace(
                                    "</Types>",
                                    """  <Override PartName="/ppt/commentAuthors.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.commentAuthors+xml"/>
  <Override PartName="/ppt/slides/comments/comment1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.comments+xml"/>
</Types>""",
                                )
                            zout.putNextEntry(ZipEntry(name))
                            zout.write(updated.toByteArray(Charsets.UTF_8))
                        }
                        name == "ppt/_rels/presentation.xml.rels" -> {
                            // Add a relationship from the presentation to commentAuthors.xml.
                            val updated = String(bytes, Charsets.UTF_8)
                                .replace(
                                    "</Relationships>",
                                    """  <Relationship Id="rIdCA1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/commentAuthors" Target="commentAuthors.xml"/>
</Relationships>""",
                                )
                            zout.putNextEntry(ZipEntry(name))
                            zout.write(updated.toByteArray(Charsets.UTF_8))
                        }
                        name == "ppt/slides/_rels/slide1.xml.rels" -> {
                            // Extend the existing slide rels file with the comments relationship.
                            val updated = String(bytes, Charsets.UTF_8)
                                .replace(
                                    "</Relationships>",
                                    """  <Relationship Id="rIdCm1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="comments/comment1.xml"/>
</Relationships>""",
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
            // Add the new parts.
            zout.putNextEntry(ZipEntry("ppt/commentAuthors.xml"))
            zout.write(authorXml.toByteArray(Charsets.UTF_8))
            zout.closeEntry()

            zout.putNextEntry(ZipEntry("ppt/slides/comments/comment1.xml"))
            zout.write(commentXml.toByteArray(Charsets.UTF_8))
            zout.closeEntry()
        }
        return out.toByteArray()
    }

    private fun tinyPng(): ByteArray {
        val img = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }
}
