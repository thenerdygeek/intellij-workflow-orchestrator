package com.workflow.orchestrator.handover.ui.cards

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.copyright.CopyrightFileEntry
import com.workflow.orchestrator.core.copyright.CopyrightFixService
import com.workflow.orchestrator.core.copyright.CopyrightStatus
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.handover.service.HandoverStateService
import com.workflow.orchestrator.handover.ui.cards.handoverPanelHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.time.Year
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * Lists copyright header status for files in the active changelist and lets the
 * user fix outdated years / insert missing headers in one click. Wired in Phase 2:
 * Rescan walks `ChangeListManager.allChanges` and feeds `CopyrightFixService.analyzeFile`;
 * Fix All applies year-consolidation or template-insertion via a single
 * `WriteCommandAction` (so Cmd-Z reverses the whole batch).
 */
class CopyrightFixCard(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(CopyrightFixCard::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val listModel = DefaultListModel<CopyrightFileEntry>()
    private val fileList = JBList(listModel).apply {
        cellRenderer = CopyrightCellRenderer()
    }
    private val fixAllButton = JButton("Fix All").apply {
        isEnabled = false
        toolTipText = "Click Rescan first"
    }
    private val rescanButton = JButton("Rescan").apply {
        toolTipText = "Re-check copyright headers in the current changelist"
    }
    private val statusLabel = JBLabel("").apply {
        foreground = StatusColors.SECONDARY_TEXT
    }
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val emptyLabel = JBLabel("No files to check.").apply {
        foreground = StatusColors.SECONDARY_TEXT
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(40)
    }

    init {
        border = JBUI.Borders.empty(8)

        rescanButton.addActionListener { onRescan() }
        fixAllButton.addActionListener { onFixAll() }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(fixAllButton)
            add(rescanButton)
        }
        val southPanel = JPanel(BorderLayout()).apply {
            add(buttonPanel, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        cardPanel.add(JBScrollPane(fileList), "list")
        cardPanel.add(emptyLabel, "empty")
        cardLayout.show(cardPanel, "empty")

        add(handoverPanelHeader("COPYRIGHT HEADER STATUS"), BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)
    }

    fun setEntries(entries: List<CopyrightFileEntry>) {
        listModel.clear()
        entries.forEach { listModel.addElement(it) }
        cardLayout.show(cardPanel, if (entries.isEmpty()) "empty" else "list")
        refreshFixAllState(entries)
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private fun onRescan() {
        rescanButton.isEnabled = false
        statusLabel.text = "Scanning..."
        scope.launch {
            try {
                val entries = scanChangelist()
                withContext(Dispatchers.EDT) {
                    setEntries(entries)
                    val fixCount = entries.count { it.status != CopyrightStatus.OK }
                    statusLabel.text = if (entries.isEmpty()) {
                        "No changed files."
                    } else {
                        "${entries.size} file(s) — $fixCount need fixing"
                    }
                    rescanButton.isEnabled = true
                }
            } catch (t: Throwable) {
                log.warn("[Handover:Copyright] Rescan failed", t)
                withContext(Dispatchers.EDT) {
                    statusLabel.text = "Scan failed: ${t.message?.take(60).orEmpty()}"
                    rescanButton.isEnabled = true
                }
            }
        }
    }

    private fun onFixAll() {
        val entries = (0 until listModel.size()).map { listModel.getElementAt(it) }
        val nonOk = entries.filter { it.status != CopyrightStatus.OK }
        if (nonOk.isEmpty()) return

        val template = PluginSettings.getInstance(project).state.copyrightTemplate.orEmpty()
        if (template.isBlank() && nonOk.any { it.status == CopyrightStatus.MISSING_HEADER }) {
            statusLabel.text = "Set a Copyright template in Settings → Builds & Health Checks"
            log.info("[Handover:Copyright] Fix-all aborted: copyrightTemplate is blank and at least one file is MISSING_HEADER")
            return
        }

        fixAllButton.isEnabled = false
        rescanButton.isEnabled = false
        statusLabel.text = "Fixing ${nonOk.size} file(s)..."

        scope.launch {
            val failures = applyFixes(nonOk, template)
            withContext(Dispatchers.EDT) {
                if (failures.isEmpty()) {
                    statusLabel.text = "Fixed ${nonOk.size} file(s)"
                    HandoverStateService.getInstance(project).markCopyrightFixed()
                } else {
                    statusLabel.text = "Fixed ${nonOk.size - failures.size} of ${nonOk.size} (${failures.size} failed)"
                    WorkflowNotificationService.getInstance(project).notifyWarning(
                        "workflow.handover",
                        "Copyright fix incomplete",
                        "Could not fix:\n" + failures.joinToString("\n") { "• $it" }
                    )
                }
                rescanButton.isEnabled = true
                // Re-scan so the UI reflects post-fix state.
                // Called inside withContext(EDT) so the initial Swing mutations in
                // onRescan() (rescanButton.isEnabled = false, statusLabel.text) execute on the EDT.
                onRescan()
            }
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private suspend fun scanChangelist(): List<CopyrightFileEntry> {
        val service = CopyrightFixService.getInstance(project)
        val proj = project
        // Snapshot files inside a read action — VirtualFile predicates touch indices.
        val files = readAction {
            ChangeListManager.getInstance(proj).allChanges
                .mapNotNull { it.virtualFile ?: it.afterRevision?.file?.virtualFile }
                .filter { it.exists() && service.isSourceFile(it) && !service.isGeneratedFile(it) }
                .distinctBy { it.path }
        }
        if (files.isEmpty()) return emptyList()

        val currentYear = Year.now().value
        return files.map { vf ->
            val content = readAction { vf.contentsToByteArray().toString(Charsets.UTF_8) }
            service.analyzeFile(vf.path, content, currentYear)
        }
    }

    /** Applies all fixes inside a single WriteCommandAction (one undo step). Returns failed paths. */
    private suspend fun applyFixes(
        nonOk: List<CopyrightFileEntry>,
        template: String
    ): List<String> = withContext(Dispatchers.EDT) {
        val service = CopyrightFixService.getInstance(project)
        val currentYear = Year.now().value
        val failed = mutableListOf<String>()

        WriteCommandAction.runWriteCommandAction(project, "Fix Copyright Headers", null, Runnable {
            for (entry in nonOk) {
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(entry.filePath)
                if (vf == null || !vf.isValid || vf.isWritable.not()) {
                    failed.add(entry.filePath)
                    continue
                }
                try {
                    val document = FileDocumentManager.getInstance().getDocument(vf)
                    if (document == null) {
                        failed.add(entry.filePath)
                        continue
                    }
                    val original = document.text
                    val updated = when (entry.status) {
                        CopyrightStatus.YEAR_OUTDATED -> {
                            val headerRegion = original.lines().take(CopyrightFixService.HEADER_SCAN_LINES).joinToString("\n")
                            val rewritten = service.updateYearInHeader(headerRegion, currentYear)
                            if (rewritten == headerRegion) original
                            else {
                                // Reassemble: rewritten header region + the rest verbatim.
                                // Use the same HEADER_SCAN_LINES constant as CopyrightFixService.analyzeFile
                                // to avoid a scan-window mismatch that would leave years in lines 16-30 unpatched.
                                val rest = original.lines().drop(CopyrightFixService.HEADER_SCAN_LINES).joinToString("\n")
                                if (rest.isEmpty()) rewritten else "$rewritten\n$rest"
                            }
                        }
                        CopyrightStatus.MISSING_HEADER -> {
                            val rendered = service.prepareHeader(template, currentYear)
                            val wrapped = service.wrapForLanguage(rendered, vf)
                            "$wrapped\n\n$original"
                        }
                        CopyrightStatus.OK -> original
                    }
                    if (updated != original) {
                        document.setText(updated)
                        FileDocumentManager.getInstance().saveDocument(document)
                    }
                } catch (t: Throwable) {
                    log.warn("[Handover:Copyright] Failed to fix ${entry.filePath}", t)
                    failed.add(entry.filePath)
                }
            }
        })
        failed
    }

    private fun refreshFixAllState(entries: List<CopyrightFileEntry>) {
        val nonOk = entries.count { it.status != CopyrightStatus.OK }
        fixAllButton.isEnabled = nonOk > 0
        fixAllButton.toolTipText = when {
            entries.isEmpty() -> "Click Rescan first"
            nonOk == 0 -> "All files are OK"
            else -> "Fix $nonOk file(s) in one batch"
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}

/**
 * P2-20: rubber-stamp cell renderer for copyright file entries. All widgets are
 * allocated once as instance fields and mutated per render call, avoiding per-row
 * JPanel + JBLabel allocations on every scroll/repaint event.
 *
 * Rubber-stamp hygiene: ALL per-row properties (text, icon, foreground, background,
 * opacity) are reset on EVERY [getListCellRendererComponent] call — no property
 * leaks from the "selected" branch to the "normal" branch or vice versa.
 */
private class CopyrightCellRenderer : ListCellRenderer<CopyrightFileEntry> {

    private val row = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(2, 6)
    }
    private val left = JBLabel("", SwingConstants.LEFT).apply {
        font = font.deriveFont(Font.PLAIN)
    }
    private val right = JBLabel("").apply {
        font = font.deriveFont(Font.PLAIN, font.size - 1f)
        border = JBUI.Borders.emptyLeft(8)
    }

    init {
        row.add(left, BorderLayout.CENTER)
        row.add(right, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out CopyrightFileEntry>,
        value: CopyrightFileEntry,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val (icon, statusColor, suffix) = when (value.status) {
            CopyrightStatus.OK -> Triple(AllIcons.General.InspectionsOK, StatusColors.SUCCESS, "OK")
            CopyrightStatus.YEAR_OUTDATED -> {
                val transition = "${value.oldYear ?: "?"} → ${value.newYear ?: "?"}"
                Triple(AllIcons.General.BalloonWarning, StatusColors.WARNING, transition)
            }
            CopyrightStatus.MISSING_HEADER -> Triple(AllIcons.General.Add, StatusColors.ERROR, "missing")
        }

        // Reset ALL per-row properties on every call (rubber-stamp hygiene).
        left.text = relativeProjectPath(value.filePath)
        left.icon = icon
        right.text = suffix

        if (isSelected) {
            row.background = list.selectionBackground
            row.isOpaque = true
            left.foreground = list.selectionForeground
            right.foreground = list.selectionForeground
        } else {
            row.background = list.background
            row.isOpaque = false
            left.foreground = list.foreground
            right.foreground = statusColor
        }

        return row
    }

    private fun relativeProjectPath(path: String): String {
        val idx = path.lastIndexOf('/')
        if (idx < 0) return path
        // Show "<parent>/<name>" — enough context to disambiguate without overflowing.
        val name = path.substring(idx + 1)
        val parentEnd = idx
        val parentStart = path.lastIndexOf('/', parentEnd - 1).coerceAtLeast(0)
        val parent = path.substring(parentStart + 1, parentEnd)
        return if (parent.isBlank()) name else "$parent/$name"
    }
}
