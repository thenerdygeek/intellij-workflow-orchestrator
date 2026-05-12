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
}
