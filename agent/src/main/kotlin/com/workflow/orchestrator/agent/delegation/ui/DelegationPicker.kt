package com.workflow.orchestrator.agent.delegation.ui

import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.workflow.orchestrator.agent.delegation.AutoLaunchOutcome
import com.workflow.orchestrator.agent.delegation.AutoLaunchPoller
import com.workflow.orchestrator.agent.delegation.DefaultProcessSpawner
import com.workflow.orchestrator.agent.delegation.LauncherResolver
import com.workflow.orchestrator.agent.delegation.ProcessSpawner
import com.workflow.orchestrator.agent.delegation.SpawnResult
import com.workflow.orchestrator.agent.delegation.ToolboxFlavorReader
import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationPaths
import com.workflow.orchestrator.core.ui.StatusColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

data class PickerEntry(
    val path: Path,
    val displayName: String,
    val status: Status,
    /** When true this row is a non-selectable section header, not a project entry. */
    val isHeader: Boolean = false,
) {
    enum class Status { RUNNING, CLOSED, MISSING }

    override fun toString(): String {
        if (isHeader) return displayName
        val badge = when (status) {
            Status.RUNNING -> "● Running"
            Status.CLOSED -> "○ Closed"
            Status.MISSING -> "⚠ Missing"
        }
        return "$displayName  [$badge]  $path"
    }
}

/**
 * Modal picker that lists the user's recent projects (from RecentProjectsManager)
 * and, on open, probes each one's deterministic UDS socket to determine status.
 *
 * MVP behavior: only Running rows are selectable for delegation. Closed and
 * Missing rows are visible but disabled (Plan 3 will add Launch & Delegate).
 *
 * The [launcherResolver], [toolboxFlavorReader], and [processSpawner] parameters
 * are injectable (spec §5.6, §6.3) so the auto-launch path can be driven from
 * tests without touching the real OS or IntelliJ install layout. Production
 * callers omit these args and receive the default production implementations.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §5.1, §5.2, §5.3, §5.6.
 */
