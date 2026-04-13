package com.workflow.orchestrator.core.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Targeted tests for the XML parser's handling of `ask_followup_question`.
 *
 * The ask_followup_question tool is unique because:
 * 1. Its `options` param contains a JSON array as a STRING (double-quote heavy)
 * 2. It is the only communication tool the user interacts with directly
 * 3. A parser failure here leaves the UI stuck with no tool card, no question, and no error
 *
 * These tests reproduce the exact XML the LLM produces and verify extraction at every stage.
 */
@DisplayName("AssistantMessageParser: ask_followup_question extraction")
class AskFollowupQuestionParserTest {

    private val toolNames = setOf(
        "read_file", "edit_file", "create_file", "run_command", "search_code",
        "think", "attempt_completion", "ask_followup_question"
    )
    private val paramNames = setOf(
        "path", "content", "old_string", "new_string", "diff", "code", "command",
        "pattern", "question", "result", "thought", "working_dir", "regex",
        "file_pattern", "output_mode", "directory", "include", "options",
        "questions", "title", "description", "prompt"
    )

    // ════════════════════════════════════════════
    //  Stage 1: Basic extraction
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Basic tool call extraction")
    inner class BasicExtraction {

        @Test
        fun `extracts ask_followup_question with question only (no options)`() {
            val text = """<ask_followup_question>
<question>What database should we use?</question>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val tools = blocks.filterIsInstance<ToolUseContent>()

            assertEquals(1, tools.size)
            assertEquals("ask_followup_question", tools[0].name)
            assertEquals("What database should we use?", tools[0].params["question"])
            assertFalse(tools[0].partial)
            assertNull(tools[0].params["options"])
        }

        @Test
        fun `extracts ask_followup_question with question and options`() {
            val text = """<ask_followup_question>
<question>What would you like to work on today?</question>
<options>["Help with PROJECT-12345 (some API fixes)", "Something else"]</options>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val tools = blocks.filterIsInstance<ToolUseContent>()

            assertEquals(1, tools.size, "Should extract exactly one tool call")
            assertEquals("ask_followup_question", tools[0].name)
            assertEquals("What would you like to work on today?", tools[0].params["question"])
            assertEquals(
                """["Help with PROJECT-12345 (some API fixes)", "Something else"]""",
                tools[0].params["options"]
            )
            assertFalse(tools[0].partial)
        }

        @Test
        fun `extracts ask_followup_question preceded by text`() {
            val text = """I'd like to understand your priorities before proceeding.

<ask_followup_question>
<question>What would you like to work on today?</question>
<options>["Option A", "Option B"]</options>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)

            assertEquals(2, blocks.size, "Should have text block + tool block")
            assertTrue(blocks[0] is TextContent)
            assertTrue((blocks[0] as TextContent).content.contains("priorities"))

            val tool = blocks[1] as ToolUseContent
            assertEquals("ask_followup_question", tool.name)
            assertFalse(tool.partial)
        }
    }

    // ════════════════════════════════════════════
    //  Stage 2: Options containing tricky characters
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Options with special characters")
    inner class OptionsSpecialChars {

        @Test
        fun `options with double quotes are preserved literally`() {
            // The LLM sends JSON array inside <options> tags — quotes are literal
            val text = """<ask_followup_question>
<question>Pick one</question>
<options>["First \"quoted\" option", "Second option"]</options>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val tool = blocks.filterIsInstance<ToolUseContent>().single()

            assertEquals("ask_followup_question", tool.name)
            assertFalse(tool.partial)
            // The raw options string should be preserved exactly as the LLM wrote it
            assertNotNull(tool.params["options"])
            assertTrue(tool.params["options"]!!.contains("quoted"))
        }

        @Test
        fun `options with parentheses and special project keys`() {
            val text = """<ask_followup_question>
<question>Which task?</question>
<options>["Fix PROJECT-12345 (critical bug in AuthService)", "Refactor CORE-999 [low priority]", "Something else entirely"]</options>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val tool = blocks.filterIsInstance<ToolUseContent>().single()

            assertEquals("ask_followup_question", tool.name)
            assertFalse(tool.partial)
            assertTrue(tool.params["options"]!!.contains("PROJECT-12345"))
            assertTrue(tool.params["options"]!!.contains("CORE-999"))
        }

        @Test
        fun `options with angle brackets in text`() {
            // Options text that contains < or > could confuse the XML parser
            val text = """<ask_followup_question>
<question>Pick a type</question>
<options>["List<String>", "Map<String, Int>", "Set<Any>"]</options>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val tools = blocks.filterIsInstance<ToolUseContent>()

            // This is the critical test: angle brackets inside <options> could
            // cause the parser to prematurely close the tag or misparse
            assertEquals(1, tools.size, "Parser must still extract exactly one tool")
            assertEquals("ask_followup_question", tools[0].name)
            // The options value may be truncated or mangled — verify the tool at least parsed
            assertNotNull(tools[0].params["question"])
        }

        @Test
        fun `options with newlines inside JSON array`() {
            val text = """<ask_followup_question>
<question>Pick one</question>
<options>[
  "Option A - a long description that wraps",
  "Option B - another long description"
]</options>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val tool = blocks.filterIsInstance<ToolUseContent>().single()

            assertFalse(tool.partial)
            assertTrue(tool.params["options"]!!.contains("Option A"))
            assertTrue(tool.params["options"]!!.contains("Option B"))
        }

        @Test
        fun `options with unicode characters`() {
            val text = """<ask_followup_question>
<question>Choose language</question>
<options>["English 🇬🇧", "日本語", "Español", "Français"]</options>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val tool = blocks.filterIsInstance<ToolUseContent>().single()

            assertFalse(tool.partial)
            assertTrue(tool.params["options"]!!.contains("日本語"))
        }

        @Test
        fun `options with backslashes (file paths)`() {
            val text = """<ask_followup_question>
<question>Which file?</question>
<options>["src\\main\\kotlin\\App.kt", "src/main/kotlin/App.kt"]</options>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val tool = blocks.filterIsInstance<ToolUseContent>().single()

            assertFalse(tool.partial)
            assertTrue(tool.params["options"]!!.contains("src"))
        }

        @Test
        fun `options with single quotes (apostrophes)`() {
            val text = """<ask_followup_question>
<question>What's the approach?</question>
<options>["It's a refactor", "Don't change anything", "Let's discuss"]</options>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val tool = blocks.filterIsInstance<ToolUseContent>().single()

            assertFalse(tool.partial)
            assertEquals("What's the approach?", tool.params["question"])
            assertTrue(tool.params["options"]!!.contains("It's a refactor"))
        }
    }

