package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.ui.ThinkingTagSplitter.Part
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ThinkingTagSplitter: streams <thinking>...</thinking> deltas + end-of-block markers")
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

    /** Concatenate ThinkingDelta payloads in order — yields the same per-block strings the prior contract used. */
    private fun List<Part>.thinkingBlocks(): List<String> {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        for (p in this) when (p) {
            is Part.ThinkingDelta -> current.append(p.text)
            is Part.ThinkingEnd -> {
                if (current.isNotEmpty()) {
                    blocks.add(current.toString())
                    current.setLength(0)
                }
            }
            else -> Unit
        }
        // Trailing deltas without an End (e.g. unclosed at flush-time) — surface
        // them so tests can assert on partial blocks.
        if (current.isNotEmpty()) blocks.add(current.toString())
        return blocks
    }

    @Test
    fun `passthrough when no thinking tags`() {
        val parts = feed("Hello, ", "world!")
        assertEquals("Hello, world!", parts.text())
        assertTrue(parts.thinkingBlocks().isEmpty())
    }

    @Test
    fun `single complete thinking block in one chunk`() {
        val parts = feed("before <thinking>secret</thinking> after")
        assertEquals("before  after", parts.text())
        assertEquals(listOf("secret"), parts.thinkingBlocks())
    }

    @Test
    fun `open tag split across two chunks`() {
        val parts = feed("intro <thi", "nking>ponder</thinking> tail")
        assertEquals("intro  tail", parts.text())
        assertEquals(listOf("ponder"), parts.thinkingBlocks())
    }

    @Test
    fun `close tag split across two chunks`() {
        val parts = feed("<thinking>partial reasoning</thi", "nking>final")
        assertEquals("final", parts.text())
        assertEquals(listOf("partial reasoning"), parts.thinkingBlocks())
    }

    @Test
    fun `multiple thinking blocks emitted in order`() {
        val parts = feed("a<thinking>one</thinking>b<thinking>two</thinking>c")
        assertEquals("abc", parts.text())
        assertEquals(listOf("one", "two"), parts.thinkingBlocks())
    }

    @Test
    fun `thinking content split across many chunks streams as separate deltas`() {
        val parts = feed(
            "<thinking>",
            "first part. ",
            "second part. ",
            "third part.",
            "</thinking>",
            "done",
        )
        assertEquals("done", parts.text())
        // Each chunk inside the block produces its own ThinkingDelta — the
        // webview sees the reasoning live as it accumulates, not in one burst.
        val deltas = parts.filterIsInstance<Part.ThinkingDelta>().map { it.text }
        assertEquals(listOf("first part. ", "second part. ", "third part."), deltas)
        // Exactly one ThinkingEnd marks the close of the (single) block.
        assertEquals(1, parts.filterIsInstance<Part.ThinkingEnd>().size)
    }

    @Test
    fun `char-by-char streaming still parses correctly`() {
        val full = "before <thinking>xyz</thinking> after"
        val parts = mutableListOf<Part>()
        for (ch in full) parts += splitter.consume(ch.toString())
        parts += splitter.flush()
        assertEquals("before  after", parts.text())
        assertEquals(listOf("xyz"), parts.thinkingBlocks())
        assertEquals(1, parts.filterIsInstance<Part.ThinkingEnd>().size)
    }

    @Test
    fun `unclosed thinking block emits delta plus end on flush`() {
        // Stream ends mid-thinking (LLM cut off or user cancelled). The
        // splitter must surface the partial reasoning AND emit `ThinkingEnd`
        // so the downstream consumer (chatStore.endThinking) can finalize the
        // streaming bubble into the message list — otherwise the live
        // `<ThinkingView isStreaming={true}>` would hang forever in the footer.
        val parts = feed("<thinking>cut off mid-sentence")
        assertEquals("", parts.text())
        assertEquals(listOf("cut off mid-sentence"), parts.thinkingBlocks())
        assertEquals(1, parts.filterIsInstance<Part.ThinkingEnd>().size)
    }

    @Test
    fun `empty thinking block suppresses end marker`() {
        // <thinking></thinking> with no content — splitter must not emit a
        // ThinkingEnd, otherwise the webview would render an empty bubble.
        val parts = feed("<thinking></thinking>tail")
        assertEquals("tail", parts.text())
        assertTrue(parts.thinkingBlocks().isEmpty())
        assertEquals(0, parts.filterIsInstance<Part.ThinkingEnd>().size)
    }

    @Test
    fun `false-start prefix collision does not eat real text`() {
        val parts = feed("<thi", "s should be plain")
        assertEquals("<this should be plain", parts.text())
        assertTrue(parts.thinkingBlocks().isEmpty())
    }

    @Test
    fun `partial-prefix tail flushed correctly on stream end`() {
        val parts = feed("intro <thi")
        assertEquals("intro <thi", parts.text())
        assertTrue(parts.thinkingBlocks().isEmpty())
    }

    @Test
    fun `case mismatch falls through as plain text`() {
        val parts = feed("<Thinking>nope</Thinking>")
        assertEquals("<Thinking>nope</Thinking>", parts.text())
        assertTrue(parts.thinkingBlocks().isEmpty())
    }

    @Test
    fun `reset clears mid-stream state`() {
        splitter.consume("<thinking>aborted")
        splitter.reset()
        val parts = splitter.consume("clean prose") + splitter.flush()
        assertEquals("clean prose", parts.text())
        assertTrue(parts.thinkingBlocks().isEmpty())
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
        assertEquals(
            listOf<Part>(
                Part.Text("A"),
                Part.ThinkingDelta("1"),
                Part.ThinkingEnd,
                Part.Text("B"),
                Part.ThinkingDelta("2"),
                Part.ThinkingEnd,
                Part.Text("C"),
            ),
            parts,
        )
    }

    @Test
    fun `consecutive thinking blocks each get their own end marker`() {
        // Regression guard: per-block `hasEmittedThinkingDelta` must reset on
        // each ThinkingEnd, otherwise the empty-block suppression would
        // accidentally fire on the second block too.
        val parts = feed("<thinking>a</thinking><thinking>b</thinking>")
        assertEquals(listOf("a", "b"), parts.thinkingBlocks())
        assertEquals(2, parts.filterIsInstance<Part.ThinkingEnd>().size)
    }
}
