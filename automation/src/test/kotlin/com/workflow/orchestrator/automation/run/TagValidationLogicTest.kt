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

    // A-P0-1: path-based Nexus registry — buildManifestUrl with basePath

    @Test
    fun `builds registry URL for tag check — root (port-based)`() {
        val url = TagValidationLogic.buildManifestUrl("https://nexus.example.com", "myapp/service-a", "1.2.3")
        assertEquals("https://nexus.example.com/v2/myapp/service-a/manifests/1.2.3", url)
    }

    @Test
    fun `trims trailing slash from registry URL — root`() {
        val url = TagValidationLogic.buildManifestUrl("https://nexus.example.com/", "myapp/svc", "latest")
        assertEquals("https://nexus.example.com/v2/myapp/svc/manifests/latest", url)
    }

    @Test
    fun `buildManifestUrl with basePath includes repository sub-path (A-P0-1)`() {
        val url = TagValidationLogic.buildManifestUrl(
            registryUrl = "https://nexus.example.com",
            imageName = "myapp/service-auth",
            tag = "2.4.0",
            basePath = "/repository/docker-hosted"
        )
        assertEquals(
            "https://nexus.example.com/repository/docker-hosted/v2/myapp/service-auth/manifests/2.4.0",
            url
        )
    }

    @Test
    fun `buildManifestUrl with basePath missing leading slash normalises it`() {
        val url = TagValidationLogic.buildManifestUrl(
            registryUrl = "https://nexus.example.com",
            imageName = "svc",
            tag = "1.0",
            basePath = "repository/docker-hosted"
        )
        assertEquals(
            "https://nexus.example.com/repository/docker-hosted/v2/svc/manifests/1.0",
            url
        )
    }

    // A-P0-3: extractDockerTagsJson case-insensitive lookup

    @Test
    fun `extractDockerTagsJson matches key case-insensitively — DockerTagsAsJSON (live Bamboo casing)`() {
        // Probe-confirmed variable name: DockerTagsAsJSON
        val buildVars = """{"DockerTagsAsJSON": "{\"svc-a\": \"1.0.0\"}", "other": "value"}"""
        val result = TagValidationLogic.extractDockerTagsJson(buildVars, "DockerTagsAsJSON")
        assertEquals("""{"svc-a": "1.0.0"}""", result)
    }

    @Test
    fun `extractDockerTagsJson matches DockerTagsAsJSON when configured name is lowercase`() {
        // User-configured name is lowercase but Bamboo plan has mixed case — must still match
        val buildVars = """{"DockerTagsAsJSON": "{\"svc-a\": \"1.0.0\"}"}"""
        val result = TagValidationLogic.extractDockerTagsJson(buildVars, "dockertagsasjson")
        assertEquals("""{"svc-a": "1.0.0"}""", result)
    }

    @Test
    fun `extractDockerTagsJson matches lowercase key when configured name has different casing`() {
        val buildVars = """{"dockerTagsAsJson": "{\"svc-a\": \"1.0.0\"}"}"""
        val result = TagValidationLogic.extractDockerTagsJson(buildVars, "DockerTagsAsJSON")
        assertEquals("""{"svc-a": "1.0.0"}""", result)
    }

    @Test
    fun `extractDockerTagsJson returns empty when key absent`() {
        val buildVars = """{"someOtherKey": "value"}"""
        val result = TagValidationLogic.extractDockerTagsJson(buildVars, "DockerTagsAsJSON")
        assertEquals("", result)
    }

    @Test
    fun `extractDockerTagsJson returns empty for malformed build variables JSON`() {
        val result = TagValidationLogic.extractDockerTagsJson("not valid json {", "DockerTagsAsJSON")
        assertEquals("", result)
    }

    @Test
    fun `extractDockerTagsJson returns empty for blank build variables`() {
        val result = TagValidationLogic.extractDockerTagsJson("", "DockerTagsAsJSON")
        assertEquals("", result)
    }

    // Legacy single-arg overload (backward-compat)
    @Test
    fun `extractDockerTagsJson default varName still works for mixed-case key`() {
        val buildVars = """{"DockerTagsAsJson": "{\"svc\": \"1.0.0\"}"}"""
        val result = TagValidationLogic.extractDockerTagsJson(buildVars)
        assertEquals("""{"svc": "1.0.0"}""", result)
    }
}