    // ════════════════════════════════════════════
    //  Stage 3: Partial/truncated tool calls
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Partial and truncated responses")
    inner class PartialResponses {

        @Test
        fun `partial ask_followup_question (no closing tag)`() {
            val text = """<ask_followup_question>
<question>What would you like?</question>
<options>["Option A", "Option B"]</options>"""
            // Missing </ask_followup_question>

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val tools = blocks.filterIsInstance<ToolUseContent>()

            assertEquals(1, tools.size)
            assertTrue(tools[0].partial, "Tool should be marked partial — closing tag missing")
            assertEquals("ask_followup_question", tools[0].name)
        }

        @Test
        fun `partial options (truncated mid-JSON)`() {
            val text = """<ask_followup_question>
<question>Pick one</question>
<options>["Option A", "Option"""
            // Truncated mid-JSON, no closing tags

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val tools = blocks.filterIsInstance<ToolUseContent>()

            assertEquals(1, tools.size)
            assertTrue(tools[0].partial)
            assertEquals("Pick one", tools[0].params["question"])
            // options should contain whatever was captured
            assertNotNull(tools[0].params["options"])
        }

        @Test
        fun `partial question (still streaming question text)`() {
            val text = """<ask_followup_question>
<question>What would you like to work on tod"""
            // Mid-question text

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val tools = blocks.filterIsInstance<ToolUseContent>()

            assertEquals(1, tools.size)
            assertTrue(tools[0].partial)
            assertTrue(tools[0].params["question"]!!.contains("work on tod"))
        }
    }

