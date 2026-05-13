package com.workflow.orchestrator.agent.migration

import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.assistantTurn
import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.renderForNextTurn
import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.toolResultTurn
import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.userTurn
import com.workflow.orchestrator.agent.session.DialectDriftDetector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FormatReinforcementScenarioTest {

    @Test
    fun `E1 — five turns of canonical XML history all preserved verbatim in next request`() {
        val history = buildList {
            add(userTurn("start"))
            for (i in 1..5) {
                add(assistantTurn("Turn $i.\n<read_file>\n<path>file$i.kt</path>\n</read_file>"))
                add(toolResultTurn(toolUseId = "toolu_$i", content = "contents$i"))
            }
            add(userTurn("now do Y"))
        }
        val wire = renderForNextTurn(history)
        // Every assistant turn should still contain its XML inline (format reinforcement loop)
        val assistantMessages = wire.filter { it.role == "assistant" }
        assertEquals(5, assistantMessages.size)
        for ((i, msg) in assistantMessages.withIndex()) {
            assertTrue(msg.content!!.contains("<read_file>"), "Turn ${i+1} missing canonical XML")
            assertTrue(msg.content!!.contains("<path>file${i+1}.kt</path>"))
            assertNull(msg.toolCalls)
        }
    }

    @Test
    fun `E2 — drift redaction replaces dialect block, prose intact, format teaching reasserted`() {
        // Simulate one turn where the model drifted, run it through redaction,
        // then verify the redacted form is what would be persisted.
        val driftedRaw = "OK I'll read. <function_calls><invoke name=\"read_file\"><parameter name=\"path\">x</parameter></invoke></function_calls> Done."
        val redacted = DialectDriftDetector.redactDialectMarkers(driftedRaw)
        assertTrue(redacted.modified)
        assertTrue(redacted.text.contains("OK I'll read."))
        assertTrue(redacted.text.contains("Done."))
        assertFalse(redacted.text.contains("<function_calls>"))
        assertTrue(redacted.text.contains("[redacted"))
    }
}
