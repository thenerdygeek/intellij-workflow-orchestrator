package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Pins the two layers of dialect-drift defense in MessageStateHandler:
 *
 * 1. **Write-time guard** in [MessageStateHandler.addToApiConversationHistory]
 *    rejects assistant turns whose Text content contains
 *    `<function_calls><invoke>` / `<invoke name="…">` / `<tool_call>{json}`
 *    patterns, and raises the one-shot drift flag.
 *
 * 2. **One-pass cleanup** [MessageStateHandler.redactDialectXmlInHistory]
 *    walks the persisted history and rewrites Text spans containing dialect
 *    XML to the `[redacted: …]` marker. Also raises the drift flag.
 *
 * Both layers feed [MessageStateHandler.consumeDialectDriftFlag], which is a
 * one-shot consumer used by `AgentService.systemPromptBuilder` to inject the
 * corrective `<system-reminder>` exactly once per detection event.
 *
 * See research notes: `docs/research/2026-05-12-dialect-contamination-research.md`.
 */
class MessageStateHandlerDialectDriftTest {

    @TempDir
    lateinit var tempDir: Path

    private fun handler(sessionId: String = "drift-test"): MessageStateHandler =
        MessageStateHandler(
            baseDir = tempDir.toFile(),
            sessionId = sessionId,
            taskText = "test task",
        )

    // ── Layer 1: write-time guard ────────────────────────────────────────

