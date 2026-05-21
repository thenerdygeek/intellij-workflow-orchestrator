package com.workflow.orchestrator.agent.migration

import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.assistantTurn
import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.persistAssistantTurn
import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.renderForNextTurn
import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.userTurn
import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.ToolUseContent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CanonicalFormatScenarioTest {

    private val knownTools = setOf("read_file", "edit_file", "create_file")
    private val knownParams = setOf("path", "old_string", "new_string", "content")

    @Test
    fun `F1 C1 S1 M1 — single canonical XML tool call parses and persists inline`() {
        val raw = "I'll read it.\n<read_file>\n<path>src/Foo.kt</path>\n</read_file>"
        // Parser: extracts one ToolUseContent
        val parsed = AssistantMessageParser.parse(raw, knownTools, knownParams)
        val toolUses = parsed.filterIsInstance<ToolUseContent>()
        assertEquals(1, toolUses.size)
        assertEquals("read_file", toolUses[0].name)
        assertEquals("src/Foo.kt", toolUses[0].params["path"])
        assertFalse(toolUses[0].partial)
        // Persist: single Text block with full raw content
        val blocks = persistAssistantTurn(raw)
        assertEquals(1, blocks.size)
        assertEquals(raw, (blocks[0] as ContentBlock.Text).text)
    }

    @Test
    fun `F1 C1 S1 M2 — two canonical tool calls both parse`() {
        val raw = """Reading two files.
<read_file>
<path>a.kt</path>
</read_file>
<read_file>
<path>b.kt</path>
</read_file>"""
        val parsed = AssistantMessageParser.parse(raw, knownTools, knownParams)
        val toolUses = parsed.filterIsInstance<ToolUseContent>()
        assertEquals(2, toolUses.size)
        assertEquals("a.kt", toolUses[0].params["path"])
        assertEquals("b.kt", toolUses[1].params["path"])
    }

    @Test
    fun `F1 C1 S1 M3 — prose interleaved with tool calls preserves order`() {
        val raw = """First I'll read.
<read_file>
<path>a.kt</path>
</read_file>
Now editing.
<edit_file>
<path>a.kt</path>
<old_string>foo</old_string>
<new_string>bar</new_string>
</edit_file>"""
        val parsed = AssistantMessageParser.parse(raw, knownTools, knownParams)
        val toolUses = parsed.filterIsInstance<ToolUseContent>()
        assertEquals(2, toolUses.size)
        assertEquals("read_file", toolUses[0].name)
        assertEquals("edit_file", toolUses[1].name)
        assertEquals("foo", toolUses[1].params["old_string"])
        assertEquals("bar", toolUses[1].params["new_string"])
    }

    @Test
    fun `W3 — canonical XML survives round trip into next turn request body`() {
        val raw = "Reading.\n<read_file>\n<path>x.kt</path>\n</read_file>"
        val history = listOf(userTurn("read x.kt"), assistantTurn(raw))
        val wire = renderForNextTurn(history)
        // After sanitizer: user message + assistant message + Phase 6 synthetic user
        // tail. The assistant content contains the FULL raw XML, no separate
        // tool_calls field.
        val assistant = wire.last { it.role == "assistant" }
        assertNull(assistant.toolCalls)
        assertTrue(assistant.content!!.contains("<read_file>"))
        assertTrue(assistant.content!!.contains("<path>x.kt</path>"))
    }
}
