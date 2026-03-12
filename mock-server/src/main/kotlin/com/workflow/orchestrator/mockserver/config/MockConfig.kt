package com.workflow.orchestrator.mockserver.config

data class MockConfig(
    val jiraPort: Int = System.getenv("MOCK_JIRA_PORT")?.toIntOrNull() ?: 8180,
    val bambooPort: Int = System.getenv("MOCK_BAMBOO_PORT")?.toIntOrNull() ?: 8280,
    val sonarPort: Int = System.getenv("MOCK_SONAR_PORT")?.toIntOrNull() ?: 8380,
)
