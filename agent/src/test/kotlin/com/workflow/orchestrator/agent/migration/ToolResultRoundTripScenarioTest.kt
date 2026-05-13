package com.workflow.orchestrator.agent.migration

import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.assistantTurn
import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.renderForNextTurn
import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.toolResultTurn
import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.userTurn
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolResultRoundTripScenarioTest {

    @Test
    fun `R1 W4 W5 — tool result becomes role user with TOOL RESULT prefix`() {
        val history = listOf(
            userTurn("read x"),
            assistantTurn("<read_file>\n<path>x</path>\n</read_file>"),
            toolResultTurn(toolUseId = "toolu_1", content = "file contents here")
        )
        val wire = renderForNextTurn(history)
        // After sanitizer: the tool result should be a `role: user` message
        // with "TOOL RESULT:\n" prefix. NO tool_call_id on the wire.
        val toolResultMsg = wire.last()
        assertEquals("user", toolResultMsg.role)
        assertTrue(toolResultMsg.content!!.startsWith("TOOL RESULT:"))
        assertTrue(toolResultMsg.content!!.contains("file contents here"))
        assertNull(toolResultMsg.toolCallId)
    }

    @Test
    fun `R1 — consecutive user-turn coercion after tool result merges into prior user message`() {
        val history = listOf(
            userTurn("read x"),
            assistantTurn("<read_file>\n<path>x</path>\n</read_file>"),
            toolResultTurn(toolUseId = "toolu_1", content = "contents")
        )
        val wire = renderForNextTurn(history)
        // Sanitizer merges consecutive same-role messages → if there's a user
        // message right after the (coerced) tool result, they collapse.
        // In this scenario there is none, so we have user/assistant/user (3 messages).
        assertEquals(3, wire.size)
        assertEquals("user", wire[0].role)
        assertEquals("assistant", wire[1].role)
        assertEquals("user", wire[2].role)
    }
}
