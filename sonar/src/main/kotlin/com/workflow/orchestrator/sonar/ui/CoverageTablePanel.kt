package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.sonar.model.FileCoverageData
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class CoverageTablePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val tableModel = CoverageTableModel()
    private val table = JBTable(tableModel).apply {
        autoCreateRowSorter = true
        setShowGrid(false)
        rowHeight = 24
    }

    private val paginationWarning = JBLabel().apply {
        foreground = JBColor(java.awt.Color(0xB0, 0x6D, 0x00), java.awt.Color(0xFA, 0xB3, 0x87))
        font = font.deriveFont(java.awt.Font.ITALIC, JBUI.scale(10).toFloat())
        border = JBUI.Borders.empty(2, 8)
        isVisible = false
    }

    private val emptyLabel = JBLabel("No coverage data available. Configure SonarQube project key in Settings > CI/CD.").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        horizontalAlignment = javax.swing.SwingConstants.CENTER
        verticalAlignment = javax.swing.SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(40)
    }

    private val scrollPane = JBScrollPane(table)

    init {
        border = JBUI.Borders.empty(8)
        add(scrollPane, BorderLayout.CENTER)

        // File name column renderer with tooltip showing full path
        table.columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                val modelRow = table.convertRowIndexToModel(row)
                toolTipText = tableModel.getFilePath(modelRow)
                return comp
            }
        }

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = table.selectedRow
                    if (row >= 0) {
                        val modelRow = table.convertRowIndexToModel(row)
                        val filePath = tableModel.getFilePath(modelRow)
                        navigateToFile(filePath)
                    }
                }
            }
        })
    }

    fun update(fileCoverage: Map<String, FileCoverageData>, newCodeMode: Boolean = false, totalFileCount: Int? = null) {
        val data = fileCoverage.values.toList().sortedBy {
            if (newCodeMode) it.newCoverage ?: 0.0 else it.lineCoverage
        }

        // Preserve selected row
        val selectedRow = table.selectedRow
        val selectedFilePath = if (selectedRow >= 0) {
            tableModel.getFilePath(table.convertRowIndexToModel(selectedRow))
        } else null

        tableModel.setData(data, newCodeMode)

        // Show pagination warning when file count hits the page size limit
        if (data.size >= 500 && totalFileCount != null && totalFileCount >= 500) {
            paginationWarning.text = "\u26A0 Showing first 500 files. More may exist."
            paginationWarning.isVisible = true
        } else {
            paginationWarning.isVisible = false
        }

        removeAll()
        if (data.isEmpty()) {
            add(emptyLabel, BorderLayout.CENTER)
        } else {
            val contentPanel = JPanel(BorderLayout()).apply {
                add(paginationWarning, BorderLayout.NORTH)
                add(scrollPane, BorderLayout.CENTER)
            }
            add(contentPanel, BorderLayout.CENTER)
        }

        // Restore selection by file path
        if (selectedFilePath != null) {
            for (viewRow in 0 until table.rowCount) {
                val modelRow = table.convertRowIndexToModel(viewRow)
                if (tableModel.getFilePath(modelRow) == selectedFilePath) {
                    table.setRowSelectionInterval(viewRow, viewRow)
                    break
                }
            }
        }

        revalidate()
        repaint()
    }

    private fun navigateToFile(filePath: String) {
        val basePath = project.basePath ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(java.io.File(basePath, filePath).path) ?: return
        OpenFileDescriptor(project, vf, 0, 0).navigate(true)
    }
}

private class CoverageTableModel : AbstractTableModel() {
    private var data: List<FileCoverageData> = emptyList()
    private var newCodeMode: Boolean = false

    private val overallColumns = arrayOf("File", "Line %", "Branch %", "Uncovered Lines", "Uncovered Conditions")
    private val newCodeColumns = arrayOf("File", "New Coverage %", "New Branch %", "New Uncov. Lines", "New Lines")

    fun setData(newData: List<FileCoverageData>, isNewCode: Boolean) {
        val modeChanged = newCodeMode != isNewCode
        data = newData
        newCodeMode = isNewCode
        if (modeChanged) {
            // Column names changed — must rebuild structure (resets sort/column widths)
            fireTableStructureChanged()
        } else {
            // Same columns — preserve sort state and column widths
            fireTableDataChanged()
        }
    }

    fun getFilePath(row: Int): String = data[row].filePath

    override fun getRowCount() = data.size
    override fun getColumnCount() = if (newCodeMode) newCodeColumns.size else overallColumns.size
    override fun getColumnName(col: Int) = if (newCodeMode) newCodeColumns[col] else overallColumns[col]

    override fun getValueAt(row: Int, col: Int): Any {
        val file = data[row]
        return if (newCodeMode) {
            when (col) {
                0 -> java.io.File(file.filePath).name
                1 -> "%.1f%%".format(file.newCoverage ?: 0.0)
                2 -> "%.1f%%".format(file.newBranchCoverage ?: 0.0)
                3 -> file.newUncoveredLines ?: 0
                4 -> file.newLinesToCover ?: 0
                else -> ""
            }
        } else {
            when (col) {
                0 -> java.io.File(file.filePath).name
                1 -> "%.1f%%".format(file.lineCoverage)
                2 -> "%.1f%%".format(file.branchCoverage)
                3 -> file.uncoveredLines
                4 -> file.uncoveredConditions
                else -> ""
            }
        }
    }

    override fun getColumnClass(col: Int): Class<*> = when (col) {
        3, 4 -> Int::class.java
        else -> String::class.java
    }
}
