package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ApiMessageInlineXmlTest {

    @Test
    fun `assistant turn with tool use renders XML inline in text content`() {
        val msg = ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(
                ContentBlock.Text("I'll read the file."),
                ContentBlock.ToolUse(
                    id = "toolu_01",
                    name = "read_file",
                    input = """{"path":"src/Foo.kt"}"""
                )
            )
        )
        val chat = msg.toChatMessage()
        assertEquals("assistant", chat.role)
        assertNull(chat.toolCalls, "toolCalls field must be null after migration")
        assertEquals(
            """I'll read the file.

<read_file>
<path>src/Foo.kt</path>
</read_file>""",
            chat.content
        )
    }

    @Test
    fun `assistant turn with only tool use produces XML-only content`() {
        val msg = ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(
                ContentBlock.ToolUse(
                    id = "toolu_02",
                    name = "edit_file",
                    input = """{"path":"x.kt","old_string":"a","new_string":"b"}"""
                )
            )
        )
        val chat = msg.toChatMessage()
        assertNull(chat.toolCalls)
        assertEquals(
            """<edit_file>
<path>x.kt</path>
<old_string>a</old_string>
<new_string>b</new_string>
</edit_file>""",
            chat.content
        )
    }

    @Test
    fun `multiple tool use blocks render in declaration order`() {
        val msg = ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(
                ContentBlock.Text("Reading two files."),
                ContentBlock.ToolUse(id = "t1", name = "read_file", input = """{"path":"a.kt"}"""),
                ContentBlock.ToolUse(id = "t2", name = "read_file", input = """{"path":"b.kt"}""")
            )
        )
        val chat = msg.toChatMessage()
        assertEquals(
            """Reading two files.

<read_file>
<path>a.kt</path>
</read_file>

<read_file>
<path>b.kt</path>
</read_file>""",
            chat.content
        )
    }

    @Test
    fun `tool result still maps to role tool for sanitizer compat`() {
        val msg = ApiMessage(
            role = ApiRole.USER,
            content = listOf(
                ContentBlock.ToolResult(
                    toolUseId = "toolu_01",
                    content = "file contents here"
                )
            )
        )
        val chat = msg.toChatMessage()
        // Tool result keeps role="tool" so MessageSanitizer's tool→user coercion
        // (with "TOOL RESULT:\n" prefix) still applies uniformly.
        assertEquals("tool", chat.role)
        assertEquals("file contents here", chat.content)
        assertEquals("toolu_01", chat.toolCallId)
    }
}
