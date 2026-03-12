package com.workflow.orchestrator.automation.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.automation.model.Conflict
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Service(Service.Level.PROJECT)
class ConflictDetectorService {

    private val bambooClient: BambooApiClient
    private val buildVariableName: String

    /** Project service constructor — used by IntelliJ DI. */
    constructor(project: Project) {
        val settings = PluginSettings.getInstance(project)
        val credentialStore = CredentialStore()
        this.bambooClient = BambooApiClient(
            baseUrl = settings.state.bambooUrl.orEmpty().trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) },
            connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
            readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
        )
        this.buildVariableName = settings.state.bambooBuildVariableName?.takeIf { it.isNotBlank() } ?: "dockerTagsAsJson"
    }

    /** Test constructor — allows injecting mocks. */
    constructor(bambooClient: BambooApiClient, buildVariableName: String = "dockerTagsAsJson") {
        this.bambooClient = bambooClient
        this.buildVariableName = buildVariableName
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkConflicts(
        suitePlanKey: String,
        stagedTags: Map<String, String>
    ): List<Conflict> {
        val buildsResult = bambooClient.getRunningAndQueuedBuilds(suitePlanKey)
        if (buildsResult !is ApiResult.Success) return emptyList()

        val conflicts = mutableListOf<Conflict>()

        for (build in buildsResult.data) {
            val varsResult = bambooClient.getBuildVariables(build.key)
            if (varsResult !is ApiResult.Success) continue

            val dockerTagsJson = varsResult.data[buildVariableName] ?: continue
            val otherTags = parseDockerTagsJson(dockerTagsJson)

            for ((service, yourTag) in stagedTags) {
                val otherTag = otherTags[service]
                if (otherTag != null && otherTag != yourTag) {
                    conflicts.add(
                        Conflict(
                            serviceName = service,
                            yourTag = yourTag,
                            otherTag = otherTag,
                            triggeredBy = varsResult.data["triggerUser"] ?: "unknown",
                            buildNumber = build.buildNumber,
                            isRunning = build.lifeCycleState == "InProgress"
                        )
                    )
                }
            }
        }

        return conflicts
    }

    private fun parseDockerTagsJson(jsonStr: String): Map<String, String> {
        return try {
            val obj = json.decodeFromString<JsonObject>(jsonStr)
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
