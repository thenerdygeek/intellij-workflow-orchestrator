package com.workflow.orchestrator.agent.prompt

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SystemPromptIntegrationGatingTest {
    private fun prompt(flags: IntegrationFlags) = SystemPrompt.build(
        projectName = "P", projectPath = "/p", osName = "Linux", shell = "/bin/bash",
        homeDir = "/home", integrations = flags,
    )

    @Test fun `all-on prompt mentions every integration`() {
        val p = prompt(IntegrationFlags.ALL)
        assertTrue(p.contains("enterprise integrations (Jira, Bamboo, SonarQube, Bitbucket)"))
        assertTrue(p.contains("- Jira tickets: [PROJ-1234](jira:PROJ-1234)"))
        assertTrue(p.contains("jira, bamboo_builds, bamboo_plans, sonar, bitbucket_pr, bitbucket_repo, bitbucket_review"))
        assertTrue(p.contains("Sonar quality gate"))
        assertTrue(p.contains("sonar(action=\"local_analysis\""))
        assertTrue(p.contains("# Jira Transition — Field Collection Pattern"))
    }

    @Test fun `no-integration prompt omits all stack mentions`() {
        val p = prompt(IntegrationFlags.NONE)
        assertFalse(p.contains("enterprise integrations"))
        assertFalse(p.contains("(jira:"))                 // no jira: URL scheme
        assertFalse(p.contains("or Jira ticket"))         // outputFormatting lead-line softened
        assertFalse(p.contains("bamboo_builds"))
        assertFalse(p.contains("SonarQube"))
        assertFalse(p.contains("Sonar quality gate"))
        assertFalse(p.contains("# Jira Transition"))
        assertFalse(p.contains("active Jira ticket"))
    }

    @Test fun `jira-only prompt has Jira fragments but not bamboo or sonar`() {
        val p = prompt(IntegrationFlags(jira = true))
        assertTrue(p.contains("enterprise integrations (Jira)"))
        assertTrue(p.contains("- Jira tickets: [PROJ-1234](jira:PROJ-1234)"))
        assertTrue(p.contains("# Jira Transition — Field Collection Pattern"))
        assertTrue(p.contains("active Jira ticket"))
        assertFalse(p.contains("bamboo_builds"))
        assertFalse(p.contains("SonarQube"))
        assertFalse(p.contains("- **Project integrations** → jira, bamboo")) // list trimmed to "jira"
        assertTrue(p.contains("- **Project integrations** → jira\n"))
    }
}
