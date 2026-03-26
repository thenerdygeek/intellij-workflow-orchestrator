package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MentionSearchProviderTest {

    private val project = mockk<Project>(relaxed = true).apply {
        every { basePath } returns "/tmp/test-project"
    }

    @Test
    fun `categories returns all 5 types`() {
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
    fun `categories have required fields`() {
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
    fun `unknown type returns empty array`() {
        val provider = MentionSearchProvider(project)
        assertEquals("[]", provider.search("unknown", ""))
    }

    @Test
    fun `symbol search with short query returns empty`() {
        val provider = MentionSearchProvider(project)
        val json = provider.search("symbol", "a")
        assertEquals("[]", json)
    }

    @Test
    fun `file search with no roots returns empty array`() {
        val provider = MentionSearchProvider(project)
        val json = provider.search("file", "test")
        val arr = Json.parseToJsonElement(json).jsonArray
        // With a mock project, no content roots exist, so empty
        assertTrue(arr.isEmpty())
    }

    @Test
    fun `tool search returns empty when service unavailable`() {
        val provider = MentionSearchProvider(project)
        val json = provider.search("tool", "read")
        val arr = Json.parseToJsonElement(json).jsonArray
        // AgentService.getInstance() will throw on mock project
        assertTrue(arr.isEmpty())
    }

    @Test
    fun `skill search returns empty when service unavailable`() {
        val provider = MentionSearchProvider(project)
        val json = provider.search("skill", "debug")
        val arr = Json.parseToJsonElement(json).jsonArray
        assertTrue(arr.isEmpty())
    }
}
