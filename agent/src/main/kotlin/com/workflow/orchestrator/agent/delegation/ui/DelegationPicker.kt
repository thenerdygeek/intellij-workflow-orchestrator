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
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

data class PickerEntry(
    val path: Path,
    val displayName: String,
    val status: Status,
) {
    enum class Status { RUNNING, CLOSED, MISSING }

    override fun toString(): String {
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
        if (sel?.status != PickerEntry.Status.RUNNING) {
            // Disable OK for non-Running selections — silently no-op
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
            listModel.addElement(PickerEntry(path, name, status))
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationPicker::class.java)
    }
}
