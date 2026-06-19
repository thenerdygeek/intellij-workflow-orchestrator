package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.sonar.model.FileCoverageData
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** B11 / P2-20: delay between the last keystroke and the table re-filter. */
private const val SEARCH_DEBOUNCE_MS = 300

class CoverageTablePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

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

    private val emptyLabel = JBLabel().apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        horizontalAlignment = javax.swing.SwingConstants.CENTER
        verticalAlignment = javax.swing.SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(40)
    }

    private val scrollPane = JBScrollPane(table)

    private val summaryLabel = JBLabel().apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
        border = JBUI.Borders.empty(4, 8)
    }

    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Filter files..."
    }

    private val previewPanel = CoveragePreviewPanel(project)

    private val splitter = JBSplitter(true, 0.6f).apply {
        dividerWidth = 3
    }

    private var allCoverageData: List<FileCoverageData> = emptyList()
    private var currentNewCodeMode: Boolean = false

    /**
     * B11 / P2-20: debounce the search field so that rapid keystrokes do not
     * trigger a full model rebuild per keystroke.  300 ms matches the
     * [com.intellij.openapi.util.registry.Registry] IDE debounce convention.
     */
    private val searchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** Cell renderer that color-codes complexity values: >threshold1 orange, >threshold2 red. */
    private class ComplexityCellRenderer(
        private val orangeThreshold: Int,
        private val redThreshold: Int
    ) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (!isSelected) {
                val intValue = (value as? Int) ?: 0
                foreground = when {
                    intValue > redThreshold -> StatusColors.ERROR
                    intValue > orangeThreshold -> StatusColors.WARNING
                    else -> table.foreground
                }
            }
            return comp
        }
    }

    init {
        border = JBUI.Borders.empty(8)

        // Cascade dispose to previewPanel (which cancels its IO scope on project close).
        Disposer.register(this, previewPanel)

        // Stitch design: uppercase bold header text in SECONDARY_TEXT color
        table.tableHeader.defaultRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean,
                hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                text = value?.toString()?.uppercase() ?: ""
                font = font.deriveFont(java.awt.Font.BOLD, JBUI.scale(10).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                border = JBUI.Borders.empty(4, 6)
                return comp
            }
        }

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

        // Complexity column renderers (cyclomatic: orange >20, red >50; cognitive: orange >15, red >25)
        applyComplexityRenderers()

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = table.selectedRow
                    if (row >= 0) {
                        val modelRow = table.convertRowIndexToModel(row)
                        val filePath = tableModel.getFilePath(modelRow)
                        val coverageData = tableModel.getFileCoverageData(modelRow)
                        navigateToFile(filePath, coverageData?.projectKey)
                    }
                }
            }
        })

        // Single-click selection -> update preview panel
        table.selectionModel.addListSelectionListener(ListSelectionListener { e ->
            if (e.valueIsAdjusting) return@ListSelectionListener
            val row = table.selectedRow
            if (row >= 0) {
                val modelRow = table.convertRowIndexToModel(row)
                val filePath = tableModel.getFilePath(modelRow)
                val coverageData = tableModel.getFileCoverageData(modelRow)
                if (coverageData != null) {
                    previewPanel.showFile(filePath, coverageData)
                }
            } else {
                previewPanel.showEmptyState()
            }
        })

        // B11 / P2-20: debounce the search field.  Only schedule a filter run
        // 300 ms after the last keystroke; cancel any pending request first so
        // rapid typing collapses into a single rebuild.
        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = scheduleFilter()
            override fun removeUpdate(e: DocumentEvent?) = scheduleFilter()
            override fun changedUpdate(e: DocumentEvent?) = scheduleFilter()

            private fun scheduleFilter() {
                searchAlarm.cancelAllRequests()
                searchAlarm.addRequest({ filterTable() }, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    /** Apply complexity cell renderers to the last two columns. Called after structure changes. */
    private fun applyComplexityRenderers() {
        val colCount = table.columnModel.columnCount
        if (colCount >= 7) {
            // Complexity column (cyclomatic): orange > 20, red > 50
            table.columnModel.getColumn(colCount - 2).cellRenderer = ComplexityCellRenderer(20, 50)
            // Cognitive column: orange > 15, red > 25
            table.columnModel.getColumn(colCount - 1).cellRenderer = ComplexityCellRenderer(15, 25)
        }
    }

    fun update(fileCoverage: Map<String, FileCoverageData>, newCodeMode: Boolean = false, totalFileCount: Int? = null) {
        allCoverageData = fileCoverage.values.toList().sortedBy {
            if (newCodeMode) it.newCoverage ?: 0.0 else it.lineCoverage
        }
        currentNewCodeMode = newCodeMode

        // Apply any active search filter
        val data = filterData(allCoverageData)

        // Preserve selected row
        val selectedRow = table.selectedRow
        val selectedFilePath = if (selectedRow >= 0) {
            tableModel.getFilePath(table.convertRowIndexToModel(selectedRow))
        } else null

        val previousMode = tableModel.isNewCodeMode()
        tableModel.setData(data, newCodeMode)

        // Re-apply renderers when table structure changes (mode switch or first load)
        if (previousMode != newCodeMode) {
            // Re-apply file name tooltip renderer after structure change
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
            applyComplexityRenderers()
        }

        // Show pagination warning when file count hits the page size limit
        if (data.size >= 500 && totalFileCount != null && totalFileCount >= 500) {
            paginationWarning.text = "⚠ Showing first 500 files. More may exist."
            paginationWarning.isVisible = true
        } else {
            paginationWarning.isVisible = false
        }

        // Update summary bar
        updateSummaryLabel(allCoverageData)

        removeAll()
        if (allCoverageData.isEmpty()) {
            emptyLabel.text = if (newCodeMode) {
                "No files in the new-code period. Switch to Overall to see project-wide coverage."
            } else {
                "No coverage data available. Configure SonarQube project key in Settings > CI/CD."
            }
            add(emptyLabel, BorderLayout.CENTER)
        } else {
            // Header panel with summary + search
            val headerPanel = JPanel(BorderLayout()).apply {
                add(summaryLabel, BorderLayout.NORTH)
                add(searchField, BorderLayout.SOUTH)
            }

            // Table content panel
            val tableContentPanel = JPanel(BorderLayout()).apply {
                add(headerPanel, BorderLayout.NORTH)
                add(paginationWarning, BorderLayout.SOUTH)
                add(scrollPane, BorderLayout.CENTER)
            }

            splitter.firstComponent = tableContentPanel
            splitter.secondComponent = previewPanel
            add(splitter, BorderLayout.CENTER)
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

    /**
     * B11 / P2-20: re-filter the table without blanking the preview when the
     * currently selected file is still present after filtering.  Previously this
     * method always called [previewPanel.showEmptyState] and never restored the
     * selection — so the selected row and its preview both disappeared on every
     * keystroke even when the file still matched.
     */
    private fun filterTable() {
        // Snapshot selected file before rebuilding the model.
        val selectedRow = table.selectedRow
        val selectedFilePath = if (selectedRow >= 0) {
            tableModel.getFilePath(table.convertRowIndexToModel(selectedRow))
        } else null

        val filtered = filterData(allCoverageData)
        tableModel.setData(filtered, currentNewCodeMode)

        // Restore selection when the previously selected file is still visible;
        // only blank the preview when the file has been filtered out.
        val visibleFilePaths = (0 until table.rowCount).map { viewRow ->
            tableModel.getFilePath(table.convertRowIndexToModel(viewRow))
        }
        val outcome = CoverageFilterSelection.resolve(selectedFilePath, visibleFilePaths)
        outcome.restoreRow?.let { table.setRowSelectionInterval(it, it) }
        if (outcome.blankPreview) {
            previewPanel.showEmptyState()
        }
    }

    private fun filterData(data: List<FileCoverageData>): List<FileCoverageData> {
        val query = searchField.text.orEmpty().trim().lowercase()
        if (query.isEmpty()) return data
        return data.filter { it.filePath.lowercase().contains(query) }
    }

    private fun updateSummaryLabel(data: List<FileCoverageData>) {
        if (data.isEmpty()) {
            summaryLabel.text = ""
            return
        }
        val avgCoverage = data.map { it.lineCoverage }.average()
        val belowThreshold = data.count { it.lineCoverage < 80.0 }
        summaryLabel.text = "${data.size} files | %.1f%% avg coverage | $belowThreshold files below 80%%".format(avgCoverage)
    }

    private fun navigateToFile(filePath: String, projectKey: String? = null) {
        // SONAR-CLE-7/SONAR-ARC-2: resolve the owning repo root using projectKey so that
        // files in secondary repos (multi-repo projects) are found correctly. Falls back to
        // project.basePath for single-repo setups or when projectKey is not matched.
        val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
        val basePath = projectKey?.let { key ->
            settings.getRepos().firstOrNull { it.sonarProjectKey == key }?.localVcsRootPath
                ?.takeIf { it.isNotBlank() }
        } ?: project.basePath ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(java.io.File(basePath, filePath).path) ?: return
        OpenFileDescriptor(project, vf, 0, 0).navigate(true)
    }

    override fun dispose() {
        // No own resources to clean up — Disposer cascades to registered children (previewPanel).
        // searchAlarm is registered as a child via Alarm(threadToUse, this).
    }
}

/**
 * B11: pure selection-restore decision used by [CoverageTablePanel.filterTable].
 * Extracted so the keystroke-must-not-reset-selection contract is unit-testable.
 */
internal object CoverageFilterSelection {

    /**
     * @property restoreRow view row to re-select, or null when nothing should be selected
     * @property blankPreview true only when a previously selected file is no longer visible
     */
    data class Outcome(val restoreRow: Int?, val blankPreview: Boolean)

    fun resolve(selectedFilePath: String?, visibleFilePaths: List<String>): Outcome {
        if (selectedFilePath == null) return Outcome(restoreRow = null, blankPreview = false)
        val row = visibleFilePaths.indexOf(selectedFilePath)
        return if (row >= 0) {
            Outcome(restoreRow = row, blankPreview = false)
        } else {
            Outcome(restoreRow = null, blankPreview = true)
        }
    }
}

internal class CoverageTableModel : AbstractTableModel() {
    private var data: List<FileCoverageData> = emptyList()
    private var newCodeMode: Boolean = false

    /**
     * P2-20: pre-computed display names to avoid [java.io.File] allocation per
     * cell per paint.  Rebuilt whenever [data] is replaced.
     */
    private var displayNames: Array<String> = emptyArray()

    /**
     * P2-20: pre-computed formatted coverage/count strings.  Each row is a
     * 7-element array of [Any] matching the column layout (col 0 = displayName,
     * cols 1-4 = String, cols 5-6 = Int).  Rebuilt whenever [data] or [newCodeMode]
     * changes.
     */
    private var cachedValues: Array<Array<Any>> = emptyArray()

    private val overallColumns = arrayOf("File", "Line %", "Branch %", "Uncovered Lines", "Uncovered Cond.", "Complexity", "Cognitive")
    private val newCodeColumns = arrayOf("File", "New Coverage %", "New Branch %", "New Uncov. Lines", "New Lines", "Complexity", "Cognitive")

    fun setData(newData: List<FileCoverageData>, isNewCode: Boolean) {
        val modeChanged = newCodeMode != isNewCode

        // P2-20: equality gate — skip fireTableDataChanged when nothing actually changed.
        if (!modeChanged && newData == data) return

        data = newData
        newCodeMode = isNewCode
        rebuildCache()

        if (modeChanged) {
            // Column names changed — must rebuild structure (resets sort/column widths)
            fireTableStructureChanged()
        } else {
            // Same columns — preserve sort state and column widths
            fireTableDataChanged()
        }
    }

    private fun rebuildCache() {
        displayNames = Array(data.size) { i -> java.io.File(data[i].filePath).name }
        cachedValues = Array(data.size) { i ->
            val file = data[i]
            if (newCodeMode) {
                arrayOf(
                    displayNames[i],
                    "%.1f%%".format(file.newCoverage ?: 0.0),
                    "%.1f%%".format(file.newBranchCoverage ?: 0.0),
                    file.newUncoveredLines ?: 0,
                    file.newLinesToCover ?: 0,
                    file.complexity,
                    file.cognitiveComplexity,
                )
            } else {
                arrayOf(
                    displayNames[i],
                    "%.1f%%".format(file.lineCoverage),
                    "%.1f%%".format(file.branchCoverage),
                    file.uncoveredLines,
                    file.uncoveredConditions,
                    file.complexity,
                    file.cognitiveComplexity,
                )
            }
        }
    }

    fun isNewCodeMode(): Boolean = newCodeMode

    fun getFilePath(row: Int): String = data[row].filePath

    fun getFileCoverageData(row: Int): FileCoverageData? = data.getOrNull(row)

    override fun getRowCount() = data.size
    override fun getColumnCount() = if (newCodeMode) newCodeColumns.size else overallColumns.size
    override fun getColumnName(col: Int) = if (newCodeMode) newCodeColumns[col] else overallColumns[col]

    override fun getValueAt(row: Int, col: Int): Any = cachedValues[row][col]

    override fun getColumnClass(col: Int): Class<*> = when (col) {
        3, 4, 5, 6 -> Int::class.java
        else -> String::class.java
    }
}
