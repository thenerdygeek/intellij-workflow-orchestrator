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

    // ── secrets redaction (closes audit finding pullrequest:F-11) ────────────────

    @Test
    fun `build redacts AWS access key from diff`() {
        val diff = """
            +AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
            +aws_secret_access_key=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
            +// some normal code
        """.trimIndent()
        val prompt = builder.build(
            projectKey = "P", repoSlug = "R", prId = 1,
            prTitle = "t", prAuthor = "a",
            sourceBranch = "src", targetBranch = "main",
            reviewers = emptyList(),
            changedFiles = listOf("config.env"),
            diff = diff,
            jiraTicket = null,
            sessionId = "s1",
        )
        assertFalse(prompt.contains("AKIAIOSFODNN7EXAMPLE"), "AWS key ID must be redacted from prompt")
        assertTrue(prompt.contains("***REDACTED***"), "Redaction marker must appear")
        // Normal code lines must still be present
        assertTrue(prompt.contains("some normal code"), "Non-secret lines must pass through unchanged")
    }

    @Test
    fun `build redacts PEM private key header from diff`() {
        val diff = """
            +-----BEGIN RSA PRIVATE KEY-----
            +MIIEpAIBAAKCAQEA0Z3VS5JJcds3xHn/ygWep4PAtEsHAcGDIRNEbV6aSX4ZNBHK
            +-----END RSA PRIVATE KEY-----
        """.trimIndent()
        val prompt = builder.build(
            projectKey = "P", repoSlug = "R", prId = 1,
            prTitle = "t", prAuthor = "a",
            sourceBranch = "src", targetBranch = "main",
            reviewers = emptyList(), changedFiles = listOf("secret.pem"),
            diff = diff, jiraTicket = null, sessionId = "s1",
        )
        assertFalse(prompt.contains("BEGIN RSA PRIVATE KEY"), "PEM header must be redacted from prompt")
        assertTrue(prompt.contains("REDACTED"), "Redaction marker must appear")
    }

    @Test
    fun `build redacts Bearer token line from diff`() {
        val diff = """
            +Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.secret
            +// regular comment
        """.trimIndent()
        val prompt = builder.build(
            projectKey = "P", repoSlug = "R", prId = 1,
            prTitle = "t", prAuthor = "a",
            sourceBranch = "src", targetBranch = "main",
            reviewers = emptyList(), changedFiles = listOf("http.log"),
            diff = diff, jiraTicket = null, sessionId = "s1",
        )
        assertFalse(
            prompt.contains("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.secret"),
            "Bearer token must be redacted from prompt",
        )
        assertTrue(prompt.contains("[REDACTED]") || prompt.contains("***REDACTED***"), "Redaction marker must appear")
        assertTrue(prompt.contains("regular comment"), "Non-secret lines must pass through")
    }

    @Test
    fun `build passes ordinary code lines through unchanged`() {
        val diff = """
            diff --git a/src/Service.kt b/src/Service.kt
            +fun computeHash(input: String): String = sha256(input)
            +return response.body?.string()
        """.trimIndent()
        val prompt = builder.build(
            projectKey = "P", repoSlug = "R", prId = 1,
            prTitle = "t", prAuthor = "a",
            sourceBranch = "src", targetBranch = "main",
            reviewers = emptyList(), changedFiles = listOf("src/Service.kt"),
            diff = diff, jiraTicket = null, sessionId = "s1",
        )
        assertTrue(prompt.contains("computeHash"), "Ordinary code lines must not be redacted")
        assertFalse(prompt.contains("REDACTED"), "No spurious redaction on clean diff")
    }
}
