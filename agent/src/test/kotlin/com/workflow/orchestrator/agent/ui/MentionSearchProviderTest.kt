package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.jira.IssueSuggestion
import com.workflow.orchestrator.core.model.jira.JiraTicketData
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MentionSearchProviderTest {

    private val project = mockk<Project>(relaxed = true).apply {
        every { basePath } returns "/tmp/test-project"
    }

    /**
     * Helper: build a [JiraTicketData] with only the fields the sprint walk reads,
     * defaulting everything else.
     */
    private fun ticket(key: String, summary: String, status: String = "To Do") = JiraTicketData(
        key = key,
        summary = summary,
        status = status,
        assignee = null,
        reporter = null,
        type = "Task",
        priority = null,
        description = null
    )

    @Test
    fun `categories returns all 5 types`() = runTest {
        val provider = MentionSearchProvider(project)
        val json = provider.search("categories", "")
        val arr = Json.parseToJsonElement(json).jsonArray
        assertEquals(5, arr.size)
        val types = arr.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertTrue("file" in types)
        assertTrue("folder" in types)
        assertTrue("symbol" in types)
        assertTrue("tool" in types)
        assertTrue("skill" in types)
    }

    @Test
    fun `categories have required fields`() = runTest {
        val provider = MentionSearchProvider(project)
        val json = provider.search("categories", "")
        val arr = Json.parseToJsonElement(json).jsonArray
        for (item in arr) {
            val obj = item.jsonObject
            assertNotNull(obj["type"])
            assertNotNull(obj["icon"])
            assertNotNull(obj["label"])
            assertNotNull(obj["description"])
        }
    }

    @Test
    fun `unknown type returns empty array`() = runTest {
        val provider = MentionSearchProvider(project)
        assertEquals("[]", provider.search("unknown", ""))
    }

    @Test
    fun `symbol search with short query returns empty`() = runTest {
        val provider = MentionSearchProvider(project)
        val json = provider.search("symbol", "a")
        assertEquals("[]", json)
    }

    @Test
    fun `file search with no roots returns empty array`() = runTest {
        val provider = MentionSearchProvider(project)
        val json = provider.search("file", "test")
        val arr = Json.parseToJsonElement(json).jsonArray
        // With a mock project, no content roots exist, so empty
        assertTrue(arr.isEmpty())
    }

    @Test
    fun `tool search returns empty when service unavailable`() = runTest {
        val provider = MentionSearchProvider(project)
        val json = provider.search("tool", "read")
        val arr = Json.parseToJsonElement(json).jsonArray
        // AgentService.getInstance() will throw on mock project
        assertTrue(arr.isEmpty())
    }

    @Test
    fun `blank ticket query does not short-circuit to a single item`() = runTest {
        // Regression: previously `searchTickets("")` returned only the
        // workflow-context active ticket (1 item), starving the dropdown of the
        // sprint list. With no JiraService wired on the mock project the call
        // must yield an empty array — never a stale 1-item response — so the
        // user-visible "only one item" symptom can't recur.
        val provider = MentionSearchProvider(project)
        val json = provider.searchTickets("")
        val arr = Json.parseToJsonElement(json).jsonArray
        assertTrue(arr.isEmpty(), "blank query without Jira service should return empty, got: $json")
    }

    @Test
    fun `active and current literal keywords do not crash without active ticket`() = runTest {
        // The literal-keyword path is still exposed for spec §6.2 (single-hit fallback).
        // With no WorkflowContextService configured on the mock project the call must
        // gracefully fall through to the empty sprint-search result, NOT throw.
        val provider = MentionSearchProvider(project)
        for (kw in listOf("active", "current", "ACTIVE", "Current")) {
            val json = provider.searchTickets(kw)
            val arr = Json.parseToJsonElement(json).jsonArray
            assertTrue(arr.isEmpty(), "searchTickets(\"$kw\") with no service should be empty, got: $json")
        }
    }

    @Test
    fun `non-blank text query does not engage the active-ticket fallback`() = runTest {
        // A regular text query (e.g. typing "PROJ" or "auth") must not be confused with
        // the literal-keyword fallback path; otherwise users typing a partial key would
        // see the active ticket inserted as a phantom result.
        val provider = MentionSearchProvider(project)
        val json = provider.searchTickets("PROJ")
        val arr = Json.parseToJsonElement(json).jsonArray
        assertTrue(arr.isEmpty(), "text query without Jira service should be empty, got: $json")
    }

    @Test
    fun `skill search returns matching skills from bundled resources`() = runTest {
        val provider = MentionSearchProvider(project)
        val json = provider.search("skill", "debug")
        val arr = Json.parseToJsonElement(json).jsonArray
        // Should find debugging-related skills (systematic-debugging, interactive-debugging)
        assertTrue(arr.isNotEmpty(), "should find debugging skills from bundled resources")
        for (item in arr) {
            val obj = item.jsonObject
            assertEquals("skill", obj["type"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `non-blank query routes through issue picker and tags source as issue_picker`() = runTest {
        // R-SWAP-2: when getIssueSuggestions returns suggestions, the dropdown JSON
        // should carry those exact ticket keys with source = "issue_picker".
        val pickerProject = mockk<Project>(relaxed = true).apply {
            every { basePath } returns "/tmp/test-project"
        }
        val jiraService = mockk<JiraService>(relaxed = true)
        val suggestions = listOf(
            IssueSuggestion(key = "PROJ-1", summary = "Fix login bug", summaryText = "Fix login bug"),
            IssueSuggestion(key = "PROJ-2", summary = "Refactor auth", summaryText = "Refactor auth"),
            IssueSuggestion(key = "PROJ-3", summary = "Add 2FA", summaryText = "Add 2FA")
        )
        coEvery { jiraService.getIssueSuggestions("PROJ-1") } returns ToolResult(
            data = suggestions,
            summary = "3 suggestions"
        )
        every { pickerProject.getService(JiraService::class.java) } returns jiraService

        val provider = MentionSearchProvider(pickerProject)
        val json = provider.searchTickets("PROJ-1")
        val arr = Json.parseToJsonElement(json).jsonArray

        assertEquals(3, arr.size, "expected all 3 issue-picker suggestions, got: $json")
        val keys = arr.map { it.jsonObject["label"]?.jsonPrimitive?.content }
        assertEquals(listOf("PROJ-1", "PROJ-2", "PROJ-3"), keys)
        for (item in arr) {
            val obj = item.jsonObject
            assertEquals("ticket", obj["type"]?.jsonPrimitive?.content)
            assertEquals("issue_picker", obj["source"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `issue picker error falls back to sprint walk filter`() = runTest {
        // R-SWAP-2: when getIssueSuggestions returns isError = true, the existing
        // sprint-cache filter logic must run and serve results from there.
        val fallbackProject = mockk<Project>(relaxed = true).apply {
            every { basePath } returns "/tmp/test-project"
        }
        val jiraService = mockk<JiraService>(relaxed = true)
        coEvery { jiraService.getIssueSuggestions("auth") } returns ToolResult(
            data = emptyList(),
            summary = "endpoint unavailable",
            isError = true
        )
        every { fallbackProject.getService(JiraService::class.java) } returns jiraService

        val provider = MentionSearchProvider(fallbackProject)
        // Pre-populate the sprint cache so the fallback has data to serve without
        // exercising the board/sprint discovery branch (PluginSettings is hard to
        // mock in a unit test, and the filter logic is what we want to verify).
        provider.onSprintDataLoaded(listOf(
            ticket("AUTH-10", "Auth: rotate tokens", status = "In Progress"),
            ticket("AUTH-11", "Auth: refresh login", status = "To Do"),
            ticket("MISC-99", "Unrelated work", status = "Done")
        ))

        val json = provider.searchTickets("auth")
        val arr = Json.parseToJsonElement(json).jsonArray

        // Both AUTH tickets match "auth" (case-insensitive), MISC-99 should not.
        assertEquals(2, arr.size, "expected 2 sprint-walk matches, got: $json")
        val keys = arr.map { it.jsonObject["label"]?.jsonPrimitive?.content }.toSet()
        assertEquals(setOf("AUTH-10", "AUTH-11"), keys)
        // Sprint-walk semantics: ticket entries do NOT carry a source field
        // (only the workflow_context_active pinned entry sets one). Preserve that.
        for (item in arr) {
            val obj = item.jsonObject
            assertEquals("ticket", obj["type"]?.jsonPrimitive?.content)
            assertNull(obj["source"], "sprint-walk results must not carry a source field — got: $obj")
        }
    }
}
