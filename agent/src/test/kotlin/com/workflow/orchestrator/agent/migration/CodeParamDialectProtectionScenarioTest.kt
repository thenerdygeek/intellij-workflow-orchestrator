package com.workflow.orchestrator.agent.migration

import com.workflow.orchestrator.agent.session.DialectDriftDetector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * End-to-end scenario test for Gap 1 of the post-dda9f6882 migration review.
 *
 * Verifies that when the model emits a legitimate canonical tool call whose
 * code-carrying param value (e.g. <content>, <diff>, <old_string>, <new_string>,
 * <code>) contains text shaped like an Anthropic or Hermes dialect marker,
 * the dialect redactor does NOT corrupt that param value before persistence.
 *
 * Follow-up to: dda9f6882 (XML-in-content migration)
 */
class CodeParamDialectProtectionScenarioTest {

    /**
     * The canonical failure scenario from the bug report:
     * The model writes a create_file tool call whose <content> value
     * contains Anthropic dialect documentation text.
     * The write-time redaction path must NOT corrupt the file content.
     */
    @Test
    fun `create_file content discussing Anthropic dialect survives redaction pass intact`() {
        // This is the raw assistant text that AssistantMessageParser would extract
        // as a create_file tool call. The <content> param references Anthropic dialect
        // syntax — which is legitimate doc content, not a real tool attempt.
        val rawAssistantText = """I'll create the documentation file.

<create_file>
<path>docs/function-calling.md</path>
<content>
# Function Calling Formats

Anthropic uses `<function_calls><invoke name="X">...</invoke></function_calls>`
for tool calls in text. Don't use it.

The <invoke name="tool"> pattern is the legacy Anthropic format.
</content>
</create_file>"""

        // Simulate the write-time path: DialectDriftDetector.redactDialectMarkers
        // is called inside addToApiConversationHistory on every assistant turn.
        val result = DialectDriftDetector.redactDialectMarkers(rawAssistantText)

        // The content must NOT be modified — the dialect substrings live inside
        // a canonical code-carrying param, not in free-floating prose.
        assertFalse(
            result.modified,
            "redactDialectMarkers must not modify content when dialect is inside a code-carrying param"
        )
        assertEquals(rawAssistantText, result.text)

        // Specifically verify the dialect text inside <content> is fully intact
        assertTrue(result.text.contains("<function_calls><invoke name=\"X\">...</invoke></function_calls>"))
        assertTrue(result.text.contains("<invoke name=\"tool\">"))

        // And the canonical tool structure is preserved
        assertTrue(result.text.contains("<create_file>"))
        assertTrue(result.text.contains("<path>docs/function-calling.md</path>"))
    }

    /**
     * Scenario: edit_file with Anthropic dialect text in <old_string> / <new_string>.
     * Common when refactoring documentation files that discuss function calling.
     */
    @Test
    fun `edit_file old_string and new_string with dialect text survive intact`() {
        val rawAssistantText = """<edit_file>
<path>docs/migration.md</path>
<old_string><function_calls><invoke name="read_file"><parameter name="path">x</parameter></invoke></function_calls></old_string>
<new_string><read_file><path>x</path></read_file></new_string>
</edit_file>"""

        val result = DialectDriftDetector.redactDialectMarkers(rawAssistantText)

        assertFalse(result.modified, "dialect inside old_string/new_string must not be redacted")
        assertEquals(rawAssistantText, result.text)
    }

    /**
     * Scenario: Hermes dialect text inside <content> of a create_file.
     * Verifies the protection covers the Hermes pattern too.
     */
    @Test
    fun `create_file content with Hermes dialect text survives intact`() {
        val rawAssistantText = """<create_file>
<path>docs/hermes-format.md</path>
<content>
Hermes models emit: <tool_call>{"tool_name":"read_file","parameters":{"path":"x"}}</tool_call>
We redact this when the model uses it bare, but it's fine in documentation.
</content>
</create_file>"""

        val result = DialectDriftDetector.redactDialectMarkers(rawAssistantText)

        assertFalse(result.modified, "Hermes dialect inside content param must not be redacted")
        assertEquals(rawAssistantText, result.text)
    }

    /**
     * Scenario: legitimate dialect OUTSIDE a tool call (the redaction must still fire).
     * Ensures the protection only shields code-carrying param values and doesn't
     * accidentally suppress all dialect detection.
     */
    @Test
    fun `bare dialect outside any canonical tool is still redacted`() {
        val rawAssistantText = """Let me call the tool.

<function_calls>
<invoke name="read_file">
<parameter name="path">src/Foo.kt</parameter>
</invoke>
</function_calls>"""

        val result = DialectDriftDetector.redactDialectMarkers(rawAssistantText)

        assertTrue(result.modified, "bare dialect outside canonical tool must be redacted")
        assertTrue(result.text.contains(DialectDriftDetector.REDACTION_MARKER))
        assertFalse(result.text.contains("<function_calls>"))
    }

    /**
     * Mixed scenario: canonical tool call with dialect in <content> AND a bare
     * dialect emission outside. Only the outside emission must be redacted.
     */
    @Test
    fun `dialect inside content param preserved while bare dialect outside is redacted`() {
        val rawAssistantText = """<create_file>
<path>docs/formats.md</path>
<content>
Legacy: <function_calls><invoke name="X">...</invoke></function_calls>
</content>
</create_file>

Now let me also read this file (wrong format by accident):
<function_calls>
<invoke name="read_file">
<parameter name="path">src/Foo.kt</parameter>
</invoke>
</function_calls>"""

        val result = DialectDriftDetector.redactDialectMarkers(rawAssistantText)

        // The outside emission must be redacted
        assertTrue(result.modified)
        assertTrue(result.text.contains(DialectDriftDetector.REDACTION_MARKER))

        // The content param value must remain intact
        assertTrue(result.text.contains("<function_calls><invoke name=\"X\">...</invoke></function_calls>"))

        // The canonical tool structure must survive
        assertTrue(result.text.contains("<create_file>"))
        assertTrue(result.text.contains("<path>docs/formats.md</path>"))
    }
}
