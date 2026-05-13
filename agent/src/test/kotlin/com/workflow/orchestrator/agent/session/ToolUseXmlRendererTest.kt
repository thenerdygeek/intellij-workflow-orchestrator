package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolUseXmlRendererTest {

    @Test
    fun `renders simple tool call with string params`() {
        val tu = ContentBlock.ToolUse(
            id = "toolu_01",
            name = "read_file",
            input = """{"path":"src/main/kotlin/Foo.kt"}"""
        )
        val xml = ToolUseXmlRenderer.render(tu)
        assertEquals(
            "<read_file>\n<path>src/main/kotlin/Foo.kt</path>\n</read_file>",
            xml
        )
    }

    @Test
    fun `renders multi-param tool call preserving order`() {
        val tu = ContentBlock.ToolUse(
            id = "toolu_02",
            name = "edit_file",
            input = """{"path":"src/Foo.kt","old_string":"foo","new_string":"bar"}"""
        )
        val xml = ToolUseXmlRenderer.render(tu)
        // Param order follows JSON object iteration order (insertion in kotlinx.serialization).
        assertEquals(
            """<edit_file>
<path>src/Foo.kt</path>
<old_string>foo</old_string>
<new_string>bar</new_string>
</edit_file>""",
            xml
        )
    }

    @Test
    fun `renders param values with embedded newlines verbatim`() {
        val tu = ContentBlock.ToolUse(
            id = "toolu_03",
            name = "create_file",
            input = """{"path":"a.txt","content":"line1\nline2\nline3"}"""
        )
        val xml = ToolUseXmlRenderer.render(tu)
        assertEquals(
            """<create_file>
<path>a.txt</path>
<content>line1
line2
line3</content>
</create_file>""",
            xml
        )
    }

    @Test
    fun `renders nested JSON object param as raw JSON string`() {
        // Edge case: some tools accept JSON-shaped params (env on run_command).
        // We emit them as the JSON string representation — the parser's
        // CODE_CARRYING_PARAMS set tolerates this for `content`, `diff`, etc.,
        // but for non-code params it's still well-formed enough to read back.
        val tu = ContentBlock.ToolUse(
            id = "toolu_04",
            name = "run_command",
            input = """{"command":"ls","env":{"FOO":"bar","BAZ":"qux"}}"""
        )
        val xml = ToolUseXmlRenderer.render(tu)
        assertEquals(
            """<run_command>
<command>ls</command>
<env>{"FOO":"bar","BAZ":"qux"}</env>
</run_command>""",
            xml
        )
    }

    @Test
    fun `handles empty input gracefully`() {
        val tu = ContentBlock.ToolUse(id = "toolu_05", name = "think", input = "{}")
        val xml = ToolUseXmlRenderer.render(tu)
        assertEquals("<think>\n</think>", xml)
    }

    @Test
    fun `handles malformed input by emitting empty body`() {
        // Defensive: if a legacy session has corrupt JSON, don't throw.
        val tu = ContentBlock.ToolUse(id = "toolu_06", name = "read_file", input = "not json")
        val xml = ToolUseXmlRenderer.render(tu)
        assertEquals("<read_file>\n</read_file>", xml)
    }
}
