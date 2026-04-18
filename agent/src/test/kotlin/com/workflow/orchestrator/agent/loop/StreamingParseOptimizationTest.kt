package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.AssistantMessageContent
import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.TextContent
import com.workflow.orchestrator.core.ai.ToolUseContent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StreamingParseOptimizationTest {

    private fun simulateOptimizedStreaming(
        chunks: List<String>,
        toolNames: Set<String> = emptySet(),
        paramNames: Set<String> = emptySet(),
    ): Pair<String, Int> {
        val accumulated = StringBuilder()
        var cachedBlocks: List<AssistantMessageContent>? = null
        var cachedStrippedText = ""
        var lastPresentedLength = 0
        var parseCount = 0
        val outputChunks = mutableListOf<String>()

        for (chunk in chunks) {
            accumulated.append(chunk)
            val needsParse = cachedBlocks == null || chunk.contains('<') || chunk.contains('>')
            val blocks = if (needsParse) {
                parseCount++
                AssistantMessageParser.parse(accumulated.toString(), toolNames, paramNames)
                    .also { cachedBlocks = it }
            } else {
                cachedBlocks!!
            }
            val visibleText = blocks.filterIsInstance<TextContent>()
                .joinToString("\n\n") { it.content }

            // FIXED: on the skip-parse path, don't append tool-param content to the
            // display buffer. A partial ToolUseContent in cachedBlocks means we're
            // inside a tool call — `chunk` is a parameter value, not visible text.
            val stripped = if (needsParse) {
                val hasToolCalls = blocks.any { it is ToolUseContent }
                val base = if (hasToolCalls) visibleText else accumulated.toString()
                AssistantMessageParser.stripPartialTag(base).also { cachedStrippedText = it }
            } else {
                val hasPendingTool = cachedBlocks?.any { it is ToolUseContent && it.partial } == true
                if (hasPendingTool) {
                    cachedStrippedText  // tool param in flight — don't leak to display
                } else {
                    (cachedStrippedText + chunk).also { cachedStrippedText = it }
                }
            }
            if (stripped.length > lastPresentedLength) {
                outputChunks.add(stripped.substring(lastPresentedLength))
                lastPresentedLength = stripped.length
            } else if (needsParse && stripped.length < lastPresentedLength) {
                // Safety reset: stale watermark from leaked skip-parse content.
                lastPresentedLength = stripped.length
            }
        }
        return outputChunks.joinToString("") to parseCount
    }

    private fun simulateFullReparse(
        chunks: List<String>,
        toolNames: Set<String> = emptySet(),
        paramNames: Set<String> = emptySet(),
    ): String {
        val accumulated = StringBuilder()
        var lastPresentedLength = 0
        val outputChunks = mutableListOf<String>()
        for (chunk in chunks) {
            accumulated.append(chunk)
            val blocks = AssistantMessageParser.parse(accumulated.toString(), toolNames, paramNames)
            val visibleText = blocks.filterIsInstance<TextContent>()
                .joinToString("\n\n") { it.content }
            val stripped = AssistantMessageParser.stripPartialTag(visibleText)
            if (stripped.length > lastPresentedLength) {
                outputChunks.add(stripped.substring(lastPresentedLength))
                lastPresentedLength = stripped.length
            }
        }
        return outputChunks.joinToString("")
    }

    @Test
    fun `pure text streaming produces correct output`() {
        val chunks = listOf("Hello ", "world, ", "how ", "are ", "you?")
        val (output, parseCount) = simulateOptimizedStreaming(chunks)
        assertEquals("Hello world, how are you?", output)
        assertEquals(1, parseCount, "Should parse only on first chunk")
    }

    @Test
    fun `pure text matches full reparse output`() {
        // With the whitespace fix, optimized and full reparse produce identical output
        // even when chunks have trailing whitespace
        val chunks = listOf("Hello ", "world, ", "this is a ", "test.")
        val (optimized, _) = simulateOptimizedStreaming(chunks)
        val full = simulateFullReparse(chunks)
        assertEquals(full, optimized)
    }

    @Test
    fun `300 plain text chunks parse only once`() {
        val chunks = (1..300).map { "chunk$it " }
        val (output, parseCount) = simulateOptimizedStreaming(chunks)
        assertEquals(1, parseCount, "300 plain text chunks should parse exactly once")
        assertTrue(output.startsWith("chunk1 chunk2 "))
        assertTrue(output.endsWith("chunk300 "))
    }

    @Test
    fun `chunk with angle bracket triggers reparse`() {
        val chunks = listOf("Hello ", "x < y ", "world")
        val (_, parseCount) = simulateOptimizedStreaming(chunks)
        assertTrue(parseCount >= 2, "Chunk with '<' should trigger reparse")
    }

    @Test
    fun `angle bracket chunks trigger reparse`() {
        // Chunks containing '<' or '>' trigger reparse; text content is recovered
        val chunks = listOf("Hello ", "x < y> ", "done")
        val (output, parseCount) = simulateOptimizedStreaming(chunks)
        assertTrue(parseCount >= 2, "Both '<' and '>' chunks should trigger reparse")
        assertTrue(output.contains("Hello"))
        assertTrue(output.contains("x < y>"))
        assertTrue(output.contains("done"))
    }

    @Test
    fun `tool call in middle is parsed correctly`() {
        val toolNames = setOf("read_file")
        val paramNames = setOf("path")
        val chunks = listOf(
            "Let me read ",
            "the file. ",
            "<read_file>",
            "<path>/src/main.kt</path>",
            "</read_file>",
        )
        val (_, parseCount) = simulateOptimizedStreaming(chunks, toolNames, paramNames)
        assertTrue(parseCount >= 3, "XML chunks should trigger re-parse")
    }

    @Test
    fun `all text response emits all text`() {
        val chunks = listOf("Here is the answer: ", "42. ", "That is all.")
        val (output, _) = simulateOptimizedStreaming(chunks)
        assertEquals("Here is the answer: 42. That is all.", output)
    }

    @Test
    fun `single chunk produces correct output`() {
        val chunks = listOf("Hello world!")
        val (output, parseCount) = simulateOptimizedStreaming(chunks)
        assertEquals("Hello world!", output)
        assertEquals(1, parseCount, "Single chunk should parse exactly once")
    }

    @Test
    fun `empty chunks are handled gracefully`() {
        val chunks = listOf("Hello", "", " world")
        val (output, parseCount) = simulateOptimizedStreaming(chunks)
        assertEquals("Hello world", output)
        assertEquals(1, parseCount, "No angle brackets means parse only once")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Regression: "stops abruptly in between" — skip-parse path leaked
    // tool-param content into cachedStrippedText, inflating lastPresentedLength
    // past the actual visible-text length, making all subsequent text invisible.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `text after tool call is visible — no stops-abruptly regression`() {
        // THE KEY REGRESSION: pre-fix, "Based on the file..." was never shown
        // because lastPresentedLength was inflated by the param value "src/main.kt".
        val toolNames = setOf("read_file")
        val paramNames = setOf("path")
        val chunks = listOf(
            "Let me read the file.",      // plain text — shown
            "<read_file>",                // tool open — parse triggered
            "<path>",                     // param open — parse triggered
            "src/main.kt",               // param VALUE — no <>, skip-parse path
            "</path>",                   // param close — parse triggered
            "</read_file>",              // tool close — parse triggered
            " Based on the file,",       // post-tool text — MUST be visible
            " the answer is 42.",        // continuation
        )
        val (output, _) = simulateOptimizedStreaming(chunks, toolNames, paramNames)

        assertFalse(
            output.contains("src/main.kt"),
            "Tool parameter value must NOT appear in the stream display"
        )
        assertTrue(
            output.contains("Let me read the file"),
            "Pre-tool text must be shown"
        )
        assertTrue(
            output.contains("Based on the file") && output.contains("the answer is 42"),
            "Post-tool text must be shown — was invisible before the fix. Output was: '$output'"
        )
    }

    @Test
    fun `param content with no angle brackets does not inflate lastPresentedLength`() {
        // Verify the specific mechanism: the skip-parse path must not add param bytes
        // to the display buffer when a partial tool call is in progress.
        val toolNames = setOf("edit_file")
        val paramNames = setOf("path", "old_string", "new_string")
        val chunks = listOf(
            "Editing the file.",
            "<edit_file><path>",
            "src/Foo.kt",            // param value — must NOT appear in output
            "</path><old_string>",
            "old code",              // param value — must NOT appear in output
            "</old_string><new_string>",
            "new code",              // param value — must NOT appear in output
            "</new_string></edit_file>",
            " Done editing.",        // post-tool — must appear
        )
        val (output, _) = simulateOptimizedStreaming(chunks, toolNames, paramNames)

        assertFalse(output.contains("src/Foo.kt"), "Path param must not leak to display")
        assertFalse(output.contains("old code"), "old_string param must not leak to display")
        assertFalse(output.contains("new code"), "new_string param must not leak to display")
        assertTrue(output.contains("Editing the file"), "Pre-tool text must show")
        assertTrue(output.contains("Done editing"), "Post-tool text must show — was invisible before fix")
    }

    @Test
    fun `two consecutive tool calls — text between them is visible`() {
        val toolNames = setOf("read_file")
        val paramNames = setOf("path")
        val chunks = listOf(
            "First: ",
            "<read_file><path>",
            "foo.kt",
            "</path></read_file>",
            " then second: ",         // text between tools — must be visible
            "<read_file><path>",
            "bar.kt",
            "</path></read_file>",
            " all done.",             // text after second tool
        )
        val (output, _) = simulateOptimizedStreaming(chunks, toolNames, paramNames)
        assertTrue(output.contains("First"), "First pre-tool text must show")
        assertTrue(output.contains("then second"), "Text between tools must show")
        assertTrue(output.contains("all done"), "Post-tool text must show")
        assertFalse(output.contains("foo.kt"), "First param must not leak")
        assertFalse(output.contains("bar.kt"), "Second param must not leak")
    }
}
