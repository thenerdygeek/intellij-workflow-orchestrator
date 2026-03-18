package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AgentEventLogTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `log records events in memory`() {
        val log = AgentEventLog("session-1", tempDir.toString())

        log.log(AgentEventType.SESSION_STARTED, "Task: fix bug")
        log.log(AgentEventType.TOOL_CALLED, "read_file(test.kt)")
        log.log(AgentEventType.TOOL_SUCCEEDED, "read_file")
        log.log(AgentEventType.SESSION_COMPLETED, "Done in 3 iterations")

        val events = log.getEvents()
        assertEquals(4, events.size)
        assertEquals(AgentEventType.SESSION_STARTED, events[0].type)
        assertEquals("session-1", events[0].sessionId)
        assertEquals("Task: fix bug", events[0].detail)
        assertEquals(AgentEventType.SESSION_COMPLETED, events[3].type)
    }

    @Test
    fun `log persists to JSONL file`() {
        val log = AgentEventLog("session-persist", tempDir.toString())

        log.log(AgentEventType.SESSION_STARTED, "Test persistence")
        log.log(AgentEventType.TOOL_CALLED, "edit_file")

        val jsonlFile = File(tempDir.toFile(), ".workflow/agent/sessions/session-persist.jsonl")
        assertTrue(jsonlFile.exists(), "JSONL file should be created")

        val lines = jsonlFile.readLines()
        assertEquals(2, lines.size, "Should have 2 lines (one per event)")
        assertTrue(lines[0].contains("SESSION_STARTED"), "First line should be SESSION_STARTED")
        assertTrue(lines[1].contains("TOOL_CALLED"), "Second line should be TOOL_CALLED")
    }

    @Test
    fun `JSONL lines are valid JSON`() {
        val log = AgentEventLog("session-json", tempDir.toString())
        log.log(AgentEventType.EDIT_APPLIED, "UserService.kt")

        val jsonlFile = File(tempDir.toFile(), ".workflow/agent/sessions/session-json.jsonl")
        val line = jsonlFile.readLines().first()

        // Should parse as JSON
        val parsed = kotlinx.serialization.json.Json.decodeFromString<AgentEvent>(line)
        assertEquals("session-json", parsed.sessionId)
        assertEquals(AgentEventType.EDIT_APPLIED, parsed.type)
        assertEquals("UserService.kt", parsed.detail)
        assertTrue(parsed.timestamp > 0, "Timestamp should be set")
    }

    @Test
    fun `getEvents by type filters correctly`() {
        val log = AgentEventLog("session-filter", tempDir.toString())

        log.log(AgentEventType.SESSION_STARTED, "Start")
        log.log(AgentEventType.TOOL_CALLED, "read_file")
        log.log(AgentEventType.TOOL_SUCCEEDED, "read_file")
        log.log(AgentEventType.TOOL_CALLED, "edit_file")
        log.log(AgentEventType.TOOL_FAILED, "edit_file: syntax error")
        log.log(AgentEventType.SESSION_COMPLETED, "Done")

        val toolCalls = log.getEvents(AgentEventType.TOOL_CALLED)
        assertEquals(2, toolCalls.size)

        val failures = log.getEvents(AgentEventType.TOOL_FAILED)
        assertEquals(1, failures.size)
        assertTrue(failures[0].detail.contains("syntax error"))
    }
}
