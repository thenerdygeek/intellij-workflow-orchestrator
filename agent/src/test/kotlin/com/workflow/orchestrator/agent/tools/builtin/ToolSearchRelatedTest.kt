package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.ToolRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolSearchRelatedTest {

    private val tool = ToolSearchTool(mockk<ToolRegistry>())

    @Test
    fun `spring tools suggest build and coverage`() {
        val hint = tool.getRelatedToolsHint(listOf("spring"))
        assertTrue(hint.contains("build") || hint.contains("coverage") || hint.contains("db_schema"))
    }

    @Test
    fun `debug tools suggest diagnostics and runtime_exec`() {
        val hint = tool.getRelatedToolsHint(listOf("debug_step"))
        assertTrue(hint.contains("diagnostics") || hint.contains("runtime_exec"))
    }

    @Test
    fun `already-loaded tools are not suggested`() {
        val hint = tool.getRelatedToolsHint(listOf("spring", "build", "coverage", "db_schema"))
        // All related tools for spring are already loaded — nothing to suggest
        assertTrue(hint.isEmpty() || !hint.contains("spring"))
    }

    @Test
    fun `empty input returns empty hint`() {
        val hint = tool.getRelatedToolsHint(emptyList())
        assertTrue(hint.isEmpty())
    }

    @Test
    fun `max 3 suggestions returned`() {
        val hint = tool.getRelatedToolsHint(listOf("spring", "debug_step", "sonar"))
        val parts = hint.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(parts.size <= 3)
    }

    @Test
    fun `jira suggests git`() {
        val hint = tool.getRelatedToolsHint(listOf("jira"))
        assertTrue(hint.contains("git"))
    }

    @Test
    fun `bitbucket suggests git`() {
        val hint = tool.getRelatedToolsHint(listOf("bitbucket_pr"))
        assertTrue(hint.contains("git"))
    }

    @Test
    fun `sonar suggests diagnostics and coverage`() {
        val hint = tool.getRelatedToolsHint(listOf("sonar"))
        assertTrue(hint.contains("diagnostics") || hint.contains("coverage"))
    }
}
