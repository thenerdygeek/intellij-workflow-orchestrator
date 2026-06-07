package com.workflow.orchestrator.core.config

import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DefaultWorkflowConfigTest {
    private val state = ConnectionSettings.State(
        jiraUrl = "https://jira.example",
        bambooUrl = "https://bamboo.example",
        bitbucketUrl = "https://bitbucket.example",
        sonarUrl = "https://sonar.example",
        sourcegraphUrl = "https://sg.example",
    )
    private val config = DefaultWorkflowConfig { state }

    @Test
    fun `maps each service to its configured base url`() {
        assertEquals("https://jira.example", config.baseUrl(ServiceType.JIRA))
        assertEquals("https://bamboo.example", config.baseUrl(ServiceType.BAMBOO))
        assertEquals("https://bitbucket.example", config.baseUrl(ServiceType.BITBUCKET))
        assertEquals("https://sonar.example", config.baseUrl(ServiceType.SONARQUBE))
        assertEquals("https://sg.example", config.baseUrl(ServiceType.SOURCEGRAPH))
    }

    @Test
    fun `web search has no canonical base url`() {
        assertEquals("", config.baseUrl(ServiceType.WEB_SEARCH))
    }

    @Test
    fun `default config is lowest priority so forks win`() {
        assertEquals(Int.MAX_VALUE, config.order)
    }
}
