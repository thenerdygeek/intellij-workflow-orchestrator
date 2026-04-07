package com.workflow.orchestrator.bamboo.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.settings.ConnectionStatusBanner
import com.workflow.orchestrator.core.settings.PluginSettings
import javax.swing.JComponent

/**
 * "Builds & Health Checks" settings page — Bamboo build configuration and
 * pre-commit health check gates.
 *
 * Sections (in order):
 *  1. Connection status banner (Bamboo)
 *  2. Bamboo (plan key, poll interval)
 *  3. Health Checks (master toggle + blocking mode + sub-checks, reactive enabledIf)
 *  4. Advanced (maven goals, skip branch regex, timeout, copyright regex — collapsed)
 *
 * The legacy CiCdConfigurable (in :automation) still exists and remains registered
 * in plugin.xml; this new page will take over ownership in a later phase.
 *
 * Note: `healthCheckCveEnabled` is intentionally omitted — it's orphaned and is
 * being deleted as part of the dead-code cleanup.
 */
class BuildsAndHealthChecksConfigurable(private val project: Project) : SearchableConfigurable {

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    override fun getId(): String = "workflow.orchestrator.builds_health"
    override fun getDisplayName(): String = "Builds & Health Checks"

    override fun createComponent(): JComponent {
        val settings = PluginSettings.getInstance(project)

        val innerPanel = panel {
            // === 1. Connection status banner ===
            ConnectionStatusBanner.render(
                this, project,
                listOf(ConnectionStatusBanner.Requirement.BAMBOO)
            )

            // === 2. Bamboo ===
            group("Bamboo") {
                row("Bamboo plan key:") {
                    textField()
                        .bindText(
                            { settings.state.bambooPlanKey ?: "" },
                            { settings.state.bambooPlanKey = it }
                        )
                        .columns(COLUMNS_LARGE)
                        .comment("e.g., PROJ-BUILD. Auto-detected from PR build status if blank.")
                }
                row("Build poll interval (seconds):") {
                    intTextField(range = 5..3600)
                        .bindIntText(settings.state::buildPollIntervalSeconds)
                }
            }

            // === 3. Health Checks ===
            group("Health Checks") {
                lateinit var enableCheckbox: Cell<JBCheckBox>
                row {
                    enableCheckbox = checkBox("Enable health checks on commit")
                        .bindSelected(settings.state::healthCheckEnabled)
                }
                indent {
                    row("Blocking mode:") {
                        comboBox(listOf("hard", "soft", "off"))
                            .bindItem(
                                { settings.state.healthCheckBlockingMode },
                                { settings.state.healthCheckBlockingMode = it ?: "soft" }
                            )
                            .comment("hard = block commit, soft = warn only, off = disabled")
                    }.enabledIf(enableCheckbox.selected)

                    // Sub-checks
                    row {
                        checkBox("Maven compile")
                            .bindSelected(settings.state::healthCheckCompileEnabled)
                    }.enabledIf(enableCheckbox.selected)
                    row {
                        checkBox("Maven test")
                            .bindSelected(settings.state::healthCheckTestEnabled)
                    }.enabledIf(enableCheckbox.selected)
                    row {
                        checkBox("Copyright headers")
                            .bindSelected(settings.state::healthCheckCopyrightEnabled)
                    }.enabledIf(enableCheckbox.selected)
                    row {
                        checkBox("Sonar quality gate (uses cached status)")
                            .bindSelected(settings.state::healthCheckSonarGateEnabled)
                    }.enabledIf(enableCheckbox.selected)
                }
            }

            // === 4. Advanced (collapsed by default) ===
            collapsibleGroup("Advanced") {
                row("Maven goals:") {
                    textField()
                        .bindText(
                            { settings.state.healthCheckMavenGoals ?: "" },
                            { settings.state.healthCheckMavenGoals = it }
                        )
                        .columns(40)
                }
                row("Skip for branches (regex):") {
                    textField()
                        .bindText(
                            { settings.state.healthCheckSkipBranchPattern ?: "" },
                            { settings.state.healthCheckSkipBranchPattern = it }
                        )
                        .columns(40)
                        .comment("e.g., hotfix/.* — leave blank to run on all branches")
                }
                row("Timeout (seconds):") {
                    intTextField(range = 10..3600)
                        .bindIntText(settings.state::healthCheckTimeoutSeconds)
                }
                row("Copyright header pattern (regex):") {
                    textField()
                        .bindText(
                            { settings.state.copyrightHeaderPattern ?: "" },
                            { settings.state.copyrightHeaderPattern = it }
                        )
                        .columns(40)
                        .comment("e.g., Copyright \\(c\\) \\d{4} MyCompany")
                }
            }.expanded = false
        }
        dialogPanel = innerPanel

        return JBScrollPane(innerPanel).apply {
            border = null
        }
    }

    override fun isModified(): Boolean = dialogPanel?.isModified() ?: false

    override fun apply() {
        dialogPanel?.apply()
    }

    override fun reset() {
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }
}
