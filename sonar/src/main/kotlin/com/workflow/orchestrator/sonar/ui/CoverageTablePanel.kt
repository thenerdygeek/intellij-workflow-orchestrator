package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.sonar.model.FileCoverageData
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

class CoverageTablePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val tableModel = CoverageTableModel()
    private val table = JBTable(tableModel).apply {
        autoCreateRowSorter = true
        setShowGrid(false)
        rowHeight = 24
    }

    init {
        border = JBUI.Borders.empty(8)
        add(JBScrollPane(table), BorderLayout.CENTER)

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

    fun update(fileCoverage: Map<String, FileCoverageData>) {
        tableModel.setData(fileCoverage.values.toList().sortedBy { it.lineCoverage })
    }

    private fun navigateToFile(filePath: String) {
        val basePath = project.basePath ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(java.io.File(basePath, filePath).path) ?: return
        OpenFileDescriptor(project, vf, 0, 0).navigate(true)
    }
}

private class CoverageTableModel : AbstractTableModel() {
    private var data: List<FileCoverageData> = emptyList()
    private val columns = arrayOf("File", "Line %", "Branch %", "Uncovered Lines", "Uncovered Conditions")

    fun setData(newData: List<FileCoverageData>) {
        data = newData
        fireTableDataChanged()
    }

    fun getFilePath(row: Int): String = data[row].filePath

    override fun getRowCount() = data.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(col: Int) = columns[col]

    override fun getValueAt(row: Int, col: Int): Any {
        val file = data[row]
        return when (col) {
            0 -> java.io.File(file.filePath).name
            1 -> "%.1f%%".format(file.lineCoverage)
            2 -> "%.1f%%".format(file.branchCoverage)
            3 -> file.uncoveredLines
            4 -> file.uncoveredConditions
            else -> ""
        }
    }

    override fun getColumnClass(col: Int): Class<*> = when (col) {
        3, 4 -> Int::class.java
        else -> String::class.java
    }
}
