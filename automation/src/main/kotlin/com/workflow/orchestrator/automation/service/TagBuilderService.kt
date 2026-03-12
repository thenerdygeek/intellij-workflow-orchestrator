package com.workflow.orchestrator.automation.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.automation.model.*
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Service(Service.Level.PROJECT)
class TagBuilderService {

    private val bambooClient: BambooApiClient

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
    }

    /** Test constructor — allows injecting mocks. */
    constructor(bambooClient: BambooApiClient) {
        this.bambooClient = bambooClient
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val semverPattern = Regex("""^\d+\.\d+\.\d+.*$""")

    suspend fun scoreAndRankRuns(
        suitePlanKey: String,
        maxResults: Int = 10
    ): List<BaselineRun> {
        val buildsResult = bambooClient.getRecentResults(suitePlanKey, maxResults)
        if (buildsResult !is ApiResult.Success) return emptyList()

        return buildsResult.data.mapNotNull { dto ->
            val varsResult = bambooClient.getBuildVariables(dto.key)
            if (varsResult !is ApiResult.Success) return@mapNotNull null

            val dockerTagsJson = varsResult.data["dockerTagsAsJson"] ?: return@mapNotNull null
            val tags = parseDockerTagsJson(dockerTagsJson)
            if (tags.isEmpty()) return@mapNotNull null

            val releaseCount = tags.values.count { semverPattern.matches(it) }
            val successStages = dto.stages.stage.count { it.state == "Successful" }
            val failedStages = dto.stages.stage.count { it.state == "Failed" }
            val score = (releaseCount * 10) + (successStages * 5) - (failedStages * 20)

            BaselineRun(
                buildNumber = dto.buildNumber,
                resultKey = dto.key,
                dockerTags = tags,
                releaseTagCount = releaseCount,
                totalServices = tags.size,
                successfulStages = successStages,
                failedStages = failedStages,
                triggeredAt = java.time.Instant.now(),
                score = score
            )
        }.sortedByDescending { it.score }
    }

    suspend fun loadBaseline(suitePlanKey: String): List<TagEntry> {
        val ranked = scoreAndRankRuns(suitePlanKey)
        if (ranked.isEmpty()) return emptyList()

        val best = ranked[0]
        return best.dockerTags.map { (service, tag) ->
            TagEntry(
                serviceName = service,
                currentTag = tag,
                latestReleaseTag = null,
                source = TagSource.BASELINE,
                registryStatus = RegistryStatus.UNKNOWN,
                isDrift = false,
                isCurrentRepo = false
            )
        }
    }

    fun replaceCurrentRepoTag(
        entries: List<TagEntry>,
        context: CurrentRepoContext
    ): List<TagEntry> {
        if (context.featureBranchTag == null) return entries
        return entries.map { entry ->
            if (entry.serviceName == context.serviceName) {
                entry.copy(
                    currentTag = context.featureBranchTag,
                    source = TagSource.AUTO_DETECTED,
                    isCurrentRepo = true
                )
            } else {
                entry
            }
        }
    }

    fun buildJsonPayload(entries: List<TagEntry>): String {
        val map = entries.associate { it.serviceName to JsonPrimitive(it.currentTag) }
        return JsonObject(map).toString()
    }

    fun buildTriggerVariables(
        entries: List<TagEntry>,
        extraVars: Map<String, String>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        result["dockerTagsAsJson"] = buildJsonPayload(entries)
        result.putAll(extraVars)
        return result
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
