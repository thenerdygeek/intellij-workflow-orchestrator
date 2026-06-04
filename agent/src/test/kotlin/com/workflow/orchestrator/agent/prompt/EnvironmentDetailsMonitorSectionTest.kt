package com.workflow.orchestrator.agent.prompt

import com.workflow.orchestrator.agent.monitor.MonitorHandle
import com.workflow.orchestrator.agent.monitor.MonitorSource
import com.workflow.orchestrator.agent.monitor.MonitorEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvironmentDetailsMonitorSectionTest {
    private fun handle(id: String): MonitorHandle {
        val src = object : MonitorSource {
            override val monitorId = id; override val description = "tail build log"
            override fun start(emit: (MonitorEvent) -> Unit) {}; override fun stop() {}
        }
        return MonitorHandle(src, "s1", 0).apply { appendLine("ERROR something broke") }
    }

    @Test
    fun `empty list renders nothing`() {
        assertEquals("", EnvironmentDetailsBuilder.renderActiveMonitors(emptyList()))
    }

    @Test
    fun `active monitor shows label and recent line`() {
        val text = EnvironmentDetailsBuilder.renderActiveMonitors(listOf(handle("shell-1")))
        assertTrue(text.contains("shell-1"))
        assertTrue(text.contains("tail build log"))
        assertTrue(text.contains("ERROR something broke"))
    }
}
