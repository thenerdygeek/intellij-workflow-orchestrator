package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BitbucketRepoToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = BitbucketRepoTool()

    @Test
    fun `tool name is bitbucket_repo`() {
        assertEquals("bitbucket_repo", tool.name)
    }

    @Test
    fun `action enum contains all 6 actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(6, actions!!.size)
        assertTrue("get_branches" in actions)
        assertTrue("create_branch" in actions)
        assertTrue("search_users" in actions)
        assertTrue("get_file_content" in actions)
        assertTrue("get_build_statuses" in actions)
        assertTrue("list_repos" in actions)
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
        assertEquals("bitbucket_repo", def.function.name)
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
    }
}
