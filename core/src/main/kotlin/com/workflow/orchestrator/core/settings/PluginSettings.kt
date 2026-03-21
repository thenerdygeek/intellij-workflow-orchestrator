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
        var nexusUsername by string("")

        // Polling intervals (seconds)
        var buildPollIntervalSeconds by property(30)
        var queuePollIntervalSeconds by property(60)
        var sonarPollIntervalSeconds by property(60)

        // Bamboo plan key (auto-detected or user-configured)
        var bambooPlanKey by string("")

        // Automation: this repo's docker tag key in dockerTagsAsJson
        var dockerTagKey by string("")

        // Automation: this repo's CI build plan key (for docker tag extraction)
        var serviceCiPlanKey by string("")

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
        var jiraBoardName by string("")
        var boardFilterRegex by string("")

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

        // Automation & Docker Registry settings
        var dockerRegistryUrl by string("")
        var dockerRegistryCaPath by string("")
        var queueActivePollingIntervalSeconds by property(15)
        var queueAutoTriggerEnabled by property(true)
        var tagValidationOnTrigger by property(true)
        var queueMaxDepthPerSuite by property(10)
        var queueBuildQueuedTimeoutSeconds by property(720)

        // Phase 2B: Handover settings
        var defaultTargetBranch by string("develop")
        var bitbucketProjectKey by string("")
        var bitbucketRepoSlug by string("")
        var startWorkTimestamp by property(0L)

        // --- Configurable values (Phase: Config Extraction) ---

        // Jira workflow mapping (serialized JSON of TransitionMapping list)
        var workflowMappings by string("")

        // Jira board type filter ("scrum", "kanban", or "" for all)
        var jiraBoardType by string("scrum")

        // Plugin guards (transition prerequisites)
        var guardBuildPassedBeforeReview by property(false)
        var guardCopyrightBeforeClose by property(false)
        var guardCoverageBeforeReview by property(false)
        var guardAutomationBeforeClose by property(false)

        // Coverage thresholds (percentage)
        var coverageHighThreshold by property(80.0f)
        var coverageMediumThreshold by property(50.0f)

        // HTTP timeouts (seconds) — applied to all API clients via HttpClientFactory
        var httpConnectTimeoutSeconds by property(10)
        var httpReadTimeoutSeconds by property(30)

        // Time tracking
        var maxWorklogHours by property(7.0f)
        var worklogIncrementHours by property(0.5f)

        // Branching & PRs
        var branchMaxSummaryLength by property(50)
        var prTitleFormat by string("{ticketId}: {summary}")
        var maxPrTitleLength by property(120)
        var prDefaultReviewers by string("")

        // Jira custom fields
        var epicLinkFieldId by string("customfield_10014")
        var reviewerFieldId by string("")
        var testerFieldId by string("")

        // Cody AI
        var maxDiffLinesForReview by property(10000)

        // SonarQube
        var sonarMetricKeys by string("coverage,line_coverage,branch_coverage,uncovered_lines,uncovered_conditions,new_coverage,new_branch_coverage")

        // Automation
        var tagHistoryMaxEntries by property(5)
        var bambooBuildVariableName by string("dockerTagsAsJson")

        // Sprint dashboard view preferences
        var sprintGroupBy by string("Assignee")
        var sprintSortBy by string("Default")
    }

    /**
     * Convenience accessor for global connection URLs.
     * All code that previously read settings.state.jiraUrl should use settings.connections.jiraUrl.
     */
    val connections: ConnectionSettings.State
        get() = ConnectionSettings.getInstance().state

    /**
     * Migrates any URLs stored in per-project settings to the global ConnectionSettings.
     * Called once on first access. After migration, per-project URL fields are cleared.
     */
    fun migrateUrlsToGlobalIfNeeded() {
        val global = ConnectionSettings.getInstance()
        val gs = global.state
        var migrated = false

        fun migrate(localValue: String?, globalValue: String, setter: (String) -> Unit) {
            if (!localValue.isNullOrBlank() && globalValue.isBlank()) {
                setter(localValue)
                migrated = true
            }
        }

        migrate(state.jiraUrl, gs.jiraUrl) { gs.jiraUrl = it }
        migrate(state.bambooUrl, gs.bambooUrl) { gs.bambooUrl = it }
        migrate(state.bitbucketUrl, gs.bitbucketUrl) { gs.bitbucketUrl = it }
        migrate(state.sonarUrl, gs.sonarUrl) { gs.sonarUrl = it }
        migrate(state.sourcegraphUrl, gs.sourcegraphUrl) { gs.sourcegraphUrl = it }
        migrate(state.nexusUrl, gs.nexusUrl) { gs.nexusUrl = it }
        if (!state.nexusUsername.isNullOrBlank() && gs.nexusUsername.isBlank()) {
            gs.nexusUsername = state.nexusUsername ?: ""
            migrated = true
        }

        if (migrated) {
            // Clear per-project URL fields after migration
            state.jiraUrl = ""
            state.bambooUrl = ""
            state.bitbucketUrl = ""
            state.sonarUrl = ""
            state.sourcegraphUrl = ""
            state.nexusUrl = ""
            state.nexusUsername = ""
        }
    }

    val isAnyServiceConfigured: Boolean
        get() {
            val gs = ConnectionSettings.getInstance().state
            return gs.jiraUrl.isNotBlank() ||
                    gs.bambooUrl.isNotBlank() ||
                    gs.bitbucketUrl.isNotBlank() ||
                    gs.sonarUrl.isNotBlank() ||
                    gs.sourcegraphUrl.isNotBlank() ||
                    gs.nexusUrl.isNotBlank() ||
                    // Fallback: check legacy per-project fields (pre-migration)
                    !state.jiraUrl.isNullOrBlank() ||
                    !state.bambooUrl.isNullOrBlank() ||
                    !state.bitbucketUrl.isNullOrBlank() ||
                    !state.sonarUrl.isNullOrBlank() ||
                    !state.sourcegraphUrl.isNullOrBlank() ||
                    !state.nexusUrl.isNullOrBlank()
        }

    companion object {
        fun getInstance(project: Project): PluginSettings {
            return project.service<PluginSettings>()
        }
    }
}
