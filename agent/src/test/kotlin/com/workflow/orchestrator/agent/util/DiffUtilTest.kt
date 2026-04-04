package com.workflow.orchestrator.agent.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DiffUtilTest {

    @Nested
    inner class UnifiedDiff {

        @Test
        fun `identical content returns empty string`() {
            val content = "line 1\nline 2\nline 3"
            val result = DiffUtil.unifiedDiff(content, content, "test.kt")
            assertEquals("", result)
        }

        @Test
        fun `single line change produces unified diff`() {
            val old = "line 1\nline 2\nline 3"
            val new = "line 1\nline CHANGED\nline 3"
            val result = DiffUtil.unifiedDiff(old, new, "test.kt")

            assertTrue(result.contains("--- a/test.kt"))
            assertTrue(result.contains("+++ b/test.kt"))
            assertTrue(result.contains("-line 2"))
            assertTrue(result.contains("+line CHANGED"))
        }

        @Test
        fun `added lines appear with plus prefix`() {
            val old = "line 1\nline 2"
            val new = "line 1\nline 2\nline 3\nline 4"
            val result = DiffUtil.unifiedDiff(old, new, "test.kt")

            assertTrue(result.contains("+line 3"))
            assertTrue(result.contains("+line 4"))
            assertFalse(result.contains("-line"))
        }

        @Test
        fun `deleted lines appear with minus prefix`() {
            val old = "line 1\nline 2\nline 3\nline 4"
            val new = "line 1\nline 4"
            val result = DiffUtil.unifiedDiff(old, new, "test.kt")

            assertTrue(result.contains("-line 2"))
            assertTrue(result.contains("-line 3"))
        }

        @Test
        fun `empty old content produces all-additions diff`() {
            val new = "line 1\nline 2\nline 3"
            val result = DiffUtil.unifiedDiff("", new, "new_file.kt")

            assertTrue(result.contains("--- a/new_file.kt"))
            assertTrue(result.contains("+++ b/new_file.kt"))
            assertTrue(result.contains("+line 1"))
            assertTrue(result.contains("+line 2"))
            assertTrue(result.contains("+line 3"))
        }

        @Test
        fun `empty new content produces all-deletions diff`() {
            val old = "line 1\nline 2"
            val result = DiffUtil.unifiedDiff(old, "", "deleted.kt")

            assertTrue(result.contains("-line 1"))
            assertTrue(result.contains("-line 2"))
        }

        @Test
        fun `context lines surround changes`() {
            val old = "a\nb\nc\nd\ne\nf\ng\nh"
            val new = "a\nb\nc\nD_CHANGED\ne\nf\ng\nh"
            val result = DiffUtil.unifiedDiff(old, new, "test.kt", contextLines = 2)

            // Should have context: b, c before change, e, f after
            assertTrue(result.contains(" b"))
            assertTrue(result.contains(" c"))
            assertTrue(result.contains("-d"))
            assertTrue(result.contains("+D_CHANGED"))
            assertTrue(result.contains(" e"))
            assertTrue(result.contains(" f"))
        }

        @Test
        fun `file path in header uses a and b prefix`() {
            val result = DiffUtil.unifiedDiff("old", "new", "src/main/App.kt")

            assertTrue(result.contains("--- a/src/main/App.kt"))
            assertTrue(result.contains("+++ b/src/main/App.kt"))
        }

        @Test
        fun `hunk header contains line numbers`() {
            val old = "line 1\nline 2\nline 3"
            val new = "line 1\nline CHANGED\nline 3"
            val result = DiffUtil.unifiedDiff(old, new, "test.kt")

            assertTrue(result.contains("@@"), "Should contain hunk header with @@")
        }
    }

    @Nested
    inner class UnifiedDiffFromLines {

        @Test
        fun `identical lines return empty string`() {
            val lines = listOf("a", "b", "c")
            val result = DiffUtil.unifiedDiffFromLines(lines, lines, "test.kt")
            assertEquals("", result)
        }

        @Test
        fun `single line replacement`() {
            val old = listOf("alpha", "beta", "gamma")
            val new = listOf("alpha", "BETA", "gamma")
            val result = DiffUtil.unifiedDiffFromLines(old, new, "test.kt")

            assertTrue(result.contains("-beta"))
            assertTrue(result.contains("+BETA"))
            assertTrue(result.contains(" alpha"))
            assertTrue(result.contains(" gamma"))
        }

        @Test
        fun `multiple changes in same file`() {
            val old = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
            val new = listOf("a", "B", "c", "d", "e", "f", "g", "H", "i", "j")
            val result = DiffUtil.unifiedDiffFromLines(old, new, "test.kt", contextLines = 1)

            assertTrue(result.contains("-b"))
            assertTrue(result.contains("+B"))
            assertTrue(result.contains("-h"))
            assertTrue(result.contains("+H"))
        }

        @Test
        fun `empty old lines produces all additions`() {
            val new = listOf("line1", "line2")
            val result = DiffUtil.unifiedDiffFromLines(emptyList(), new, "test.kt")

            assertTrue(result.contains("+line1"))
            assertTrue(result.contains("+line2"))
        }
    }

    @Nested
    inner class ComputeEdits {

        @Test
        fun `identical lists produce all KEEP edits`() {
            val lines = listOf("a", "b", "c")
            val edits = DiffUtil.computeEdits(lines, lines)

            assertTrue(edits.all { it.type == DiffUtil.EditType.KEEP })
            assertEquals(3, edits.size)
        }

        @Test
        fun `completely different lists produce DELETE and INSERT edits`() {
            val old = listOf("a", "b")
            val new = listOf("x", "y")
            val edits = DiffUtil.computeEdits(old, new)

            val deletes = edits.count { it.type == DiffUtil.EditType.DELETE }
            val inserts = edits.count { it.type == DiffUtil.EditType.INSERT }
            assertTrue(deletes > 0, "Should have DELETE edits")
            assertTrue(inserts > 0, "Should have INSERT edits")
        }

        @Test
        fun `insertion at end`() {
            val old = listOf("a", "b")
            val new = listOf("a", "b", "c")
            val edits = DiffUtil.computeEdits(old, new)

            val keeps = edits.count { it.type == DiffUtil.EditType.KEEP }
            val inserts = edits.count { it.type == DiffUtil.EditType.INSERT }
            assertEquals(2, keeps)
            assertEquals(1, inserts)
        }

        @Test
        fun `deletion from middle`() {
            val old = listOf("a", "b", "c")
            val new = listOf("a", "c")
            val edits = DiffUtil.computeEdits(old, new)

            val keeps = edits.count { it.type == DiffUtil.EditType.KEEP }
            val deletes = edits.count { it.type == DiffUtil.EditType.DELETE }
            assertEquals(2, keeps)
            assertEquals(1, deletes)
        }
    }
}
