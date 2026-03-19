package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.agent.runtime.WorkerType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Metadata tests for integration tools.
 * These tools now delegate to the unified service layer (JiraService, BambooService, etc.)
 * so HTTP-level tests live in the service implementation tests in their respective modules.
 * These tests verify tool names, descriptions, parameters, and worker scoping.
 */
class IntegrationToolMetadataTest {

    @Test
    fun `jira_get_ticket has correct metadata`() {
        val tool = JiraGetTicketTool()
        assertEquals("jira_get_ticket", tool.name)
        assertTrue(tool.description.contains("Jira"))
        assertTrue(tool.parameters.required.contains("key"))
        assertTrue(tool.allowedWorkers.contains(WorkerType.TOOLER))
        assertTrue(tool.allowedWorkers.contains(WorkerType.ORCHESTRATOR))
    }

    @Test
    fun `jira_transition has correct metadata`() {
        val tool = JiraTransitionTool()
        assertEquals("jira_transition", tool.name)
        assertTrue(tool.parameters.required.containsAll(listOf("key", "transition_id")))
        assertTrue(tool.allowedWorkers.contains(WorkerType.TOOLER))
    }

    @Test
    fun `jira_comment has correct metadata`() {
        val tool = JiraCommentTool()
        assertEquals("jira_comment", tool.name)
        assertTrue(tool.parameters.required.containsAll(listOf("key", "body")))
        assertTrue(tool.allowedWorkers.contains(WorkerType.TOOLER))
    }

    @Test
    fun `bamboo_build_status has correct metadata`() {
        val tool = BambooBuildTool()
        assertEquals("bamboo_build_status", tool.name)
        assertTrue(tool.parameters.required.contains("plan_key"))
        assertTrue(tool.allowedWorkers.contains(WorkerType.TOOLER))
        assertTrue(tool.allowedWorkers.contains(WorkerType.ORCHESTRATOR))
    }

    @Test
    fun `sonar_issues has correct metadata`() {
        val tool = SonarIssuesTool()
        assertEquals("sonar_issues", tool.name)
        assertTrue(tool.parameters.required.contains("project_key"))
        assertTrue(tool.allowedWorkers.contains(WorkerType.TOOLER))
        assertTrue(tool.allowedWorkers.contains(WorkerType.ANALYZER))
    }

    @Test
    fun `bitbucket_create_pr has correct metadata`() {
        val tool = BitbucketPrTool()
        assertEquals("bitbucket_create_pr", tool.name)
        assertTrue(tool.parameters.required.containsAll(listOf("title", "description", "from_branch")))
        assertTrue(tool.allowedWorkers.contains(WorkerType.TOOLER))
    }

    @Test
    fun `all tools produce valid tool definitions`() {
        val tools = listOf(
            JiraGetTicketTool(), JiraTransitionTool(), JiraCommentTool(),
            BambooBuildTool(), SonarIssuesTool(), BitbucketPrTool()
        )
        for (tool in tools) {
            val def = tool.toToolDefinition()
            assertEquals("function", def.type)
            assertTrue(def.function.name.matches(Regex("^[a-zA-Z0-9_-]+$")), "Invalid tool name: ${def.function.name}")
            assertTrue(def.function.name.length <= 64, "Tool name too long: ${def.function.name}")
            assertTrue(def.function.description.isNotBlank(), "Empty description for ${def.function.name}")
            assertEquals("object", def.function.parameters.type, "Missing type for ${def.function.name}")
        }
    }
}
