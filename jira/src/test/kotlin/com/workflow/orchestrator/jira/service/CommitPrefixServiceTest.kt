package com.workflow.orchestrator.jira.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommitPrefixServiceTest {

    @Test
    fun `adds standard prefix when not present`() {
        val result = CommitPrefixService.addPrefix(
            message = "implemented auth logic",
            ticketId = "PROJ-123",
            useConventionalCommits = false
        )
        assertEquals("PROJ-123: implemented auth logic", result)
    }

    @Test
    fun `does not double-prefix when already present`() {
        val result = CommitPrefixService.addPrefix(
            message = "PROJ-123: implemented auth logic",
            ticketId = "PROJ-123",
            useConventionalCommits = false
        )
        assertEquals("PROJ-123: implemented auth logic", result)
    }

    @Test
    fun `adds conventional commit prefix`() {
        val result = CommitPrefixService.addPrefix(
            message = "implemented auth logic",
            ticketId = "PROJ-123",
            useConventionalCommits = true
        )
        assertEquals("feat(PROJ-123): implemented auth logic", result)
    }

    @Test
    fun `preserves existing conventional commit type`() {
        val result = CommitPrefixService.addPrefix(
            message = "fix: resolve null pointer",
            ticketId = "PROJ-123",
            useConventionalCommits = true
        )
        assertEquals("fix(PROJ-123): resolve null pointer", result)
    }

    @Test
    fun `does not modify message when ticket already in conventional format`() {
        val result = CommitPrefixService.addPrefix(
            message = "feat(PROJ-123): implemented auth logic",
            ticketId = "PROJ-123",
            useConventionalCommits = true
        )
        assertEquals("feat(PROJ-123): implemented auth logic", result)
    }

    @Test
    fun `rejects invalid ticket ID format - header key`() {
        val result = CommitPrefixService.addPrefix(
            message = "some commit message",
            ticketId = "── John Doe (5) ──",
            useConventionalCommits = false
        )
        assertEquals("some commit message", result)
    }

    @Test
    fun `rejects empty ticket ID`() {
        val result = CommitPrefixService.addPrefix(
            message = "some commit message",
            ticketId = "",
            useConventionalCommits = false
        )
        assertEquals("some commit message", result)
    }

    @Test
    fun `accepts valid multi-letter project keys`() {
        val result = CommitPrefixService.addPrefix(
            message = "fix something",
            ticketId = "MYAPP-42",
            useConventionalCommits = false
        )
        assertEquals("MYAPP-42: fix something", result)
    }
}
