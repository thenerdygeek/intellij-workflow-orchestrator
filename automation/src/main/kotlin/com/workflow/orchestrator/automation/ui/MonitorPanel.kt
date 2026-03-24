package com.workflow.orchestrator.automation.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.service.BambooServiceImpl
import com.workflow.orchestrator.bamboo.service.BambooTestResultConverter
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.polling.SmartPoller
import kotlinx.coroutines.*
import kotlinx.coroutines.cancel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
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
            val status = runListModel.getElementAt(i).status
            if (status != "Successful" && status != "Failed") return true
        }
        return false
    }

    private suspend fun pollAllRuns() {
        val apiClient = createApiClient() ?: return

        for (i in 0 until runListModel.size()) {
            val entry = runListModel.getElementAt(i)
            if (entry.status == "Successful" || entry.status == "Failed") continue

            try {
                val result = apiClient.getBuildResult(entry.resultKey)
                if (result is ApiResult.Success) {
                    val dto = result.data
                    val stages = dto.stages.stage.map { stage ->
                        StageInfo(
                            name = stage.name,
                            state = stage.state,
                            duration = if (stage.buildDurationInSeconds > 0) "${stage.buildDurationInSeconds / 60}m ${stage.buildDurationInSeconds % 60}s" else ""
                        )
                    }

                    val bambooUrl = settings.connections.bambooUrl.orEmpty().trimEnd('/')
                    val updated = entry.copy(
                        buildNumber = dto.buildNumber,
                        status = dto.state,
                        stages = stages,
                        totalTests = dto.stages.stage.sumOf { it.results.result.size },
                        duration = "${dto.buildDurationInSeconds / 60}m ${dto.buildDurationInSeconds % 60}s",
                        bambooUrl = "$bambooUrl/browse/${entry.resultKey}"
                    )

                    // Fetch test results if build is finished
                    if (dto.lifeCycleState.equals("Finished", ignoreCase = true)) {
                        val testResult = apiClient.getTestResults(entry.resultKey)
                        if (testResult is ApiResult.Success) {
                            val testData = testResult.data.testResults
                            val failedNames = testData.failedTests.testResult.map {
                                "${it.className.substringAfterLast('.')}.${it.methodName}"
                            }
                            val withTests = updated.copy(
                                totalTests = testData.all,
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

            add(JBLabel("$statusIcon ${entry.suiteName} #${entry.buildNumber}").apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD)
                foreground = statusColor
            }, BorderLayout.WEST)

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

        // Test summary
        if (entry.totalTests > 0) {
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

        // Stages as chips
        if (entry.stages.isNotEmpty()) {
            val stagesPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2)))
            for (stage in entry.stages) {
                val chipColor = when (stage.state.lowercase()) {
                    "successful" -> JBColor(ColorUtil.withAlpha(StatusColors.SUCCESS, 0.2), ColorUtil.withAlpha(StatusColors.SUCCESS, 0.2))
                    "failed" -> JBColor(ColorUtil.withAlpha(StatusColors.ERROR, 0.2), ColorUtil.withAlpha(StatusColors.ERROR, 0.2))
                    else -> JBColor(ColorUtil.withAlpha(StatusColors.LINK, 0.2), ColorUtil.withAlpha(StatusColors.LINK, 0.2))
                }
                val icon = when (stage.state.lowercase()) {
                    "successful" -> "✓"
                    "failed" -> "✗"
                    "inprogress" -> "⟳"
                    else -> "○"
                }
                stagesPanel.add(JBLabel("$icon ${stage.name} ${stage.duration}").apply {
                    border = JBUI.Borders.empty(2, 6)
                    isOpaque = true
                    background = chipColor
                })
            }
            content.add(stagesPanel)
            content.add(Box.createVerticalStrut(JBUI.scale(8)))
        }

        // Failed test names
        if (entry.failedTestNames.isNotEmpty()) {
            content.add(JBLabel("Failed Tests (${entry.failedTests}):").apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD)
                foreground = StatusColors.SECONDARY_TEXT
            })
            content.add(Box.createVerticalStrut(JBUI.scale(4)))
            for (testName in entry.failedTestNames.take(20)) {
                content.add(JBLabel("  ✗ $testName").apply {
                    foreground = StatusColors.ERROR
                    font = font.deriveFont(JBUI.scale(11).toFloat())
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
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
        log.info("[Automation:Monitor] Results copied to clipboard")
    }

    override fun dispose() {
        poller?.stop()
        scope.cancel()
    }

    private fun createApiClient(): BambooApiClient? {
        return BambooServiceImpl.getInstance(project).getApiClient()
    }

    private class RunListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): java.awt.Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val entry = value as? RunEntry ?: return this
            border = JBUI.Borders.empty(4, 8)

            val icon = when (entry.status.lowercase()) {
                "successful" -> "✓"
                "failed" -> "⚠"
                else -> "⟳"
            }
            val statusColor = when (entry.status.lowercase()) {
                "successful" -> StatusColors.SUCCESS
                "failed" -> StatusColors.ERROR
                else -> StatusColors.LINK
            }

            text = "<html><b style='color:${StatusColors.htmlColor(statusColor)};'>$icon ${entry.suiteName}</b>" +
                "<br><span style='color:gray;'>#${entry.buildNumber} • ${entry.status}" +
                (if (entry.failedTests > 0) " • ${entry.failedTests} failed" else "") +
                "</span></html>"
            return this
        }

        private fun colorToHex(c: JBColor): String = StatusColors.htmlColor(c)
    }
}
