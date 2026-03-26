package com.workflow.orchestrator.agent.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.agent.api.SourcegraphChatClient
import com.workflow.orchestrator.agent.api.dto.ModelInfo
import com.workflow.orchestrator.core.ai.ModelCache
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.awt.Component
import javax.swing.*

class AgentSettingsConfigurable(
    private val project: Project
) : SearchableConfigurable {

    companion object {
        private val LOG = Logger.getInstance(AgentSettingsConfigurable::class.java)
    }

    private val settings = AgentSettings.getInstance(project)

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    // Mutable copies for the UI — written to settings.state only on apply()
    private var agentEnabled = settings.state.agentEnabled
    private var sourcegraphChatModel = settings.state.sourcegraphChatModel ?: ""
    private var maxInputTokens = settings.state.maxInputTokens
    private var maxOutputTokens = settings.state.maxOutputTokens
    private var enableFastPath = settings.state.enableFastPath
    private var approvalRequiredForEdits = settings.state.approvalRequiredForEdits
    private var tokenBudgetWarningPercent = settings.state.tokenBudgetWarningPercent
    private var showDebugLog = settings.state.showDebugLog

    // Model dropdown state
    private var modelComboBox: JComboBox<ModelItem>? = null
    private var modelStatusLabel: JBLabel? = null
    private var loadModelsButton: JButton? = null
    private val loadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cachedModels: List<ModelInfo> = emptyList()

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
                    val combo = JComboBox(DefaultComboBoxModel<ModelItem>())
                    combo.renderer = ModelCellRenderer()
                    combo.isEditable = true
                    combo.preferredSize = JBUI.size(350, 30)
                    modelComboBox = combo

                    // Set current value
                    if (sourcegraphChatModel.isNotBlank()) {
                        combo.editor.item = ModelItem(
                            ModelInfo(id = sourcegraphChatModel),
                            isManualEntry = true
                        )
                    }

                    combo.addActionListener {
                        val selected = combo.selectedItem
                        if (selected is ModelItem && !selected.isSeparator) {
                            sourcegraphChatModel = selected.model.id
                            // Mark as manually selected so auto-upgrade doesn't override
                            settings.state.userManuallySelectedModel = true
                        } else if (selected is String) {
                            sourcegraphChatModel = selected
                            settings.state.userManuallySelectedModel = true
                        }
                    }

                    cell(combo)
                }
                row {
                    val loadBtn = button("Load Models") { loadModelsFromServer() }.component
                    loadModelsButton = loadBtn
                    loadBtn.icon = AllIcons.Actions.Refresh

                    val statusLbl = JBLabel("")
                    modelStatusLabel = statusLbl
                    cell(statusLbl)
                }.comment("Fetches available models from your Sourcegraph instance")

                row("Max input tokens:") {
                    intTextField(1000..1000000, 1000)
                        .bindIntText(::maxInputTokens)
                        .comment("Maximum tokens for LLM input context (probe your instance with tools/probe-model-limits.py)")
                }
                row("Max output tokens:") {
                    intTextField(1000..10000, 500)
                        .bindIntText(::maxOutputTokens)
                        .comment("Maximum tokens per LLM response (Sourcegraph API cap: 4,000)")
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
            group("Advanced") {
                row {
                    checkBox("Show debug log panel (displays real-time agent activity in chat)")
                        .bindSelected(::showDebugLog)
                        .comment("Enables an expandable debug panel in the chat view showing tool events, context usage, and loop diagnostics")
                }
            }
        }
        dialogPanel = innerPanel
        return innerPanel
    }

    /**
     * Fetch models from the Sourcegraph instance and populate the dropdown.
     */
    private fun loadModelsFromServer() {
        val connections = ConnectionSettings.getInstance()
        val credentialStore = CredentialStore()

        val url = connections.state.sourcegraphUrl
        val token = credentialStore.getToken(ServiceType.SOURCEGRAPH)

        if (url.isBlank()) {
            modelStatusLabel?.text = "Configure Sourcegraph URL in Connections first"
            modelStatusLabel?.foreground = JBColor.RED
            return
        }

        if (token.isNullOrBlank()) {
            modelStatusLabel?.text = "Configure Sourcegraph token in Connections first"
            modelStatusLabel?.foreground = JBColor.RED
            return
        }

        // Show loading state
        loadModelsButton?.isEnabled = false
        modelStatusLabel?.icon = AnimatedIcon.Default()
        modelStatusLabel?.text = "Loading models..."
        modelStatusLabel?.foreground = JBColor.foreground()

        loadScope.launch {
            try {
                val client = SourcegraphChatClient(
                    baseUrl = url.trimEnd('/'),
                    tokenProvider = { token },
                    model = "", // Not needed for listing
                    httpClientOverride = OkHttpClient.Builder()
                        .addInterceptor(AuthInterceptor({ token }, AuthScheme.TOKEN))
                        .build()
                )

                val result = client.listModels()

                SwingUtilities.invokeLater {
                    when (result) {
                        is ApiResult.Success -> {
                            cachedModels = result.data.data
                            // Share with ModelCache so LlmBrainFactory can use cached models
                            ModelCache.populateFromExternal(cachedModels)
                            populateModelDropdown(cachedModels)
                            modelStatusLabel?.icon = AllIcons.General.InspectionsOK
                            modelStatusLabel?.text = "${cachedModels.size} models loaded"
                            modelStatusLabel?.foreground = JBColor(java.awt.Color(0, 128, 0), java.awt.Color(0, 180, 0))
                        }
                        is ApiResult.Error -> {
                            modelStatusLabel?.icon = AllIcons.General.Error
                            modelStatusLabel?.text = "Failed: ${result.message}"
                            modelStatusLabel?.foreground = JBColor.RED
                            LOG.warn("Failed to load models: ${result.message}")
                        }
                    }
                    loadModelsButton?.isEnabled = true
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    modelStatusLabel?.icon = AllIcons.General.Error
                    modelStatusLabel?.text = "Error: ${e.message}"
                    modelStatusLabel?.foreground = JBColor.RED
                    loadModelsButton?.isEnabled = true
                }
            }
        }
    }

    private fun populateModelDropdown(models: List<ModelInfo>) {
        val combo = modelComboBox ?: return
        val comboModel = DefaultComboBoxModel<ModelItem>()

        // Group by provider, sorted: Anthropic first, then alphabetical
        val grouped = models.groupBy { it.displayProvider }.toSortedMap(compareBy {
            when (it) {
                "Anthropic" -> "0"
                "OpenAI" -> "1"
                "Google" -> "2"
                else -> "9$it"
            }
        })

        // Within each provider, sort by tier (opus > sonnet > haiku) then thinking first
        for ((provider, providerModels) in grouped) {
            comboModel.addElement(ModelItem.separator("— $provider —"))
            val sorted = providerModels.sortedWith(
                compareBy<ModelInfo> { it.tier }
                    .thenBy { if (it.isThinkingModel) 0 else 1 }
                    .thenBy { it.displayName }
            )
            for (model in sorted) {
                comboModel.addElement(ModelItem(model))
            }
        }

        combo.model = comboModel

        // Auto-select logic:
        // 1. If user has a configured model that's in the list, select it
        // 2. If user has the default model (never changed), auto-upgrade to best available
        // 3. Otherwise select the best Opus/thinking model available
        val currentId = sourcegraphChatModel
        val userManuallySelected = settings.state.userManuallySelectedModel

        if (userManuallySelected && currentId.isNotBlank()) {
            // User manually set a model — respect their choice
            selectModelInDropdown(combo, comboModel, currentId)
        } else {
            // User hasn't manually selected — auto-upgrade to best available
            val bestModel = findBestModel(models)
            if (bestModel != null) {
                selectModelInDropdown(combo, comboModel, bestModel.id)
                sourcegraphChatModel = bestModel.id
            }
        }
    }

    /**
     * Find the best available model: prefer latest Opus thinking > Opus > latest Sonnet.
     * Delegates to ModelCache.pickBest for consistent model selection across the plugin.
     */
    private fun findBestModel(models: List<ModelInfo>): ModelInfo? {
        return ModelCache.pickBest(models)
    }

    private fun selectModelInDropdown(combo: JComboBox<ModelItem>, comboModel: DefaultComboBoxModel<ModelItem>, modelId: String) {
        for (i in 0 until comboModel.size) {
            val item = comboModel.getElementAt(i)
            if (!item.isSeparator && item.model.id == modelId) {
                combo.selectedIndex = i
                return
            }
        }
        // Not found — add as manual entry at top
        val manualItem = ModelItem(ModelInfo(id = modelId), isManualEntry = true)
        comboModel.insertElementAt(manualItem, 0)
        combo.selectedIndex = 0
    }

    override fun isModified(): Boolean {
        return dialogPanel?.isModified() ?: false ||
            sourcegraphChatModel != (settings.state.sourcegraphChatModel ?: "")
    }

    override fun apply() {
        dialogPanel?.apply()

        // Read model from combo box
        val combo = modelComboBox
        if (combo != null) {
            val selected = combo.selectedItem
            sourcegraphChatModel = when (selected) {
                is ModelItem -> selected.model.id
                is String -> selected
                else -> sourcegraphChatModel
            }
        }

        settings.state.agentEnabled = agentEnabled
        settings.state.sourcegraphChatModel = sourcegraphChatModel
        settings.state.maxInputTokens = maxInputTokens
        settings.state.maxOutputTokens = maxOutputTokens
        settings.state.enableFastPath = enableFastPath
        settings.state.approvalRequiredForEdits = approvalRequiredForEdits
        settings.state.tokenBudgetWarningPercent = tokenBudgetWarningPercent
        settings.state.showDebugLog = showDebugLog
    }

    override fun reset() {
        agentEnabled = settings.state.agentEnabled
        sourcegraphChatModel = settings.state.sourcegraphChatModel ?: ""
        maxInputTokens = settings.state.maxInputTokens
        maxOutputTokens = settings.state.maxOutputTokens
        enableFastPath = settings.state.enableFastPath
        approvalRequiredForEdits = settings.state.approvalRequiredForEdits
        tokenBudgetWarningPercent = settings.state.tokenBudgetWarningPercent
        showDebugLog = settings.state.showDebugLog
        dialogPanel?.reset()

        // Reset combo to current value
        if (sourcegraphChatModel.isNotBlank()) {
            modelComboBox?.editor?.item = ModelItem(
                ModelInfo(id = sourcegraphChatModel),
                isManualEntry = true
            )
        }
    }

    override fun disposeUIResources() {
        dialogPanel = null
        modelComboBox = null
        modelStatusLabel = null
        loadModelsButton = null
        loadScope.cancel()
    }

    // ═══════════════════════════════════════════════════
    //  Model dropdown data + renderer
    // ═══════════════════════════════════════════════════

    /**
     * Wrapper for models in the combo box.
     */
    data class ModelItem(
        val model: ModelInfo,
        val isManualEntry: Boolean = false,
        val isSeparator: Boolean = false,
        val separatorText: String = ""
    ) {
        override fun toString(): String = if (isSeparator) separatorText else model.displayName

        companion object {
            fun separator(text: String) = ModelItem(
                model = ModelInfo(id = ""),
                isSeparator = true,
                separatorText = text
            )
        }
    }

    /**
     * Custom renderer for the model dropdown.
     * Shows icons for thinking vs standard models, provider name, and model name.
     */
    private class ModelCellRenderer : ListCellRenderer<ModelItem> {
        private val label = JBLabel()

        override fun getListCellRendererComponent(
            list: JList<out ModelItem>?,
            value: ModelItem?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            if (value == null) {
                label.text = "Select a model..."
                label.icon = null
                return label
            }

            val model = value.model

            // Icon: brain for thinking models, message bubble for standard
            label.icon = if (model.isThinkingModel) {
                AllIcons.Actions.IntentionBulbGrey // Brain/lightbulb icon for thinking models
            } else {
                AllIcons.Nodes.Plugin // Standard model icon
            }

            // Text: formatted model name + provider
            val displayName = model.displayName
            val provider = model.displayProvider
            label.text = if (value.isManualEntry && !value.isSeparator) {
                "<html><b>${model.displayName}</b> <span style='color:gray;font-size:11px;'>${model.displayProvider}</span></html>"
            } else if (value.isSeparator) {
                "<html><b style='color:gray;font-size:11px;'>${value.separatorText}</b></html>"
            } else {
                "<html><b>$displayName</b> <span style='color:gray;font-size:11px;'>$provider</span></html>"
            }

            // Selection styling
            if (isSelected) {
                label.background = list?.selectionBackground
                label.foreground = list?.selectionForeground
                label.isOpaque = true
            } else {
                label.background = list?.background
                label.foreground = list?.foreground
                label.isOpaque = false
            }

            label.border = JBUI.Borders.empty(2, 4)
            return label
        }
    }
}
