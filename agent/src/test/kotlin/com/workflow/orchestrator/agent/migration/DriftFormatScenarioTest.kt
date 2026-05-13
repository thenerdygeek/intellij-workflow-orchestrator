package com.workflow.orchestrator.agent.migration

import com.workflow.orchestrator.agent.session.DialectDriftDetector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DriftFormatScenarioTest {

    @Test
    fun `F2 — Anthropic function_calls wrapper detected and redacted`() {
        val raw = """<function_calls><invoke name="read_file"><parameter name="path">x</parameter></invoke></function_calls>"""
        assertTrue(DialectDriftDetector.hasDialectMarker(raw))
        val result = DialectDriftDetector.redactDialectMarkers(raw)
        assertTrue(result.modified)
        assertFalse(result.text.contains("<function_calls>"))
        assertFalse(result.text.contains("<invoke"))
    }

    @Test
    fun `F3 — Anthropic singular function_call with name attr detected`() {
        val raw = """<function_call name="read_file">{"path":"x"}</function_call>"""
        assertTrue(DialectDriftDetector.hasDialectMarker(raw))
        val result = DialectDriftDetector.redactDialectMarkers(raw)
        assertTrue(result.modified)
        assertFalse(result.text.contains("<function_call"))
    }

    @Test
    fun `F4 — bare invoke with name attr detected`() {
        val raw = """<invoke name="read_file"><parameter name="path">x</parameter></invoke>"""
        assertTrue(DialectDriftDetector.hasDialectMarker(raw))
        val result = DialectDriftDetector.redactDialectMarkers(raw)
        assertTrue(result.modified)
    }

    @Test
    fun `F5 — Hermes tool_call with JSON detected`() {
        val raw = """<tool_call>{"tool_name":"read_file","parameters":{"path":"x"}}</tool_call>"""
        assertTrue(DialectDriftDetector.hasDialectMarker(raw))
        val result = DialectDriftDetector.redactDialectMarkers(raw)
        assertTrue(result.modified)
    }

    @Test
    fun `F6 — generic tool wrapper detected`() {
        val raw = "<tool>read_file path=x</tool>"
        assertTrue(DialectDriftDetector.hasDialectMarker(raw))
        val result = DialectDriftDetector.redactDialectMarkers(raw)
        assertTrue(result.modified)
    }

    @Test
    fun `F7 — generic tool_use wrapper detected`() {
        val raw = "<tool_use><name>read_file</name><path>x</path></tool_use>"
        assertTrue(DialectDriftDetector.hasDialectMarker(raw))
        val result = DialectDriftDetector.redactDialectMarkers(raw)
        assertTrue(result.modified)
    }

    @Test
    fun `F8 — generic function wrapper detected`() {
        val raw = """<function>read_file(path="x")</function>"""
        assertTrue(DialectDriftDetector.hasDialectMarker(raw))
    }

    @Test
    fun `F9 — generic function_use wrapper detected`() {
        val raw = "<function_use><name>read_file</name></function_use>"
        assertTrue(DialectDriftDetector.hasDialectMarker(raw))
    }

    @Test
    fun `drift redaction preserves surrounding prose`() {
        val raw = "OK, I'll read. <tool>read_file path=x</tool> Done."
        val result = DialectDriftDetector.redactDialectMarkers(raw)
        assertTrue(result.modified)
        assertTrue(result.text.contains("OK, I'll read."))
        assertTrue(result.text.contains("Done."))
        assertFalse(result.text.contains("<tool>"))
    }

    @Test
    fun `drift inside fenced code block is NOT redacted (prose discussion)`() {
        val raw = """Here's the wrong format:
```
<function_calls><invoke name="X"><parameter name="Y">v</parameter></invoke></function_calls>
```
Don't use it."""
        assertFalse(DialectDriftDetector.hasDialectMarker(raw))
    }

    @Test
    fun `drift inside inline code is NOT redacted`() {
        val raw = "Avoid `<tool>read_file</tool>` because it doesn't parse."
        assertFalse(DialectDriftDetector.hasDialectMarker(raw))
    }
}
