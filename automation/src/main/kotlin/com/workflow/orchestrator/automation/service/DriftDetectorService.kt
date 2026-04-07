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

    /** Project service constructor — used by IntelliJ DI. */
    constructor(project: Project) {
        val settings = PluginSettings.getInstance(project)
        val registryUrl = (settings.state.dockerRegistryUrl
            .takeUnless { it.isNullOrBlank() }
            ?: settings.connections.nexusUrl.orEmpty()).trimEnd('/')
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
    }
    private val semverPattern = Regex("""^\d+\.\d+\.\d+.*$""")
    private val concurrencyLimit = Semaphore(5)

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
}
