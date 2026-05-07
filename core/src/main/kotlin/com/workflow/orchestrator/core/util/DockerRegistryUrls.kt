package com.workflow.orchestrator.core.util

/**
 * Shared helper for building Docker Registry v2 manifest and tag-list URLs.
 *
 * Nexus 3 path-based Docker repos are reachable at:
 *   <registryUrl>/<basePath>/v2/<imageName>/manifests/<tag>
 * where basePath is typically `/repository/<repo-name>` (non-empty for path-based) or
 * empty string for port-based registries where `/v2/` lives at the root.
 *
 * Reference: memory entry `reference_nexus3_url_conventions.md`.
 * All three former callers (DockerRegistryClient, TagValidationService, TagValidationLogic)
 * now delegate here so a single fix applies everywhere (A-P0-1 / A-P2-3).
 */
object DockerRegistryUrls {

    /**
     * Constructs the Docker Registry v2 manifest URL used for HEAD tag-existence checks.
     *
     * @param registryUrl Base URL of the Docker registry (e.g. `https://nexus.example.com`).
     *   Trailing slashes are stripped.
     * @param basePath Optional sub-path for path-based registries
     *   (e.g. `/repository/docker-hosted`). Leading slash is normalised; blank means
     *   port-based or root-based deployment.
     * @param imageName Image name / repository name (e.g. `myapp/service-a`).
     * @param tag Docker tag (e.g. `2.4.0`).
     */
    fun manifestUrl(
        registryUrl: String,
        basePath: String,
        imageName: String,
        tag: String
    ): String {
        val base = registryUrl.trimEnd('/')
        val path = normaliseBasePath(basePath)
        return "$base$path/v2/$imageName/manifests/$tag"
    }

    /**
     * Constructs the Docker Registry v2 tags-list URL for a given image.
     *
     * @param registryUrl Base URL of the Docker registry. Trailing slashes are stripped.
     * @param basePath Optional sub-path for path-based registries. Blank means root.
     * @param imageName Image name / repository name.
     * @param pageSize Number of tags to request per page (default 100).
     */
    fun tagsListUrl(
        registryUrl: String,
        basePath: String,
        imageName: String,
        pageSize: Int = 100
    ): String {
        val base = registryUrl.trimEnd('/')
        val path = normaliseBasePath(basePath)
        return "$base$path/v2/$imageName/tags/list?n=$pageSize"
    }

    /**
     * Constructs the Docker Registry v2 base-path prefix used for pagination `Link` headers.
     * The returned string is `<basePath>/v2` (e.g. `/repository/docker-hosted/v2`) or
     * simply `/v2` for root-based registries.
     */
    fun v2Prefix(basePath: String): String {
        val path = normaliseBasePath(basePath)
        return "$path/v2"
    }

    // ── Private ──────────────────────────────────────────────────────────────────

    /**
     * Normalises a base path:
     * - Blank / whitespace → empty string (root mode).
     * - Ensures a single leading slash and no trailing slash.
     */
    internal fun normaliseBasePath(basePath: String): String {
        val trimmed = basePath.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    }
}
