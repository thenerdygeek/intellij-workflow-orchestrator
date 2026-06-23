package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * GAP4 characterization tests — pin that the WA-1 gate does NOT change XML behavior.
 *
 * (a) WRITE-PATH / CHOKEPOINT: feed a real drift-bearing assistant turn through
 *     [MessageStateHandler.addToApiConversationHistory] → assert the turn is dropped AND
 *     [MessageStateHandler.consumeDialectDriftFlag] returns true exactly once under XML
 *     (post-gate; the chokepoint predicate is true for [XmlToolProtocol]).
 *
 * (b) RESUME REDACTION (pure [ResumeHelper] call): [ResumeHelper.redactDialectDriftInHistory]
 *     on a known-drift history returns the same [DialectRedactionResult.redactedCount] as the
 *     pre-gate code. [ResumeHelper.emptyRedaction] is shape-correct (field names + values).
 *
 * Both tests REUSE the construction helper from [MessageStateHandlerDialectDriftTest] —
 * same `baseDir`/`sessionId`/`taskText` shape; no new `:core` `BasePlatformTestCase`.
 */
class DialectGuardGateCharacterizationTest {

    @TempDir
    lateinit var tempDir: Path

    // Reuse the same construction shape as MessageStateHandlerDialectDriftTest.handler().
    // Default ctor uses XmlToolProtocol (requiresDialectGuard=true) so the chokepoint is open.
    private fun handler(): MessageStateHandler = MessageStateHandler(
        baseDir = tempDir.toFile(),
        sessionId = "gate-characterization-test",
        taskText = "gate test task",
        // toolProtocol defaults to XmlToolProtocol — explicit here for clarity in the test
        toolProtocol = XmlToolProtocol(),
    )

    // ── (a) WRITE-PATH CHOKEPOINT: XML gate is transparent ──────────────────────

    /**
     * Feeds a real Anthropic `<invoke name="…">` drift marker (matched by
     * [DialectDriftDetector]'s ANTHROPIC_INVOKE regex at detector line ~154) through the
     * MessageStateHandler write path and verifies:
     *  1. The turn is rejected (history stays empty).
     *  2. [consumeDialectDriftFlag] returns true (flag was raised through the gate).
     *  3. [consumeDialectDriftFlag] returns false on the second call (one-shot reset).
     *
     * This pins the gate is a no-op for XML — behavior identical to pre-gate code.
     */
    @Test
    fun `xml chokepoint surfaces drift then resets one-shot`() = runTest {
        val h = handler()
        // Real Anthropic invoke dialect — matches ANTHROPIC_INVOKE regex in DialectDriftDetector.
        h.addToApiConversationHistory(
            ApiMessage(
                role = ApiRole.ASSISTANT,
                content = listOf(ContentBlock.Text(
                    "Let me read the file.\n\n<invoke name=\"read_file\">\n<parameter name=\"path\">src/Foo.kt</parameter>\n</invoke>"
                )),
            )
        )

        // Turn must be rejected (same as pre-gate behavior for XML).
        assertEquals(0, h.getApiConversationHistory().size,
            "Drift-bearing assistant turn must be rejected through the XML gate")

        // Flag raised at the chokepoint — returns true exactly once.
        assertTrue(h.consumeDialectDriftFlag(),
            "consumeDialectDriftFlag() must return true after drift detection (XML gate is transparent)")

        // One-shot: second call resets.
        assertFalse(h.consumeDialectDriftFlag(),
            "consumeDialectDriftFlag() must return false on second call (one-shot reset)")
    }

    /**
     * Also verifies the Hermes `<tool_call>{json}</tool_call>` dialect is caught through
     * the gate under XML — the guard is additive with the existing test in
     * [MessageStateHandlerDialectDriftTest], providing a post-gate regression anchor.
     */
    @Test
    fun `xml chokepoint surfaces Hermes tool_call drift then resets`() = runTest {
        val h = handler()
        h.addToApiConversationHistory(
            ApiMessage(
                role = ApiRole.ASSISTANT,
                content = listOf(ContentBlock.Text(
                    "Checking now.\n\n<tool_call>{\"tool_name\":\"run_command\",\"parameters\":{\"command\":\"ls\"}}</tool_call>"
                )),
            )
        )

        assertEquals(0, h.getApiConversationHistory().size,
            "Hermes tool_call drift must be rejected through the XML gate")
        assertTrue(h.consumeDialectDriftFlag(),
            "consumeDialectDriftFlag() must return true for Hermes dialect under XML gate")
        assertFalse(h.consumeDialectDriftFlag(),
            "One-shot reset must apply for Hermes dialect too")
    }

    // ── (b) RESUME REDACTION: pure ResumeHelper call; pre-gate parity ────────────

    /**
     * Verifies [ResumeHelper.redactDialectDriftInHistory] returns [DialectRedactionResult.redactedCount] >= 1
     * for a known-drift history (pre-gate parity — the gate only short-circuits under native).
     * Also verifies [ResumeHelper.emptyRedaction] is shape-correct for the native branch.
     *
     * Pure call — no fixture needed. Uses a real `<invoke name="…">` marker confirmed to
     * match [DialectDriftDetector.redactDialectMarkers] (same marker as the write-path tests).
     */
    @Test
    fun `resume redaction count is unchanged by the gate under XML (pre-gate parity)`() {
        val driftHistory = listOf(
            ApiMessage(
                role = ApiRole.ASSISTANT,
                content = listOf(ContentBlock.Text(
                    "Searching now.\n\n<invoke name=\"search_code\">\n<parameter name=\"pattern\">JWT</parameter>\n</invoke>"
                )),
                ts = 1L,
            ),
        )
        val redaction = ResumeHelper.redactDialectDriftInHistory(driftHistory)
        assertTrue(redaction.redactedCount >= 1,
            "known drift must redact at least one turn (pre-gate parity: gate does not short-circuit under XML)")

        // Verify the actual text was redacted (sanity check the marker is present).
        val assistantText = (redaction.history[0].content[0] as ContentBlock.Text).text
        assertTrue(assistantText.contains(DialectDriftDetector.REDACTION_MARKER),
            "Redacted turn must contain the REDACTION_MARKER")
        assertFalse(assistantText.contains("<invoke"),
            "Redacted turn must not contain the raw <invoke tag")

        // emptyRedaction() shape-correct check — the native branch substitutes this.
        val empty = ResumeHelper.emptyRedaction()
        assertEquals(0, empty.redactedCount,
            "emptyRedaction().redactedCount must be 0 (native no-op)")
        assertTrue(empty.history.isEmpty(),
            "emptyRedaction().history must be empty (safe for the if(redactedCount>0) guard)")
    }
}
