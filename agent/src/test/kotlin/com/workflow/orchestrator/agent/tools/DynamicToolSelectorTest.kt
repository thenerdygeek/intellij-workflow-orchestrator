package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.runtime.WorkerType
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DynamicToolSelectorTest {

    /** Minimal AgentTool stub for testing tool selection. */
    private fun stubTool(name: String) = object : AgentTool {
        override val name = name
        override val description = "Test tool $name"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult("ok", "ok", 1)
    }

    private val allTools = listOf(
        // Core
        "read_file", "edit_file", "search_code", "run_command",
        "file_structure", "find_definition", "find_references", "type_hierarchy", "call_hierarchy",
        "diagnostics", "format_code", "optimize_imports",
        // IDE
        "run_inspections", "refactor_rename", "compile_module", "run_tests",
        // VCS
        "git_status", "git_blame", "find_implementations",
        // Jira
        "jira_get_ticket", "jira_transition",
        // Bamboo
        "bamboo_build_status", "bamboo_trigger_build",
        // Sonar
        "sonar_issues", "sonar_quality_gate",
        // Bitbucket
        "bitbucket_create_pr",
        // Meta
        "request_tools"
    ).map { stubTool(it) }

    @Test
    fun `core tools always included with no keywords`() {
        val selected = DynamicToolSelector.selectTools(allTools, "fix the NPE please")
        val names = selected.map { it.name }.toSet()
        assertTrue(names.contains("read_file"))
        assertTrue(names.contains("edit_file"))
        assertTrue(names.contains("search_code"))
        assertTrue(names.contains("diagnostics"))
    }

    @Test
    fun `request_tools always included`() {
        val selected = DynamicToolSelector.selectTools(allTools, "anything")
        assertTrue(selected.any { it.name == "request_tools" })
    }

    @Test
    fun `jira keyword triggers jira tools`() {
        val selected = DynamicToolSelector.selectTools(allTools, "check the jira ticket")
        val names = selected.map { it.name }.toSet()
        assertTrue(names.contains("jira_get_ticket"))
        assertTrue(names.contains("jira_transition"))
    }

    @Test
    fun `build keyword triggers bamboo and compile tools`() {
        val selected = DynamicToolSelector.selectTools(allTools, "trigger a build")
        val names = selected.map { it.name }.toSet()
        assertTrue(names.contains("bamboo_build_status"))
        assertTrue(names.contains("bamboo_trigger_build"))
        assertTrue(names.contains("compile_module"))
    }

    @Test
    fun `sonar keyword triggers sonar tools`() {
        val selected = DynamicToolSelector.selectTools(allTools, "check sonar issues")
        val names = selected.map { it.name }.toSet()
        assertTrue(names.contains("sonar_issues"))
        assertTrue(names.contains("sonar_quality_gate"))
    }

    @Test
    fun `keyword matching is case insensitive`() {
        val selected = DynamicToolSelector.selectTools(allTools, "Check the JIRA ticket")
        assertTrue(selected.any { it.name == "jira_get_ticket" })
    }

    @Test
    fun `disabled tools are excluded`() {
        val selected = DynamicToolSelector.selectTools(
            allTools, "check jira ticket",
            disabledTools = setOf("jira_get_ticket")
        )
        assertFalse(selected.any { it.name == "jira_get_ticket" })
        // Other jira tools still present
        assertTrue(selected.any { it.name == "jira_transition" })
    }

    @Test
    fun `request_tools cannot be disabled by user preferences`() {
        val selected = DynamicToolSelector.selectTools(
            allTools, "anything",
            disabledTools = setOf("request_tools")
        )
        assertTrue(selected.any { it.name == "request_tools" },
            "request_tools must always be included even if in disabledTools")
    }

    @Test
    fun `activated tools are included even without keyword match`() {
        val selected = DynamicToolSelector.selectTools(
            allTools, "fix the NPE",
            activatedTools = setOf("sonar_issues")
        )
        assertTrue(selected.any { it.name == "sonar_issues" })
    }

    @Test
    fun `no keywords yields only core tools plus request_tools`() {
        val selected = DynamicToolSelector.selectTools(allTools, "hello world")
        val names = selected.map { it.name }.toSet()
        // Should not include integration tools
        assertFalse(names.contains("jira_get_ticket"))
        assertFalse(names.contains("bamboo_build_status"))
        assertFalse(names.contains("sonar_issues"))
        assertFalse(names.contains("bitbucket_create_pr"))
        // Should include core + request_tools
        assertTrue(names.contains("read_file"))
        assertTrue(names.contains("request_tools"))
    }

    @Test
    fun `hasIntegrationTriggers detects keywords`() {
        assertTrue(DynamicToolSelector.hasIntegrationTriggers("check sonar coverage"))
        assertTrue(DynamicToolSelector.hasIntegrationTriggers("open the JIRA ticket"))
        assertFalse(DynamicToolSelector.hasIntegrationTriggers("hello world"))
    }

    @Test
    fun `multiple keywords trigger tools from different categories`() {
        val selected = DynamicToolSelector.selectTools(allTools, "check jira ticket and build status")
        val names = selected.map { it.name }.toSet()
        assertTrue(names.contains("jira_get_ticket"))
        assertTrue(names.contains("bamboo_build_status"))
    }

    @Test
    fun `disabled core tools are removed`() {
        val selected = DynamicToolSelector.selectTools(
            allTools, "hello",
            disabledTools = setOf("diagnostics")
        )
        assertFalse(selected.any { it.name == "diagnostics" })
    }
}
