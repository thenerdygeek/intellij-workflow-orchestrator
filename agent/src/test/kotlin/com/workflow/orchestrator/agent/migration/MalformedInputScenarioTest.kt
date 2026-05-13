package com.workflow.orchestrator.agent.migration

import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.TextContent
import com.workflow.orchestrator.core.ai.ToolUseContent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MalformedInputScenarioTest {

    private val knownTools = setOf("read_file", "edit_file", "create_file")
    private val knownParams = setOf("path", "old_string", "new_string", "content")

    @Test
    fun `C2 — tool call missing required param still parses but with empty value`() {
        // The parser doesn't validate required params; that's the tool's job at dispatch.
        // Verify the parser gracefully produces a ToolUseContent with whatever params are present.
        val raw = "<read_file></read_file>"
        val parsed = AssistantMessageParser.parse(raw, knownTools, knownParams)
        val tu = parsed.filterIsInstance<ToolUseContent>().single()
        assertEquals("read_file", tu.name)
        assertTrue(tu.params.isEmpty())
        assertFalse(tu.partial)
    }

    @Test
    fun `C3 — unknown extra param is collected if its name matches knownParams`() {
        // If extra param name is in knownParams set (e.g., model passes <content> to read_file
        // by mistake), it parses. If not in knownParams, it's ignored.
        val raw = "<read_file>\n<path>x</path>\n<old_string>extra</old_string>\n</read_file>"
        val parsed = AssistantMessageParser.parse(raw, knownTools, knownParams)
        val tu = parsed.filterIsInstance<ToolUseContent>().single()
        assertEquals("x", tu.params["path"])
        assertEquals("extra", tu.params["old_string"])
    }

    @Test
    fun `C4 — unknown param name falls through as text inside tool block`() {
        // <paht> is not in knownParams. The parser doesn't recognize it,
        // so the inner text is captured into whatever surrounding state holds.
        // Acceptance: the tool block still parses with whatever params it CAN identify.
        val raw = "<read_file>\n<paht>x</paht>\n</read_file>"
        val parsed = AssistantMessageParser.parse(raw, knownTools, knownParams)
        val tu = parsed.filterIsInstance<ToolUseContent>().single()
        assertEquals("read_file", tu.name)
        // <paht> not recognized → not in params map
        assertFalse(tu.params.containsKey("path"))
        assertFalse(tu.params.containsKey("paht"))
    }

    @Test
    fun `C5 — tool name with hyphen treated as prose (tool names use underscore)`() {
        // <read-file> is not a registered tool name; it becomes prose.
        val raw = "<read-file><path>x</path></read-file>"
        val parsed = AssistantMessageParser.parse(raw, knownTools, knownParams)
        // No ToolUseContent extracted
        assertTrue(parsed.filterIsInstance<ToolUseContent>().isEmpty())
        // Text content preserved verbatim
        val text = parsed.filterIsInstance<TextContent>().joinToString("\n") { it.content }
        assertTrue(text.contains("<read-file>"))
    }

    @Test
    fun `C6 — unknown tool name treated as prose, no tool dispatched`() {
        val raw = "<read_filezzz><path>x</path></read_filezzz>"
        val parsed = AssistantMessageParser.parse(raw, knownTools, knownParams)
        assertTrue(parsed.filterIsInstance<ToolUseContent>().isEmpty())
    }
}
