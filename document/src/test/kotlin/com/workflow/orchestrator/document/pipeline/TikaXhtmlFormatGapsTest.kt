package com.workflow.orchestrator.document.pipeline

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `gap HTML anchor href is dropped — only display text remains`() {
        val html = """
            <html><body>
            <p>See <a href="https://example.com/details">the details</a> for context.</p>
            </body></html>
        """.trimIndent()

        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val text = blocks.filterIsInstance<DocumentBlock.Paragraph>().joinToString("\n") { it.text }

        assertTrue(text.contains("the details"), "Anchor display text kept")
        assertTrue(text.contains("for context"), "Surrounding prose kept")
        assertFalse(text.contains("example.com"),
            "href is lost — DocumentBlockHandler never reads attributes on <a>")
    }

    // ── Lists ────────────────────────────────────────────────────────────────

    @Test
    fun `gap HTML lists are flattened — no bullet, no number, no nesting metadata`() {
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

        // No DocumentBlock.List variant exists; <li> content surfaces somewhere — usually
        // collapsed into one paragraph by Tika's XHTML emission. The exact arrangement
        // varies by Tika version; what we assert is "no list semantics survived".
        val variants = DocumentBlock::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertFalse("List" in variants, "Sentinel: DocumentBlock has no List variant")
        assertFalse("ListItem" in variants, "Sentinel: DocumentBlock has no ListItem variant")

        val flat = blocks.joinToString(" ") {
            when (it) {
                is DocumentBlock.Paragraph -> it.text
                is DocumentBlock.Heading -> it.text
                else -> ""
            }
        }
        assertTrue(flat.contains("First") && flat.contains("Second") && flat.contains("Third"),
            "List item text survives as plain content")
        assertFalse(Regex("""^\s*[•\-*]\s""").containsMatchIn(flat),
            "No bullet markers reintroduced — extractor preserves no list type info")
    }

    // ── Images ────────────────────────────────────────────────────────────────

    @Test
    fun `gap HTML img tag is dropped completely — no EmbeddedFileRef, no alt text fallback`() {
        val html = """
            <html><body>
            <p>Caption above.</p>
            <p><img src="cid:logo.png" alt="Company Logo"/></p>
            <p>Caption below.</p>
            </body></html>
        """.trimIndent()

        val blocks = pipeline.extract(ByteArrayInputStream(html.toByteArray()), "text/html")
        val texts = blocks.filterIsInstance<DocumentBlock.Paragraph>().map { it.text }

        assertTrue(texts.any { it.contains("Caption above") })
        assertTrue(texts.any { it.contains("Caption below") })
        assertTrue(blocks.none { it is DocumentBlock.EmbeddedFileRef },
            "No EmbeddedFileRef emitted for img tags")
        assertFalse(texts.any { it.contains("Company Logo") },
            "alt text is not even surfaced as a fallback caption")
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
    fun `the entire DocumentBlock sealed hierarchy has 5 variants — that is the expressivity ceiling`() {
        val variants = DocumentBlock::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertEquals(
            setOf("Heading", "Paragraph", "Table", "PageMarker", "EmbeddedFileRef"),
            variants,
            "Any feature that doesn't fit one of these 5 variants is invisible to the LLM. " +
                "Adding new structural concepts (List, Code, Quote, Image, Comment, Footnote) " +
                "requires extending this hierarchy AND the MarkdownAssembler."
        )
    }
}
