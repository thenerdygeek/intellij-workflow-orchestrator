package com.workflow.orchestrator.automation.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.automation.model.Conflict
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Service(Service.Level.PROJECT)
class ConflictDetectorService {

    private val log = Logger.getInstance(ConflictDetectorService::class.java)
    private val bambooService: BambooService
    private val buildVariableName: String

    /** Project service constructor — used by IntelliJ DI. */
    constructor(project: Project) {
        val settings = PluginSettings.getInstance(project)
        this.bambooService = project.getService(BambooService::class.java)
        this.buildVariableName = settings.state.bambooBuildVariableName?.takeIf { it.isNotBlank() } ?: "dockerTagsAsJson"
    }

    /** Test constructor — allows injecting mocks. */
    constructor(bambooService: BambooService, buildVariableName: String = "dockerTagsAsJson") {
        this.bambooService = bambooService
        this.buildVariableName = buildVariableName
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkConflicts(
        suitePlanKey: String,
        stagedTags: Map<String, String>
    ): List<Conflict> {
        val buildsResult = bambooService.getRunningBuilds(suitePlanKey)
        if (buildsResult.isError) return emptyList()

        val conflicts = mutableListOf<Conflict>()

        for (build in buildsResult.data) {
            val varsResult = bambooService.getBuildVariables(build.buildResultKey)
            if (varsResult.isError) continue

            val variablesMap = varsResult.data.associate { it.name to it.value }
            val dockerTagsJson = variablesMap[buildVariableName] ?: continue
            val otherTags = parseDockerTagsJson(dockerTagsJson)

            for ((service, yourTag) in stagedTags) {
                val otherTag = otherTags[service]
                if (otherTag != null && otherTag != yourTag) {
                    conflicts.add(
                        Conflict(
                            serviceName = service,
                            yourTag = yourTag,
                            otherTag = otherTag,
                            triggeredBy = variablesMap["triggerUser"] ?: "unknown",
                            buildNumber = build.buildNumber,
                            isRunning = build.state == "InProgress" || build.state == "Unknown"
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
