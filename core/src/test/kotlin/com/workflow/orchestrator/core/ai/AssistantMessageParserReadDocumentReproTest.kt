package com.workflow.orchestrator.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reproduction probe for the user-reported symptom:
 *
 *   "the read_document tool USE is shown as an LLM output message
 *    instead of a tool-use UI component like the other tools"
 *
 * Hypothesis: the parser routes `<read_document>…</read_document>` to a
 * [ToolUseContent] block only when `read_document` is in the `toolNames`
 * set. If the streaming presentation layer ever calls
 * `AssistantMessageParser.parse(text, toolNames=…)` with a set that omits
 * `read_document`, the LLM's properly-formed XML tool call leaks through
 * as `TextContent` and ends up as plain prose in the assistant bubble.
 *
 * These tests run the parser with and without `read_document` in the set
 * to pin down the bug surface.
 */
class AssistantMessageParserReadDocumentReproTest {

    private val coreToolsOnly = setOf(
        "read_file", "edit_file", "create_file", "run_command", "search_code",
        "think", "attempt_completion", "ask_followup_question"
    )

    private val coreParams = setOf("path", "content", "command", "pattern", "question", "result", "thought")

    private val withReadDocument = coreToolsOnly + "read_document"

    private val streamPayload = """I'll read the requirements doc.

<read_document>
<path>/Users/me/Downloads/spec.pdf</path>
</read_document>"""

    @Test
    fun `when read_document IS in tool set, parser routes XML to a ToolUseContent block`() {
        val blocks = AssistantMessageParser.parse(streamPayload, withReadDocument, coreParams)

        assertEquals(2, blocks.size, "Expected one text block before the tool + one tool block")

        val text = blocks[0] as TextContent
        assertTrue(
            text.content.contains("I'll read"),
            "Pre-tool narrative must remain visible as TextContent"
        )
        assertFalse(
            text.content.contains("<read_document>"),
            "When recognised, the tool tag MUST NOT appear in TextContent"
        )

        val tool = blocks[1] as ToolUseContent
        assertEquals("read_document", tool.name, "Recognised tool block carries the tool name")
        assertEquals("/Users/me/Downloads/spec.pdf", tool.params["path"])
        assertFalse(tool.partial, "Closing tag arrived; tool block must be complete")
    }

    @Test
    fun `when read_document is MISSING, parser leaks the entire XML as plain text - reproduces the bug`() {
        val blocks = AssistantMessageParser.parse(streamPayload, coreToolsOnly, coreParams)

        // The bug: no ToolUseContent block, all the XML survives in a single TextContent
        val toolBlocks = blocks.filterIsInstance<ToolUseContent>()
        assertEquals(
            0,
            toolBlocks.size,
            "Reproducing the failure mode: parser does not produce a tool block when read_document is missing from the set"
        )

        val textBlocks = blocks.filterIsInstance<TextContent>()
        assertTrue(textBlocks.isNotEmpty(), "All content lands in TextContent block(s)")

        val joinedVisibleText = textBlocks.joinToString("\n") { it.content }
        assertTrue(
            joinedVisibleText.contains("<read_document>"),
            "Bug surface: the opening tag leaks into visible text"
        )
        assertTrue(
            joinedVisibleText.contains("</read_document>"),
            "Bug surface: the closing tag leaks into visible text"
        )
        assertTrue(
            joinedVisibleText.contains("/Users/me/Downloads/spec.pdf"),
            "Bug surface: the path parameter body leaks into visible text"
        )
    }

    @Test
    fun `same failure mode for any deferred tool the LLM calls without being in the set`() {
        // Generalise: this is not read_document-specific. Any deferred-but-missing tool
        // will exhibit the same surface (matches user's report that 'the code is getting
        // printed' for other tools too).
        val payload = """Looking at that.

<bitbucket_pr>
<action>get_pr_detail</action>
<pr_id>1234</pr_id>
</bitbucket_pr>"""

        val blocks = AssistantMessageParser.parse(payload, coreToolsOnly, coreParams)
        val toolBlocks = blocks.filterIsInstance<ToolUseContent>()
        assertEquals(0, toolBlocks.size, "Same bug applies to bitbucket_pr when missing from the set")

        val visibleText = blocks.filterIsInstance<TextContent>().joinToString("\n") { it.content }
        assertTrue(visibleText.contains("<bitbucket_pr>"), "Tool tag leaks for any missing deferred tool")
        assertTrue(visibleText.contains("get_pr_detail"), "Action parameter body leaks too")
    }
}
