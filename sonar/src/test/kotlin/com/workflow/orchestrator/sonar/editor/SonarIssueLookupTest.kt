package com.workflow.orchestrator.sonar.editor

import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import com.workflow.orchestrator.sonar.model.MappedIssue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarIssueLookupTest {

    private fun issue(
        filePath: String,
        startLine: Int,
        endLine: Int,
        key: String = "k$startLine"
    ) = MappedIssue(
        key = key,
        type = IssueType.CODE_SMELL,
        severity = IssueSeverity.MAJOR,
        message = "x",
        rule = "r",
        filePath = filePath,
        startLine = startLine,
        endLine = endLine,
        startOffset = 0,
        endOffset = 0,
        effort = null,
        projectKey = "P"
    )

    @Test
    fun `returns issue when line is within range`() {
        val issues = listOf(issue("Foo.kt", 10, 12))
        val match = SonarIssueLookup.findIssueSpanningLine(issues, "Foo.kt", 11)
        assertEquals("k10", match?.key)
    }

    @Test
    fun `returns issue at start boundary`() {
        val issues = listOf(issue("Foo.kt", 10, 12))
        assertNotNull(SonarIssueLookup.findIssueSpanningLine(issues, "Foo.kt", 10))
    }

    @Test
    fun `returns issue at end boundary`() {
        val issues = listOf(issue("Foo.kt", 10, 12))
        assertNotNull(SonarIssueLookup.findIssueSpanningLine(issues, "Foo.kt", 12))
    }

    @Test
    fun `returns null when line is outside range`() {
        val issues = listOf(issue("Foo.kt", 10, 12))
        assertNull(SonarIssueLookup.findIssueSpanningLine(issues, "Foo.kt", 9))
        assertNull(SonarIssueLookup.findIssueSpanningLine(issues, "Foo.kt", 13))
    }

    @Test
    fun `returns null when filePath does not match`() {
        val issues = listOf(issue("Foo.kt", 10, 12))
        assertNull(SonarIssueLookup.findIssueSpanningLine(issues, "Bar.kt", 11))
    }

    @Test
    fun `returns innermost match when issues nest`() {
        val issues = listOf(
            issue("Foo.kt", 10, 20, "outer"),
            issue("Foo.kt", 12, 14, "inner")
        )
        val match = SonarIssueLookup.findIssueSpanningLine(issues, "Foo.kt", 13)
        assertEquals("inner", match?.key)
    }

    @Test
    fun `returns null on empty list`() {
        assertNull(SonarIssueLookup.findIssueSpanningLine(emptyList(), "Foo.kt", 10))
    }
}
