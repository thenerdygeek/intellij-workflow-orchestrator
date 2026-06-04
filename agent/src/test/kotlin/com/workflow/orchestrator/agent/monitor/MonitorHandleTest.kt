package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.agent.tools.background.BackgroundState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorHandleTest {
    private fun fakeSource(id: String) = object : MonitorSource {
        override val monitorId = id; override val description = "fake"
        override fun start(emit: (MonitorEvent) -> Unit) {}
        override fun stop() {}
    }

    @Test
    fun `appendLine is visible via readOutput and bumps outputBytes`() {
        val h = MonitorHandle(fakeSource("m1"), sessionId = "s1", startedAt = 0)
        h.appendLine("first event")
        h.appendLine("second event")
        val chunk = h.readOutput(tailLines = 10)
        assertTrue(chunk.content.contains("first event"))
        assertTrue(chunk.content.contains("second event"))
        assertTrue(h.outputBytes() > 0)
    }

    @Test
    fun `state is RUNNING until kill then KILLED`() {
        val h = MonitorHandle(fakeSource("m1"), sessionId = "s1", startedAt = 0)
        assertEquals(BackgroundState.RUNNING, h.state())
        h.kill()
        assertEquals(BackgroundState.KILLED, h.state())
    }

    @Test
    fun `kill is idempotent — second call returns false and stops source once`() {
        var stops = 0
        val src = object : MonitorSource {
            override val monitorId = "m1"; override val description = "fake"
            override fun start(emit: (MonitorEvent) -> Unit) {}
            override fun stop() { stops++ }
        }
        val h = MonitorHandle(src, "s1", 0)
        assertEquals(true, h.kill())
        assertEquals(false, h.kill())
        assertEquals(1, stops)
    }

    @Test
    fun `ring buffer evicts oldest beyond maxLines`() {
        val src = object : MonitorSource {
            override val monitorId = "m1"; override val description = "fake"
            override fun start(emit: (MonitorEvent) -> Unit) {}; override fun stop() {}
        }
        val h = MonitorHandle(src, "s1", 0, maxLines = 3)
        listOf("a", "b", "c", "d").forEach { h.appendLine(it) }
        val content = h.readOutput().content
        assertEquals(false, content.contains("a"))   // evicted
        assertTrue(content.contains("b") && content.contains("c") && content.contains("d"))
    }
}
