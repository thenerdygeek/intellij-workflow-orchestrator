package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.agent.settings.AgentSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonitorSettingsDefaultsTest {
    @Test
    fun `monitor defaults match the design spec`() {
        val s = AgentSettings.State()
        assertEquals(2_000L, s.monitorCoalesceWindowMs)
        assertEquals(3, s.monitorWakeBudgetPerMonitor)
        assertEquals(20, s.monitorFloodThresholdPerMin)
    }
}
