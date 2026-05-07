package com.workflow.orchestrator.automation.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.http.HttpClientFactory
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.DockerRegistryUrls
import okhttp3.Request

/** Per-image validation outcome produced by [TagValidationService.validateTags]. */
data class TagValidationResult(
    /** Tags that were not found in the registry (404) or produced a connection error. */
    val missingTags: List<String>
) {
    val allPresent: Boolean get() = missingTags.isEmpty()
}

/**
 * Validates Docker image tags against the configured Nexus Docker registry using
 * HEAD requests to the Docker Registry v2 manifest endpoint.
 *
 * Auth: Nexus uses BASIC auth. Credentials are supplied via [CredentialStore.getNexusBasicAuthToken]
 * and injected into the shared [HttpClientFactory] so that Phase 3 caching can install
 * a single interceptor at [ServiceType.NEXUS] without touching this class.
 */
@Service(Service.Level.PROJECT)
class TagValidationService(private val project: Project) {

    private val log = Logger.getInstance(TagValidationService::class.java)

    private val httpClient by lazy {
        val settings = PluginSettings.getInstance(project)
        val credentialStore = CredentialStore()
        val nexusUsername = settings.connections.nexusUsername.orEmpty()
        val timeouts = HttpClientFactory.timeoutsFromSettings(project)
        HttpClientFactory(
            tokenProvider = { serviceType ->
                if (serviceType == ServiceType.NEXUS)
                    credentialStore.getNexusBasicAuthToken(nexusUsername)
                else null
            },
            connectTimeoutSeconds = timeouts.connectSeconds,
            readTimeoutSeconds = timeouts.readSeconds
        ).clientFor(ServiceType.NEXUS)
    }

    /**
     * Validates that each (imageName, tag) pair exists in the Nexus registry.
     *
     * @param registryUrl base URL of the Nexus Docker registry (e.g. `https://nexus.example.com`).
     *   For Nexus path-based repos, supply the host root here and the repo sub-path via [basePath].
     * @param basePath optional sub-path for Nexus path-based Docker repos
     *   (e.g. `/repository/docker-hosted`). Blank for port-based registries.
     * @param tags map of service name → docker tag to validate
     * @return [ToolResult] containing a [TagValidationResult]; [ToolResult.isError] is true only
     *         when the registry URL is blank (configuration error), not when tags are missing.
     */
    fun validateTags(
        registryUrl: String,
        tags: Map<String, String>,
        basePath: String = ""
    ): ToolResult<TagValidationResult> {
        if (registryUrl.isBlank()) {
            return ToolResult(
                data = TagValidationResult(emptyList()),
                summary = "Nexus registry URL is not configured.",
                isError = true
            )
        }

        val missingTags = mutableListOf<String>()

        for ((service, tag) in tags) {
            val url = DockerRegistryUrls.manifestUrl(registryUrl, basePath, service, tag)
            val request = Request.Builder()
                .url(url)
                .head()
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        log.warn("[Automation:TagValidation] Tag not found: $service:$tag")
                        missingTags.add("$service:$tag")
                    }
                }
            } catch (e: Exception) {
                log.warn("[Automation:TagValidation] Connection error checking $service:$tag: ${e.message}")
                missingTags.add("$service:$tag (connection error)")
            }
        }

        val result = TagValidationResult(missingTags)
        val summary = if (result.allPresent) {
            "All ${tags.size} Docker tag(s) validated successfully."
        } else {
            "Missing Docker tags: ${missingTags.joinToString(", ")}"
        }
        return ToolResult(data = result, summary = summary)
    }

    companion object {
        fun getInstance(project: Project): TagValidationService = project.service()
    }
}
