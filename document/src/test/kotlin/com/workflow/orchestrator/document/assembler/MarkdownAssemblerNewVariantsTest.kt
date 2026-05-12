package com.workflow.orchestrator.document.assembler

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownAssemblerNewVariantsTest {

    private val assembler = MarkdownAssembler()

    @Test
    fun `Comment variant exists with author, anchorText, text, kind`() {
        val c = DocumentBlock.Comment(
            author = "Jane",
            anchorText = "80ms",
            text = "Confirm with the latest benchmark.",
            kind = DocumentBlock.Comment.Kind.REVIEW,
        )
        assertEquals("Jane", c.author)
        assertEquals("80ms", c.anchorText)
        assertEquals(DocumentBlock.Comment.Kind.REVIEW, c.kind)
    }

    @Test
    fun `Comment kind enum has REVIEW, TRACKED_INSERTION, TRACKED_DELETION, PDF_ANNOTATION`() {
        val names = DocumentBlock.Comment.Kind.entries.map { it.name }.toSet()
        assertEquals(
            setOf("REVIEW", "TRACKED_INSERTION", "TRACKED_DELETION", "PDF_ANNOTATION"),
            names,
        )
    }

    @Test
    fun `ListBlock has ordered and items fields, single-level only`() {
        val unordered = DocumentBlock.ListBlock(ordered = false, items = listOf("a", "b"))
        val ordered = DocumentBlock.ListBlock(ordered = true, items = listOf("first", "second"))
        assertEquals(false, unordered.ordered)
        assertEquals(listOf("a", "b"), unordered.items)
        assertEquals(true, ordered.ordered)
    }

    @Test
    fun `Footnote has marker and text`() {
        val f = DocumentBlock.Footnote(marker = "1", text = "See appendix A.")
        assertEquals("1", f.marker)
        assertEquals("See appendix A.", f.text)
    }

    @Test
    fun `KeyValueGroup has title and pairs`() {
        val kv = DocumentBlock.KeyValueGroup(
            title = "Document properties",
            pairs = listOf("Author" to "Jane", "Title" to "Spec v3"),
        )
        assertEquals("Document properties", kv.title)
        assertEquals(2, kv.pairs.size)
        assertEquals("Jane", kv.pairs[0].second)
    }

    @Test
    fun `EmbeddedFileRef path is optional and defaults to null for backward compat`() {
        val noPath = DocumentBlock.EmbeddedFileRef(name = "foo.png", mimeType = "image/png")
        assertEquals(null, noPath.path)

        val withPath = DocumentBlock.EmbeddedFileRef(
            name = "foo.png",
            mimeType = "image/png",
            path = "/session/downloads/document-abc/image-0-def.png",
        )
        assertEquals("/session/downloads/document-abc/image-0-def.png", withPath.path)
    }

    @Test
    fun `Comment REVIEW renders as blockquote with author and anchor`() {
        val block = DocumentBlock.Comment(
            author = "Jane",
            anchorText = "80ms",
            text = "Confirm with the latest benchmark.",
            kind = DocumentBlock.Comment.Kind.REVIEW,
        )
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals(
            "> **Comment by Jane** (anchor: \"80ms\"):\n> Confirm with the latest benchmark.\n\n",
            md,
        )
    }

    @Test
    fun `Comment REVIEW with null author renders as Anonymous`() {
        val block = DocumentBlock.Comment(
            author = null,
            anchorText = "section 3",
            text = "Looks good.",
            kind = DocumentBlock.Comment.Kind.REVIEW,
        )
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals(
            "> **Comment by Anonymous** (anchor: \"section 3\"):\n> Looks good.\n\n",
            md,
        )
    }

    @Test
    fun `Comment REVIEW with null anchor omits anchor clause`() {
        val block = DocumentBlock.Comment(
            author = "Tom",
            anchorText = null,
            text = "Per slide overall.",
            kind = DocumentBlock.Comment.Kind.REVIEW,
        )
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals("> **Comment by Tom**:\n> Per slide overall.\n\n", md)
    }

    @Test
    fun `Comment with multi-line text indents every continuation line with greater-than-space`() {
        val block = DocumentBlock.Comment(
            author = "Jane",
            anchorText = null,
            text = "Line one.\nLine two.",
            kind = DocumentBlock.Comment.Kind.REVIEW,
        )
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals("> **Comment by Jane**:\n> Line one.\n> Line two.\n\n", md)
    }

    @Test
    fun `Comment TRACKED_INSERTION renders without anchor clause`() {
        val block = DocumentBlock.Comment(
            author = "Tom",
            anchorText = null,
            text = "proposed sentence",
            kind = DocumentBlock.Comment.Kind.TRACKED_INSERTION,
        )
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals("> **Tom proposes inserting**:\n> proposed sentence\n\n", md)
    }

    @Test
    fun `Comment TRACKED_DELETION renders the deleted text inline in the header`() {
        val block = DocumentBlock.Comment(
            author = "Tom",
            anchorText = "old text",
            text = "",
            kind = DocumentBlock.Comment.Kind.TRACKED_DELETION,
        )
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        // text is blank so body is dropped — just the header line + double newline.
        assertEquals("> **Tom proposes deleting**: \"old text\"\n\n", md)
    }

    @Test
    fun `Comment PDF_ANNOTATION with null author renders as PDF annotation`() {
        val block = DocumentBlock.Comment(
            author = null,
            anchorText = "paragraph quote",
            text = "highlighted by user",
            kind = DocumentBlock.Comment.Kind.PDF_ANNOTATION,
        )
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals(
            "> **PDF annotation** (on: \"paragraph quote\"):\n> highlighted by user\n\n",
            md,
        )
    }

    @Test
    fun `ListBlock unordered renders with dashes`() {
        val block = DocumentBlock.ListBlock(ordered = false, items = listOf("apple", "banana", "cherry"))
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals("- apple\n- banana\n- cherry\n\n", md)
    }

    @Test
    fun `ListBlock ordered renders with 1-indexed numbers`() {
        val block = DocumentBlock.ListBlock(ordered = true, items = listOf("first", "second"))
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals("1. first\n2. second\n\n", md)
    }

    @Test
    fun `ListBlock empty items renders nothing for the body but keeps trailing newlines absent`() {
        val block = DocumentBlock.ListBlock(ordered = false, items = emptyList())
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals("", md)
    }

    @Test
    fun `ListBlock items containing newlines preserve the newline inside the item text`() {
        // Nested-level encoding: caller puts "  sub-item" inside an item to indicate nesting.
        val block = DocumentBlock.ListBlock(
            ordered = false,
            items = listOf("top\n  sub-item-a\n  sub-item-b", "another top"),
        )
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals("- top\n  sub-item-a\n  sub-item-b\n- another top\n\n", md)
    }

    @Test
    fun `Footnote renders as caret-marker line`() {
        val block = DocumentBlock.Footnote(marker = "1", text = "See appendix A.")
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals("[^1]: See appendix A.\n", md)
    }

    @Test
    fun `Multiple Footnote blocks render contiguously with single trailing newline each`() {
        val blocks = listOf(
            DocumentBlock.Footnote(marker = "1", text = "First note."),
            DocumentBlock.Footnote(marker = "2", text = "Second note."),
            DocumentBlock.Footnote(marker = "a", text = "Alpha note."),
        )
        val (md, _) = assembler.assemble(blocks, maxChars = 10_000)
        assertEquals(
            "[^1]: First note.\n[^2]: Second note.\n[^a]: Alpha note.\n",
            md,
        )
    }

    @Test
    fun `Footnote ordering is the callers responsibility — assembler emits in list order`() {
        // Extractors emit Footnote blocks LAST in their returned List<DocumentBlock>.
        // The assembler renders in given order, producing a contiguous final block.
        val blocks = listOf(
            DocumentBlock.Paragraph("Body prose."),
            DocumentBlock.Footnote("1", "Note A"),
            DocumentBlock.Footnote("2", "Note B"),
        )
        val (md, _) = assembler.assemble(blocks, maxChars = 10_000)
        assertEquals("Body prose.\n\n[^1]: Note A\n[^2]: Note B\n", md)
    }

    @Test
    fun `KeyValueGroup renders bold title and indented pairs`() {
        val block = DocumentBlock.KeyValueGroup(
            title = "Document properties",
            pairs = listOf("Author" to "Jane", "Created" to "2026-05-12"),
        )
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals(
            "**Document properties**\n- Author: Jane\n- Created: 2026-05-12\n\n",
            md,
        )
    }

    @Test
    fun `KeyValueGroup with empty pairs renders the title only`() {
        val block = DocumentBlock.KeyValueGroup(title = "Bookmarks", pairs = emptyList())
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals("**Bookmarks**\n\n", md)
    }

    @Test
    fun `KeyValueGroup pairs with multi-line values render the newline inside the value`() {
        val block = DocumentBlock.KeyValueGroup(
            title = "Notes",
            pairs = listOf("Detail" to "line one\nline two"),
        )
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals("**Notes**\n- Detail: line one\nline two\n\n", md)
    }

    @Test
    fun `EmbeddedFileRef with null path keeps existing not-extracted format`() {
        val block = DocumentBlock.EmbeddedFileRef(name = "screenshot.png", mimeType = "image/png")
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        // Existing format from MarkdownAssembler.serializeEmbeddedFileRef — unchanged.
        assertEquals("[embedded: screenshot.png (image/png)]\n\n", md)
    }

    @Test
    fun `EmbeddedFileRef with non-null path renders image marker with path and mime`() {
        val block = DocumentBlock.EmbeddedFileRef(
            name = "figure-1.png",
            mimeType = "image/png",
            path = "/session/abc/downloads/document-xyz/image-0-def.png",
        )
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals(
            "[image: /session/abc/downloads/document-xyz/image-0-def.png] (image/png)\n\n",
            md,
        )
    }
}
