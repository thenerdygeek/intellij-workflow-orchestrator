package com.workflow.orchestrator.document.assembler

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `CodeBlock has non-empty lines and rejects empty`() {
        val cb = DocumentBlock.CodeBlock(lines = listOf("a = b", "c = d"))
        assertEquals(listOf("a = b", "c = d"), cb.lines)
        assertThrows(IllegalArgumentException::class.java) { DocumentBlock.CodeBlock(emptyList()) }
    }

    @Test
    fun `CodeBlock renders as a fenced code block with line breaks preserved`() {
        val cb = DocumentBlock.CodeBlock(
            lines = listOf(
                "HTTP-version  = HTTP-name \"/\" DIGIT \".\" DIGIT",
                "HTTP-name     = %x48.54.54.50",
            ),
        )
        val (md, _) = assembler.assemble(listOf(cb), maxChars = 10_000)
        // Fences on their own lines, each source line preserved verbatim (leading indent kept).
        assertEquals(
            "```\nHTTP-version  = HTTP-name \"/\" DIGIT \".\" DIGIT\nHTTP-name     = %x48.54.54.50\n```\n\n",
            md,
        )
    }

    @Test
    fun `CodeBlock preserves leading indentation and trims trailing whitespace`() {
        val cb = DocumentBlock.CodeBlock(lines = listOf("    while (x > 0) {   ", "        do_thing()"))
        val (md, _) = assembler.assemble(listOf(cb), maxChars = 10_000)
        assertTrue(md.contains("\n    while (x > 0) {\n"), "leading indent lost or trailing ws kept: $md")
        assertTrue(md.contains("\n        do_thing()\n"), "second indented line wrong: $md")
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
    fun `image-mime EmbeddedFileRef with null path uses image token so the imageMarkers metric is consistent`() {
        val block = DocumentBlock.EmbeddedFileRef(name = "screenshot.png", mimeType = "image/png")
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        // IMG-4: image-mime refs always use the `[image:` token (even with no on-disk path)
        // so the corpus probe's `imageMarkers` count matches the body for every format.
        assertEquals("[image: screenshot.png] (image/png)\n\n", md)
    }

    @Test
    fun `non-image EmbeddedFileRef with null path keeps the embedded token`() {
        val block = DocumentBlock.EmbeddedFileRef(name = "data.bin", mimeType = "application/octet-stream")
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        // `[embedded:` is reserved for genuine non-image attachments the vision path can't render.
        assertEquals("[embedded: data.bin (application/octet-stream)]\n\n", md)
    }

    @Test
    fun `image-mime EmbeddedFileRef with null path but altText leads with the alt text`() {
        val block = DocumentBlock.EmbeddedFileRef(
            name = "img1.png",
            mimeType = "image/png",
            altText = "Milky way galaxy, under mostly clear night skies",
        )
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals("[image: Milky way galaxy, under mostly clear night skies] (image/png)\n\n", md)
    }

    @Test
    fun `EmbeddedFileRef with path and altText leads with alt text then path (IMG-1)`() {
        val block = DocumentBlock.EmbeddedFileRef(
            name = "figure-1.png",
            mimeType = "image/png",
            path = "/session/abc/downloads/document-xyz/image-0-def.png",
            altText = "Photo of boulders on beach in bright sunshine",
        )
        val (md, _) = assembler.assemble(listOf(block), maxChars = 10_000)
        assertEquals(
            "[image: Photo of boulders on beach in bright sunshine — " +
                "/session/abc/downloads/document-xyz/image-0-def.png] (image/png)\n\n",
            md,
        )
    }

    @Test
    fun `EmbeddedObjectRef renders kind-specific placeholder markers (IMG-3)`() {
        val smart = DocumentBlock.EmbeddedObjectRef(
            kind = DocumentBlock.EmbeddedObjectRef.Kind.SMARTART, name = "AlternatingHexagons",
        )
        val shape = DocumentBlock.EmbeddedObjectRef(
            kind = DocumentBlock.EmbeddedObjectRef.Kind.SHAPE, name = "Direct Access Storage 1",
        )
        val ole = DocumentBlock.EmbeddedObjectRef(
            kind = DocumentBlock.EmbeddedObjectRef.Kind.OLE, name = "PowerPoint.Slide.8",
        )
        val oleNoName = DocumentBlock.EmbeddedObjectRef(
            kind = DocumentBlock.EmbeddedObjectRef.Kind.OLE, name = null,
        )
        assertEquals("[SmartArt: AlternatingHexagons]\n\n", assembler.assemble(listOf(smart), 10_000).markdown)
        assertEquals("[Shape: Direct Access Storage 1]\n\n", assembler.assemble(listOf(shape), 10_000).markdown)
        assertEquals("[Embedded object: PowerPoint.Slide.8]\n\n", assembler.assemble(listOf(ole), 10_000).markdown)
        assertEquals("[Embedded object]\n\n", assembler.assemble(listOf(oleNoName), 10_000).markdown)
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

    @Test
    fun `Truncation marker mentions dropped footnotes when any Footnote was skipped`() {
        // Paragraph "x"*40 serializes to 42 chars (40 + "\n\n").
        // Each footnote "[^N]: A\n" is 8 chars.
        // At maxChars = 50: paragraph (42) + footnote 1 (8) = 50 fits exactly.
        // Footnote 2 would push to 58 → truncates here. Footnotes 2 and 3 drop.
        val blocks = listOf(
            DocumentBlock.Paragraph("x".repeat(40)),
            DocumentBlock.Footnote("1", "A"),
            DocumentBlock.Footnote("2", "B"),
            DocumentBlock.Footnote("3", "C"),
        )
        val (md, truncated) = assembler.assemble(blocks, maxChars = 50)
        assertTrue(truncated)
        assertTrue(
            md.contains("(2 footnotes dropped)"),
            "Expected '(2 footnotes dropped)' in marker, got: ${md.takeLast(150)}"
        )
    }

    @Test
    fun `Truncation marker omits footnote clause when no Footnote was dropped`() {
        // 50-char paragraphs only, no Footnote blocks.
        val blocks = listOf(
            DocumentBlock.Paragraph("a".repeat(50)),
            DocumentBlock.Paragraph("b".repeat(50)),
        )
        val (md, truncated) = assembler.assemble(blocks, maxChars = 60)
        assertTrue(truncated)
        assertFalse(
            md.contains("footnotes dropped"),
            "Marker should NOT mention footnotes when none were dropped, got: ${md.takeLast(150)}"
        )
    }
}
