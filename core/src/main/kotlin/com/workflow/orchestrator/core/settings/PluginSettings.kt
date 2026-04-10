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
        // Polling intervals (seconds)
        var buildPollIntervalSeconds by property(30)

        // Bamboo plan key (auto-detected or user-configured)
        var bambooPlanKey by string("")

        // Automation: this repo's docker tag key in dockerTagsAsJson
        var dockerTagKey by string("")

        // Automation: this repo's CI build plan key (for docker tag extraction)
        var serviceCiPlanKey by string("")

        // SonarQube project key (auto-detected or user-configured)
        var sonarProjectKey by string("")

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

        // AI availability determined by LlmBrainFactory.isAvailable() (Sourcegraph URL + token)

        // Health check settings
        var healthCheckEnabled by property(true)
        var healthCheckBlockingMode by string("soft")
        var healthCheckCompileEnabled by property(true)
        var healthCheckTestEnabled by property(true)
        var healthCheckCopyrightEnabled by property(true)
        var healthCheckSonarGateEnabled by property(true)
        var healthCheckMavenGoals by string("clean compile test")
        var healthCheckSkipBranchPattern by string("")
        var healthCheckTimeoutSeconds by property(300)
        var copyrightHeaderPattern by string("")

        // Automation & Docker Registry settings
        var dockerRegistryUrl by string("")
        var queueAutoTriggerEnabled by property(true)
        var tagValidationOnTrigger by property(true)
        var queueMaxDepthPerSuite by property(10)

        // Phase 2B: Handover settings
        var defaultTargetBranch by string("develop")
        var branchTargetOverrides by string("")
        var bitbucketProjectKey by string("")
        var bitbucketRepoSlug by string("")
        var startWorkTimestamp by property(0L)

        // --- Configurable values (Phase: Config Extraction) ---

        // Jira workflow mapping (serialized JSON of TransitionMapping list)
        var workflowMappings by string("")

        // Jira board type filter ("scrum", "kanban", or "" for all)
        var jiraBoardType by string("scrum")

        // Coverage thresholds (percentage)
        var coverageHighThreshold by property(80.0f)
        var coverageMediumThreshold by property(50.0f)

        // HTTP timeouts (seconds) — applied to all API clients via HttpClientFactory
        var httpConnectTimeoutSeconds by property(10)
        var httpReadTimeoutSeconds by property(30)

        // Time tracking
        var maxWorklogHours by property(7.0f)
        var worklogIncrementHours by property(0.5f)

        // VCS commit handler toggles
        var autoTransitionOnCommit by property(false)

        // Branching & PRs
        var branchMaxSummaryLength by property(50)
        var prTitleFormat by string("{ticketId}: {summary}")
        var maxPrTitleLength by property(120)
        var prDefaultReviewers by string("")

        // Jira custom fields
        var epicLinkFieldId by string("customfield_10014")
        var reviewerFieldId by string("")
        var testerFieldId by string("")

        // AI review
        var maxDiffLinesForReview by property(10000)

        // SonarQube
        var sonarMetricKeys by string("coverage,line_coverage,branch_coverage,uncovered_lines,uncovered_conditions,new_coverage,new_branch_coverage")
        var coverageGutterMarkersEnabled by property(false)
        var sonarIntentionActionEnabled by property(false)
        var sonarInlineAnnotationsEnabled by property(false)

        // Automation
        var tagHistoryMaxEntries by property(5)
        var bambooBuildVariableName by string("dockerTagsAsJson")

        // AI agent
        var useXmlToolMode by property(true)  // Default ON — XML fixes parallel tool calls

        // Sprint dashboard view preferences
        var sprintSortBy by string("Default")

        // Multi-repo configuration
        var repos by list<RepoConfig>()
    }

    /**
     * Convenience accessor for global connection URLs.
     */
    val connections: ConnectionSettings.State
        get() = ConnectionSettings.getInstance().state

    val isAnyServiceConfigured: Boolean
        get() {
            val gs = ConnectionSettings.getInstance().state
            return gs.jiraUrl.isNotBlank() ||
                    gs.bambooUrl.isNotBlank() ||
                    gs.bitbucketUrl.isNotBlank() ||
                    gs.sonarUrl.isNotBlank() ||
                    gs.sourcegraphUrl.isNotBlank() ||
                    gs.nexusUrl.isNotBlank()
        }

    // ---- Multi-repo convenience accessors ----

    fun getRepos(): List<RepoConfig> = state.repos.toList()

    fun getPrimaryRepo(): RepoConfig? = state.repos.find { it.isPrimary } ?: state.repos.firstOrNull()

    fun getRepoForPath(vcsRootPath: String): RepoConfig? = state.repos.find { it.localVcsRootPath == vcsRootPath }

    fun getRepoByName(name: String): RepoConfig? = state.repos.find { it.name == name }

    companion object {
        fun getInstance(project: Project): PluginSettings {
            return project.service<PluginSettings>()
        }
    }
}