class DelegationPicker(
    private val project: Project,
    private val suggestedRepo: String?,
    private val launcherResolver: LauncherResolver = LauncherResolver(),
    private val toolboxFlavorReader: ToolboxFlavorReader = ToolboxFlavorReader(),
    private val processSpawner: ProcessSpawner = DefaultProcessSpawner,
    // MODELESS is required for Launch & Delegate. The default DialogWrapper(project)
    // construction is project-modal (Swing APPLICATION_MODAL), which suspends the
    // outer AWT event loop. JetBrains' single-instance IPC — invoked by the
    // spawned `idea.sh /path/repo` launcher when an IDE is already running from
    // the same install — dispatches "open project" requests via
    // ApplicationManager.invokeLater(NON_MODAL). NON_MODAL runnables do NOT fire
    // while a modal dialog is up, so under the previous (default) modality the
    // spawned launcher's open-project request queued behind the picker and the
    // new window only appeared after the picker was dismissed (often firing
    // multiple times if the user clicked Launch & Delegate repeatedly).
    //
    // MODELESS keeps the dialog's blocking semantics for the calling coroutine
    // (showAndGet still suspends until OK / Cancel) but lets the EDT continue
    // draining invokeLater(NON_MODAL), so the new IDE window can actually open
    // while the picker waits for its socket via AutoLaunchPoller.
) : DialogWrapper(project, true, IdeModalityType.MODELESS) {

    var selectedEntry: PickerEntry? = null
        private set

    private val listModel = DefaultListModel<PickerEntry>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        // Custom two-line renderer (status dot + bold name + ellipsized path + status pill).
        // Per Plan 5.3 design spec §4.1, §4.2. JB components + StatusColors only.
        cellRenderer = DelegationPickerCellRenderer()
    }

    // ---- Auto-launch UI affordances ----------------------------------------

    /** Yellow informational banner shown when Toolbox is detected but flavor is unknown. */
    private val toolboxUnknownBanner = JBLabel(
        "<html>⚠  Toolbox detected: IDE flavor unknown — launching with current IDE flavor.</html>"
    ).apply {
        foreground = StatusColors.WARNING
        isVisible = false
    }

    /** Warning-background wrapper panel for [toolboxUnknownBanner]. Visibility toggles
     *  with the inner label so the colored rectangle disappears too. */
    private val toolboxUnknownBannerPanel = JPanel(BorderLayout()).apply {
        background = StatusColors.WARNING_BG
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        isOpaque = true
        isVisible = false
        add(toolboxUnknownBanner, BorderLayout.CENTER)
    }

    /** Red inline failure label shown after spawn failure or 90s timeout. */
    private val launchFailureLabel = JBLabel("").apply {
        foreground = StatusColors.ERROR
        isVisible = false
    }

    /** Error-background wrapper panel for [launchFailureLabel]. `StatusColors.ERROR_BG`
     *  doesn't exist (only `WARNING_BG` / `SUCCESS_BG` / `INFO_BG`), so use an inline
     *  JBColor pair tuned to read as a soft error tint in both themes (per spec §4.4
     *  fallback path: "otherwise use JBColor with light + dark hex variants"). */
    private val launchFailurePanel = JPanel(BorderLayout()).apply {
        background = com.intellij.ui.JBColor(
            java.awt.Color(0xFD, 0xEC, 0xEA),
            java.awt.Color(0x4E, 0x2A, 0x2A),
        )
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        isOpaque = true
        isVisible = false
        add(launchFailureLabel, BorderLayout.CENTER)
    }

    /** Retry probe button — hidden by default; revealed after a launch failure. */
    private val retryProbeButton = javax.swing.JButton("Retry probe").apply {
        isVisible = false
        addActionListener { onRetryProbe() }
    }

    // ---- DialogWrapper button actions --------------------------------------

    private var launchAndDelegateAction: Action? = null

    /**
     * Background scope used for async socket-glob discovery and recent-project
     * status probing. Cancelled in [dispose] so no coroutine outlives the dialog.
     */
    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        title = "Delegate to Another IDE"
        setOKButtonText("Delegate")
        // Phase 1 (sync): populate the list with recent projects immediately — no I/O,
        // so the picker opens without blocking the EDT. Status shows as CLOSED until
        // async probing upgrades it.
        populateRecentsSync()
        // Phase 2 (async): probe recent-project sockets and run socket-glob discovery
        // off the EDT. Results are posted back via invokeLater.
        triggerDiscoveryAsync()
        init()
        suggestedRepo?.let { hint ->
            val match = (0 until listModel.size())
                .map { listModel.get(it) }
                .firstOrNull { !it.isHeader && it.displayName.contains(hint, ignoreCase = true) }
            if (match != null) list.setSelectedValue(match, true)
        }
        // Keep the Launch & Delegate button state in sync with the selection.
        list.addListSelectionListener { updateLaunchButtonState() }
    }

    override fun dispose() {
        discoveryScope.cancel()
        super.dispose()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        // Top: friendlier hint copy + toolbox-unknown banner (rounded background panel).
        // Per Plan 5.3 §4.3 / §4.4. Hint uses `<html>` so the two-line copy wraps cleanly.
        val northPanel = JPanel(java.awt.GridLayout(0, 1, 0, 6))
        val hintLabel = JBLabel(
            "<html>Choose where to send this task. Running targets receive it immediately; " +
                "closed ones can be launched first.</html>"
        ).apply {
            foreground = StatusColors.SECONDARY_TEXT
            border = BorderFactory.createEmptyBorder(12, 0, 8, 0)
        }
        northPanel.add(hintLabel)
        northPanel.add(toolboxUnknownBannerPanel)
        panel.add(northPanel, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(list).apply {
            preferredSize = Dimension(720, 320)
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        // South: failure panel (rounded background) + retry button. Order is failure on top,
        // retry below, both left-aligned in a vertical stack.
        val southPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
        }
        southPanel.add(launchFailurePanel, BorderLayout.NORTH)
        // Plan 5.4 — Sticky footer reminder. The picker shows everything-not-running
        // as "closed" regardless of whether the IDE is actually off, on a different
        // project, or just has inbound disabled. This footer tells the user up-front
        // about the inbound requirement so they aren't surprised by a 90s timeout.
        val centerRow = JPanel(BorderLayout()).apply { isOpaque = false }
        centerRow.add(retryProbeButton, BorderLayout.WEST)
        val inboundReminder = JBLabel(
            "<html><i>Target IDEs need <b>Accept incoming delegations</b> on " +
                "(their Settings → Tools → Workflow Orchestrator → Cross-IDE Delegation).</i></html>"
        ).apply {
            foreground = StatusColors.SECONDARY_TEXT
            border = BorderFactory.createEmptyBorder(4, 8, 0, 0)
        }
        centerRow.add(inboundReminder, BorderLayout.CENTER)
        val retryRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
        }
        retryRow.add(centerRow, BorderLayout.CENTER)
        southPanel.add(retryRow, BorderLayout.SOUTH)
        panel.add(southPanel, BorderLayout.SOUTH)

        return panel
    }

    override fun createActions(): Array<Action> {
        // Build the Launch & Delegate action alongside the standard OK/Cancel actions.
        val launchAction = object : DialogWrapperAction("Launch && Delegate") {
            init { putValue(DEFAULT_ACTION, false) }

            override fun doAction(e: java.awt.event.ActionEvent) {
                val sel = list.selectedValue
                if (sel == null || sel.isHeader || sel.status != PickerEntry.Status.CLOSED) return
                onLaunchAndDelegate(sel)
            }
        }
        launchAndDelegateAction = launchAction
        updateLaunchButtonState()
        // Order: Delegate (OK), Launch & Delegate, Cancel
        return arrayOf(okAction, launchAction, cancelAction)
    }

    override fun doOKAction() {
        val sel = list.selectedValue
        if (sel?.isHeader == true || sel?.status != PickerEntry.Status.RUNNING) {
            // Disable OK for header rows and non-Running selections — silently no-op
            return
        }
        selectedEntry = sel
        super.doOKAction()
    }

    // ---- Private helpers ---------------------------------------------------

    /** Updates the Launch & Delegate button enabled state based on current selection. */
    private fun updateLaunchButtonState() {
        val sel = list.selectedValue
        val enabled = sel != null && !sel.isHeader && sel.status == PickerEntry.Status.CLOSED
        launchAndDelegateAction?.isEnabled = enabled
    }

    /** Shows a non-modal red inline label below the row list. Also reveals Retry button. */
    private fun showInlineLaunchFailure(reason: String) {
        launchFailureLabel.text = "<html>✕  $reason</html>"
        launchFailureLabel.isVisible = true
        launchFailurePanel.isVisible = true
        retryProbeButton.isVisible = true
        pack()
    }

    /** Shows a non-modal yellow informational banner above the action area. */
    private fun showToolboxUnknownBanner() {
        toolboxUnknownBanner.isVisible = true
        toolboxUnknownBannerPanel.isVisible = true
        pack()
    }

    /** Hides any previously-shown failure UI. */
    private fun hideLaunchFailure() {
        launchFailureLabel.isVisible = false
        launchFailurePanel.isVisible = false
        retryProbeButton.isVisible = false
        toolboxUnknownBanner.isVisible = false
        toolboxUnknownBannerPanel.isVisible = false
    }

    /**
     * Retry probe: re-PINGs the socket once. On PONG updates the selected row's status
     * to RUNNING and enables the regular Delegate button.
     */
    private fun onRetryProbe() {
        val sel = list.selectedValue ?: return
        if (sel.isHeader) return
        val socketPath = DelegationPaths.socketFor(sel.path)
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Probing IDE socket…", false) {
                override fun run(indicator: ProgressIndicator) {
                    val pong = runBlockingCancellable {
                        DelegationClient.ping(socketPath, timeoutMillis = 2_000)
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (pong != null) {
                            // Upgrade the row status so the regular Delegate button activates.
                            val idx = listModel.indexOf(sel)
                            if (idx >= 0) {
                                listModel.set(idx, sel.copy(status = PickerEntry.Status.RUNNING))
                                list.setSelectedIndex(idx)
                            }
                            hideLaunchFailure()
                        } else {
                            showInlineLaunchFailure("IDE-B is still not reachable. Open the project manually, then click Retry probe.")
                        }
                    }
                }
            }
        )
    }

    /**
     * Auto-launch flow: Toolbox flavor check → process spawn → 90s poll → delegate or fall-through.
     * Called from the "Launch & Delegate" button click handler.
     */
    private fun onLaunchAndDelegate(selected: PickerEntry) {
        hideLaunchFailure()
        if (launcherResolver.isToolboxInstall()) {
            val flavor = toolboxFlavorReader.readLastUsedFlavor(selected.path)
            val current = ApplicationInfo.getInstance()
            val currentCode = current.build.productCode
            val currentMajor = current.majorVersion
            if (flavor != null && (flavor.productCode != currentCode || flavor.majorVersion != currentMajor)) {
                val proceed = Messages.showYesNoDialog(
                    project,
                    "This project was last opened with ${flavor.productCode} ${flavor.majorVersion}. " +
                        "Auto-launch will use $currentCode $currentMajor. Continue anyway, or open manually?",
                    "Toolbox Flavor Mismatch",
                    "Continue", "Open manually", null,
                )
                if (proceed != Messages.YES) return
            } else if (flavor == null) {
                showToolboxUnknownBanner()
            }
        }
        val spawn = processSpawner.spawn(launcherResolver.resolveLauncher(), selected.path)
        if (spawn is SpawnResult.Failed) {
            showInlineLaunchFailure("Could not spawn IDE process: ${spawn.message}")
            return
        }
        // Plan 5.4 — launcher-lifetime heuristic for inbound-off diagnosis.
        // When an IntelliJ is already running on this host, `idea.sh /path` (or
        // idea64.exe) signals it via single-instance IPC and exits within ~1s.
        // When the IDE is NOT running, the launcher starts a fresh JVM and
        // stays alive. So: process exits cleanly in <2s ⇒ handed off to a
        // running IDE ⇒ if the socket then fails to bind, the most likely
        // cause is the target's inbound setting being OFF (not a fresh-launch
        // failure). Captured here on the EDT (the wait is bounded at 2s so we
        // don't freeze the dialog); used by the poller's timeout branch below.
        val handedOffToRunningIde: Boolean = try {
            val proc = (spawn as? SpawnResult.Started)?.process
            if (proc != null) {
                val exited = proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                exited && proc.exitValue() == 0
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
        val socketPath = DelegationPaths.socketFor(selected.path)
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Waiting for IDE…", true) {
                override fun run(indicator: ProgressIndicator) {
                    val poller = AutoLaunchPoller(
                        socketPath = socketPath,
                        scope = CoroutineScope(Dispatchers.IO),
                        pingFn = { sp ->
                            indicator.checkCanceled()
                            DelegationClient.ping(sp)
                        },
                    )
                    val outcome = runBlockingCancellable {
                        poller.awaitOrTimeout()
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (outcome is AutoLaunchOutcome.Ready) {
                            doDelegate(selected)
                        } else if (handedOffToRunningIde) {
                            // Strong signal: the launcher handed off to an
                            // already-running IDE that never bound an inbound
                            // socket. Almost always = "Accept incoming
                            // delegations from other IDEs" is OFF in that IDE.
                            showInlineLaunchFailure(
                                "<html>The target IDE appears to be running, but it didn't bind an " +
                                    "inbound delegation socket within 90 s.<br><br>" +
                                    "<b>Most likely cause:</b> the target IDE does not have " +
                                    "<b>Accept incoming delegations from other IDEs</b> enabled.<br><br>" +
                                    "In the target IDE: Settings → Tools → Workflow Orchestrator → " +
                                    "Cross-IDE Delegation → enable the inbound checkbox, then restart " +
                                    "that IDE. Then click <b>Retry probe</b>.</html>"
                            )
                        } else {
                            showInlineLaunchFailure(
                                "<html>Timed out after 90 s waiting for the target IDE.<br><br>" +
                                    "<b>Likely causes</b> (in order):<br>" +
                                    "1. The target IDE doesn't have <b>Accept incoming delegations from other IDEs</b> " +
                                    "enabled (Settings → Tools → Workflow Orchestrator → Cross-IDE Delegation).<br>" +
                                    "2. The IDE failed to start or opened a different project.<br>" +
                                    "3. A different IDE flavor (Toolbox) opened the project.<br><br>" +
                                    "Verify the setting, restart the target IDE, then click <b>Retry probe</b>.</html>"
                            )
                        }
                    }
                }
            }
        )
    }

    /**
     * Completes delegation for the given entry — the same action the Delegate (OK)
     * button performs. Sets [selectedEntry] and closes the dialog.
     */
    private fun doDelegate(entry: PickerEntry) {
        if (isDisposed) {
            // The user clicked Cancel between the auto-launch poll succeeding and this
            // callback firing on the EDT. The dialog is already disposed; calling
            // super.doOKAction() would NPE inside DialogWrapper. Just return.
            return
        }
        selectedEntry = entry
        super.doOKAction()
    }

    /**
     * Synchronous phase: read the recent-projects list from [RecentProjectsManagerBase]
     * and populate the list model with CLOSED/MISSING entries. No I/O is performed here —
     * status probing happens in [triggerDiscoveryAsync]. This runs on the EDT so the picker
     * opens immediately without blocking.
     */
    private fun populateRecentsSync() {
        val mgr = try {
            RecentProjectsManager.getInstance() as? RecentProjectsManagerBase
        } catch (e: Exception) {
            LOG.warn("Failed to access RecentProjectsManager", e)
            return
        } ?: run {
            LOG.warn("RecentProjectsManager is not a RecentProjectsManagerBase instance")
            return
        }
        val recentPaths: List<String> = try {
            mgr.getRecentPaths()
        } catch (e: Exception) {
            LOG.warn("Failed to read recent project paths", e)
            return
        }
        if (recentPaths.isNotEmpty()) {
            // Section header — the renderer uppercases the displayName and trails a separator.
            // Per Plan 5.3 §4.2.
            listModel.addElement(
                PickerEntry(
                    displayName = "Recent",
                    path = Path.of("/"),
                    status = PickerEntry.Status.MISSING,
                    isHeader = true,
                )
            )
        }
        for (pathStr in recentPaths) {
            val path = Path.of(pathStr)
            val name = mgr.getDisplayName(pathStr) ?: path.fileName?.toString() ?: pathStr
            // Show MISSING for non-existent paths immediately; CLOSED for the rest until
            // the async probe upgrades them to RUNNING.
            val initialStatus = if (!Files.exists(path)) PickerEntry.Status.MISSING else PickerEntry.Status.CLOSED
            listModel.addElement(PickerEntry(path, name, initialStatus))
        }
    }

    /**
     * Asynchronous phase: probe each recent-project socket and run socket-glob discovery,
     * both off the EDT. When results arrive, post back to the EDT to update the model.
     * Cancelled automatically when the dialog is disposed.
     *
     * Spec §5.5 + Plan 3.1 Fix 3: EDT must not block for N × ping-timeout on open.
     */
    private fun triggerDiscoveryAsync() {
        discoveryScope.launch {
            // Step A: probe recent-project sockets and upgrade CLOSED → RUNNING as pongs arrive.
            val recentsSnapshot: List<PickerEntry> = withContext(Dispatchers.Main) {
                // Safe to read listModel on EDT (Dispatchers.Main).
                (0 until listModel.size()).map { listModel.getElementAt(it) }
            }
            for (entry in recentsSnapshot) {
                if (entry.isHeader || entry.status == PickerEntry.Status.MISSING) continue
                val socketPath = DelegationPaths.socketFor(entry.path)
                val pong = try {
                    DelegationClient.ping(socketPath, timeoutMillis = 200)
                } catch (e: Exception) {
                    null
                }
                if (pong != null) {
                    withContext(Dispatchers.Main) {
                        if (isDisposed) return@withContext
                        val idx = listModel.indexOf(entry)
                        if (idx >= 0) {
                            listModel.set(idx, entry.copy(status = PickerEntry.Status.RUNNING))
                            // Update auto-select hint if this newly-RUNNING row matches.
                            suggestedRepo?.let { hint ->
                                if (entry.displayName.contains(hint, ignoreCase = true)) {
                                    list.setSelectedValue(listModel.getElementAt(idx), true)
                                }
                            }
                        }
                    }
                }
            }

            // Step B: socket-glob discovery — find IDE-B instances not in recents.
            val recentNormPaths: Set<Path> = recentsSnapshot
                .filter { !it.isHeader }
                .map { it.path.toAbsolutePath().normalize() }
                .toSet()
            val discovered: List<DiscoveredProject> = try {
                SocketGlobDiscovery(
                    pingFn = { socketPath -> DelegationClient.ping(socketPath) },
                ).discover()
            } catch (e: Exception) {
                LOG.warn("Socket-glob discovery failed (non-fatal)", e)
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (isDisposed) return@withContext
                appendDiscoveredEntries(discovered, recentNormPaths)
            }
        }
    }

    /**
     * Appends discovered projects that are not already in the recents list.
     * Must be called on the EDT. [recentNormPaths] is the normalized-path set of
     * recents captured at discovery-start time (dedup against what was in the list
     * when probing began; headers are excluded).
     */
    private fun appendDiscoveredEntries(discovered: List<DiscoveredProject>, recentNormPaths: Set<Path>) {
        val novel = discovered.filter {
            Path.of(it.projectPath).toAbsolutePath().normalize() !in recentNormPaths
        }
        if (novel.isEmpty()) return

        // Insert a non-selectable section header row before the discovered entries.
        // The renderer uppercases the displayName and trails a separator.
        listModel.addElement(
            PickerEntry(
                displayName = "Other JetBrains instances",
                path = Path.of("/"),
                status = PickerEntry.Status.MISSING,
                isHeader = true,
            )
        )
        for (d in novel) {
            listModel.addElement(
                PickerEntry(
                    displayName = Path.of(d.projectPath).fileName?.toString() ?: d.projectPath,
                    path = Path.of(d.projectPath),
                    status = PickerEntry.Status.RUNNING,
                )
            )
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationPicker::class.java)
    }
}
