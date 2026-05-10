package com.workflow.orchestrator.automation.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.automation.model.QueueEntry
import com.workflow.orchestrator.automation.model.QueueEntryStatus
import com.workflow.orchestrator.automation.service.AutomationSettingsService
import com.workflow.orchestrator.automation.service.QueueService
import com.workflow.orchestrator.core.ui.ClipboardUtil
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.ui.TimeFormatter
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.polling.SmartPoller
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
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
 *
 * Subscribes to [QueueService.stateFlow] and reflects every active queue entry in
 * real time.  WAITING_LOCAL entries are shown with a "Waiting (local queue)" status
 * and no Bamboo polling — the plugin is holding them locally until the previous run
 * for the same suite finishes on Bamboo (Bamboo cannot queue the same plan twice
 * server-side).  Entries with a bambooResultKey (QUEUED_ON_BAMBOO / RUNNING) are
 * polled for live stage and test data via [SmartPoller].  Terminal entries
 * (COMPLETED, CANCELLED, etc.) are removed from the flow by [QueueService] itself,
 * so they drop off the list automatically.
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

    // Tracks the last set of queue entries we received, keyed by entry id, so the
    // poll loop can reference the bambooResultKey without touching the Swing model.
    @Volatile
    private var lastQueueSnapshot: List<QueueEntry> = emptyList()

    data class RunEntry(
        val queueId: String,           // QueueEntry.id — stable identity across refreshes
        val suiteName: String,
        val planKey: String,
        val resultKey: String,         // "" for WAITING_LOCAL
        val buildNumber: Int = 0,
        val status: String = "Triggered",
        val stages: List<StageInfo> = emptyList(),
        val totalTests: Int = 0,
        val failedTests: Int = 0,
        val failedTestNames: List<String> = emptyList(),
        val duration: String = "",
        val bambooUrl: String = "",
        val isLocalWait: Boolean = false   // true when WAITING_LOCAL (no bambooResultKey)
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
                else if (runListModel.isEmpty) showEmptyState()
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

        subscribeToQueue()
    }

    // -------------------------------------------------------------------------
    // QueueService subscription
    // -------------------------------------------------------------------------

    private fun subscribeToQueue() {
        scope.launch {
            try {
                val queueService = project.getService(QueueService::class.java) ?: return@launch
                queueService.stateFlow.collectLatest { entries ->
                    lastQueueSnapshot = entries

                    // Build the new RunEntry list from queue entries
                    val newEntries = entries.map { queueEntry -> toRunEntry(queueEntry) }

                    withContext(Dispatchers.EDT) {
                        applyNewEntryList(newEntries)
                    }

                    // Start or stop the Bamboo poll loop based on whether any
                    // entries actually have a resultKey to poll
                    val hasPollable = entries.any { it.bambooResultKey != null &&
                        it.status in BAMBOO_POLLABLE_STATUSES }
                    if (hasPollable) {
                        startPollingIfNeeded()
                    } else {
                        poller?.stop()
                        poller = null
                    }
                }
            } catch (_: CancellationException) {
                // Expected on dispose
            }
        }
    }

    /**
     * Merges [newEntries] into [runListModel] while:
     *  - preserving the user's selected entry by [RunEntry.queueId]
     *  - updating existing rows in-place (to keep the detail panel coherent)
     *  - adding new rows at the top
     *  - removing rows whose queueId no longer exists
     *
     * Must be called on the EDT.
     */
    private fun applyNewEntryList(newEntries: List<RunEntry>) {
        val selectedId = runList.selectedValue?.queueId

        if (newEntries.isEmpty()) {
            runListModel.clear()
            showEmptyState()
            return
        }

        val existingById = mutableMapOf<String, Int>()
        for (i in 0 until runListModel.size()) {
            existingById[runListModel.getElementAt(i).queueId] = i
        }

        val newIds = newEntries.map { it.queueId }.toSet()

        // Remove rows that are no longer in the queue (terminal entries dropped by QueueService)
        val toRemove = mutableListOf<Int>()
        for (i in 0 until runListModel.size()) {
            if (runListModel.getElementAt(i).queueId !in newIds) toRemove.add(i)
        }
        for (i in toRemove.reversed()) runListModel.removeElementAt(i)

        // Rebuild existingById after removals
        existingById.clear()
        for (i in 0 until runListModel.size()) {
            existingById[runListModel.getElementAt(i).queueId] = i
        }

        // Insert new entries at top; update existing ones in-place
        var insertPos = 0
        for (entry in newEntries) {
            val existingIdx = existingById[entry.queueId]
            if (existingIdx == null) {
                runListModel.add(insertPos, entry)
                // Shift all existing indices
                for (k in existingById.keys.toList()) {
                    existingById[k] = existingById[k]!! + 1
                }
                existingById[entry.queueId] = insertPos
                insertPos++
            } else {
                // Update in-place; preserve Bamboo-polled detail fields if we don't have fresh ones
                val existing = runListModel.getElementAt(existingIdx)
                val merged = entry.copy(
                    buildNumber = if (entry.buildNumber != 0) entry.buildNumber else existing.buildNumber,
                    stages = if (entry.stages.isNotEmpty()) entry.stages else existing.stages,
                    totalTests = if (entry.totalTests != 0) entry.totalTests else existing.totalTests,
                    failedTests = if (entry.failedTests != 0) entry.failedTests else existing.failedTests,
                    failedTestNames = if (entry.failedTestNames.isNotEmpty()) entry.failedTestNames else existing.failedTestNames,
                    duration = if (entry.duration.isNotBlank()) entry.duration else existing.duration,
                    bambooUrl = if (entry.bambooUrl.isNotBlank()) entry.bambooUrl else existing.bambooUrl
                )
                if (merged != existing) {
                    runListModel.set(existingIdx, merged)
                }
                insertPos = existingIdx + 1
            }
        }

        // Restore selection
        val restoredIdx = if (selectedId != null) {
            (0 until runListModel.size()).firstOrNull { runListModel.getElementAt(it).queueId == selectedId }
        } else null

        when {
            restoredIdx != null -> {
                runList.selectedIndex = restoredIdx
                showRunDetail(runListModel.getElementAt(restoredIdx))
            }
            runListModel.size() > 0 && runList.selectedIndex < 0 -> {
                runList.selectedIndex = 0
                showRunDetail(runListModel.getElementAt(0))
            }
            runListModel.size() > 0 && runList.selectedIndex >= 0 -> {
                showRunDetail(runListModel.getElementAt(runList.selectedIndex))
            }
        }
    }

    // -------------------------------------------------------------------------
    // QueueEntry → RunEntry conversion
    // -------------------------------------------------------------------------

    private fun toRunEntry(queueEntry: QueueEntry): RunEntry {
        val suiteName = AutomationSettingsService
            .getInstance().getSuiteConfig(queueEntry.suitePlanKey)?.displayName
            ?: queueEntry.suitePlanKey

        val isLocalWait = queueEntry.bambooResultKey == null
        val statusText = when {
            isLocalWait -> "Waiting (local queue)"
            queueEntry.status == QueueEntryStatus.TRIGGERING -> "Triggering"
            queueEntry.status == QueueEntryStatus.QUEUED_ON_BAMBOO -> "Queued"
            queueEntry.status == QueueEntryStatus.RUNNING -> "Running"
            else -> queueEntry.status.name
        }

        return RunEntry(
            queueId = queueEntry.id,
            suiteName = suiteName,
            planKey = queueEntry.suitePlanKey,
            resultKey = queueEntry.bambooResultKey ?: "",
            status = statusText,
            isLocalWait = isLocalWait
        )
    }

    // -------------------------------------------------------------------------
    // Bamboo polling (only for entries with a resultKey)
    // -------------------------------------------------------------------------

    private fun startPollingIfNeeded() {
        if (poller != null) return  // already running; collectLatest drives the snapshot
        poller = SmartPoller(
            name = "AutomationMonitor",
            baseIntervalMs = 15_000,
            scope = scope
        ) {
            pollAllRuns()
            lastQueueSnapshot.any { it.bambooResultKey != null && it.status in BAMBOO_POLLABLE_STATUSES }
        }.also { it.start() }
    }

    private suspend fun pollAllRuns() {
        val bambooService = project.getService(BambooService::class.java) ?: return

        // Snapshot queue entries that have a bambooResultKey (safe — lastQueueSnapshot is @Volatile)
        val pollable = lastQueueSnapshot.filter {
            it.bambooResultKey != null && it.status in BAMBOO_POLLABLE_STATUSES
        }

        for (queueEntry in pollable) {
            val resultKey = queueEntry.bambooResultKey ?: continue

            try {
                val result = bambooService.getBuild(resultKey)
                if (!result.isError) {
                    val buildData = result.data!!
                    val stages = buildData.stages.map { stage ->
                        StageInfo(
                            name = stage.name,
                            state = stage.state,
                            duration = TimeFormatter.formatDurationSeconds(stage.durationSeconds, zero = "")
                        )
                    }

                    val bambooUrl = settings.connections.bambooUrl.orEmpty().trimEnd('/')
                    var runEntry = RunEntry(
                        queueId = queueEntry.id,
                        suiteName = AutomationSettingsService
                            .getInstance().getSuiteConfig(queueEntry.suitePlanKey)?.displayName
                            ?: queueEntry.suitePlanKey,
                        planKey = queueEntry.suitePlanKey,
                        resultKey = resultKey,
                        buildNumber = buildData.buildNumber,
                        status = when (buildData.state) {
                            "Successful" -> "Successful"
                            "Failed" -> "Failed"
                            else -> if (queueEntry.status == QueueEntryStatus.RUNNING) "Running" else "Queued"
                        },
                        stages = stages,
                        duration = TimeFormatter.formatDurationSeconds(buildData.durationSeconds, zero = ""),
                        bambooUrl = "$bambooUrl/browse/$resultKey"
                    )

                    // Fetch test results if build is in a terminal-ish state from Bamboo's view
                    if (buildData.state in BAMBOO_TERMINAL_STATES) {
                        val testResult = bambooService.getTestResults(resultKey)
                        if (!testResult.isError) {
                            val testData = testResult.data!!
                            val failedNames = testData.failedTests.map {
                                "${it.className.substringAfterLast('.')}.${it.methodName}"
                            }
                            runEntry = runEntry.copy(
                                totalTests = testData.total,
                                failedTests = testData.failed,
                                failedTestNames = failedNames
                            )
                        }
                    }

                    val finalEntry = runEntry
                    invokeLater {
                        val idx = (0 until runListModel.size()).firstOrNull {
                            runListModel.getElementAt(it).queueId == queueEntry.id
                        } ?: return@invokeLater

                        // Preserve any richer fields from the existing row
                        val existing = runListModel.getElementAt(idx)
                        val merged = finalEntry.copy(
                            totalTests = if (finalEntry.totalTests != 0) finalEntry.totalTests else existing.totalTests,
                            failedTests = if (finalEntry.failedTests != 0) finalEntry.failedTests else existing.failedTests,
                            failedTestNames = if (finalEntry.failedTestNames.isNotEmpty()) finalEntry.failedTestNames else existing.failedTestNames
                        )
                        runListModel.set(idx, merged)
                        if (runList.selectedIndex == idx) showRunDetail(merged)
                    }
                }
            } catch (e: Exception) {
                log.warn("[Automation:Monitor] Poll failed for $resultKey: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Detail panel
    // -------------------------------------------------------------------------

    private fun showRunDetail(entry: RunEntry) {
        detailPanel.removeAll()

        // Header
        val headerPanel = JPanel(BorderLayout()).apply {
            val statusColor = statusColorFor(entry)
            val statusIcon = when {
                entry.status.equals("Successful", ignoreCase = true) -> "✓"
                entry.status.equals("Failed", ignoreCase = true) ->
                    if (entry.failedTests > 0) "⚠" else "✗"
                entry.isLocalWait -> "⏳"
                else -> "⟳"
            }

            val headerRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(JBLabel("$statusIcon ${entry.suiteName}").apply {
                    font = JBUI.Fonts.label().deriveFont(Font.BOLD)
                    foreground = statusColor
                })
                if (entry.buildNumber > 0) {
                    add(JBLabel("#${entry.buildNumber}").apply {
                        font = Font(Font.MONOSPACED, Font.BOLD, JBUI.Fonts.label().size)
                        foreground = statusColor
                    })
                }
            }
            add(headerRow, BorderLayout.WEST)

            val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))

            // Cancel button — shown for any non-terminal entry
            if (!entry.isTerminal()) {
                actionsPanel.add(JButton("Cancel").apply {
                    isFocusPainted = false
                    foreground = StatusColors.ERROR
                    addActionListener {
                        project.getService(QueueService::class.java)?.cancel(entry.queueId)
                    }
                })
            }

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

        // Status + duration (or local-wait explanation)
        if (entry.isLocalWait) {
            content.add(JBLabel("Waiting (local queue)").apply {
                foreground = StatusColors.WARNING
            })
            content.add(Box.createVerticalStrut(JBUI.scale(4)))
            content.add(JBLabel("Bamboo cannot queue the same plan twice simultaneously.").apply {
                foreground = StatusColors.SECONDARY_TEXT
                font = font.deriveFont(JBUI.scale(11).toFloat())
            })
            content.add(JBLabel("This run will be submitted when the current run finishes.").apply {
                foreground = StatusColors.SECONDARY_TEXT
                font = font.deriveFont(JBUI.scale(11).toFloat())
            })
        } else {
            val statusDuration = buildString {
                append(entry.status)
                if (entry.duration.isNotBlank()) append(" • ${entry.duration}")
            }
            content.add(JBLabel(statusDuration).apply {
                foreground = StatusColors.SECONDARY_TEXT
            })
        }
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
                    "successful" -> "✓"
                    "failed" -> "✗"
                    "inprogress" -> "⟳"
                    else -> "○"
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
                content.add(JBLabel("  ✗ $testName").apply {
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
        detailPanel.add(JBLabel("No active runs. Trigger a suite from the Configure tab.").apply {
            horizontalAlignment = SwingConstants.CENTER
            foreground = StatusColors.SECONDARY_TEXT
        }, BorderLayout.CENTER)
        detailPanel.revalidate()
        detailPanel.repaint()
    }

    private fun copyResultsToClipboard(entry: RunEntry) {
        val text = buildString {
            if (entry.buildNumber > 0) {
                appendLine("Suite: ${entry.suiteName} #${entry.buildNumber}")
            } else {
                appendLine("Suite: ${entry.suiteName}")
            }
            appendLine("Status: ${entry.status}")
            if (entry.duration.isNotBlank()) appendLine("Duration: ${entry.duration}")
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun statusColorFor(entry: RunEntry): Color = when {
        entry.status.equals("Successful", ignoreCase = true) -> StatusColors.SUCCESS
        entry.status.equals("Failed", ignoreCase = true) -> StatusColors.ERROR
        entry.isLocalWait -> StatusColors.WARNING
        else -> StatusColors.LINK
    }

    private fun RunEntry.isTerminal(): Boolean =
        status.equals("Successful", ignoreCase = true) ||
        status.equals("Failed", ignoreCase = true)

    override fun dispose() {
        poller?.stop()
        scope.cancel()
    }

    private companion object {
        private val BAMBOO_POLLABLE_STATUSES = setOf(
            QueueEntryStatus.QUEUED_ON_BAMBOO,
            QueueEntryStatus.RUNNING
        )
        private val BAMBOO_TERMINAL_STATES = setOf("Successful", "Failed", "Unknown")
    }

    // -------------------------------------------------------------------------
    // Cell renderer — preserved from original (cached panels for performance)
    // -------------------------------------------------------------------------

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

            val statusColor = when {
                entry.status.equals("Successful", ignoreCase = true) -> StatusColors.SUCCESS
                entry.status.equals("Failed", ignoreCase = true) -> StatusColors.ERROR
                entry.isLocalWait -> StatusColors.WARNING
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

            // Stitch: monospace build number (only when known)
            if (entry.buildNumber > 0) {
                buildLabel.text = "#${entry.buildNumber}"
                buildLabel.isVisible = true
            } else {
                buildLabel.isVisible = false
            }
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
