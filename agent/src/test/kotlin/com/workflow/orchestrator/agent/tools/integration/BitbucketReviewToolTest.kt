package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.model.PrComment
import com.workflow.orchestrator.core.model.PrCommentAuthor
import com.workflow.orchestrator.core.model.PrCommentSeverity
import com.workflow.orchestrator.core.model.PrCommentState
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.ToolResult as CoreToolResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BitbucketReviewToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = BitbucketReviewTool()

    // --- helpers ---

    private fun mockBitbucketService(): BitbucketService {
        val svc = mockk<BitbucketService>()
        every { project.getService(BitbucketService::class.java) } returns svc
        return svc
    }

    private fun sampleComment(
        id: String = "1",
        version: Int = 0,
        text: String = "hello",
        state: PrCommentState = PrCommentState.OPEN
    ) = PrComment(
        id = id,
        version = version,
        text = text,
        author = PrCommentAuthor(name = "alice", displayName = "Alice"),
        createdDate = 0L,
        updatedDate = 0L,
        state = state,
        severity = PrCommentSeverity.NORMAL
    )

    // --- schema / static tests ---

    @Test
    fun `tool name is bitbucket_review`() {
        assertEquals("bitbucket_review", tool.name)
    }

    @Test
    fun `action enum contains all 7 actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(7, actions!!.size)
        assertTrue("add_pr_comment" in actions)
        assertTrue("add_inline_comment" in actions)
        assertTrue("reply_to_comment" in actions)
        assertTrue("add_reviewer" in actions)
        assertTrue("remove_reviewer" in actions)
        assertTrue("set_reviewer_status" in actions)
        assertTrue("list_comments" in actions)
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
        assertEquals("bitbucket_review", def.function.name)
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

    // --- Task 1: list_comments ---

    @Test
    fun `list_comments dispatches to service with defaults`() = runTest {
        val svc = mockBitbucketService()
        val comments = listOf(sampleComment("10"), sampleComment("11"))
        coEvery { svc.listPrComments("PROJ", "my-repo", 42, false, false) } returns
            CoreToolResult(data = comments, summary = "2 comment(s) on PR 42")

        val result = tool.execute(buildJsonObject {
            put("action", "list_comments")
            put("project_key", "PROJ")
            put("repo_slug", "my-repo")
            put("pr_id", "42")
        }, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("2 comment(s) on PR 42"))
    }

    @Test
    fun `list_comments passes only_open and only_inline flags`() = runTest {
        val svc = mockBitbucketService()
        coEvery { svc.listPrComments("PROJ", "my-repo", 1, true, true) } returns
            CoreToolResult(data = emptyList(), summary = "0 comment(s)")

        val result = tool.execute(buildJsonObject {
            put("action", "list_comments")
            put("project_key", "PROJ")
            put("repo_slug", "my-repo")
            put("pr_id", "1")
            put("only_open", "true")
            put("only_inline", "true")
        }, project)

        assertFalse(result.isError)
    }

    @Test
    fun `list_comments missing project_key returns error`() = runTest {
        val svc = mockBitbucketService()
        @Suppress("UNUSED_VARIABLE") val unused = svc
        val result = tool.execute(buildJsonObject {
            put("action", "list_comments")
            put("repo_slug", "my-repo")
            put("pr_id", "1")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("project_key"))
    }

    @Test
    fun `list_comments missing repo_slug returns error`() = runTest {
        val svc = mockBitbucketService()
        @Suppress("UNUSED_VARIABLE") val unused = svc
        val result = tool.execute(buildJsonObject {
            put("action", "list_comments")
            put("project_key", "PROJ")
            put("pr_id", "1")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("repo_slug"))
    }

    @Test
    fun `list_comments missing pr_id returns error`() = runTest {
        val svc = mockBitbucketService()
        @Suppress("UNUSED_VARIABLE") val unused = svc
        val result = tool.execute(buildJsonObject {
            put("action", "list_comments")
            put("project_key", "PROJ")
            put("repo_slug", "my-repo")
        }, project)
        assertTrue(result.isError)
    }
}
