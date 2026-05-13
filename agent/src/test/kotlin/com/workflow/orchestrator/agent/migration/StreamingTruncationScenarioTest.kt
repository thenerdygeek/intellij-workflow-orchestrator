package com.workflow.orchestrator.agent.migration

import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.ToolUseContent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StreamingTruncationScenarioTest {

    private val knownTools = setOf("read_file", "edit_file")
    private val knownParams = setOf("path", "old_string", "new_string", "content")

    @Test
    fun `S2 — stream split across chunks parses correctly when re-parsed on accumulated text`() {
        val chunk1 = "<read_file>\n<pa"
        val chunk2 = "th>x.kt</path>\n</read_file>"
        // Stateless re-parse pattern: parse the accumulated buffer at each chunk boundary
        val partial1 = AssistantMessageParser.parse(chunk1, knownTools, knownParams)
        val tu1 = partial1.filterIsInstance<ToolUseContent>().single()
        assertTrue(tu1.partial, "Mid-stream parse should mark tool as partial")
        val full = AssistantMessageParser.parse(chunk1 + chunk2, knownTools, knownParams)
        val tu2 = full.filterIsInstance<ToolUseContent>().single()
        assertFalse(tu2.partial)
        assertEquals("x.kt", tu2.params["path"])
    }

    @Test
    fun `S3 — truncated mid-XML (no closing tag) marked partial`() {
        val raw = "<read_file>\n<path>src/Foo.kt</path>"  // missing </read_file>
        val parsed = AssistantMessageParser.parse(raw, knownTools, knownParams)
        val tu = parsed.filterIsInstance<ToolUseContent>().single()
        assertTrue(tu.partial)
        assertEquals("src/Foo.kt", tu.params["path"])
    }

    @Test
    fun `S4 — truncated mid-param marked partial`() {
        val raw = "<read_file>\n<path>src/Fo"  // truncated inside param value
        val parsed = AssistantMessageParser.parse(raw, knownTools, knownParams)
        val tu = parsed.filterIsInstance<ToolUseContent>().single()
        assertTrue(tu.partial)
    }
}
