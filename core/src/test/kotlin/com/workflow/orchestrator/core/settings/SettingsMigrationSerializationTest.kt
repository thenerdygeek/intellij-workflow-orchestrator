package com.workflow.orchestrator.core.settings

import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsMigrationSerializationTest {
    @Test fun `fresh install round-trips at the neutral default (omitted from XML)`() {
        val state = PluginSettings.State() // v0 fresh
        SettingsMigration.migrate(state) // 0 -> 2, no seeding

        val element = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(element, PluginSettings.State::class.java)

        assertEquals(2, restored.settingsSchemaVersion) // sentinel serializes (differs from default 0)
        // defaultTargetBranch equals its default -> omitted from XML -> restored at the default.
        assertEquals(NEUTRAL_DEFAULT_TARGET_BRANCH, restored.defaultTargetBranch)
    }

    @Test fun `upgrader's seeded develop value serializes and survives a round-trip`() {
        val state = PluginSettings.State()
        state.settingsSchemaVersion = 1 // upgrader
        SettingsMigration.migrate(state) // seeds "develop"

        val element = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(element, PluginSettings.State::class.java)

        assertEquals(2, restored.settingsSchemaVersion)
        // "develop" now differs from the neutral default, so it IS written to XML and persists.
        assertEquals("develop", restored.defaultTargetBranch)
    }
}
