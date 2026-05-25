package com.workflow.orchestrator.web.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.workflow.orchestrator.core.model.web.DomainAllowlistEntry
import com.workflow.orchestrator.core.util.InstantMoshiAdapter
import java.awt.BorderLayout
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * Panel embedding a [JBTable] of [DomainAllowlistEntry] items, with Add / Remove / Import / Export
 * buttons. Used inside [WebSettingsConfigurable] (group 2 — Fetch → Allowlist).
 *
 * Public contract:
 *  - [loadEntries] — populate the table from settings
 *  - [currentEntries] — extract typed entries from the current table state
 *  - [isModified] — true when table differs from the last [loadEntries] snapshot
 */
class AllowlistEditorPanel : JPanel(BorderLayout()) {

    private companion object {
        private val DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

        private val COLUMNS = arrayOf("Domain", "HTTP OK", "Added", "Last used")

        private val moshi: Moshi = Moshi.Builder()
            .add(InstantMoshiAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()

        @Suppress("UNCHECKED_CAST")
        private val listAdapter = moshi.adapter<List<DomainAllowlistEntry>>(
            com.squareup.moshi.Types.newParameterizedType(
                List::class.java, DomainAllowlistEntry::class.java
            )
        )
    }

    // ── Model ──────────────────────────────────────────────────────────────────

    private val tableModel = object : DefaultTableModel(COLUMNS, 0) {
        override fun getColumnClass(col: Int): Class<*> =
            if (col == 1) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, col: Int): Boolean = col == 1  // only httpOk is editable
    }

    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        tableHeader.reorderingAllowed = false
        columnModel.getColumn(0).preferredWidth = 300
        columnModel.getColumn(1).preferredWidth = 70
        columnModel.getColumn(1).maxWidth = 80
        columnModel.getColumn(2).preferredWidth = 100
        columnModel.getColumn(3).preferredWidth = 100
    }

    // Snapshot taken at load-time so isModified can compare
    private var snapshot: List<DomainAllowlistEntry> = emptyList()

    // Track whether any in-table edits have been made (beyond what snapshot tracks)
    var isModified: Boolean = false
        private set

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(buildButtonBar(), BorderLayout.SOUTH)

        tableModel.addTableModelListener { isModified = true }
    }

    // ── Button bar ────────────────────────────────────────────────────────────

    private fun buildButtonBar(): JPanel {
        val bar = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 4)
        }

        bar.add(JButton("+ Add domain").apply {
            addActionListener { onAdd() }
        })
        bar.add(JButton("− Remove").apply {
            addActionListener { onRemove() }
        })
        bar.add(JButton("Import…").apply {
            addActionListener { onImport() }
        })
        bar.add(JButton("Export…").apply {
            addActionListener { onExport() }
        })

        return bar
    }

    // ── Table population ──────────────────────────────────────────────────────

    /** Load [entries] into the table and reset the [snapshot] used by [isModified]. */
    fun loadEntries(entries: List<DomainAllowlistEntry>) {
        snapshot = entries.toList()
        refreshTable(entries)
        isModified = false
    }

    /** Extract the current table rows as typed [DomainAllowlistEntry] objects. */
    fun currentEntries(): List<DomainAllowlistEntry> {
        val rows = mutableListOf<DomainAllowlistEntry>()
        for (row in 0 until tableModel.rowCount) {
            val domain = tableModel.getValueAt(row, 0) as? String ?: continue
            val httpOk = tableModel.getValueAt(row, 1) as? Boolean ?: false
            val addedStr = tableModel.getValueAt(row, 2) as? String ?: ""
            val lastStr = tableModel.getValueAt(row, 3) as? String ?: ""
            val addedAt = parseDate(addedStr) ?: Instant.now()
            val lastUsedAt = parseDate(lastStr)
            rows += DomainAllowlistEntry(
                domain = domain.trim(),
                httpOk = httpOk,
                addedAt = addedAt,
                lastUsedAt = lastUsedAt,
            )
        }
        return rows
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun refreshTable(entries: List<DomainAllowlistEntry>) {
        tableModel.rowCount = 0
        for (e in entries) {
            tableModel.addRow(
                arrayOf(
                    e.domain,
                    e.httpOk,
                    DATE_FMT.format(e.addedAt),
                    e.lastUsedAt?.let { DATE_FMT.format(it) } ?: "",
                )
            )
        }
        tableModel.fireTableDataChanged()
    }

    private fun onAdd() {
        val domain = Messages.showInputDialog(
            this,
            "Enter the domain to allow (e.g. docs.example.com or *.example.com):",
            "Add Domain",
            Messages.getQuestionIcon(),
        )?.trim() ?: return
        if (domain.isBlank()) return
        tableModel.addRow(
            arrayOf(
                domain,
                false,
                DATE_FMT.format(Instant.now()),
                "",
            )
        )
    }

    private fun onRemove() {
        val row = table.selectedRow
        if (row < 0) {
            Messages.showInfoMessage(this, "Select a row first.", "Remove Domain")
            return
        }
        tableModel.removeRow(row)
    }

    private fun onImport() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Import allowlist (JSON)"
            fileSelectionMode = JFileChooser.FILES_ONLY
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile ?: return
        try {
            val json = file.readText()
            val imported = listAdapter.fromJson(json) ?: emptyList()
            refreshTable(imported)
            isModified = true
        } catch (e: Exception) {
            Messages.showErrorDialog(
                this,
                "Failed to import: ${e.message}",
                "Import Error"
            )
        }
    }

    private fun onExport() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Export allowlist (JSON)"
            fileSelectionMode = JFileChooser.FILES_ONLY
            selectedFile = File("web-allowlist.json")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile ?: return
        try {
            file.writeText(listAdapter.toJson(currentEntries()))
        } catch (e: Exception) {
            Messages.showErrorDialog(
                this,
                "Failed to export: ${e.message}",
                "Export Error"
            )
        }
    }

    private fun parseDate(s: String): Instant? {
        if (s.isBlank()) return null
        return try {
            // ISO-8601 full timestamp (from Moshi serialization)
            Instant.parse(s)
        } catch (_: Exception) {
            try {
                // yyyy-MM-dd display format
                java.time.LocalDate.parse(s, DATE_FMT).atStartOfDay(ZoneId.systemDefault()).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }
}
