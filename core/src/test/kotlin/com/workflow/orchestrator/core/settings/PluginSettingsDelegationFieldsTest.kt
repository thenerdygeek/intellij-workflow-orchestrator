package com.workflow.orchestrator.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PluginSettingsDelegationFieldsTest {

    @Test
    fun `delegationIdleTimeoutMinutes defaults to 30`() {
        val s = PluginSettings.State()
        assertEquals(30, s.delegationIdleTimeoutMinutes)
    }

    @Test
    fun `delegationIdleTimeoutMinutes round-trips`() {
        val s = PluginSettings.State()
        s.delegationIdleTimeoutMinutes = 5
        assertEquals(5, s.delegationIdleTimeoutMinutes)
        s.delegationIdleTimeoutMinutes = 0
        assertEquals(0, s.delegationIdleTimeoutMinutes)
    }
}
