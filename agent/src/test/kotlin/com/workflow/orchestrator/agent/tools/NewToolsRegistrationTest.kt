package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.runtime.ApprovalGate
import com.workflow.orchestrator.agent.runtime.RiskLevel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// Tests that all new tools are correctly registered, classified, and selectable.
// Covers: CreateFileTool, ListChangesTool, RollbackChangesTool, ProjectContextTool,
// and the meta-tool consolidation (9 meta-tools).
class NewToolsRegistrationTest {

    // ── ApprovalGate Risk Classification ──

    @Test
    fun `create_file is MEDIUM risk by default`() {
        assertEquals(RiskLevel.MEDIUM, ApprovalGate.riskLevelFor("create_file"))
    }

    @Test
    fun `create_file in test directory is LOW risk`() {
        val risk = ApprovalGate.classifyRisk("create_file", mapOf("path" to "src/test/MyTest.kt"))
        assertEquals(RiskLevel.LOW, risk)
    }

    @Test
    fun `create_file for markdown is LOW risk`() {
        val risk = ApprovalGate.classifyRisk("create_file", mapOf("path" to "docs/README.md"))
        assertEquals(RiskLevel.LOW, risk)
    }

    @Test
    fun `create_file in main directory is MEDIUM risk`() {
        val risk = ApprovalGate.classifyRisk("create_file", mapOf("path" to "src/main/kotlin/Service.kt"))
        assertEquals(RiskLevel.MEDIUM, risk)
    }

    @Test
    fun `list_changes is NONE risk`() {
        assertEquals(RiskLevel.NONE, ApprovalGate.riskLevelFor("list_changes"))
    }

    @Test
    fun `rollback_changes is HIGH risk`() {
        assertEquals(RiskLevel.HIGH, ApprovalGate.riskLevelFor("rollback_changes"))
    }

    @Test
    fun `project_context is NONE risk`() {
        assertEquals(RiskLevel.NONE, ApprovalGate.riskLevelFor("project_context"))
    }

    // ── Meta-tool Action-Aware Classification ──

    @Test
    fun `jira get_ticket action is NONE risk`() {
        val risk = ApprovalGate.classifyRisk("jira", mapOf("action" to "get_ticket"))
        assertEquals(RiskLevel.NONE, risk)
    }

    @Test
    fun `jira comment action is LOW risk`() {
        val risk = ApprovalGate.classifyRisk("jira", mapOf("action" to "comment"))
        assertEquals(RiskLevel.LOW, risk)
    }

    @Test
    fun `jira transition action is MEDIUM risk`() {
        val risk = ApprovalGate.classifyRisk("jira", mapOf("action" to "transition"))
        assertEquals(RiskLevel.MEDIUM, risk)
    }

    @Test
    fun `bamboo build_status is NONE risk`() {
        val risk = ApprovalGate.classifyRisk("bamboo", mapOf("action" to "build_status"))
        assertEquals(RiskLevel.NONE, risk)
    }

    @Test
    fun `bamboo trigger_build is MEDIUM risk`() {
        val risk = ApprovalGate.classifyRisk("bamboo", mapOf("action" to "trigger_build"))
        assertEquals(RiskLevel.MEDIUM, risk)
    }

    @Test
    fun `sonar is NONE risk (all read-only)`() {
        assertEquals(RiskLevel.NONE, ApprovalGate.riskLevelFor("sonar"))
    }

    @Test
    fun `spring is NONE risk (all read-only)`() {
        assertEquals(RiskLevel.NONE, ApprovalGate.riskLevelFor("spring"))
    }

    @Test
    fun `build is NONE risk (all read-only)`() {
        assertEquals(RiskLevel.NONE, ApprovalGate.riskLevelFor("build"))
    }

    @Test
    fun `runtime run_tests is LOW risk`() {
        val risk = ApprovalGate.classifyRisk("runtime", mapOf("action" to "run_tests"))
        assertEquals(RiskLevel.LOW, risk)
    }

    @Test
    fun `runtime compile_module is LOW risk`() {
        val risk = ApprovalGate.classifyRisk("runtime", mapOf("action" to "compile_module"))
        assertEquals(RiskLevel.LOW, risk)
    }

    @Test
    fun `runtime get_run_configurations is NONE risk`() {
        val risk = ApprovalGate.classifyRisk("runtime", mapOf("action" to "get_run_configurations"))
        assertEquals(RiskLevel.NONE, risk)
    }

