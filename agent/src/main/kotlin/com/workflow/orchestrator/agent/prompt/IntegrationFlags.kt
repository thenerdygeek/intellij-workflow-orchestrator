package com.workflow.orchestrator.agent.prompt

import com.workflow.orchestrator.core.settings.ConnectionSettings

/**
 * Which Atlassian/Sonar integrations are configured, derived from [ConnectionSettings].
 * Drives system-prompt gating (Phase 1b de-convention): the prompt mentions an integration
 * only when its flag is true. Resolved by the prompt's callers and PASSED IN to
 * [SystemPrompt.build] — never read from ConnectionSettings inside build(), which must stay
 * pure for the golden-snapshot tests.
 */
data class IntegrationFlags(
    val jira: Boolean = false,
    val bamboo: Boolean = false,
    val sonar: Boolean = false,
    val bitbucket: Boolean = false,
) {
    /** True if at least one integration is configured. */
    val any: Boolean get() = jira || bamboo || sonar || bitbucket

    companion object {
        val NONE = IntegrationFlags()
        val ALL = IntegrationFlags(jira = true, bamboo = true, sonar = true, bitbucket = true)

        /** Mirror of [com.workflow.orchestrator.agent.AgentService] tool-gating: `*Url.isNotBlank()`. */
        fun from(state: ConnectionSettings.State) = IntegrationFlags(
            jira = state.jiraUrl.isNotBlank(),
            bamboo = state.bambooUrl.isNotBlank(),
            sonar = state.sonarUrl.isNotBlank(),
            bitbucket = state.bitbucketUrl.isNotBlank(),
        )
    }
}
