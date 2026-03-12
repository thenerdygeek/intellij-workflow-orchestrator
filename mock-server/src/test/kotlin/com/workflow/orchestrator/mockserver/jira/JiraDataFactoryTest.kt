package com.workflow.orchestrator.mockserver.jira

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class JiraDataFactoryTest {

    @Test
    fun `default state has 6 issues across different statuses`() {
        val state = JiraDataFactory.createDefaultState()
        assertEquals(6, state.issues.size)
        val statusNames = state.issues.values.map { it.status.name }.toSet()
        assertFalse("In Progress" in statusNames, "Should NOT use standard 'In Progress' name")
        assertTrue("WIP" in statusNames, "Should use divergent 'WIP' name")
    }

    @Test
    fun `default state uses divergent category keys`() {
        val state = JiraDataFactory.createDefaultState()
        val categoryKeys = state.statuses.map { it.statusCategory.key }.toSet()
        assertTrue("in_flight" in categoryKeys, "Should include custom 'in_flight' key")
        assertTrue("verification" in categoryKeys, "Should include custom 'verification' key")
        assertTrue("blocked" in categoryKeys, "Should include custom 'blocked' key")
        assertTrue("indeterminate" in categoryKeys, "Should include 'indeterminate' for fallback test")
    }

    @Test
    fun `transitions from Open require assignee`() {
        val state = JiraDataFactory.createDefaultState()
        val transitions = state.getTransitionsForIssue("PROJ-101")
        val startWorking = transitions.find { it.name == "Start Working" }
        assertNotNull(startWorking)
        assertTrue(startWorking!!.fields["assignee"]?.required == true)
    }

    @Test
    fun `applyTransition changes issue status`() {
        val state = JiraDataFactory.createDefaultState()
        val before = state.issues["PROJ-101"]!!.status.name
        assertEquals("Open", before)
        state.applyTransition("PROJ-101", "11")
        val after = state.issues["PROJ-101"]!!.status.name
        assertEquals("WIP", after)
    }

    @Test
    fun `one issue has empty summary for null handling test`() {
        val state = JiraDataFactory.createDefaultState()
        val emptyTitle = state.issues.values.find { it.summary.isEmpty() }
        assertNotNull(emptyTitle, "Should have one issue with empty summary")
    }

    @Test
    fun `issue types diverge from standard Jira`() {
        val state = JiraDataFactory.createDefaultState()
        val types = state.issues.values.map { it.issueType }.toSet()
        assertFalse("Bug" in types, "Should use 'Defect' not 'Bug'")
        assertTrue("Defect" in types)
        assertTrue("Spike" in types)
        assertTrue("Tech Debt" in types)
    }

    @Test
    fun `large sprint has 50+ issues`() {
        val state = JiraDataFactory.createLargeSprintState()
        assertTrue(state.issues.size >= 50)
    }

    @Test
    fun `no-active-sprint has no active sprints`() {
        val state = JiraDataFactory.createNoActiveSprintState()
        val active = state.sprints.filter { it.state == "active" }
        assertTrue(active.isEmpty())
    }
}
