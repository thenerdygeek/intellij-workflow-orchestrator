package com.workflow.orchestrator.automation.run

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TagValidationLogicTest {

    @Test
    fun `parses docker tags from JSON string`() {
        val json = """{"service-a": "1.2.3", "service-b": "4.5.6"}"""
        val tags = TagValidationLogic.parseDockerTags(json)
        assertEquals(2, tags.size)
        assertEquals("1.2.3", tags["service-a"])
        assertEquals("4.5.6", tags["service-b"])
    }

    @Test
    fun `returns empty map for invalid JSON`() {
        val tags = TagValidationLogic.parseDockerTags("not json")
        assertTrue(tags.isEmpty())
    }

    @Test
    fun `returns empty map for empty string`() {
        val tags = TagValidationLogic.parseDockerTags("")
        assertTrue(tags.isEmpty())
    }

    @Test
    fun `builds registry URL for tag check`() {
        val url = TagValidationLogic.buildManifestUrl("https://nexus.example.com", "myapp/service-a", "1.2.3")
        assertEquals("https://nexus.example.com/v2/myapp/service-a/manifests/1.2.3", url)
    }

    @Test
    fun `trims trailing slash from registry URL`() {
        val url = TagValidationLogic.buildManifestUrl("https://nexus.example.com/", "myapp/svc", "latest")
        assertEquals("https://nexus.example.com/v2/myapp/svc/manifests/latest", url)
    }
}
