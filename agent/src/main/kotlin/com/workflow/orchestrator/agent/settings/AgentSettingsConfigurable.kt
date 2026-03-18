package com.workflow.orchestrator.agent.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

class AgentSettingsConfigurable(
    private val project: Project
) : SearchableConfigurable {

    private val settings = AgentSettings.getInstance(project)

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    // Mutable copies for the UI — written to settings.state only on apply()
    private var agentEnabled = settings.state.agentEnabled
    private var sourcegraphChatModel = settings.state.sourcegraphChatModel ?: "anthropic/claude-sonnet-4"
    private var maxInputTokens = settings.state.maxInputTokens
    private var maxOutputTokens = settings.state.maxOutputTokens
    private var enableFastPath = settings.state.enableFastPath
    private var approvalRequiredForEdits = settings.state.approvalRequiredForEdits
    private var tokenBudgetWarningPercent = settings.state.tokenBudgetWarningPercent

    override fun getId(): String = "workflow.orchestrator.agent"
    override fun getDisplayName(): String = "Agent"

    override fun createComponent(): JComponent {
        val innerPanel = panel {
            group("Agent Features") {
                row {
                    checkBox("Enable Agent features")
                        .bindSelected(::agentEnabled)
                        .comment("Enables the Agent tab and AI-powered task orchestration")
                }
            }
            group("LLM Configuration") {
                row("Chat model:") {
                    textField()
                        .columns(30)
                        .bindText(::sourcegraphChatModel)
                        .comment("Sourcegraph chat model identifier (e.g., anthropic/claude-sonnet-4)")
                }
                row("Max input tokens:") {
                    intTextField(1000..1000000, 1000)
                        .bindIntText(::maxInputTokens)
                        .comment("Maximum tokens for LLM input context")
                }
                row("Max output tokens:") {
                    intTextField(1000..200000, 1000)
                        .bindIntText(::maxOutputTokens)
                        .comment("Maximum tokens for LLM output")
                }
            }
            group("Behavior") {
                row {
                    checkBox("Enable fast path for simple tasks")
                        .bindSelected(::enableFastPath)
                        .comment("Routes simple tasks directly to a single worker instead of full orchestration")
                }
                row {
                    checkBox("Require approval for file edits")
                        .bindSelected(::approvalRequiredForEdits)
                        .comment("Shows a confirmation dialog before the agent modifies files")
                }
                row("Token budget warning (%):") {
                    spinner(0..100, 5)
                        .bindIntValue(::tokenBudgetWarningPercent)
                        .comment("Show warning when token usage exceeds this percentage")
                }
            }
        }
        dialogPanel = innerPanel
        return innerPanel
    }

    override fun isModified(): Boolean {
        return dialogPanel?.isModified() ?: false
    }

    override fun apply() {
        dialogPanel?.apply()

        settings.state.agentEnabled = agentEnabled
        settings.state.sourcegraphChatModel = sourcegraphChatModel
        settings.state.maxInputTokens = maxInputTokens
        settings.state.maxOutputTokens = maxOutputTokens
        settings.state.enableFastPath = enableFastPath
        settings.state.approvalRequiredForEdits = approvalRequiredForEdits
        settings.state.tokenBudgetWarningPercent = tokenBudgetWarningPercent
    }

    override fun reset() {
        agentEnabled = settings.state.agentEnabled
        sourcegraphChatModel = settings.state.sourcegraphChatModel ?: "anthropic/claude-sonnet-4"
        maxInputTokens = settings.state.maxInputTokens
        maxOutputTokens = settings.state.maxOutputTokens
        enableFastPath = settings.state.enableFastPath
        approvalRequiredForEdits = settings.state.approvalRequiredForEdits
        tokenBudgetWarningPercent = settings.state.tokenBudgetWarningPercent
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }
}
