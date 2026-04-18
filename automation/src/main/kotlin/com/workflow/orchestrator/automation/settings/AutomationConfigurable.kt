package com.workflow.orchestrator.automation.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.service.AutomationSettingsService
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.ConnectionStatusBanner
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.ui.StatusColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator

/**
 * Settings page for the Automation feature.
 *
 * Covers Docker tag extraction fields and the Automation Suites picker UI.
 * Bamboo connection details live on the Connections page; this page assumes
 * Bamboo and Nexus are already configured and shows a status banner at the top.
 *
 * Note: The Automation Suites panel is a custom Swing component (ported
 * verbatim from [CiCdConfigurable]) because Kotlin UI DSL v2 cannot
 * cleanly model the dynamic project/plan/search workflow.
 */
class AutomationConfigurable(private val project: Project) : SearchableConfigurable, Disposable {

    private val log = Logger.getInstance(AutomationConfigurable::class.java)

    /** invokeLater that works inside modal dialogs (Settings dialog is modal). */
    private fun runOnEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
    }

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // === Automation Suites UI state ===
    private val suiteRows = mutableListOf<SuiteRow>()
    private var suitesContainer: JPanel? = null
    private val projectCombo = JComboBox<AutomationProjectItem>()
    private val planCombo = JComboBox<AutomationPlanItem>()
    private val searchField = JBTextField(20).apply {
        emptyText.setText("Search pattern (e.g., REGRESSION, E2E.*)")
    }
    private val searchResultsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val bambooService: BambooService by lazy { project.getService(BambooService::class.java) }

    data class SuiteRow(
        val displayNameField: JBTextField,
        val planKeyField: JBTextField,
        val panel: JPanel
    )

    override fun getId(): String = "workflow.orchestrator.automation"
    override fun getDisplayName(): String = "Automation"

    override fun createComponent(): JComponent {
        // Recreate scope in case it was cancelled by a previous disposeUIResources()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val automationSettings = AutomationSettingsService.getInstance()

        val dslPanel = panel {
            ConnectionStatusBanner.render(
                this, project, listOf(
                    ConnectionStatusBanner.Requirement.BAMBOO,
                    ConnectionStatusBanner.Requirement.NEXUS,
                )
            )

            group("Docker Tags") {
                row {
                    comment("Docker Tag Key and Bamboo Plan Key are auto-detected from bamboo-specs and shown in Settings \u2192 Repositories.")
                }
                row("Build variable name:") {
                    textField()
                        .bindText(
                            { PluginSettings.getInstance(project).state.bambooBuildVariableName ?: "dockerTagsAsJson" },
                            { PluginSettings.getInstance(project).state.bambooBuildVariableName = it }
                        )
                        .comment("Bamboo build variable containing Docker tag JSON (default: dockerTagsAsJson)")
                }
            }

            group("Automation Suites") {
                row {
                    cell(buildAutomationSuitesPanel(automationSettings))
                        .align(AlignX.FILL)
                        .resizableColumn()
                }
            }

            collapsibleGroup("Advanced") {
                row("Tag history entries:") {
                    intTextField(range = 1..50)
                        .bindIntText(PluginSettings.getInstance(project).state::tagHistoryMaxEntries)
                }
            }
        }
        dialogPanel = dslPanel

        loadProjects()
        return JBScrollPane(dslPanel)
    }

    // ========== Automation Suites UI (ported verbatim from CiCdConfigurable) ==========

    private fun buildAutomationSuitesPanel(automationSettings: AutomationSettingsService): JPanel {
        val wrapper = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 0, 0, 0)
        }

        // === Add by Project -> Plan ===
        val addByProjectPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(12)
        }
        addByProjectPanel.add(JBLabel("Add suite by browsing Bamboo projects:").apply {
            foreground = StatusColors.SECONDARY_TEXT
            border = JBUI.Borders.emptyBottom(4)
        })

        val dropdownRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        dropdownRow.add(JBLabel("Project:"))
        dropdownRow.add(projectCombo.apply { preferredSize = Dimension(JBUI.scale(200), JBUI.scale(28)) })
        dropdownRow.add(JBLabel("Plan:"))
        dropdownRow.add(planCombo.apply { preferredSize = Dimension(JBUI.scale(250), JBUI.scale(28)) })
        dropdownRow.add(JButton("Add").apply { addActionListener { addSelectedPlan() } })
        addByProjectPanel.add(dropdownRow)

        projectCombo.addActionListener {
            val item = projectCombo.selectedItem as? AutomationProjectItem ?: return@addActionListener
            loadPlansForProject(item.key)
        }

        // === Add by regex search ===
        val addBySearchPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(12)
        }
        addBySearchPanel.add(JBLabel("Or find plans by name/pattern:").apply {
            foreground = StatusColors.SECONDARY_TEXT
            border = JBUI.Borders.emptyBottom(4)
        })

        val searchRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        searchRow.add(searchField)
        searchRow.add(JButton("Find & Add").apply { addActionListener { searchAndAdd() } })
        addBySearchPanel.add(searchRow)
        addBySearchPanel.add(JBScrollPane(searchResultsPanel).apply {
            preferredSize = Dimension(0, JBUI.scale(80))
            border = JBUI.Borders.emptyTop(4)
        })

        // === Current suites ===
        suitesContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        for (suite in automationSettings.getAllSuites()) {
            addSuiteRow(suite.displayName, suite.planKey)
        }

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(addByProjectPanel)
            add(JSeparator())
            add(addBySearchPanel)
            add(JSeparator())
            add(JBLabel("Configured Suites:").apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD)
                border = JBUI.Borders.empty(8, 0, 4, 0)
            })
        }

        wrapper.add(topPanel, BorderLayout.NORTH)
        wrapper.add(JBScrollPane(suitesContainer).apply {
            preferredSize = Dimension(0, JBUI.scale(200))
            border = null
        }, BorderLayout.CENTER)

        return wrapper
    }

    private fun loadProjects() {
        val connSettings = ConnectionSettings.getInstance()
        val url = connSettings.state.bambooUrl.trimEnd('/')
        if (url.isBlank()) {
            log.warn("[Automation] Bamboo URL is blank — showing configure message")
            runOnEdt {
                projectCombo.removeAllItems()
                projectCombo.addItem(AutomationProjectItem("", "Configure Bamboo URL in Connections first"))
            }
            return
        }
        runOnEdt {
            projectCombo.removeAllItems()
            projectCombo.addItem(AutomationProjectItem("", "Loading projects..."))
        }
        scope.launch {
            try {
                val result = withTimeout(15_000) {
                    bambooService.getProjects()
                }
                runOnEdt {
                    projectCombo.removeAllItems()
                    if (!result.isError) {
                        if (result.data.isEmpty()) {
                            projectCombo.addItem(AutomationProjectItem("", "No projects found"))
                        } else {
                            projectCombo.addItem(AutomationProjectItem("", "Select a project..."))
                            for (proj in result.data.sortedBy { it.name }) {
                                projectCombo.addItem(AutomationProjectItem(proj.key, proj.name))
                            }
                        }
                    } else {
                        projectCombo.addItem(AutomationProjectItem("", "Failed: ${result.summary}"))
                    }
                }
            } catch (e: Exception) {
                log.warn("[Automation] loadProjects() exception: ${e::class.simpleName}: ${e.message}", e)
                runOnEdt {
                    projectCombo.removeAllItems()
                    projectCombo.addItem(AutomationProjectItem("", "Error: ${e.message ?: "Connection failed"}"))
                }
            }
        }
    }

    private fun loadPlansForProject(projectKey: String) {
        if (projectKey.isBlank()) return
        val projectName = (projectCombo.selectedItem as? AutomationProjectItem)?.name ?: ""
        runOnEdt {
            planCombo.removeAllItems()
            planCombo.addItem(AutomationPlanItem("", "Loading plans..."))
        }
        scope.launch {
            val result = bambooService.getProjectPlans(projectKey)
            runOnEdt {
                planCombo.removeAllItems()
                if (!result.isError) {
                    if (result.data.isEmpty()) {
                        planCombo.addItem(AutomationPlanItem("", "No plans in this project"))
                    } else {
                        for (plan in result.data.sortedBy { it.name }) {
                            val displayName = plan.name
                                .removePrefix("$projectName - ")
                                .removePrefix("$projectName-")
                            planCombo.addItem(AutomationPlanItem(plan.key, displayName))
                        }
                    }
                } else {
                    planCombo.addItem(AutomationPlanItem("", "Failed: ${result.summary}"))
                }
            }
        }
    }

    private fun addSelectedPlan() {
        val plan = planCombo.selectedItem as? AutomationPlanItem ?: return
        if (plan.key.isBlank()) return
        if (suiteRows.any { it.planKeyField.text == plan.key }) return
        addSuiteRow(plan.name, plan.key)
    }

    private fun searchAndAdd() {
        val pattern = searchField.text.trim()
        if (pattern.isBlank()) return

        searchResultsPanel.removeAll()
        searchResultsPanel.add(JBLabel("Searching...").apply { foreground = StatusColors.SECONDARY_TEXT })
        searchResultsPanel.revalidate()

        scope.launch {
            val result = bambooService.getPlans()
            if (!result.isError) {
                val regex = try {
                    Regex(pattern, RegexOption.IGNORE_CASE)
                } catch (_: Exception) {
                    Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE)
                }

                val matches = result.data.filter {
                    regex.containsMatchIn(it.key) || regex.containsMatchIn(it.name)
                }

                runOnEdt {
                    searchResultsPanel.removeAll()
                    if (matches.isEmpty()) {
                        searchResultsPanel.add(JBLabel("No plans matching '$pattern'").apply {
                            foreground = StatusColors.SECONDARY_TEXT
                        })
                    } else {
                        for (plan in matches) {
                            val alreadyAdded = suiteRows.any { it.planKeyField.text == plan.key }
                            val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
                            row.add(JBLabel("${plan.key} — ${plan.name}").apply {
                                font = JBUI.Fonts.smallFont()
                            })
                            if (!alreadyAdded) {
                                row.add(JButton("Add").apply {
                                    font = JBUI.Fonts.smallFont()
                                    addActionListener {
                                        addSuiteRow(plan.name, plan.key)
                                        isEnabled = false; text = "Added"
                                    }
                                })
                            } else {
                                row.add(JBLabel("(already added)").apply {
                                    foreground = StatusColors.SECONDARY_TEXT
                                    font = JBUI.Fonts.smallFont()
                                })
                            }
                            searchResultsPanel.add(row)
                        }
                        searchResultsPanel.add(JBLabel("${matches.size} plan(s) found").apply {
                            foreground = StatusColors.SUCCESS
                            font = JBUI.Fonts.smallFont()
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
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        }
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2)
        }

        val nameField = JBTextField(displayName).apply { emptyText.setText("Display name") }
        val keyField = JBTextField(planKey).apply { emptyText.setText("Plan key"); isEditable = false }
        val removeButton = JButton("\u2715").apply {
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

    // ========== SearchableConfigurable lifecycle ==========

    override fun isModified(): Boolean {
        // DSL-bound fields (Docker Tags + Advanced)
        val dslModified = dialogPanel?.isModified() ?: false

        // Automation suites (app-level AutomationSettingsService)
        val automationSettings = AutomationSettingsService.getInstance()
        val currentSuites = automationSettings.getAllSuites().map { it.planKey to it.displayName }.toSet()
        val editedSuites = suiteRows.filter { it.planKeyField.text.isNotBlank() }
            .map { it.planKeyField.text to it.displayNameField.text }.toSet()
        val suitesModified = currentSuites != editedSuites

        return dslModified || suitesModified
    }

    override fun apply() {
        // Apply DSL-bound fields (project-level PluginSettings)
        dialogPanel?.apply()

        // Apply automation suites (app-level AutomationSettingsService)
        val automationSettings = AutomationSettingsService.getInstance()
        automationSettings.state.suites.clear()
        for (row in suiteRows) {
            val key = row.planKeyField.text.trim()
            val name = row.displayNameField.text.trim()
            if (key.isNotBlank()) {
                automationSettings.saveSuiteConfig(
                    AutomationSettingsService.SuiteConfig(
                        planKey = key, displayName = name.ifBlank { key },
                        lastModified = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    override fun reset() {
        dialogPanel?.reset()

        // Reset automation suites
        suiteRows.clear()
        suitesContainer?.removeAll()
        val automationSettings = AutomationSettingsService.getInstance()
        for (suite in automationSettings.getAllSuites()) {
            addSuiteRow(suite.displayName, suite.planKey)
        }
        suitesContainer?.revalidate()
    }

    override fun disposeUIResources() {
        scope.cancel()
        dialogPanel = null
        suitesContainer = null
        suiteRows.clear()
    }

    override fun dispose() {
        scope.cancel()
    }
}

private data class AutomationProjectItem(val key: String, val name: String) {
    override fun toString() = if (key.isBlank()) name else "$name ($key)"
}

private data class AutomationPlanItem(val key: String, val name: String) {
    override fun toString() = if (key.isBlank()) name else "$name ($key)"
}
