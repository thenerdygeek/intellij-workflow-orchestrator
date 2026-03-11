package com.workflow.orchestrator.cody.editor

import com.workflow.orchestrator.cody.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodyEditApplierLogicTest {

    @Test
    fun `applyTextEditsToContent replaces text correctly`() {
        val content = "line0\nline1\nline2\nline3\nline4"
        val edits = listOf(
            TextEdit(
                type = "replace",
                range = Range(
                    start = Position(line = 1, character = 0),
                    end = Position(line = 1, character = 5)
                ),
                value = "REPLACED"
            )
        )
        val result = CodyEditApplierLogic.applyTextEditsToContent(content, edits)
        assertTrue(result.contains("REPLACED"))
        assertFalse(result.contains("line1"))
    }

    @Test
    fun `applyTextEditsToContent inserts text`() {
        val content = "line0\nline1\nline2"
        val edits = listOf(
            TextEdit(
                type = "insert",
                position = Position(line = 1, character = 0),
                value = "INSERTED\n"
            )
        )
        val result = CodyEditApplierLogic.applyTextEditsToContent(content, edits)
        assertTrue(result.contains("INSERTED"))
    }

    @Test
    fun `applyTextEditsToContent deletes text`() {
        val content = "line0\nDELETE_ME\nline2"
        val edits = listOf(
            TextEdit(
                type = "delete",
                range = Range(
                    start = Position(line = 1, character = 0),
                    end = Position(line = 2, character = 0)
                )
            )
        )
        val result = CodyEditApplierLogic.applyTextEditsToContent(content, edits)
        assertFalse(result.contains("DELETE_ME"))
        assertTrue(result.contains("line0"))
        assertTrue(result.contains("line2"))
    }

    @Test
    fun `computeOffset converts line and character to offset`() {
        val content = "abc\ndef\nghi"
        assertEquals(0, CodyEditApplierLogic.computeOffset(content, Position(0, 0)))
        assertEquals(4, CodyEditApplierLogic.computeOffset(content, Position(1, 0)))
        assertEquals(5, CodyEditApplierLogic.computeOffset(content, Position(1, 1)))
        assertEquals(8, CodyEditApplierLogic.computeOffset(content, Position(2, 0)))
    }
}