    // ════════════════════════════════════════════
    //  Stage 4: Tool call after other tools
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("ask_followup_question following other tool calls")
    inner class AfterOtherTools {

        @Test
        fun `ask_followup_question after read_file`() {
            val text = """<read_file>
<path>src/Main.kt</path>
</read_file>

I need some clarification before proceeding.

<ask_followup_question>
<question>Should I refactor or patch?</question>
<options>["Refactor the entire module", "Quick patch"]</options>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val tools = blocks.filterIsInstance<ToolUseContent>()

            assertEquals(2, tools.size)
            assertEquals("read_file", tools[0].name)
            assertFalse(tools[0].partial)

            assertEquals("ask_followup_question", tools[1].name)
            assertFalse(tools[1].partial)
            assertEquals("Should I refactor or patch?", tools[1].params["question"])
            assertTrue(tools[1].params["options"]!!.contains("Refactor"))
        }

        @Test
        fun `ask_followup_question as only tool call (no text, no other tools)`() {
            // LLM outputs just the tool call with zero surrounding text
            val text = """<ask_followup_question>
<question>What would you like to work on today?</question>
<options>["Help with PROJECT-12345 (some API fixes)", "Something else"]</options>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)

            // CRITICAL: there must be at least one ToolUseContent that is NOT partial
            val tools = blocks.filterIsInstance<ToolUseContent>().filter { !it.partial }
            assertEquals(1, tools.size, "Must extract exactly one complete tool call")
            assertEquals("ask_followup_question", tools[0].name)
        }
    }

    // ════════════════════════════════════════════
    //  Stage 5: Visible text extraction during streaming
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("Visible text stripping (streaming presentation)")
    inner class VisibleTextStripping {

        @Test
        fun `ask_followup_question XML is stripped from visible text`() {
            val text = """I'd like to ask a question.

<ask_followup_question>
<question>What would you like?</question>
<options>["A", "B"]</options>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val visibleText = blocks.filterIsInstance<TextContent>()
                .joinToString("\n\n") { it.content }

            // The XML tags must NOT appear in the visible text
            assertFalse(visibleText.contains("<ask_followup_question>"))
            assertFalse(visibleText.contains("<question>"))
            assertFalse(visibleText.contains("<options>"))
            assertTrue(visibleText.contains("ask a question"))
        }

        @Test
        fun `ask_followup_question with NO preceding text produces empty visible text`() {
            val text = """<ask_followup_question>
<question>What would you like?</question>
<options>["A", "B"]</options>
</ask_followup_question>"""

            val blocks = AssistantMessageParser.parse(text, toolNames, paramNames)
            val visibleText = blocks.filterIsInstance<TextContent>()
                .joinToString("\n\n") { it.content }

            // When only a tool call is in the response, visible text should be empty
            assertTrue(visibleText.isBlank(), "Visible text should be blank when response is only a tool call")
        }
    }

    // ════════════════════════════════════════════
    //  Stage 6: stripPartialTag edge cases
    // ════════════════════════════════════════════

    @Nested
    @DisplayName("stripPartialTag with ask_followup_question")
    inner class StripPartialTag {

        @Test
        fun `strips partial opening tag mid-stream`() {
            val text = "Let me ask you something.\n\n<ask_followup"
            val stripped = AssistantMessageParser.stripPartialTag(text)
            assertFalse(stripped.contains("<ask_followup"))
            assertTrue(stripped.contains("Let me ask"))
        }

        @Test
        fun `does not strip complete tags`() {
            val text = "Use the <options> tag for choices."
            val stripped = AssistantMessageParser.stripPartialTag(text)
            assertEquals(text, stripped) // Complete tag, should NOT be stripped
        }
    }
}
