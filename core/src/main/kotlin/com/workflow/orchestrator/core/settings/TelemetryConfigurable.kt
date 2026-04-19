package com.workflow.orchestrator.core.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.util.ProjectIdentifier
import javax.swing.JComponent

class TelemetryConfigurable(private val project: Project) : SearchableConfigurable {

    private val settings = PluginSettings.getInstance(project)
    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    override fun getId(): String = "workflow.orchestrator.telemetry"
    override fun getDisplayName(): String = "Telemetry & Logs"

    override fun createComponent(): JComponent {
        val innerPanel = panel {
            group("Logging") {
                row("Log Level:") {
                    comboBox(listOf("INFO", "DEBUG", "TRACE"))
                        .bindItem(
                            { settings.state.logLevel },
                            { settings.state.logLevel = it ?: "INFO" }
                        )
                }
                row {
                    checkBox("Enable diagnostic JSONL logging")
                        .bindSelected(
                            { settings.state.diagnosticJsonlEnabled },
                            { settings.state.diagnosticJsonlEnabled = it }
                        )
                }
                row("Log retention (days):") {
                    intTextField(range = 1..365)
                        .bindIntText(settings.state::retentionDays)
                }
                row {
                    button("Open Log Folder") { openLogsFolder() }
                }
            }
            group("Privacy") {
                row {
                    checkBox("Include run_command output in logs")
                        .bindSelected(
                            { settings.state.includeCommandOutputInLogs },
                            { settings.state.includeCommandOutputInLogs = it }
                        )
                }
            }
            group("Display") {
                row {
                    checkBox("Show estimated cost in UI")
                        .bindSelected(
                            { settings.state.costDisplayEnabled },
                            { settings.state.costDisplayEnabled = it }
                        )
                }
            }
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

    private fun openLogsFolder() {
        val basePath = project.basePath ?: return
        val logsDir = ProjectIdentifier.logsDir(basePath)
        logsDir.mkdirs()
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(logsDir)
            }
        } catch (_: Exception) {}
    }
}
