package com.workflow.orchestrator.agent.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.TitledBorder

/**
 * Interactive tool testing panel that lets you pick any registered agent tool,
 * fill in parameters, execute it, and see the exact ToolResult the LLM receives.
 */
class ToolTestingPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Left: Tool List ──
    private val searchField = SearchTextField(false)
    private val toolListModel = DefaultListModel<ToolEntry>()
    private val toolList = JBList(toolListModel)
    private val allTools = mutableListOf<ToolEntry>()

    // ── Right: Detail Panel ──
    private val detailPanel = JPanel(BorderLayout())
    private val toolInfoLabel = JBLabel("Select a tool to inspect")
    private val descriptionArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont()
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(4, 8)
    }
    private val paramFormPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val executeButton = JButton("Execute").apply {
        icon = com.intellij.icons.AllIcons.Actions.Execute
    }
    private val clearButton = JButton("Clear").apply {
        icon = com.intellij.icons.AllIcons.Actions.GC
    }
    private val copyButton = JButton("Copy Output").apply {
        icon = com.intellij.icons.AllIcons.Actions.Copy
        isEnabled = false
    }

    // Result display
    private val statusLabel = JBLabel(" ")
    private val summaryLabel = JBLabel(" ")
    private val metadataLabel = JBLabel(" ")
    private val contentArea = JBTextArea().apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, 12)
        tabSize = 4
    }

    // State
    private val paramFields = mutableMapOf<String, JComponent>()
    private var selectedTool: AgentTool? = null
    private var lastResult: ToolResult? = null

    data class ToolEntry(
        val tool: AgentTool,
        val tier: String // "Core", "Deferred", "Integration"
    ) {
        override fun toString(): String = "${tool.name}  [$tier]"
    }

    init {
        border = JBUI.Borders.empty(4)
        buildUI()
        loadTools()

        // Cleanup
        scope.coroutineContext[Job]?.let { job ->
            com.intellij.openapi.util.Disposer.register(parentDisposable) { job.cancel() }
        }
    }

    // ── UI Construction ──────────────────────────────────────────────────

    private fun buildUI() {
        // Three vertical panes: Tool List | Params | Results
        val rightSplitter = JBSplitter(false, 0.5f).apply {
            firstComponent = buildMiddlePanel()
            secondComponent = buildResultPanel()
        }
        val outerSplitter = JBSplitter(false, 0.2f).apply {
            firstComponent = buildLeftPanel()
            secondComponent = rightSplitter
        }
        add(outerSplitter, BorderLayout.CENTER)
    }

    private fun buildLeftPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        // Search filter
        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                filterTools(searchField.text.trim())
            }
        })
        panel.add(searchField, BorderLayout.NORTH)

        // Tool list
        toolList.cellRenderer = ToolListRenderer()
        toolList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        toolList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                toolList.selectedValue?.let { onToolSelected(it) }
            }
        }
        panel.add(JBScrollPane(toolList), BorderLayout.CENTER)

        // Tool count label
        val countLabel = JBLabel("0 tools").apply {
            border = JBUI.Borders.empty(4, 8)
            foreground = UIUtil.getContextHelpForeground()
        }
        panel.add(countLabel, BorderLayout.SOUTH)
        toolList.model.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent) { countLabel.text = "${toolListModel.size()} tools" }
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent) { countLabel.text = "${toolListModel.size()} tools" }
            override fun contentsChanged(e: javax.swing.event.ListDataEvent) { countLabel.text = "${toolListModel.size()} tools" }
        })

        return panel
    }

    private fun buildMiddlePanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(0, 8, 0, 0)

        // ── Top: Tool info ──
        val infoPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(8)
        }
        toolInfoLabel.font = toolInfoLabel.font.deriveFont(Font.BOLD, 16f)
        infoPanel.add(toolInfoLabel, BorderLayout.NORTH)
        val descScroll = JBScrollPane(descriptionArea).apply {
            preferredSize = Dimension(0, 70)
            minimumSize = Dimension(0, 50)
        }
        infoPanel.add(descScroll, BorderLayout.CENTER)

        // ── Params ──
        val paramScroll = JBScrollPane(paramFormPanel).apply {
            border = JBUI.Borders.empty()
        }

        // Buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        executeButton.addActionListener { executeSelectedTool() }
        clearButton.addActionListener { clearResult() }
        copyButton.addActionListener { copyOutput() }
        buttonPanel.add(executeButton)
        buttonPanel.add(clearButton)
        buttonPanel.add(copyButton)

        panel.add(infoPanel, BorderLayout.NORTH)
        panel.add(paramScroll, BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun buildResultPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(0, 8, 0, 0)

        val resultHeader = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 8)
            add(statusLabel)
            add(summaryLabel)
            add(metadataLabel)
        }
        panel.add(resultHeader, BorderLayout.NORTH)
        panel.add(JBScrollPane(contentArea), BorderLayout.CENTER)

        return panel
    }

    // ── Tool Loading ─────────────���─────────────────────────��─────────────

    private fun loadTools() {
        try {
            val agentService = project.getService(AgentService::class.java)
            if (agentService == null) {
                toolInfoLabel.text = "AgentService not available — is the agent enabled?"
                return
            }

            val registry = agentService.registry
            categorizeTools(registry)
            filterTools("")
        } catch (e: Exception) {
            toolInfoLabel.text = "Failed to load tools: ${e.message}"
        }
    }

    private fun categorizeTools(registry: ToolRegistry) {
        allTools.clear()

        // We need to figure out which tools are core vs deferred.
        // Use the registry's active tools (core + active deferred) and all tools.
        val activeToolNames = registry.getActiveTools().keys
        val allRegistered = registry.allTools()

        for (tool in allRegistered) {
            val tier = when {
                tool.name in activeToolNames && registry.deferredCount() > 0 -> {
                    // It's in active tools — check if it was originally core
                    // Core tools are always in active; deferred tools may be activated
                    // We check if it's NOT in the deferred catalog (which only has non-activated deferred)
                    val deferredNames = registry.getDeferredCatalog().map { it.first }.toSet()
                    if (tool.name in deferredNames) "Deferred" else "Core"
                }
                tool.name in activeToolNames -> "Core"
                else -> "Deferred"
            }
            allTools.add(ToolEntry(tool, tier))
        }

        // Sort: Core first, then Deferred, alphabetical within each
        allTools.sortWith(compareBy<ToolEntry> {
            when (it.tier) { "Core" -> 0; "Deferred" -> 1; else -> 2 }
        }.thenBy { it.tool.name })
    }

    private fun filterTools(query: String) {
        toolListModel.clear()
        val lower = query.lowercase()
        val filtered = if (lower.isBlank()) allTools
        else allTools.filter {
            it.tool.name.lowercase().contains(lower) ||
                it.tool.description.lowercase().contains(lower) ||
                it.tier.lowercase().contains(lower)
        }
        filtered.forEach { toolListModel.addElement(it) }
    }

    // ── Tool Selection ──────────��────────────────────────────────────────

    private fun onToolSelected(entry: ToolEntry) {
        selectedTool = entry.tool
        val tool = entry.tool

        // Update info
        toolInfoLabel.text = "${tool.name}  [${entry.tier}]"
        descriptionArea.text = tool.description
        descriptionArea.caretPosition = 0

        // Build param form
        buildParamForm(tool)

        // Clear previous result
        clearResult()
    }

    private fun buildParamForm(tool: AgentTool) {
        paramFormPanel.removeAll()
        paramFields.clear()

        val params = tool.parameters
        if (params.properties.isEmpty()) {
            paramFormPanel.add(JBLabel("No parameters").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(8)
            })
            paramFormPanel.revalidate()
            paramFormPanel.repaint()
            return
        }

        for ((name, prop) in params.properties) {
            val isRequired = name in params.required
            val row = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 0)
                maximumSize = Dimension(Int.MAX_VALUE, 120)
            }

            // Label
            val label = JBLabel(buildString {
                append(name)
                if (isRequired) append(" *")
                append("  (${prop.type})")
            }).apply {
                font = if (isRequired) font.deriveFont(Font.BOLD) else font
                border = JBUI.Borders.emptyBottom(2)
            }

            // Input field
            val field: JComponent = when {
                prop.enumValues != null && prop.enumValues!!.isNotEmpty() -> {
                    ComboBox(DefaultComboBoxModel((listOf("") + prop.enumValues!!).toTypedArray())).apply {
                        selectedIndex = 0
                    }
                }
                prop.type == "boolean" -> JBCheckBox()
                prop.type == "string" && (name.contains("content") || name.contains("old_string") ||
                    name.contains("new_string") || name.contains("body") || name.contains("message") ||
                    name.contains("context") || name.contains("plan")) -> {
                    // Multi-line for text-heavy params
                    JBTextArea(4, 40).apply {
                        lineWrap = true
                        wrapStyleWord = true
                        border = JBUI.Borders.customLine(JBColor.border())
                    }
                }
                else -> JBTextField().apply {
                    columns = 40
                }
            }

            paramFields[name] = field

            // Description hint
            val hint = JBLabel(prop.description).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = font.deriveFont(font.size2D - 1f)
                border = JBUI.Borders.emptyTop(2)
            }

            val topRow = JPanel(BorderLayout())
            topRow.add(label, BorderLayout.NORTH)
            if (field is JBTextArea) {
                topRow.add(JBScrollPane(field).apply {
                    preferredSize = Dimension(0, 80)
                }, BorderLayout.CENTER)
            } else {
                topRow.add(field, BorderLayout.CENTER)
            }
            topRow.add(hint, BorderLayout.SOUTH)

            row.add(topRow, BorderLayout.CENTER)
            paramFormPanel.add(row)
        }

        // Add some glue at the bottom so fields don't stretch
        paramFormPanel.add(Box.createVerticalGlue())

        paramFormPanel.revalidate()
        paramFormPanel.repaint()
    }

    // ── Execution ─────────────��──────────────────────────────────────────

    private fun executeSelectedTool() {
        val tool = selectedTool ?: return

        // Collect params from form
        val jsonParams = buildJsonObject {
            for ((name, component) in paramFields) {
                val value = getFieldValue(component) ?: continue
                val prop = tool.parameters.properties[name] ?: continue

                when (prop.type) {
                    "integer" -> {
                        val intVal = value.toIntOrNull()
                        if (intVal != null) put(name, intVal)
                        else if (value.isNotBlank()) put(name, value) // let tool handle error
                    }
                    "boolean" -> put(name, value.toBoolean())
                    "number" -> {
                        val numVal = value.toDoubleOrNull()
                        if (numVal != null) put(name, numVal)
                        else if (value.isNotBlank()) put(name, value)
                    }
                    else -> put(name, value)
                }
            }
        }

        // Show what we're sending
        executeButton.isEnabled = false
        executeButton.text = "Running..."
        statusLabel.text = "Executing ${tool.name} ..."
        statusLabel.foreground = UIUtil.getLabelForeground()
        summaryLabel.text = "Params: $jsonParams"
        metadataLabel.text = " "
        contentArea.text = ""

        scope.launch {
            val startTime = System.currentTimeMillis()
            try {
                val result = tool.execute(jsonParams, project)
                val elapsed = System.currentTimeMillis() - startTime
                lastResult = result

                invokeLater {
                    displayResult(result, elapsed, jsonParams)
                    executeButton.isEnabled = true
                    executeButton.text = "Execute"
                    copyButton.isEnabled = true
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                invokeLater {
                    displayError(e, elapsed, jsonParams)
                    executeButton.isEnabled = true
                    executeButton.text = "Execute"
                }
            }
        }
    }

    private fun getFieldValue(component: JComponent): String? {
        val value = when (component) {
            is JBTextField -> component.text
            is JBTextArea -> component.text
            is JBCheckBox -> component.isSelected.toString()
            is ComboBox<*> -> (component.selectedItem as? String) ?: ""
            else -> null
        }
        return if (value.isNullOrBlank()) null else value
    }

    // ── Result Display ───────────────────────────────────────────────────

    private fun displayResult(result: ToolResult, elapsedMs: Long, params: JsonObject) {
        // Status line
        val statusIcon = if (result.isError) "FAIL" else "OK"
        val statusColor = if (result.isError) JBColor.RED else JBColor(Color(0, 128, 0), Color(0, 180, 0))
        statusLabel.text = "$statusIcon  |  ${elapsedMs}ms  |  ~${result.tokenEstimate} tokens"
        statusLabel.foreground = statusColor

        // Summary
        summaryLabel.text = "Summary: ${result.summary}"
        summaryLabel.foreground = UIUtil.getLabelForeground()

        // Metadata
        val meta = buildString {
            append("isError=${result.isError}")
            if (result.artifacts.isNotEmpty()) append("  |  artifacts=${result.artifacts}")
            if (result.isCompletion) append("  |  isCompletion=true")
            if (result.isPlanResponse) append("  |  isPlanResponse=true")
            if (result.isSessionHandoff) append("  |  isSessionHandoff=true")
            if (result.isSkillActivation) append("  |  skill=${result.activatedSkillName}")
            if (result.enablePlanMode) append("  |  enablePlanMode=true")
            if (result.diff != null) append("  |  hasDiff=true")
            if (result.verifyCommand != null) append("  |  verify=${result.verifyCommand}")
        }
        metadataLabel.text = meta
        metadataLabel.foreground = UIUtil.getContextHelpForeground()

        // Content — this is what the LLM sees
        val fullOutput = buildString {
            appendLine("═══ PARAMS SENT ═══")
            appendLine(params.toString())
            appendLine()
            appendLine("═══ CONTENT (what LLM sees) ═══")
            appendLine(result.content)
            if (result.diff != null) {
                appendLine()
                appendLine("═══ DIFF ═══")
                appendLine(result.diff)
            }
            if (result.handoffContext != null) {
                appendLine()
                appendLine("═══ HANDOFF CONTEXT ═══")
                appendLine(result.handoffContext)
            }
            if (result.activatedSkillContent != null) {
                appendLine()
                appendLine("��══ SKILL CONTENT ═══")
                appendLine(result.activatedSkillContent)
            }
        }
        contentArea.text = fullOutput
        contentArea.caretPosition = 0
    }

    private fun displayError(e: Exception, elapsedMs: Long, params: JsonObject) {
        statusLabel.text = "EXCEPTION  |  ${elapsedMs}ms"
        statusLabel.foreground = JBColor.RED
        summaryLabel.text = "Exception: ${e.javaClass.simpleName}: ${e.message}"
        metadataLabel.text = "This is a crash, not a graceful error — the tool threw instead of returning ToolResult(isError=true)"
        metadataLabel.foreground = JBColor.RED

        contentArea.text = buildString {
            appendLine("═���═ PARAMS SENT ═══")
            appendLine(params.toString())
            appendLine()
            appendLine("═══ EXCEPTION (tool crashed instead of returning error) ��══")
            appendLine("${e.javaClass.name}: ${e.message}")
            appendLine()
            e.stackTrace.take(30).forEach { appendLine("  at $it") }
            if (e.stackTrace.size > 30) appendLine("  ... (${e.stackTrace.size - 30} more frames)")
        }
        contentArea.caretPosition = 0
        copyButton.isEnabled = true
    }

    private fun clearResult() {
        statusLabel.text = " "
        summaryLabel.text = " "
        metadataLabel.text = " "
        contentArea.text = ""
        lastResult = null
        copyButton.isEnabled = false
    }

    private fun copyOutput() {
        val text = contentArea.text
        if (text.isNotBlank()) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    // ── Custom List Renderer ─────────────────────────────────────────────

    private inner class ToolListRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val entry = value as? ToolEntry ?: return this
            border = JBUI.Borders.empty(2, 6)

            // Color-code tier
            val tierColor = when (entry.tier) {
                "Core" -> JBColor(Color(0, 100, 200), Color(100, 180, 255))
                "Deferred" -> JBColor(Color(128, 128, 128), Color(150, 150, 150))
                else -> UIUtil.getLabelForeground()
            }

            text = "<html><b>${entry.tool.name}</b> <font color='${colorToHex(tierColor)}'>[${entry.tier}]</font></html>"

            return this
        }

        private fun colorToHex(c: Color): String {
            return String.format("#%02x%02x%02x", c.red, c.green, c.blue)
        }
    }
}
