package com.workflow.orchestrator.automation.ui

import com.workflow.orchestrator.automation.model.RegistryStatus
import com.workflow.orchestrator.automation.model.TagSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DockerTagsJsonParserTest {

    private val parser = TagStagingPanel.DockerTagsJsonParser

    @Test
    fun `parse returns entries for valid dockerTagsAsJson object`() {
        val json = """{"auth":"2.4.0","payments":"2.3.1","user":"1.9.0"}"""

        val result = parser.parse(json)

        assertNotNull(result)
        assertEquals(3, result!!.size)
        val auth = result.first { it.serviceName == "auth" }
        assertEquals("2.4.0", auth.currentTag)
        assertEquals(TagSource.USER_EDIT, auth.source)
        assertEquals(RegistryStatus.UNKNOWN, auth.registryStatus)
        assertFalse(auth.isDrift)
        assertFalse(auth.isCurrentRepo)
        assertNull(auth.latestReleaseTag)
    }

    @Test
    fun `parse returns empty list for empty JSON object`() {
        val result = parser.parse("{}")

        assertNotNull(result)
        assertTrue(result!!.isEmpty())
    }

    @Test
    fun `parse returns null for plain non-JSON string`() {
        assertNull(parser.parse("not-json-at-all"))
    }

    @Test
    fun `parse returns null for a JSON array`() {
        // Top-level array is not a JSON object — Paste should reject it
        assertNull(parser.parse("""["auth","payments"]"""))
    }

    @Test
    fun `parse returns null for a JSON string primitive`() {
        assertNull(parser.parse(""""just a string""""))
    }

    @Test
    fun `parse returns null for blank input`() {
        assertNull(parser.parse(""))
        assertNull(parser.parse("   "))
    }

    @Test
    fun `parse returns null when value is not a primitive`() {
        // Nested object values are not valid in dockerTagsAsJson
        val json = """{"auth":{"tag":"2.4.0"}}"""
        // kotlinx jsonPrimitive throws on object — parser should return null
        assertNull(parser.parse(json))
    }

    @Test
    fun `parse preserves service names with hyphens and dots`() {
        val json = """{"my-service":"1.0.0","org.example.svc":"2.0.0-beta"}"""

        val result = parser.parse(json)

        assertNotNull(result)
        assertEquals(2, result!!.size)
        assertTrue(result.any { it.serviceName == "my-service" && it.currentTag == "1.0.0" })
        assertTrue(result.any { it.serviceName == "org.example.svc" && it.currentTag == "2.0.0-beta" })
    }

    @Test
    fun `parse single-entry object`() {
        val result = parser.parse("""{"svc":"feature-branch-abc123"}""")

        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals("svc", result[0].serviceName)
        assertEquals("feature-branch-abc123", result[0].currentTag)
    }
}
