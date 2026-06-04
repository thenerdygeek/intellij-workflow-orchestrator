package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.monitor.MonitorEvent
import com.workflow.orchestrator.agent.monitor.MonitorHandle
import com.workflow.orchestrator.agent.monitor.MonitorSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorToolTest {

    private fun tool() = MonitorTool(sessionIdProvider = { "s1" }, cs = CoroutineScope(SupervisorJob()))

    private fun handleWith(id: String, label: String, vararg lines: String): MonitorHandle {
        val src = object : MonitorSource {
            override val monitorId = id
            override val description = label
            override fun start(emit: (MonitorEvent) -> Unit) {}
            override fun stop() {}
        }
        return MonitorHandle(src, sessionId = "s1", startedAt = 0L).also { h -> lines.forEach { h.appendLine(it) } }
    }

    @Test
    fun `renderStatus shows id label state and buffered event lines`() {
        val s = MonitorTool.renderStatus(handleWith("shell-abc", "watch build", "line1 ERROR", "line2 done"))
        assertTrue(s.contains("shell-abc"), "should show id: $s")
        assertTrue(s.contains("watch build"), "should show label: $s")
        assertTrue(s.contains("RUNNING"), "should show state: $s")
        assertTrue(s.contains("line1 ERROR"), "should show buffered events: $s")
        assertTrue(s.contains("line2 done"), "should show buffered events: $s")
    }

    @Test
    fun `renderStatus notes when no events have matched yet`() {
        val s = MonitorTool.renderStatus(handleWith("shell-xyz", "watch"))
        assertTrue(s.contains("shell-xyz"), "should show id: $s")
        assertTrue(s.contains("no matching events", ignoreCase = true), "should note empty buffer: $s")
    }

    @Test
    fun `status is an allowed action value in the schema`() {
        val enum = tool().parameters.properties["action"]?.enumValues
        assertTrue(enum != null && "status" in enum, "action enum should include 'status', was: $enum")
    }

    @Test
    fun `filter description documents case sensitivity`() {
        val desc = tool().parameters.properties["filter"]?.description ?: ""
        assertTrue(desc.contains("(?i)"), "filter desc should document case-insensitive (?i) prefix: $desc")
    }

    @Test
    fun `start requires command and filter for shell source`() {
        val err = MonitorTool.validateStart(source = "shell", command = null, filter = "ERROR")
        assertTrue(err!!.contains("command"))
    }

    @Test
    fun `start rejects an unknown source in phase 1`() {
        val err = MonitorTool.validateStart(source = "bamboo", command = "x", filter = "y")
        assertTrue(err!!.contains("not supported"))
    }

    @Test
    fun `valid shell start passes validation`() {
        assertEquals(null, MonitorTool.validateStart(source = "shell", command = "tail -f log", filter = "ERROR"))
    }

    @Test
    fun `invalid regex filter is reported`() {
        val err = MonitorTool.validateStart(source = "shell", command = "tail -f log", filter = "(")
        assertTrue(err!!.contains("regex"))
    }
}
