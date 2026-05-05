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

    /**
     * F1 followup (Phase 6) — `stopReason` was previously parsed but never
     * surfaced. Verifies a frame carrying both text + stopReason emits BOTH
     * results, and the StopReason variant fires last so callers can update
     * their accumulator before reading the termination cause.
     */
    @Test
    fun `surfaces StopReason when frame carries one`() = runBlocking {
        val sse = "event: completion\n" +
            "data: {\"deltaText\":\"final \"}\n\n" +
            "event: completion\n" +
            "data: {\"deltaText\":\"chunk\",\"stopReason\":\"end_turn\"}\n\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        val text = parts.filterIsInstance<CodyStreamSseParser.ParseResult.TextDelta>()
            .joinToString("") { it.text }
        assertEquals("final chunk", text)
        val stopReason = parts.filterIsInstance<CodyStreamSseParser.ParseResult.StopReason>()
        assertEquals(1, stopReason.size)
        assertEquals("end_turn", stopReason.first().reason)
        // Order matters: TextDelta before StopReason within the same frame so
        // the accumulator is up-to-date when termination fires.
        val textIdx = parts.indexOfLast { it is CodyStreamSseParser.ParseResult.TextDelta }
        val stopIdx = parts.indexOfLast { it is CodyStreamSseParser.ParseResult.StopReason }
        assertTrue(textIdx < stopIdx, "StopReason must be emitted AFTER text from the same frame")
    }

    @Test
    fun `surfaces StopReason from frames with no text payload`() = runBlocking {
        val sse = "event: completion\n" +
            "data: {\"deltaText\":\"hello\"}\n\n" +
            "event: completion\n" +
            "data: {\"stopReason\":\"length\"}\n\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        val stopReason = parts.filterIsInstance<CodyStreamSseParser.ParseResult.StopReason>()
        assertEquals(1, stopReason.size)
        assertEquals("length", stopReason.first().reason)
    }

    // --- event: error frames (gateway rejection visibility) ----------------
    // format_lab probe (2026-05-05) showed Sourcegraph emits HTTP 200 + an SSE
    // `event: error` frame for unsupported MIMEs (HEIC/HEIF/BMP/TIFF/AVIF/SVG)
    // and unsupported document shapes — 58 of 96 cells in that pattern. Without
    // the parser surfacing this, callers see an empty assistant bubble.

    @Test
    fun `surfaces gateway error frame as ParseResult Error with structured message`() = runBlocking {
        val sse = "event: error\n" +
            "data: {\"error\":\"media type image/heic not supported\"}\n\n" +
            "event: done\n" +
            "data: {}\n\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        val errors = parts.filterIsInstance<CodyStreamSseParser.ParseResult.Error>()
        assertEquals(1, errors.size)
        assertEquals("media type image/heic not supported", errors.first().message)
        assertTrue(parts.any { it is CodyStreamSseParser.ParseResult.StreamDone },
            "Stream must still terminate via done event after an error frame")
    }

    @Test
    fun `extracts message field as fallback when error envelope uses message instead of error`() = runBlocking {
        val sse = "event: error\n" +
            "data: {\"message\":\"unsupported document type\"}\n\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        val errors = parts.filterIsInstance<CodyStreamSseParser.ParseResult.Error>()
        assertEquals(1, errors.size)
        assertEquals("unsupported document type", errors.first().message)
    }

    @Test
    fun `falls back to raw payload when error data is free-form text`() = runBlocking {
        val sse = "event: error\n" +
            "data: rate limit exceeded\n\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        val errors = parts.filterIsInstance<CodyStreamSseParser.ParseResult.Error>()
        assertEquals(1, errors.size)
        assertEquals("rate limit exceeded", errors.first().message)
    }

    // --- delta_tool_calls frames (Sourcegraph forwards tool calls on /stream
    //     at api-version=9 — verified by format_lab 2026-05-05) -------------

    @Test
    fun `surfaces delta_tool_calls frames as ToolCallDelta with id and name`() = runBlocking {
        val sse = "event: completion\n" +
            "data: {\"delta_tool_calls\":[{\"id\":\"toolu_01\",\"type\":\"function\"," +
            "\"function\":{\"name\":\"must_call_this\",\"arguments\":\"\"}}]}\n\n" +
            "event: done\ndata: {}\n\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        val toolDeltas = parts.filterIsInstance<CodyStreamSseParser.ParseResult.ToolCallDelta>()
        assertEquals(1, toolDeltas.size)
        val deltas = toolDeltas.first().deltas
        assertEquals(1, deltas.size)
        assertEquals("toolu_01", deltas.first().id)
        assertEquals("function", deltas.first().type)
        assertEquals("must_call_this", deltas.first().function?.name)
    }

    @Test
    fun `surfaces continuation frames with empty id and incremental arguments`() = runBlocking {
        // Continuation pattern observed from Haiku 4.5: first frame has id+name,
        // subsequent frames have empty strings for both and append to arguments.
        val sse = "event: completion\n" +
            "data: {\"delta_tool_calls\":[{\"id\":\"\",\"type\":\"function\"," +
            "\"function\":{\"name\":\"\",\"arguments\":\"{\\\"\"}}]}\n\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        val deltas = parts.filterIsInstance<CodyStreamSseParser.ParseResult.ToolCallDelta>()
        assertEquals(1, deltas.size)
        assertEquals("", deltas.first().deltas.first().id)
        assertEquals("{\"", deltas.first().deltas.first().function?.arguments)
    }

    @Test
    fun `frame with both deltaText and delta_tool_calls emits both`() = runBlocking {
        // Defensive: the wire spec doesn't forbid a frame from carrying text
        // AND a tool delta; the parser must surface both so callers can
        // accumulate independently.
        val sse = "event: completion\n" +
            "data: {\"deltaText\":\"thinking\"," +
            "\"delta_tool_calls\":[{\"id\":\"x\",\"type\":\"function\"," +
            "\"function\":{\"name\":\"f\",\"arguments\":\"\"}}]}\n\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        assertTrue(parts.any { it is CodyStreamSseParser.ParseResult.TextDelta })
        assertTrue(parts.any { it is CodyStreamSseParser.ParseResult.ToolCallDelta })
    }

    @Test
    fun `error frame state does not leak into subsequent normal frames`() = runBlocking {
        // Hypothetical: gateway emits an error then continues with normal text
        // (we haven't observed this in practice but the parser must not
        // mis-classify the next data: as another error).
        val sse = "event: error\n" +
            "data: {\"error\":\"transient\"}\n\n" +
            "event: completion\n" +
            "data: {\"deltaText\":\"recovered\"}\n\n" +
            "event: done\n" +
            "data: {}\n\n"
        val parts = mutableListOf<CodyStreamSseParser.ParseResult>()
        parser.parse(reader(sse)) { parts.add(it) }
        assertEquals(1, parts.filterIsInstance<CodyStreamSseParser.ParseResult.Error>().size)
        val text = parts.filterIsInstance<CodyStreamSseParser.ParseResult.TextDelta>()
            .joinToString("") { it.text }
        assertEquals("recovered", text)
    }
}