    @Test
    fun `addToApiConversationHistory rejects assistant turn with Anthropic invoke dialect`() = runTest {
        val h = handler()
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text(
                "Let me read the file.\n\n<invoke name=\"read_file\">\n<parameter name=\"path\">src/Foo.kt</parameter>\n</invoke>"
            )),
        ))

        assertEquals(0, h.getApiConversationHistory().size, "Dialect-drift assistant turn must be rejected on write")
        assertTrue(h.consumeDialectDriftFlag(), "Drift flag must be raised when the guard fires")
    }

    @Test
    fun `addToApiConversationHistory rejects assistant turn with function_calls wrapper`() = runTest {
        val h = handler()
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text(
                """<function_calls>
                |<invoke name="run_command">
                |<parameter name="command">ls</parameter>
                |</invoke>
                |</function_calls>""".trimMargin()
            )),
        ))

        assertEquals(0, h.getApiConversationHistory().size)
        assertTrue(h.consumeDialectDriftFlag())
    }

    @Test
    fun `addToApiConversationHistory rejects assistant turn with Hermes tool_call`() = runTest {
        val h = handler()
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text(
                "Checking now.\n\n<tool_call>{\"tool_name\":\"read_file\",\"parameters\":{\"path\":\"x\"}}</tool_call>"
            )),
        ))

        assertEquals(0, h.getApiConversationHistory().size)
        assertTrue(h.consumeDialectDriftFlag())
    }

    @Test
    fun `addToApiConversationHistory accepts clean assistant turn`() = runTest {
        val h = handler()
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text("I'll help with that. Here's what I found.")),
        ))

        assertEquals(1, h.getApiConversationHistory().size)
        assertFalse(h.consumeDialectDriftFlag(), "Flag must NOT be raised on a clean turn")
    }

    @Test
    fun `addToApiConversationHistory accepts assistant turn discussing dialect inside code fence`() = runTest {
        val h = handler()
        // The agent's own /help output or audit explanation must not be rejected
        // just because it mentions the format.
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text(
                """The Anthropic format looks like this:
                |
                |```
                |<function_calls>
                |<invoke name="X">
                |<parameter name="Y">value</parameter>
                |</invoke>
                |</function_calls>
                |```
                |
                |We don't use that format — we use the bare tool tag.""".trimMargin()
            )),
        ))

        assertEquals(1, h.getApiConversationHistory().size, "Fenced documentation must survive the guard")
        assertFalse(h.consumeDialectDriftFlag())
    }

    @Test
    fun `addToApiConversationHistory user role with dialect-looking text is NOT rejected`() = runTest {
        val h = handler()
        // A user could legitimately paste an example from another agent in their
        // own message. We only guard assistant turns (the contamination vector).
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.USER,
            content = listOf(ContentBlock.Text(
                "Why is the model emitting <invoke name=\"x\">…</invoke>?"
            )),
        ))

        assertEquals(1, h.getApiConversationHistory().size)
        assertFalse(h.consumeDialectDriftFlag())
    }

    // ── Layer 2: one-pass cleanup ─────────────────────────────────────────

    @Test
    fun `redactDialectXmlInHistory rewrites contaminated assistant turns`() = runTest {
        val h = handler()
        // Seed contaminated history directly via the overwrite path so we
        // exercise the cleanup function on legacy data that pre-dates the
        // write-time guard.
        h.overwriteApiConversationHistory(listOf(
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("Find auth code"))),
            ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text(
                "Searching now.\n\n<invoke name=\"search_code\">\n<parameter name=\"pattern\">JWT</parameter>\n</invoke>"
            ))),
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("continue"))),
            ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text(
                "Now reading.\n\n<tool_call>{\"tool_name\":\"read_file\",\"parameters\":{\"path\":\"a.kt\"}}</tool_call>"
            ))),
        ))

        val rewritten = h.redactDialectXmlInHistory()

        assertEquals(2, rewritten, "Both contaminated assistant turns must be rewritten")
        assertTrue(h.consumeDialectDriftFlag(), "Cleanup must raise the drift flag")

        // Verify the actual content was rewritten with the marker
        val history = h.getApiConversationHistory()
        val assistantTexts = history.filter { it.role == ApiRole.ASSISTANT }
            .flatMap { it.content }
            .filterIsInstance<ContentBlock.Text>()
            .map { it.text }
        assertTrue(assistantTexts.all { it.contains(DialectDriftDetector.REDACTION_MARKER) })
        assertTrue(assistantTexts.none { it.contains("<invoke") })
        assertTrue(assistantTexts.none { it.contains("<tool_call>") })
        // Surrounding prose must survive
        assertTrue(assistantTexts[0].contains("Searching now."))
        assertTrue(assistantTexts[1].contains("Now reading."))
    }

    @Test
    fun `redactDialectXmlInHistory leaves clean assistant turns alone`() = runTest {
        val h = handler()
        h.overwriteApiConversationHistory(listOf(
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("hi"))),
            ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("Hello, how can I help?"))),
        ))

        val rewritten = h.redactDialectXmlInHistory()

        assertEquals(0, rewritten)
        assertFalse(h.consumeDialectDriftFlag(), "Flag must NOT be raised when no rewrites happened")
        assertEquals(2, h.getApiConversationHistory().size)
    }

    @Test
    fun `redactDialectXmlInHistory leaves user turns alone even if they mention dialect text`() = runTest {
        val h = handler()
        h.overwriteApiConversationHistory(listOf(
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text(
                "Look at this: <invoke name=\"foo\"></invoke>"
            ))),
            ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("Got it."))),
        ))

        val rewritten = h.redactDialectXmlInHistory()

        assertEquals(0, rewritten, "User turns must never be redacted — only assistant turns are the contamination vector")
        val userText = (h.getApiConversationHistory()[0].content[0] as ContentBlock.Text).text
        assertTrue(userText.contains("<invoke name=\"foo\">"), "User content preserved verbatim")
    }

    @Test
    fun `redactDialectXmlInHistory is idempotent`() = runTest {
        val h = handler()
        h.overwriteApiConversationHistory(listOf(
            ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text(
                "<invoke name=\"x\"><parameter name=\"y\">v</parameter></invoke>"
            ))),
        ))

        val first = h.redactDialectXmlInHistory()
        h.consumeDialectDriftFlag() // drain
        val second = h.redactDialectXmlInHistory()

        assertEquals(1, first)
        assertEquals(0, second, "Second pass over already-redacted history must be a no-op")
        assertFalse(h.consumeDialectDriftFlag(), "Flag must NOT be raised on the no-op pass")
    }

    // ── consumeDialectDriftFlag — one-shot semantics ─────────────────────

    @Test
    fun `consumeDialectDriftFlag is single-shot — first call returns true then false`() = runTest {
        val h = handler()
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text(
                "<invoke name=\"read_file\"><parameter name=\"path\">a</parameter></invoke>"
            )),
        ))

        assertTrue(h.consumeDialectDriftFlag(), "First consume must return true after drift was detected")
        assertFalse(h.consumeDialectDriftFlag(), "Second consume must return false — flag is one-shot")
        assertFalse(h.consumeDialectDriftFlag(), "Subsequent consumes stay false until next detection")
    }

    @Test
    fun `flag is independent across detection events`() = runTest {
        val h = handler()

        // Event 1
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text("<invoke name=\"a\"></invoke>")),
        ))
        assertTrue(h.consumeDialectDriftFlag())

        // Event 2 — fresh detection
        h.addToApiConversationHistory(ApiMessage(
            role = ApiRole.ASSISTANT,
            content = listOf(ContentBlock.Text("<tool_call>{\"name\":\"x\"}</tool_call>")),
        ))
        assertTrue(h.consumeDialectDriftFlag(), "New detection must re-arm the flag")
    }
}
