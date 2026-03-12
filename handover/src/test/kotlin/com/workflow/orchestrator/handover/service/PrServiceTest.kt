package com.workflow.orchestrator.handover.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PrServiceTest {

    private val service = PrService()

    // --- Git remote URL parsing ---

    @Test
    fun `parseGitRemote extracts project and repo from SSH URL`() {
        val result = service.parseGitRemote("ssh://git@bitbucket.example.com/PROJ/my-service.git")
        assertNotNull(result)
        assertEquals("PROJ", result!!.first)
        assertEquals("my-service", result.second)
    }

    @Test
    fun `parseGitRemote extracts from HTTPS URL`() {
        val result = service.parseGitRemote("https://bitbucket.example.com/scm/PROJ/my-service.git")
        assertNotNull(result)
        assertEquals("PROJ", result!!.first)
        assertEquals("my-service", result.second)
    }

    @Test
    fun `parseGitRemote extracts from SSH with port`() {
        val result = service.parseGitRemote("ssh://git@bitbucket.example.com:7999/PROJ/my-service.git")
        assertNotNull(result)
        assertEquals("PROJ", result!!.first)
        assertEquals("my-service", result.second)
    }

    @Test
    fun `parseGitRemote extracts from SCP-style URL`() {
        val result = service.parseGitRemote("git@bitbucket.example.com:PROJ/my-service.git")
        assertNotNull(result)
        assertEquals("PROJ", result!!.first)
        assertEquals("my-service", result.second)
    }

    @Test
    fun `parseGitRemote returns null for invalid URL`() {
        val result = service.parseGitRemote("not-a-url")
        assertNull(result)
    }

    @Test
    fun `parseGitRemote strips dot-git suffix`() {
        val result = service.parseGitRemote("ssh://git@bitbucket.example.com/PROJ/my-service.git")
        assertEquals("my-service", result!!.second)
    }

    // --- PR title generation ---

    @Test
    fun `buildPrTitle formats ticket and summary`() {
        val title = service.buildPrTitle("PROJ-123", "Add login feature", "feature/PROJ-123")
        assertEquals("PROJ-123: Add login feature", title)
    }

    @Test
    fun `buildPrTitle truncates long summaries`() {
        val longSummary = "A".repeat(200)
        val title = service.buildPrTitle("PROJ-123", longSummary, "feature/PROJ-123")
        assertTrue(title.length <= 120)
    }

    // --- Fallback description ---

    @Test
    fun `buildFallbackDescription includes ticket and branch`() {
        val desc = service.buildFallbackDescription("PROJ-123", "Add login", "feature/PROJ-123-login")
        assertTrue(desc.contains("PROJ-123"))
        assertTrue(desc.contains("Add login"))
        assertTrue(desc.contains("feature/PROJ-123-login"))
    }
}
