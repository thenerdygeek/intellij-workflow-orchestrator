package com.workflow.orchestrator.automation.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.automation.api.DockerRegistryClient
import com.workflow.orchestrator.automation.model.DriftResult
import com.workflow.orchestrator.automation.model.TagEntry
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings

@Service(Service.Level.PROJECT)
class DriftDetectorService {

    private val registryClient: DockerRegistryClient

    /** Project service constructor — used by IntelliJ DI. */
    constructor(project: Project) {
        val settings = PluginSettings.getInstance(project)
        val registryUrl = (settings.state.dockerRegistryUrl
            .takeUnless { it.isNullOrBlank() }
            ?: settings.state.nexusUrl.orEmpty()).trimEnd('/')
        val credentialStore = CredentialStore()
        this.registryClient = DockerRegistryClient(
            registryUrl = registryUrl,
            tokenProvider = { credentialStore.getToken(ServiceType.NEXUS) }
        )
    }

    /** Test constructor — allows injecting mocks. */
    constructor(registryClient: DockerRegistryClient) {
        this.registryClient = registryClient
    }
    private val semverPattern = Regex("""^\d+\.\d+\.\d+.*$""")

    suspend fun checkDrift(entries: List<TagEntry>): List<DriftResult> {
        return entries
            .filter { semverPattern.matches(it.currentTag) && !it.isCurrentRepo }
            .mapNotNull { entry ->
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
}
