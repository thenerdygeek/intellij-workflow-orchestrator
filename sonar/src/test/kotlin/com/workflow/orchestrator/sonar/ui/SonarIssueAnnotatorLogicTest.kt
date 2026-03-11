package com.workflow.orchestrator.sonar.ui

import com.intellij.lang.annotation.HighlightSeverity
import com.workflow.orchestrator.sonar.model.IssueSeverity
import com.workflow.orchestrator.sonar.model.IssueType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarIssueAnnotatorLogicTest {

    @Test
    fun `critical bug maps to ERROR`() {
        val result = SonarIssueAnnotator.mapSeverity(IssueType.BUG, IssueSeverity.CRITICAL)
        assertEquals(HighlightSeverity.ERROR, result)
    }

    @Test
    fun `blocker vulnerability maps to ERROR`() {
        val result = SonarIssueAnnotator.mapSeverity(IssueType.VULNERABILITY, IssueSeverity.BLOCKER)
        assertEquals(HighlightSeverity.ERROR, result)
    }

    @Test
    fun `major bug maps to WARNING`() {
        val result = SonarIssueAnnotator.mapSeverity(IssueType.BUG, IssueSeverity.MAJOR)
        assertEquals(HighlightSeverity.WARNING, result)
    }

    @Test
    fun `minor vulnerability maps to WARNING`() {
        val result = SonarIssueAnnotator.mapSeverity(IssueType.VULNERABILITY, IssueSeverity.MINOR)
        assertEquals(HighlightSeverity.WARNING, result)
    }

    @Test
    fun `code smell maps to WEAK_WARNING regardless of severity`() {
        val result = SonarIssueAnnotator.mapSeverity(IssueType.CODE_SMELL, IssueSeverity.BLOCKER)
        assertEquals(HighlightSeverity.WEAK_WARNING, result)
    }

    @Test
    fun `security hotspot maps to WEAK_WARNING`() {
        val result = SonarIssueAnnotator.mapSeverity(IssueType.SECURITY_HOTSPOT, IssueSeverity.CRITICAL)
        assertEquals(HighlightSeverity.WEAK_WARNING, result)
    }
}
