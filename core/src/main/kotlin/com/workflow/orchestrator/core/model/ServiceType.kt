package com.workflow.orchestrator.core.model

enum class ServiceType(val displayName: String) {
    JIRA("Jira"),
    BAMBOO("Bamboo"),
    BITBUCKET("Bitbucket"),
    SONARQUBE("SonarQube"),
    SOURCEGRAPH("Sourcegraph"),
    NEXUS("Nexus Docker Registry");
}
