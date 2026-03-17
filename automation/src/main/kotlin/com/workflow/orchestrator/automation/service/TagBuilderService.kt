package com.workflow.orchestrator.automation.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
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

    private val log = Logger.getInstance(TagBuilderService::class.java)
    private val bambooClient: BambooApiClient
    private val buildVariableName: String

    /** Project service constructor — used by IntelliJ DI. */
    constructor(project: Project) {
        val settings = PluginSettings.getInstance(project)
        val credentialStore = CredentialStore()
        this.bambooClient = BambooApiClient(
            baseUrl = settings.connections.bambooUrl.orEmpty().trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) },
            connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
            readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
        )
        this.buildVariableName = settings.state.bambooBuildVariableName ?: "dockerTagsAsJson"
        log.info("[Automation:Tags] TagBuilderService initialized, buildVariableName='$buildVariableName'")
    }

    /** Test constructor — allows injecting mocks. */
    constructor(bambooClient: BambooApiClient, buildVariableName: String = "dockerTagsAsJson") {
        this.bambooClient = bambooClient
        this.buildVariableName = buildVariableName
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val semverPattern = Regex("""^\d+\.\d+\.\d+.*$""")

    suspend fun scoreAndRankRuns(
        suitePlanKey: String,
        maxResults: Int = 10
    ): List<BaselineRun> {
        log.info("[Automation:Tags] Scoring and ranking runs for plan '$suitePlanKey', maxResults=$maxResults")
        val buildsResult = bambooClient.getRecentResults(suitePlanKey, maxResults)
        if (buildsResult !is ApiResult.Success) {
            log.info("[Automation:Tags] getRecentResults failed for '$suitePlanKey': $buildsResult")
            return emptyList()
        }

        log.info("[Automation:Tags] Found ${buildsResult.data.size} recent builds for '$suitePlanKey'")

        return buildsResult.data.mapNotNull { dto ->
            log.info("[Automation:Tags] Checking build #${dto.buildNumber} (key=${dto.key}, state=${dto.state})")
            val varsResult = bambooClient.getBuildVariables(dto.key)
            if (varsResult !is ApiResult.Success) {
                log.info("[Automation:Tags]   Build #${dto.buildNumber}: failed to get variables: $varsResult")
                return@mapNotNull null
            }

            log.info("[Automation:Tags]   Build #${dto.buildNumber}: variables=${varsResult.data.keys}")
            val dockerTagsJson = varsResult.data[buildVariableName]
            if (dockerTagsJson == null) {
                log.info("[Automation:Tags]   Build #${dto.buildNumber}: no '$buildVariableName' variable found")
                return@mapNotNull null
            }

            val tags = parseDockerTagsJson(dockerTagsJson)
            if (tags.isEmpty()) {
                log.info("[Automation:Tags]   Build #${dto.buildNumber}: dockerTagsJson parsed to empty map: ${dockerTagsJson.take(200)}")
                return@mapNotNull null
            }

            val releaseCount = tags.values.count { semverPattern.matches(it) }
            val successStages = dto.stages.stage.count { it.state.equals("Successful", ignoreCase = true) }
            val failedStages = dto.stages.stage.count { it.state.equals("Failed", ignoreCase = true) }
            val score = (releaseCount * 10) + (successStages * 5) - (failedStages * 20)

            log.info("[Automation:Tags]   Build #${dto.buildNumber}: ${tags.size} services, score=$score")

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
        }.sortedByDescending { it.score }.also { ranked ->
            log.info("[Automation:Tags] Scored ${ranked.size} baseline runs for plan '$suitePlanKey'")
        }
    }

    suspend fun loadBaseline(suitePlanKey: String): List<TagEntry> {
        log.info("[Automation:Tags] Loading baseline for plan '$suitePlanKey'")
        val ranked = scoreAndRankRuns(suitePlanKey)
        if (ranked.isEmpty()) {
            log.info("[Automation:Tags] No baseline runs found for plan '$suitePlanKey'")
            return emptyList()
        }

        val best = ranked[0]
        log.info("[Automation:Tags] Selected baseline run #${best.buildNumber} with ${best.totalServices} services, score=${best.score}")
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
        log.debug("[Automation:Tags] Replacing tag for service '${context.serviceName}' with feature branch tag '${context.featureBranchTag}'")
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
        val payload = JsonObject(map).toString()
        log.info("[Automation:Tags] Built JSON payload with ${entries.size} services, length=${payload.length}")
        log.debug("[Automation:Tags] JSON preview: ${payload.take(200)}${if (payload.length > 200) "..." else ""}")
        return payload
    }

    fun buildTriggerVariables(
        entries: List<TagEntry>,
        extraVars: Map<String, String>
    ): Map<String, String> {
        log.info("[Automation:Tags] Building trigger variables using variable name '$buildVariableName' with ${extraVars.size} extra vars")
        val result = mutableMapOf<String, String>()
        result[buildVariableName] = buildJsonPayload(entries)
        result.putAll(extraVars)
        return result
    }

    /**
     * Extract the current repo's docker tag from its CI build log.
     * Looks for "Unique Docker Tag : <tag>" in the log.
     */
    suspend fun extractDockerTagFromBuildLog(serviceCiPlanKey: String, branchName: String): String? {
        log.info("[Automation:Tags] Extracting docker tag from build log: plan=$serviceCiPlanKey, branch=$branchName")

        // Get latest build for this branch
        val buildResult = bambooClient.getLatestResult(serviceCiPlanKey, branchName)
        if (buildResult !is ApiResult.Success) {
            log.warn("[Automation:Tags] No build found for $serviceCiPlanKey/$branchName")
            return null
        }

        val resultKey = buildResult.data.key
        log.info("[Automation:Tags] Found build $resultKey, fetching log...")

        // Try to get the build log
        val logResult = bambooClient.getBuildLog(resultKey)
        if (logResult !is ApiResult.Success) {
            log.warn("[Automation:Tags] Failed to fetch build log for $resultKey")
            return null
        }

        // Extract docker tag from log
        val dockerTagRegex = Regex("Unique Docker Tag\\s*:\\s*(.+)")
        val match = dockerTagRegex.find(logResult.data)
        val tag = match?.groupValues?.get(1)?.trim()
            ?.replace(Regex("\\x1B\\[[0-9;]*m"), "") // Strip ANSI escape codes

        if (tag != null) {
            log.info("[Automation:Tags] Extracted docker tag: '$tag'")
        } else {
            log.warn("[Automation:Tags] 'Unique Docker Tag' not found in build log for $resultKey")
        }
        return tag
    }

    private fun parseDockerTagsJson(jsonStr: String): Map<String, String> {
        return try {
            val obj = json.decodeFromString<JsonObject>(jsonStr)
            val result = obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
            log.debug("[Automation:Tags] Parsed dockerTagsJson with ${result.size} entries")
            result
        } catch (e: Exception) {
            log.error("[Automation:Tags] Failed to parse dockerTagsJson: ${e.message}")
            emptyMap()
        }
    }
}
