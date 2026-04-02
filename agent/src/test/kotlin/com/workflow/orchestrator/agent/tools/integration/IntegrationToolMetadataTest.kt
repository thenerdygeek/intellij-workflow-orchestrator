package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.agent.runtime.WorkerType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Metadata tests for integration meta-tools.
 * These meta-tools consolidate individual actions into single tools
 * (e.g., JiraTool handles get_ticket, transition, comment, etc.).
 */
class IntegrationToolMetadataTest {

    @Test
    fun `jira meta-tool has correct metadata`() {
        val tool = JiraTool()
        assertEquals("jira", tool.name)
        assertTrue(tool.description.contains("Jira", ignoreCase = true))
        assertTrue(tool.parameters.required.contains("action"))
        assertTrue(tool.allowedWorkers.contains(WorkerType.TOOLER))
        assertTrue(tool.allowedWorkers.contains(WorkerType.ORCHESTRATOR))
    }

    @Test
    fun `bamboo_builds meta-tool has correct metadata`() {
        val tool = BambooBuildsTool()
        assertEquals("bamboo_builds", tool.name)
        assertTrue(tool.parameters.required.contains("action"))
        assertTrue(tool.allowedWorkers.contains(WorkerType.TOOLER))
        assertTrue(tool.allowedWorkers.contains(WorkerType.ORCHESTRATOR))
    }

    @Test
    fun `sonar meta-tool has correct metadata`() {
        val tool = SonarTool()
        assertEquals("sonar", tool.name)
        assertTrue(tool.parameters.required.contains("action"))
        assertTrue(tool.allowedWorkers.contains(WorkerType.TOOLER))
        assertTrue(tool.allowedWorkers.contains(WorkerType.ANALYZER))
    }

    @Test
    fun `bitbucket_pr meta-tool has correct metadata`() {
        val tool = BitbucketPrTool()
        assertEquals("bitbucket_pr", tool.name)
        assertTrue(tool.parameters.required.contains("action"))
        assertTrue(tool.allowedWorkers.contains(WorkerType.TOOLER))
    }

    @Test
    fun `all meta-tools produce valid tool definitions`() {
        val tools = listOf(
            JiraTool(), BambooBuildsTool(), BambooPlansTool(),
            SonarTool(), BitbucketPrTool(), BitbucketReviewTool(), BitbucketRepoTool()
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
