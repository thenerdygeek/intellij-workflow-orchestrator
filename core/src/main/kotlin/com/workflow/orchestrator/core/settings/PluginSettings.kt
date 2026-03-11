package com.workflow.orchestrator.core.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "WorkflowOrchestratorSettings",
    storages = [Storage("workflowOrchestrator.xml")]
)
class PluginSettings : SimplePersistentStateComponent<PluginSettings.State>(State()) {

    class State : BaseState() {
        // Service endpoints
        var jiraUrl by string("")
        var bambooUrl by string("")
        var bitbucketUrl by string("")
        var sonarUrl by string("")
        var sourcegraphUrl by string("")
        var nexusUrl by string("")

        // Polling intervals (seconds)
        var buildPollIntervalSeconds by property(30)
        var queuePollIntervalSeconds by property(60)
        var sonarPollIntervalSeconds by property(60)

        // Feature toggles
        var sprintModuleEnabled by property(true)
        var buildModuleEnabled by property(true)
        var qualityModuleEnabled by property(true)
        var automationModuleEnabled by property(true)
        var handoverModuleEnabled by property(true)

        // Commit message format
        var useConventionalCommits by property(false)

        // Branch naming pattern
        var branchPattern by string("feature/{ticketId}-{summary}")
    }

    val isAnyServiceConfigured: Boolean
        get() = !state.jiraUrl.isNullOrBlank() ||
                !state.bambooUrl.isNullOrBlank() ||
                !state.bitbucketUrl.isNullOrBlank() ||
                !state.sonarUrl.isNullOrBlank() ||
                !state.sourcegraphUrl.isNullOrBlank() ||
                !state.nexusUrl.isNullOrBlank()

    companion object {
        fun getInstance(project: Project): PluginSettings {
            return project.service<PluginSettings>()
        }
    }
}
