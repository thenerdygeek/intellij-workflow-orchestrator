package com.workflow.orchestrator.automation.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XMap

/**
 * Application-level persistence for automation suites + per-suite variables.
 *
 * **Roaming (PR 7 #7).** `roamingType = RoamingType.DEFAULT` lets IntelliJ's
 * Settings Sync feature carry suites and their custom variables across the
 * user's machines — so a developer who configures `featureFlag=true` on
 * Mac sees the same value pre-filled on their Windows test box. The XML
 * filename was stable (`workflowAutomationSuites.xml`) before this change;
 * we keep it identical so existing on-disk state still loads on upgrade.
 *
 * **Why APP scope.** Suites are organisational artefacts — every project the
 * user opens against the same Bamboo instance shares them. Project-scoping
 * would force the user to re-add every suite per project, which they
 * explicitly pushed back on.
 */
@Service(Service.Level.APP)
@State(
    name = "AutomationSuiteSettings",
    storages = [Storage("workflowAutomationSuites.xml", roamingType = RoamingType.DEFAULT)]
)
class AutomationSettingsService : PersistentStateComponent<AutomationSettingsService.SettingsState> {

    @Tag("suite")
    data class SuiteConfig(
        var planKey: String = "",
        var displayName: String = "",
        /**
         * Plan-scoped variables: keys MUST exist in the suite's Bamboo plan
         * `?expand=variableContext` response. Used by the existing variable
         * picker dropdown in [com.workflow.orchestrator.automation.ui.SuiteConfigPanel].
         */
        @MapAnnotation(surroundWithTag = false, entryTagName = "variable", keyAttributeName = "key", valueAttributeName = "value")
        var variables: MutableMap<String, String> = mutableMapOf(),
        var enabledStages: MutableList<String> = mutableListOf(),
        @MapAnnotation(surroundWithTag = false, entryTagName = "mapping", keyAttributeName = "docker", valueAttributeName = "service")
        var serviceNameMapping: MutableMap<String, String>? = null,
        var lastModified: Long = 0,
        /**
         * Free-form per-suite extras (PR 7 #7). Keys are user-supplied — they
         * are NOT validated against the plan's variable list. Merged into the
         * trigger payload at trigger-time (`TagBuilderService.buildTriggerVariables`)
         * so they show up in Bamboo as `bamboo.variable.<key>=<value>`.
         *
         * Conflict resolution: if the user creates an extra named
         * `DockerTagsAsJSON`, the auto-generated docker tags payload always wins —
         * see [com.workflow.orchestrator.automation.service.TagBuilderService.buildTriggerVariables]
         * for the merge order.
         *
         * Placed last in the parameter list so existing positional constructor
         * calls (e.g. tests built before PR 7) continue to work without edits.
         */
        @MapAnnotation(surroundWithTag = false, entryTagName = "extra", keyAttributeName = "key", valueAttributeName = "value")
        var extraVariables: MutableMap<String, String> = mutableMapOf()
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

    /**
     * Returns the free-form extras for [planKey], or empty when no suite is
     * configured / no extras have been added. Convenience wrapper over
     * [getSuiteConfig] for trigger-time merging.
     */
    fun getExtraVariables(planKey: String): Map<String, String> =
        getSuiteConfig(planKey)?.extraVariables?.toMap().orEmpty()

    /**
     * Replaces the extras map for [planKey]. Creates a new SuiteConfig when
     * the suite isn't already in state. Persists immediately — IntelliJ flushes
     * the underlying XML file on a periodic schedule (no explicit save needed).
     */
    fun setExtraVariables(planKey: String, extras: Map<String, String>) {
        val existing = myState.suites[planKey]
        val updated = (existing ?: SuiteConfig(planKey = planKey, displayName = planKey))
            .also {
                it.extraVariables = extras.toMutableMap()
                it.lastModified = System.currentTimeMillis()
            }
        myState.suites[planKey] = updated
    }

    companion object {
        fun getInstance(): AutomationSettingsService =
            ApplicationManager.getApplication().getService(AutomationSettingsService::class.java)
    }
}
