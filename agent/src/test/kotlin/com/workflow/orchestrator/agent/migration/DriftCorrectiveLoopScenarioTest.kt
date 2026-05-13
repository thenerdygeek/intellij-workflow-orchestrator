package com.workflow.orchestrator.agent.migration

import com.workflow.orchestrator.agent.prompt.SystemPrompt
import com.workflow.orchestrator.agent.session.ApiMessage
import com.workflow.orchestrator.agent.session.ApiRole
import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.agent.session.DialectDriftDetector
import com.workflow.orchestrator.agent.session.MessageStateHandler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Gap 3 integration tests — end-to-end corrective-loop chain.
 *
 * The corrective chain has 5 hops:
 *   1. Model emits dialect-only response.
 *   2. Parser extracts zero tool calls; full raw text passes through persistence.
 *   3. Write-time guard (hasDialectDrift) fires; the message is DROPPED from history
 *      and the dialectDriftFlag is raised.
 *      Alternatively via the one-pass cleanup path (redactDialectXmlInHistory):
 *      contaminated history is rewritten with REDACTION_MARKER in-place.
 *   4. consumeDialectDriftFlag() returns true on the next call (one-shot — fires once).
 *   5. SystemPrompt.build(dialectDriftDetected = true, ...) returns a prompt with the
 *      corrective <system-reminder> block at the top.
 *
 * Hop 2 → 3 tested via two paths:
 *   (a) write-time guard path: addToApiConversationHistory drops the dialect turn and
 *       raises the flag.
 *   (b) one-pass cleanup path: overwriteApiConversationHistory + redactDialectXmlInHistory
 *       rewrites the contaminated turn with REDACTION_MARKER.
 */
class DriftCorrectiveLoopScenarioTest {

    @TempDir
    lateinit var tempDir: Path

    private fun handler(sessionId: String = "loop-test"): MessageStateHandler =
        MessageStateHandler(
            baseDir = tempDir.toFile(),
            sessionId = sessionId,
            taskText = "test task",
        )

    private val dialectText =
        "<function_calls><invoke name=\"X\"><parameter name=\"Y\">v</parameter></invoke></function_calls>"

    // ── Hop 2 → 3 (path a): write-time guard drops the message and raises flag ──

    @Test
    fun `hop2to3 write-time guard — dialect turn is dropped and drift flag is raised`() = runTest {
        val h = handler("hop2to3a")

        // Seed a canonical message so the history is non-empty before the dialect turn
        h.addToApiConversationHistory(
            ApiMessage(
                role = ApiRole.USER,
                content = listOf(ContentBlock.Text("do something")),
            )
        )
        val sizeBefore = h.getApiConversationHistory().size

        // Add a dialect-bearing assistant turn — must be dropped by the write-time guard
        h.addToApiConversationHistory(
            ApiMessage(
                role = ApiRole.ASSISTANT,
                content = listOf(ContentBlock.Text(dialectText)),
            )
        )

        val history = h.getApiConversationHistory()

        // The dialect turn must NOT appear in history (it was dropped, not redacted)
        assertEquals(sizeBefore, history.size,
            "Dialect turn must be dropped by the write-time guard — history size must not grow")

        val texts = history.flatMap { it.content }.filterIsInstance<ContentBlock.Text>().map { it.text }
        assertFalse(texts.any { it.contains("<function_calls>") },
            "History must not contain the dialect <function_calls> block after write-time guard")

        // The drift flag must have been raised
        assertTrue(h.consumeDialectDriftFlag(),
            "Drift flag must be raised when the write-time guard drops a dialect turn")
    }

    // ── Hop 2 → 3 (path b): one-pass cleanup rewrites contaminated history ──

