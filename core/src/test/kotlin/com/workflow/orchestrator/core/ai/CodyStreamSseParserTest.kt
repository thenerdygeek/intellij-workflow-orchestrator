package com.workflow.orchestrator.core.ai

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * Unit tests for [CodyStreamSseParser].
 *
 * Multimodal-agent Phase 3 — covers all three end-of-stream signals (per spec
 * §Wire formats > Format B), the two frame shapes (`deltaText` incremental and
 * `completion` cumulative), and defensive handling of malformed JSON frames.
 *
 * Spec: `docs/research/2026-05-02-multimodal-agent-design.md` §Wire formats > Format B
 */
class CodyStreamSseParserTest {

    private val parser = CodyStreamSseParser()

    private fun reader(s: String) = BufferedReader(StringReader(s))

    @Test
    fun `accumulates deltaText across multiple completion frames`() = runBlocking {
        val sse = "event: completion\n" +
            "data: {\"deltaText\":\"hello\"}\n" +
            "\n" +
            "event: completion\n" +
            "data: {\"deltaText\":\" world\"}\n" +
            "\n" +
            "event: done\n" +
            "data: {}\n" +
            "\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        val text = parts.filterIsInstance<CodyStreamSseParser.ParseResult.TextDelta>()
            .joinToString("") { it.text }
        assertEquals("hello world", text)
        assertTrue(parts.any { it is CodyStreamSseParser.ParseResult.StreamDone })
    }

    @Test
    fun `terminates on event done before connection close`() = runBlocking {
        val sse = "event: completion\ndata: {\"deltaText\":\"x\"}\n\nevent: done\ndata: {}\n\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        assertTrue(parts.last() is CodyStreamSseParser.ParseResult.StreamDone)
    }

    @Test
    fun `terminates on data DONE sentinel`() = runBlocking {
        val sse = "event: completion\ndata: {\"deltaText\":\"x\"}\n\ndata: [DONE]\n\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        assertTrue(parts.last() is CodyStreamSseParser.ParseResult.StreamDone)
    }

    @Test
    fun `terminates on stream EOF as last fallback`() = runBlocking {
        val sse = "event: completion\ndata: {\"deltaText\":\"x\"}\n\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        assertTrue(parts.any { it is CodyStreamSseParser.ParseResult.TextDelta })
        // EOF reached without explicit done — that's still a valid terminator.
    }

    @Test
    fun `cumulative completion field emits TextReplacement, not TextDelta`() = runBlocking {
        val sse = "event: completion\n" +
            "data: {\"completion\":\"first\"}\n" +
            "\n" +
            "event: completion\n" +
            "data: {\"completion\":\"first second\"}\n" +
            "\n" +
            "event: done\n" +
            "data: {}\n" +
            "\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        val replacements = parts.filterIsInstance<CodyStreamSseParser.ParseResult.TextReplacement>()
        val deltas = parts.filterIsInstance<CodyStreamSseParser.ParseResult.TextDelta>()
        assertEquals(2, replacements.size)
        assertEquals(0, deltas.size)
        assertEquals("first second", replacements.last().text)
    }

    @Test
    fun `ignores malformed JSON frames without crashing`() = runBlocking {
        val sse = "event: completion\n" +
            "data: {not valid json\n" +
            "\n" +
            "event: completion\n" +
            "data: {\"deltaText\":\"recovered\"}\n" +
            "\n" +
            "event: done\n" +
            "data: {}\n" +
            "\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        val text = parts.filterIsInstance<CodyStreamSseParser.ParseResult.TextDelta>()
            .joinToString("") { it.text }
        assertEquals("recovered", text)
    }
}
