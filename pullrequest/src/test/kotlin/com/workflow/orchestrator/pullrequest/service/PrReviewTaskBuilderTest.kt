package com.workflow.orchestrator.pullrequest.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PrReviewTaskBuilderTest {

    private val builder = PrReviewTaskBuilder()

    @Test
    fun `build produces prompt with pr_id and diff`() {
        val prompt = builder.build(
            projectKey = "PROJ",
            repoSlug = "repo",
            prId = 123,
            prTitle = "Add auth",
            prAuthor = "alice",
            sourceBranch = "feat/auth",
            targetBranch = "main",
            reviewers = listOf("bob", "carol"),
            changedFiles = listOf("src/Auth.kt", "src/AuthTest.kt"),
            diff = "diff --git a/x.kt b/x.kt\n...",
            jiraTicket = null,
            sessionId = "s1",
        )
        assertTrue(prompt.contains("<pr_id>PROJ/repo/PR-123</pr_id>"))
        assertTrue(prompt.contains("diff --git a/x.kt"))
        assertTrue(prompt.contains("ai_review.add_finding"))
        assertTrue(prompt.contains("session_id"))
    }

    @Test
    fun `build truncates large diffs at MAX_DIFF_CHARS boundary`() {
        val huge = "x".repeat(400_000)
        val prompt = builder.build(
            projectKey = "P", repoSlug = "R", prId = 1,
            prTitle = "t", prAuthor = "a",
            sourceBranch = "src", targetBranch = "main",
            reviewers = emptyList(),
            changedFiles = emptyList(),
            diff = huge,
            jiraTicket = null,
            sessionId = "s1",
        )
        assertTrue(prompt.contains("[... diff truncated"), "prompt should contain truncation marker")
        assertTrue(prompt.length < huge.length + 2000, "prompt should be capped")
    }

    @Test
    fun `build omits jira block when ticket is null`() {
        val prompt = builder.build(
            projectKey = "P", repoSlug = "R", prId = 1,
            prTitle = "t", prAuthor = "a",
            sourceBranch = "src", targetBranch = "main",
            reviewers = emptyList(),
            changedFiles = emptyList(),
            diff = "tiny",
            jiraTicket = null,
            sessionId = "s1",
        )
        assertFalse(prompt.contains("<linked_jira_ticket>"))
    }

    @Test
    fun `build includes jira block when ticket provided`() {
        val prompt = builder.build(
            projectKey = "P", repoSlug = "R", prId = 1,
            prTitle = "t", prAuthor = "a",
            sourceBranch = "src", targetBranch = "main",
            reviewers = emptyList(),
            changedFiles = emptyList(),
            diff = "tiny",
            jiraTicket = PrReviewTaskBuilder.JiraTicket(
                key = "ENG-42",
                summary = "Add auth",
                description = "Users want auth",
                acceptanceCriteria = "login works",
            ),
            sessionId = "s1",
        )
        assertTrue(prompt.contains("<linked_jira_ticket>"))
        assertTrue(prompt.contains("ENG-42"))
        assertTrue(prompt.contains("Users want auth"))
    }

    @Test
    fun `build instructs agent to not push directly to Bitbucket`() {
        val prompt = builder.build(
            projectKey = "P", repoSlug = "R", prId = 1,
            prTitle = "t", prAuthor = "a",
            sourceBranch = "src", targetBranch = "main",
            reviewers = emptyList(),
            changedFiles = emptyList(),
            diff = "x",
            jiraTicket = null,
            sessionId = "s1",
        )
        assertTrue(
            prompt.contains("Do NOT post to Bitbucket") ||
                prompt.contains("do not push to Bitbucket", ignoreCase = true)
        )
    }
}
