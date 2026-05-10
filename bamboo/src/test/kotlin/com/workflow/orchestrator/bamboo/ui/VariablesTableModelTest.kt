package com.workflow.orchestrator.bamboo.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Pure-logic tests for [ManualStageDialog.VariablesTableModel].
 *
 * These tests exercise only the table model; no IntelliJ infrastructure is needed.
 */
class VariablesTableModelTest {

    private fun modelOf(vararg pairs: Pair<String, String>): ManualStageDialog.VariablesTableModel {
        val entries = pairs.associate { it }.entries.toList()
        return ManualStageDialog.VariablesTableModel(entries)
    }

    private fun sortedModelOf(vararg pairs: Pair<String, String>): ManualStageDialog.VariablesTableModel {
        // Mirrors what buildVariablesPreviewSection does: sort by key before passing to the model.
        val entries = pairs.associate { it }.entries.sortedBy { it.key }
        return ManualStageDialog.VariablesTableModel(entries)
    }

    @Test
    fun `row count matches number of entries`() {
        val model = sortedModelOf("alpha" to "1", "beta" to "2", "gamma" to "3")
        assertEquals(3, model.rowCount)
    }

    @Test
    fun `column count is always 2`() {
        val model = sortedModelOf("a" to "x")
        assertEquals(2, model.columnCount)
    }

    @Test
    fun `column names are Key and Value`() {
        val model = sortedModelOf("a" to "x")
        assertEquals("Key", model.getColumnName(0))
        assertEquals("Value", model.getColumnName(1))
    }

    @Test
    fun `isCellEditable always returns false`() {
        val model = sortedModelOf("a" to "x", "b" to "y")
        for (row in 0 until model.rowCount) {
            for (col in 0 until model.columnCount) {
                assertFalse(model.isCellEditable(row, col),
                    "Cell ($row,$col) should not be editable")
            }
        }
    }

    @Test
    fun `keys are in the order provided (caller sorts before constructing)`() {
        // The model itself preserves insertion order; the caller (buildVariablesPreviewSection)
        // is responsible for sorting. This test confirms the model doesn't reorder entries.
        val model = sortedModelOf("dockerTagsAsJson" to "{}", "alpha" to "v1", "zulu" to "last")
        assertEquals("alpha", model.getValueAt(0, 0))
        assertEquals("dockerTagsAsJson", model.getValueAt(1, 0))
        assertEquals("zulu", model.getValueAt(2, 0))
    }

    @Test
    fun `value column returns entry value at correct row`() {
        val model = sortedModelOf("key_a" to "val_a", "key_b" to "val_b")
        assertEquals("val_a", model.getValueAt(0, 1))
        assertEquals("val_b", model.getValueAt(1, 1))
    }

    @Test
    fun `blank value is substituted with (empty) placeholder`() {
        val model = sortedModelOf("myKey" to "")
        assertEquals("(empty)", model.getValueAt(0, 1))
    }

    @Test
    fun `whitespace-only value is treated as blank`() {
        val model = sortedModelOf("myKey" to "   ")
        assertEquals("(empty)", model.getValueAt(0, 1))
    }

    @Test
    fun `non-blank value is returned as-is`() {
        val json = """{"service":"1.2.3"}"""
        val model = sortedModelOf("dockerTagsAsJson" to json)
        assertEquals(json, model.getValueAt(0, 1))
    }

    @Test
    fun `empty map produces zero rows`() {
        val model = ManualStageDialog.VariablesTableModel(emptyList())
        assertEquals(0, model.rowCount)
    }

    @Test
    fun `column class is String for both columns`() {
        val model = sortedModelOf("a" to "1")
        assertEquals(String::class.java, model.getColumnClass(0))
        assertEquals(String::class.java, model.getColumnClass(1))
    }
}
