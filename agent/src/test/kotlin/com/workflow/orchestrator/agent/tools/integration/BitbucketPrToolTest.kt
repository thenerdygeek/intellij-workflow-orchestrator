package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.model.bitbucket.PullRequestData
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.ToolResult as CoreToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BitbucketPrToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = BitbucketPrTool()

    private fun mockBitbucketService(): BitbucketService {
        val svc = mockk<BitbucketService>()
        every { project.getService(BitbucketService::class.java) } returns svc
        return svc
    }

    @Test
    fun `tool name is bitbucket_pr`() {
        assertEquals("bitbucket_pr", tool.name)
    }

    @Test
    fun `action enum contains all 19 actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(19, actions!!.size)
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
        // 2026-05-07 audit additions:
        assertTrue("get_pr_participants" in actions)
        assertTrue("get_blocker_comment_count" in actions)
        assertTrue("get_linked_jira_issues" in actions)
        assertTrue("get_required_builds" in actions)
        // audit gap #3:
        assertTrue("get_prs_for_branch" in actions)
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

    // --- get_prs_for_branch ---

    @Test
    fun `get_prs_for_branch returns matching open PRs`() = runTest {
        val svc = mockBitbucketService()
        val pr = PullRequestData(
            id = 42,
            title = "My feature PR",
            state = "OPEN",
            fromBranch = "feature/PROJ-1",
            toBranch = "main",
            link = "https://bitbucket.example.com/projects/PROJ/repos/app/pull-requests/42",
            authorName = "alice"
        )
        coEvery { svc.getPullRequestsForBranch("feature/PROJ-1", null) } returns
            CoreToolResult(data = listOf(pr), summary = "1 PR(s) for branch feature/PROJ-1")

        val params = buildJsonObject {
            put("action", "get_prs_for_branch")
            put("branch_name", "feature/PROJ-1")
        }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("42") || result.content.contains("My feature PR"))
        coVerify(exactly = 1) { svc.getPullRequestsForBranch("feature/PROJ-1", null) }
    }

    @Test
    fun `get_prs_for_branch with repo_name routes to that repo`() = runTest {
        val svc = mockBitbucketService()
        coEvery { svc.getPullRequestsForBranch("feature/X-1", "platform-app") } returns
            CoreToolResult(data = emptyList(), summary = "0 PR(s) for branch feature/X-1")

        val params = buildJsonObject {
            put("action", "get_prs_for_branch")
            put("branch_name", "feature/X-1")
            put("repo_name", "platform-app")
        }
        tool.execute(params, project)

        coVerify(exactly = 1) { svc.getPullRequestsForBranch("feature/X-1", "platform-app") }
    }

    @Test
    fun `get_prs_for_branch missing branch_name returns missingParam error`() = runTest {
        val svc = mockBitbucketService()
        @Suppress("UNUSED_VARIABLE") val unused = svc

        val params = buildJsonObject {
            put("action", "get_prs_for_branch")
        }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("branch_name"))
    }

    @Test
    fun `merge_pr strategy description uses valid DC strategy ids`() {
        // Regression guard for F-MED audit finding: tool description must not advertise
        // invalid DC strategy ids. See audit details §bitbucket_pr.merge_pr.
        val source = java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt").readText()
        val relevant = source.lines()
            .filter { it.contains("strategy") && it.contains("Merge strategy", ignoreCase = true) }
            .joinToString("\n")
        org.junit.jupiter.api.Assertions.assertTrue(
            relevant.contains("no-ff"), "description should list no-ff but was: $relevant"
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            relevant.contains("squash"), "description should list squash but was: $relevant"
        )
        org.junit.jupiter.api.Assertions.assertTrue(
            relevant.contains("rebase-no-ff"), "description should list rebase-no-ff but was: $relevant"
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            relevant.contains("merge-commit"), "description should not list merge-commit but was: $relevant"
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            relevant.contains("ff-only"), "description should not list ff-only but was: $relevant"
        )
    }
}
