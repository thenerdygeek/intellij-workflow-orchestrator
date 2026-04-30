package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.project.Project
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
}
