package com.workflow.orchestrator.core.model

enum class ServiceType(val displayName: String) {
    JIRA("Jira"),
    BAMBOO("Bamboo"),
    BITBUCKET("Bitbucket"),
    SONARQUBE("SonarQube"),
    SOURCEGRAPH("Cody Enterprise"),
    NEXUS("Nexus Docker Registry");
}
