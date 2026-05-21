package com.workflow.orchestrator.agent.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*
import com.workflow.orchestrator.core.settings.PluginSettings
import javax.swing.JComponent

/**
 * "AI Agent → Advanced" sub-page.
 *
 * Power-user settings that most users never touch:
 *  - Debugging toggles (debug panel, smart working indicator, PowerShell, cmd)
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
    private var cmdEnabled = agentSettings.state.cmdEnabled

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
                        .comment("When disabled, the agent will not offer PowerShell as a shell option in run_command")
                }
                row {
                    checkBox("Allow cmd.exe in run_command tool")
                        .bindSelected(::cmdEnabled)
                        .comment("When disabled, the agent will not offer cmd.exe as a shell option in run_command")
                }
            }

            group("Tool Calling") {
                row("Tool execution mode:") {
                    comboBox(listOf("accumulate", "stream_interrupt"))
                        .bindItem(
                            { agentSettings.state.toolExecutionMode },
                            { agentSettings.state.toolExecutionMode = it ?: "accumulate" }
                        )
                        .comment("accumulate: execute all tools after response completes (default). " +
                            "stream_interrupt: execute each tool as soon as it appears.")
                }
                row {
                    checkBox("Stream edit_file diff preview into the chat as it generates")
                        .bindSelected(
                            { pluginSettings.state.enableStreamingEditPreview },
                            { pluginSettings.state.enableStreamingEditPreview = it }
                        )
                        .comment(
                            "When the LLM streams an <code>edit_file</code> tool call, " +
                                "the chat panel renders the unified diff live as " +
                                "<code>new_string</code> arrives (throttled to 100ms ticks). " +
                                "When the tool call completes, the approval card (or " +
                                "session-approved write) takes over. Disable if you find it " +
                                "laggy or noisy. Note: toggling this off takes effect at the " +
                                "next iteration — any preview already streaming will finish " +
                                "in the current iteration."
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
            }

            group("Documents") {
                row("Max characters extracted per document:") {
                    intTextField(1_000..2_000_000, 10_000)
                        .bindIntText(pluginSettings.state::documentMaxChars)
                        .comment(
                            "Hard cap on characters returned from a single document. " +
                                "Set to 0 (or below) for no cap. Default: 200 000."
                        )
                }
                row("Extraction timeout (ms):") {
                    textField()
                        .columns(12)
                        .bindText(
                            { pluginSettings.state.documentTimeoutMs.toString() },
                            { pluginSettings.state.documentTimeoutMs = it.toLongOrNull() ?: 30_000L }
                        )
                        .comment(
                            "Maximum time allowed for a single document extraction before the " +
                                "agent receives a timeout error. Range: 5 000–600 000 ms. Default: 30 000 ms."
                        )
                }
                row {
                    checkBox(
                        "Enable Tabula stream-mode fallback " +
                            "(off by default; may produce phantom tables on multi-column prose)"
                    )
                        .bindSelected(
                            { pluginSettings.state.documentEnableStreamMode },
                            { pluginSettings.state.documentEnableStreamMode = it }
                        )
                }
                row {
                    checkBox("Enable OCR for scanned PDFs")
                        .applyToComponent {
                            isEnabled = false
                            toolTipText = "Coming in v2"
                        }
                        .comment("Coming in v2")
                }
            }

            group("Tool Feedback") {
                row {
                    checkBox("Enable tool feedback collection after task completion")
                        .bindSelected(agentSettings.state::agentFeedbackEnabled)
                        .comment(
                            "When enabled, the agent is asked to use the <code>feedback</code> tool " +
                            "immediately after completing a task. It reports any tools that misbehaved, " +
                            "had confusing parameters, or returned unexpected results. " +
                            "Feedback is appended to <code>~/.workflow-orchestrator/feedback.md</code>."
                        )
                }
            }

            group("Background processes") {
                row("Concurrent processes per session:") {
                    intTextField(1..20)
                        .bindIntText(agentSettings.state::concurrentBackgroundProcessesPerSession)
                }
                row {
                    checkBox("Auto-wake session on background completion")
                        .bindSelected(agentSettings.state::autoWakeOnBackgroundCompletion)
                }
                row("Max auto-wakes per session:") {
                    intTextField(1..50)
                        .bindIntText(agentSettings.state::autoWakeMaxPerSession)
                }
                row("Auto-wake cooldown (ms):") {
                    textField()
                        .columns(12)
                        .bindText(
                            { agentSettings.state.autoWakeCooldownMs.toString() },
                            { agentSettings.state.autoWakeCooldownMs = it.toLongOrNull() ?: 5_000L }
                        )
                }
                row {
                    checkBox("Suppress kill-on-session-transition confirmation")
                        .bindSelected(agentSettings.state::suppressBackgroundKillConfirmation)
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
        agentSettings.state.cmdEnabled = cmdEnabled
    }

    override fun reset() {
        showDebugLog = agentSettings.state.showDebugLog
        smartWorkingIndicator = agentSettings.state.smartWorkingIndicator
        powershellEnabled = agentSettings.state.powershellEnabled
        cmdEnabled = agentSettings.state.cmdEnabled
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }
}
