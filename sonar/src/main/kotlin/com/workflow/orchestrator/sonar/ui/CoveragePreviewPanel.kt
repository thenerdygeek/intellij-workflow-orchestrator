package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.sonar.model.FileCoverageData
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import com.workflow.orchestrator.sonar.service.SonarDataService
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants

/**
 * Panel showing uncovered code regions for a selected file.
 * Displays a metrics header, code preview with coverage markers, and a footer with navigation.
 */
class CoveragePreviewPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val metricsLabel = JBLabel().apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
        border = JBUI.Borders.empty(4, 8)
    }

    private val codeArea = JTextArea().apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(11))
        border = JBUI.Borders.empty(4, 8)
    }

    private val filePathLabel = JBLabel().apply {
        foreground = StatusColors.SECONDARY_TEXT
        border = JBUI.Borders.empty(2, 8)
    }

    private val openInEditorButton = JButton("Open in Editor").apply {
        isEnabled = false
    }

    private val emptyLabel = JBLabel("Select a file to preview coverage").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(20)
    }

    private var currentFilePath: String? = null

    init {
        border = JBUI.Borders.empty(4)

        openInEditorButton.addActionListener {
            currentFilePath?.let { navigateToFile(it) }
        }

        showEmptyState()
    }

    /**
     * Load and display coverage preview for the given file.
     */
    fun showFile(filePath: String, coverageData: FileCoverageData) {
        currentFilePath = filePath

        // Update metrics header
        val metricsText = buildString {
            append("Line: %.1f%%".format(coverageData.lineCoverage))
            append(" | Branch: %.1f%%".format(coverageData.branchCoverage))
            append(" | Uncovered: ${coverageData.uncoveredLines} lines, ${coverageData.uncoveredConditions} conds")
            if (coverageData.complexity > 0) append(" | Complexity: ${coverageData.complexity}")
            if (coverageData.cognitiveComplexity > 0) append(" | Cognitive: ${coverageData.cognitiveComplexity}")
        }
        metricsLabel.text = metricsText

        // Update footer
        filePathLabel.text = filePath
        openInEditorButton.isEnabled = true

        // Fetch line coverage asynchronously and render regions
        scope.launch {
            val lineStatuses = if (coverageData.lineStatuses.isNotEmpty()) {
                coverageData.lineStatuses
            } else {
                SonarDataService.getInstance(project).getLineCoverage(filePath)
            }

            // Read file content
            val basePath = project.basePath ?: return@launch
            val file = java.io.File(basePath, filePath)
            if (!file.exists()) {
                withContext(Dispatchers.Main) {
                    codeArea.text = "File not found: $filePath"
                    showContentLayout()
                }
                return@launch
            }

            val lines = file.readLines()
            val regions = extractUncoveredRegions(lines, lineStatuses)

            val displayText = if (regions.isEmpty()) {
                "All lines covered — no uncovered regions to display."
            } else {
                buildRegionDisplay(regions)
            }

            withContext(Dispatchers.Main) {
                codeArea.text = displayText
                codeArea.caretPosition = 0
                showContentLayout()
            }
        }
    }

    /**
     * Show the empty state when no file is selected.
     */
    fun showEmptyState() {
        currentFilePath = null
        openInEditorButton.isEnabled = false
        removeAll()
        add(emptyLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun showContentLayout() {
        removeAll()
        add(metricsLabel, BorderLayout.NORTH)
        add(JBScrollPane(codeArea), BorderLayout.CENTER)

        val footerPanel = JPanel(BorderLayout()).apply {
            add(filePathLabel, BorderLayout.CENTER)
            add(openInEditorButton, BorderLayout.EAST)
        }
        add(footerPanel, BorderLayout.SOUTH)

        revalidate()
        repaint()
    }

    private fun buildRegionDisplay(regions: List<CoverageRegion>): String {
        return buildString {
            regions.forEachIndexed { index, region ->
                if (index > 0) {
                    appendLine("--- gap ---")
                }
                for (line in region.lines) {
                    val marker = when (line.status) {
                        LineCoverageStatus.UNCOVERED -> "\u2716"
                        LineCoverageStatus.PARTIAL -> "~"
                        LineCoverageStatus.COVERED -> "\u2713"
                        null -> " "
                    }
                    appendLine("%s %4d | %s".format(marker, line.lineNumber, line.text))
                }
            }
        }.trimEnd()
    }

    private fun navigateToFile(filePath: String) {
        val basePath = project.basePath ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(java.io.File(basePath, filePath).path) ?: return
        OpenFileDescriptor(project, vf, 0, 0).navigate(true)
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        /**
         * Extract uncovered/partial regions with surrounding context lines.
         *
         * Algorithm:
         * 1. Find all uncovered/partial line numbers from statuses
         * 2. Sort them
         * 3. For each, create a range [line - contextLines, line + contextLines] clamped to [1, lines.size]
         * 4. Merge overlapping ranges
         * 5. Return list of CoverageRegion with line text and status
         */
        fun extractUncoveredRegions(
            lines: List<String>,
            statuses: Map<Int, LineCoverageStatus>,
            contextLines: Int = 3
        ): List<CoverageRegion> {
            if (lines.isEmpty()) return emptyList()

            // Find all uncovered or partial lines (1-based line numbers)
            val uncoveredLines = statuses.entries
                .filter { it.value == LineCoverageStatus.UNCOVERED || it.value == LineCoverageStatus.PARTIAL }
                .map { it.key }
                .filter { it in 1..lines.size }
                .sorted()

            if (uncoveredLines.isEmpty()) return emptyList()

            // Build ranges with context, clamped to file boundaries
            val ranges = uncoveredLines.map { lineNum ->
                val start = (lineNum - contextLines).coerceAtLeast(1)
                val end = (lineNum + contextLines).coerceAtMost(lines.size)
                start to end
            }

            // Merge overlapping/adjacent ranges
            val merged = mutableListOf<Pair<Int, Int>>()
            var currentRange = ranges[0]
            for (i in 1 until ranges.size) {
                val next = ranges[i]
                if (next.first <= currentRange.second + 1) {
                    // Overlapping or adjacent — merge
                    currentRange = currentRange.first to maxOf(currentRange.second, next.second)
                } else {
                    merged.add(currentRange)
                    currentRange = next
                }
            }
            merged.add(currentRange)

            // Build regions with line data
            return merged.map { (start, end) ->
                val regionLines = (start..end).map { lineNum ->
                    CoverageRegionLine(
                        lineNumber = lineNum,
                        text = lines[lineNum - 1], // lines is 0-based
                        status = statuses[lineNum]
                    )
                }
                CoverageRegion(regionLines)
            }
        }
    }
}

/**
 * A contiguous region of code lines (uncovered + context) for preview display.
 */
data class CoverageRegion(val lines: List<CoverageRegionLine>)

/**
 * A single line within a coverage region.
 */
data class CoverageRegionLine(
    val lineNumber: Int,
    val text: String,
    val status: LineCoverageStatus?
)
