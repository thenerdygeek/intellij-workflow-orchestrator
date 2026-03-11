package com.workflow.orchestrator.automation.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XMap

@Service(Service.Level.APP)
@State(
    name = "AutomationSuiteSettings",
    storages = [Storage("workflowAutomationSuites.xml")]
)
class AutomationSettingsService : PersistentStateComponent<AutomationSettingsService.SettingsState> {

    @Tag("suite")
    data class SuiteConfig(
        var planKey: String = "",
        var displayName: String = "",
        @MapAnnotation(surroundWithTag = false, entryTagName = "variable", keyAttributeName = "key", valueAttributeName = "value")
        var variables: MutableMap<String, String> = mutableMapOf(),
        var enabledStages: MutableList<String> = mutableListOf(),
        @MapAnnotation(surroundWithTag = false, entryTagName = "mapping", keyAttributeName = "docker", valueAttributeName = "service")
        var serviceNameMapping: MutableMap<String, String>? = null,
        var lastModified: Long = 0
    )

    data class SettingsState(
        @XMap(entryTagName = "suite", keyAttributeName = "planKey")
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
