package com.workflow.orchestrator.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DocumentBlockTest {

    // ── Heading ────────────────────────────────────────────────────────────────

    @Test
    fun `Heading accepts level 1`() {
        val h = DocumentBlock.Heading(level = 1, text = "Title")
        assertEquals(1, h.level)
        assertEquals("Title", h.text)
    }

    @Test
    fun `Heading accepts level 6`() {
        val h = DocumentBlock.Heading(level = 6, text = "Deep")
        assertEquals(6, h.level)
    }

    @Test
    fun `Heading rejects level 0`() {
        assertThrows<IllegalArgumentException> {
            DocumentBlock.Heading(level = 0, text = "Bad")
        }
    }

    @Test
    fun `Heading rejects level 7`() {
        assertThrows<IllegalArgumentException> {
            DocumentBlock.Heading(level = 7, text = "Bad")
        }
    }

    @Test
    fun `Heading rejects negative level`() {
        assertThrows<IllegalArgumentException> {
            DocumentBlock.Heading(level = -1, text = "Bad")
        }
    }

    // ── Paragraph ─────────────────────────────────────────────────────────────

    @Test
    fun `Paragraph holds text unchanged`() {
        val p = DocumentBlock.Paragraph("Hello, world.")
        assertEquals("Hello, world.", p.text)
    }

    @Test
    fun `Paragraph allows empty text`() {
        val p = DocumentBlock.Paragraph("")
        assertEquals("", p.text)
    }

    // ── Table ─────────────────────────────────────────────────────────────────

    @Test
    fun `Table accepts matching headers and rows`() {
        val table = DocumentBlock.Table(
            headers = listOf("Name", "Value"),
            rows = listOf(
                listOf("alpha", "1"),
                listOf("beta", "2"),
            ),
        )
        assertEquals(2, table.headers.size)
        assertEquals(2, table.rows.size)
    }

    @Test
    fun `Table accepts empty rows (headers only, no data)`() {
        val table = DocumentBlock.Table(
            headers = listOf("Col A", "Col B", "Col C"),
            rows = emptyList(),
        )
        assertEquals(3, table.headers.size)
        assertEquals(0, table.rows.size)
    }

    @Test
    fun `Table accepts empty headers and empty rows`() {
        val table = DocumentBlock.Table(headers = emptyList(), rows = emptyList())
        assertEquals(0, table.headers.size)
        assertEquals(0, table.rows.size)
    }

    @Test
    fun `Table rejects row with too few cells`() {
        assertThrows<IllegalArgumentException> {
            DocumentBlock.Table(
                headers = listOf("A", "B", "C"),
                rows = listOf(listOf("x", "y")), // missing third cell
            )
        }
    }

    @Test
    fun `Table rejects row with too many cells`() {
        assertThrows<IllegalArgumentException> {
            DocumentBlock.Table(
                headers = listOf("A", "B"),
                rows = listOf(listOf("x", "y", "z")), // extra cell
            )
        }
    }

    @Test
    fun `Table rejects when any row in a multi-row list has wrong size`() {
        assertThrows<IllegalArgumentException> {
            DocumentBlock.Table(
                headers = listOf("A", "B"),
                rows = listOf(
                    listOf("ok1", "ok2"),
                    listOf("bad"),  // wrong length
                ),
            )
        }
    }

    @Test
    fun `Table stores optional caption`() {
        val table = DocumentBlock.Table(
            headers = listOf("X"),
            rows = emptyList(),
            caption = "Table 1: Requirements",
        )
        assertEquals("Table 1: Requirements", table.caption)
    }

    // ── PageMarker ────────────────────────────────────────────────────────────

    @Test
    fun `PageMarker accepts page number 1`() {
        val marker = DocumentBlock.PageMarker(pageNumber = 1)
        assertEquals(1, marker.pageNumber)
    }

    @Test
    fun `PageMarker accepts large page number`() {
        val marker = DocumentBlock.PageMarker(pageNumber = 999)
        assertEquals(999, marker.pageNumber)
    }

    @Test
    fun `PageMarker rejects pageNumber 0`() {
        assertThrows<IllegalArgumentException> {
            DocumentBlock.PageMarker(pageNumber = 0)
        }
    }

    @Test
    fun `PageMarker rejects negative pageNumber`() {
        assertThrows<IllegalArgumentException> {
            DocumentBlock.PageMarker(pageNumber = -5)
        }
    }

    // ── EmbeddedFileRef ───────────────────────────────────────────────────────

    @Test
    fun `EmbeddedFileRef stores name and mimeType`() {
        val ref = DocumentBlock.EmbeddedFileRef(name = "figure1.png", mimeType = "image/png")
        assertEquals("figure1.png", ref.name)
        assertEquals("image/png", ref.mimeType)
    }
}
