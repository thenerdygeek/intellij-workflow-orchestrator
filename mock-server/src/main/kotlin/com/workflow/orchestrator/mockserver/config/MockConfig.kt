package com.workflow.orchestrator.mockserver.config

data class MockConfig(
    val jiraPort: Int = System.getenv("MOCK_JIRA_PORT")?.toIntOrNull() ?: 8180,
    val bambooPort: Int = System.getenv("MOCK_BAMBOO_PORT")?.toIntOrNull() ?: 8280,
    val sonarPort: Int = System.getenv("MOCK_SONAR_PORT")?.toIntOrNull() ?: 8380,
    val bitbucketPort: Int = System.getenv("MOCK_BITBUCKET_PORT")?.toIntOrNull() ?: 8480,
    val sourcegraphPort: Int = System.getenv("MOCK_SOURCEGRAPH_PORT")?.toIntOrNull() ?: 8088,
    val sourcegraphDefaultScenario: String = System.getenv("MOCK_SOURCEGRAPH_SCENARIO") ?: "read-and-finish",
)
