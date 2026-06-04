package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Time-stamp scheme: user messages always carry a full datetime (retained); tool results
 * carry one only when the wall-clock minute has advanced since the last stamp.
 */
class ContextManagerTimeStampTest {

    private fun cmWithClock(clock: () -> Long): ContextManager =
        ContextManager(maxInputTokens = 100_000).apply { clockMillis = clock }

    /** Epoch millis for minute index [minute] + [sec] seconds. */
    private fun millis(minute: Long, sec: Int) = minute * 60_000L + sec * 1000L

    @Test
    fun `user message always produces a full datetime stamp`() {
        var t = millis(100, 34)
        val cm = cmWithClock { t }
        assertTrue(cm.userTimeStamp().startsWith("Current time:"), "user stamp must be the full datetime line")
        // A tool result within the same minute must NOT re-stamp.
        t = millis(100, 45)
        assertNull(cm.toolResultTimeStampOrNull(), "same-minute tool result must not be stamped")
    }

    @Test
    fun `tool result stamps only when the wall-clock minute advances`() {
        // Mirrors the spec example: user 01:10:34, tool 01:10:45 (skip), tool 01:11:04 (stamp).
        var t = millis(100, 34)
        val cm = cmWithClock { t }
        cm.userTimeStamp()                                                  // baseline minute 100

        t = millis(100, 45); assertNull(cm.toolResultTimeStampOrNull())     // :10 → skip
        t = millis(101, 4);  assertNotNull(cm.toolResultTimeStampOrNull())  // :11 → stamp
        t = millis(101, 50); assertNull(cm.toolResultTimeStampOrNull())     // :11 → skip
        t = millis(102, 1);  assertNotNull(cm.toolResultTimeStampOrNull())  // :12 → stamp
    }

    @Test
    fun `a fresh user message re-anchors the baseline minute`() {
        var t = millis(200, 10)
        val cm = cmWithClock { t }
        cm.userTimeStamp()                                                  // baseline 200
        t = millis(201, 0); assertNotNull(cm.toolResultTimeStampOrNull())   // advanced → stamp, baseline 201
        // New user message at minute 205 re-anchors; a tool result in 205 must then skip.
        t = millis(205, 30); cm.userTimeStamp()                             // baseline 205
        t = millis(205, 59); assertNull(cm.toolResultTimeStampOrNull())     // same minute → skip
    }
}
