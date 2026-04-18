package com.workflow.orchestrator.automation.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.automation.api.DockerRegistryClient
import com.workflow.orchestrator.automation.model.DriftResult
import com.workflow.orchestrator.automation.model.TagEntry
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Service(Service.Level.PROJECT)
class DriftDetectorService {

    private val registryClient: DockerRegistryClient
    private val registryConfigured: Boolean

    /** Project service constructor — used by IntelliJ DI. */
    constructor(project: Project) {
        val settings = PluginSettings.getInstance(project)
        val registryUrl = (settings.state.dockerRegistryUrl
            .takeUnless { it.isNullOrBlank() }
            ?: settings.connections.nexusUrl.orEmpty()).trimEnd('/')
        this.registryConfigured = registryUrl.isNotBlank()
        val credentialStore = CredentialStore()
        val nexusUsername = settings.connections.nexusUsername.orEmpty()
        val timeouts = com.workflow.orchestrator.core.http.HttpClientFactory.timeoutsFromSettings(project)
        this.registryClient = DockerRegistryClient(
            registryUrl = registryUrl,
            tokenProvider = { credentialStore.getNexusBasicAuthToken(nexusUsername) },
            connectTimeoutSeconds = timeouts.connectSeconds,
            readTimeoutSeconds = timeouts.readSeconds
        )
    }

    /** Test constructor — allows injecting mocks. */
    constructor(registryClient: DockerRegistryClient) {
        this.registryClient = registryClient
        this.registryConfigured = true
    }
    private val semverPattern = Regex("""^\d+\.\d+\.\d+.*$""")
    private val concurrencyLimit = Semaphore(5)

    /** Whether the registry is configured (has a non-blank URL). */
    fun isRegistryConfigured(): Boolean = registryConfigured

    suspend fun checkDrift(entries: List<TagEntry>): List<DriftResult> {
        val eligible = entries.filter { semverPattern.matches(it.currentTag) && !it.isCurrentRepo }
        if (eligible.isEmpty()) return emptyList()

        // Fetch all registry tags in parallel (capped at 5 concurrent requests)
        return coroutineScope {
            eligible.map { entry ->
                async {
                    concurrencyLimit.withPermit {
                        val result = registryClient.getLatestReleaseTag(entry.serviceName)
                        val latestTag = (result as? ApiResult.Success)?.data
                        if (latestTag != null) {
                            DriftResult(
                                serviceName = entry.serviceName,
                                currentTag = entry.currentTag,
                                latestReleaseTag = latestTag,
                                isStale = entry.currentTag != latestTag
                            )
                        } else {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Fetches the latest release tag from the Docker registry for ALL services in the list.
     * Unlike [checkDrift], this checks every service regardless of current tag format.
     * Returns entries enriched with [TagEntry.latestReleaseTag] and [TagEntry.isDrift].
     */
    suspend fun enrichWithLatestReleaseTags(entries: List<TagEntry>): List<TagEntry> {
        if (!registryConfigured || entries.isEmpty()) return entries

        return coroutineScope {
            entries.map { entry ->
                async {
                    concurrencyLimit.withPermit {
                        val result = registryClient.getLatestReleaseTag(entry.serviceName)
                        val latestTag = (result as? ApiResult.Success)?.data
                        if (latestTag != null) {
                            val isStale = semverPattern.matches(entry.currentTag) &&
                                entry.currentTag != latestTag && !entry.isCurrentRepo
                            entry.copy(latestReleaseTag = latestTag, isDrift = isStale)
                        } else {
                            entry
                        }
                    }
                }
            }.awaitAll()
        }
    }
}
