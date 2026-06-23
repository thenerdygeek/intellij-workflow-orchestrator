package com.workflow.orchestrator.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsMigrationTest {
    @Test fun `v0 state is stamped to current version`() {
        val state = PluginSettings.State()
        assertEquals(0, state.settingsSchemaVersion)
        assertTrue(SettingsMigration.migrate(state))
        assertEquals(SettingsMigration.CURRENT_VERSION, state.settingsSchemaVersion)
    }

    @Test fun `already-current state is a no-op`() {
        val state = PluginSettings.State()
        state.settingsSchemaVersion = SettingsMigration.CURRENT_VERSION
        assertFalse(SettingsMigration.migrate(state))
    }
}