    @Test
    fun `hop2to3 cleanup path — redactDialectXmlInHistory rewrites dialect turn with REDACTION_MARKER`() = runTest {
        val h = handler("hop2to3b")

        // Seed contaminated history (bypasses write-time guard via overwrite — simulates legacy data)
        h.overwriteApiConversationHistory(listOf(
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("task"))),
            ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text(dialectText))),
        ))

        val rewritten = h.redactDialectXmlInHistory()

        assertEquals(1, rewritten, "Exactly one assistant turn must be rewritten")

        val history = h.getApiConversationHistory()
        val assistantText = history
            .filter { it.role == ApiRole.ASSISTANT }
            .flatMap { it.content }
            .filterIsInstance<ContentBlock.Text>()
            .map { it.text }

        // After cleanup, <function_calls> must be gone and REDACTION_MARKER must be present
        assertTrue(assistantText.all { it.contains(DialectDriftDetector.REDACTION_MARKER) },
            "Rewritten assistant turn must contain REDACTION_MARKER")
        assertTrue(assistantText.none { it.contains("<function_calls>") },
            "Rewritten assistant turn must not contain <function_calls>")
    }

    // ── Hop 3 → 4: consumeDialectDriftFlag one-shot semantics ───────────────

    @Test
    fun `hop3to4 — consumeDialectDriftFlag returns true once then false (one-shot)`() = runTest {
        val h = handler("hop3to4")

        // Trigger drift via write-time guard
        h.addToApiConversationHistory(
            ApiMessage(
                role = ApiRole.ASSISTANT,
                content = listOf(ContentBlock.Text(dialectText)),
            )
        )

        // Hop 4: first consume returns true
        assertTrue(h.consumeDialectDriftFlag(),
            "consumeDialectDriftFlag must return true after a dialect-drift event")

        // One-shot: second consume returns false
        assertFalse(h.consumeDialectDriftFlag(),
            "consumeDialectDriftFlag must return false on the second call — it is one-shot")

        // And stays false
        assertFalse(h.consumeDialectDriftFlag(),
            "consumeDialectDriftFlag must remain false until the next detection event")
    }

    // ── Hop 4 → 5: SystemPrompt.build injects reminder when flag was set ────

    @Test
    fun `hop4to5 — SystemPrompt with dialectDriftDetected=true starts with system-reminder and contains correction text`() {
        val prompt = SystemPrompt.build(
            projectName = "test-project",
            projectPath = "/tmp/test",
            dialectDriftDetected = true,
        )

        // The corrective reminder must be the very first content in the prompt
        assertTrue(prompt.trimStart().startsWith("<system-reminder>"),
            "Prompt with dialectDriftDetected=true must start with <system-reminder>. " +
            "Actual start: ${prompt.take(200)}")

        // Core correction message
        assertTrue(prompt.contains("CRITICAL — TOOL-CALL FORMAT CORRECTION"),
            "Corrective reminder must contain the CRITICAL header")

        // Positive example showing the correct format
        assertTrue(prompt.contains("<read_file>"),
            "Corrective reminder must include a positive <read_file> example")
        assertTrue(prompt.contains("<path>src/main/kotlin/Example.kt</path>"),
            "Corrective reminder must include the example path param")

        // Must also close the reminder block
        assertTrue(prompt.contains("</system-reminder>"),
            "Corrective reminder must be closed with </system-reminder>")

        // When dialectDriftDetected=false, the reminder must NOT appear
        val cleanPrompt = SystemPrompt.build(
            projectName = "test-project",
            projectPath = "/tmp/test",
            dialectDriftDetected = false,
        )
        assertFalse(cleanPrompt.contains("<system-reminder>"),
            "Prompt with dialectDriftDetected=false must NOT contain <system-reminder>")
        assertFalse(cleanPrompt.contains("CRITICAL — TOOL-CALL FORMAT CORRECTION"),
            "Prompt with dialectDriftDetected=false must NOT contain the correction header")
    }

    // ── Full chain: all 5 hops in one test ──────────────────────────────────

    @Test
    fun `full corrective chain — persist dialect via cleanup path then read back redacted history then check flag then build prompt with reminder`() = runTest {
        val h = handler("full-chain")

        // Hop 1: model emits dialect-only response
        val dialectTurn = ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text(dialectText)),
        )

        // Hop 2 + 3: seed contaminated history and run one-pass cleanup
        //   (tests the resume/retry path where old sessions have dialect in history)
        h.overwriteApiConversationHistory(listOf(
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("find something"))),
            dialectTurn,
        ))
        val rewritten = h.redactDialectXmlInHistory()
        assertTrue(rewritten > 0, "At least one turn must be rewritten in the cleanup pass")

        // Hop 3 → read back: <function_calls> must be gone, REDACTION_MARKER must be present
        val historyAfterCleanup = h.getApiConversationHistory()
        val assistantBlockTexts = historyAfterCleanup
            .filter { it.role == ApiRole.ASSISTANT }
            .flatMap { it.content }
            .filterIsInstance<ContentBlock.Text>()
            .map { it.text }

        assertTrue(assistantBlockTexts.isNotEmpty(),
            "History must contain at least one assistant turn after cleanup")
        assertTrue(assistantBlockTexts.all { it.contains(DialectDriftDetector.REDACTION_MARKER) },
            "All assistant turns must contain REDACTION_MARKER after cleanup")
        assertTrue(assistantBlockTexts.none { it.contains("<function_calls>") },
            "No assistant turn must contain <function_calls> after cleanup")

        // Hop 4: consumeDialectDriftFlag returns true — the flag was raised by redactDialectXmlInHistory
        val flagValue = h.consumeDialectDriftFlag()
        assertTrue(flagValue,
            "consumeDialectDriftFlag must return true after the cleanup pass raised the flag")

        // One-shot: calling again returns false
        assertFalse(h.consumeDialectDriftFlag(),
            "Second consume must return false — one-shot semantics")

        // Hop 5: build prompt with the flag active → corrective reminder present
        val correctedPrompt = SystemPrompt.build(
            projectName = "my-project",
            projectPath = "/tmp/my-project",
            dialectDriftDetected = true,   // simulates consuming flagValue and passing it to SystemPrompt
        )

        assertTrue(correctedPrompt.trimStart().startsWith("<system-reminder>"),
            "Corrected prompt must start with <system-reminder>")
        assertTrue(correctedPrompt.contains("CRITICAL — TOOL-CALL FORMAT CORRECTION"),
            "Corrected prompt must contain the correction header")
    }

    // ── Negative control: canonical turn does not trigger the corrective chain ──

    @Test
    fun `negative control — canonical assistant turn does not raise drift flag or inject reminder`() = runTest {
        val h = handler("negative-control")

        // Add a canonical assistant turn with a <read_file> XML call
        val canonicalTurn = ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text(
                "I'll read the file.\n<read_file>\n<path>src/Foo.kt</path>\n</read_file>"
            )),
        )
        h.addToApiConversationHistory(canonicalTurn)

        // The turn must be persisted (not dropped)
        assertEquals(1, h.getApiConversationHistory().size,
            "Canonical turn must be persisted — write-time guard must not drop it")

        // consumeDialectDriftFlag must return false — no drift was detected
        assertFalse(h.consumeDialectDriftFlag(),
            "consumeDialectDriftFlag must return false when only canonical turns were added")

        // Building a prompt without the explicit dialectDriftDetected=true must NOT inject the reminder
        val prompt = SystemPrompt.build(
            projectName = "test-project",
            projectPath = "/tmp/test",
            dialectDriftDetected = false,
        )
        assertFalse(prompt.contains("<system-reminder>"),
            "Prompt without dialectDriftDetected=true must not contain <system-reminder>")
        assertFalse(prompt.contains("CRITICAL — TOOL-CALL FORMAT CORRECTION"),
            "Prompt without dialectDriftDetected=true must not contain the correction header")
    }
}
