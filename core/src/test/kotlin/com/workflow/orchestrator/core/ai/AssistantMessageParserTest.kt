package com.workflow.orchestrator.core.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AssistantMessageParserTest {

    private val toolNames = setOf("read_file", "edit_file", "create_file", "run_command",
        "search_code", "think", "attempt_completion", "ask_followup_question")
    private val paramNames = setOf("path", "content", "old_string", "new_string", "diff",
        "code", "command", "pattern", "question", "result", "thought", "working_dir",
        "regex", "file_pattern", "output_mode", "directory", "include", "options",
        "questions", "title", "description", "prompt")

    @Test
    fun `parses text-only response`() {
        val text = "The answer is 42."
        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val block = blocks[0] as TextContent
        assertEquals("The answer is 42.", block.content)
        assertTrue(block.partial, "Trailing text is always partial — parser can't know if stream is done")
    }

    @Test
    fun `parses single tool call`() {
        val text = """I'll read that file.

<read_file>
<path>src/Foo.kt</path>
</read_file>"""
        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)

        assertEquals(2, blocks.size)
        val textBlock = blocks[0] as TextContent
        assertTrue(textBlock.content.contains("I'll read that file."))

        val toolBlock = blocks[1] as ToolUseContent
        assertEquals("read_file", toolBlock.name)
        assertEquals("src/Foo.kt", toolBlock.params["path"])
        assertFalse(toolBlock.partial)
    }

    @Test
    fun `parses parallel tool calls with text between`() {
        val text = """<read_file>
<path>A.kt</path>
</read_file>

Now the second file.

<read_file>
<path>B.kt</path>
</read_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)

        assertEquals(3, blocks.size)
        val tool1 = blocks[0] as ToolUseContent
        assertEquals("A.kt", tool1.params["path"])

        val textBlock = blocks[1] as TextContent
        assertTrue(textBlock.content.contains("Now the second file."))

        val tool2 = blocks[2] as ToolUseContent
        assertEquals("B.kt", tool2.params["path"])
    }

    @Test
    fun `detects partial tool call (no closing tag)`() {
        val text = """<read_file>
<path>src/Foo.kt</path>"""
        // No closing </read_file> — still streaming

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertEquals("read_file", toolBlock.name)
        assertEquals("src/Foo.kt", toolBlock.params["path"])
        assertTrue(toolBlock.partial)
    }

    @Test
    fun `detects partial param (mid-value truncation)`() {
        val text = """<edit_file>
<path>Foo.kt</path>
<new_string>class Foo {
    fun bar"""
        // Truncated mid-value

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertTrue(toolBlock.partial)
        assertEquals("Foo.kt", toolBlock.params["path"])
        assertTrue(toolBlock.params["new_string"]!!.contains("class Foo"))
    }

    @Test
    fun `handles XML-like strings in code content using lastIndexOf`() {
        val text = """<create_file>
<path>index.html</path>
<content><html><body><div>Hello</div></body></html></content>
</create_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertFalse(toolBlock.partial)
        assertTrue(toolBlock.params["content"]!!.contains("</div>"))
    }

    @Test
    fun `does not mis-match path tag inside new_string`() {
        val text = """<edit_file>
<path>src/Foo.kt</path>
<new_string>// See <path>other/Bar.kt</path> for details
class Foo {
    fun bar() = 42
}</new_string>
</edit_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertEquals("src/Foo.kt", toolBlock.params["path"])
        assertTrue(toolBlock.params["new_string"]!!.contains("other/Bar.kt"))
        assertTrue(toolBlock.params["new_string"]!!.contains("class Foo"))
    }

    @Test
    fun `handles large content block with 200+ lines`() {
        val lines = (1..250).joinToString("\n") { "    val field$it = \"value$it\"" }
        val text = """<create_file>
<path>Generated.kt</path>
<content>package com.example

class Generated {
$lines
}</content>
</create_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertFalse(toolBlock.partial)
        assertTrue(toolBlock.params["content"]!!.contains("field1"))
        assertTrue(toolBlock.params["content"]!!.contains("field250"))
    }

    @Test
    fun `ignores unknown tags inside tool block`() {
        val text = """<read_file>
<path>Foo.kt</path>
<unknown_param>should be ignored</unknown_param>
</read_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertEquals("Foo.kt", toolBlock.params["path"])
        assertFalse(toolBlock.params.containsKey("unknown_param"))
    }

    @Test
    fun `does not treat unknown tag as tool call`() {
        val text = "Use the <div>element</div> for layout."

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val textBlock = blocks[0] as TextContent
        assertTrue(textBlock.content.contains("<div>element</div>"))
    }

    @Test
    fun `handles empty params (think tool)`() {
        val text = """<think>
<thought>The user wants a simple function.</thought>
</think>"""

        val blocks = AssistantMessageParser.parse(text, toolNames + "think", paramNames + "thought")
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertEquals("think", toolBlock.name)
        assertEquals("The user wants a simple function.", toolBlock.params["thought"])
    }

    @Test
    fun `text block is partial when at end of stream`() {
        val text = "I'm thinking about"
        // Stream not ended yet — this text is partial

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val textBlock = blocks[0] as TextContent
        assertTrue(textBlock.partial, "Text at end should be partial during streaming")
    }

    @Test
    fun `multi-param tool call preserves all params`() {
        val text = """<edit_file>
<path>Foo.kt</path>
<old_string>fun bar() = 41</old_string>
<new_string>fun bar() = 42</new_string>
</edit_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        assertEquals(1, blocks.size)
        val toolBlock = blocks[0] as ToolUseContent
        assertEquals("Foo.kt", toolBlock.params["path"])
        assertEquals("fun bar() = 41", toolBlock.params["old_string"])
        assertEquals("fun bar() = 42", toolBlock.params["new_string"])
    }
}
