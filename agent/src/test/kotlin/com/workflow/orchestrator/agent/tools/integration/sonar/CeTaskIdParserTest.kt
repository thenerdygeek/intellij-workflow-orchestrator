package com.workflow.orchestrator.agent.tools.integration.sonar

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CeTaskIdParserTest {

    @Test
    fun `parses canonical INFO line`() {
        val line = "INFO: More about the report processing at https://sonar.example.com/api/ce/task?id=AYabc123XYZ_456"
        assertEquals("AYabc123XYZ_456", CeTaskIdParser.extract(line))
    }

    @Test
    fun `parses URL with trailing space and content`() {
        val line = "[INFO] api/ce/task?id=ABC-123 something else"
        assertEquals("ABC-123", CeTaskIdParser.extract(line))
    }

    @Test
    fun `parses URL with extra query suffix`() {
        val line = "see api/ce/task?id=TASK1&format=json"
        assertEquals("TASK1", CeTaskIdParser.extract(line))
    }

    @Test
    fun `returns null when line has no task URL`() {
        assertNull(CeTaskIdParser.extract("INFO: ANALYSIS SUCCESSFUL, you can find the results"))
    }

    @Test
    fun `returns null on blank line`() {
        assertNull(CeTaskIdParser.extract(""))
    }

    @Test
    fun `accepts allowed characters in task id`() {
        // SonarQube task IDs are base64-url-safe-ish: alphanumerics, dash, underscore.
        val line = "/api/ce/task?id=A_b-1.2"
        // The dot is not a valid task-ID char per Sonar's emission; parser stops at non-id char.
        assertEquals("A_b-1", CeTaskIdParser.extract(line))
    }

    @Test
    fun `returns null when id substring is empty`() {
        assertNull(CeTaskIdParser.extract("api/ce/task?id="))
    }

    @Test
    fun `picks first match when multiple URLs in one line`() {
        val line = "see api/ce/task?id=FIRST and api/ce/task?id=SECOND"
        assertEquals("FIRST", CeTaskIdParser.extract(line))
    }
}
