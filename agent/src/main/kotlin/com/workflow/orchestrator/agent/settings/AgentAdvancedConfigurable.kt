package com.workflow.orchestrator.agent.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.workflow.orchestrator.core.settings.PluginSettings
import javax.swing.JComponent

/**
 * "AI Agent → Advanced" sub-page.
 *
 * Power-user settings that most users never touch:
 *  - Debugging toggles (debug panel, smart working indicator, PowerShell)
 *  - HTTP timeouts (shared with every API client, via [PluginSettings])
 *  - Jira custom field IDs used by AI pre-review and other integration features
 */
class AgentAdvancedConfigurable(
    private val project: Project
) : SearchableConfigurable {

    private val agentSettings = AgentSettings.getInstance(project)
    private val pluginSettings = PluginSettings.getInstance(project)

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    // Mutable copies for the UI — written to settings.state only on apply()
    private var showDebugLog = agentSettings.state.showDebugLog
    private var smartWorkingIndicator = agentSettings.state.smartWorkingIndicator
    private var powershellEnabled = agentSettings.state.powershellEnabled

    override fun getId(): String = "workflow.orchestrator.agent.advanced"
    override fun getDisplayName(): String = "Advanced"

    override fun createComponent(): JComponent {
        val innerPanel = panel {
            group("Debugging & Diagnostics") {
                row {
                    checkBox("Show debug log panel (displays real-time agent activity in chat)")
                        .bindSelected(::showDebugLog)
                        .comment(
                            "Enables an expandable debug panel in the chat view showing tool events, " +
                                "context usage, and loop diagnostics"
                        )
                }
                row {
                    checkBox("Smart working indicator (experimental)")
                        .bindSelected(::smartWorkingIndicator)
                        .comment("Uses a lightweight AI model to generate contextual loading messages")
                }
                row {
                    checkBox("Allow PowerShell in run_command tool")
                        .bindSelected(::powershellEnabled)
                        .comment(
                            "When disabled, the agent cannot use PowerShell — only bash and cmd are available"
                        )
                }
            }

            group("Network") {
                row("HTTP connect timeout (seconds):") {
                    intTextField(1..300)
                        .bindIntText(pluginSettings.state::httpConnectTimeoutSeconds)
                }
                row("HTTP read timeout (seconds):") {
                    intTextField(1..600)
                        .bindIntText(pluginSettings.state::httpReadTimeoutSeconds)
                }
            }

            group("Jira Custom Fields") {
                row("Epic link field ID:") {
                    textField()
                        .bindText(
                            { pluginSettings.state.epicLinkFieldId ?: "" },
                            { pluginSettings.state.epicLinkFieldId = it }
                        )
                }
                row("Reviewer field ID:") {
                    textField()
                        .bindText(
                            { pluginSettings.state.reviewerFieldId ?: "" },
                            { pluginSettings.state.reviewerFieldId = it }
                        )
                }
                row("Tester field ID:") {
                    textField()
                        .bindText(
                            { pluginSettings.state.testerFieldId ?: "" },
                            { pluginSettings.state.testerFieldId = it }
                        )
                }
                row {
                    comment(
                        "Used by AI pre-review and other Jira integration features that need to " +
                            "read/write custom fields."
                    )
                }
            }
        }
        dialogPanel = innerPanel
        return innerPanel
    }

    override fun isModified(): Boolean = dialogPanel?.isModified() ?: false

    override fun apply() {
        dialogPanel?.apply()
        agentSettings.state.showDebugLog = showDebugLog
        agentSettings.state.smartWorkingIndicator = smartWorkingIndicator
        agentSettings.state.powershellEnabled = powershellEnabled
    }

    override fun reset() {
        showDebugLog = agentSettings.state.showDebugLog
        smartWorkingIndicator = agentSettings.state.smartWorkingIndicator
        powershellEnabled = agentSettings.state.powershellEnabled
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }
}
