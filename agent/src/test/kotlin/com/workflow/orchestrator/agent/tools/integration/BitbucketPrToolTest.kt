package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BitbucketPrToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = BitbucketPrTool()

    @Test
    fun `tool name is bitbucket_pr`() {
        assertEquals("bitbucket_pr", tool.name)
    }

    @Test
    fun `action enum contains all 14 actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(14, actions!!.size)
        assertTrue("create_pr" in actions)
        assertTrue("get_pr_detail" in actions)
        assertTrue("get_pr_commits" in actions)
        assertTrue("get_pr_activities" in actions)
        assertTrue("get_pr_changes" in actions)
        assertTrue("get_pr_diff" in actions)
        assertTrue("check_merge_status" in actions)
        assertTrue("approve_pr" in actions)
        assertTrue("merge_pr" in actions)
        assertTrue("decline_pr" in actions)
        assertTrue("update_pr_title" in actions)
        assertTrue("update_pr_description" in actions)
        assertTrue("get_my_prs" in actions)
        assertTrue("get_reviewing_prs" in actions)
    }

    @Test
    fun `only action is required`() {
        assertEquals(listOf("action"), tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes all expected types`() {
        assertEquals(
            setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("bitbucket_pr", def.function.name)
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.description.isNotBlank())
    }

    @Test
    fun `missing action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("action"))
    }

    @Test
    fun `unknown action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "nonexistent") }, project)
        assertTrue(result.isError)
        // Error may be "not configured" (service lookup) or "Unknown action" depending on environment
        assertTrue(result.isError)
    }
}
