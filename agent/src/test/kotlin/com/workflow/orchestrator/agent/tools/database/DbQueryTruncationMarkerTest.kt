package com.workflow.orchestrator.agent.tools.database

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.sql.ResultSet
import java.sql.ResultSetMetaData

/**
 * Coverage for feedback.md §3 — db_query cell truncation used to silently cut a JSON
 * value mid-word (`"UNIT_D…"`) with no indication. After the fix, the rendered cell
 * carries an explicit `[truncated, K of N chars]` marker AND a per-result-set footer
 * counting how many cells were clipped.
 */
class DbQueryTruncationMarkerTest {

    @Test
    @DisplayName("§3 — short cell values pass through unchanged (no marker)")
    fun `short cell values pass through unchanged`() {
        val rs = singleRowResultSet(columns = listOf("name" to "Alice"))
        val (table, count) = DatabaseConnectionManager.resultSetToMarkdown(rs)
        assertEquals(1, count)
        assertTrue(table.contains("| Alice |"), "Expected short value to render verbatim")
        assertFalse(table.contains("truncated"), "Did not expect any truncation marker")
    }

    @Test
    @DisplayName("§3 — long cell values get the marker with kept-of-original counts")
    fun `long cell value gets explicit truncation marker`() {
        val payload = "X".repeat(700)   // exceeds MAX_CELL_CHARS=500
        val rs = singleRowResultSet(columns = listOf("payload" to payload))
        val (table, _) = DatabaseConnectionManager.resultSetToMarkdown(rs)

        assertTrue(
            table.contains("[truncated, 500 of 700 chars]"),
            "Expected explicit truncation marker, got:\n$table"
        )
        assertTrue(
            table.contains("cell value(s) truncated at 500 chars"),
            "Expected footer summarising truncations, got:\n$table"
        )
        // Verify CAST hint is in the footer (we want the LLM to know how to re-query)
        assertTrue(table.contains("CAST(") || table.contains("LEFT("),
            "Expected re-query hint in footer")
    }

    @Test
    @DisplayName("§3 — pipe characters in cells are replaced so Markdown table is not broken")
    fun `pipe chars in cell values get replaced`() {
        // A user/order id with a literal `|` would split the Markdown cell across columns
        // pre-fix, corrupting the table layout. Now sanitized to `/`.
        val rs = singleRowResultSet(columns = listOf("id" to "u|42"))
        val (table, _) = DatabaseConnectionManager.resultSetToMarkdown(rs)
        assertFalse(table.contains("u|42"), "Expected pipe to be sanitized")
        assertTrue(table.contains("u/42"), "Expected pipe replaced with slash, got:\n$table")
    }

    @Test
    @DisplayName("§3 — newlines in cells are replaced so Markdown row stays single-line")
    fun `newlines in cell values get replaced`() {
        val rs = singleRowResultSet(columns = listOf("note" to "line one\nline two"))
        val (table, _) = DatabaseConnectionManager.resultSetToMarkdown(rs)
        // The value should appear on a single visual line in the rendered table
        assertFalse(table.lines().any { it == "line two |" },
            "Expected newline to be replaced with space; got:\n$table")
        assertTrue(table.contains("line one line two") || table.contains("line one  line two"),
            "Expected newline to be space-replaced, got:\n$table")
    }

    @Test
    @DisplayName("§3 — multiple truncations are counted together in the footer")
    fun `multiple truncations roll up to a single footer line`() {
        val big = "X".repeat(700)
        // Two columns × two rows = four cells, three of which are oversized.
        val rs = multiRowResultSet(
            columns = listOf("col1", "col2"),
            rows = listOf(
                listOf(big, "short"),
                listOf(big, big),
            ),
        )
        val (table, _) = DatabaseConnectionManager.resultSetToMarkdown(rs)
        assertTrue(table.contains("3 cell value(s) truncated"),
            "Expected count of 3 in footer, got:\n$table")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ResultSet builders
    // ──────────────────────────────────────────────────────────────────────────

    private fun singleRowResultSet(columns: List<Pair<String, String>>): ResultSet =
        multiRowResultSet(
            columns = columns.map { it.first },
            rows = listOf(columns.map { it.second }),
        )

    private fun multiRowResultSet(columns: List<String>, rows: List<List<String>>): ResultSet {
        val rs = mockk<ResultSet>()
        val meta = mockk<ResultSetMetaData>()

        every { meta.columnCount } returns columns.size
        for ((idx, name) in columns.withIndex()) {
            every { meta.getColumnName(idx + 1) } returns name
        }
        every { rs.metaData } returns meta

        // The implementation reads with rs.next() then rs.getString(i) per column,
        // then peeks rs.next() once at the end to detect overflow.
        val iter = rows.iterator()
        var current: List<String>? = null
        every { rs.next() } answers {
            if (iter.hasNext()) {
                current = iter.next()
                true
            } else {
                current = null
                false
            }
        }
        for (i in columns.indices) {
            every { rs.getString(i + 1) } answers { current?.getOrNull(i) }
        }
        return rs
    }

    private fun assertEquals(expected: Int, actual: Int) =
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual)
}
