package com.workflow.orchestrator.core.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * A-P0-1 / A-P2-3: Verifies the shared Docker registry URL helper used by
 * DockerRegistryClient, TagValidationService, and TagValidationLogic.
 *
 * The user's Nexus uses path-based Docker routing where the manifest URL is:
 *   <registryUrl>/repository/<repo-name>/v2/<image>/manifests/<tag>
 *
 * Reference: reference_nexus3_url_conventions.md
 */
class DockerRegistryUrlsTest {

    // ── manifestUrl ───────────────────────────────────────────────────────────

    @Test
    fun `manifestUrl — blank basePath produces root v2 path (port-based registry)`() {
        val url = DockerRegistryUrls.manifestUrl(
            registryUrl = "https://registry.example.com",
            basePath = "",
            imageName = "service-auth",
            tag = "2.4.0"
        )
        assertEquals("https://registry.example.com/v2/service-auth/manifests/2.4.0", url)
    }

    @Test
    fun `manifestUrl — Nexus path-based basePath includes repository sub-path (A-P0-1)`() {
        val url = DockerRegistryUrls.manifestUrl(
            registryUrl = "https://nexus.example.com",
            basePath = "/repository/docker-hosted",
            imageName = "myapp/service-auth",
            tag = "2.4.0"
        )
        assertEquals(
            "https://nexus.example.com/repository/docker-hosted/v2/myapp/service-auth/manifests/2.4.0",
            url
        )
    }

    @Test
    fun `manifestUrl — trailing slash on registryUrl is stripped`() {
        val url = DockerRegistryUrls.manifestUrl(
            registryUrl = "https://nexus.example.com/",
            basePath = "/repository/docker-group",
            imageName = "svc",
            tag = "1.0.0"
        )
        assertEquals("https://nexus.example.com/repository/docker-group/v2/svc/manifests/1.0.0", url)
    }

    @Test
    fun `manifestUrl — basePath missing leading slash is normalised`() {
        val url = DockerRegistryUrls.manifestUrl(
            registryUrl = "https://nexus.example.com",
            basePath = "repository/docker-hosted",
            imageName = "svc",
            tag = "latest"
        )
        assertEquals("https://nexus.example.com/repository/docker-hosted/v2/svc/manifests/latest", url)
    }

    @Test
    fun `manifestUrl — basePath with trailing slash is stripped`() {
        val url = DockerRegistryUrls.manifestUrl(
            registryUrl = "https://nexus.example.com",
            basePath = "/repository/docker-hosted/",
            imageName = "svc",
            tag = "1.0"
        )
        assertEquals("https://nexus.example.com/repository/docker-hosted/v2/svc/manifests/1.0", url)
    }

    // ── tagsListUrl ───────────────────────────────────────────────────────────

    @Test
    fun `tagsListUrl — blank basePath produces root tags path`() {
        val url = DockerRegistryUrls.tagsListUrl(
            registryUrl = "https://registry.example.com",
            basePath = "",
            imageName = "service-auth"
        )
        assertEquals("https://registry.example.com/v2/service-auth/tags/list?n=100", url)
    }

    @Test
    fun `tagsListUrl — path-based basePath includes repository prefix`() {
        val url = DockerRegistryUrls.tagsListUrl(
            registryUrl = "https://nexus.example.com",
            basePath = "/repository/docker-hosted",
            imageName = "service-auth",
            pageSize = 50
        )
        assertEquals(
            "https://nexus.example.com/repository/docker-hosted/v2/service-auth/tags/list?n=50",
            url
        )
    }

    // ── normaliseBasePath (internal) ──────────────────────────────────────────

    @Test
    fun `normaliseBasePath — blank returns empty`() {
        assertEquals("", DockerRegistryUrls.normaliseBasePath(""))
        assertEquals("", DockerRegistryUrls.normaliseBasePath("   "))
    }

    @Test
    fun `normaliseBasePath — adds leading slash if missing`() {
        assertEquals("/repository/docker-hosted", DockerRegistryUrls.normaliseBasePath("repository/docker-hosted"))
    }

    @Test
    fun `normaliseBasePath — strips trailing slash`() {
        assertEquals("/repository/docker-hosted", DockerRegistryUrls.normaliseBasePath("/repository/docker-hosted/"))
    }

    @Test
    fun `normaliseBasePath — leaves well-formed path unchanged`() {
        assertEquals("/repository/docker-hosted", DockerRegistryUrls.normaliseBasePath("/repository/docker-hosted"))
    }
}
