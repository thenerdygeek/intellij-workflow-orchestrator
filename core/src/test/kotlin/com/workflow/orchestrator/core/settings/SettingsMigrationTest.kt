package com.workflow.orchestrator.core.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsMigrationTest {
    @Test fun `v0 fresh install is stamped to current version and keeps the neutral default`() {
        val state = PluginSettings.State()
        assertEquals(0, state.settingsSchemaVersion)
        assertEquals(NEUTRAL_DEFAULT_TARGET_BRANCH, state.defaultTargetBranch)
        assertTrue(SettingsMigration.migrate(state))
        assertEquals(SettingsMigration.CURRENT_VERSION, state.settingsSchemaVersion)
        // Fresh install: NOT an upgrader -> no legacy seeding.
        assertEquals(NEUTRAL_DEFAULT_TARGET_BRANCH, state.defaultTargetBranch)
    }

    @Test fun `v1 upgrader on the neutral default is seeded the legacy develop value and stamped v2`() {
        val state = PluginSettings.State()
        state.settingsSchemaVersion = 1 // existed before the blanking (0a-stamped)
        // The field is at the new neutral default == it was omitted from the upgrader's XML.
        assertEquals(NEUTRAL_DEFAULT_TARGET_BRANCH, state.defaultTargetBranch)
        assertTrue(SettingsMigration.migrate(state))
        assertEquals("develop", state.defaultTargetBranch) // behavior preserved
        assertEquals(2, state.settingsSchemaVersion)
    }

    @Test fun `v1 upgrader who explicitly chose a non-neutral branch is left untouched`() {
        val state = PluginSettings.State()
        state.settingsSchemaVersion = 1
        state.defaultTargetBranch = "trunk" // explicit value (present in XML)
        assertTrue(SettingsMigration.migrate(state))
        assertEquals("trunk", state.defaultTargetBranch) // preserved, not overwritten
        assertEquals(2, state.settingsSchemaVersion)
    }

    @Test fun `already-current v2 state is a no-op`() {
        val state = PluginSettings.State()
        state.settingsSchemaVersion = SettingsMigration.CURRENT_VERSION
        assertFalse(SettingsMigration.migrate(state))
    }
}
