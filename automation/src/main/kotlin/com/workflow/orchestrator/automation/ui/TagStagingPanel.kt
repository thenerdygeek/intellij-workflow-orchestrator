package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.model.RegistryStatus
import com.workflow.orchestrator.automation.model.TagEntry
import com.workflow.orchestrator.automation.model.TagSource
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class TagStagingPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val tableModel = TagTableModel()
    private val table = JBTable(tableModel)

    init {
        border = JBUI.Borders.emptyTop(4)

        table.apply {
            setShowGrid(false)
            intercellSpacing = JBUI.size(0, 0)
            rowHeight = JBUI.scale(28)
            columnModel.getColumn(0).preferredWidth = JBUI.scale(150)
            columnModel.getColumn(1).preferredWidth = JBUI.scale(250)
            columnModel.getColumn(2).preferredWidth = JBUI.scale(100)
            columnModel.getColumn(3).preferredWidth = JBUI.scale(80)
            columnModel.getColumn(4).preferredWidth = JBUI.scale(100)

            columnModel.getColumn(1).cellEditor = DefaultCellEditor(JTextField())
            setDefaultRenderer(Any::class.java, TagTableCellRenderer())
        }

        add(JBLabel("Docker Tags").apply {
            border = JBUI.Borders.emptyBottom(4)
            font = font.deriveFont(font.size + 1f)
        }, BorderLayout.NORTH)

        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    fun setEntries(entries: List<TagEntry>) {
        tableModel.entries = entries
        tableModel.fireTableDataChanged()
    }

    fun getEntries(): List<TagEntry> = tableModel.entries

    /** Alias for setEntries — used by AutomationPanel. */
    fun updateTags(tags: List<TagEntry>) = setEntries(tags)

    /** Get current tag entries (may have user edits). */
    fun getCurrentTags(): List<TagEntry> = getEntries()

    override fun dispose() {}

    private class TagTableModel : AbstractTableModel() {
        var entries: List<TagEntry> = emptyList()

        private val columns = arrayOf("Service", "Docker Tag", "Latest", "Registry", "Status")

        override fun getRowCount() = entries.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(col: Int) = columns[col]

        override fun getValueAt(row: Int, col: Int): Any {
            val entry = entries[row]
            return when (col) {
                0 -> entry.serviceName
                1 -> entry.currentTag
                2 -> entry.latestReleaseTag ?: ""
                3 -> when (entry.registryStatus) {
                    RegistryStatus.VALID -> "\u2713"
                    RegistryStatus.NOT_FOUND -> "\u2717"
                    RegistryStatus.CHECKING -> "..."
                    RegistryStatus.UNKNOWN -> ""
                    RegistryStatus.ERROR -> "!"
                }
                4 -> when {
                    entry.isCurrentRepo -> "Your branch"
                    entry.isDrift -> "\u26A0 Drift"
                    entry.registryStatus == RegistryStatus.VALID -> "\u2713 OK"
                    entry.registryStatus == RegistryStatus.NOT_FOUND -> "\u2717 Missing"
                    else -> ""
                }
                else -> ""
            }
        }

        override fun isCellEditable(row: Int, col: Int) = col == 1

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            if (col == 1 && value is String) {
                entries = entries.toMutableList().apply {
                    this[row] = this[row].copy(
                        currentTag = value,
                        source = TagSource.USER_EDIT,
                        registryStatus = RegistryStatus.UNKNOWN
                    )
                }
                fireTableRowsUpdated(row, row)
            }
        }
    }

    private class TagTableCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val component = super.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, col
            )
            if (!isSelected) {
                val model = table.model as TagTableModel
                if (row < model.entries.size) {
                    val entry = model.entries[row]
                    background = when {
                        entry.isCurrentRepo -> StatusColors.SUCCESS_BG
                        entry.isDrift -> StatusColors.WARNING_BG
                        entry.registryStatus == RegistryStatus.NOT_FOUND -> JBColor(ColorUtil.withAlpha(StatusColors.ERROR, 0.1), ColorUtil.withAlpha(StatusColors.ERROR, 0.1))
                        else -> table.background
                    }
                }
            }
            return component
        }
    }
}
