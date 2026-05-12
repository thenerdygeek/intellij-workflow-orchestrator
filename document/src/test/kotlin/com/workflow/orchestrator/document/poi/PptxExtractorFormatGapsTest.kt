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
 * Characterization and positive-coverage tests for [PptxExtractor] format gaps.
 *
 * Gap tests (inline formatting): bold/italic runs collapse to plain text — style metadata is
 * not preserved.
 *
 * Positive tests (Phase 2): picture shapes extracted as [DocumentBlock.EmbeddedFileRef] when
 * [com.workflow.orchestrator.document.service.ImageExtractionService] is wired.
 *
 * Positive tests (Phase 3): [org.apache.poi.xslf.usermodel.XSLFGroupShape] children are
 * recursed into via [PptxExtractor]'s `handleShape()` helper, supporting arbitrary nesting
 * depth. Text/tables/pictures inside grouped shapes now extract in declaration order alongside
 * top-level content.
 *
 * Positive tests (slide comments): slide-level review comments extracted via
 * `XSLFSlide.getComments()` and emitted as `DocumentBlock.Comment(REVIEW, …)`.
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

    // ── Group shape recursion (positive coverage after Phase 3) ───────────────

    @Test
    fun `text inside a group shape is recursed into and extracted`() {
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
        val texts = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        assertTrue(texts.any { it.contains("text-inside-a-group") },
            "Group-shape children must be recursed into; got texts: $texts")
    }

    @Test
    fun `nested group shapes (group inside group) are recursed into recursively`() {
        val bytes = buildPptx { ppt ->
            val slide = ppt.createSlide()
            val outerGroup = slide.createGroup()
            val innerGroup = outerGroup.createGroup()
            val deepBox = innerGroup.createTextBox().apply {
                anchor = Rectangle(0, 0, 300, 40)
                setText("text-at-depth-2")
            }
            check(deepBox.text == "text-at-depth-2")
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val texts = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        assertTrue(texts.any { it.contains("text-at-depth-2") },
            "Recursion must handle arbitrary nesting depth; got texts: $texts")
    }

    // ── SmartArt (P5a-4) ─────────────────────────────────────────────────────

    /**
     * P5a-4: a PPTX containing a SmartArt diagramData part emits a
     * [DocumentBlock.ListBlock] with the SmartArt text items.
     *
     * We inject a minimal `diagramData+xml` part into the ZIP after the base PPTX is
     * serialised by POI — identical strategy to the DOCX SmartArt test and to the PPTX
     * comment injection in [buildPptxWithComment].
     *
     * [SmartArtExtractor] queries the OPC package by content type (package-wide), so no
     * slide-level relationship is required for the extractor to find the part.
     */
    @Test
    fun `PPTX SmartArt diagramData part is extracted as a flat ListBlock`() {
        val bytes = buildPptxWithSmartArt(listOf("Idea", "Plan", "Execute"))

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val listBlocks = blocks.filterIsInstance<DocumentBlock.ListBlock>()
        assertTrue(listBlocks.isNotEmpty(),
            "Expected at least one ListBlock from PPTX SmartArt; got: ${blocks.map { it::class.simpleName }}")
        val items = listBlocks.flatMap { it.items }
        assertTrue(items.containsAll(listOf("Idea", "Plan", "Execute")),
            "All SmartArt text items should appear; got items: $items")
    }

    @Test
    fun `PPTX with no SmartArt emits no ListBlocks`() {
        val bytes = buildPptx { ppt ->
            ppt.createSlide().createTextBox().apply {
                anchor = java.awt.Rectangle(50, 30, 500, 50)
                setText("Just text")
            }
        }
        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        assertTrue(blocks.none { it is DocumentBlock.ListBlock },
            "No SmartArt → no ListBlocks")
    }

    @Test
    fun `mixed top-level and group-shape text emits in declaration order`() {
        val bytes = buildPptx { ppt ->
            val slide = ppt.createSlide()
            slide.createTextBox().apply {
                anchor = Rectangle(0, 0, 300, 40)
                setText("top-level-text")
            }
            val group = slide.createGroup()
            group.createTextBox().apply {
                anchor = Rectangle(0, 0, 300, 40)
                setText("group-text")
            }
            slide.createTextBox().apply {
                anchor = Rectangle(0, 0, 300, 40)
                setText("trailing-top-level")
            }
        }

        val blocks = extractor.extract(ByteArrayInputStream(bytes))
        val texts = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        // Declaration order: top-level-text, then group's child (group-text), then trailing-top-level.
        val topIdx = texts.indexOfFirst { it.contains("top-level-text") }
        val groupIdx = texts.indexOfFirst { it.contains("group-text") }
        val trailingIdx = texts.indexOfFirst { it.contains("trailing-top-level") }
        assertTrue(topIdx >= 0 && groupIdx > topIdx && trailingIdx > groupIdx,
            "Order should be: top → group child → trailing; got texts: $texts")
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

    /**
     * Builds a PPTX ZIP with a SmartArt diagramData part injected after the base bytes.
     *
     * [SmartArtExtractor] queries the OPC package by content type; no relationship entry
     * is needed for the extractor to find the part.
     */
    private fun buildPptxWithSmartArt(items: List<String>): ByteArray {
        val baseBytes = buildPptx { ppt ->
            ppt.createSlide().createTextBox().apply {
                anchor = java.awt.Rectangle(50, 30, 500, 50)
                setText("Slide with SmartArt")
            }
        }

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
                                """  <Override PartName="/ppt/diagrams/data1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.diagramData+xml"/>
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
            zout.putNextEntry(ZipEntry("ppt/diagrams/data1.xml"))
            zout.write(diagramXml.toByteArray(Charsets.UTF_8))
            zout.closeEntry()
        }
        return out.toByteArray()
    }
}
