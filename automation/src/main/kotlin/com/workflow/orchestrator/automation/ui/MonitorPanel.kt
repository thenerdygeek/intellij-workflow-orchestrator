package com.workflow.orchestrator.automation.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.ui.ClipboardUtil
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.polling.SmartPoller
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Unified monitor panel showing all automation suite runs.
 * Left: compact run list. Right: selected run detail.
 */
class MonitorPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val log = Logger.getInstance(MonitorPanel::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings = PluginSettings.getInstance(project)

    private val runListModel = DefaultListModel<RunEntry>()
    private val runList = javax.swing.JList(runListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = RunListCellRenderer()
    }

    private val detailPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(12)
    }

    private var poller: SmartPoller? = null

    data class RunEntry(
        val suiteName: String,
        val planKey: String,
        val resultKey: String,
        val buildNumber: Int = 0,
        val status: String = "Triggered",
        val stages: List<StageInfo> = emptyList(),
        val totalTests: Int = 0,
        val failedTests: Int = 0,
        val failedTestNames: List<String> = emptyList(),
        val duration: String = "",
        val bambooUrl: String = ""
    )

    data class StageInfo(
        val name: String,
        val state: String,
        val duration: String = ""
    )

    init {
        val splitter = com.intellij.ui.JBSplitter(false, 0.3f).apply {
            setSplitterProportionKey("workflow.automation.monitor.splitter")
            firstComponent = JBScrollPane(runList).apply { border = JBUI.Borders.empty() }
            secondComponent = JBScrollPane(detailPanel).apply { border = JBUI.Borders.empty() }
        }
        add(splitter, BorderLayout.CENTER)

        runList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = runList.selectedValue
                if (selected != null) showRunDetail(selected)
            }
        }

        showEmptyState()

        // Wire visibility to SmartPoller so polling pauses when tab is not visible
        addAncestorListener(object : javax.swing.event.AncestorListener {
            override fun ancestorAdded(event: javax.swing.event.AncestorEvent) {
                poller?.setVisible(true)
            }
            override fun ancestorRemoved(event: javax.swing.event.AncestorEvent) {
                poller?.setVisible(false)
            }
            override fun ancestorMoved(event: javax.swing.event.AncestorEvent) {}
        })
    }

    fun addRun(suitePlanKey: String, resultKey: String) {
        val suiteName = com.workflow.orchestrator.automation.service.AutomationSettingsService
            .getInstance().getSuiteConfig(suitePlanKey)?.displayName ?: suitePlanKey

        val entry = RunEntry(
            suiteName = suiteName,
            planKey = suitePlanKey,
            resultKey = resultKey,
            status = "Triggered"
        )
        runListModel.add(0, entry)
        runList.selectedIndex = 0

        startPolling()
    }

    private fun startPolling() {
        poller?.stop()
        poller = SmartPoller(
            name = "AutomationMonitor",
            baseIntervalMs = 15_000,
            scope = scope
        ) {
            val hadActive = hasActiveRuns()
            pollAllRuns()
            hadActive || hasActiveRuns() // only report change when there are active runs
        }.also { it.start() }
    }

    private fun hasActiveRuns(): Boolean {
        for (i in 0 until runListModel.size()) {
            if (runListModel.getElementAt(i).status !in TERMINAL_STATUSES) return true
        }
        return false
    }

    private fun formatDuration(durationSeconds: Long): String =
        if (durationSeconds > 0) "${durationSeconds / 60}m ${durationSeconds % 60}s" else ""

    private suspend fun pollAllRuns() {
        val bambooService = project.getService(BambooService::class.java) ?: return

        for (i in 0 until runListModel.size()) {
            val entry = runListModel.getElementAt(i)
            if (entry.status in TERMINAL_STATUSES) continue

            try {
                val result = bambooService.getBuild(entry.resultKey)
                if (!result.isError) {
                    val buildData = result.data
                    val stages = buildData.stages.map { stage ->
                        StageInfo(
                            name = stage.name,
                            state = stage.state,
                            duration = formatDuration(stage.durationSeconds)
                        )
                    }

                    val bambooUrl = settings.connections.bambooUrl.orEmpty().trimEnd('/')
                    val updated = entry.copy(
                        buildNumber = buildData.buildNumber,
                        status = buildData.state,
                        stages = stages,
                        duration = formatDuration(buildData.durationSeconds),
                        bambooUrl = "$bambooUrl/browse/${entry.resultKey}"
                    )

                    // Fetch test results if build is finished
                    if (buildData.state in TERMINAL_STATUSES) {
                        val testResult = bambooService.getTestResults(entry.resultKey)
                        if (!testResult.isError) {
                            val testData = testResult.data
                            val failedNames = testData.failedTests.map {
                                "${it.className.substringAfterLast('.')}.${it.methodName}"
                            }
                            val withTests = updated.copy(
                                totalTests = testData.total,
                                failedTests = testData.failed,
                                failedTestNames = failedNames
                            )
                            invokeLater {
                                runListModel.set(i, withTests)
                                if (runList.selectedIndex == i) showRunDetail(withTests)
                            }
                            continue
                        }
                    }

                    invokeLater {
                        runListModel.set(i, updated)
                        if (runList.selectedIndex == i) showRunDetail(updated)
                    }
                }
            } catch (e: Exception) {
                log.warn("[Automation:Monitor] Poll failed for ${entry.resultKey}: ${e.message}")
            }
        }
    }

    private fun showRunDetail(entry: RunEntry) {
        detailPanel.removeAll()

        // Header
        val headerPanel = JPanel(BorderLayout()).apply {
            val statusColor = when (entry.status.lowercase()) {
                "successful" -> StatusColors.SUCCESS
                "failed" -> StatusColors.ERROR
                else -> StatusColors.LINK
            }
            val statusIcon = when (entry.status.lowercase()) {
                "successful" -> "✓"
                "failed" -> if (entry.failedTests > 0) "⚠" else "✗"
                else -> "⟳"
            }

            val headerRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(JBLabel("$statusIcon ${entry.suiteName}").apply {
                    font = JBUI.Fonts.label().deriveFont(Font.BOLD)
                    foreground = statusColor
                })
                // Stitch: monospace build number
                add(JBLabel("#${entry.buildNumber}").apply {
                    font = Font(Font.MONOSPACED, Font.BOLD, JBUI.Fonts.label().size)
                    foreground = statusColor
                })
            }
            add(headerRow, BorderLayout.WEST)

            val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
            if (entry.bambooUrl.isNotBlank()) {
                actionsPanel.add(JButton("Open in Bamboo ↗").apply {
                    isBorderPainted = false
                    foreground = StatusColors.LINK
                    addActionListener { BrowserUtil.browse(entry.bambooUrl) }
                })
            }
            if (entry.status.lowercase() in listOf("failed", "successful")) {
                actionsPanel.add(JButton("Copy Results").apply {
                    addActionListener { copyResultsToClipboard(entry) }
                })
            }
            add(actionsPanel, BorderLayout.EAST)
        }
        detailPanel.add(headerPanel, BorderLayout.NORTH)

        // Content
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyTop(8)
        }

        // Status + duration
        content.add(JBLabel("${entry.status} • ${entry.duration}").apply {
            foreground = StatusColors.SECONDARY_TEXT
        })
        content.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Stitch: uppercase test summary header
        if (entry.totalTests > 0) {
            content.add(JBLabel("TESTS").apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                border = JBUI.Borders.emptyBottom(2)
            })
            val testSummary = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0))
            testSummary.add(JBLabel("✓ ${entry.totalTests - entry.failedTests} passed").apply {
                foreground = StatusColors.SUCCESS
            })
            if (entry.failedTests > 0) {
                testSummary.add(JBLabel("✗ ${entry.failedTests} failed").apply {
                    foreground = StatusColors.ERROR
                })
            }
            content.add(testSummary)
            content.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        // Stitch: stages section header uppercase
        if (entry.stages.isNotEmpty()) {
            content.add(JBLabel("STAGES").apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                border = JBUI.Borders.emptyBottom(4)
            })
            val stagesPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2)))
            for (stage in entry.stages) {
                // Stitch: outline-style stage chips with sharp corners (2px radius)
                val stageColor = when (stage.state.lowercase()) {
                    "successful" -> StatusColors.SUCCESS
                    "failed" -> StatusColors.ERROR
                    else -> StatusColors.LINK
                }
                val icon = when (stage.state.lowercase()) {
                    "successful" -> "\u2713"
                    "failed" -> "\u2717"
                    "inprogress" -> "\u27F3"
                    else -> "\u25CB"
                }
                stagesPanel.add(object : JBLabel("$icon ${stage.name} ${stage.duration}") {
                    override fun paintComponent(g: Graphics) {
                        val g2 = g.create() as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = stageColor
                        g2.draw(RoundRectangle2D.Float(
                            0.5f, 0.5f, width - 1f, height - 1f,
                            JBUI.scale(2).toFloat(), JBUI.scale(2).toFloat()
                        ))
                        g2.dispose()
                        super.paintComponent(g)
                    }
                }.apply {
                    border = JBUI.Borders.empty(2, 6)
                    isOpaque = false
                    foreground = stageColor
                })
            }
            content.add(stagesPanel)
            content.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        // Failed test names
        if (entry.failedTestNames.isNotEmpty()) {
            content.add(JBLabel("FAILED TESTS (${entry.failedTests})").apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
            })
            content.add(Box.createVerticalStrut(JBUI.scale(4)))
            for (testName in entry.failedTestNames.take(20)) {
                content.add(JBLabel("  \u2717 $testName").apply {
                    foreground = StatusColors.ERROR
                    font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))
                })
            }
            if (entry.failedTestNames.size > 20) {
                content.add(JBLabel("  ... and ${entry.failedTestNames.size - 20} more").apply {
                    foreground = StatusColors.SECONDARY_TEXT
                })
            }
        }

        detailPanel.add(JBScrollPane(content).apply { border = null }, BorderLayout.CENTER)
        detailPanel.revalidate()
        detailPanel.repaint()
    }

    private fun showEmptyState() {
        detailPanel.removeAll()
        detailPanel.add(JBLabel("No runs yet. Trigger a suite from the Configure tab.").apply {
            horizontalAlignment = SwingConstants.CENTER
            foreground = StatusColors.SECONDARY_TEXT
        }, BorderLayout.CENTER)
    }

    private fun copyResultsToClipboard(entry: RunEntry) {
        val text = buildString {
            appendLine("Suite: ${entry.suiteName} #${entry.buildNumber}")
            appendLine("Status: ${entry.status}")
            appendLine("Duration: ${entry.duration}")
            appendLine("Tests: ${entry.totalTests - entry.failedTests} passed, ${entry.failedTests} failed")
            if (entry.bambooUrl.isNotBlank()) appendLine("Bamboo: ${entry.bambooUrl}")
            if (entry.failedTestNames.isNotEmpty()) {
                appendLine("\nFailed tests:")
                entry.failedTestNames.forEach { appendLine("  - $it") }
            }
        }
        ClipboardUtil.copyToClipboard(text)
        log.info("[Automation:Monitor] Results copied to clipboard")
    }

    override fun dispose() {
        poller?.stop()
        scope.cancel()
    }

    private companion object {
        private val TERMINAL_STATUSES = setOf("Successful", "Failed")
    }

    private class RunListCellRenderer : ListCellRenderer<RunEntry> {

        // Cached top-level panel with left border accent — owns its own statusColor
        private val panel = object : JPanel(BorderLayout()) {
            var statusColor: Color = StatusColors.LINK

            init {
                isOpaque = true
                border = JBUI.Borders.empty(6, 8, 6, 6)
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                com.workflow.orchestrator.core.ui.RenderingUtils.applyDesktopHints(g2)
                g2.color = statusColor
                g2.fillRect(0, 0, JBUI.scale(3), height)
                g2.dispose()
            }
        }

        // Cached labels
        private val nameLabel = JBLabel().apply {
            font = font.deriveFont(Font.BOLD)
        }
        private val buildLabel = JBLabel().apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))
        }

        // Outline status badge — owns its own badgeColor
        private val statusBadge = object : JBLabel() {
            var badgeColor: Color = StatusColors.LINK

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                com.workflow.orchestrator.core.ui.RenderingUtils.applyDesktopHints(g2)
                g2.color = badgeColor
                g2.draw(RoundRectangle2D.Float(
                    0.5f, 0.5f, width - 1f, height - 1f,
                    JBUI.scale(2).toFloat(), JBUI.scale(2).toFloat()
                ))
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            font = font.deriveFont(JBUI.scale(10).toFloat())
            border = JBUI.Borders.empty(1, 4)
            isOpaque = false
        }

        private val failedLabel = JBLabel().apply {
            foreground = StatusColors.ERROR
            font = font.deriveFont(JBUI.scale(10).toFloat())
        }

        // Cached empty fallback
        private val emptyLabel = JBLabel("")

        // Cached layout panels
        private val topRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
        }
        private val bottomRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
        }
        private val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        init {
            topRow.add(nameLabel)
            topRow.add(buildLabel)
            bottomRow.add(statusBadge)
            bottomRow.add(failedLabel)
            content.add(topRow)
            content.add(bottomRow)
            panel.add(content, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out RunEntry>, value: RunEntry?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): java.awt.Component {
            val entry = value ?: return emptyLabel

            val statusColor = when (entry.status.lowercase()) {
                "successful" -> StatusColors.SUCCESS
                "failed" -> StatusColors.ERROR
                else -> StatusColors.LINK
            }
            panel.statusColor = statusColor
            statusBadge.badgeColor = statusColor

            // Background: selection or alternating row tonal shift
            panel.background = if (isSelected) list.selectionBackground else {
                if (index % 2 == 0) list.background else StatusColors.CARD_BG
            }

            // Suite name
            nameLabel.text = entry.suiteName
            nameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

            // Stitch: monospace build number
            buildLabel.text = "#${entry.buildNumber}"
            buildLabel.foreground = if (isSelected) list.selectionForeground else StatusColors.SECONDARY_TEXT

            // Status badge text and color
            statusBadge.text = entry.status
            statusBadge.foreground = statusColor

            // Failed test count
            if (entry.failedTests > 0) {
                failedLabel.text = "${entry.failedTests} failed"
                failedLabel.isVisible = true
            } else {
                failedLabel.isVisible = false
            }

            return panel
        }
    }
}
