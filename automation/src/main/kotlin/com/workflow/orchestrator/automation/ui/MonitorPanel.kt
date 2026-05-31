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
 * North: filter chip row (All / Queued / Running / Failed / Completed).
 * Centre: split view — compact run list on the left, selected run detail on the right.
 *
 * Subscribes to [QueueService.stateFlow] and renders every queue entry — including
 * **terminal** ones (COMPLETED, FAILED, CANCELLED, FAILED_TO_TRIGGER). PR 8: terminal
 * entries persist in [_stateFlow] until the user explicitly clicks Remove (which
 * calls [QueueService.dismiss]).  Entries are sorted latest-first by `enqueuedAt`.
 *
 * Polling: WAITING_LOCAL entries are not polled (no resultKey yet). QUEUED_ON_BAMBOO
 * and RUNNING entries are polled every 15 s by [SmartPoller] for stage and test data.
 * Once an entry hits a terminal status, polling stops for that row.
 *
 * Selection: the parent [AutomationPanel] subscribes via [onSelectionChanged] so the
 * top status bar can mirror the user's selection (PR 8 #4).
 */
class MonitorPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val log = Logger.getInstance(MonitorPanel::class.java)
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, t ->
                log.error("[Automation:Monitor] Unhandled coroutine exception", t)
            }
    )
    private val settings = PluginSettings.getInstance(project)

    private val runListModel = DefaultListModel<RunEntry>()
    private val runList = javax.swing.JList(runListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = RunListCellRenderer()
    }

    private val detailPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(12)
    }

    @Volatile
    private var poller: SmartPoller? = null

    // Tracks the last set of queue entries we received, keyed by entry id, so the
    // poll loop can reference the bambooResultKey without touching the Swing model.
    @Volatile
    private var lastQueueSnapshot: List<QueueEntry> = emptyList()

    /** Latest unfiltered RunEntry list, sorted latest-first. Re-applied to the model when the filter changes. */
    @Volatile
    private var allEntries: List<RunEntry> = emptyList()

    /** Active filter chip. Mutated on the EDT only. */
    private var currentFilter: MonitorFilter = MonitorFilter.ALL

    /**
     * Fired on the EDT when the list selection changes (or the selected row is
     * removed). The parent [AutomationPanel] wires this into [QueueStatusPanel]
     * so the top status bar reflects the user's selection.
     */
    var onSelectionChanged: ((RunEntry?) -> Unit)? = null

    enum class MonitorFilter(val label: String) {
        ALL("All"),
        QUEUED("Queued"),
        RUNNING("Running"),
        FAILED("Failed"),
        COMPLETED("Completed")
    }

    private val filterToggles: Map<MonitorFilter, JToggleButton> = MonitorFilter.values().associateWith { f ->
        JToggleButton(f.label).apply {
            isFocusPainted = false
            isSelected = (f == MonitorFilter.ALL)
        }
    }

    data class RunEntry(
        val queueId: String,           // QueueEntry.id — stable identity across refreshes
        val suiteName: String,
        val planKey: String,
        val resultKey: String,         // "" for WAITING_LOCAL / FAILED_TO_TRIGGER
        val buildNumber: Int = 0,
        val status: String = "Triggered",
        val stages: List<StageInfo> = emptyList(),
        val totalTests: Int = 0,
        val failedTests: Int = 0,
        val failedTestNames: List<String> = emptyList(),
        val duration: String = "",
        val bambooUrl: String = "",
        val isLocalWait: Boolean = false,  // true when WAITING_LOCAL (no bambooResultKey)
        val isTerminal: Boolean = false,   // derived from QueueEntryStatus; avoids string-matching downstream
        /** Bucket used by the filter chips. Derived once in [toRunEntry]. */
        val filterBucket: MonitorFilter = MonitorFilter.ALL,
        /**
         * Failure reason, surfaced by [buildRunEntry] for terminal-failed statuses
         * (currently `FAILED_TO_TRIGGER`). Mirrors `QueueEntry.errorMessage` so the
         * detail panel can tell the user *why* a trigger failed without forcing them
         * into `idea.log`. `null` for non-failed rows.
         */
        val errorMessage: String? = null
    )

    data class StageInfo(
        val name: String,
        val state: String,
        val duration: String = ""
    )

    init {
        // Filter chips — radio-style; All is the default. Mutating currentFilter
        // triggers re-render with the cached allEntries list.
        val filterGroup = ButtonGroup()
        val filterRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(2, 4, 4, 4)
            for ((filter, toggle) in filterToggles) {
                filterGroup.add(toggle)
                toggle.addActionListener { onFilterChanged(filter) }
                add(toggle)
            }
        }
        add(filterRow, BorderLayout.NORTH)

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
                onSelectionChanged?.invoke(selected)
            }
        }

        // Right-click context menu — fast access to per-row actions without
        // requiring the user to click into the detail panel first.  We use one
        // popup instance and rebuild items each show so it can target whichever
        // row the click landed on.
        runList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = maybeShow(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = maybeShow(e)
            private fun maybeShow(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val idx = runList.locationToIndex(e.point).takeIf { it >= 0 } ?: return
                val cellBounds = runList.getCellBounds(idx, idx) ?: return
                if (!cellBounds.contains(e.point)) return
                val entry = runListModel.getElementAt(idx) ?: return
                runList.selectedIndex = idx
                showRowContextMenu(entry, e.x, e.y)
            }
        })

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

                    // PR 8: sort latest-first so applyNewEntryList's "insert new at top"
                    // walk produces an enqueueTime-desc model. Existing rows keep their
                    // position via in-place update.
                    val sortedQueueEntries = entries.sortedByDescending { it.enqueuedAt }
                    val newEntries = sortedQueueEntries.map { toRunEntry(it) }
                    allEntries = newEntries

                    withContext(Dispatchers.EDT) {
                        applyNewEntryList(visibleEntries(newEntries))
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

    /** Filter chip click handler — re-renders the list with the same cached data. */
    private fun onFilterChanged(newFilter: MonitorFilter) {
        if (newFilter == currentFilter) return
        currentFilter = newFilter
        applyNewEntryList(visibleEntries(allEntries))
    }

    /**
     * CANCELLED is bucketed under FAILED — terminal-not-success. Users who don't
     * want to see cancelled rows can filter to Completed / Running / Queued; All
     * surfaces them too.
     */
    private fun visibleEntries(all: List<RunEntry>): List<RunEntry> =
        if (currentFilter == MonitorFilter.ALL) all
        else all.filter { it.filterBucket == currentFilter }

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
            onSelectionChanged?.invoke(null)
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
        val bambooUrlBase = settings.connections.bambooUrl.orEmpty().trimEnd('/')
        return buildRunEntry(queueEntry, suiteName, bambooUrlBase)
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
                    val bambooStatus = when (buildData.state) {
                        "Successful" -> "Successful"
                        "Failed" -> "Failed"
                        else -> if (queueEntry.status == QueueEntryStatus.RUNNING) "Running" else "Queued"
                    }
                    val bucket = when (bambooStatus) {
                        "Successful" -> MonitorFilter.COMPLETED
                        "Failed" -> MonitorFilter.FAILED
                        "Running" -> MonitorFilter.RUNNING
                        else -> MonitorFilter.QUEUED
                    }
                    var runEntry = RunEntry(
                        queueId = queueEntry.id,
                        suiteName = AutomationSettingsService
                            .getInstance().getSuiteConfig(queueEntry.suitePlanKey)?.displayName
                            ?: queueEntry.suitePlanKey,
                        planKey = queueEntry.suitePlanKey,
                        resultKey = resultKey,
                        buildNumber = buildData.buildNumber,
                        status = bambooStatus,
                        stages = stages,
                        duration = TimeFormatter.formatDurationSeconds(buildData.durationSeconds, zero = ""),
                        bambooUrl = "$bambooUrl/browse/$resultKey",
                        isTerminal = buildData.state in BAMBOO_TERMINAL_STATES,
                        filterBucket = bucket
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
                        // PR 8: keep allEntries (the unfiltered cache) in sync so a
                        // later chip switch sees the latest stage/test data. Then
                        // re-apply the filter — if the bucket changed (e.g. RUNNING
                        // → COMPLETED) and the user is on a different chip, the row
                        // falls out of the displayed list cleanly.
                        allEntries = allEntries.map {
                            if (it.queueId == finalEntry.queueId) finalEntry else it
                        }
                        applyNewEntryList(visibleEntries(allEntries))
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
                entry.status.equals("Failed to trigger", ignoreCase = true) -> "✗"
                entry.status.equals("Cancelled", ignoreCase = true) -> "⏹"
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

            // Cancel button — shown for any non-terminal entry. Cancels on Bamboo
            // (if applicable) and transitions the entry to CANCELLED. The row STAYS
            // in the list — the user removes it later with the Remove button below.
            if (!entry.terminal()) {
                actionsPanel.add(JButton("Cancel").apply {
                    isFocusPainted = false
                    foreground = StatusColors.ERROR
                    addActionListener {
                        project.getService(QueueService::class.java)?.cancel(entry.queueId)
                    }
                })
            } else {
                // Remove button — only on terminal entries. Calls dismiss(), which
                // is the user's explicit "I've seen this, take it off the list"
                // action. (PR 8 #3.)
                actionsPanel.add(JButton("Remove").apply {
                    isFocusPainted = false
                    toolTipText = "Remove this run from the list"
                    foreground = StatusColors.SECONDARY_TEXT
                    addActionListener {
                        project.getService(QueueService::class.java)?.dismiss(entry.queueId)
                    }
                })
            }

            if (entry.bambooUrl.isNotBlank()) {
                actionsPanel.add(JButton("Open in Bamboo ↗").apply {
                    isBorderPainted = false
                    foreground = StatusColors.LINK
                    addActionListener { BrowserUtil.browse(entry.bambooUrl) }
                })
                actionsPanel.add(JButton("Copy Link").apply {
                    isBorderPainted = false
                    toolTipText = "Copy the Bamboo build URL to the clipboard"
                    addActionListener { copyLinkToClipboard(entry) }
                })
            }
            // Copy Results: useful for any terminal run (Successful, Failed,
            // FailedToTrigger, Cancelled). Pre-PR-8 the visibility was narrowed
            // to just Successful/Failed because terminal entries were pruned
            // before the user could click — that constraint is gone now.
            if (entry.terminal()) {
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
            // Failure reason from QueueEntry.errorMessage — surfaces the underlying
            // Bamboo cause (auth/plan/race) so the user does not have to dig through
            // idea.log when an automation run fails to trigger.
            val errorMessage = entry.errorMessage?.takeIf { it.isNotBlank() }
            if (errorMessage != null) {
                content.add(Box.createVerticalStrut(JBUI.scale(4)))
                content.add(JBLabel("Reason: $errorMessage").apply {
                    foreground = StatusColors.ERROR
                    font = font.deriveFont(JBUI.scale(11).toFloat())
                })
            }
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
        // Filter-aware empty message: tell the user whether the list is truly
        // empty or whether the current chip just hides everything.
        val message = when {
            allEntries.isEmpty() -> "No runs yet. Trigger a suite from the Configure tab."
            currentFilter == MonitorFilter.ALL -> "No runs match the current view."
            else -> "No ${currentFilter.label.lowercase()} runs. Pick another filter to see more."
        }
        detailPanel.add(JBLabel(message).apply {
            horizontalAlignment = SwingConstants.CENTER
            foreground = StatusColors.SECONDARY_TEXT
        }, BorderLayout.CENTER)
        detailPanel.revalidate()
        detailPanel.repaint()
    }

    /**
     * Right-click popup on a run row.  Mirrors the detail-header buttons so
     * users don't have to click a row, wait for the detail panel to render,
     * then click again — common shortcut on a Monitor list.
     */
    private fun showRowContextMenu(entry: RunEntry, x: Int, y: Int) {
        val menu = JPopupMenu()
        if (entry.bambooUrl.isNotBlank()) {
            menu.add(JMenuItem("Open in Bamboo ↗").apply {
                addActionListener { BrowserUtil.browse(entry.bambooUrl) }
            })
            menu.add(JMenuItem("Copy Link").apply {
                addActionListener { copyLinkToClipboard(entry) }
            })
        }
        if (entry.terminal()) {
            if (menu.componentCount > 0) menu.addSeparator()
            menu.add(JMenuItem("Copy Results").apply {
                addActionListener { copyResultsToClipboard(entry) }
            })
            menu.add(JMenuItem("Remove from list").apply {
                addActionListener {
                    project.getService(QueueService::class.java)?.dismiss(entry.queueId)
                }
            })
        } else {
            if (menu.componentCount > 0) menu.addSeparator()
            menu.add(JMenuItem("Cancel").apply {
                addActionListener {
                    project.getService(QueueService::class.java)?.cancel(entry.queueId)
                }
            })
        }
        if (menu.componentCount > 0) menu.show(runList, x, y)
    }

    private fun copyLinkToClipboard(entry: RunEntry) {
        if (entry.bambooUrl.isBlank()) return
        ClipboardUtil.copyToClipboard(entry.bambooUrl)
        log.info("[Automation:Monitor] Bamboo link copied: ${entry.bambooUrl}")
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
        entry.status.equals("Failed to trigger", ignoreCase = true) -> StatusColors.ERROR
        entry.status.equals("Cancelled", ignoreCase = true) -> StatusColors.SECONDARY_TEXT
        entry.isLocalWait -> StatusColors.WARNING
        else -> StatusColors.LINK
    }

    // isTerminal is derived from QueueEntryStatus in toRunEntry / the Bamboo poll loop;
    // kept as an extension for call-site readability.
    private fun RunEntry.terminal(): Boolean = isTerminal

    override fun dispose() {
        poller?.stop()
        scope.cancel()
    }

    companion object {
        private val BAMBOO_POLLABLE_STATUSES = setOf(
            QueueEntryStatus.QUEUED_ON_BAMBOO,
            QueueEntryStatus.RUNNING
        )
        private val BAMBOO_TERMINAL_STATES = setOf("Successful", "Failed", "Unknown")

        /**
         * Pure helper that maps a persisted [QueueEntry] to a [RunEntry] view-model.
         * Extracted from the instance method [toRunEntry] so its mapping logic
         * (status text, filter bucket, terminal flag, errorMessage propagation) is
         * unit-testable without an IntelliJ project / Swing harness.
         *
         * @param suiteName the resolved display name for the suite (caller looks up
         *   via [AutomationSettingsService]).
         * @param bambooUrlBase the configured Bamboo base URL with no trailing slash,
         *   or `""` when no connection is configured.
         */
        @JvmStatic
        fun buildRunEntry(
            queueEntry: QueueEntry,
            suiteName: String,
            bambooUrlBase: String
        ): RunEntry {
            val resultKey = queueEntry.bambooResultKey ?: ""
            val bambooUrl = if (resultKey.isNotBlank() && bambooUrlBase.isNotBlank()) {
                "$bambooUrlBase/browse/$resultKey"
            } else ""

            // PR 8 status mapping. Terminal statuses now persist in _stateFlow; we render them
            // here just like live ones. Each status is tagged with its filter bucket so the
            // chips can filter without re-inspecting the string.
            return when (queueEntry.status) {
                QueueEntryStatus.FAILED_TO_TRIGGER -> RunEntry(
                    queueId = queueEntry.id, suiteName = suiteName,
                    planKey = queueEntry.suitePlanKey, resultKey = "",
                    status = "Failed to trigger",
                    isTerminal = true,
                    filterBucket = MonitorFilter.FAILED,
                    errorMessage = queueEntry.errorMessage
                )
                QueueEntryStatus.WAITING_LOCAL -> RunEntry(
                    queueId = queueEntry.id, suiteName = suiteName,
                    planKey = queueEntry.suitePlanKey, resultKey = resultKey,
                    status = "Waiting (local queue)",
                    bambooUrl = bambooUrl, isLocalWait = true, isTerminal = false,
                    filterBucket = MonitorFilter.QUEUED
                )
                QueueEntryStatus.QUEUED_ON_BAMBOO -> RunEntry(
                    queueId = queueEntry.id, suiteName = suiteName,
                    planKey = queueEntry.suitePlanKey, resultKey = resultKey,
                    status = "Queued",
                    bambooUrl = bambooUrl, isTerminal = false,
                    filterBucket = MonitorFilter.QUEUED
                )
                QueueEntryStatus.RUNNING -> RunEntry(
                    queueId = queueEntry.id, suiteName = suiteName,
                    planKey = queueEntry.suitePlanKey, resultKey = resultKey,
                    status = "Running",
                    bambooUrl = bambooUrl, isTerminal = false,
                    filterBucket = MonitorFilter.RUNNING
                )
                QueueEntryStatus.COMPLETED -> RunEntry(
                    queueId = queueEntry.id, suiteName = suiteName,
                    planKey = queueEntry.suitePlanKey, resultKey = resultKey,
                    status = "Successful",
                    bambooUrl = bambooUrl, isTerminal = true,
                    filterBucket = MonitorFilter.COMPLETED
                )
                QueueEntryStatus.FAILED -> RunEntry(
                    queueId = queueEntry.id, suiteName = suiteName,
                    planKey = queueEntry.suitePlanKey, resultKey = resultKey,
                    status = "Failed",
                    bambooUrl = bambooUrl, isTerminal = true,
                    filterBucket = MonitorFilter.FAILED,
                    errorMessage = queueEntry.errorMessage
                )
                QueueEntryStatus.CANCELLED -> RunEntry(
                    queueId = queueEntry.id, suiteName = suiteName,
                    planKey = queueEntry.suitePlanKey, resultKey = resultKey,
                    status = "Cancelled",
                    bambooUrl = bambooUrl, isTerminal = true,
                    // CANCELLED bucketed under Failed — terminal-not-success. See visibleEntries() KDoc.
                    filterBucket = MonitorFilter.FAILED
                )
                // TRIGGERING currently unused by QueueService — render generically if reintroduced.
                // TAG_INVALID is a deprecated SQLite-only legacy state; treat as terminal Failed.
                else -> RunEntry(
                    queueId = queueEntry.id, suiteName = suiteName,
                    planKey = queueEntry.suitePlanKey, resultKey = resultKey,
                    status = queueEntry.status.name,
                    bambooUrl = bambooUrl,
                    isTerminal = queueEntry.status in QueueEntryStatus.TERMINAL,
                    filterBucket = if (queueEntry.status in QueueEntryStatus.TERMINAL)
                        MonitorFilter.FAILED else MonitorFilter.QUEUED
                )
            }
        }
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
                entry.status.equals("Failed to trigger", ignoreCase = true) -> StatusColors.ERROR
                entry.status.equals("Cancelled", ignoreCase = true) -> StatusColors.SECONDARY_TEXT
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
