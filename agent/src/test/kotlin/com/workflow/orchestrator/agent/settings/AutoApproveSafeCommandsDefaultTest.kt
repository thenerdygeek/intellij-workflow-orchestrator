package com.workflow.orchestrator.agent.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AutoApproveSafeCommandsDefaultTest {
    @Test fun `defaults to false`() {
        assertFalse(AgentSettings.State().autoApproveSafeCommands)
    }
}
