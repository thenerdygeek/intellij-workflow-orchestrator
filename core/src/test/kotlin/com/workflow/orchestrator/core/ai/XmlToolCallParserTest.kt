package com.workflow.orchestrator.core.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class XmlToolCallParserTest {

    @Test
    fun `parses single tool call`() {
        val text = """I'll read that file for you.

<tool>
  <name>read_file</name>
  <args>
    <path>src/main/kotlin/Foo.kt</path>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        assertFalse(result.hasPartial)
        assertEquals("read_file", result.toolCalls[0].function.name)
        assertTrue(result.toolCalls[0].function.arguments.contains("src/main/kotlin/Foo.kt"))
    }

    @Test
    fun `parses parallel tool calls`() {
        val text = """Let me read both files.

<tool>
  <name>read_file</name>
  <args>
    <path>A.kt</path>
  </args>
</tool>

<tool>
  <name>read_file</name>
  <args>
    <path>B.kt</path>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(2, result.toolCalls.size)
        assertFalse(result.hasPartial)
        assertTrue(result.toolCalls[0].function.arguments.contains("A.kt"))
        assertTrue(result.toolCalls[1].function.arguments.contains("B.kt"))
    }

    @Test
    fun `detects partial tool call on truncation`() {
        val text = """<tool>
  <name>search_code</name>
  <args>
    <pattern>suspend fun"""
        // Truncated mid-arg — no closing tags

        val result = XmlToolCallParser.parse(text)

        assertTrue(result.hasPartial)
        assertEquals(0, result.toolCalls.size) // Partial not included in completed
    }

    @Test
    fun `extracts text content before tool calls`() {
        val text = """I found the issue. Let me fix it.

<tool>
  <name>edit_file</name>
  <args>
    <path>Foo.kt</path>
    <content>class Foo { fun bar() = 42 }</content>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals("I found the issue. Let me fix it.", result.textContent.trim())
        assertEquals(1, result.toolCalls.size)
    }

    @Test
    fun `handles content with XML-like strings using lastIndexOf`() {
        // Code containing </div> should not break the parser
        val text = """<tool>
  <name>create_file</name>
  <args>
    <path>index.html</path>
    <content><html><body><div>Hello</div></body></html></content>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        assertFalse(result.hasPartial)
        val args = result.toolCalls[0].function.arguments
        assertTrue(args.contains("</div>"), "Should preserve XML-like content in args")
    }

    @Test
    fun `returns empty for text-only response`() {
        val text = "The answer is 42. No tools needed."

        val result = XmlToolCallParser.parse(text)

        assertEquals(0, result.toolCalls.size)
        assertFalse(result.hasPartial)
        assertEquals(text, result.textContent)
    }

    @Test
    fun `handles multiline arg values`() {
        val text = """<tool>
  <name>edit_file</name>
  <args>
    <path>Foo.kt</path>
    <content>package com.example

class Foo {
    fun bar(): Int {
        return 42
    }
}</content>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        val args = result.toolCalls[0].function.arguments
        assertTrue(args.contains("class Foo"))
        assertTrue(args.contains("return 42"))
    }

    @Test
    fun `does not mis-match path tag inside content block`() {
        // CRITICAL: <path> inside <content> must not confuse the parser.
        // Before the fix, lastIndexOf("</path>") found the one inside content,
        // causing <path> to swallow everything up to and including the code reference.
        val text = """<tool>
  <name>edit_file</name>
  <args>
    <path>src/Foo.kt</path>
    <content>// See <path>other/Bar.kt</path> for details
class Foo {
    fun bar() = 42
}</content>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        val args = result.toolCalls[0].function.arguments
        // path should be ONLY "src/Foo.kt", not swallowing into content
        assertTrue(args.contains("\"path\":\"src/Foo.kt\""), "path should be exact, got: $args")
        // content should contain the full code including the <path> reference
        assertTrue(args.contains("other/Bar.kt"), "content should include the code reference")
        assertTrue(args.contains("class Foo"), "content should include the class")
    }

    @Test
    fun `handles large content block with 200+ lines`() {
        // Matches lab scenario xml_write_large_file — verify no corruption on large payloads
        val lines = (1..250).joinToString("\n") { "    val field$it: String = \"value$it\"" }
        val text = """<tool>
  <name>create_file</name>
  <args>
    <path>src/Generated.kt</path>
    <content>package com.example

class Generated {
$lines
}</content>
  </args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        assertFalse(result.hasPartial)
        val args = result.toolCalls[0].function.arguments
        assertTrue(args.contains("field1"))
        assertTrue(args.contains("field250"))
        assertTrue(args.contains("Generated"))
    }

    @Test
    fun `generates unique synthetic IDs`() {
        val text = """<tool>
  <name>read_file</name>
  <args><path>A.kt</path></args>
</tool>
<tool>
  <name>read_file</name>
  <args><path>B.kt</path></args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(2, result.toolCalls.size)
        assertNotEquals(result.toolCalls[0].id, result.toolCalls[1].id, "IDs should be unique")
        assertTrue(result.toolCalls[0].id.startsWith("xmltool_"))
    }

    @Test
    fun `preserves text between two tool calls`() {
        val text = """<tool>
  <name>read_file</name>
  <args><path>A.kt</path></args>
</tool>

Now let me also check the second file.

<tool>
  <name>read_file</name>
  <args><path>B.kt</path></args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(2, result.toolCalls.size)
        assertTrue(result.toolCalls[0].function.arguments.contains("A.kt"))
        assertTrue(result.toolCalls[1].function.arguments.contains("B.kt"))
        assertTrue(
            result.textContent.contains("Now let me also check the second file."),
            "Text between tools should be preserved, got: '${result.textContent}'"
        )
    }

    @Test
    fun `handles empty args block`() {
        val text = """<tool>
  <name>think</name>
  <args></args>
</tool>"""

        val result = XmlToolCallParser.parse(text)

        assertEquals(1, result.toolCalls.size)
        assertEquals("think", result.toolCalls[0].function.name)
        assertEquals("{}", result.toolCalls[0].function.arguments)
    }
}
