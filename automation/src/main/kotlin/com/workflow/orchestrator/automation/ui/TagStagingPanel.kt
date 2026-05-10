package com.workflow.orchestrator.automation.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.model.RegistryStatus
import com.workflow.orchestrator.automation.model.TagEntry
import com.workflow.orchestrator.automation.model.TagSource
import com.workflow.orchestrator.automation.service.TagBuilderService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.ui.ClipboardUtil
import com.intellij.ui.components.JBScrollPane
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Font
import java.awt.datatransfer.DataFlavor
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class TagStagingPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val tagBuilderService by lazy { project.getService(TagBuilderService::class.java) }

    private val tableModel = TagTableModel(onEntriesChanged = ::refreshButtonStates)
    private val table = JBTable(tableModel)
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val emptyLabel = JBLabel("No docker tags configured.").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(40)
    }

    /** Baseline snapshot set by AutomationPanel whenever a baseline-source update arrives. */
    private var baselineSnapshot: List<TagEntry>? = null

    // Action buttons
    private val copyButton = JButton("Copy").apply {
        toolTipText = "Copy current docker tags as JSON to clipboard"
        isEnabled = false
    }
    private val pasteButton = JButton("Paste").apply {
        toolTipText = "Replace table with dockerTagsAsJson from clipboard"
        isEnabled = true
    }
    private val revertButton = JButton("Revert").apply {
        toolTipText = "Restore auto-detected baseline tags"
        isEnabled = false
    }

    init {
        border = JBUI.Borders.emptyTop(4)

        table.apply {
            setShowGrid(false)
            intercellSpacing = JBUI.size(0, 0)
            rowHeight = JBUI.scale(28)
            columnModel.getColumn(0).preferredWidth = JBUI.scale(150)
            columnModel.getColumn(1).preferredWidth = JBUI.scale(250)
            columnModel.getColumn(2).preferredWidth = JBUI.scale(120)

            columnModel.getColumn(1).cellEditor = DefaultCellEditor(JTextField())
            setDefaultRenderer(Any::class.java, TagTableCellRenderer())

            // Stitch: uppercase bold table headers
            tableHeader.defaultRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?, isSelected: Boolean,
                    hasFocus: Boolean, row: Int, col: Int
                ): Component {
                    val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
                    if (c is JLabel) {
                        c.text = (value as? String)?.uppercase() ?: ""
                        c.font = c.font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                        c.foreground = StatusColors.SECONDARY_TEXT
                        c.border = JBUI.Borders.empty(4, 6)
                    }
                    return c
                }
            }
        }

        val buildVariableName = PluginSettings.getInstance(project).state.bambooBuildVariableName
            ?.takeIf { it.isNotBlank() } ?: "DockerTagsAsJSON"
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(4)
            isOpaque = false
            add(JBLabel("DOCKER TAGS").apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                alignmentX = LEFT_ALIGNMENT
            })
            add(JBLabel("Sent to Bamboo as build variable: $buildVariableName").apply {
                font = font.deriveFont(JBUI.scale(10).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                alignmentX = LEFT_ALIGNMENT
                toolTipText = "Configurable in Settings → Workflow Orchestrator. " +
                    "Trigger Now / Queue Run will POST these tags as the JSON value of this variable."
            })
        }
        add(headerPanel, BorderLayout.NORTH)

        // Button row: Copy | Paste | Revert
        val buttonRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(2, 0, 4, 0)
            isOpaque = false
            add(copyButton)
            add(pasteButton)
            add(revertButton)
        }

        // North compound: title/subtitle + button row
        val northPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(headerPanel)
            add(buttonRow)
        }
        add(northPanel, BorderLayout.NORTH)

        cardPanel.add(JBScrollPane(table), "table")
        cardPanel.add(emptyLabel, "empty")
        cardLayout.show(cardPanel, "empty")
        add(cardPanel, BorderLayout.CENTER)

        wireButtons()
    }

    private fun wireButtons() {
        copyButton.addActionListener {
            val json = tagBuilderService.buildJsonPayload(tableModel.entries)
            ClipboardUtil.copyToClipboard(json)
        }

        pasteButton.addActionListener {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val text = try {
                clipboard.getData(DataFlavor.stringFlavor) as? String
            } catch (_: Exception) {
                null
            }
            if (text == null) {
                Messages.showWarningDialog(
                    project,
                    "Clipboard does not contain text.",
                    "Paste Failed"
                )
                return@addActionListener
            }
            val parsed = DockerTagsJsonParser.parse(text)
            if (parsed == null) {
                Messages.showWarningDialog(
                    project,
                    "Clipboard text is not a valid dockerTagsAsJson object.\n\nExpected format: {\"service-name\": \"tag\", ...}",
                    "Paste Failed"
                )
                return@addActionListener
            }
            updateTags(parsed)
        }

        revertButton.addActionListener {
            val snapshot = baselineSnapshot ?: return@addActionListener
            updateTags(snapshot)
        }
    }

    /**
     * Updates the table with [tags].
     *
     * This does NOT update [baselineSnapshot] — only [setBaseline] does that.
     * User edits via the cell editor or via Paste flow through here and must
     * not overwrite the baseline that Revert restores.
     */
    fun updateTags(tags: List<TagEntry>) {
        val oldEntries = tableModel.entries

        // Skip update entirely if data is identical
        if (oldEntries == tags) return

        tableModel.entries = tags

        if (oldEntries.size != tags.size) {
            // Row count changed — must do full structural update
            tableModel.fireTableDataChanged()
        } else {
            // Same row count — only fire updates for rows that actually changed
            for (i in tags.indices) {
                if (oldEntries[i] != tags[i]) {
                    tableModel.fireTableRowsUpdated(i, i)
                }
            }
        }
        cardLayout.show(cardPanel, if (tags.isEmpty()) "empty" else "table")
        refreshButtonStates()
    }

    /**
     * Called by [AutomationPanel] whenever the displayed tags originate from a
     * baseline-source update (auto-selected build or user-picked alternative).
     * Caches the list as the revert target and then updates the table.
     */
    fun setBaseline(tags: List<TagEntry>) {
        baselineSnapshot = tags
        updateTags(tags)
    }

    /** Get current tag entries (may have user edits). */
    fun getCurrentTags(): List<TagEntry> = tableModel.entries

    private fun refreshButtonStates() {
        val hasTags = tableModel.entries.isNotEmpty()
        copyButton.isEnabled = hasTags
        val snapshot = baselineSnapshot
        revertButton.isEnabled = snapshot != null && tableModel.entries != snapshot
    }

    override fun dispose() {}

    // -----------------------------------------------------------------------
    // Table model — 3 columns: Service | Docker Tag | Status
    // -----------------------------------------------------------------------

    private class TagTableModel(
        private val onEntriesChanged: () -> Unit = {}
    ) : AbstractTableModel() {
        var entries: List<TagEntry> = emptyList()

        private val columns = arrayOf("Service", "Docker Tag", "Status")

        override fun getRowCount() = entries.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(col: Int) = columns[col]

        override fun getValueAt(row: Int, col: Int): Any {
            val entry = entries[row]
            return when (col) {
                0 -> entry.serviceName
                1 -> entry.currentTag
                2 -> when {
                    entry.isCurrentRepo -> "Your branch"
                    entry.isDrift -> "⚠ Drift"
                    entry.registryStatus == RegistryStatus.VALID -> "✓ OK"
                    entry.registryStatus == RegistryStatus.NOT_FOUND -> "✗ Missing"
                    else -> ""
                }
                else -> ""
            }
        }

        override fun isCellEditable(row: Int, col: Int) = col == 1

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            if (col == 1 && value is String) {
                entries = entries.toMutableList().apply {
                    this[row] = this[row].copy(
                        currentTag = value,
                        source = TagSource.USER_EDIT,
                        registryStatus = RegistryStatus.UNKNOWN
                    )
                }
                fireTableRowsUpdated(row, row)
                onEntriesChanged()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Cell renderer — 2 data columns (monospace) + 1 status column (coloured)
    // -----------------------------------------------------------------------

    private class TagTableCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, col: Int
        ): Component {
            val component = super.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, col
            )

            // Stitch: monospace for service/tag columns
            if (col == 0 || col == 1) {
                font = Font(Font.MONOSPACED, font.style, font.size)
            }

            // Stitch: outline-style status indicator in Status column (col 2)
            if (col == 2) {
                val model = table.model as TagTableModel
                if (row < model.entries.size) {
                    val entry = model.entries[row]
                    foreground = when {
                        entry.isCurrentRepo -> StatusColors.LINK
                        entry.isDrift -> StatusColors.WARNING
                        entry.registryStatus == RegistryStatus.VALID -> StatusColors.SUCCESS
                        entry.registryStatus == RegistryStatus.NOT_FOUND -> StatusColors.ERROR
                        else -> StatusColors.SECONDARY_TEXT
                    }
                }
            }

            // Stitch: tonal background shifts instead of borders
            if (!isSelected) {
                val model = table.model as TagTableModel
                if (row < model.entries.size) {
                    val entry = model.entries[row]
                    background = when {
                        entry.isCurrentRepo -> StatusColors.SUCCESS_BG
                        entry.isDrift -> StatusColors.WARNING_BG
                        entry.registryStatus == RegistryStatus.NOT_FOUND -> JBColor(
                            ColorUtil.withAlpha(StatusColors.ERROR, 0.1),
                            ColorUtil.withAlpha(StatusColors.ERROR, 0.1)
                        )
                        else -> table.background
                    }
                }
            }
            return component
        }
    }

    // -----------------------------------------------------------------------
    // Paste parser — isolated so it is testable without a Project/Service
    // -----------------------------------------------------------------------

    internal object DockerTagsJsonParser {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses a dockerTagsAsJson string into a [TagEntry] list.
         * Returns `null` if [text] is not valid JSON or not a JSON object.
         */
        fun parse(text: String): List<TagEntry>? {
            return try {
                val obj = json.decodeFromString<JsonObject>(text)
                // Reject entries whose value is JSON null — a literal "null" tag is
                // almost certainly user error and must not silently produce bad payloads.
                if (obj.values.any { it is JsonNull }) return null
                obj.entries.map { (service, tagElement) ->
                    TagEntry(
                        serviceName = service,
                        currentTag = tagElement.jsonPrimitive.content,
                        latestReleaseTag = null,
                        source = TagSource.USER_EDIT,
                        registryStatus = RegistryStatus.UNKNOWN,
                        isDrift = false,
                        isCurrentRepo = false
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
