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

        // Bamboo plan key (auto-detected or user-configured)
        var bambooPlanKey by string("")

        // SonarQube project key (auto-detected or user-configured)
        var sonarProjectKey by string("")

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

        // Active ticket (persisted across restarts)
        var activeTicketId by string("")
        var activeTicketSummary by string("")
        var jiraBoardId by property(0)

        // Cody AI configuration
        var codyAgentPath by string("")
        var codyEnabled by property(true)

        // Health check settings
        var healthCheckEnabled by property(true)
        var healthCheckBlockingMode by string("soft")
        var healthCheckCompileEnabled by property(true)
        var healthCheckTestEnabled by property(true)
        var healthCheckCopyrightEnabled by property(true)
        var healthCheckSonarGateEnabled by property(true)
        var healthCheckCveEnabled by property(true)
        var healthCheckMavenGoals by string("clean compile test")
        var healthCheckSkipBranchPattern by string("")
        var healthCheckTimeoutSeconds by property(300)
        var copyrightHeaderPattern by string("")
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
