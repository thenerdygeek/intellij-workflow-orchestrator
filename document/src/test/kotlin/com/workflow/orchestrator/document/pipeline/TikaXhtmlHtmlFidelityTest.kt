package com.workflow.orchestrator.document.pipeline

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * HTML-fidelity cluster (deferred read_document findings NAV-3 / IMG-5 / HX-2).
 *
 * Exercises the [TikaXhtmlPipeline] → [com.workflow.orchestrator.document.sax.DocumentBlockHandler]
 * path on small synthetic HTML byte arrays. All three findings were surfaced by the
 * Wikipedia GDP corpus file:
 *
 * - **NAV-3** — sidebar / header / footer / nav chrome text leaked into the body and got
 *   promoted to section anchors. The fix discards `nav` / `header` / `footer` / `aside`
 *   subtrees during HTML parsing (custom Tika [org.apache.tika.parser.html.HtmlMapper]).
 * - **IMG-5** — a wall of tiny decorative flag thumbnails (`width`/`height` ≈ 23×12, no alt)
 *   was emitted as hundreds of bare image markers. The fix suppresses sub-threshold decorative
 *   `<img>` (small declared dimensions AND empty alt).
 * - **HX-2** — intra-page fragment refs (`href="#cite_note-1"`) rendered as broken
 *   `text (#cite_note-1)` links with no resolvable target. The fix drops the dead fragment
 *   parenthetical, keeping the visible text.
 */
class TikaXhtmlHtmlFidelityTest {

    private val pipeline = TikaXhtmlPipeline()

    private fun extract(html: String): List<DocumentBlock> =
        pipeline.extract(ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)), "text/html")

    private fun headings(blocks: List<DocumentBlock>) =
        blocks.filterIsInstance<DocumentBlock.Heading>().map { it.text }

    private fun bodyText(blocks: List<DocumentBlock>) =
        blocks.joinToString("\n") {
            when (it) {
                is DocumentBlock.Paragraph -> it.text
                is DocumentBlock.Heading -> it.text
                is DocumentBlock.ListBlock -> it.items.joinToString("\n")
                else -> ""
            }
        }

    // ── NAV-3: chrome regions must not become section anchors or body ──────────

    @Test
    fun `NAV-3 nav and footer chrome is stripped while the real h2 heading survives`() {
        val html = """
            <html><body>
              <nav role="navigation">
                <ul><li>Contribute</li><li>Appearance</li><li>English</li></ul>
              </nav>
              <header><div>Print/export</div></header>
              <main>
                <h2>Real Article Section</h2>
                <p>The gross domestic product is measured in dollars.</p>
              </main>
              <footer><p>Privacy policy</p></footer>
            </body></html>
        """.trimIndent()

        val blocks = extract(html)
        val heads = headings(blocks)
        val body = bodyText(blocks)

        assertTrue(heads.contains("Real Article Section"),
            "The real <h2> must survive as a heading; got headings=$heads")
        // Chrome must be gone from BOTH the section anchors and the body.
        for (chrome in listOf("Contribute", "Appearance", "English", "Print/export", "Privacy policy")) {
            assertFalse(body.contains(chrome),
                "Chrome text '$chrome' must be stripped, not promoted to a heading/body; body=$body")
        }
        assertTrue(body.contains("gross domestic product"), "Real article body must survive")
    }

    @Test
    fun `NAV-3 guard — a normal article with no chrome is completely untouched`() {
        val html = """
            <html><body>
              <h1>Document Title</h1>
              <h2>Introduction</h2>
              <p>This is the opening paragraph of a perfectly ordinary article.</p>
              <h2>Methods</h2>
              <p>We describe the methodology here in detail.</p>
            </body></html>
        """.trimIndent()

        val blocks = extract(html)
        val heads = headings(blocks)

        assertEquals(listOf("Document Title", "Introduction", "Methods"), heads,
            "An ordinary article's headings must be untouched by the chrome strip")
        val body = bodyText(blocks)
        assertTrue(body.contains("opening paragraph"), "Body paragraph 1 preserved")
        assertTrue(body.contains("methodology here"), "Body paragraph 2 preserved")
    }

    // ── IMG-5: decorative inline-icon wall ─────────────────────────────────────

    @Test
    fun `IMG-5 a run of tiny decorative icons with empty alt is suppressed`() {
        val icons = (1..20).joinToString("") {
            """<img src="//cdn/40px-Flag_$it.svg.png" alt="" width="23" height="12"/>"""
        }
        val html = "<html><body><p>Before.</p>$icons<p>After.</p></body></html>"

        val blocks = extract(html)
        val imageRefs = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>()

        assertTrue(imageRefs.size < 20,
            "A wall of 20 tiny decorative icons must NOT emit 20 image markers; got ${imageRefs.size}")
        // Conservative: ideally zero markers for a pure decorative run.
        assertEquals(0, imageRefs.size,
            "Sub-threshold decorative icons (empty alt, ${'$'}width/height < 32) should be dropped; got ${imageRefs.size}")
    }

    @Test
    fun `IMG-5 guard — a real content image with alt or large dimensions survives`() {
        val html = """
            <html><body>
              <p><img src="diagram.png" alt="System architecture diagram" width="640" height="480"/></p>
              <p><img src="photo.jpg" width="301" height="301"/></p>
            </body></html>
        """.trimIndent()

        val blocks = extract(html)
        val imageRefs = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>()

        assertEquals(2, imageRefs.size,
            "Real images (descriptive alt, or large dimensions) must survive; got ${imageRefs.size}")
        assertTrue(imageRefs.any { it.altText == "System architecture diagram" },
            "The alt-bearing diagram must survive")
    }

    @Test
    fun `IMG-5 guard — a small icon WITH a descriptive alt is kept (alt = real content)`() {
        val html = """<html><body><p><img src="//cdn/40px-warning.png" alt="Warning" width="23" height="12"/></p></body></html>"""

        val blocks = extract(html)
        val imageRefs = blocks.filterIsInstance<DocumentBlock.EmbeddedFileRef>()

        assertEquals(1, imageRefs.size,
            "A tiny image that carries a real alt is NOT purely decorative — keep it; got ${imageRefs.size}")
    }

    // ── HX-2: dead intra-page fragment refs ────────────────────────────────────

    @Test
    fun `HX-2 a dead cite_note fragment ref renders without the broken anchor token`() {
        val html = """<html><body><p>China's economy is the largest<a href="#cite_note-1">[1]</a> in Asia.</p></body></html>"""

        val blocks = extract(html)
        val body = bodyText(blocks)

        assertFalse(body.contains("#cite_note"),
            "Dead fragment ref token must be cleaned, not left as a broken link; body=$body")
        assertFalse(body.contains("(#"),
            "No bare '(#...)' fragment parenthetical should survive; body=$body")
        assertTrue(body.contains("largest") && body.contains("in Asia"),
            "Surrounding prose preserved; body=$body")
    }

    @Test
    fun `HX-2 guard — a real external href is still appended as a parenthetical`() {
        val html = """<html><body><p>See <a href="https://example.com/details">the details</a> here.</p></body></html>"""

        val blocks = extract(html)
        val body = bodyText(blocks)

        assertTrue(body.contains("the details (https://example.com/details)"),
            "A real external href must still be preserved as a parenthetical; body=$body")
    }
}
