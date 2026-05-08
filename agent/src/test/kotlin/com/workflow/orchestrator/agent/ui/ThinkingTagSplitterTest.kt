package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.ui.ThinkingTagSplitter.Part
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ThinkingTagSplitter: routes <thinking>...</thinking> separately from prose")
class ThinkingTagSplitterTest {

    private val splitter = ThinkingTagSplitter()

    private fun feed(vararg chunks: String): List<Part> {
        val out = mutableListOf<Part>()
        for (c in chunks) out += splitter.consume(c)
        out += splitter.flush()
        return out
    }

    private fun List<Part>.text(): String =
        filterIsInstance<Part.Text>().joinToString("") { it.text }

    private fun List<Part>.thinking(): List<String> =
        filterIsInstance<Part.Thinking>().map { it.text }

    @Test
    fun `passthrough when no thinking tags`() {
        val parts = feed("Hello, ", "world!")
        assertEquals("Hello, world!", parts.text())
        assertTrue(parts.thinking().isEmpty())
    }

    @Test
    fun `single complete thinking block in one chunk`() {
        val parts = feed("before <thinking>secret</thinking> after")
        assertEquals("before  after", parts.text())
        assertEquals(listOf("secret"), parts.thinking())
    }

    @Test
    fun `open tag split across two chunks`() {
        // Tag spans the chunk boundary: "<thi" + "nking>..."
        val parts = feed("intro <thi", "nking>ponder</thinking> tail")
        assertEquals("intro  tail", parts.text())
        assertEquals(listOf("ponder"), parts.thinking())
    }

    @Test
    fun `close tag split across two chunks`() {
        val parts = feed("<thinking>partial reasoning</thi", "nking>final")
        assertEquals("final", parts.text())
        assertEquals(listOf("partial reasoning"), parts.thinking())
    }

    @Test
    fun `multiple thinking blocks emitted in order`() {
        val parts = feed(
            "a<thinking>one</thinking>b<thinking>two</thinking>c"
        )
        assertEquals("abc", parts.text())
        assertEquals(listOf("one", "two"), parts.thinking())
    }

    @Test
    fun `thinking content split across many chunks accumulates into one block`() {
        val parts = feed(
            "<thinking>",
            "first part. ",
            "second part. ",
            "third part.",
            "</thinking>",
            "done"
        )
        assertEquals("done", parts.text())
        assertEquals(listOf("first part. second part. third part."), parts.thinking())
    }

    @Test
    fun `char-by-char streaming still parses correctly`() {
        val full = "before <thinking>x</thinking> after"
        val parts = mutableListOf<Part>()
        for (ch in full) parts += splitter.consume(ch.toString())
        parts += splitter.flush()
        assertEquals("before  after", parts.text())
        assertEquals(listOf("x"), parts.thinking())
    }

    @Test
    fun `unclosed thinking block emits on flush`() {
        // Stream ends mid-thinking — better to surface what we have than swallow it.
        val parts = feed("<thinking>cut off mid-sentence")
        assertEquals("", parts.text())
        assertEquals(listOf("cut off mid-sentence"), parts.thinking())
    }

    @Test
    fun `empty thinking block is suppressed`() {
        val parts = feed("<thinking></thinking>tail")
        assertEquals("tail", parts.text())
        assertTrue(parts.thinking().isEmpty())
    }

    @Test
    fun `false-start prefix collision does not eat real text`() {
        // "<thi" is a prefix of "<thinking>" — splitter must hold it back, then
        // when "s should be plain" rules out the match, emit "<this should be plain".
        val parts = feed("<thi", "s should be plain")
        assertEquals("<this should be plain", parts.text())
        assertTrue(parts.thinking().isEmpty())
    }

    @Test
    fun `partial-prefix tail flushed correctly on stream end`() {
        // Stream ends right at "<thi" — flush must emit it as text, not swallow.
        val parts = feed("intro <thi")
        assertEquals("intro <thi", parts.text())
        assertTrue(parts.thinking().isEmpty())
    }

    @Test
    fun `case mismatch falls through as plain text`() {
        // Per system prompt convention the LLM uses lowercase. Anything else
        // is treated as plain text — safer than aggressive matching.
        val parts = feed("<Thinking>nope</Thinking>")
        assertEquals("<Thinking>nope</Thinking>", parts.text())
        assertTrue(parts.thinking().isEmpty())
    }

    @Test
    fun `reset clears mid-stream state`() {
        splitter.consume("<thinking>aborted")
        splitter.reset()
        val parts = splitter.consume("clean prose") + splitter.flush()
        assertEquals("clean prose", parts.text())
        assertTrue(parts.thinking().isEmpty())
    }

    @Test
    fun `flush is idempotent and safe with no pending state`() {
        assertTrue(splitter.flush().isEmpty())
        assertTrue(splitter.flush().isEmpty())
    }

    @Test
    fun `consume of empty chunk is a no-op`() {
        assertTrue(splitter.consume("").isEmpty())
    }

    @Test
    fun `text and thinking parts arrive in order across the stream`() {
        // Order matters: webview rendering depends on the prose-thinking-prose
        // sequence being preserved.
        val parts = feed("A<thinking>1</thinking>B<thinking>2</thinking>C")
        // Text parts: "A", "B", "C" interleaved with two thinking blocks.
        assertEquals(
            listOf<Part>(
                Part.Text("A"),
                Part.Thinking("1"),
                Part.Text("B"),
                Part.Thinking("2"),
                Part.Text("C"),
            ),
            parts
        )
    }
}
