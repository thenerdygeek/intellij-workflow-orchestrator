package com.workflow.orchestrator.agent.settings

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.swing.table.AbstractTableModel

/**
 * Swing table model for per-model max-input-token overrides. Pure data + JSON (de)serialization so the
 * round-trip is unit-testable without the Configurable. Column 2 (Override) editable; 0 = "use model default".
 */
class MaxTokenOverrideTableModel : AbstractTableModel() {

    data class Row(val modelId: String, val real: Int?, var override: Int) {
        fun aboveCatalog(): Boolean = real != null && override > real
    }

    private val rows = mutableListOf<Row>()
    private val columns = arrayOf("Model", "Real (catalog)", "Override (0 = default)")

    fun setRows(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        fireTableDataChanged()
    }
    fun rows(): List<Row> = rows.toList()
    fun rowAt(modelId: String): Row = rows.first { it.modelId == modelId }
    fun anyAboveCatalog(): Boolean = rows.any { it.aboveCatalog() }

    fun seed(modelReals: Map<String, Int?>, overridesJson: String?) {
        val ov: Map<String, Int> = try {
            Json.decodeFromString<Map<String, Int>>(overridesJson ?: "{}")
        } catch (_: Exception) { emptyMap() }
        setRows(modelReals.map { (id, real) -> Row(id, real, ov[id] ?: 0) })
    }

    fun toJson(): String {
        val map: Map<String, Int> = rows.filter { it.override > 0 }.associate { it.modelId to it.override }
        return Json.encodeToString(MapSerializer(String.serializer(), Int.serializer()), map)
    }

    override fun getRowCount() = rows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(c: Int) = columns[c]
    override fun isCellEditable(r: Int, c: Int) = c == 2
    override fun getColumnClass(c: Int): Class<*> = if (c == 1 || c == 2) Integer::class.java else String::class.java
    override fun getValueAt(r: Int, c: Int): Any = when (c) {
        0 -> rows[r].modelId
        1 -> rows[r].real ?: -1
        else -> rows[r].override
    }
    override fun setValueAt(value: Any?, r: Int, c: Int) {
        if (c == 2) {
            rows[r].override = (value as? Int ?: (value as? String)?.toIntOrNull() ?: 0).coerceAtLeast(0)
            fireTableRowsUpdated(r, r)
        }
    }
}
