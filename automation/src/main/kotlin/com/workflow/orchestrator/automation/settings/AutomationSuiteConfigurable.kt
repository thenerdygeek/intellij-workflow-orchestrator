package com.workflow.orchestrator.automation.settings

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.service.AutomationSettingsService
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Settings page for configuring automation suite plan keys.
 * Two ways to add suites:
 * 1. Project dropdown → Plan dropdown → Add
 * 2. Regex/text search → Find & Add matching plans
 */
class AutomationSuiteConfigurable : SearchableConfigurable {

    private var mainPanel: JPanel? = null
    private val suiteRows = mutableListOf<SuiteRow>()
    private var suitesContainer: JPanel? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val projectCombo = JComboBox<ProjectItem>()
    private val planCombo = JComboBox<PlanItem>()

    private val searchField = JBTextField(20).apply {
        emptyText.setText("Search pattern (e.g., REGRESSION, E2E.*)")
    }

    // Ticket key regex
    private val ticketKeyRegexField = JBTextField(30)
    private val searchResultsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    private var bambooClient: BambooApiClient? = null

    data class SuiteRow(
        val displayNameField: JBTextField,
        val planKeyField: JBTextField,
        val panel: JPanel
    )

    override fun getId(): String = "workflow.orchestrator.automation.suites"
    override fun getDisplayName(): String = "Automation Suites"

    override fun createComponent(): JComponent {
        // Recreate scope in case it was cancelled by a previous disposeUIResources()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = AutomationSettingsService.getInstance()
        initBambooClient()

        mainPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(8) }

