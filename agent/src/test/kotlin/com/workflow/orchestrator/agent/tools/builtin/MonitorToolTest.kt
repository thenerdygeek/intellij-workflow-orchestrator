package com.workflow.orchestrator.agent.tools.builtin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorToolTest {
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
