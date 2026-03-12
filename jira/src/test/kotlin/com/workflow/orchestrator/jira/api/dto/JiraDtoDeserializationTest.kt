package com.workflow.orchestrator.jira.api.dto

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JiraDtoDeserializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @Test
    fun `deserialize boards response`() {
        val result = json.decodeFromString<JiraBoardSearchResult>(fixture("jira-boards.json"))
        assertEquals(1, result.values.size)
        assertEquals("My Scrum Board", result.values[0].name)
        assertEquals("scrum", result.values[0].type)
        assertEquals("PROJ", result.values[0].location?.projectKey)
    }

    @Test
    fun `deserialize sprints response`() {
        val result = json.decodeFromString<JiraSprintSearchResult>(fixture("jira-sprints.json"))
        assertEquals(1, result.values.size)
        assertEquals("Sprint 14", result.values[0].name)
        assertEquals("active", result.values[0].state)
        assertEquals(42, result.values[0].id)
    }

    @Test
    fun `deserialize sprint issues response`() {
        val result = json.decodeFromString<JiraIssueSearchResult>(fixture("jira-sprint-issues.json"))
        assertEquals(2, result.issues.size)
        assertEquals("PROJ-123", result.issues[0].key)
        assertEquals("Fix login page redirect", result.issues[0].fields.summary)
        assertEquals("In Progress", result.issues[0].fields.status.name)
        assertEquals("High", result.issues[0].fields.priority?.name)
        assertEquals(1, result.issues[0].fields.issuelinks.size)
        assertEquals("TEAM-456", result.issues[0].fields.issuelinks[0].inwardIssue?.key)
    }

    @Test
    fun `deserialize single issue with links`() {
        val issue = json.decodeFromString<JiraIssue>(fixture("jira-issue-detail.json"))
        assertEquals("PROJ-123", issue.key)
        assertEquals("Jane Smith", issue.fields.reporter?.displayName)
        assertNotNull(issue.fields.description)
        assertEquals("Sprint 14", issue.fields.sprint?.name)
    }

    @Test
    fun `deserialize transitions response`() {
        val result = json.decodeFromString<JiraTransitionList>(fixture("jira-transitions.json"))
        assertEquals(3, result.transitions.size)
        assertEquals("In Progress", result.transitions[1].name)
        assertEquals("21", result.transitions[1].id)
    }
}
