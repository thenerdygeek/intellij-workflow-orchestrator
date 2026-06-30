package com.workflow.orchestrator.core.config

import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings

/**
 * Base configuration: reads company-variable values from the IDE's [ConnectionSettings]. Sits at the
 * lowest priority ([order] = [Int.MAX_VALUE]) so any fork-registered [WorkflowConfig] wins.
 *
 * The state source is injectable for tests; the `@JvmOverloads` no-arg constructor is what the
 * platform instantiates for the EP (a bare Kotlin defaulted-param ctor exposes no no-arg JVM ctor —
 * see the `@Service`/EP constructor trap in project memory).
 */
class DefaultWorkflowConfig @JvmOverloads constructor(
    private val stateProvider: () -> ConnectionSettings.State = { ConnectionSettings.getInstance().state },
) : WorkflowConfig {
    override val order: Int get() = Int.MAX_VALUE

    override fun baseUrl(service: ServiceType): String {
        val state = stateProvider()
        return when (service) {
            ServiceType.JIRA -> state.jiraUrl
            ServiceType.BAMBOO -> state.bambooUrl
            ServiceType.BITBUCKET -> state.bitbucketUrl
            ServiceType.SONARQUBE -> state.sonarUrl
            ServiceType.SOURCEGRAPH -> state.sourcegraphUrl
            // WEB_SEARCH stores a provider-agnostic API key with no canonical base URL.
            ServiceType.WEB_SEARCH -> ""
            ServiceType.ANTHROPIC -> state.anthropicApiUrl
        }
    }
}
