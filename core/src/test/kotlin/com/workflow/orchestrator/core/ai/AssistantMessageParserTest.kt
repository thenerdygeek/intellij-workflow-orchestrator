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

    @Test
    fun `multiple edit_file calls with new_string are not merged`() {
        val text = """I'll fix both files.

<edit_file>
<path>A.kt</path>
<new_string>fun a() = 1</new_string>
</edit_file>

Now the second file.

<edit_file>
<path>B.kt</path>
<new_string>fun b() = 2</new_string>
</edit_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)

        // Must produce 4 blocks: text, tool, text, tool — NOT 2 (with merged tools)
        assertEquals(4, blocks.size)

        val tool1 = blocks[1] as ToolUseContent
        assertEquals("edit_file", tool1.name)
        assertEquals("A.kt", tool1.params["path"])
        assertEquals("fun a() = 1", tool1.params["new_string"])
        assertFalse(tool1.partial)

        val tool2 = blocks[3] as ToolUseContent
        assertEquals("edit_file", tool2.name)
        assertEquals("B.kt", tool2.params["path"])
        assertEquals("fun b() = 2", tool2.params["new_string"])
        assertFalse(tool2.partial)
    }

    @Test
    fun `multiple create_file calls with content param are not merged`() {
        val text = """<create_file>
<path>A.html</path>
<content><html><body>Page A</body></html></content>
</create_file>

<create_file>
<path>B.html</path>
<content><html><body>Page B</body></html></content>
</create_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)

        val tools = blocks.filterIsInstance<ToolUseContent>()
        assertEquals(2, tools.size)
        assertEquals("A.html", tools[0].params["path"])
        assertTrue(tools[0].params["content"]!!.contains("Page A"))
        assertFalse(tools[0].params["content"]!!.contains("Page B"), "First tool should NOT contain second tool's content")
        assertEquals("B.html", tools[1].params["path"])
        assertTrue(tools[1].params["content"]!!.contains("Page B"))
    }

    // ── Real-world agent scenarios ─────────────────────────────────

    @Test
    fun `edit_file with old_string and new_string followed by read_file`() {
        val text = """<edit_file>
<path>Foo.kt</path>
<old_string>fun old() = 1</old_string>
<new_string>fun new() = 2</new_string>
</edit_file>

<read_file>
<path>Bar.kt</path>
</read_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        val tools = blocks.filterIsInstance<ToolUseContent>()
        assertEquals(2, tools.size)
        assertEquals("edit_file", tools[0].name)
        assertEquals("fun old() = 1", tools[0].params["old_string"])
        assertEquals("fun new() = 2", tools[0].params["new_string"])
        assertEquals("read_file", tools[1].name)
        assertEquals("Bar.kt", tools[1].params["path"])
    }

    @Test
    fun `three consecutive edit_file calls with code-carrying params`() {
        val text = """<edit_file>
<path>A.kt</path>
<old_string>val x = 1</old_string>
<new_string>val x = 10</new_string>
</edit_file>

<edit_file>
<path>B.kt</path>
<old_string>val y = 2</old_string>
<new_string>val y = 20</new_string>
</edit_file>

<edit_file>
<path>C.kt</path>
<old_string>val z = 3</old_string>
<new_string>val z = 30</new_string>
</edit_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        val tools = blocks.filterIsInstance<ToolUseContent>()
        assertEquals(3, tools.size)
        assertEquals("A.kt", tools[0].params["path"])
        assertEquals("val x = 10", tools[0].params["new_string"])
        assertEquals("B.kt", tools[1].params["path"])
        assertEquals("val y = 20", tools[1].params["new_string"])
        assertEquals("C.kt", tools[2].params["path"])
        assertEquals("val z = 30", tools[2].params["new_string"])
    }

    @Test
    fun `create_file with HTML content does not eat next tool`() {
        val text = """<create_file>
<path>page.html</path>
<content><!DOCTYPE html>
<html>
<head><title>Test</title></head>
<body>
<div class="container">
  <p>Hello world</p>
</div>
</body>
</html></content>
</create_file>

<run_command>
<command>open page.html</command>
</run_command>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        val tools = blocks.filterIsInstance<ToolUseContent>()
        assertEquals(2, tools.size)
        assertEquals("create_file", tools[0].name)
        assertTrue(tools[0].params["content"]!!.contains("<div"))
        assertTrue(tools[0].params["content"]!!.contains("</html>"))
        assertEquals("run_command", tools[1].name)
        assertEquals("open page.html", tools[1].params["command"])
    }

    @Test
    fun `mixed tool types in realistic agent response`() {
        val text = """Let me investigate and fix the issue.

<read_file>
<path>src/Main.kt</path>
</read_file>

I see the bug. The function returns null instead of empty list.

<edit_file>
<path>src/Main.kt</path>
<old_string>return null</old_string>
<new_string>return emptyList()</new_string>
</edit_file>

Now let me run the tests to verify.

<run_command>
<command>./gradlew test</command>
</run_command>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)

        assertEquals(6, blocks.size)
        assertTrue(blocks[0] is TextContent)
        assertTrue((blocks[0] as TextContent).content.contains("investigate"))

        val tool1 = blocks[1] as ToolUseContent
        assertEquals("read_file", tool1.name)

        assertTrue(blocks[2] is TextContent)
        assertTrue((blocks[2] as TextContent).content.contains("bug"))

        val tool2 = blocks[3] as ToolUseContent
        assertEquals("edit_file", tool2.name)
        assertEquals("return null", tool2.params["old_string"])
        assertEquals("return emptyList()", tool2.params["new_string"])

        assertTrue(blocks[4] is TextContent)
        assertTrue((blocks[4] as TextContent).content.contains("tests"))

        val tool3 = blocks[5] as ToolUseContent
        assertEquals("run_command", tool3.name)
        assertEquals("./gradlew test", tool3.params["command"])
    }

    @Test
    fun `edit_file with code containing closing tag lookalikes`() {
        // Code contains </new_string> as a string literal — parser must not be fooled
        val text = """<edit_file>
<path>Parser.kt</path>
<new_string>val closeTag = "</new_string>"
println(closeTag)</new_string>
</edit_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        val tools = blocks.filterIsInstance<ToolUseContent>()
        assertEquals(1, tools.size)
        assertTrue(tools[0].params["new_string"]!!.contains("closeTag"))
    }

    @Test
    fun `partial second tool call during streaming`() {
        // Stream mid-way: first tool done, second still streaming
        val text = """<read_file>
<path>A.kt</path>
</read_file>

<edit_file>
<path>B.kt</path>
<new_string>fun bar() {
    // still typing"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)

        val tools = blocks.filterIsInstance<ToolUseContent>()
        assertEquals(2, tools.size)
        assertFalse(tools[0].partial, "First tool should be complete")
        assertTrue(tools[1].partial, "Second tool should be partial")
        assertEquals("B.kt", tools[1].params["path"])
        assertTrue(tools[1].params["new_string"]!!.contains("still typing"))
    }

    @Test
    fun `five read_file calls in parallel (no code-carrying params)`() {
        val text = (1..5).joinToString("\n\n") { i ->
            "<read_file>\n<path>file$i.kt</path>\n</read_file>"
        }

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        val tools = blocks.filterIsInstance<ToolUseContent>()
        assertEquals(5, tools.size)
        tools.forEachIndexed { i, tool ->
            assertEquals("file${i + 1}.kt", tool.params["path"])
            assertFalse(tool.partial)
        }
    }

    @Test
    fun `edit_file where new_string contains tool-like XML tags`() {
        // The code being inserted contains XML that looks like tool tags
        val text = """<edit_file>
<path>Template.kt</path>
<new_string>val template = ""${'"'}
<read_file>
<path>example.txt</path>
</read_file>
""${'"'}.trimIndent()</new_string>
</edit_file>

<read_file>
<path>other.kt</path>
</read_file>"""

        val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
        val tools = blocks.filterIsInstance<ToolUseContent>()
        assertEquals(2, tools.size)
        assertEquals("edit_file", tools[0].name)
        assertTrue(tools[0].params["new_string"]!!.contains("<read_file>"))
        assertEquals("read_file", tools[1].name)
        assertEquals("other.kt", tools[1].params["path"])
    }
}
