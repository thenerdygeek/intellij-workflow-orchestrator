package com.workflow.orchestrator.agent.delegation.ui

import com.intellij.ide.ReopenProjectAction
import com.intellij.ide.RecentProjectListActionProvider
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
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.workflow.orchestrator.agent.delegation.AutoLaunchOutcome
import com.workflow.orchestrator.agent.delegation.AutoLaunchPoller
import com.workflow.orchestrator.agent.delegation.DefaultProcessSpawner
import com.workflow.orchestrator.agent.delegation.LauncherResolver
import com.workflow.orchestrator.agent.delegation.SpawnResult
import com.workflow.orchestrator.agent.delegation.ToolboxFlavorReader
import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Action
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
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
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §5.1, §5.2, §5.3.
 */
class DelegationPicker(
    private val project: Project,
    private val suggestedRepo: String?,
) : DialogWrapper(project) {

    var selectedEntry: PickerEntry? = null
        private set

    private val listModel = DefaultListModel<PickerEntry>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        // Render section headers in bold/italic; prevent them from being selected.
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val entry = value as? PickerEntry
                val selected = isSelected && entry?.isHeader != true
                val component = super.getListCellRendererComponent(list, value, index, selected, cellHasFocus && !selected)
                if (entry?.isHeader == true && component is JLabel) {
                    component.font = component.font.deriveFont(Font.BOLD or Font.ITALIC)
                    component.isEnabled = false
                }
                return component
            }
        }
    }

    // ---- Auto-launch UI affordances ----------------------------------------

    /** Yellow informational banner shown when Toolbox is detected but flavor is unknown. */
    private val toolboxUnknownBanner = JBLabel(
        "Toolbox detected: IDE flavor unknown — launching with current IDE flavor."
    ).apply {
        foreground = JBColor(java.awt.Color(0x8A6000), java.awt.Color(0xFFD600))
        isVisible = false
    }

    /** Red inline failure label shown after spawn failure or 90s timeout. */
    private val launchFailureLabel = JBLabel("").apply {
        foreground = JBColor.RED
        isVisible = false
    }

    /** Retry probe button — hidden by default; revealed after a launch failure. */
    private val retryProbeButton = javax.swing.JButton("Retry probe").apply {
        isVisible = false
        addActionListener { onRetryProbe() }
    }

    // ---- DialogWrapper button actions --------------------------------------

    private var launchAndDelegateAction: Action? = null

    init {
        title = "Delegate to Another IDE"
        setOKButtonText("Delegate")
        init()
        // Probe at open time using runBlockingCancellable so ProgressIndicator
        // cancellation propagates correctly. Each probe is timeout-bounded at
        // ~200ms via DelegationClient.ping.
        runBlockingCancellable { populate() }
        suggestedRepo?.let { hint ->
            val match = (0 until listModel.size())
                .map { listModel.get(it) }
                .firstOrNull { it.status == PickerEntry.Status.RUNNING && it.displayName.contains(hint, ignoreCase = true) }
            if (match != null) list.setSelectedValue(match, true)
        }
        // Keep the Launch & Delegate button state in sync with the selection.
        list.addListSelectionListener { updateLaunchButtonState() }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        // Top: hint label + toolbox-unknown banner
        val northPanel = JPanel(java.awt.GridLayout(0, 1))
        northPanel.add(JLabel("Pick a target IDE for delegation (must be Running):"))
        northPanel.add(toolboxUnknownBanner)
        panel.add(northPanel, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(list).apply {
            preferredSize = Dimension(640, 320)
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        // South: inline failure label + retry button
        val southPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        southPanel.add(launchFailureLabel)
        southPanel.add(retryProbeButton)
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
        launchFailureLabel.text = "<html>$reason</html>"
        launchFailureLabel.isVisible = true
        retryProbeButton.isVisible = true
        pack()
    }

    /** Shows a non-modal yellow informational banner above the action area. */
    private fun showToolboxUnknownBanner() {
        toolboxUnknownBanner.isVisible = true
        pack()
    }

    /** Hides any previously-shown failure UI. */
    private fun hideLaunchFailure() {
        launchFailureLabel.isVisible = false
        retryProbeButton.isVisible = false
        toolboxUnknownBanner.isVisible = false
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
        val launcher = LauncherResolver()
        if (launcher.isToolboxInstall()) {
            val flavor = ToolboxFlavorReader().readLastUsedFlavor(selected.path)
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
        val spawn = DefaultProcessSpawner.spawn(launcher.resolveLauncher(), selected.path)
        if (spawn is SpawnResult.Failed) {
            showInlineLaunchFailure("Could not spawn IDE process: ${spawn.message}")
            return
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
                        } else {
                            showInlineLaunchFailure(
                                "Timed out after 90 s waiting for IDE-B. Open the project manually, " +
                                    "then click Retry probe."
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
        selectedEntry = entry
        super.doOKAction()
    }

    private suspend fun populate() {
        val actions = try {
            RecentProjectListActionProvider.getInstance().getActions(false)
        } catch (e: Exception) {
            LOG.warn("Failed to read recent projects", e)
            emptyList()
        }
        val recents = mutableListOf<PickerEntry>()
        for (action in actions) {
            val reopen = action as? ReopenProjectAction ?: continue
            val pathStr = reopen.projectPath ?: continue
            val path = Path.of(pathStr)
            val name = reopen.projectName ?: path.fileName?.toString() ?: pathStr
            val status = when {
                !Files.exists(path) -> PickerEntry.Status.MISSING
                else -> {
                    val socketPath = DelegationPaths.socketFor(path)
                    val pong = DelegationClient.ping(socketPath, timeoutMillis = 200)
                    if (pong != null) PickerEntry.Status.RUNNING else PickerEntry.Status.CLOSED
                }
            }
            recents.add(PickerEntry(path, name, status))
        }
        recents.forEach { listModel.addElement(it) }

        // Plan 3 §5.5: socket-glob supplement. Discover IDE-B instances whose project
        // isn't in this IDE's recents. PONG returns the project path; we resolve a
        // display name from the path's last segment (best-effort). The picker dialog
        // runs on EDT so we bridge into the suspend `discover()` via
        // `runBlockingCancellable` (the pre-commit hook bans `runBlocking` in main/).
        val recentPaths = recents.map { it.path.toAbsolutePath().normalize() }.toSet()
        val discovered: List<PickerEntry> = try {
            SocketGlobDiscovery(
                pingFn = { socketPath ->
                    DelegationClient.ping(socketPath)
                },
            ).discover()
                .filter { Path.of(it.projectPath).toAbsolutePath().normalize() !in recentPaths }
                .map { d ->
                    PickerEntry(
                        displayName = Path.of(d.projectPath).fileName?.toString() ?: d.projectPath,
                        path = Path.of(d.projectPath),
                        status = PickerEntry.Status.RUNNING,
                    )
                }
        } catch (e: Exception) {
            LOG.warn("Socket-glob discovery failed (non-fatal)", e)
            emptyList()
        }
        if (discovered.isNotEmpty()) {
            // Insert a non-selectable section header row before the discovered entries.
            listModel.addElement(
                PickerEntry(
                    displayName = "— Discovered (not in recents) —",
                    path = Path.of("/"),
                    status = PickerEntry.Status.MISSING,
                    isHeader = true,
                )
            )
            discovered.forEach { listModel.addElement(it) }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationPicker::class.java)
    }
}
