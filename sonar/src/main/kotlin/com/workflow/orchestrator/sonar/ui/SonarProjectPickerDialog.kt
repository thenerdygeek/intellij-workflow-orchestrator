package com.workflow.orchestrator.sonar.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.sonar.SonarProjectData
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.services.SonarService
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * Dialog for searching and selecting a SonarQube project.
 * Uses debounced search to query /api/components/search.
 */
class SonarProjectPickerDialog(
    private val project: Project
) : DialogWrapper(project, true) {

    private val log = Logger.getInstance(SonarProjectPickerDialog::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var searchJob: Job? = null

    private val searchField = JBTextField(30).apply {
        emptyText.setText("Type to search SonarQube projects...")
    }
    private val listModel = DefaultListModel<SonarProjectData>()
    private val resultList = JBList(listModel).apply {
        cellRenderer = SonarProjectCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val loadingIcon = JBLabel(AnimatedIcon.Default()).apply {
        isVisible = false
    }
    private val statusLabel = JBLabel("Type to search SonarQube projects...").apply {
        foreground = StatusColors.SECONDARY_TEXT
        font = font.deriveFont(JBUI.scale(11).toFloat())
        border = JBUI.Borders.empty(4, 4)
    }

    var selectedProjectKey: String? = null
        private set

    init {
        title = "Select SonarQube Project"
        setOKButtonText("Select")
        init()

        // Debounced search on key input
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent?) {
                scheduleSearch()
            }
        })

        // Double-click to select
        resultList.addListSelectionListener {
            isOKActionEnabled = resultList.selectedValue != null
        }

        isOKActionEnabled = false
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            preferredSize = JBUI.size(500, 400)
            border = JBUI.Borders.empty(8)
        }

        // Search field at top
        val searchPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(8)
            add(JBLabel("Search: ").apply { border = JBUI.Borders.emptyRight(4) }, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
        }
        panel.add(searchPanel, BorderLayout.NORTH)

        // Results list in center
        panel.add(JBScrollPane(resultList), BorderLayout.CENTER)

        // Status at bottom
        val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(loadingIcon)
            add(statusLabel)
        }
        panel.add(statusPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        val query = searchField.text.trim()
        if (query.length < 2) {
            SwingUtilities.invokeLater {
                listModel.clear()
                loadingIcon.isVisible = false
                statusLabel.text = "Type at least 2 characters to search..."
            }
            return
        }

        searchJob = scope.launch {
            delay(300) // debounce 300ms
            SwingUtilities.invokeLater {
                loadingIcon.isVisible = true
                statusLabel.text = "Searching..."
            }

            val sonarService = project.getService(SonarService::class.java)
            val result = sonarService.searchProjects(query)

            SwingUtilities.invokeLater {
                listModel.clear()
                loadingIcon.isVisible = false
                if (!result.isError) {
                    if (result.data.isEmpty()) {
                        statusLabel.text = "No projects found for '$query'"
                    } else {
                        for (proj in result.data) {
                            listModel.addElement(proj)
                        }
                        statusLabel.text = "${result.data.size} project(s) found"
                    }
                } else {
                    statusLabel.text = "Error: ${result.summary}"
                    log.warn("[SonarPicker] Search failed: ${result.summary}")
                }
            }
        }
    }

    override fun doOKAction() {
        selectedProjectKey = resultList.selectedValue?.key
        super.doOKAction()
    }

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }

    /**
     * Custom cell renderer showing "project-key -- Project Name".
     */
    private class SonarProjectCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is SonarProjectData) {
                text = "${value.key} \u2014 ${value.name}"
            }
            return component
        }
    }
}