        // === Add by Project → Plan ===
        val addByProjectPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(12)
        }
        addByProjectPanel.add(JBLabel("Add suite by browsing Bamboo projects:").apply {
            foreground = JBColor(0x656D76, 0x8B949E)
            border = JBUI.Borders.emptyBottom(4)
        })

        val dropdownRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        dropdownRow.add(JBLabel("Project:"))
        dropdownRow.add(projectCombo.apply { preferredSize = java.awt.Dimension(JBUI.scale(200), JBUI.scale(28)) })
        dropdownRow.add(JBLabel("Plan:"))
        dropdownRow.add(planCombo.apply { preferredSize = java.awt.Dimension(JBUI.scale(250), JBUI.scale(28)) })
        dropdownRow.add(JButton("Add").apply { addActionListener { addSelectedPlan() } })
        addByProjectPanel.add(dropdownRow)

        projectCombo.addActionListener {
            val item = projectCombo.selectedItem as? ProjectItem ?: return@addActionListener
            loadPlansForProject(item.key)
        }

        // === Add by regex search ===
        val addBySearchPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(12)
        }
        addBySearchPanel.add(JBLabel("Or find plans by name/pattern:").apply {
            foreground = JBColor(0x656D76, 0x8B949E)
            border = JBUI.Borders.emptyBottom(4)
        })

        val searchRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        searchRow.add(searchField)
        searchRow.add(JButton("Find & Add").apply { addActionListener { searchAndAdd() } })
        addBySearchPanel.add(searchRow)
        addBySearchPanel.add(JBScrollPane(searchResultsPanel).apply {
            preferredSize = java.awt.Dimension(0, JBUI.scale(80))
            border = JBUI.Borders.emptyTop(4)
        })

        // === Current suites ===
        suitesContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        for (suite in settings.getAllSuites()) {
            addSuiteRow(suite.displayName, suite.planKey)
        }

        // === Ticket key regex ===
        val regexPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(12)
        }
        regexPanel.add(JBLabel("Jira Ticket Key Detection Pattern:").apply {
            foreground = JBColor(0x656D76, 0x8B949E)
            border = JBUI.Borders.emptyBottom(4)
        })
        val regexRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        val connSettings = com.workflow.orchestrator.core.settings.ConnectionSettings.getInstance()
        ticketKeyRegexField.text = connSettings.state.ticketKeyRegex
        regexRow.add(JBLabel("Regex:"))
        regexRow.add(ticketKeyRegexField)
        regexPanel.add(regexRow)
        regexPanel.add(JBLabel("Used to detect and hyperlink Jira ticket keys in descriptions and comments").apply {
            foreground = JBColor(0x999999, 0x585b70)
            font = font.deriveFont(JBUI.scale(10).toFloat())
            border = JBUI.Borders.emptyLeft(JBUI.scale(6))
        })

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(addByProjectPanel)
            add(JSeparator())
            add(addBySearchPanel)
            add(JSeparator())
            add(regexPanel)
            add(JSeparator())
            add(JBLabel("Configured Suites:").apply {
                font = font.deriveFont(font.size + 1f)
                border = JBUI.Borders.empty(8, 0, 4, 0)
            })
        }

        mainPanel!!.add(topPanel, BorderLayout.NORTH)
        mainPanel!!.add(JBScrollPane(suitesContainer).apply { border = null }, BorderLayout.CENTER)

        loadProjects()
        return mainPanel!!
    }

    private fun initBambooClient() {
        val connSettings = com.workflow.orchestrator.core.settings.ConnectionSettings.getInstance()
        val url = connSettings.state.bambooUrl.trimEnd('/')
        if (url.isBlank()) {
            invokeLater {
                projectCombo.removeAllItems()
                projectCombo.addItem(ProjectItem("", "Configure Bamboo URL in Connections first"))
            }
            return
        }
        bambooClient = BambooApiClient(
            baseUrl = url,
            tokenProvider = { CredentialStore().getToken(ServiceType.BAMBOO) }
        )
    }

    private fun loadProjects() {
        val client = bambooClient ?: return
        invokeLater {
            projectCombo.removeAllItems()
            projectCombo.addItem(ProjectItem("", "Loading projects..."))
        }
        scope.launch {
            try {
                val result = kotlinx.coroutines.withTimeout(15_000) {
                    client.getProjects()
                }
                invokeLater {
                    projectCombo.removeAllItems()
                    when (result) {
                        is ApiResult.Success -> {
                            if (result.data.isEmpty()) {
                                projectCombo.addItem(ProjectItem("", "No projects found"))
                            } else {
                                projectCombo.addItem(ProjectItem("", "Select a project..."))
                                for (proj in result.data.sortedBy { it.name }) {
                                    projectCombo.addItem(ProjectItem(proj.key, proj.name))
                                }
                            }
                        }
                        is ApiResult.Error -> {
                            projectCombo.addItem(ProjectItem("", "Failed: ${result.message}"))
                        }
                    }
                }
            } catch (e: Exception) {
                invokeLater {
                    projectCombo.removeAllItems()
                    projectCombo.addItem(ProjectItem("", "Error: ${e.message ?: "Connection failed"}"))
                }
            }
        }
    }

    private fun loadPlansForProject(projectKey: String) {
        if (projectKey.isBlank()) return
        val client = bambooClient ?: return
        invokeLater {
            planCombo.removeAllItems()
            planCombo.addItem(PlanItem("", "Loading plans..."))
        }
        scope.launch {
            val result = client.getProjectPlans(projectKey)
            invokeLater {
                planCombo.removeAllItems()
                when (result) {
                    is ApiResult.Success -> {
                        if (result.data.isEmpty()) {
                            planCombo.addItem(PlanItem("", "No plans in this project"))
                        } else {
                            for (plan in result.data.sortedBy { it.name }) {
                                planCombo.addItem(PlanItem(plan.key, plan.name))
                            }
                        }
                    }
                    is ApiResult.Error -> {
                        planCombo.addItem(PlanItem("", "Failed: ${result.message}"))
                    }
                }
            }
        }
    }

    private fun addSelectedPlan() {
        val plan = planCombo.selectedItem as? PlanItem ?: return
        if (plan.key.isBlank()) return
        if (suiteRows.any { it.planKeyField.text == plan.key }) return
        addSuiteRow(plan.name, plan.key)
    }

    private fun searchAndAdd() {
        val pattern = searchField.text.trim()
        if (pattern.isBlank()) return

        val client = bambooClient ?: return
        searchResultsPanel.removeAll()
        searchResultsPanel.add(JBLabel("Searching...").apply { foreground = JBColor(0x656D76, 0x6c7086) })
        searchResultsPanel.revalidate()

        scope.launch {
            val result = client.getPlans()
            if (result is ApiResult.Success) {
                val regex = try {
                    Regex(pattern, RegexOption.IGNORE_CASE)
                } catch (_: Exception) {
                    Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE)
                }

                val matches = result.data.filter {
                    regex.containsMatchIn(it.key) || regex.containsMatchIn(it.name)
                }

                invokeLater {
                    searchResultsPanel.removeAll()
                    if (matches.isEmpty()) {
                        searchResultsPanel.add(JBLabel("No plans matching '$pattern'").apply {
                            foreground = JBColor(0x656D76, 0x585b70)
                        })
                    } else {
                        for (plan in matches) {
                            val alreadyAdded = suiteRows.any { it.planKeyField.text == plan.key }
                            val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
                            row.add(JBLabel("${plan.key} — ${plan.name}").apply {
                                font = font.deriveFont(JBUI.scale(11).toFloat())
                            })
                            if (!alreadyAdded) {
                                row.add(JButton("Add").apply {
                                    font = font.deriveFont(JBUI.scale(10).toFloat())
                                    addActionListener {
                                        addSuiteRow(plan.name, plan.key)
                                        isEnabled = false; text = "Added"
                                    }
                                })
                            } else {
                                row.add(JBLabel("(already added)").apply {
                                    foreground = JBColor(0x656D76, 0x585b70)
                                    font = font.deriveFont(JBUI.scale(10).toFloat())
                                })
                            }
                            searchResultsPanel.add(row)
                        }
                        searchResultsPanel.add(JBLabel("${matches.size} plan(s) found").apply {
                            foreground = JBColor(0x1B7F37, 0xa6e3a1)
                            font = font.deriveFont(JBUI.scale(10).toFloat())
                            border = JBUI.Borders.emptyTop(4)
                        })
                    }
                    searchResultsPanel.revalidate()
                    searchResultsPanel.repaint()
                }
            }
        }
    }

    private fun addSuiteRow(displayName: String, planKey: String) {
        val rowPanel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.emptyBottom(4)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(32))
        }
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2)
        }

        val nameField = JBTextField(displayName).apply { emptyText.setText("Display name") }
        val keyField = JBTextField(planKey).apply { emptyText.setText("Plan key"); isEditable = false }
        val removeButton = JButton("✕").apply {
            isBorderPainted = false
            addActionListener {
                suitesContainer?.remove(rowPanel)
                suiteRows.removeIf { it.panel == rowPanel }
                suitesContainer?.revalidate()
                suitesContainer?.repaint()
            }
        }

        gbc.gridx = 0; gbc.weightx = 0.35; rowPanel.add(nameField, gbc)
        gbc.gridx = 1; gbc.weightx = 0.55; rowPanel.add(keyField, gbc)
        gbc.gridx = 2; gbc.weightx = 0.0; rowPanel.add(removeButton, gbc)

        suiteRows.add(SuiteRow(nameField, keyField, rowPanel))
        suitesContainer?.add(rowPanel)
        suitesContainer?.revalidate()
    }

    override fun isModified(): Boolean {
        val settings = AutomationSettingsService.getInstance()
        val currentSuites = settings.getAllSuites().map { it.planKey to it.displayName }.toSet()
        val editedSuites = suiteRows.filter { it.planKeyField.text.isNotBlank() }
            .map { it.planKeyField.text to it.displayNameField.text }.toSet()
        val regexChanged = ticketKeyRegexField.text !=
            com.workflow.orchestrator.core.settings.ConnectionSettings.getInstance().state.ticketKeyRegex
        return currentSuites != editedSuites || regexChanged
    }

    override fun apply() {
        val settings = AutomationSettingsService.getInstance()
        settings.state.suites.clear()
        for (row in suiteRows) {
            val key = row.planKeyField.text.trim()
            val name = row.displayNameField.text.trim()
            if (key.isNotBlank()) {
                settings.saveSuiteConfig(AutomationSettingsService.SuiteConfig(
                    planKey = key, displayName = name.ifBlank { key },
                    lastModified = System.currentTimeMillis()
                ))
            }
        }
        com.workflow.orchestrator.core.settings.ConnectionSettings.getInstance().state.ticketKeyRegex =
            ticketKeyRegexField.text.trim().ifBlank { "\\b([A-Z][A-Z0-9]+-\\d+)\\b" }
    }

    override fun reset() {
        suiteRows.clear()
        suitesContainer?.removeAll()
        val settings = AutomationSettingsService.getInstance()
        for (suite in settings.getAllSuites()) {
            addSuiteRow(suite.displayName, suite.planKey)
        }
        ticketKeyRegexField.text = com.workflow.orchestrator.core.settings.ConnectionSettings
            .getInstance().state.ticketKeyRegex
        suitesContainer?.revalidate()
    }

    override fun disposeUIResources() {
        scope.cancel()
    }
}

private data class ProjectItem(val key: String, val name: String) {
    override fun toString() = if (key.isBlank()) name else "$name ($key)"
}

private data class PlanItem(val key: String, val name: String) {
    override fun toString() = if (key.isBlank()) name else "$name ($key)"
}
