package com.workflow.orchestrator.agent.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * "AI Agent → Process Tools" sub-page.
 *
 * Controls how the agent's interactive `run_command`, `send_stdin`, and
 * `ask_user_input` tools behave — specifically idle detection thresholds and
 * stdin rate limits. These fields all live on [AgentSettings.State].
 */
class AgentProcessToolsConfigurable(
    private val project: Project
) : SearchableConfigurable {

    private val settings = AgentSettings.getInstance(project)

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    // Mutable copies for the UI — written to settings.state only on apply()
    private var commandIdleThresholdSeconds = settings.state.commandIdleThresholdSeconds
    private var buildCommandIdleThresholdSeconds = settings.state.buildCommandIdleThresholdSeconds
    private var maxStdinPerProcess = settings.state.maxStdinPerProcess
    private var askUserInputTimeoutMinutes = settings.state.askUserInputTimeoutMinutes

    override fun getId(): String = "workflow.orchestrator.agent.process_tools"
    override fun getDisplayName(): String = "Process Tools"

    override fun createComponent(): JComponent {
        val innerPanel = panel {
            group("Process Tools") {
                row("Command idle threshold (seconds):") {
                    intTextField(1..600)
                        .bindIntText(::commandIdleThresholdSeconds)
                        .comment(
                            "Seconds without output before run_command is reported as [IDLE]. " +
                                "Default: 15."
                        )
                }
                row("Build command idle threshold (seconds):") {
                    intTextField(1..3600)
                        .bindIntText(::buildCommandIdleThresholdSeconds)
                        .comment(
                            "Idle threshold for build commands (mvn, gradle, npm, docker build, etc.). " +
                                "Builds frequently pause for downloads or compilation, so the threshold " +
                                "is higher. Default: 60."
                        )
                }
                row("Max stdin per process:") {
                    intTextField(1..100)
                        .bindIntText(::maxStdinPerProcess)
                        .comment(
                            "Maximum number of send_stdin calls allowed against a single running process."
                        )
                }
                row("Ask-user-input timeout (minutes):") {
                    intTextField(1..60)
                        .bindIntText(::askUserInputTimeoutMinutes)
                        .comment(
                            "Timeout (minutes) for ask_user_input waiting for the user to respond. " +
                                "After this many minutes the prompt expires."
                        )
                }
                row {
                    comment(
                        "These settings control how the agent's interactive run_command tool detects " +
                            "idle processes and limits how often it can interact with them. Changes " +
                            "apply to new tool calls, not running ones."
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
        settings.state.commandIdleThresholdSeconds = commandIdleThresholdSeconds
        settings.state.buildCommandIdleThresholdSeconds = buildCommandIdleThresholdSeconds
        settings.state.maxStdinPerProcess = maxStdinPerProcess
        settings.state.askUserInputTimeoutMinutes = askUserInputTimeoutMinutes
    }

    override fun reset() {
        commandIdleThresholdSeconds = settings.state.commandIdleThresholdSeconds
        buildCommandIdleThresholdSeconds = settings.state.buildCommandIdleThresholdSeconds
        maxStdinPerProcess = settings.state.maxStdinPerProcess
        askUserInputTimeoutMinutes = settings.state.askUserInputTimeoutMinutes
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }
}
