package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LoopDetectorTest {

    private lateinit var detector: LoopDetector

    @BeforeEach
    fun setUp() {
        detector = LoopDetector()
    }

    // ---- Basic threshold behavior ----

    @Nested
    inner class ThresholdTests {

        @Test
        fun `returns OK for first tool call`() {
            val status = detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            assertEquals(LoopStatus.OK, status)
        }

        @Test
        fun `returns OK for 2 consecutive identical calls`() {
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            val status = detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            assertEquals(LoopStatus.OK, status)
        }

        @Test
        fun `returns SOFT_WARNING at 3 consecutive identical calls`() {
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            val status = detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            assertEquals(LoopStatus.SOFT_WARNING, status)
        }

        @Test
        fun `returns SOFT_WARNING at exactly 3 then OK-equivalent at 4`() {
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            assertEquals(LoopStatus.SOFT_WARNING, detector.recordToolCall("read_file", """{"path":"a.kt"}"""))
            // 4th call: between soft and hard thresholds — still SOFT_WARNING
            assertEquals(LoopStatus.SOFT_WARNING, detector.recordToolCall("read_file", """{"path":"a.kt"}"""))
        }

        @Test
        fun `returns HARD_LIMIT at 5 consecutive identical calls`() {
            repeat(4) {
                detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            }
            val status = detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            assertEquals(LoopStatus.HARD_LIMIT, status)
        }

        @Test
        fun `tracks count correctly through all thresholds`() {
            assertEquals(LoopStatus.OK, detector.recordToolCall("read_file", """{"path":"a.kt"}"""))
            assertEquals(1, detector.currentCount)

            assertEquals(LoopStatus.OK, detector.recordToolCall("read_file", """{"path":"a.kt"}"""))
            assertEquals(2, detector.currentCount)

            assertEquals(LoopStatus.SOFT_WARNING, detector.recordToolCall("read_file", """{"path":"a.kt"}"""))
            assertEquals(3, detector.currentCount)

            assertEquals(LoopStatus.SOFT_WARNING, detector.recordToolCall("read_file", """{"path":"a.kt"}"""))
            assertEquals(4, detector.currentCount)

            assertEquals(LoopStatus.HARD_LIMIT, detector.recordToolCall("read_file", """{"path":"a.kt"}"""))
            assertEquals(5, detector.currentCount)
        }
    }

    // ---- Different tool calls reset the count ----

    @Nested
    inner class DifferentCallTests {

        @Test
        fun `different tool names reset count`() {
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            // Different tool name resets
            val status = detector.recordToolCall("edit_file", """{"path":"a.kt"}""")
            assertEquals(LoopStatus.OK, status)
            assertEquals(1, detector.currentCount)
        }

        @Test
        fun `different arguments reset count`() {
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            // Same tool, different arguments
            val status = detector.recordToolCall("read_file", """{"path":"b.kt"}""")
            assertEquals(LoopStatus.OK, status)
            assertEquals(1, detector.currentCount)
        }

        @Test
        fun `interleaved different calls never trigger`() {
            // Alternating between two different calls
            for (i in 1..10) {
                val file = if (i % 2 == 0) "a.kt" else "b.kt"
                val status = detector.recordToolCall("read_file", """{"path":"$file"}""")
                assertEquals(LoopStatus.OK, status, "Interleaved calls at iteration $i should be OK")
            }
        }

        @Test
        fun `after different call, new identical sequence starts fresh`() {
            // 2 identical calls
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            // Break the sequence
            detector.recordToolCall("edit_file", """{"path":"x.kt"}""")
            // Start new identical sequence
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            val status = detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            assertEquals(LoopStatus.OK, status) // Only 2 consecutive, not 4
            assertEquals(2, detector.currentCount)
        }
    }

    // ---- Signature computation (parameter order independence) ----

    @Nested
    inner class SignatureTests {

        @Test
        fun `parameter order does not affect signature`() {
            val sig1 = LoopDetector.toolCallSignature("""{"path":"a.kt","line":10}""")
            val sig2 = LoopDetector.toolCallSignature("""{"line":10,"path":"a.kt"}""")
            assertEquals(sig1, sig2, "Signatures should be identical regardless of key order")
        }

        @Test
        fun `empty arguments produce consistent signature`() {
            val sig1 = LoopDetector.toolCallSignature("")
            val sig2 = LoopDetector.toolCallSignature("")
            assertEquals(sig1, sig2)
        }

        @Test
        fun `empty JSON object produces consistent signature`() {
            val sig1 = LoopDetector.toolCallSignature("{}")
            val sig2 = LoopDetector.toolCallSignature("{}")
            assertEquals(sig1, sig2)
        }

        @Test
        fun `invalid JSON used as raw string for comparison`() {
            // Unparseable JSON should still be compared by raw string
            val sig1 = LoopDetector.toolCallSignature("not json{{{")
            val sig2 = LoopDetector.toolCallSignature("not json{{{")
            assertEquals(sig1, sig2)
        }

        @Test
        fun `different values produce different signatures`() {
            val sig1 = LoopDetector.toolCallSignature("""{"path":"a.kt"}""")
            val sig2 = LoopDetector.toolCallSignature("""{"path":"b.kt"}""")
            assertNotEquals(sig1, sig2)
        }
    }

    // ---- Reset ----

    @Nested
    inner class ResetTests {

        @Test
        fun `reset clears all state`() {
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            assertEquals(2, detector.currentCount)

            detector.reset()
            assertEquals(0, detector.currentCount)

            // After reset, same call starts fresh
            val status = detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            assertEquals(LoopStatus.OK, status)
            assertEquals(1, detector.currentCount)
        }
    }

    // ---- Custom thresholds ----

    @Nested
    inner class CustomThresholdTests {

        @Test
        fun `custom soft threshold of 2`() {
            val customDetector = LoopDetector(softThreshold = 2, hardThreshold = 4)
            customDetector.recordToolCall("tool", """{}""")
            val status = customDetector.recordToolCall("tool", """{}""")
            assertEquals(LoopStatus.SOFT_WARNING, status)
        }

        @Test
        fun `custom hard threshold of 3`() {
            val customDetector = LoopDetector(softThreshold = 2, hardThreshold = 3)
            customDetector.recordToolCall("tool", """{}""")
            customDetector.recordToolCall("tool", """{}""")
            val status = customDetector.recordToolCall("tool", """{}""")
            assertEquals(LoopStatus.HARD_LIMIT, status)
        }
    }
}
