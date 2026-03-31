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
        "run_inspections", "refactor_rename", "find_implementations",
        // Meta-tools (consolidated)
        "jira", "bamboo", "sonar", "bitbucket",
        "debug", "git", "spring", "build", "runtime",
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
    fun `jira keyword triggers jira meta-tool`() {
        val selected = DynamicToolSelector.selectTools(allTools, "check the jira ticket")
        val names = selected.map { it.name }.toSet()
        assertTrue(names.contains("jira"))
    }

    @Test
    fun `build keyword triggers bamboo and runtime meta-tools`() {
        val selected = DynamicToolSelector.selectTools(allTools, "trigger a build")
        val names = selected.map { it.name }.toSet()
        assertTrue(names.contains("bamboo"))
        assertTrue(names.contains("runtime"))
    }

    @Test
    fun `sonar keyword triggers sonar meta-tool`() {
        val selected = DynamicToolSelector.selectTools(allTools, "check sonar issues")
        val names = selected.map { it.name }.toSet()
        assertTrue(names.contains("sonar"))
    }

    @Test
    fun `keyword matching is case insensitive`() {
        val selected = DynamicToolSelector.selectTools(allTools, "Check the JIRA ticket")
        assertTrue(selected.any { it.name == "jira" })
    }

    @Test
    fun `disabled tools are excluded`() {
        val selected = DynamicToolSelector.selectTools(
            allTools, "check jira ticket",
            disabledTools = setOf("jira")
        )
        assertFalse(selected.any { it.name == "jira" })
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
            activatedTools = setOf("sonar")
        )
        assertTrue(selected.any { it.name == "sonar" })
    }

    @Test
    fun `no keywords yields only core tools plus request_tools`() {
        val selected = DynamicToolSelector.selectTools(allTools, "hello world")
        val names = selected.map { it.name }.toSet()
        // Should not include integration meta-tools
        assertFalse(names.contains("jira"))
        assertFalse(names.contains("bamboo"))
        assertFalse(names.contains("sonar"))
        assertFalse(names.contains("bitbucket"))
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
    fun `multiple keywords trigger meta-tools from different categories`() {
        val selected = DynamicToolSelector.selectTools(allTools, "check jira ticket and build status")
        val names = selected.map { it.name }.toSet()
        assertTrue(names.contains("jira"))
        assertTrue(names.contains("bamboo"))
    }

    @Test
    fun `disabled core tools are removed`() {
        val selected = DynamicToolSelector.selectTools(
            allTools, "hello",
            disabledTools = setOf("diagnostics")
        )
        assertFalse(selected.any { it.name == "diagnostics" })
    }

    @Test
    fun `skillAllowedTools restricts to whitelist only`() {
        val toolsWithDelegate = allTools + listOf(stubTool("delegate_task"))
        val result = DynamicToolSelector.selectTools(
            allTools = toolsWithDelegate,
            conversationContext = "check jira ticket and build status",
            skillAllowedTools = setOf("read_file", "search_code")
        )
        val names = result.map { it.name }.toSet()
        assertTrue("read_file" in names, "allowed tool read_file should be present")
        assertTrue("search_code" in names, "allowed tool search_code should be present")
        assertTrue("delegate_task" in names, "delegate_task always included as escape hatch")
        assertFalse("request_tools" in names, "request_tools excluded to prevent whitelist bypass")
        assertFalse("edit_file" in names, "edit_file should be blocked")
        assertFalse("run_command" in names, "run_command should be blocked")
        assertFalse("jira" in names, "jira meta-tool should be blocked despite keyword match")
        assertFalse("bamboo" in names, "bamboo meta-tool should be blocked despite keyword match")
    }

    @Test
    fun `null skillAllowedTools uses normal selection`() {
        val result = DynamicToolSelector.selectTools(
            allTools = allTools,
            conversationContext = "read the file",
            skillAllowedTools = null
        )
        assertTrue(result.isNotEmpty())
        // Normal selection should include core tools
        assertTrue(result.any { it.name == "read_file" })
    }
}
