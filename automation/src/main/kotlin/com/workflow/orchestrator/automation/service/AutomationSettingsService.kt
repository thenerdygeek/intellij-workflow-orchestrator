package com.workflow.orchestrator.automation.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "AutomationSuiteSettings",
    storages = [Storage("workflowAutomationSuites.xml")]
)
class AutomationSettingsService : PersistentStateComponent<AutomationSettingsService.SettingsState> {

    data class SuiteConfig(
        val planKey: String = "",
        val displayName: String = "",
        val variables: Map<String, String> = emptyMap(),
        val enabledStages: List<String> = emptyList(),
        val serviceNameMapping: Map<String, String>? = null,
        val lastModified: Long = 0
    )

    data class SettingsState(
        var suites: MutableMap<String, SuiteConfig> = mutableMapOf()
    )

    private var myState = SettingsState()

    override fun getState(): SettingsState = myState

    override fun loadState(state: SettingsState) {
        myState = state
    }

    fun getSuiteConfig(planKey: String): SuiteConfig? = myState.suites[planKey]

    fun saveSuiteConfig(config: SuiteConfig) {
        myState.suites[config.planKey] = config
    }

    fun getAllSuites(): List<SuiteConfig> = myState.suites.values.toList()

    companion object {
        fun getInstance(): AutomationSettingsService =
            ApplicationManager.getApplication().getService(AutomationSettingsService::class.java)
    }
}