    @Test
    fun `debug get_state is NONE risk`() {
        val risk = ApprovalGate.classifyRisk("debug", mapOf("action" to "get_state"))
        assertEquals(RiskLevel.NONE, risk)
    }

    @Test
    fun `debug step_over is LOW risk`() {
        val risk = ApprovalGate.classifyRisk("debug", mapOf("action" to "step_over"))
        assertEquals(RiskLevel.LOW, risk)
    }

    @Test
    fun `debug start_session is MEDIUM risk`() {
        val risk = ApprovalGate.classifyRisk("debug", mapOf("action" to "start_session"))
        assertEquals(RiskLevel.MEDIUM, risk)
    }

    @Test
    fun `bitbucket get_pr_diff is NONE risk`() {
        val risk = ApprovalGate.classifyRisk("bitbucket", mapOf("action" to "get_pr_diff"))
        assertEquals(RiskLevel.NONE, risk)
    }

    @Test
    fun `bitbucket add_inline_comment is LOW risk`() {
        val risk = ApprovalGate.classifyRisk("bitbucket", mapOf("action" to "add_inline_comment"))
        assertEquals(RiskLevel.LOW, risk)
    }

    @Test
    fun `bitbucket merge_pr is MEDIUM risk`() {
        val risk = ApprovalGate.classifyRisk("bitbucket", mapOf("action" to "merge_pr"))
        assertEquals(RiskLevel.MEDIUM, risk)
    }

    @Test
    fun `git status is NONE risk`() {
        val risk = ApprovalGate.classifyRisk("git", mapOf("action" to "status"))
        assertEquals(RiskLevel.NONE, risk)
    }

    @Test
    fun `git shelve is LOW risk`() {
        val risk = ApprovalGate.classifyRisk("git", mapOf("action" to "shelve"))
        assertEquals(RiskLevel.LOW, risk)
    }

    // ── DynamicToolSelector ──

    @Test
    fun `create_file is in ALWAYS_INCLUDE`() {
        val allTools = listOf("create_file", "read_file", "edit_file", "jira").map { stubTool(it) }
        val selected = DynamicToolSelector.selectTools(allTools, "hello world")
        assertTrue(selected.any { it.name == "create_file" })
    }

    @Test
    fun `list_changes is in ALWAYS_INCLUDE`() {
        val allTools = listOf("list_changes", "read_file").map { stubTool(it) }
        val selected = DynamicToolSelector.selectTools(allTools, "hello world")
        assertTrue(selected.any { it.name == "list_changes" })
    }

    @Test
    fun `project_context is in ALWAYS_INCLUDE`() {
        val allTools = listOf("project_context", "read_file").map { stubTool(it) }
        val selected = DynamicToolSelector.selectTools(allTools, "hello world")
        assertTrue(selected.any { it.name == "project_context" })
    }

    @Test
    fun `rollback_changes is NOT in ALWAYS_INCLUDE`() {
        val allTools = listOf("rollback_changes", "read_file").map { stubTool(it) }
        val selected = DynamicToolSelector.selectTools(allTools, "hello world")
        assertFalse(selected.any { it.name == "rollback_changes" })
    }

    // ── Meta-tool DynamicToolSelector integration ──

    @Test
    fun `jira keyword selects jira meta-tool`() {
        val allTools = listOf("jira", "read_file").map { stubTool(it) }
        val selected = DynamicToolSelector.selectTools(allTools, "check jira ticket")
        assertTrue(selected.any { it.name == "jira" })
    }

    @Test
    fun `sonar keyword selects sonar meta-tool`() {
        val allTools = listOf("sonar", "read_file").map { stubTool(it) }
        val selected = DynamicToolSelector.selectTools(allTools, "check sonar quality")
        assertTrue(selected.any { it.name == "sonar" })
    }

    @Test
    fun `debug keyword selects debug and runtime meta-tools`() {
        val allTools = listOf("debug", "runtime", "read_file").map { stubTool(it) }
        val selected = DynamicToolSelector.selectTools(allTools, "debug the issue")
        assertTrue(selected.any { it.name == "debug" })
        assertTrue(selected.any { it.name == "runtime" })
    }

    private fun stubTool(name: String) = object : AgentTool {
        override val name = name
        override val description = "Test tool $name"
        override val parameters = com.workflow.orchestrator.agent.api.dto.FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(com.workflow.orchestrator.agent.runtime.WorkerType.CODER)
        override suspend fun execute(params: kotlinx.serialization.json.JsonObject, project: com.intellij.openapi.project.Project) =
            ToolResult("ok", "ok", 1)
    }
}
