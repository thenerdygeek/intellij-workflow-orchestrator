package com.workflow.orchestrator.agent.listeners

import com.intellij.openapi.startup.ProjectActivity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentStartupActivityTest {

    @Test
    fun `AgentStartupActivity implements ProjectActivity`() {
        val activity = AgentStartupActivity()
        assertInstanceOf(ProjectActivity::class.java, activity)
    }
}
