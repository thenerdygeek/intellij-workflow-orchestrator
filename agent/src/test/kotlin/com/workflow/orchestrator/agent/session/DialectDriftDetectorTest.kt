package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DialectDriftDetectorTest {

    // ── hasDialectMarker — positive cases ───────────────────────────────

    @Test
    fun `detects Anthropic function_calls wrapper`() {
        val text = """
            Let me read the file.

            <function_calls>
            <invoke name="read_document">
            <parameter name="path">/foo.pdf</parameter>
            </invoke>
            </function_calls>
        """.trimIndent()
        assertTrue(DialectDriftDetector.hasDialectMarker(text))
    }

    @Test
    fun `detects bare Anthropic invoke without wrapper`() {
        val text = """
            <invoke name="read_file">
            <parameter name="path">src/Foo.kt</parameter>
            </invoke>
        """.trimIndent()
        assertTrue(DialectDriftDetector.hasDialectMarker(text))
    }

    @Test
    fun `detects Hermes tool_call with immediate JSON`() {
        val text = """
            Now let me check the file:

            <tool_call>{"tool_name":"read_file","parameters":{"path":"src/Foo.kt"}}</tool_call>
        """.trimIndent()
        assertTrue(DialectDriftDetector.hasDialectMarker(text))
    }

    // ── hasDialectMarker — negative cases ───────────────────────────────

    @Test
    fun `registered tool format is not flagged`() {
        val text = """
            I'll read the file.

            <read_file>
            <path>src/Foo.kt</path>
            </read_file>
        """.trimIndent()
        assertFalse(DialectDriftDetector.hasDialectMarker(text))
    }

    @Test
    fun `prose discussing dialect inside triple-backtick fence is not flagged`() {
        // The agent's own /help output, or a user asking "what does this format mean?"
        // — must NOT trip the detector.
        val text = """
            The Anthropic protocol uses a different shape. For example:

            ```xml
            <function_calls>
            <invoke name="read_file">
            <parameter name="path">x</parameter>
            </invoke>
            </function_calls>
            ```

            That's what we redact when we see it.
        """.trimIndent()
        assertFalse(DialectDriftDetector.hasDialectMarker(text))
    }

    @Test
    fun `prose with inline backtick mention is not flagged`() {
        val text = "If the model emits `<invoke name=\"x\">` we should redact it."
        assertFalse(DialectDriftDetector.hasDialectMarker(text))
    }

    @Test
    fun `plain prose mentioning function_calls without nested tag is not flagged`() {
        // "<function_calls>" without an immediately-following `<` should not trip
        // the wrapper detector (the regex requires `<function_calls>\s*<`).
        val text = "The function_calls protocol is what Anthropic uses internally."
        assertFalse(DialectDriftDetector.hasDialectMarker(text))
    }

    @Test
    fun `empty text is not flagged`() {
        assertFalse(DialectDriftDetector.hasDialectMarker(""))
    }

    // ── redactDialectMarkers — rewrites correctly ───────────────────────

    @Test
    fun `redacts Anthropic function_calls block with marker`() {
        val input = """
            Let me read these files.

            <function_calls>
            <invoke name="read_document">
            <parameter name="path">/foo.pdf</parameter>
            </invoke>
            </function_calls>

            Done.
        """.trimIndent()

        val result = DialectDriftDetector.redactDialectMarkers(input)

        assertTrue(result.modified)
        assertTrue(result.text.contains(DialectDriftDetector.REDACTION_MARKER))
        assertFalse(result.text.contains("<function_calls>"))
        assertFalse(result.text.contains("<invoke"))
        // Surrounding prose preserved
        assertTrue(result.text.contains("Let me read these files."))
        assertTrue(result.text.contains("Done."))
    }

    @Test
    fun `redacts bare invoke block`() {
        val input = """
            <invoke name="run_command">
            <parameter name="command">ls -la</parameter>
            </invoke>
        """.trimIndent()

        val result = DialectDriftDetector.redactDialectMarkers(input)

        assertTrue(result.modified)
        assertEquals(DialectDriftDetector.REDACTION_MARKER, result.text.trim())
    }

    @Test
    fun `redacts Hermes tool_call block`() {
        val input = """Let me check.

<tool_call>{"tool_name":"read_file","parameters":{"path":"src/Foo.kt"}}</tool_call>

Continuing."""

        val result = DialectDriftDetector.redactDialectMarkers(input)

        assertTrue(result.modified)
        assertTrue(result.text.contains(DialectDriftDetector.REDACTION_MARKER))
        assertFalse(result.text.contains("<tool_call>"))
        assertTrue(result.text.contains("Let me check."))
        assertTrue(result.text.contains("Continuing."))
    }

    @Test
    fun `redacts multiple dialect blocks in same text`() {
        val input = """
            <invoke name="read_file"><parameter name="path">a</parameter></invoke>
            Some prose between calls.
            <tool_call>{"tool_name":"read_file","parameters":{"path":"b"}}</tool_call>
        """.trimIndent()

        val result = DialectDriftDetector.redactDialectMarkers(input)

        assertTrue(result.modified)
        val markerCount = result.text.split(DialectDriftDetector.REDACTION_MARKER).size - 1
        assertEquals(2, markerCount, "Both dialect blocks should be redacted with separate markers")
        assertTrue(result.text.contains("Some prose between calls."))
    }

    @Test
    fun `clean text passes through unmodified`() {
        val input = """
            I'll read the file.

            <read_file>
            <path>src/Foo.kt</path>
            </read_file>
        """.trimIndent()

        val result = DialectDriftDetector.redactDialectMarkers(input)

        assertFalse(result.modified)
        assertEquals(input, result.text)
    }

    @Test
    fun `dialect inside code fence is NOT redacted`() {
        // Documentation / illustrative prose must not be eaten.
        val input = """
            Here is the bad format the parser does not understand:

            ```
            <function_calls>
            <invoke name="read_file">
            <parameter name="path">x</parameter>
            </invoke>
            </function_calls>
            ```

            We redact it when the model emits it outside a code block.
        """.trimIndent()

        val result = DialectDriftDetector.redactDialectMarkers(input)

        // Code-fence-protected — must not trip the redactor.
        assertFalse(result.modified)
        assertEquals(input, result.text)
    }

    @Test
    fun `dialect outside fence is redacted even when same text contains a fenced example`() {
        // Mixed case: the model EMITTED a real call AND included a docs-style fence.
        // The real call must be redacted, the fenced example must not.
        val input = """
            Calling the tool:
            <invoke name="run_command"><parameter name="command">ls</parameter></invoke>

            For reference, the bad format looks like:
            ```
            <invoke name="X"><parameter name="Y">v</parameter></invoke>
            ```
        """.trimIndent()

        val result = DialectDriftDetector.redactDialectMarkers(input)

        assertTrue(result.modified)
        assertTrue(result.text.contains(DialectDriftDetector.REDACTION_MARKER))
        // The fenced example must still be intact (it's inside ```)
        assertTrue(result.text.contains("```"))
        assertTrue(
            result.text.contains("<invoke name=\"X\">"),
            "Fenced example must survive — only the outside-fence call gets redacted"
        )
    }

    @Test
    fun `redaction is idempotent`() {
        val input = """
            <function_calls>
            <invoke name="read_file"><parameter name="path">a</parameter></invoke>
            </function_calls>
        """.trimIndent()

        val once = DialectDriftDetector.redactDialectMarkers(input)
        val twice = DialectDriftDetector.redactDialectMarkers(once.text)

        assertTrue(once.modified)
        assertFalse(twice.modified, "Second pass over already-redacted text must be a no-op")
        assertEquals(once.text, twice.text)
    }

    @Test
    fun `redaction marker text is non-empty and self-describing`() {
        // Sanity check on the constant — if someone shortens it to "" the cleanup
        // would silently destroy content. Pin a minimum.
        assertNotEquals("", DialectDriftDetector.REDACTION_MARKER)
        assertTrue(DialectDriftDetector.REDACTION_MARKER.startsWith("[redacted"))
        assertTrue(DialectDriftDetector.REDACTION_MARKER.length >= 40)
    }
}
