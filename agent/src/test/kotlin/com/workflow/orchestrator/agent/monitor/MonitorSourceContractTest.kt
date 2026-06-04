package com.workflow.orchestrator.agent.monitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonitorSourceContractTest {
    private class FakeSource(override val monitorId: String) : MonitorSource {
        override val description = "fake"
        var started = false; var stopped = false
        lateinit var sink: (MonitorEvent) -> Unit
        override fun start(emit: (MonitorEvent) -> Unit) { started = true; sink = emit }
        override fun stop() { stopped = true }
        fun fire(line: String) = sink(MonitorEvent(monitorId, Severity.NOTABLE, line))
    }

    @Test
    fun `start wires the emit sink and stop flips state`() {
        val seen = mutableListOf<MonitorEvent>()
        val s = FakeSource("m1")
        s.start { seen += it }
        s.fire("hello")
        s.stop()
        assertEquals(true, s.started)
        assertEquals(true, s.stopped)
        assertEquals(listOf("hello"), seen.map { it.line })
    }
}
