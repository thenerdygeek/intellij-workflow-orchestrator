package com.workflow.orchestrator.core.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GitMergeBaseUtilTest {

    @Test
    fun `parseMergeBaseOutput extracts commit hash`() {
        val output = "abc123def456\n"
        val result = GitMergeBaseUtil.parseMergeBaseOutput(output)
        assertEquals("abc123def456", result)
    }

    @Test
    fun `parseMergeBaseOutput returns null for empty output`() {
        assertNull(GitMergeBaseUtil.parseMergeBaseOutput(""))
        assertNull(GitMergeBaseUtil.parseMergeBaseOutput("  \n"))
    }

    @Test
    fun `parseRevListCount extracts count`() {
        assertEquals(5, GitMergeBaseUtil.parseRevListCount("5\n"))
        assertEquals(0, GitMergeBaseUtil.parseRevListCount("0\n"))
    }

    @Test
    fun `parseRevListCount returns max on invalid input`() {
        assertEquals(Int.MAX_VALUE, GitMergeBaseUtil.parseRevListCount(""))
        assertEquals(Int.MAX_VALUE, GitMergeBaseUtil.parseRevListCount("not-a-number"))
    }
}
