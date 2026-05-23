package com.workflow.orchestrator.agent.delegation.ui

import com.intellij.ide.ReopenProjectAction
import com.intellij.ide.RecentProjectListActionProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.workflow.orchestrator.core.delegation.DelegationClient
import com.workflow.orchestrator.core.delegation.DelegationPaths
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
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
    project: Project,
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
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(JLabel("Pick a target IDE for delegation (must be Running):"), BorderLayout.NORTH)
        val scrollPane = JBScrollPane(list).apply {
            preferredSize = Dimension(640, 320)
        }
        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
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
