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
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.delegation.AutoLaunchOutcome
import com.workflow.orchestrator.agent.delegation.AutoLaunchPoller
import com.workflow.orchestrator.agent.delegation.DefaultProcessSpawner
import com.workflow.orchestrator.agent.delegation.LauncherResolver
import com.workflow.orchestrator.agent.delegation.ProcessSpawner
import com.workflow.orchestrator.agent.delegation.SpawnResult
import com.workflow.orchestrator.agent.delegation.TargetStatusResolver
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
    enum class Status { RUNNING, AVAILABLE, CLOSED, MISSING }

    override fun toString(): String {
        if (isHeader) return displayName
        val badge = when (status) {
            Status.RUNNING -> "● Running"
            Status.AVAILABLE -> "◑ Available (inbound off)"
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
    /**
     * The task text being delegated.  Shown as a read-only truncated preview so
     * the user understands *what* is being sent before they choose a target.
     *
     * Defaults to `""` so existing test call-sites (pickTargetOverride lambdas,
     * reflective construction in [DialogModalityContractTest]) keep compiling
     * without passing this argument.
     */
    private val request: String = "",
    private val launcherResolver: LauncherResolver = LauncherResolver(),
    private val toolboxFlavorReader: ToolboxFlavorReader = ToolboxFlavorReader(),
    private val processSpawner: ProcessSpawner = DefaultProcessSpawner,
    // Project-modal (the default). pickTarget() in DelegationOutboundService shows this dialog via
    // showAndGet(), and DialogWrapper.showAndGet() throws
    // IllegalStateException("The showAndGet() method is for modal dialogs only") for any non-modal
    // (IdeModalityType.MODELESS) dialog — so the picker MUST stay modal.
    //
    // History: this was briefly MODELESS (commit 15b835d69) to let the EDT keep draining
    // invokeLater(NON_MODAL) so a launcher spawned *while the picker was open* could open its IDE
    // window. That changed the next day: since Plan 6 Task 8 (commit 3a9823d04) Launch & Delegate
    // closes the picker first (doDelegate → doOKAction) and DelegationOutboundService.send() owns the
    // whole knock → spawn → AutoLaunchPoller-wait sequence AFTER the picker is dismissed. Nothing
    // spawns a window while the picker is open anymore, so MODELESS is no longer needed — and it was
    // incompatible with showAndGet(). Pinned by DialogModalityContractTest.
) : DialogWrapper(project, true) {

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

    /**
     * Per-selection explainer that updates in [list]'s selection listener.
     * Starts empty; updated by [DelegationPickerExplainer.explainerFor] on every
     * selection change so the user knows what "Delegate" will do for each row.
     */
    private val explainerLabel = JBLabel("").apply {
        foreground = StatusColors.SECONDARY_TEXT
        border = BorderFactory.createEmptyBorder(4, 0, 2, 0)
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
        // Keep the Launch & Delegate button state and per-selection explainer in sync.
        list.addListSelectionListener {
            updateLaunchButtonState()
            updateExplainerLabel()
        }
    }

    override fun dispose() {
        discoveryScope.cancel()
        super.dispose()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        // Top: (1) intent header, (2) optional task preview, (3) toolbox-unknown banner.
        // Per Plan 5.3 §4.3 / §4.4 + enrichment spec.
        val northPanel = JPanel(java.awt.GridLayout(0, 1, 0, 6))

        // (1) Intent header — explains WHAT is being sent and WHERE it runs.
        val hintLabel = JBLabel(
            "<html>Send this task to another IDE's agent. " +
                "The task runs there under that IDE's own tool permissions — " +
                "you can watch or take over in that window.</html>"
        ).apply {
            foreground = StatusColors.SECONDARY_TEXT
            border = BorderFactory.createEmptyBorder(12, 0, 4, 0)
        }
        northPanel.add(hintLabel)

        // (2) Task preview — only when request is non-blank.
        // Truncated to REQUEST_PREVIEW_CHARS (280) with a total-chars suffix so a
        // multi-KB prompt can't blow out the dialog height.
        if (request.isNotBlank()) {
            val taskBeingSentLabel = JBLabel("<html><b>Task being sent:</b></html>").apply {
                border = BorderFactory.createEmptyBorder(4, 0, 2, 0)
            }
            northPanel.add(taskBeingSentLabel)

            val totalChars = request.length
            val truncated = if (totalChars > DelegationOutboundService.REQUEST_PREVIEW_CHARS) {
                request.take(DelegationOutboundService.REQUEST_PREVIEW_CHARS) +
                    "… ($totalChars chars total)"
            } else {
                request
            }
            val taskPreviewArea = JBTextArea(3, 60).apply {
                text = truncated
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }
            val taskPreviewScroll = JBScrollPane(taskPreviewArea).apply {
                // Fixed 3-row height; vertically scrollable for very wide content.
                preferredSize = Dimension(720, taskPreviewArea.preferredSize.height.coerceAtMost(80))
            }
            northPanel.add(taskPreviewScroll)
        }

        northPanel.add(toolboxUnknownBannerPanel)
        panel.add(northPanel, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(list).apply {
            preferredSize = Dimension(720, 320)
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        // South: (a) dynamic per-selection explainer, (b) failure panel + retry button,
        // (c) static inbound reminder — always visible.
        val southPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
        }

        // (a) Dynamic explainer — updates in the selection listener via updateExplainerLabel().
        val explainerRow = JPanel(BorderLayout()).apply { isOpaque = false }
        explainerRow.add(explainerLabel, BorderLayout.CENTER)
        southPanel.add(explainerRow, BorderLayout.NORTH)

        // (b) + (c) Failure panel + retry button + inbound reminder.
        southPanel.add(launchFailurePanel, BorderLayout.CENTER)
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
        // RUNNING and AVAILABLE are both delegatable:
        //   RUNNING  → delegation socket bound, connect directly.
        //   AVAILABLE → doorbell bound, inbound off; send() will ring the doorbell for consent.
        val isDelegatable = sel != null && !sel.isHeader &&
            (sel.status == PickerEntry.Status.RUNNING || sel.status == PickerEntry.Status.AVAILABLE)
        if (!isDelegatable) {
            // Disable OK for headers, CLOSED, and MISSING entries — silently no-op.
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

    /**
     * Updates [explainerLabel] text based on the currently-selected [PickerEntry].
     * Delegates to [DelegationPickerExplainer.explainerFor] so the text mapping is
     * pure and independently testable.
     */
    private fun updateExplainerLabel() {
        val sel = list.selectedValue
        explainerLabel.text = "<html>${DelegationPickerExplainer.explainerFor(sel)}</html>"
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
     * Retry probe: dual-probes the delegation and doorbell sockets once.
     *
     * Fix C: upgraded from single-socket to dual-probe so a running-but-inbound-off IDE
     * (doorbell reachable, delegation socket unbound) is correctly shown as AVAILABLE
     * (delegatable) rather than remaining CLOSED.
     */
    private fun onRetryProbe() {
        val sel = list.selectedValue ?: return
        if (sel.isHeader) return
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Probing IDE socket…", false) {
                override fun run(indicator: ProgressIndicator) {
                    val probed = runBlockingCancellable {
                        try {
                            TargetStatusResolver.dualProbeStatus(sel.path) { socketPath ->
                                DelegationClient.ping(socketPath, timeoutMillis = 2_000)
                            }
                        } catch (_: Exception) {
                            TargetStatusResolver.TargetStatus.CLOSED
                        }
                    }
                    val newStatus = when (probed) {
                        TargetStatusResolver.TargetStatus.RUNNING -> PickerEntry.Status.RUNNING
                        TargetStatusResolver.TargetStatus.AVAILABLE -> PickerEntry.Status.AVAILABLE
                        TargetStatusResolver.TargetStatus.CLOSED -> PickerEntry.Status.CLOSED
                        TargetStatusResolver.TargetStatus.MISSING -> PickerEntry.Status.MISSING
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (newStatus == PickerEntry.Status.RUNNING || newStatus == PickerEntry.Status.AVAILABLE) {
                            val idx = listModel.indexOf(sel)
                            if (idx >= 0) {
                                listModel.set(idx, sel.copy(status = newStatus))
                                list.setSelectedIndex(idx)
                            }
                            hideLaunchFailure()
                        } else {
                            showInlineLaunchFailure("${sel.displayName} is still not reachable. Open the project manually, then click Retry probe.")
                        }
                    }
                }
            }
        )
    }

    /**
     * Launch & Delegate for a CLOSED (or inbound-off) target.
     *
     * Plan 6 Task 8 — routing fix: this no longer spawns the launcher and polls the
     * delegation socket here. That was a dead end for the doorbell flow — the
     * delegation socket only binds AFTER the target user consents, so polling it for
     * 90 s always timed out for an inbound-off target and never reached [send].
     *
     * Instead we hand the picked CLOSED target straight to [DelegationOutboundService.send]
     * (via [doDelegate] → OK), whose `knockAndWaitForBind` flow now owns the full
     * knock-and-wait sequence: write the pending request, ring the doorbell, spawn the
     * launcher if the doorbell is unreachable, then wait for the door to bind after the
     * target user consents (or bail on a declined marker). The Toolbox flavor pre-flight
     * check is retained here because it needs the picker's EDT modality + dialogs.
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
        // Route to send() — it owns the knock → consent → bind → connect flow,
        // including spawning the launcher when the doorbell is unreachable.
        doDelegate(selected)
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
     *
     * Fix C: uses [TargetStatusResolver.dualProbeStatus] (shared with the `list_targets` tool)
     * for doorbell-aware dual-probe so RUNNING/AVAILABLE/CLOSED/MISSING can't diverge between
     * the picker and the tool action.
     */
    private fun triggerDiscoveryAsync() {
        discoveryScope.launch {
            // Step A: probe recent-project sockets — upgrade CLOSED → RUNNING or AVAILABLE.
            // Uses the shared dual-probe so picker and list_targets always agree.
            val recentsSnapshot: List<PickerEntry> = withContext(Dispatchers.Main) {
                // Safe to read listModel on EDT (Dispatchers.Main).
                (0 until listModel.size()).map { listModel.getElementAt(it) }
            }
            for (entry in recentsSnapshot) {
                if (entry.isHeader || entry.status == PickerEntry.Status.MISSING) continue
                val probed: TargetStatusResolver.TargetStatus = try {
                    TargetStatusResolver.dualProbeStatus(entry.path)
                } catch (e: Exception) {
                    TargetStatusResolver.TargetStatus.CLOSED
                }
                val newStatus = when (probed) {
                    TargetStatusResolver.TargetStatus.RUNNING -> PickerEntry.Status.RUNNING
                    TargetStatusResolver.TargetStatus.AVAILABLE -> PickerEntry.Status.AVAILABLE
                    TargetStatusResolver.TargetStatus.CLOSED -> PickerEntry.Status.CLOSED
                    TargetStatusResolver.TargetStatus.MISSING -> PickerEntry.Status.MISSING
                }
                if (newStatus != entry.status) {
                    withContext(Dispatchers.Main) {
                        if (isDisposed) return@withContext
                        val idx = listModel.indexOf(entry)
                        if (idx >= 0) {
                            listModel.set(idx, entry.copy(status = newStatus))
                            // Update auto-select hint for RUNNING/AVAILABLE rows.
                            if (newStatus == PickerEntry.Status.RUNNING || newStatus == PickerEntry.Status.AVAILABLE) {
                                suggestedRepo?.let { hint ->
                                    if (entry.displayName.contains(hint, ignoreCase = true)) {
                                        list.setSelectedValue(listModel.getElementAt(idx), true)
                                    }
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
