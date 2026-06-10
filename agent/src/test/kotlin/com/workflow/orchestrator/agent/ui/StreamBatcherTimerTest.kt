package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamBatcherTimerTest {

    @Test
    fun `append starts the timer without an EDT round-trip`() {
        val batcher = StreamBatcher(onFlush = {})
        batcher.append("chunk")
        // Synchronously observable — the old code needed an invokeLater to land first.
        assertTrue(batcher.isTimerRunning())
        batcher.dispose()
    }

    @Test
    fun `timer stops itself once the buffer drains (B12)`() {
        val batcher = StreamBatcher(onFlush = {}, intervalMs = 10)
        batcher.append("hello")
        // Simulate a timer tick (flush the data); the implementation stops the timer
        // on the same tick as drain once the buffer is empty.
        batcher.flushIfNeeded()
        assertFalse(batcher.isTimerRunning(), "timer must stop after draining, not tick at 60Hz forever")
        batcher.dispose()
    }

    @Test
    fun `append after self-stop restarts the timer`() {
        val batcher = StreamBatcher(onFlush = {}, intervalMs = 10)
        batcher.append("a")
        batcher.flushIfNeeded() // flush + self-stop (simulate timer tick)
        batcher.append("b")
        assertTrue(batcher.isTimerRunning())
        batcher.dispose()
    }

    @Test
    fun `flush and clear stop the timer`() {
        val b1 = StreamBatcher(onFlush = {})
        b1.append("x")
        b1.flush()
        assertFalse(b1.isTimerRunning())
        val b2 = StreamBatcher(onFlush = {})
        b2.append("x")
        b2.clear()
        assertFalse(b2.isTimerRunning())
        b1.dispose()
        b2.dispose()
    }
}
