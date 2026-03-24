package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiffHunkApplierTest {

    @Test
    fun `applies simple hunk to file content`() {
        val original = "line1\nline2\nline3"
        val hunk = DiffHunk(startLine = 2, oldLines = listOf("line2"), newLines = listOf("modified"))
        val result = DiffHunkApplier.apply(original, hunk)
        assertEquals("line1\nmodified\nline3", result.content)
        assertTrue(result.applied)
        assertFalse(result.conflict)
    }

    @Test
    fun `applies hunk replacing multiple lines`() {
        val original = "a\nb\nc\nd\ne"
        val hunk = DiffHunk(startLine = 2, oldLines = listOf("b", "c"), newLines = listOf("x", "y", "z"))
        val result = DiffHunkApplier.apply(original, hunk)
        assertEquals("a\nx\ny\nz\nd\ne", result.content)
        assertTrue(result.applied)
    }

    @Test
    fun `applies hunk deleting lines`() {
        val original = "a\nb\nc\nd"
        val hunk = DiffHunk(startLine = 2, oldLines = listOf("b", "c"), newLines = emptyList())
        val result = DiffHunkApplier.apply(original, hunk)
        assertEquals("a\nd", result.content)
        assertTrue(result.applied)
    }

    @Test
    fun `applies hunk inserting lines (empty old)`() {
        val original = "a\nb\nc"
        // startLine = 2 with no old lines means insert before line 2
        val hunk = DiffHunk(startLine = 2, oldLines = emptyList(), newLines = listOf("x", "y"))
        val result = DiffHunkApplier.apply(original, hunk)
        assertEquals("a\nx\ny\nb\nc", result.content)
        assertTrue(result.applied)
    }

    @Test
    fun `handles context line shift with fuzzy matching`() {
        val original = "a\nb\nline2\nc"
        val hunk = DiffHunk(startLine = 2, oldLines = listOf("line2"), newLines = listOf("changed"))
        val result = DiffHunkApplier.apply(original, hunk)
        assertTrue(result.applied, "fuzzy match should find 'line2' at line 3")
        assertTrue(result.content.contains("changed"))
        assertFalse(result.content.contains("line2"))
    }

    @Test
    fun `returns conflict on mismatch`() {
        val original = "line1\nline2\nline3"
        val hunk = DiffHunk(startLine = 2, oldLines = listOf("different"), newLines = listOf("new"))
        val result = DiffHunkApplier.apply(original, hunk)
        assertFalse(result.applied)
        assertTrue(result.conflict)
        assertTrue(result.message.contains("Could not find matching lines"))
    }

    @Test
    fun `applies hunk at first line`() {
        val original = "first\nsecond\nthird"
        val hunk = DiffHunk(startLine = 1, oldLines = listOf("first"), newLines = listOf("FIRST"))
        val result = DiffHunkApplier.apply(original, hunk)
        assertEquals("FIRST\nsecond\nthird", result.content)
        assertTrue(result.applied)
    }

    @Test
    fun `applies hunk at last line`() {
        val original = "first\nsecond\nthird"
        val hunk = DiffHunk(startLine = 3, oldLines = listOf("third"), newLines = listOf("THIRD"))
        val result = DiffHunkApplier.apply(original, hunk)
        assertEquals("first\nsecond\nTHIRD", result.content)
        assertTrue(result.applied)
    }

    @Test
    fun `fuzzy match reports offset in message`() {
        // line2 is at position 4 (1-based), but hunk says startLine=2
        val original = "a\nb\nc\nline2\nd"
        val hunk = DiffHunk(startLine = 2, oldLines = listOf("line2"), newLines = listOf("changed"))
        val result = DiffHunkApplier.apply(original, hunk)
        assertTrue(result.applied)
        assertTrue(result.message.contains("offset"), "Should mention offset in message")
    }

    @Test
    fun `applyAll applies multiple hunks sequentially`() {
        val original = "a\nb\nc\nd\ne"
        val hunks = listOf(
            DiffHunk(startLine = 2, oldLines = listOf("b"), newLines = listOf("B")),
            DiffHunk(startLine = 4, oldLines = listOf("d"), newLines = listOf("D"))
        )
        val results = DiffHunkApplier.applyAll(original, hunks)
        assertEquals(2, results.size)
        assertTrue(results.all { it.applied })
        assertEquals("a\nB\nc\nD\ne", results.last().content)
    }

    @Test
    fun `applyAll continues after conflict`() {
        val original = "a\nb\nc"
        // Hunks are sorted by startLine descending internally, so line 3 is applied first
        val hunks = listOf(
            DiffHunk(startLine = 2, oldLines = listOf("nonexistent"), newLines = listOf("x")),
            DiffHunk(startLine = 3, oldLines = listOf("c"), newLines = listOf("C"))
        )
        val results = DiffHunkApplier.applyAll(original, hunks)
        assertEquals(2, results.size)
        // Line 3 hunk is applied first (descending sort), then line 2 conflict
        assertTrue(results[0].applied)
        assertFalse(results[1].applied)
        assertTrue(results[1].conflict)
    }

    @Test
    fun `handles empty file content`() {
        val original = ""
        val hunk = DiffHunk(startLine = 1, oldLines = listOf("something"), newLines = listOf("new"))
        val result = DiffHunkApplier.apply(original, hunk)
        assertFalse(result.applied)
        assertTrue(result.conflict)
    }

    @Test
    fun `handles startLine beyond file length`() {
        val original = "a\nb"
        val hunk = DiffHunk(startLine = 100, oldLines = listOf("a"), newLines = listOf("x"))
        val result = DiffHunkApplier.apply(original, hunk)
        // Should still find via fuzzy match if within range, otherwise conflict
        // "a" is at line 1, offset from 100 is 99, which exceeds MAX_FUZZY_OFFSET
        assertFalse(result.applied)
        assertTrue(result.conflict)
    }

    @Test
    fun `preserves original content on conflict`() {
        val original = "unchanged\ncontent\nhere"
        val hunk = DiffHunk(startLine = 1, oldLines = listOf("no match"), newLines = listOf("x"))
        val result = DiffHunkApplier.apply(original, hunk)
        assertEquals(original, result.content, "Original content should be preserved on conflict")
    }
}
