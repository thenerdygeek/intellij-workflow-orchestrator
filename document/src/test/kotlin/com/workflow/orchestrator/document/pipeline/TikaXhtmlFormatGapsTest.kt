package com.workflow.orchestrator.document.pipeline

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Characterization tests for the [TikaXhtmlPipeline] / [com.workflow.orchestrator.document.sax.DocumentBlockHandler]
 * path used for HTML, RTF, ODT, EPUB, and anything that isn't PDF or OOXML Office.
 *
 * The SAX handler only emits the 5 [DocumentBlock] variants: Heading, Paragraph, Table,
 * PageMarker, EmbeddedFileRef. Inline structure (bold/italic/anchor/list-item) is
 * stripped to character data; structural elements that don't map to a variant disappear.
 */
class TikaXhtmlFormatGapsTest {

    private val pipeline = TikaXhtmlPipeline()

    // ── Inline formatting ─────────────────────────────────────────────────────

    @Test
    fun `gap HTML bold and italic tags are stripped — content survives, structure does not`() {
        val html = """
            <html><body>
            <p>Read the <b>important</b> and <i>urgent</i> notice carefully.</p>
            </body></html>
        """.trimIndent()

        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val text = blocks.filterIsInstance<DocumentBlock.Paragraph>().joinToString("\n") { it.text }

        assertTrue(text.contains("important"), "Bold content survives as plain text")
        assertTrue(text.contains("urgent"), "Italic content survives as plain text")
        assertFalse(text.contains("<b>") || text.contains("**important**"),
            "Bold structure is gone — handler does not synthesise Markdown")
    }

    // ── Hyperlinks ────────────────────────────────────────────────────────────

    @Test
    fun `HTML anchor href is preserved as parenthetical suffix on the anchor text`() {
        val html = """
            <html><body>
            <p>See <a href="https://example.com/details">the details</a> for context.</p>
            </body></html>
        """.trimIndent()

        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val text = blocks.filterIsInstance<DocumentBlock.Paragraph>().joinToString("\n") { it.text }

        assertTrue(text.contains("the details (https://example.com/details)"),
            "Anchor href should be appended as parenthetical; got: $text")
        assertTrue(text.contains("for context"), "Surrounding prose preserved")
    }

    // ── Lists (positive coverage after Phase 3) ───────────────────────────────

    @Test
    fun `HTML unordered list emits one ListBlock with ordered=false`() {
        val html = """
            <html><body>
            <ul>
              <li>First</li>
              <li>Second</li>
              <li>Third</li>
            </ul>
            </body></html>
        """.trimIndent()

        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val list = blocks.filterIsInstance<DocumentBlock.ListBlock>().singleOrNull()
        assertNotNull(list, "Expected one ListBlock from <ul>")
        assertEquals(false, list!!.ordered)
        assertEquals(listOf("First", "Second", "Third"), list.items)
    }

    @Test
    fun `HTML ordered list emits one ListBlock with ordered=true`() {
        val html = """
            <html><body>
            <ol>
              <li>Alpha</li>
              <li>Beta</li>
            </ol>
            </body></html>
        """.trimIndent()

        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val list = blocks.filterIsInstance<DocumentBlock.ListBlock>().singleOrNull()
        assertNotNull(list)
        assertEquals(true, list!!.ordered)
        assertEquals(listOf("Alpha", "Beta"), list.items)
    }

    @Test
    fun `nested HTML list flattens to one ListBlock with indent prefixes on nested items`() {
        val html = """
            <html><body>
            <ul>
              <li>Top1
                <ul>
                  <li>Sub1</li>
                  <li>Sub2</li>
                </ul>
              </li>
              <li>Top2</li>
            </ul>
            </body></html>
        """.trimIndent()

        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val list = blocks.filterIsInstance<DocumentBlock.ListBlock>().singleOrNull()
        assertNotNull(list)
        // Top1 keeps no indent; Sub1/Sub2 get "  "; Top2 returns to no indent.
        // Whitespace around the inner ul may vary; assert presence + indent pattern.
        val items = list!!.items.map { it.trim() to it.startsWith("  ") }
        assertTrue(items.any { it.first == "Top1" && !it.second }, "Top1 should not be indented; got: ${list.items}")
        assertTrue(items.any { it.first == "Sub1" && it.second }, "Sub1 should be indented; got: ${list.items}")
        assertTrue(items.any { it.first == "Sub2" && it.second }, "Sub2 should be indented; got: ${list.items}")
        assertTrue(items.any { it.first == "Top2" && !it.second }, "Top2 should not be indented; got: ${list.items}")
    }

    @Test
    fun `consecutive ul and ol emit two separate ListBlocks`() {
        val html = """
            <html><body>
            <ul><li>A</li><li>B</li></ul>
            <p>between</p>
            <ol><li>1</li><li>2</li></ol>
            </body></html>
        """.trimIndent()

        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val lists = blocks.filterIsInstance<DocumentBlock.ListBlock>()
        assertEquals(2, lists.size, "Two distinct lists with prose between → two ListBlocks")
        assertEquals(false, lists[0].ordered)
        assertEquals(true, lists[1].ordered)
        // Order: list1, paragraph, list2.
        val firstList = blocks.indexOfFirst { it is DocumentBlock.ListBlock }
        val between = blocks.indexOfFirst { it is DocumentBlock.Paragraph && (it as DocumentBlock.Paragraph).text.contains("between") }
        val secondList = blocks.indexOfLast { it is DocumentBlock.ListBlock }
        assertTrue(firstList >= 0 && between > firstList && secondList > between,
            "Expected ListBlock, Paragraph(between), ListBlock order; got: ${blocks.map { it::class.simpleName }}")
    }

