package com.workflow.orchestrator.jira.tasks

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JiraTaskMappingTest {

    @Test
    fun `maps Bug issue type to BUG`() {
        assertEquals("BUG", JiraTaskMapping.mapIssueType("Bug"))
    }

    @Test
    fun `maps Story to FEATURE`() {
        assertEquals("FEATURE", JiraTaskMapping.mapIssueType("Story"))
    }

    @Test
    fun `maps Task to FEATURE`() {
        assertEquals("FEATURE", JiraTaskMapping.mapIssueType("Task"))
    }

    @Test
    fun `maps unknown to OTHER`() {
        assertEquals("OTHER", JiraTaskMapping.mapIssueType("Epic"))
    }

    @Test
    fun `maps new status category to OPEN`() {
        assertEquals("OPEN", JiraTaskMapping.mapStatusCategory("new"))
    }

    @Test
    fun `maps indeterminate to IN_PROGRESS`() {
        assertEquals("IN_PROGRESS", JiraTaskMapping.mapStatusCategory("indeterminate"))
    }

    @Test
    fun `maps done to RESOLVED`() {
        assertEquals("RESOLVED", JiraTaskMapping.mapStatusCategory("done"))
    }

    @Test
    fun `maps unknown category to OPEN`() {
        assertEquals("OPEN", JiraTaskMapping.mapStatusCategory("undefined"))
    }
}
