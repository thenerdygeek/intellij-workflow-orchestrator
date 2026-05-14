package com.workflow.orchestrator.document.assembler

import com.workflow.orchestrator.core.model.DocumentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownAssemblerTest {

    private val assembler = MarkdownAssembler()

    // ── 1. Empty list ─────────────────────────────────────────────────────────

    @Test
    fun `empty list returns empty string and truncated false`() {
        val (md, truncated) = assembler.assemble(emptyList(), maxChars = 1000)
        assertEquals("", md)
        assertFalse(truncated)
    }

    // ── 2. Single Paragraph ───────────────────────────────────────────────────

    @Test
    fun `single Paragraph renders with trailing double newline`() {
        val blocks = listOf(DocumentBlock.Paragraph("Hello, world."))
        val (md, truncated) = assembler.assemble(blocks, maxChars = 10_000)
        assertEquals("Hello, world.\n\n", md)
        assertFalse(truncated)
    }

    // ── 3. Heading levels 1..6 ────────────────────────────────────────────────

    @Test
    fun `heading level 1 renders as single hash`() {
        val (md, _) = assembler.assemble(listOf(DocumentBlock.Heading(1, "Title")), maxChars = 10_000)
        assertEquals("# Title\n\n", md)
    }

    @Test
    fun `heading level 2 renders as two hashes`() {
        val (md, _) = assembler.assemble(listOf(DocumentBlock.Heading(2, "Section")), maxChars = 10_000)
        assertEquals("## Section\n\n", md)
    }

    @Test
    fun `heading level 3 renders as three hashes`() {
        val (md, _) = assembler.assemble(listOf(DocumentBlock.Heading(3, "Sub")), maxChars = 10_000)
        assertEquals("### Sub\n\n", md)
    }

    @Test
    fun `heading level 4 renders as four hashes`() {
        val (md, _) = assembler.assemble(listOf(DocumentBlock.Heading(4, "Sub-sub")), maxChars = 10_000)
        assertEquals("#### Sub-sub\n\n", md)
    }

    @Test
    fun `heading level 5 renders as five hashes`() {
        val (md, _) = assembler.assemble(listOf(DocumentBlock.Heading(5, "Deep")), maxChars = 10_000)
        assertEquals("##### Deep\n\n", md)
    }

    @Test
    fun `heading level 6 renders as six hashes`() {
        val (md, _) = assembler.assemble(listOf(DocumentBlock.Heading(6, "Deepest")), maxChars = 10_000)
        assertEquals("###### Deepest\n\n", md)
    }

    // ── 4. Table with no caption ──────────────────────────────────────────────

    @Test
    fun `table with no caption renders standard pipe table`() {
        val table = DocumentBlock.Table(
            headers = listOf("Name", "Value"),
            rows = listOf(
                listOf("alpha", "1"),
                listOf("beta", "22"),
            ),
        )
        val (md, truncated) = assembler.assemble(listOf(table), maxChars = 10_000)
        assertFalse(truncated)

        // Headers must appear in first row.
        val lines = md.lines()
        assertTrue(lines[0].contains("Name"), "header row should contain 'Name'")
        assertTrue(lines[0].contains("Value"), "header row should contain 'Value'")

        // Separator row follows the header row.
        assertTrue(lines[1].contains("---"), "separator row should contain '---'")

        // Data rows.
        assertTrue(lines[2].contains("alpha"), "first data row should contain 'alpha'")
        assertTrue(lines[3].contains("beta"), "second data row should contain 'beta'")

        // Columns are padded: 'Value' is 5 chars, '22' is 2 chars — header wins.
        // 'alpha' is 5 chars, 'beta' is 4 chars — alpha wins col-0 width.
        // Every row must start with '|' and end with '|'.
        assertTrue(lines[0].startsWith("|"), "header row should start with '|'")
        assertTrue(lines[0].trimEnd().endsWith("|"), "header row should end with '|'")
        assertTrue(lines[2].startsWith("|"), "data row should start with '|'")
        assertTrue(lines[2].trimEnd().endsWith("|"), "data row should end with '|'")

        // Column widths padded consistently: each cell padded to the same column width.
        // 'alpha' width=5, 'beta' width=4 → col0 width=5; 'Value' width=5 → col1 width=5.
        // Expected header:  "| Name  | Value |"
        // Expected sep:     "| ----- | ----- |"
        // Expected row1:    "| alpha | 1     |"
        // Expected row2:    "| beta  | 22    |"
        assertEquals("| Name  | Value |", lines[0].trimEnd())
        assertEquals("| ----- | ----- |", lines[1].trimEnd())
        assertEquals("| alpha | 1     |", lines[2].trimEnd())
        assertEquals("| beta  | 22    |", lines[3].trimEnd())

        // Trailing blank line (table ends with \n\n).
        assertTrue(md.endsWith("\n\n"), "table output should end with double newline")
    }

    // ── 5. Table with caption ─────────────────────────────────────────────────

    @Test
    fun `table with caption renders caption as bold paragraph before table`() {
        val table = DocumentBlock.Table(
            headers = listOf("Col"),
            rows = listOf(listOf("val")),
            caption = "Table 1: Requirements",
        )
        val (md, _) = assembler.assemble(listOf(table), maxChars = 10_000)

        assertTrue(md.startsWith("**Table 1: Requirements**\n\n"), "output should start with bold caption")
        // The pipe table follows immediately after the caption.
        assertTrue(md.contains("| Col |"), "table header should follow caption")
    }

    // ── 6. Table with | in cell content ──────────────────────────────────────

    @Test
    fun `pipe in cell content is escaped as backslash-pipe`() {
        val table = DocumentBlock.Table(
            headers = listOf("Expr"),
            rows = listOf(listOf("a | b")),
        )
        val (md, _) = assembler.assemble(listOf(table), maxChars = 10_000)
        assertTrue(md.contains("a \\| b"), "pipe in cell must be escaped as \\|")
    }

    // ── 7. Table with \ in cell content ──────────────────────────────────────

    @Test
    fun `backslash in cell content is escaped as double backslash`() {
        val table = DocumentBlock.Table(
            headers = listOf("Path"),
            rows = listOf(listOf("C:\\Users")),
        )
        val (md, _) = assembler.assemble(listOf(table), maxChars = 10_000)
        assertTrue(md.contains("C:\\\\Users"), "backslash in cell must be doubled to \\\\")
    }

    // ── 8. Table with \| in cell content ─────────────────────────────────────

    @Test
    fun `backslash-pipe in cell content produces triple-backslash-pipe (backslash doubled then pipe escaped)`() {
        // Input: \|
        // Step 1 (backslash escape): \\ |
        // Step 2 (pipe escape): \\ \|
        // So output cell content should be \\|  i.e. the literal chars: backslash backslash backslash pipe
        val table = DocumentBlock.Table(
            headers = listOf("X"),
            rows = listOf(listOf("\\|")),
        )
        val (md, _) = assembler.assemble(listOf(table), maxChars = 10_000)
        // The escaped cell content in the Markdown source should be \\\|
        assertTrue(md.contains("\\\\\\|"), "\\| in cell must produce \\\\\\| (backslash doubled then pipe escaped)")
    }

    // ── 9. Table with embedded newline in cell ────────────────────────────────

    @Test
    fun `embedded newline in cell is replaced with br tag`() {
        val table = DocumentBlock.Table(
            headers = listOf("Notes"),
            rows = listOf(listOf("line1\nline2")),
        )
        val (md, _) = assembler.assemble(listOf(table), maxChars = 10_000)
        assertTrue(md.contains("line1<br>line2"), "newline in cell must be replaced with <br>")
    }

    // ── 10. Empty Table ───────────────────────────────────────────────────────

    @Test
    fun `empty table with no headers and no rows emits nothing`() {
        val table = DocumentBlock.Table(headers = emptyList(), rows = emptyList())
        val (md, truncated) = assembler.assemble(listOf(table), maxChars = 10_000)
        assertEquals("", md)
        assertFalse(truncated)
    }

    // ── 11. PageMarker ────────────────────────────────────────────────────────

    @Test
    fun `PageMarker 5 renders as HTML comment on its own line`() {
        val (md, truncated) = assembler.assemble(
            listOf(DocumentBlock.PageMarker(5)),
            maxChars = 10_000,
        )
        assertEquals("<!-- page: 5 -->\n", md)
        assertFalse(truncated)
    }

    // ── 12. EmbeddedFileRef ───────────────────────────────────────────────────

    @Test
    fun `EmbeddedFileRef renders as bracketed placeholder`() {
        val ref = DocumentBlock.EmbeddedFileRef(name = "diagram.png", mimeType = "image/png")
        val (md, truncated) = assembler.assemble(listOf(ref), maxChars = 10_000)
        assertEquals("[embedded: diagram.png (image/png)]\n\n", md)
        assertFalse(truncated)
    }

    // ── 13. Mixed blocks ──────────────────────────────────────────────────────

    @Test
    fun `mixed blocks are assembled in order`() {
        val blocks = listOf(
            DocumentBlock.Heading(1, "Overview"),
            DocumentBlock.Paragraph("Intro text."),
            DocumentBlock.Table(
                headers = listOf("ID", "Status"),
                rows = listOf(listOf("BUG-1", "OPEN")),
            ),
            DocumentBlock.Paragraph("Conclusion."),
            DocumentBlock.PageMarker(2),
            DocumentBlock.Paragraph("Page 2 content."),
        )
        val (md, truncated) = assembler.assemble(blocks, maxChars = 10_000)
        assertFalse(truncated)

        // Verify order by checking index positions.
        val headingPos = md.indexOf("# Overview")
        val introPos = md.indexOf("Intro text.")
        val tablePos = md.indexOf("| ID")
        val conclusionPos = md.indexOf("Conclusion.")
        val markerPos = md.indexOf("<!-- page: 2 -->")
        val page2Pos = md.indexOf("Page 2 content.")

        assertTrue(headingPos >= 0, "heading must be present")
        assertTrue(introPos > headingPos, "intro must follow heading")
        assertTrue(tablePos > introPos, "table must follow intro")
        assertTrue(conclusionPos > tablePos, "conclusion must follow table")
        assertTrue(markerPos > conclusionPos, "page marker must follow conclusion")
        assertTrue(page2Pos > markerPos, "page 2 content must follow page marker")
    }

    // ── 14. Truncation at exact boundary ──────────────────────────────────────

    @Test
    fun `block that would push output over maxChars is dropped and marker appended`() {
        // First block: "Hello\n\n" = 7 chars; second: "World\n\n" = 7 chars.
        // maxChars = 7 → second block would push to 14, exceeding 7. Drop it.
        val blocks = listOf(
            DocumentBlock.Paragraph("Hello"),
            DocumentBlock.Paragraph("World"),
        )
        val (md, truncated) = assembler.assemble(blocks, maxChars = 7)
        assertTrue(truncated)
        // The rendered part must be exactly the first block.
        assertTrue(md.startsWith("Hello\n\n"), "rendered part should be first paragraph")
        // The marker must be present.
        assertTrue(md.contains("*[Document truncated at"), "truncation marker must be present")
        // The second block must NOT be present.
        assertFalse(md.contains("World"), "dropped block must not appear in output")
    }

    // ── 15. Truncation that would split a table ────────────────────────────────

    @Test
    fun `table is dropped atomically when it would exceed maxChars`() {
        // Para: "Short\n\n" = 7 chars.
        // Table will be larger; set maxChars so that para fits but table would not.
        val para = DocumentBlock.Paragraph("Short")   // 7 chars
        val table = DocumentBlock.Table(
            headers = listOf("Column A", "Column B"),
            rows = listOf(listOf("value1", "value2"), listOf("value3", "value4")),
        )
        // Serialise the table to measure its size.
        val tableSize = assembler.assemble(listOf(table), maxChars = 100_000).markdown.length

        // maxChars is 7 (para fits exactly, table does not).
        val (md, truncated) = assembler.assemble(listOf(para, table), maxChars = 7)
        assertTrue(truncated)
        // Para is present; table is absent.
        assertTrue(md.contains("Short"), "paragraph before table must be present")
        assertFalse(md.contains("Column A"), "table header must not appear (dropped atomically)")
        // Marker present.
        assertTrue(md.contains("*[Document truncated at"), "truncation marker must be present")
        // Suppress unused variable warning.
        assertTrue(tableSize > 0)
    }

    // ── 16. Single oversize block ──────────────────────────────────────────────

    @Test
    fun `single block larger than maxChars is included with truncation marker after it`() {
        // A paragraph with 100 characters; maxChars = 10.
        val bigText = "A".repeat(100)
        val blocks = listOf(DocumentBlock.Paragraph(bigText))
        val (md, truncated) = assembler.assemble(blocks, maxChars = 10)
        assertTrue(truncated)
        // The big block must be present.
        assertTrue(md.contains(bigText), "oversize block must still be included")
        // Marker comes AFTER the block, not before.
        val blockEnd = md.indexOf(bigText) + bigText.length
        val markerStart = md.indexOf("*[Document truncated at")
        assertTrue(markerStart > blockEnd, "marker must appear after the oversize block")
    }

    // ── 17. Empty list with small maxChars ────────────────────────────────────

    @Test
    fun `empty list with small maxChars returns empty string and truncated false`() {
        val (md, truncated) = assembler.assemble(emptyList(), maxChars = 10)
        assertEquals("", md)
        assertFalse(truncated)
    }

    // ── 18. Truncation marker text format ─────────────────────────────────────

    @Test
    fun `truncation marker format matches specification exactly`() {
        // Set up two paragraphs; limit so only the first fits.
        // "First\n\n" = 7 chars.  maxChars = 7 so the second is dropped.
        val blocks = listOf(
            DocumentBlock.Paragraph("First"),
            DocumentBlock.Paragraph("Second"),
        )
        val (md, truncated) = assembler.assemble(blocks, maxChars = 7)
        assertTrue(truncated)

        // N = 7 (length of "First\n\n"), M = 7 + "Second\n\n".length = 7+8 = 15.
        // X = 1 (one block rendered), Y = 2 (total blocks).
        val expectedMarker =
            "\n\n*[Document truncated at 7 characters of 15 total characters; 1 of 2 blocks rendered]*\n"
        assertTrue(
            md.contains(expectedMarker),
            "truncation marker must match the spec format exactly.\n" +
                "Expected to find: $expectedMarker\n" +
                "Actual output:    $md",
        )
    }
}