    // ── Images (positive coverage after Phase 2) ──────────────────────────────

    @Test
    fun `HTML img tag emits EmbeddedFileRef with src-derived name and MIME — path is null because we do not fetch URI bytes`() {
        val html = """
            <html><body>
            <p>Caption above.</p>
            <p><img src="cid:logo.png" alt="Company Logo"/></p>
            <p>Caption below.</p>
            </body></html>
        """.trimIndent()

        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val refs = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>()

        assertEquals(1, refs.size, "Expected exactly one EmbeddedFileRef for the <img>")
        val ref = refs.single()
        assertEquals("Company Logo", ref.name, "Alt text preferred for the display name")
        assertEquals("image/png", ref.mimeType, "MIME derived from .png extension in src")
        assertNull(ref.path, "HTML img bytes are not fetched in Phase 2; path is always null")

        // The surrounding text blocks still extract.
        val texts = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }
        assertTrue(texts.any { it.contains("Caption above") })
        assertTrue(texts.any { it.contains("Caption below") })
    }

    @Test
    fun `HTML img with no alt falls back to the src filename`() {
        val html = """<html><body><p><img src="https://example.com/path/photo.jpg"/></p></body></html>"""
        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val ref = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>().single()
        assertEquals("photo.jpg", ref.name)
        assertEquals("image/jpeg", ref.mimeType)
    }

    @Test
    fun `HTML img with data URI — Tika strips the src attribute so handler falls back to octet-stream placeholder`() {
        // Tika's HtmlParser truncates <img src="data:…"> to just "data:" in the SAX
        // attribute stream. The handler therefore cannot recover the declared MIME via
        // the pipeline path and emits a safe placeholder. The unit-level test at
        // DocumentBlockHandlerHelpersTest covers the data: URI extraction logic directly.
        val html = """<html><body><p><img src="data:image/webp;base64,aGVsbG8="/></p></body></html>"""
        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val ref = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>().single()
        assertEquals("image", ref.name, "Name falls back to 'image' when src is stripped by Tika")
        assertEquals("application/octet-stream", ref.mimeType,
            "MIME falls back to octet-stream when Tika strips the data: URI from src")
    }

    @Test
    fun `HTML img with neither src nor alt still emits a placeholder so the LLM knows an image existed`() {
        val html = """<html><body><p><img/></p></body></html>"""
        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val ref = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>().single()
        assertEquals("image", ref.name)
        assertEquals("application/octet-stream", ref.mimeType)
    }

    @Test
    fun `multiple HTML img tags emit one EmbeddedFileRef each in document order`() {
        val html = """
            <html><body>
            <p>Intro.</p>
            <p><img src="https://example.com/first.png" alt="First"/></p>
            <p>Middle text.</p>
            <p><img src="https://example.com/second.jpg"/></p>
            <p>Outro.</p>
            </body></html>
        """.trimIndent()

        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val refs = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>()

        assertEquals(2, refs.size, "Expected one EmbeddedFileRef per <img>")
        assertEquals("First", refs[0].name, "First image's alt becomes its name")
        assertEquals("image/png", refs[0].mimeType)
        assertEquals("second.jpg", refs[1].name, "Second image with no alt falls back to filename")
        assertEquals("image/jpeg", refs[1].mimeType)

        // Document order: First img comes before Middle text which comes before Second img.
        val firstImgIdx = blocks.indexOfFirst { it is DocumentBlock.EmbeddedFileRef }
        val middleTextIdx = blocks.indexOfFirst {
            it is DocumentBlock.Paragraph && (it).text.contains("Middle text")
        }
        val secondImgIdx = blocks.indexOfLast { it is DocumentBlock.EmbeddedFileRef }
        assertTrue(firstImgIdx < middleTextIdx && middleTextIdx < secondImgIdx,
            "Images and text should interleave in document order; got img@$firstImgIdx middle@$middleTextIdx img2@$secondImgIdx")
    }

    // ── Nested tables ─────────────────────────────────────────────────────────

    @Test
    fun `nested tables are flattened — inner cells become text inside the outer cell`() {
        // DocumentBlockHandler.kt explicitly documents: "Nested tables: a `<table>` open
        // resets state; inner tables are flattened into the outer table's cell text".
        val html = """
            <html><body>
            <table>
              <tr><th>Outer</th></tr>
              <tr><td><table><tr><th>InnerCol</th></tr><tr><td>inner-val</td></tr></table></td></tr>
            </table>
            </body></html>
        """.trimIndent()

        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val tables = blocks.filterIsInstance<DocumentBlock.Table>()
        // Implementation-specific: the handler may emit either the outer table (with inner
        // text concatenated into its cell) or the inner table (because the inner <table>
        // open resets state). Either way, only one Table block is produced from the
        // single outer structure — nesting is lost.
        assertEquals(1, tables.size,
            "Exactly one Table is emitted from nested HTML tables — inner structure is lost")
    }

    // ── Model boundary ────────────────────────────────────────────────────────

    @Test
    fun `the entire DocumentBlock sealed hierarchy has 10 variants — that is the expressivity ceiling`() {
        val variants = DocumentBlock::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertEquals(
            setOf(
                "Heading", "Paragraph", "Table", "PageMarker", "EmbeddedFileRef",
                "EmbeddedObjectRef",
                "Comment", "ListBlock", "Footnote", "KeyValueGroup",
            ),
            variants,
            "Any feature that doesn't fit one of these 10 variants is invisible to the LLM. " +
                "Adding new structural concepts requires extending this hierarchy AND the MarkdownAssembler."
        )
    }
}
