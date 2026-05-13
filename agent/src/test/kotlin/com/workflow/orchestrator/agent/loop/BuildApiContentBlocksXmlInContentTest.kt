package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.FunctionCall
import com.workflow.orchestrator.core.ai.dto.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildApiContentBlocksXmlInContentTest {

    // Mirror the private fun's expected behavior via a public test helper
    // exposed by AgentLoopTestSupport (or via reflection if no support seam).

    @Test
    fun `assistant response with XML in content persists as single Text block`() {
        val msg = ChatMessage(
            role = "assistant",
            content = "I'll read the file.\n\n<read_file>\n<path>src/Foo.kt</path>\n</read_file>",
            toolCalls = listOf(  // parser also produced these for dispatch
                ToolCall(
                    id = "xmltool_1",
                    function = FunctionCall(name = "read_file", arguments = """{"path":"src/Foo.kt"}""")
                )
            )
        )
        val blocks = AgentLoopTestSupport.buildApiContentBlocks(msg)
        // Single Text block with the FULL raw content (XML inline). No
        // ContentBlock.ToolUse emitted — that path is dead.
        assertEquals(1, blocks.size)
        assert(blocks[0] is ContentBlock.Text)
        val text = (blocks[0] as ContentBlock.Text).text
        assertEquals(
            "I'll read the file.\n\n<read_file>\n<path>src/Foo.kt</path>\n</read_file>",
            text
        )
    }

    @Test
    fun `assistant response with no tool calls still persists content`() {
        val msg = ChatMessage(role = "assistant", content = "Just thinking out loud.", toolCalls = null)
        val blocks = AgentLoopTestSupport.buildApiContentBlocks(msg)
        assertEquals(1, blocks.size)
        assertEquals("Just thinking out loud.", (blocks[0] as ContentBlock.Text).text)
    }

    @Test
    fun `empty content falls back to empty Text block`() {
        val msg = ChatMessage(role = "assistant", content = null, toolCalls = null)
        val blocks = AgentLoopTestSupport.buildApiContentBlocks(msg)
        assertEquals(1, blocks.size)
        assertEquals("", (blocks[0] as ContentBlock.Text).text)
    }
}
