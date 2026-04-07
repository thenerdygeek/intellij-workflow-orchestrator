package com.workflow.orchestrator.sonar.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.workflow.orchestrator.core.settings.ConnectionStatusBanner
import com.workflow.orchestrator.core.settings.PluginSettings
import javax.swing.JComponent

/**
 * Code Quality settings page — consolidates SonarQube project configuration,
 * editor integration toggles, coverage thresholds, and advanced metric settings.
 *
 * Previously these fields lived in [com.workflow.orchestrator.automation.settings.CiCdConfigurable],
 * which mixed Bamboo, Docker, Quality, SonarQube, and Health Check concerns into one
 * overloaded page. This page owns the Code Quality slice of that former page.
 */
class CodeQualityConfigurable(private val project: Project) : SearchableConfigurable {

    private val log = Logger.getInstance(CodeQualityConfigurable::class.java)

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    override fun getId(): String = "workflow.orchestrator.code_quality"
    override fun getDisplayName(): String = "Code Quality"

    override fun createComponent(): JComponent {
        val settings = PluginSettings.getInstance(project)

        val panel = panel {
            // === Connection status banner ===
            ConnectionStatusBanner.render(
                this,
                project,
                listOf(ConnectionStatusBanner.Requirement.SONARQUBE)
            )

            // === 1. SonarQube Project ===
            group("SonarQube Project") {
                row("Project Key:") {
                    val projectKeyField = textField()
                        .bindText(
                            { settings.state.sonarProjectKey ?: "" },
                            { settings.state.sonarProjectKey = it }
                        )
                        .columns(30)
                    button("Browse...") {
                        // Open SonarProjectPickerDialog via reflection to avoid compile-time
                        // dependency on concrete UI class (same pattern as CiCdConfigurable).
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
                            log.warn("[CodeQuality] SonarProjectPickerDialog not available: ${e.message}")
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

            // === 2. Editor Integration ===
            group("Editor Integration") {
                row {
                    checkBox("Enable Sonar inline annotations in editor")
                        .bindSelected(settings.state::sonarInlineAnnotationsEnabled)
                        .comment("Show SonarQube issues as inline annotations on affected lines")
                }
                row {
                    checkBox("Enable Sonar AI quick-fix intention action")
                        .bindSelected(settings.state::sonarIntentionActionEnabled)
                        .comment("Show an AI-powered quick-fix intention on Sonar-annotated lines")
                }
            }

            // === 3. Coverage Thresholds ===
            group("Coverage Thresholds") {
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
            }

            // === 4. Advanced (collapsed by default) ===
            collapsibleGroup("Advanced") {
                row("SonarQube metrics:") {
                    textField()
                        .bindText(
                            { settings.state.sonarMetricKeys ?: "" },
                            { settings.state.sonarMetricKeys = it }
                        )
                        .columns(COLUMNS_LARGE)
                        .comment("Comma-separated metric keys for API queries")
                }
                row {
                    checkBox("Show coverage gutter markers")
                        .bindSelected(settings.state::coverageGutterMarkersEnabled)
                        .comment("Render per-line coverage markers in the editor gutter")
                }
            }
        }

        dialogPanel = panel
        return panel
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
