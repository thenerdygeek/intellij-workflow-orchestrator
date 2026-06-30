package com.workflow.orchestrator.companyb

import com.workflow.orchestrator.core.config.WorkflowConfig
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings

/** order=0 beats DefaultWorkflowConfig (Int.MAX_VALUE) via WorkflowConfig.resolve(). For 0a it
 *  delegates to configured URLs (identical behavior); its presence is the override-mechanism proof. */
class CompanyBWorkflowConfig : WorkflowConfig {
    override val order: Int get() = 0
    override fun baseUrl(service: ServiceType): String {
        val state = ConnectionSettings.getInstance().state
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
