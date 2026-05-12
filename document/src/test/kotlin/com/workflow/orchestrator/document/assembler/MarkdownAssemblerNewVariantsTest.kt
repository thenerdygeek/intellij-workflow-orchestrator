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
}
