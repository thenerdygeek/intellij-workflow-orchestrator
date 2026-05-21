package com.workflow.orchestrator.agent.migration

import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.assistantTurn
import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.persistAssistantTurn
import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.renderForNextTurn
import com.workflow.orchestrator.agent.migration.XmlInContentScenarios.userTurn
import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.agent.session.DialectDriftDetector
import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.ToolUseContent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Gap 2 scenario tests — mixed canonical + dialect in the same assistant turn.
 *
 * Scenario: the model emits one correct canonical call (<read_file>) AND one
 * incorrect dialect call (<function_calls><invoke ...>) in the same response.
 *
 * Expected pipeline behaviour:
 *   1. AssistantMessageParser extracts exactly ONE tool use (the canonical one).
 *   2. DialectDriftDetector.hasDialectMarker() returns true (dialect is present).
 *   3. redactDialectMarkers() preserves the canonical block and replaces only
 *      the dialect block with REDACTION_MARKER.
 *   4. End-to-end: persisted + rendered message contains the canonical XML AND
 *      the redaction marker, but NOT the original <function_calls> block.
 *   5. Negative control: dialect-only turn → 0 tool uses, flag, full redaction.
 */
class MixedCanonicalAndDriftScenarioTest {

    private val knownTools = setOf("read_file", "edit_file", "create_file", "search_code")
    private val knownParams = setOf("path", "pattern", "old_string", "new_string", "content")

    /** The canonical mixed-turn text used across several tests. */
    private val mixedTurnRaw = """I'll read the file.
<read_file>
<path>src/Foo.kt</path>
</read_file>
And let me also call: <function_calls><invoke name="search_code"><parameter name="pattern">x</parameter></invoke></function_calls>"""

    // ── Test 1: parser extracts exactly ONE canonical tool use ──────────────

    @Test
    fun `mixed turn parses to exactly one canonical tool use and no dialect tool use`() {
        val parsed = AssistantMessageParser.parse(mixedTurnRaw, knownTools, knownParams)
        val toolUses = parsed.filterIsInstance<ToolUseContent>()

        assertEquals(1, toolUses.size,
            "Parser must extract exactly one tool use from a mixed turn")
        assertEquals("read_file", toolUses[0].name,
            "The extracted tool use must be the canonical read_file, not the dialect block")
        assertEquals("src/Foo.kt", toolUses[0].params["path"],
            "Canonical param value must be extracted correctly")
        assertFalse(toolUses[0].partial,
            "The canonical tool use must be complete (not partial)")
    }

    // ── Test 2: dialect marker is detected in the raw mixed-turn text ───────

    @Test
    fun `mixed turn triggers hasDialectMarker because the dialect block is outside code-carrying params`() {
        assertTrue(
            DialectDriftDetector.hasDialectMarker(mixedTurnRaw),
            "hasDialectMarker must return true when dialect XML is present outside a canonical tool's code-carrying param"
        )
    }

    // ── Test 3: redactor preserves canonical block and replaces dialect block ──

    @Test
    fun `redactDialectMarkers preserves canonical read_file block and replaces function_calls block`() {
        val result = DialectDriftDetector.redactDialectMarkers(mixedTurnRaw)

        assertTrue(result.modified,
            "redactDialectMarkers must report modified=true when a dialect block is present")

        // The canonical <read_file> block must survive verbatim
        val canonicalBlock = "<read_file>\n<path>src/Foo.kt</path>\n</read_file>"
        assertTrue(result.text.contains(canonicalBlock),
            "Canonical <read_file> block must be preserved verbatim after redaction")

        // Surrounding prose must survive
        assertTrue(result.text.contains("I'll read the file."),
            "Prose before the canonical block must be preserved")
        assertTrue(result.text.contains("And let me also call:"),
            "Prose before the dialect block must be preserved")

        // The dialect block must be replaced
        assertFalse(result.text.contains("<function_calls>"),
            "<function_calls> must be absent from the redacted text")
        assertFalse(result.text.contains("<invoke name="),
            "<invoke name=...> must be absent from the redacted text")

        // The redaction marker must be present
        assertTrue(result.text.contains(DialectDriftDetector.REDACTION_MARKER),
            "REDACTION_MARKER must appear where the dialect block was")
    }

    // ── Test 4: end-to-end through persist + renderForNextTurn ─────────────

    @Test
    fun `end-to-end mixed turn persists with canonical XML inline and redaction marker, without function_calls`() {
        // Step 1: persist the assistant turn (raw text → single ContentBlock.Text)
        val blocks = persistAssistantTurn(mixedTurnRaw)
        assertEquals(1, blocks.size)
        val rawPersisted = (blocks[0] as ContentBlock.Text).text

        // Step 2: simulate the write-time redaction pass
        //   (production path: DialectDriftDetector.redactDialectMarkers called in
        //    MessageStateHandler.addToApiConversationHistory via hasDialectDrift → rejection,
        //    but for the rendering test we directly apply redactDialectMarkers to model
        //    what the persisted text would look like after the in-history cleanup path)
        val redacted = DialectDriftDetector.redactDialectMarkers(rawPersisted).text
        val redactedAssistantTurn = assistantTurn(redacted)

        // Step 3: render through renderForNextTurn
        val history = listOf(userTurn("read src/Foo.kt"), redactedAssistantTurn)
        val wire = renderForNextTurn(history)

        // Phase 6 may append a synthetic [Continue] user tail — grab the assistant explicitly.
        val assistantMessage = wire.last { it.role == "assistant" }

        val content = assistantMessage.content!!

        // Canonical XML inline must be present in the wire content
        val canonicalBlock = "<read_file>\n<path>src/Foo.kt</path>\n</read_file>"
        assertTrue(content.contains(canonicalBlock),
            "The canonical <read_file> XML must appear in the assistant content sent to the next LLM turn. " +
            "Content: $content")

        // The dialect block must NOT appear in the wire content
        assertFalse(content.contains("<function_calls>"),
            "<function_calls> dialect must be absent from the wire content. Content: $content")

        // The redaction marker must appear in the wire content so the model sees the correction
        assertTrue(content.contains(DialectDriftDetector.REDACTION_MARKER),
            "REDACTION_MARKER must appear in the wire content so the model sees the corrective signal. " +
            "Content: $content")
    }

    // ── Test 5: negative control — dialect-only turn ─────────────────────────

    @Test
    fun `dialect-only turn produces zero canonical tool uses, detector flags it, redactor replaces the whole block`() {
        val dialectOnly =
            "<function_calls><invoke name=\"search_code\"><parameter name=\"pattern\">x</parameter></invoke></function_calls>"

        // Parser extracts zero tool uses (dialect tag names are not in the known tool set)
        val parsed = AssistantMessageParser.parse(dialectOnly, knownTools, knownParams)
        val toolUses = parsed.filterIsInstance<ToolUseContent>()
        assertEquals(0, toolUses.size,
            "Dialect-only turn must produce zero canonical tool uses")

        // Detector flags the dialect
        assertTrue(DialectDriftDetector.hasDialectMarker(dialectOnly),
            "Detector must flag a dialect-only turn")

        // Redactor replaces the entire dialect block
        val result = DialectDriftDetector.redactDialectMarkers(dialectOnly)
        assertTrue(result.modified,
            "redactDialectMarkers must report modified=true for dialect-only text")
        assertFalse(result.text.contains("<function_calls>"),
            "Redacted text must not contain <function_calls>")
        assertTrue(result.text.contains(DialectDriftDetector.REDACTION_MARKER),
            "Redacted text must contain the REDACTION_MARKER")
    }
}
