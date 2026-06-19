package com.workflow.orchestrator.agent.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentSettingsApiDebugDumpTest {

    @Test
    fun `writeApiDebugDumps defaults to false`() {
        // The api-debug request/response dumps (sessions/{id}/api-debug/call-NNN-*.txt) are a
        // full copy of every LLM request body written to disk on every call. Most users never
        // open the API Debug viewer, and on antivirus-scanned / network-synced filesystems each
        // write is a real cost — so the feature must default OFF.
        assertFalse(AgentSettings.State().writeApiDebugDumps)
    }

    @Test
    fun `writeApiDebugDumps round-trips when enabled`() {
        val s = AgentSettings.State().apply { writeApiDebugDumps = true }
        assertTrue(s.writeApiDebugDumps)
    }
}
