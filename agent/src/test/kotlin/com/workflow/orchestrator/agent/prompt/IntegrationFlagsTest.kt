package com.workflow.orchestrator.agent.prompt

import com.workflow.orchestrator.core.settings.ConnectionSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntegrationFlagsTest {
    @Test fun `NONE has nothing configured`() {
        assertFalse(IntegrationFlags.NONE.any)
        assertFalse(IntegrationFlags.NONE.jira)
    }

    @Test fun `ALL has everything configured`() {
        val a = IntegrationFlags.ALL
        assertTrue(a.jira && a.bamboo && a.sonar && a.bitbucket && a.any)
    }

    @Test fun `from resolves each flag off a blank vs non-blank service URL`() {
        val state = ConnectionSettings.State(
            jiraUrl = "https://jira.example.com",
            bambooUrl = "",
            sonarUrl = "https://sonar.example.com",
            bitbucketUrl = "   ",
        )
        val f = IntegrationFlags.from(state)
        assertTrue(f.jira)
        assertFalse(f.bamboo)
        assertTrue(f.sonar)
        assertFalse(f.bitbucket) // blank-but-whitespace counts as not configured
        assertEquals(IntegrationFlags(jira = true, sonar = true), f)
    }
}
