package com.workflow.orchestrator.automation.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.automation.service.AutomationSettingsService
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Merged settings page for CI/CD configuration.
 * Combines: Bamboo settings, Docker & Automation, Quality Thresholds,
 * Automation Suites (project/plan browser + regex search), and Health Checks.
 */
class CiCdConfigurable(private val project: Project) : SearchableConfigurable, Disposable {

    private val log = Logger.getInstance(CiCdConfigurable::class.java)

    /** invokeLater that works inside modal dialogs (Settings dialog is modal). */
    private fun runOnEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
    }

    private var mainPanel: JPanel? = null
    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // === Automation Suites UI state ===
    private val suiteRows = mutableListOf<SuiteRow>()
    private var suitesContainer: JPanel? = null
    private val projectCombo = JComboBox<CiCdProjectItem>()
    private val planCombo = JComboBox<CiCdPlanItem>()
    private val searchField = JBTextField(20).apply {
        emptyText.setText("Search pattern (e.g., REGRESSION, E2E.*)")
    }
    private val searchResultsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private var bambooClient: BambooApiClient? = null

    data class SuiteRow(
        val displayNameField: JBTextField,
        val planKeyField: JBTextField,
        val panel: JPanel
    )

    override fun getId(): String = "workflow.orchestrator.cicd"
    override fun getDisplayName(): String = "CI/CD"

    override fun createComponent(): JComponent {
        // Recreate scope in case it was cancelled by a previous disposeUIResources()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = PluginSettings.getInstance(project)
        val automationSettings = AutomationSettingsService.getInstance()
        initBambooClient()

        // === DSL panel for simple field groups ===
        val dslPanel = panel {
            // === 1. Bamboo ===
            collapsibleGroup("Bamboo") {
                row("Bamboo plan key:") {
                    textField()
                        .bindText(
                            { settings.state.bambooPlanKey ?: "" },
                            { settings.state.bambooPlanKey = it }
                        )
                        .comment("e.g., PROJ-BUILD. Auto-detected from PR build status if blank.")
                }
                row("Build poll interval (seconds):") {
                    intTextField(range = 5..3600)
                        .bindIntText(settings.state::buildPollIntervalSeconds)
                }
            }.expanded = true

            // === 2. Docker & Automation ===
            collapsibleGroup("Docker & Automation") {
                row("Docker tag key for this repo:") {
                    textField()
                        .bindText(
                            { settings.state.dockerTagKey ?: "" },
                            { settings.state.dockerTagKey = it }
                        )
                        .comment("Key in dockerTagsAsJson that represents this repo (e.g., order-service)")
                }
                row("Service CI plan key:") {
                    textField()
                        .bindText(
                            { settings.state.serviceCiPlanKey ?: "" },
                            { settings.state.serviceCiPlanKey = it }
                        )
                        .comment("Bamboo plan key for this repo's CI build (for docker tag extraction)")
                }
                row("Build variable name:") {
                    textField()
                        .bindText(
                            { settings.state.bambooBuildVariableName ?: "" },
                            { settings.state.bambooBuildVariableName = it }
                        )
                        .comment("Bamboo build variable containing Docker tag JSON (default: dockerTagsAsJson)")
                }
                row("Tag history entries:") {
                    intTextField(range = 1..50)
                        .bindIntText(settings.state::tagHistoryMaxEntries)
                }
            }

            // === 3. Quality Thresholds ===
            collapsibleGroup("Quality Thresholds") {
                row("High coverage — green (%):") {
                    textField()
                        .bindText(
                            { settings.state.coverageHighThreshold.toString() },
                            { settings.state.coverageHighThreshold = it.toFloatOrNull() ?: 80.0f }
                        )
                }
                row("Medium coverage — yellow (%):") {
                    textField()
                        .bindText(
                            { settings.state.coverageMediumThreshold.toString() },
                            { settings.state.coverageMediumThreshold = it.toFloatOrNull() ?: 50.0f }
                        )
                }
                row("SonarQube metrics:") {
                    textField()
                        .bindText(
                            { settings.state.sonarMetricKeys ?: "" },
                            { settings.state.sonarMetricKeys = it }
                        )
                        .comment("Comma-separated metric keys for API queries")
                }
            }

            // === 4. SonarQube ===
            collapsibleGroup("SonarQube") {
                row("Project Key:") {
                    val projectKeyField = textField()
                        .bindText(
                            { settings.state.sonarProjectKey ?: "" },
                            { settings.state.sonarProjectKey = it }
                        )
                        .columns(30)
                    button("Browse...") {
                        // Open SonarProjectPickerDialog via reflection to avoid :sonar dependency
                        try {
                            val dialogClass = Class.forName("com.workflow.orchestrator.sonar.ui.SonarProjectPickerDialog")
                            val constructor = dialogClass.getConstructor(com.intellij.openapi.project.Project::class.java)
                            val dialog = constructor.newInstance(project) as com.intellij.openapi.ui.DialogWrapper
                            if (dialog.showAndGet()) {
                                val getKey = dialogClass.getMethod("getSelectedProjectKey")
                                val key = getKey.invoke(dialog) as? String
                                if (!key.isNullOrBlank()) {
                                    projectKeyField.component.text = key
                                }
                            }
                        } catch (e: Exception) {
                            log.warn("[CI/CD] SonarProjectPickerDialog not available: ${e.message}")
                        }
                    }
                    button("Auto-detect") {
                        // Detect sonar.projectKey from pom.xml via Maven API
                        val detected = try {
                            val mavenManager = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
                            if (mavenManager.isMavenizedProject) {
                                val rootProject = mavenManager.rootProjects.firstOrNull()
                                rootProject?.properties?.getProperty("sonar.projectKey")
                                    ?: rootProject?.let { "${it.mavenId.groupId}:${it.mavenId.artifactId}" }
                            } else null
                        } catch (_: Exception) { null }

                        if (detected != null) {
                            projectKeyField.component.text = detected
                        } else {
                            com.intellij.openapi.ui.Messages.showWarningDialog(
                                "Could not detect sonar.projectKey from pom.xml.\nEnsure Maven is configured with a sonar.projectKey property.",
                                "Auto-detect Failed"
                            )
                        }
                    }
                }.comment("SonarQube project key for quality analysis. Use Browse to search or Auto-detect from pom.xml.")
            }

            // === 5. Health Checks ===
            collapsibleGroup("Health Checks") {
                row {
                    checkBox("Enable health checks on commit")
                        .bindSelected(settings.state::healthCheckEnabled)
                }
                row("Blocking mode:") {
                    comboBox(listOf("hard", "soft", "off"))
                        .bindItem(
                            getter = { settings.state.healthCheckBlockingMode },
                            setter = { settings.state.healthCheckBlockingMode = it ?: "soft" }
                        )
                        .comment("hard = block commit, soft = warn only, off = disabled")
                }
                group("Checks") {
                    row {
                        checkBox("Maven compile")
                            .bindSelected(settings.state::healthCheckCompileEnabled)
                    }
                    row {
                        checkBox("Maven test")
                            .bindSelected(settings.state::healthCheckTestEnabled)
                    }
                    row {
                        checkBox("Copyright headers")
                            .bindSelected(settings.state::healthCheckCopyrightEnabled)
                    }
                    row {
                        checkBox("Sonar quality gate (uses cached status)")
                            .bindSelected(settings.state::healthCheckSonarGateEnabled)
                    }
                    row {
                        checkBox("CVE annotations in pom.xml")
                            .bindSelected(settings.state::healthCheckCveEnabled)
                    }
                }
                row("Maven goals:") {
                    textField()
                        .columns(30)
                        .bindText(
                            { settings.state.healthCheckMavenGoals ?: "" },
                            { settings.state.healthCheckMavenGoals = it }
                        )
                }
                row("Skip for branches (regex):") {
                    textField()
                        .columns(30)
                        .bindText(
                            { settings.state.healthCheckSkipBranchPattern ?: "" },
                            { settings.state.healthCheckSkipBranchPattern = it }
                        )
                        .comment("e.g., hotfix/.* — leave blank to run on all branches")
                }
                row("Timeout (seconds):") {
                    intTextField(range = 10..3600)
                        .bindIntText(settings.state::healthCheckTimeoutSeconds)
                }
                row("Copyright header pattern (regex):") {
                    textField()
                        .columns(40)
                        .bindText(
                            { settings.state.copyrightHeaderPattern ?: "" },
                            { settings.state.copyrightHeaderPattern = it }
                        )
                        .comment("e.g., Copyright \\(c\\) \\d{4} MyCompany")
                }
            }
        }
        dialogPanel = dslPanel

        // === 4. Automation Suites (custom Swing) ===
        val suitesSection = buildAutomationSuitesPanel(automationSettings)

        // Compose the full panel
        mainPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
        }

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(dslPanel)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(suitesSection)
        }

        mainPanel!!.add(contentPanel, BorderLayout.CENTER)

        loadProjects()
        return JBScrollPane(mainPanel!!)
    }

    // ========== Automation Suites UI (ported from AutomationSuiteConfigurable) ==========

    private fun buildAutomationSuitesPanel(automationSettings: AutomationSettingsService): JPanel {
        val wrapper = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 0, 0, 0)
        }

        // Title
        val titleLabel = JBLabel("Automation Suites").apply {
            font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
            border = JBUI.Borders.emptyBottom(8)
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
        dropdownRow.add(projectCombo.apply { preferredSize = java.awt.Dimension(JBUI.scale(200), JBUI.scale(28)) })
        dropdownRow.add(JBLabel("Plan:"))
        dropdownRow.add(planCombo.apply { preferredSize = java.awt.Dimension(JBUI.scale(250), JBUI.scale(28)) })
        dropdownRow.add(JButton("Add").apply { addActionListener { addSelectedPlan() } })
        addByProjectPanel.add(dropdownRow)

        projectCombo.addActionListener {
            val item = projectCombo.selectedItem as? CiCdProjectItem ?: return@addActionListener
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
            preferredSize = java.awt.Dimension(0, JBUI.scale(80))
            border = JBUI.Borders.emptyTop(4)
        })

        // === Current suites ===
        suitesContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        for (suite in automationSettings.getAllSuites()) {
            addSuiteRow(suite.displayName, suite.planKey)
        }

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(titleLabel)
            add(addByProjectPanel)
            add(JSeparator())
            add(addBySearchPanel)
            add(JSeparator())
            add(JBLabel("Configured Suites:").apply {
                font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
                border = JBUI.Borders.empty(8, 0, 4, 0)
            })
        }

        wrapper.add(topPanel, BorderLayout.NORTH)
        wrapper.add(JBScrollPane(suitesContainer).apply { border = null }, BorderLayout.CENTER)

        return wrapper
    }

    private fun initBambooClient() {
        log.info("[CiCd] initBambooClient() called")
        val connSettings = ConnectionSettings.getInstance()
        val url = connSettings.state.bambooUrl.trimEnd('/')
        if (url.isBlank()) {
            log.warn("[CiCd] Bamboo URL is blank — showing configure message")
            runOnEdt {
                projectCombo.removeAllItems()
                projectCombo.addItem(CiCdProjectItem("", "Configure Bamboo URL in Connections first"))
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
        runOnEdt {
            projectCombo.removeAllItems()
            projectCombo.addItem(CiCdProjectItem("", "Loading projects..."))
        }
        scope.launch {
            try {
                val result = withTimeout(15_000) {
                    client.getProjects()
                }
                runOnEdt {
                    projectCombo.removeAllItems()
                    when (result) {
                        is ApiResult.Success -> {
                            if (result.data.isEmpty()) {
                                projectCombo.addItem(CiCdProjectItem("", "No projects found"))
                            } else {
                                projectCombo.addItem(CiCdProjectItem("", "Select a project..."))
                                for (proj in result.data.sortedBy { it.name }) {
                                    projectCombo.addItem(CiCdProjectItem(proj.key, proj.name))
                                }
                            }
                        }
                        is ApiResult.Error -> {
                            projectCombo.addItem(CiCdProjectItem("", "Failed: ${result.message}"))
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("[CiCd] loadProjects() exception: ${e::class.simpleName}: ${e.message}", e)
                runOnEdt {
                    projectCombo.removeAllItems()
                    projectCombo.addItem(CiCdProjectItem("", "Error: ${e.message ?: "Connection failed"}"))
                }
            }
        }
    }

    private fun loadPlansForProject(projectKey: String) {
        if (projectKey.isBlank()) return
        val client = bambooClient ?: return
        val projectName = (projectCombo.selectedItem as? CiCdProjectItem)?.name ?: ""
        runOnEdt {
            planCombo.removeAllItems()
            planCombo.addItem(CiCdPlanItem("", "Loading plans..."))
        }
        scope.launch {
            val result = client.getProjectPlans(projectKey)
            runOnEdt {
                planCombo.removeAllItems()
                when (result) {
                    is ApiResult.Success -> {
                        if (result.data.isEmpty()) {
                            planCombo.addItem(CiCdPlanItem("", "No plans in this project"))
                        } else {
                            for (plan in result.data.sortedBy { it.name }) {
                                val displayName = plan.name
                                    .removePrefix("$projectName - ")
                                    .removePrefix("$projectName-")
                                planCombo.addItem(CiCdPlanItem(plan.key, displayName))
                            }
                        }
                    }
                    is ApiResult.Error -> {
                        planCombo.addItem(CiCdPlanItem("", "Failed: ${result.message}"))
                    }
                }
            }
        }
    }

    private fun addSelectedPlan() {
        val plan = planCombo.selectedItem as? CiCdPlanItem ?: return
        if (plan.key.isBlank()) return
        if (suiteRows.any { it.planKeyField.text == plan.key }) return
        addSuiteRow(plan.name, plan.key)
    }

    private fun searchAndAdd() {
        val pattern = searchField.text.trim()
        if (pattern.isBlank()) return

        val client = bambooClient ?: return
        searchResultsPanel.removeAll()
        searchResultsPanel.add(JBLabel("Searching...").apply { foreground = StatusColors.SECONDARY_TEXT })
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
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(32))
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
        // DSL-bound fields (Bamboo, Docker, Quality, Health Check)
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
        mainPanel = null
        suitesContainer = null
        suiteRows.clear()
    }

    override fun dispose() {
        scope.cancel()
    }
}

private data class CiCdProjectItem(val key: String, val name: String) {
    override fun toString() = if (key.isBlank()) name else "$name ($key)"
}

private data class CiCdPlanItem(val key: String, val name: String) {
    override fun toString() = if (key.isBlank()) name else "$name ($key)"
}
