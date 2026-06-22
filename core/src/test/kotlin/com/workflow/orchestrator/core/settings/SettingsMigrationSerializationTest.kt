package com.workflow.orchestrator.core.settings

import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsMigrationSerializationTest {
    @Test fun `stamped sentinel survives serialize-deserialize, default field stays at default`() {
        val state = PluginSettings.State()
        SettingsMigration.migrate(state) // settingsSchemaVersion 0 -> 1 (a real change -> serializes)

        val element = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(element, PluginSettings.State::class.java)

        assertEquals(1, restored.settingsSchemaVersion) // the sentinel round-trips
        // defaultTargetBranch was NOT touched and equals its default, so it was omitted from XML
        // and the restored state shows the (unchanged) default — documenting why materialization
        // is impossible here and is deferred to Phase 1:
        assertEquals("develop", restored.defaultTargetBranch)
    }
}
